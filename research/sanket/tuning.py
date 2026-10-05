"""Fair parameter search: every detector is tuned on the *calibration* sets only, under the
same false-alarm budget measured on real traces, with the same selection rule.

Selection rule
  1. keep settings with real-trace false alarms <= budget (per hour of riding)
  2. among them maximise simulated accuracy (timely detections + silent look-alikes)
  3. tie-break on recall@15 s, then on fewer false alarms
"""
from __future__ import annotations

import itertools
import math

import numpy as np
import pandas as pd
from scipy.optimize import minimize

from . import features as fx
from .config import EvalConfig
from .core import (LOGISTIC_FEATURES, LogisticDetector, NaiveGradientDetector, SanketDetector, SlopeTTLDetector,
                   ThresholdDetector)

COOLDOWN_S = 60.0

GRIDS = {
    "threshold": {
        "filter": [{}],
        "cond": {"theta": list(range(-124, -97, 2)), "persistence": [1, 2, 3]},
    },
    "naive_gradient": {
        "filter": [{"delta_s": d} for d in (2.0, 4.0, 6.0, 8.0)],
        "cond": {"g": [0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 2.5, 3.0], "persistence": [1, 2, 3]},
    },
    "slope_ttl": {
        "filter": [{"window_s": w, "method": m} for w in (6.0, 10.0, 14.0) for m in ("ols", "theilsen")],
        "cond": {"theta": [-118.0, -121.0, -124.0], "horizon": [10.0, 15.0, 20.0, 25.0],
                 "min_rate": [0.25, 0.5, 1.0, 1.5], "persistence": [1, 2, 3]},
    },
    "sanket": {
        "filter": [{"meas_sd": a, "q": b} for a in (2.0, 3.0) for b in (0.1, 0.3, 0.8)],
        "cond": {"theta": [-118.0, -121.0, -124.0], "horizon": [10.0, 15.0, 20.0, 25.0],
                 "p_thr": [0.5, 0.7, 0.9], "min_rate": [0.0, 0.25, 0.5, 1.0], "persistence": [1, 2, 3],
                 "rsrq_mode": ["off", "confirm"],
                 # common-mode neighbour test: off, or (nbr_delta dB, nbr_diff dB/s)
                 "veto": [None] + [(d, r) for d in (3.0, 6.0, 10.0) for r in (0.5, 1.0, 1.5)]},
    },
}


def factory(name, params):
    p = dict(params)
    p.setdefault("cooldown_s", COOLDOWN_S)
    cls = {"threshold": ThresholdDetector, "naive_gradient": NaiveGradientDetector,
           "slope_ttl": SlopeTTLDetector, "sanket": SanketDetector, "logistic": LogisticDetector}[name]
    return lambda: cls(**p)


def condition(name, F, fparams, c):
    if name == "threshold":
        return fx.cond_threshold(F, c["theta"])
    if name == "naive_gradient":
        return fx.cond_naive(F, c["g"])
    if name == "slope_ttl":
        return fx.cond_slope(F, c["theta"], c["horizon"], c["min_rate"])
    if name == "sanket":
        veto = c.get("veto")
        return fx.cond_sanket(F, fparams["q"], c["theta"], c["horizon"], c["p_thr"], c["min_rate"],
                              rsrq_mode=c.get("rsrq_mode", "off"), nbr_veto=veto is not None,
                              nbr_delta=veto[0] if veto else 6.0, nbr_diff=veto[1] if veto else 1.0)
    raise ValueError(name)


def _combos(cond_grid):
    keys = list(cond_grid)
    for vals in itertools.product(*(cond_grid[k] for k in keys)):
        yield dict(zip(keys, vals))


def select(df: pd.DataFrame, budget: float) -> pd.Series:
    ok = df[df["real_fa_per_h"] <= budget]
    if ok.empty:                      # nothing meets the budget: take the quietest setting
        ok = df[df["real_fa_per_h"] == df["real_fa_per_h"].min()]
    ok = ok.sort_values(["sim_accuracy", "sim_recall15", "real_fa_per_h"], ascending=[False, False, True])
    return ok.iloc[0]


