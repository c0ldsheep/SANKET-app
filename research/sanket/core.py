"""SANKET core: dependency-free streaming link-loss detectors.

Everything here uses only the Python standard library so the exact same code can run
inside a phone (Termux), be ported line-by-line to Kotlin/Java/JavaScript, and be used
by the offline research pipeline. Golden test vectors check that the ports agree.

Input model (mirrors Android)
-----------------------------
Android delivers LTE signal readings as integers: RSRP in [-140, -43] dBm, RSRQ in
[-34, 3] dB, anything else is ``CellInfo.UNAVAILABLE`` (AOSP CellSignalStrengthLte).
Callbacks are event-driven, and loggers that poll at 1 Hz see the same value repeated
while the modem has not refreshed it (about 60 % of 1 Hz samples in the public UCC
traces are repeats). ``StreamAdapter`` therefore turns a polled log back into
measurement events: repeats are treated as stale unless they persist for
``heartbeat_s`` seconds, which is then accepted as a fresh "still the same" reading.

Decision model (shared by all detectors)
----------------------------------------
Every tick (normally 1 s) a detector evaluates a boolean *condition*. An alarm fires when
the condition has held for ``persistence`` consecutive ticks and at least ``cooldown_s``
seconds have passed since the previous alarm. An alarm means "prefetch the delivery
manifest now". Unavailable readings (no LTE / no service) reset the signal state.
"""
from __future__ import annotations

import math
from collections import deque
from statistics import NormalDist

RSRP_MIN, RSRP_MAX = -140.0, -43.0
RSRQ_MIN, RSRQ_MAX = -34.0, 3.0

# StreamAdapter statuses
UNAVAILABLE, STALE, NEW, FIRST = 0, 1, 2, 3


def _finite(x) -> bool:
    return x is not None and isinstance(x, (int, float)) and math.isfinite(x)


def valid_rsrp(x) -> bool:
    return _finite(x) and RSRP_MIN <= x <= RSRP_MAX


def valid_rsrq(x) -> bool:
    return _finite(x) and RSRQ_MIN <= x <= RSRQ_MAX


def z_for_probability(p: float) -> float:
    """Standard-normal quantile. P(X <= theta) >= p  <=>  theta - mean >= z * sd.

    Wichura's AS241 rational approximation, written out in plain Python (the same steps as
    CPython's pure-Python ``statistics`` fallback). CPython's C version may fuse multiply-adds
    and differ in the last bit, so the JavaScript and Java ports copy *this* version and all
    three implementations agree exactly.
    """
    if not 0.0 < p < 1.0:
        raise ValueError("probability must be in (0, 1)")
    q = p - 0.5
    if abs(q) <= 0.425:
        r = 0.180625 - q * q
        num = (((((((2.5090809287301226727e+3 * r + 3.3430575583588128105e+4) * r + 6.7265770927008700853e+4) * r
                    + 4.5921953931549871457e+4) * r + 1.3731693765509461125e+4) * r + 1.9715909503065514427e+3) * r
                + 1.3314166789178437745e+2) * r + 3.3871328727963666080e+0) * q
        den = (((((((5.2264952788528545610e+3 * r + 2.8729085735721942674e+4) * r + 3.9307895800092710610e+4) * r
                    + 2.1213794301586595867e+4) * r + 5.3941960214247511077e+3) * r + 6.8718700749205790830e+2) * r
                + 4.2313330701600911252e+1) * r + 1.0)
        return num / den
    r = p if q <= 0.0 else 1.0 - p
    r = math.sqrt(-math.log(r))
    if r <= 5.0:
        r = r - 1.6
        num = (((((((7.74545014278341407640e-4 * r + 2.27238449892691845833e-2) * r + 2.41780725177450611770e-1) * r
                    + 1.27045825245236838258e+0) * r + 3.64784832476320460504e+0) * r + 5.76949722146069140550e+0) * r
                + 4.63033784615654529590e+0) * r + 1.42343711074968357734e+0)
        den = (((((((1.05075007164441684324e-9 * r + 5.47593808499534494600e-4) * r + 1.51986665636164571966e-2) * r
                    + 1.48103976427480074590e-1) * r + 6.89767334985100004550e-1) * r + 1.67638483018380384940e+0) * r
                + 2.05319162663775882187e+0) * r + 1.0)
    else:
        r = r - 5.0
        num = (((((((2.01033439929228813265e-7 * r + 2.71155556874348757815e-5) * r + 1.24266094738807843860e-3) * r
                    + 2.65321895265761230930e-2) * r + 2.96560571828504891230e-1) * r + 1.78482653991729133580e+0) * r
                + 5.46378491116411436990e+0) * r + 6.65790464350110377720e+0)
        den = (((((((2.04426310338993978564e-15 * r + 1.42151175831644588870e-7) * r + 1.84631831751005468180e-5) * r
                    + 7.86869131145613259100e-4) * r + 1.48753612908506148525e-2) * r + 1.36929880922735805310e-1) * r
                + 5.99832206555887937690e-1) * r + 1.0)
    x = num / den
    return -x if q < 0.0 else x


