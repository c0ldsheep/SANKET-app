"""Bulk evaluation for parameter search.

The streaming detectors in ``core`` are the reference. For tuning we run each detector's
*filter* once per trace set, record its internal state at every tick, and then evaluate
thousands of trigger settings with NumPy. Every vectorised condition below repeats the
streaming condition with the same floating-point operations in the same order, and
``tests/test_features.py`` checks that both paths raise identical alarms.
"""
from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np

from .config import EvalConfig
from .core import z_for_probability
from .evaluate import evaluable_onsets, _valid_ticks


@dataclass
class Bank:
    """Ticks of many traces laid end to end, plus per-tick detector state."""
    t: np.ndarray
    tid: np.ndarray
    starts: np.ndarray        # first global index of each trace
    names: list
    F: dict                   # snapshot field -> array
    # evaluation helpers (independent of alarms)
    fa_eligible: np.ndarray   # tick outside outages and pre-loss windows
    ev_lo: np.ndarray         # per evaluable event: first global index in [on - max_lead, on)
    ev_hi: np.ndarray         # one past last index in the window
    ev_onset: np.ndarray
    ev_kind: list
    ev_oracle: np.ndarray
    neg_tid: np.ndarray       # trace ids of negative (loss-free) traces
    exposure_h: float


def extract(factory, traces, cfg: EvalConfig = EvalConfig()) -> Bank:
    names, t_all, tid_all, starts = [], [], [], []
    rows = []
    offset = 0
    ev_lo, ev_hi, ev_on, ev_kind, ev_or = [], [], [], [], []
    fa_ok_all = []
    neg = []
    exposure = 0.0
    for k, tr in enumerate(traces):
        if tr.t.size and not np.all(tr.t == np.round(tr.t)):
            raise ValueError("bulk path expects integer tick times")
        det = factory()
        names.append(tr.name)
        starts.append(offset)
        snaps = []
        for i in range(tr.t.size):
            r, q, nb, c = tr.rsrp[i], tr.rsrq[i], tr.nbr[i], int(tr.cell[i])
            det.step(float(tr.t[i]), float(r) if math.isfinite(r) else None,
                     float(q) if math.isfinite(q) else None,
                     float(nb) if math.isfinite(nb) else None, c if c >= 0 else None)
            snaps.append(det.snapshot())
        rows.extend(snaps)
        t_all.append(tr.t.astype(float))
        tid_all.append(np.full(tr.t.size, k, dtype=np.int32))
        # evaluation helpers
        valid = _valid_ticks(tr)
        excl = np.zeros(tr.t.size, dtype=bool)
        in_win_or_out = np.zeros(tr.t.size, dtype=bool)
        for on, end, _ in tr.episodes:
            excl |= (tr.t >= on - cfg.max_lead_s) & (tr.t < end)
            in_win_or_out |= (tr.t >= on - cfg.max_lead_s) & (tr.t < end)
        fa_ok_all.append(~in_win_or_out)
        dt = float(np.median(np.diff(tr.t))) if tr.t.size > 1 else 1.0
        exposure += float(np.count_nonzero(valid & ~excl)) * dt
        te = float(tr.meta.get("t_entry", float("nan")))
        for on in evaluable_onsets(tr, cfg):
            lo = int(np.searchsorted(tr.t, on - cfg.max_lead_s, side="left"))
            hi = int(np.searchsorted(tr.t, on, side="left"))
            ev_lo.append(offset + lo)
            ev_hi.append(offset + hi)
            ev_on.append(on)
            ev_kind.append(tr.kind)
            ev_or.append(on - te if math.isfinite(te) else float("nan"))
        if not tr.episodes:
            neg.append(k)
        offset += tr.t.size
    keys = rows[0].keys() if rows else []
    F = {key: np.array([r[key] for r in rows], dtype=float) for key in keys}
    return Bank(t=np.concatenate(t_all) if t_all else np.array([]),
                tid=np.concatenate(tid_all) if tid_all else np.array([], dtype=np.int32),
                starts=np.array(starts, dtype=np.int64), names=names, F=F,
                fa_eligible=np.concatenate(fa_ok_all) if fa_ok_all else np.array([], dtype=bool),
                ev_lo=np.array(ev_lo, dtype=np.int64), ev_hi=np.array(ev_hi, dtype=np.int64),
                ev_onset=np.array(ev_on, dtype=float), ev_kind=ev_kind, ev_oracle=np.array(ev_or, dtype=float),
                neg_tid=np.array(neg, dtype=np.int64), exposure_h=exposure / 3600.0)


# --------------------------------------------------------------------------------------
# Vectorised conditions (mirror core.*._condition exactly)
# --------------------------------------------------------------------------------------
def cond_threshold(F, theta, warmup=1):
    return (F["n"] >= warmup) & (F["z"] <= theta)


def cond_naive(F, g, warmup=2):
    return (F["n"] >= warmup) & (F["rate"] <= -g)


def cond_slope(F, theta, horizon, min_rate, warmup=3):
    b = F["slope"]
    with np.errstate(invalid="ignore"):
        return (F["n"] >= warmup) & (b <= -min_rate) & ((theta - (F["level"] + b * horizon)) >= 0.0)


def _forecast(level, rate, p00, p01, p11, q, h):
    mean = level + rate * h
    var = p00 + 2.0 * h * p01 + h * h * p11 + q * h * h * h / 3.0
    return mean, np.sqrt(np.where(var > 0.0, var, 0.0))


