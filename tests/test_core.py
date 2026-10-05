"""Unit tests for the dependency-free streaming core."""
import math
import random

import pytest

from sanket.core import (FIRST, NEW, STALE, UNAVAILABLE, LocalLinearTrend, NaiveGradientDetector, SanketDetector,
                         SlopeTTLDetector, StreamAdapter, ThresholdDetector, forecast, make_detector, ols_fit,
                         theil_sen_fit, valid_rsrp, valid_rsrq, z_for_probability)


# ------------------------------------------------------------------ input validation
def test_valid_ranges_match_android():
    assert valid_rsrp(-140) and valid_rsrp(-43) and not valid_rsrp(-141) and not valid_rsrp(-42)
    assert valid_rsrq(-34) and valid_rsrq(3) and not valid_rsrq(-35) and not valid_rsrq(4)
    for bad in (None, float("nan"), float("inf"), "x", -200, 2147483647):
        assert not valid_rsrp(bad)


def test_bad_parameters_raise():
    with pytest.raises(ValueError):
        LocalLinearTrend(meas_sd=0)
    with pytest.raises(ValueError):
        SanketDetector(rsrq_mode="maybe")
    with pytest.raises(ValueError):
        ThresholdDetector(persistence=0)
    with pytest.raises(ValueError):
        z_for_probability(1.0)
    with pytest.raises(ValueError):
        make_detector("nope")


def test_z_for_probability():
    assert z_for_probability(0.5) == pytest.approx(0.0, abs=1e-12)
    assert z_for_probability(0.975) == pytest.approx(1.959964, abs=1e-5)


# ------------------------------------------------------------------ adapter
def test_adapter_dedupes_stale_repeats_and_heartbeats():
    a = StreamAdapter(heartbeat_s=4.0)
    assert a.push(0, -90, -10) == FIRST
    assert a.push(1, -90, -10) == STALE
    assert a.push(2, -91, -10) == NEW
    assert a.push(3, -91, -10) == STALE
    assert a.push(6, -91, -10) == NEW          # 4 s without change: accept as a fresh reading
    assert a.push(7, -91, -11) == NEW          # RSRQ change alone is new information


def test_adapter_grace_then_reset():
    a = StreamAdapter(grace_s=3.0)
    a.push(0, -90)
    assert a.push(1, None) == STALE            # a glitch is "no news"
    assert a.push(2, -200) == STALE            # out-of-range (logger sentinel) too
    assert a.push(3, None) == STALE
    assert a.push(4, None) == UNAVAILABLE      # 3 s without a valid reading: reset
    assert a.push(5, -90) == FIRST


def test_adapter_cell_change():
    a = StreamAdapter(reset_on_cell_change=True)
    a.push(0, -90, cell=1)
    assert a.push(1, -95, cell=1) == NEW
    assert a.push(2, -85, cell=2) == FIRST


# ------------------------------------------------------------------ Kalman filter
def test_kalman_recovers_a_clean_ramp():
    kf = LocalLinearTrend(meas_sd=1.0, q=0.05, gate=0)
    for k in range(60):
        kf.update(float(k), -80.0 - 2.0 * k)
    assert kf.rate == pytest.approx(-2.0, abs=1e-3)
    assert kf.level == pytest.approx(-80.0 - 2.0 * 59, abs=1e-2)


def test_kalman_prediction_composes_exactly():
    a = LocalLinearTrend(2.0, 0.3)
    b = LocalLinearTrend(2.0, 0.3)
    for kf in (a, b):
        kf.update(0.0, -90.0)
        kf.update(1.0, -92.0)
    a.predict_to(3.5)
    b.predict_to(2.0)
    b.predict_to(3.5)
    for x, y in zip((a.level, a.rate, a.p00, a.p01, a.p11), (b.level, b.rate, b.p00, b.p01, b.p11)):
        assert x == pytest.approx(y, rel=1e-12, abs=1e-12)


def test_kalman_covariance_stays_valid():
    rnd = random.Random(1)
    kf = LocalLinearTrend(2.5, 0.2)
    t = 0.0
    for _ in range(2000):
        t += rnd.choice([1.0, 2.0, 3.0])
        kf.update(t, -100 + rnd.gauss(0, 5))
        assert kf.p00 > 0 and kf.p11 > 0
        assert kf.p00 * kf.p11 - kf.p01 * kf.p01 > -1e-9


def test_kalman_gating_limits_a_single_glitch():
    plain = LocalLinearTrend(1.5, 0.1, gate=0)
    gated = LocalLinearTrend(1.5, 0.1, gate=3.5)
    for kf in (plain, gated):
        for k in range(20):
            kf.update(float(k), -90.0)
        kf.update(20.0, -120.0)                # one absurd reading
    assert abs(gated.rate) < abs(plain.rate)
    assert gated.level > plain.level