class StreamAdapter:
    """Converts polled samples into measurement events (see module docstring).

    A reading that is briefly unavailable (a logger glitch such as -200 dBm) is treated as
    "no news" for up to ``grace_s`` seconds; only a longer outage resets the state.
    """

    __slots__ = ("heartbeat_s", "dedupe", "reset_on_cell_change", "grace_s", "_last", "_t_last", "_cell",
                 "_t_bad")

    def __init__(self, heartbeat_s: float = 4.0, dedupe: bool = True, reset_on_cell_change: bool = False,
                 grace_s: float = 3.0):
        if heartbeat_s <= 0 or grace_s < 0:
            raise ValueError("heartbeat_s must be positive and grace_s non-negative")
        self.heartbeat_s = float(heartbeat_s)
        self.dedupe = bool(dedupe)
        self.reset_on_cell_change = bool(reset_on_cell_change)
        self.grace_s = float(grace_s)
        self.reset()

    def reset(self) -> None:
        self._last = None
        self._t_last = None
        self._cell = None
        self._t_bad = None

    def push(self, t: float, rsrp, rsrq=None, cell=None) -> int:
        if not valid_rsrp(rsrp):
            if self._last is not None:
                if self._t_bad is None:
                    self._t_bad = float(t)
                if t - self._t_bad < self.grace_s:
                    return STALE
            self.reset()
            return UNAVAILABLE
        self._t_bad = None
        key = (float(rsrp), float(rsrq) if valid_rsrq(rsrq) else None)
        if self._last is None:
            status = FIRST
        elif (self.reset_on_cell_change and cell is not None and self._cell is not None
              and cell != self._cell):
            status = FIRST
        elif self.dedupe and key == self._last and (t - self._t_last) < self.heartbeat_s:
            return STALE
        else:
            status = NEW
        self._last = key
        self._t_last = float(t)
        if cell is not None:
            self._cell = cell
        return status


class LocalLinearTrend:
    """Kalman filter for a signal level with a slowly varying rate (dB, dB/s).

    State x = [level, rate]. Process model: level' = level + rate*dt; the rate follows a
    random walk driven by white "acceleration" noise of spectral density ``q`` (dB^2/s^3),
    discretised exactly:  Q = q * [[dt^3/3, dt^2/2], [dt^2/2, dt]].
    Measurement: z = level + v, v ~ N(0, meas_sd^2). Large innovations are down-weighted
    (normalised-innovation gating) so one glitchy reading cannot fake a cliff.
    """

    __slots__ = ("meas_var", "q", "rate0_var", "gate", "level", "rate", "p00", "p01", "p11", "t", "n")

    def __init__(self, meas_sd: float = 2.0, q: float = 0.2, rate0_sd: float = 1.0, gate: float = 3.5):
        if meas_sd <= 0 or q <= 0 or rate0_sd <= 0:
            raise ValueError("meas_sd, q and rate0_sd must be positive")
        self.meas_var = float(meas_sd) ** 2
        self.q = float(q)
        self.rate0_var = float(rate0_sd) ** 2
        self.gate = float(gate) if gate else 0.0
        self.reset()

    def reset(self) -> None:
        self.level = self.rate = 0.0
        self.p00 = self.p01 = self.p11 = 0.0
        self.t = None
        self.n = 0

    @property
    def ready(self) -> bool:
        return self.n > 0

    def init(self, t: float, z: float) -> None:
        self.level = float(z)
        self.rate = 0.0
        self.p00 = self.meas_var
        self.p01 = 0.0
        self.p11 = self.rate0_var
        self.t = float(t)
        self.n = 1

    def predict_to(self, t: float) -> None:
        if self.t is None:
            return
        dt = float(t) - self.t
        if dt <= 0.0:
            return
        q = self.q
        p00, p01, p11 = self.p00, self.p01, self.p11
        self.level = self.level + self.rate * dt
        self.p00 = p00 + 2.0 * dt * p01 + dt * dt * p11 + q * dt * dt * dt / 3.0
        self.p01 = p01 + dt * p11 + q * dt * dt / 2.0
        self.p11 = p11 + q * dt
        self.t = float(t)

    def update(self, t: float, z: float) -> None:
        if self.n == 0:
            self.init(t, z)
            return
        self.predict_to(t)
        r = self.meas_var
        s = self.p00 + r
        y = float(z) - self.level
        if self.gate > 0.0:
            nis = y * y / s
            g2 = self.gate * self.gate
            if nis > g2:
                r = r * nis / g2
                s = self.p00 + r
        k0 = self.p00 / s
        k1 = self.p01 / s
        p00, p01, p11 = self.p00, self.p01, self.p11
        self.level = self.level + k0 * y
        self.rate = self.rate + k1 * y
        self.p11 = p11 - k1 * p01
        self.p01 = (1.0 - k0) * p01
        self.p00 = (1.0 - k0) * p00
        self.n += 1