def cond_sanket(F, q, theta, horizon, p_thr, min_rate, rsrq_mode="off", theta_q=-18.0, confirm_eps=0.3,
                min_rate_q=0.2, rsrq_q=0.1, nbr_veto=False, nbr_delta=6.0, nbr_diff=1.0, nbr_max_age=6.0,
                warmup=3):
    z = z_for_probability(p_thr)
    mean, sd = _forecast(F["level"], F["rate"], F["p00"], F["p01"], F["p11"], q, horizon)
    main = ((theta - mean) >= z * sd) & (F["rate"] <= -min_rate)
    if rsrq_mode == "confirm":
        main &= ~((F["q_n"] >= 2) & (F["q_rate"] > confirm_eps))
    elif rsrq_mode == "dual":
        qm, qs = _forecast(F["q_level"], F["q_rate"], F["q_p00"], F["q_p01"], F["q_p11"], rsrq_q, horizon)
        main |= (F["q_n"] >= 2) & ((theta_q - qm) >= z * qs) & (F["q_rate"] <= -min_rate_q)
    if nbr_veto:
        veto = (F["n_n"] >= 2) & (F["n_age"] <= nbr_max_age) & (F["n_level"] >= F["level"] - nbr_delta) \
               & ((F["n_rate"] - F["rate"]) >= nbr_diff)
        main &= ~veto
    return (F["n"] >= warmup) & main


def logistic_matrix(F, theta, horizon, q):
    """Feature matrix in the order of core.LOGISTIC_FEATURES (same formulas)."""
    mean, sd = _forecast(F["level"], F["rate"], F["p00"], F["p01"], F["p11"], q, horizon)
    with np.errstate(divide="ignore", invalid="ignore"):
        margin = np.where(sd > 0.0, (theta - mean) / sd, 0.0)
    q_level = np.where(F["q_n"] > 0, F["q_level"], -12.0)
    q_rate = np.where(F["q_n"] >= 2, F["q_rate"], 0.0)
    nbr_gap = np.where(F["n_n"] > 0, F["n_level"] - F["level"], -20.0)
    rate_sd = np.sqrt(np.where(F["p11"] > 0.0, F["p11"], 0.0))
    return np.column_stack([F["level"], F["rate"], rate_sd, margin, q_level, q_rate, nbr_gap])


def cond_logistic(F, X, weights, bias, tau, warmup=3):
    s = np.full(X.shape[0], float(bias))
    for j, w in enumerate(weights):
        s = s + float(w) * X[:, j]
    return (F["n"] >= warmup) & (s >= math.log(tau / (1.0 - tau)))


# --------------------------------------------------------------------------------------
# Persistence + cooldown, then event matching
# --------------------------------------------------------------------------------------
def fire(bank: Bank, cond: np.ndarray, persistence: int, cooldown_s: float) -> np.ndarray:
    """Global indices of ticks where an alarm fires (same rule as core.Detector.step)."""
    n = cond.size
    if n == 0:
        return np.array([], dtype=np.int64)
    idx = np.arange(n)
    marker = np.where(cond, -1, idx)
    s = bank.starts
    marker[s] = np.where(cond[s], s - 1, s)
    last_false = np.maximum.accumulate(marker)
    run = np.where(cond, idx - last_false, 0)
    cand = np.flatnonzero(run >= persistence)
    if cand.size == 0:
        return cand
    gt = bank.t[cand] + bank.tid[cand].astype(float) * 1e7   # cooldown never crosses traces
    out = []
    i = 0
    while i < cand.size:
        out.append(cand[i])
        i = int(np.searchsorted(gt, gt[i] + cooldown_s, side="left"))
    return np.array(out, dtype=np.int64)


def score(bank: Bank, alarm_idx: np.ndarray, cfg: EvalConfig = EvalConfig()) -> dict:
    n_alarm = alarm_idx.size
    n_fa = int(np.count_nonzero(bank.fa_eligible[alarm_idx])) if n_alarm else 0
    # first alarm inside each event window
    j = np.searchsorted(alarm_idx, bank.ev_lo, side="left")
    has = j < n_alarm
    jj = np.where(has, j, 0)
    first = alarm_idx[jj] if n_alarm else np.zeros_like(bank.ev_lo)
    hit = has & (first < bank.ev_hi)
    leads = np.where(hit, bank.ev_onset - bank.t[first] if n_alarm else np.nan, np.nan)
    n_ev = bank.ev_lo.size
    timely = int(np.sum(leads >= cfg.min_lead_s))
    timely15 = int(np.sum(leads >= cfg.stretch_lead_s))
    if bank.neg_tid.size:
        alarmed = np.zeros(len(bank.names), dtype=bool)
        alarmed[bank.tid[alarm_idx]] = True
        tn = int(np.count_nonzero(~alarmed[bank.neg_tid]))
    else:
        tn = 0
    n_neg = int(bank.neg_tid.size)
    return {"events": n_ev, "timely": timely, "recall10": timely / n_ev if n_ev else float("nan"),
            "recall15": timely15 / n_ev if n_ev else float("nan"),
            "median_lead": float(np.nanmedian(leads)) if np.isfinite(leads).any() else float("nan"),
            "missed": int(np.sum(~hit)), "false_alarms": n_fa,
            "fa_per_h": n_fa / bank.exposure_h if bank.exposure_h > 0 else float("nan"),
            "alarms": n_alarm, "negatives": n_neg, "true_negatives": tn,
            "specificity": tn / n_neg if n_neg else float("nan"),
            "accuracy": (timely + tn) / (n_ev + n_neg) if (n_ev + n_neg) else float("nan"),
            "leads": leads}