def search(name, sim_cal, real_cal, grid=None, cfg: EvalConfig = EvalConfig(), banks_cache=None):
    grid = grid or GRIDS[name]
    rows = []
    for fparams in grid["filter"]:
        fac = factory(name, fparams)
        key = (name, tuple(sorted(fparams.items())))
        if banks_cache is not None and key in banks_cache:
            b_sim, b_real = banks_cache[key]
        else:
            b_sim, b_real = fx.extract(fac, sim_cal, cfg), fx.extract(fac, real_cal, cfg)
            if banks_cache is not None:
                banks_cache[key] = (b_sim, b_real)
        for c in _combos(grid["cond"]):
            m = c.get("persistence", 1)
            a_sim = fx.fire(b_sim, condition(name, b_sim.F, fparams, c), m, COOLDOWN_S)
            a_real = fx.fire(b_real, condition(name, b_real.F, fparams, c), m, COOLDOWN_S)
            s, r = fx.score(b_sim, a_sim, cfg), fx.score(b_real, a_real, cfg)
            rows.append({**fparams, **c, "sim_recall10": s["recall10"], "sim_recall15": s["recall15"],
                         "sim_specificity": s["specificity"], "sim_accuracy": s["accuracy"],
                         "sim_median_lead": s["median_lead"], "sim_fa_per_h": s["fa_per_h"],
                         "real_fa_per_h": r["fa_per_h"], "real_recall10": r["recall10"]})
    df = pd.DataFrame(rows)
    best = select(df, cfg.fa_budget_per_h)
    return df, best


def detector_params(fparams: dict, cparams: dict) -> dict:
    """Grid settings -> constructor keyword arguments for the streaming detector."""
    out = {**fparams, **cparams}
    if "veto" in out:
        v = out.pop("veto")
        if v is None or (isinstance(v, float) and math.isnan(v)):
            out["nbr_veto"] = False
        else:
            out.update(nbr_veto=True, nbr_delta=float(v[0]), nbr_diff=float(v[1]))
    for k, v in list(out.items()):
        if isinstance(v, np.bool_):
            out[k] = bool(v)
        elif isinstance(v, np.integer):
            out[k] = int(v)
        elif isinstance(v, np.floating):
            out[k] = float(v)
    if "persistence" in out:
        out["persistence"] = int(out["persistence"])
    return out


def params_from_row(name, row) -> dict:
    fkeys = {k for f in GRIDS[name]["filter"] for k in f}
    ckeys = set(GRIDS[name]["cond"])
    return detector_params({k: row[k] for k in fkeys}, {k: row[k] for k in ckeys})


# --------------------------------------------------------------------------------------
# Machine-learning baseline: logistic regression on the Kalman features
# --------------------------------------------------------------------------------------
def logistic_labels(bank: fx.Bank, traces, lo=10.0, hi=45.0, cfg: EvalConfig = EvalConfig()):
    """y = 1 if a loss starts 10-45 s after this tick, 0 if none within max_lead; else ignored."""
    y = np.full(bank.t.size, -1, dtype=np.int8)
    for k, tr in enumerate(traces):
        s0 = int(bank.starts[k])
        tt = tr.t
        lab = np.zeros(tt.size, dtype=np.int8)
        amb = np.zeros(tt.size, dtype=bool)
        for on, end, _ in tr.episodes:
            dt = on - tt
            lab |= ((dt >= lo) & (dt <= hi)).astype(np.int8)
            amb |= ((dt > 0) & (dt < lo)) | ((dt > hi) & (dt <= cfg.max_lead_s)) | ((tt >= on) & (tt < end))
        yy = np.where(amb & (lab == 0), -1, lab)
        y[s0:s0 + tt.size] = yy
    return y