def forecast(level: float, rate: float, p00: float, p01: float, p11: float, q: float, h: float):
    """Mean and standard deviation of the level h seconds ahead (same formula as the numpy path)."""
    mean = level + rate * h
    var = p00 + 2.0 * h * p01 + h * h * p11 + q * h * h * h / 3.0
    return mean, math.sqrt(var if var > 0.0 else 0.0)


# --------------------------------------------------------------------------------------
# Detectors
# --------------------------------------------------------------------------------------
class Detector:
    """Common tick / persistence / cooldown machinery."""

    name = "base"

    def __init__(self, persistence: int = 2, cooldown_s: float = 60.0, warmup: int = 3,
                 heartbeat_s: float = 4.0, dedupe: bool = True, reset_on_cell_change: bool = False,
                 grace_s: float = 3.0):
        if persistence < 1 or warmup < 1 or cooldown_s < 0:
            raise ValueError("persistence and warmup must be >= 1 and cooldown_s >= 0")
        self.persistence = int(persistence)
        self.cooldown_s = float(cooldown_s)
        self.warmup = int(warmup)
        self.adapter = StreamAdapter(heartbeat_s, dedupe, reset_on_cell_change, grace_s)
        self.reset()

    # -- life cycle -------------------------------------------------------------------
    def reset(self) -> None:
        self.adapter.reset()
        self.n_meas = 0
        self.run = 0
        self.last_alarm = None
        self.last_condition = False
        self._reset_signal()

    def _reset_signal(self) -> None:  # pragma: no cover - overridden
        pass

    def _on_measurement(self, t: float, rsrp: float, rsrq) -> None:  # pragma: no cover
        pass

    def _on_tick(self, t: float, nbr) -> None:
        pass

    def _condition(self, t: float) -> bool:  # pragma: no cover
        return False

    # -- main entry point -------------------------------------------------------------
    def step(self, t: float, rsrp, rsrq=None, nbr=None, cell=None) -> bool:
        """Feed one polled sample. Returns True when a prefetch alarm fires at this tick."""
        status = self.adapter.push(t, rsrp, rsrq, cell)
        if status == UNAVAILABLE:
            if self.n_meas:
                self._reset_signal()
            self.n_meas = 0
            self.run = 0
            self.last_condition = False
            return False
        if status == FIRST:
            self._reset_signal()
            self.n_meas = 0
        if status in (FIRST, NEW):
            self._on_measurement(float(t), float(rsrp), float(rsrq) if valid_rsrq(rsrq) else None)
            self.n_meas += 1
        self._on_tick(float(t), nbr)
        cond = self.n_meas >= self.warmup and self._condition(float(t))
        self.last_condition = cond
        self.run = self.run + 1 if cond else 0
        if self.run >= self.persistence and (self.last_alarm is None or t - self.last_alarm >= self.cooldown_s):
            self.last_alarm = float(t)
            return True
        return False

    def snapshot(self) -> dict:
        """Internal numbers after the last step (used by the bulk evaluator and plots)."""
        return {"n": self.n_meas}