def test_forecast_formula():
    mean, sd = forecast(-100.0, -2.0, 4.0, 0.5, 0.25, 0.1, 10.0)
    assert mean == -120.0
    assert sd == pytest.approx(math.sqrt(4.0 + 2 * 10 * 0.5 + 100 * 0.25 + 0.1 * 1000 / 3))


# ------------------------------------------------------------------ fitting helpers
def test_ols_and_theil_sen_on_clean_and_dirty_lines():
    pts = [(float(t), 5.0 - 1.5 * t) for t in range(8)]
    b, lvl = ols_fit(pts, 10.0)
    assert b == pytest.approx(-1.5) and lvl == pytest.approx(5.0 - 15.0)
    pts[3] = (3.0, 60.0)                       # one outlier
    b_ts, _ = theil_sen_fit(pts, 10.0)
    b_ols, _ = ols_fit(pts, 10.0)
    assert b_ts == pytest.approx(-1.5)
    assert abs(b_ols + 1.5) > 0.5


# ------------------------------------------------------------------ detectors
def ramp(t_start_drop=40, rate=-2.5, start=-90.0, t_end=120, report_every=2, noise=0.0, seed=0):
    rnd = random.Random(seed)
    out = []
    last = None
    for t in range(t_end):
        if t % report_every == 0:
            v = start + (rate * (t - t_start_drop) if t > t_start_drop else 0.0) + rnd.gauss(0, noise)
            last = float(round(v))
        out.append((float(t), last if last is not None and last >= -140 else None))
    return out


def first_alarm(det, series):
    for t, r in series:
        if det.step(t, r, -11.0):
            return t
    return None


def test_sanket_warns_before_a_steep_sustained_drop():
    series = ramp(rate=-2.5, noise=1.0)
    det = SanketDetector(theta=-124.0, horizon=10.0, p_thr=0.7, min_rate=1.0, persistence=1)
    t_alarm = first_alarm(det, series)
    t_cross = 40 + (124 - 90) / 2.5            # when the true level reaches -124 dBm
    assert t_alarm is not None and t_alarm < t_cross - 5


def test_sanket_stays_quiet_on_a_flat_noisy_signal():
    rnd = random.Random(3)
    det = SanketDetector(theta=-124.0, horizon=10.0, p_thr=0.7, min_rate=1.0, persistence=1)
    alarms = [t for t in range(3600) if det.step(float(t), float(round(-95 + rnd.gauss(0, 2))), -11.0)]
    assert alarms == []


def test_cooldown_limits_repeated_alarms():
    det = ThresholdDetector(theta=-100.0, persistence=1, cooldown_s=60.0)
    alarms = [t for t in range(300) if det.step(float(t), -110.0)]
    assert alarms == [0, 60, 120, 180, 240]


def test_unavailable_resets_signal_state():
    det = SanketDetector()
    for t in range(10):
        det.step(float(t), -90.0 - t)
    assert det.n_meas > 0
    for t in range(10, 15):
        det.step(float(t), None)
    assert det.n_meas == 0 and not det.kf.ready


def test_common_mode_veto():
    """Serving falls while a strong neighbour holds -> veto; both fall -> no veto."""
    def run(neighbour_falls):
        det = SanketDetector(theta=-124.0, horizon=10.0, p_thr=0.5, min_rate=0.5, persistence=1,
                             nbr_veto=True, nbr_delta=6.0, nbr_diff=1.0)
        alarms = []
        for t in range(0, 80):
            serv = -90.0 - (2.0 * (t - 30) if t > 30 else 0.0)
            nb = -93.0 - ((2.0 * (t - 30)) if (t > 30 and neighbour_falls) else 0.0)
            if det.step(float(t), round(serv), -11.0, round(nb)):
                alarms.append(t)
        return alarms
    assert run(neighbour_falls=True)            # building entry: alarm
    assert not run(neighbour_falls=False)       # handover zone: vetoed


def test_threshold_and_gradient_detectors():
    series = ramp(rate=-2.0, noise=0.0, report_every=1)
    assert first_alarm(ThresholdDetector(theta=-110.0, persistence=1), series) == 50
    g = first_alarm(NaiveGradientDetector(delta_s=4.0, g=1.5, persistence=1), series)
    assert g is not None and 41 <= g <= 46


def test_slope_detector_methods_agree_on_clean_data():
    series = ramp(rate=-2.0, report_every=1)
    a = first_alarm(SlopeTTLDetector(window_s=8, method="ols", theta=-124, horizon=10, min_rate=1.0, persistence=1), series)
    b = first_alarm(SlopeTTLDetector(window_s=8, method="theilsen", theta=-124, horizon=10, min_rate=1.0, persistence=1), series)
    assert a is not None and a == b


def test_detector_is_deterministic():
    series = ramp(noise=2.0, seed=9)
    a = [SanketDetector().step(t, r) for t, r in series]
    b = [SanketDetector().step(t, r) for t, r in series]
    assert a == b
