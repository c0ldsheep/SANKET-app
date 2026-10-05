"""Calibrate the simulator's noise model against the real UCC traces.

Only signal *statistics* are matched (how much the reading jumps second to second, how
often it repeats, how steep the steepest 10 s declines are). This is done once, before any
detector is evaluated, so detector results cannot leak into the simulator.
"""
from __future__ import annotations

import dataclasses

import numpy as np
import pandas as pd

from .config import DEFAULT, Config
from .simulate import simulate_dataset

STATS = ("repeat", "sd_diff", "sd_res", "slope_p1", "slope_p5")


def signal_stats(series_list, window=10) -> dict:
    d1, res, sl, rep = [], [], [], []
    x_axis = np.arange(window) - (window - 1) / 2.0
    for x in series_list:
        x = np.asarray(x, dtype=float)
        if x.size < 40:
            continue
        d = np.diff(x)
        ok = np.isfinite(d)
        if not ok.any():
            continue
        d1.append(d[ok])
        rep.append(np.mean(d[ok] == 0))
        s = pd.Series(x)
        r = (s - s.rolling(31, center=True, min_periods=15).median()).to_numpy()
        res.append(r[np.isfinite(r)])
        w = np.lib.stride_tricks.sliding_window_view(x, window)
        good = np.isfinite(w).all(axis=1)
        sl.append((w[good] @ x_axis) / (x_axis @ x_axis))
    d1, res, sl = (np.concatenate(v) for v in (d1, res, sl))
    return {"n": int(d1.size), "repeat": float(np.mean(rep)), "sd_diff": float(d1.std()),
            "sd_res": float(res.std()), "slope_p1": float(np.percentile(sl, 1)),
            "slope_p5": float(np.percentile(sl, 5)), "p_slope_lt_1p5": float(np.mean(sl < -1.5)),
            "slopes": sl}


def real_targets(real_traces) -> dict:
    by = {}
    for tr in real_traces:
        by.setdefault(tr.meta["mobility"], []).append(tr.rsrp)
    return {m: signal_stats(v) for m, v in by.items()}


def sim_outdoor_series(sim, kinds):
    out = []
    for tr in sim:
        if tr.kind not in kinds:
            continue
        te = tr.meta.get("t_entry", float("nan"))
        end = int(te) - 2 if np.isfinite(te) else tr.t.size
        out.append(tr.rsrp[:end])
    return out


def sim_stats(cfg: Config, n=500, seed=4242) -> dict:
    sim = simulate_dataset(n, seed=seed, cfg=cfg, keep_truth=False)
    walk = sim_outdoor_series(sim, ("basement_walk", "deep_indoor", "lobby_entry"))
    ride = sim_outdoor_series(sim, ("basement_ride", "underpass", "street_ride", "cell_edge_handover"))
    return {"walk": signal_stats(walk), "ride": signal_stats(ride)}


def _loss(sim_s, real_s):
    scale = {"repeat": 0.05, "sd_diff": 0.2, "sd_res": 0.3, "slope_p1": 0.2, "slope_p5": 0.15}
    return sum(((sim_s[k] - real_s[k]) / scale[k]) ** 2 for k in STATS)


def calibrate(real_traces, grid=None, n=500, seed=4242):
    """Grid search over the noise / shadowing parameters. Walking is matched to the real
    pedestrian traces and riding to the real bus traces (city speeds closest to riders)."""
    tgt = real_targets(real_traces)
    grid = grid or {"rsrp_noise_db_moving": (2.8, 3.5, 4.2), "shadow_sd_out": (3.0, 4.0, 5.0),
                    "shadow_dcorr_out": (10.0, 20.0, 35.0)}
    rows = []
    for nm in grid["rsrp_noise_db_moving"]:
        for sd in grid["shadow_sd_out"]:
            for dc in grid["shadow_dcorr_out"]:
                cfg = dataclasses.replace(DEFAULT,
                                          meas=dataclasses.replace(DEFAULT.meas, rsrp_noise_db_moving=nm),
                                          env=dataclasses.replace(DEFAULT.env, shadow_sd_out=sd, shadow_dcorr_out=dc))
                st = sim_stats(cfg, n, seed)
                loss = _loss(st["walk"], tgt["pedestrian"]) + _loss(st["ride"], tgt["bus"])
                rows.append({"rsrp_noise_db_moving": nm, "shadow_sd_out": sd, "shadow_dcorr_out": dc,
                             "loss": loss, **{f"walk_{k}": st["walk"][k] for k in STATS},
                             **{f"ride_{k}": st["ride"][k] for k in STATS}})
    table = pd.DataFrame(rows).sort_values("loss").reset_index(drop=True)
    return table, tgt