class ThresholdDetector(Detector):
    """Baseline: alarm when the RSRP reading is at or below a fixed level."""

    name = "threshold"

    def __init__(self, theta: float = -110.0, **kw):
        self.theta = float(theta)
        kw.setdefault("warmup", 1)
        super().__init__(**kw)

    def _reset_signal(self) -> None:
        self.z = float("nan")

    def _on_measurement(self, t, rsrp, rsrq) -> None:
        self.z = rsrp

    def _condition(self, t) -> bool:
        return self.z <= self.theta

    def snapshot(self) -> dict:
        return {"n": self.n_meas, "z": self.z}


class NaiveGradientDetector(Detector):
    """Baseline: two-point finite difference over roughly ``delta_s`` seconds."""

    name = "naive_gradient"

    def __init__(self, delta_s: float = 4.0, g: float = 1.5, **kw):
        if delta_s <= 0:
            raise ValueError("delta_s must be positive")
        self.delta_s = float(delta_s)
        self.g = float(g)
        kw.setdefault("warmup", 2)
        super().__init__(**kw)

    def _reset_signal(self) -> None:
        self.hist = deque()
        self.rate = float("nan")

    def _on_measurement(self, t, rsrp, rsrq) -> None:
        self.hist.append((t, rsrp))
        horizon = self.delta_s + 2.0 * self.adapter.heartbeat_s + 5.0
        while len(self.hist) > 2 and self.hist[1][0] <= t - horizon:
            self.hist.popleft()
        self.rate = float("nan")
        for tr, zr in reversed(self.hist):
            if tr <= t - self.delta_s:
                self.rate = (rsrp - zr) / (t - tr)
                break

    def _condition(self, t) -> bool:
        return self.rate == self.rate and self.rate <= -self.g

    def snapshot(self) -> dict:
        return {"n": self.n_meas, "rate": self.rate}


def ols_fit(points, t_now: float):
    """Least-squares slope and level at ``t_now`` for irregular (t, z) points."""
    n = len(points)
    mt = sum(p[0] for p in points) / n
    mz = sum(p[1] for p in points) / n
    sxx = sum((p[0] - mt) * (p[0] - mt) for p in points)
    if sxx <= 0.0:
        return float("nan"), float("nan")
    sxz = sum((p[0] - mt) * (p[1] - mz) for p in points)
    b = sxz / sxx
    return b, mz + b * (t_now - mt)


def theil_sen_fit(points, t_now: float):
    """Median of pairwise slopes (robust to outliers) and median-intercept level at ``t_now``."""
    slopes = []
    n = len(points)
    for i in range(n):
        ti, zi = points[i]
        for j in range(i + 1, n):
            tj, zj = points[j]
            if tj != ti:
                slopes.append((zj - zi) / (tj - ti))
    if not slopes:
        return float("nan"), float("nan")
    b = _median(slopes)
    a = _median([z - b * (tt - t_now) for tt, z in points])
    return b, a


def _median(v):
    s = sorted(v)
    m = len(s) // 2
    return s[m] if len(s) % 2 else 0.5 * (s[m - 1] + s[m])


class SlopeTTLDetector(Detector):
    """Windowed regression slope + linear time-to-loss: alarm if the fitted line reaches
    ``theta`` within ``horizon`` seconds while falling faster than ``min_rate``."""

    name = "slope_ttl"

    def __init__(self, window_s: float = 8.0, method: str = "ols", theta: float = -124.0,
                 horizon: float = 15.0, min_rate: float = 0.5, **kw):
        if method not in ("ols", "theilsen"):
            raise ValueError("method must be 'ols' or 'theilsen'")
        self.window_s = float(window_s)
        self.method = method
        self.theta = float(theta)
        self.horizon = float(horizon)
        self.min_rate = float(min_rate)
        super().__init__(**kw)

    def _reset_signal(self) -> None:
        self.pts = deque()
        self.slope = float("nan")
        self.level = float("nan")

    def _on_measurement(self, t, rsrp, rsrq) -> None:
        self.pts.append((t, rsrp))

    def _on_tick(self, t, nbr) -> None:
        while self.pts and self.pts[0][0] < t - self.window_s:
            self.pts.popleft()
        if len(self.pts) >= 3:
            fit = ols_fit if self.method == "ols" else theil_sen_fit
            self.slope, self.level = fit(list(self.pts), t)
        else:
            self.slope = self.level = float("nan")

    def _condition(self, t) -> bool:
        b = self.slope
        if b != b:
            return False
        return b <= -self.min_rate and (self.theta - (self.level + b * self.horizon)) >= 0.0

    def snapshot(self) -> dict:
        return {"n": self.n_meas, "slope": self.slope, "level": self.level}