def fit_logistic(X, y, l2=1e-2, max_iter=500):
    """L2-regularised, class-balanced logistic regression (SciPy L-BFGS). Returns raw weights."""
    m = (y >= 0) & np.isfinite(X).all(axis=1)
    X, y = X[m], y[m].astype(float)
    mu, sd = X.mean(axis=0), X.std(axis=0)
    sd[sd == 0] = 1.0
    Z = (X - mu) / sd
    w_pos = 0.5 / max(y.mean(), 1e-9)
    w_neg = 0.5 / max(1 - y.mean(), 1e-9)
    sw = np.where(y > 0, w_pos, w_neg)

    def loss(theta):
        w, b = theta[:-1], theta[-1]
        z = Z @ w + b
        ll = sw * (np.logaddexp(0, z) - y * z)
        p = 1 / (1 + np.exp(-z))
        g = Z.T @ (sw * (p - y)) / y.size + l2 * w
        gb = np.sum(sw * (p - y)) / y.size
        return ll.mean() + 0.5 * l2 * w @ w, np.concatenate([g, [gb]])

    res = minimize(loss, np.zeros(Z.shape[1] + 1), jac=True, method="L-BFGS-B", options={"maxiter": max_iter})
    w_std, b_std = res.x[:-1], res.x[-1]
    w_raw = w_std / sd
    b_raw = b_std - float(np.sum(w_std * mu / sd))
    return [float(v) for v in w_raw], float(b_raw), {"converged": bool(res.success), "n": int(y.size),
                                                       "pos_frac": float(y.mean()),
                                                       "features": list(LOGISTIC_FEATURES)}


def search_logistic(sim_cal, real_cal, sanket_filter, theta, horizon, cfg: EvalConfig = EvalConfig(),
                    banks_cache=None):
    key = ("sanket", tuple(sorted(sanket_filter.items())))
    if banks_cache is not None and key in banks_cache:
        b_sim, b_real = banks_cache[key]
    else:
        fac = factory("sanket", sanket_filter)
        b_sim, b_real = fx.extract(fac, sim_cal, cfg), fx.extract(fac, real_cal, cfg)
    q = sanket_filter["q"]
    Xs, Xr = fx.logistic_matrix(b_sim.F, theta, horizon, q), fx.logistic_matrix(b_real.F, theta, horizon, q)
    ys, yr = logistic_labels(b_sim, sim_cal, cfg=cfg), logistic_labels(b_real, real_cal, cfg=cfg)
    w, b, info = fit_logistic(np.vstack([Xs, Xr]), np.concatenate([ys, yr]))
    rows = []
    for tau in (0.5, 0.6, 0.7, 0.8, 0.85, 0.9, 0.93, 0.95, 0.97, 0.98, 0.99, 0.995):
        for m in (1, 2, 3):
            a_s = fx.fire(b_sim, fx.cond_logistic(b_sim.F, Xs, w, b, tau), m, COOLDOWN_S)
            a_r = fx.fire(b_real, fx.cond_logistic(b_real.F, Xr, w, b, tau), m, COOLDOWN_S)
            s, r = fx.score(b_sim, a_s, cfg), fx.score(b_real, a_r, cfg)
            rows.append({"tau": tau, "persistence": m, "sim_recall10": s["recall10"], "sim_recall15": s["recall15"],
                         "sim_specificity": s["specificity"], "sim_accuracy": s["accuracy"],
                         "sim_median_lead": s["median_lead"], "real_fa_per_h": r["fa_per_h"],
                         "real_recall10": r["recall10"]})
    df = pd.DataFrame(rows)
    best = select(df, cfg.fa_budget_per_h)
    params = dict(sanket_filter, theta=theta, horizon=horizon, weights=w, bias=b, tau=float(best["tau"]),
                  persistence=int(best["persistence"]))
    return df, best, params, info