class SanketDetector(Detector):
    """Proposed detector.

    1. Kalman local-linear-trend filters estimate RSRP level and rate (the temporal
       gradient) from noisy, quantised, irregular readings; the same for RSRQ and for the
       best neighbour cell's RSRP when available.
    2. Risk: probability that RSRP will be at or below the loss level ``theta`` after
       ``horizon`` seconds, from the filter's forecast mean and uncertainty. The condition
       is risk >= ``p_thr`` and rate <= -``min_rate`` (a real, sustained decline).
    3. RSRQ (optional): "confirm" requires RSRQ not to be improving; "dual" also raises
       the alarm when RSRQ is forecast to collapse (interference-limited loss).
    4. Common-mode test (optional): inside concrete *every* cell fades together; on the
       street one cell fades while another holds (shadowing, handover zones). If a viable
       neighbour (within ``nbr_delta`` dB of the serving cell) is fading at least
       ``nbr_diff`` dB/s more slowly than the serving cell, the alarm is vetoed.
    """

    name = "sanket"

    def __init__(self, meas_sd: float = 2.0, q: float = 0.2, gate: float = 3.5,
                 theta: float = -124.0, horizon: float = 15.0, p_thr: float = 0.5,
                 min_rate: float = 0.3, rsrq_mode: str = "off", theta_q: float = -18.0,
                 confirm_eps: float = 0.3, min_rate_q: float = 0.2,
                 nbr_veto: bool = False, nbr_delta: float = 6.0, nbr_diff: float = 1.0,
                 nbr_max_age: float = 6.0, rsrq_meas_sd: float = 1.5, rsrq_q: float = 0.1, **kw):
        if rsrq_mode not in ("off", "confirm", "dual"):
            raise ValueError("rsrq_mode must be off, confirm or dual")
        self.meas_sd, self.q, self.gate = float(meas_sd), float(q), float(gate)
        self.theta, self.horizon = float(theta), float(horizon)
        self.p_thr = float(p_thr)
        self.z_thr = z_for_probability(self.p_thr)
        self.min_rate = float(min_rate)
        self.rsrq_mode, self.theta_q = rsrq_mode, float(theta_q)
        self.confirm_eps, self.min_rate_q = float(confirm_eps), float(min_rate_q)
        self.nbr_veto, self.nbr_delta, self.nbr_diff = bool(nbr_veto), float(nbr_delta), float(nbr_diff)
        self.nbr_max_age = float(nbr_max_age)
        self.rsrq_meas_sd, self.rsrq_q = float(rsrq_meas_sd), float(rsrq_q)
        self.kf = LocalLinearTrend(self.meas_sd, self.q, 1.0, self.gate)
        self.kq = LocalLinearTrend(self.rsrq_meas_sd, self.rsrq_q, 0.5, self.gate)
        self.kn = LocalLinearTrend(self.meas_sd, self.q, 1.0, self.gate)
        self.nbr_adapter = StreamAdapter(kw.get("heartbeat_s", 4.0), kw.get("dedupe", True), False,
                                         kw.get("grace_s", 3.0))
        super().__init__(**kw)

    def _reset_signal(self) -> None:
        self.kf.reset()
        self.kq.reset()
        self.kn.reset()
        self.nbr_adapter.reset()
        self.nbr_t = None
        self.t_now = None

    def _on_measurement(self, t, rsrp, rsrq) -> None:
        self.kf.update(t, rsrp)
        if rsrq is not None:
            self.kq.update(t, rsrq)

    def _on_tick(self, t, nbr) -> None:
        self.t_now = t
        self.kf.predict_to(t)
        if self.kq.ready:
            self.kq.predict_to(t)
        status = self.nbr_adapter.push(t, nbr)
        if status == UNAVAILABLE:
            self.kn.reset()
            self.nbr_t = None
        else:
            if status == FIRST:
                self.kn.reset()
            if status in (FIRST, NEW):
                self.kn.update(t, float(nbr))
                self.nbr_t = t
            self.kn.predict_to(t)

    # The condition is written so that the vectorised evaluator in features.py can
    # reproduce it bit-for-bit (same operations in the same order).
    def _condition(self, t) -> bool:
        kf, h = self.kf, self.horizon
        mean, sd = forecast(kf.level, kf.rate, kf.p00, kf.p01, kf.p11, kf.q, h)
        main = (self.theta - mean) >= self.z_thr * sd and kf.rate <= -self.min_rate
        if self.rsrq_mode != "off":
            kq = self.kq
            if self.rsrq_mode == "confirm":
                if kq.n >= 2 and kq.rate > self.confirm_eps:
                    main = False
            elif kq.n >= 2:  # dual
                qm, qs = forecast(kq.level, kq.rate, kq.p00, kq.p01, kq.p11, kq.q, h)
                if (self.theta_q - qm) >= self.z_thr * qs and kq.rate <= -self.min_rate_q:
                    main = True
        if main and self.nbr_veto and self.kn.n >= 2 and self.nbr_t is not None \
                and (t - self.nbr_t) <= self.nbr_max_age:
            if self.kn.level >= kf.level - self.nbr_delta and (self.kn.rate - kf.rate) >= self.nbr_diff:
                main = False
        return main

    def risk(self) -> float:
        """Probability that RSRP is at or below theta after `horizon` s (for display)."""
        kf = self.kf
        if not kf.ready:
            return 0.0
        mean, sd = forecast(kf.level, kf.rate, kf.p00, kf.p01, kf.p11, kf.q, self.horizon)
        if sd <= 0.0:
            return 1.0 if mean <= self.theta else 0.0
        return NormalDist().cdf((self.theta - mean) / sd)

    def time_to_loss(self) -> float:
        """Linear extrapolation of seconds until the level reaches theta (inf if not falling)."""
        kf = self.kf
        if not kf.ready or kf.rate >= 0.0:
            return float("inf")
        return max(0.0, (kf.level - self.theta) / (-kf.rate))

    def snapshot(self) -> dict:
        kf, kq, kn = self.kf, self.kq, self.kn
        return {"n": self.n_meas,
                "level": kf.level, "rate": kf.rate, "p00": kf.p00, "p01": kf.p01, "p11": kf.p11,
                "q_n": kq.n, "q_level": kq.level, "q_rate": kq.rate,
                "q_p00": kq.p00, "q_p01": kq.p01, "q_p11": kq.p11,
                "n_n": kn.n, "n_level": kn.level, "n_rate": kn.rate,
                "n_age": (float("inf") if self.nbr_t is None or self.t_now is None
                          else self.t_now - self.nbr_t)}


class LogisticDetector(SanketDetector):
    """Machine-learning baseline: logistic regression on the Kalman features.

    ``weights`` order matches :func:`logistic_features`. The model is trained offline in
    the research pipeline; at run time it costs one dot product per tick.
    """

    name = "logistic"

    def __init__(self, weights, bias: float, tau: float = 0.5, **kw):
        self.weights = [float(w) for w in weights]
        self.bias = float(bias)
        self.tau = float(tau)
        if not 0.0 < self.tau < 1.0:
            raise ValueError("tau must be in (0, 1)")
        self.logit_thr = math.log(self.tau / (1.0 - self.tau))
        super().__init__(**kw)

    def _condition(self, t) -> bool:
        x = logistic_features(self.kf, self.kq, self.kn, self.theta, self.horizon)
        s = self.bias
        for w, xi in zip(self.weights, x):
            s = s + w * xi
        return s >= self.logit_thr


LOGISTIC_FEATURES = ("level", "rate", "rate_sd", "margin_z", "q_level", "q_rate", "nbr_gap")


def logistic_features(kf, kq, kn, theta, horizon):
    mean, sd = forecast(kf.level, kf.rate, kf.p00, kf.p01, kf.p11, kf.q, horizon)
    margin = (theta - mean) / sd if sd > 0.0 else 0.0
    q_level = kq.level if kq.n else -12.0
    q_rate = kq.rate if kq.n >= 2 else 0.0
    nbr_gap = (kn.level - kf.level) if kn.n else -20.0
    return (kf.level, kf.rate, math.sqrt(kf.p11 if kf.p11 > 0.0 else 0.0), margin, q_level, q_rate, nbr_gap)


DETECTORS = {cls.name: cls for cls in
             (ThresholdDetector, NaiveGradientDetector, SlopeTTLDetector, SanketDetector, LogisticDetector)}


def make_detector(name: str, **params) -> Detector:
    try:
        cls = DETECTORS[name]
    except KeyError:
        raise ValueError(f"unknown detector {name!r}; choose from {sorted(DETECTORS)}") from None
    return cls(**params)
