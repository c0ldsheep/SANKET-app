#!/usr/bin/env python3
"""Reproduce every number, table and figure used in the submission.

    python experiments/run_all.py            # full run (~15 min on a laptop)
    python experiments/run_all.py --quick    # small smoke run
    python experiments/run_all.py --reuse-params   # skip tuning, reuse results/params.json

Protocol (no peeking): detectors are tuned only on the calibration sets (simulated seed 1 +
half of the real traces). Everything reported as a result comes from data the tuning never
saw: simulated seed 2, the other half of the real traces, injected entries built on that
half, and its real LTE-loss events.
"""
from __future__ import annotations

import argparse
import dataclasses
import json
import math
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from sanket import __version__, features as fx, plots  # noqa: E402
from sanket.calibration import calibrate, real_targets, sim_stats, STATS  # noqa: E402
from sanket.config import DEFAULT, POSITIVE_KINDS, CONTROL_KINDS  # noqa: E402
from sanket.core import SanketDetector  # noqa: E402
from sanket.deadzone import daily_summary, run_fleet  # noqa: E402
from sanket.evaluate import evaluate, evaluable_onsets, run_detector, summarize, match  # noqa: E402
from sanket.prefetch import Link, completion_vs_lead, daily_cost_mb, required_lead, run_prefetch  # noqa: E402
from sanket.realdata import injection_set, load_ucc, split_files  # noqa: E402
from sanket.simulate import simulate_dataset  # noqa: E402
from sanket.tuning import (GRIDS, COOLDOWN_S, _combos, condition, detector_params, factory,  # noqa: E402
                           params_from_row, search, search_logistic)

RES = ROOT / "results"
FIG = RES / "figures"
TAB = RES / "tables"
DETECTORS = ("threshold", "naive_gradient", "slope_ttl", "logistic", "sanket")
T0 = time.time()


def log(msg):
    print(f"[{time.time() - T0:7.1f}s] {msg}", flush=True)


def jsonable(x):
    if isinstance(x, dict):
        return {str(k): jsonable(v) for k, v in x.items()}
    if isinstance(x, (list, tuple)):
        return [jsonable(v) for v in x]
    if isinstance(x, (np.bool_, bool)):
        return bool(x)
    if isinstance(x, (np.integer,)):
        return int(x)
    if isinstance(x, (np.floating, float)):
        v = float(x)
        return None if not math.isfinite(v) else round(v, 6)
    if isinstance(x, np.ndarray):
        return jsonable(x.tolist())
    return x


def slim(s: dict) -> dict:
    keep = ("events", "timely", "timely15", "late", "missed", "recall10", "recall10_ci", "recall15", "recall15_ci",
            "any_warning", "median_lead", "median_lead_ci", "false_alarms", "exposure_h", "fa_per_h", "fa_per_h_ci",
            "alarms", "precision", "negatives", "true_negatives", "specificity", "specificity_ci", "accuracy",
            "accuracy_ci")
    return {k: s[k] for k in keep if k in s}


def steepest_slope(t, x, t_lo, t_hi, window=10):
    """Most negative OLS slope over any 10 s window of observed readings inside [t_lo, t_hi]."""
    m = (t >= t_lo) & (t <= t_hi) & np.isfinite(x)
    tt, xx = t[m], x[m]
    best = np.nan
    for i in range(tt.size):
        w = (tt >= tt[i]) & (tt < tt[i] + window)
        if w.sum() >= 4 and tt[w][-1] - tt[w][0] >= window * 0.6:
            b = np.polyfit(tt[w], xx[w], 1)[0]
            best = b if not np.isfinite(best) else min(best, b)
    return best


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--quick", action="store_true")
    ap.add_argument("--reuse-params", action="store_true")
    args = ap.parse_args()
    n_cal, n_test, n_inj = (300, 300, 120) if args.quick else (1500, 1500, 600)
    for d in (RES, FIG, TAB):
        d.mkdir(parents=True, exist_ok=True)
    ev_cfg = DEFAULT.eval
    M = {"generated_utc": datetime.now(timezone.utc).isoformat(timespec="seconds"), "version": __version__,
         "quick": args.quick, "config": dataclasses.asdict(DEFAULT)}

    # ------------------------------------------------------------------ 1. data
    log("simulating calibration and test sets")
    sim_cal = simulate_dataset(n_cal, seed=1, keep_truth=False)
    sim_test = simulate_dataset(n_test, seed=2, keep_truth=True)
    ucc = load_ucc()
    rider = [tr for tr in ucc if tr.meta["mobility"] in ev_cfg.real_fa_mobility]
    real_cal, real_test = split_files(rider)
    log(f"real traces: {len(rider)} rider-like ({sum(t.duration_s for t in rider) / 3600:.1f} h); "
        f"cal {len(real_cal)}, test {len(real_test)}")
    inj_test = injection_set(real_test, n_inj, seed=3)

    def ds_info(trs):
        evs = sum(len(evaluable_onsets(t, ev_cfg)) for t in trs)
        return {"profiles": len(trs), "loss_events": evs, "negatives": sum(1 for t in trs if not t.episodes),
                "hours": sum(t.duration_s for t in trs) / 3600}
    M["data"] = {"sim_cal": ds_info(sim_cal), "sim_test": ds_info(sim_test), "real_cal": ds_info(real_cal),
                 "real_test": ds_info(real_test), "inj_test": ds_info(inj_test),
                 "ucc_total_traces": len(ucc), "ucc_total_hours": sum(t.duration_s for t in ucc) / 3600,
                 "ucc_hours_by_mobility": {m: sum(t.duration_s for t in ucc if t.meta["mobility"] == m) / 3600
                                           for m in sorted({t.meta["mobility"] for t in ucc})}}
    log(f"data: {json.dumps(jsonable(M['data']))}")

    # ------------------------------------------------------------------ 2. calibration
    log("calibrating simulator noise against real traces")
    cal_table, tgt = calibrate(ucc, n=300 if args.quick else 500)
    cal_table.to_csv(TAB / "calibration_grid.csv", index=False)
    st = sim_stats(DEFAULT, n=300 if args.quick else 600)
    M["calibration"] = {
        "chosen": {"rsrp_noise_db_moving": DEFAULT.meas.rsrp_noise_db_moving,
                   "shadow_sd_out": DEFAULT.env.shadow_sd_out, "shadow_dcorr_out": DEFAULT.env.shadow_dcorr_out},
        "grid_best": cal_table.iloc[0][["rsrp_noise_db_moving", "shadow_sd_out", "shadow_dcorr_out"]].to_dict(),
        "real": {m: {k: tgt[m][k] for k in STATS + ("p_slope_lt_1p5",)} for m in tgt},
        "sim": {k: {s: st[k][s] for s in STATS + ("p_slope_lt_1p5",)} for k in st}}
    plots.calibration({"walk": tgt["pedestrian"]["slopes"], "ride": tgt["bus"]["slopes"]},
                      {"walk": st["walk"]["slopes"], "ride": st["ride"]["slopes"]}, FIG / "fig8_calibration.png")

    # ------------------------------------------------------------------ 3. decay characterisation
    log("characterising decay at entrances")
    rows = []
    for tr in sim_test:
        te = tr.meta.get("t_entry", np.nan)
        on = evaluable_onsets(tr, ev_cfg)
        if tr.kind not in POSITIVE_KINDS or not np.isfinite(te):
            continue
        T = on[0] if on else np.nan
        hi = T if np.isfinite(T) else te + 40
        sl = steepest_slope(tr.t, tr.rsrp, te - 5, hi)
        s_true = tr.truth["s_dbm"]
        tt = tr.truth["t"]
        drop_rate = ((np.interp(te, tt, s_true) - np.interp(T, tt, s_true)) / (T - te)) if np.isfinite(T) and T > te else np.nan
        rows.append(dict(kind=tr.kind, loss=bool(np.isfinite(T)), entry_to_loss=T - te if np.isfinite(T) else np.nan,
                         steepest_slope=sl, mean_decay_db_s=drop_rate))
    dec = pd.DataFrame(rows)
    dec.to_csv(TAB / "decay_events.csv", index=False)
    bg = {m: tgt[m]["slopes"] for m in ("car", "bus", "pedestrian")}
    dec_tab = dec.groupby("kind").agg(entries=("loss", "size"), loss_rate=("loss", "mean"),
                                      entry_to_loss_median=("entry_to_loss", "median"),
                                      p_entry_to_loss_ge10=("entry_to_loss", lambda x: np.mean(x.dropna() >= 10)),
                                      steepest_slope_median=("steepest_slope", "median"),
                                      mean_decay_median=("mean_decay_db_s", "median"))
    dec_tab.to_csv(TAB / "decay_by_scenario.csv")
    ev = dec[dec.loss]
    M["decay"] = {"by_scenario": dec_tab.reset_index().to_dict(orient="records"),
                  "entry_slope_p_lt_1p5": float(np.mean(dec.steepest_slope < -1.5)),
                  "background_p_lt_1p5": {m: float(np.mean(np.asarray(v) < -1.5)) for m, v in bg.items()},
                  "physical_ceiling_10s": float(np.mean(ev.entry_to_loss >= 10)),
                  "physical_ceiling_15s": float(np.mean(ev.entry_to_loss >= 15)),
                  "entry_to_loss_median": float(ev.entry_to_loss.median())}
    pretty = {"basement_ride": "Scooter down a ramp", "basement_walk": "Walk down to a basement",
              "deep_indoor": "Walk deep into a hub"}
    plots.decay_characterisation(
        {f"Entry: {pretty[k].lower()} (sim)": dec[dec.kind == k].steepest_slope.dropna().to_numpy()
         for k in POSITIVE_KINDS},
        {f"Normal {m} (real, all windows)": bg[m] for m in ("car", "bus", "pedestrian")},
        {pretty[k]: ev[ev.kind == k].entry_to_loss.to_numpy() for k in POSITIVE_KINDS},
        FIG / "fig2_decay.png")

    # ------------------------------------------------------------------ 4-5. tuning
    params_path = RES / "params.json"
    cache = {}
    if args.reuse_params and params_path.exists():
        params = json.loads(params_path.read_text())["params"]
        log("reusing tuned parameters")
    else:
        params, tune_rows = {}, {}
        for name in ("threshold", "naive_gradient", "slope_ttl", "sanket"):
            t1 = time.time()
            df, best = search(name, sim_cal, real_cal, banks_cache=cache)
            params[name] = params_from_row(name, best)
            tune_rows[name] = {k: (None if (isinstance(v, float) and not math.isfinite(v)) else v)
                               for k, v in best.items() if k.startswith(("sim_", "real_"))}
            out = df.copy()
            if "veto" in out:
                out["veto"] = out["veto"].astype(str)
            out.to_csv(TAB / f"grid_{name}.csv.gz", index=False, compression="gzip")
            log(f"tuned {name}: {len(df)} settings in {time.time() - t1:.0f}s -> {params[name]}")
        sf = {k: params["sanket"][k] for k in ("meas_sd", "q")}
        dfl, bestl, lp, info = search_logistic(sim_cal, real_cal, sf, params["sanket"]["theta"],
                                               params["sanket"]["horizon"], banks_cache=cache)
        params["logistic"] = lp
        tune_rows["logistic"] = {k: v for k, v in bestl.items() if k.startswith(("sim_", "real_"))}
        dfl.to_csv(TAB / "grid_logistic.csv", index=False)
        log(f"tuned logistic: tau={lp['tau']} persistence={lp['persistence']} ({info})")
        params_path.write_text(json.dumps(jsonable({"params": params, "calibration_scores": tune_rows,
                                                    "logistic_fit": info}), indent=2))
        M["tuning_cal_scores"] = tune_rows
    M["params"] = params
    facs = {n: factory(n, params[n]) for n in DETECTORS}
    cache.clear()                     # tuning banks are large; free them before the rest of the run

    # budget-matched design evidence from the SANKET grid (calibration data)
    g = pd.read_csv(TAB / "grid_sanket.csv.gz")
    def best_at(d, b):
        d = d[d.real_fa_per_h <= b]
        return float(d.sim_recall10.max()) if len(d) else float("nan")
    M["design_evidence_cal"] = {
        f"fa_le_{b}": {"full_grid": best_at(g, b), "without_common_mode": best_at(g[g.veto.isna()], b),
                       "with_common_mode": best_at(g[g.veto.notna()], b),
                       "rsrq_off": best_at(g[g.rsrq_mode == "off"], b), "rsrq_confirm": best_at(g[g.rsrq_mode == "confirm"], b)}
        for b in (1, 2, 5, 10)}

    # ------------------------------------------------------------------ 6. final evaluation (streaming)
    log("evaluating every detector on unseen test data (streaming implementation)")
    test, outcomes = {}, {}
    for n in DETECTORS:
        s_sim, o_sim = evaluate(facs[n], sim_test, ev_cfg)
        s_real, o_real = evaluate(facs[n], real_test, ev_cfg)
        s_inj, o_inj = evaluate(facs[n], inj_test, ev_cfg)
        test[n] = {"sim": slim(s_sim), "real": slim(s_real), "inj": slim(s_inj)}
        outcomes[n] = {"sim": o_sim, "real": o_real, "inj": o_inj}
        log(f"  {n:15s} sim recall10={s_sim['recall10']:.3f} spec={s_sim['specificity']:.3f} "
            f"acc={s_sim['accuracy']:.3f} | real FA/h={s_real['fa_per_h']:.2f} | inj recall10={s_inj['recall10']:.3f}")
    M["test"] = test
    # bulk path must agree with the streaming path on unseen data too
    nm = "sanket"
    fp = {k: params[nm][k] for k in ("meas_sd", "q")}
    bank = fx.extract(factory(nm, fp), sim_test, ev_cfg)
    c = {k: v for k, v in params[nm].items() if k not in ("meas_sd", "q")}
    cond = fx.cond_sanket(bank.F, fp["q"], c["theta"], c["horizon"], c["p_thr"], c["min_rate"],
                          rsrq_mode=c.get("rsrq_mode", "off"), nbr_veto=c.get("nbr_veto", False),
                          nbr_delta=c.get("nbr_delta", 6.0), nbr_diff=c.get("nbr_diff", 1.0))
    idx = fx.fire(bank, cond, c["persistence"], COOLDOWN_S)
    bulk = sorted((bank.names[bank.tid[i]], float(bank.t[i])) for i in idx)
    stream = sorted((o.trace, a) for o in outcomes[nm]["sim"] for a in o.alarms)
    M["consistency_bulk_equals_streaming"] = bulk == stream
    log(f"bulk == streaming on test set: {bulk == stream} ({len(stream)} alarms)")
    if bulk != stream:
        raise SystemExit("bulk and streaming paths disagree - refusing to report results")

    # per-scenario breakdown (SANKET)
    per = []
    for kind in POSITIVE_KINDS + CONTROL_KINDS:
        oo = [o for o in outcomes["sanket"]["sim"] if o.kind == kind]
        s = summarize(oo, ev_cfg, with_ci=False)
        thr = summarize([o for o in outcomes["threshold"]["sim"] if o.kind == kind], ev_cfg, with_ci=False)
        per.append({"scenario": kind, "profiles": len(oo), "loss_events": s["events"], "recall10": s["recall10"],
                    "recall15": s["recall15"], "median_lead": s["median_lead"], "negatives": s["negatives"],
                    "specificity": s["specificity"], "threshold_recall10": thr["recall10"],
                    "threshold_specificity": thr["specificity"]})
    pd.DataFrame(per).to_csv(TAB / "sanket_by_scenario.csv", index=False)
    M["sanket_by_scenario"] = per
    # oracle = time from entrance to loss, for the building-entry events (they all have an entrance)
    oracle = [e.oracle_lead for o in outcomes["sanket"]["sim"] if o.kind in POSITIVE_KINDS for e in o.events]
    M["oracle"] = {"events": len(oracle), "p_ge_10": float(np.mean(np.asarray(oracle) >= 10)),
                   "p_ge_15": float(np.mean(np.asarray(oracle) >= 15))}

    # ------------------------------------------------------------------ 7. trade-off curves (test)
    log("tracing detection vs false-alarm curves (every grid setting, bulk path, test data)")

    def envelope(pts):
        best_at = {}
        for fa, rec in pts:                         # one point per false-alarm level: the best recall
            k = round(max(fa, 0.1), 6)
            best_at[k] = max(best_at.get(k, -1.0), rec)
        pts = sorted(best_at.items())
        front, best = [], -1.0                      # best recall reachable at each false-alarm level
        for fa, rec in pts:
            if rec > best:
                front.append((fa, rec))
                best = rec
        return [f for f, _ in front], [r for _, r in front]

    curves, sk_banks = {}, None
    for n in ("threshold", "naive_gradient", "slope_ttl", "sanket"):
        filters = GRIDS[n]["filter"]
        if n == "sanket":                           # the tuned filter; every trigger setting
            filters = [{k: params["sanket"][k] for k in ("meas_sd", "q")}]
        pts = []
        for fparams in filters:
            b_s = fx.extract(factory(n, fparams), sim_test, ev_cfg)
            b_r = fx.extract(factory(n, fparams), real_test, ev_cfg)
            for c in _combos(GRIDS[n]["cond"]):
                m = c.get("persistence", 1)
                a_s = fx.fire(b_s, condition(n, b_s.F, fparams, c), m, COOLDOWN_S)
                a_r = fx.fire(b_r, condition(n, b_r.F, fparams, c), m, COOLDOWN_S)
                pts.append((fx.score(b_r, a_r, ev_cfg)["fa_per_h"], fx.score(b_s, a_s, ev_cfg)["recall10"]))
            if n == "sanket":
                sk_banks = (b_s, b_r)
        curves[n] = envelope(pts)
        log(f"  {n}: {len(pts)} settings")
    lp = params["logistic"]
    b_s, b_r = sk_banks
    Xs = fx.logistic_matrix(b_s.F, lp["theta"], lp["horizon"], lp["q"])
    Xr = fx.logistic_matrix(b_r.F, lp["theta"], lp["horizon"], lp["q"])
    pts = []
    for tau in (0.3, 0.5, 0.6, 0.7, 0.8, 0.85, 0.9, 0.93, 0.95, 0.97, 0.98, 0.99, 0.995, 0.998, 0.999):
        for m in (1, 2, 3):
            a_s = fx.fire(b_s, fx.cond_logistic(b_s.F, Xs, lp["weights"], lp["bias"], tau), m, COOLDOWN_S)
            a_r = fx.fire(b_r, fx.cond_logistic(b_r.F, Xr, lp["weights"], lp["bias"], tau), m, COOLDOWN_S)
            pts.append((fx.score(b_r, a_r, ev_cfg)["fa_per_h"], fx.score(b_s, a_s, ev_cfg)["recall10"]))
    curves["logistic"] = envelope(pts)
    del sk_banks, b_s, b_r
    M["tradeoff_curves"] = curves

    # ------------------------------------------------------------------ 8. ablations (test)
    log("ablations")
    base = dict(params["sanket"])
    variants = {"SANKET (tuned)": base,
                "without common-mode test": dict(base, nbr_veto=False),
                "with RSRQ confirmation": dict(base, rsrq_mode="confirm"),
                "with RSRQ dual risk": dict(base, rsrq_mode="dual"),
                "without de-duplication of stale readings": dict(base, dedupe=False),
                "without outlier gating": dict(base, gate=0.0),
                "persistence + 1": dict(base, persistence=int(base["persistence"]) + 1)}
    abl = []
    for label, p in variants.items():
        f = factory("sanket", p)
        s, _ = evaluate(f, sim_test, ev_cfg)
        r, _ = evaluate(f, real_test, ev_cfg)
        i, _ = evaluate(f, inj_test, ev_cfg)
        abl.append({"variant": label, "sim_recall10": s["recall10"], "sim_recall15": s["recall15"],
                    "sim_specificity": s["specificity"], "real_fa_per_h": r["fa_per_h"], "inj_recall10": i["recall10"]})
    pd.DataFrame(abl).to_csv(TAB / "ablation.csv", index=False)
    M["ablation"] = abl

    # ------------------------------------------------------------------ 9. sensitivity
    log("sensitivity analysis")
    sens = []
    def cfg_with(section, **kw):
        return dataclasses.replace(DEFAULT, **{section: dataclasses.replace(getattr(DEFAULT, section), **kw)})
    settings = [("baseline", DEFAULT)]
    settings += [(f"report every {p:g} s", cfg_with("meas", report_periods_s=(p,), report_probs=(1.0,))) for p in (1.0, 2.0, 3.0, 5.0)]
    settings += [(f"shadowing sigma {v:g} dB", cfg_with("env", shadow_sd_out=v)) for v in (4.0, 5.0)]
    settings += [(f"moving noise {v:g} dB", cfg_with("meas", rsrp_noise_db_moving=v)) for v in (2.8, 4.2)]
    settings += [(f"indoor cell difference {v:g} dB", cfg_with("env", indoor_cell_diff_sd=v)) for v in (1.0, 4.0)]
    settings += [(f"Qrxlevmin {v:g} dBm", cfg_with("radio", qrxlevmin_dbm=v)) for v in (-120.0, -128.0)]
    n_s = 250 if args.quick else 800
    for label, cfg in settings:
        trs = simulate_dataset(n_s, seed=21, cfg=cfg, keep_truth=False)
        s, oo = evaluate(facs["sanket"], trs, cfg.eval)
        orc = [e.oracle_lead for o in oo for e in o.events]
        sens.append({"setting": label, "events": s["events"], "recall10": s["recall10"], "recall15": s["recall15"],
                     "specificity": s["specificity"], "median_lead": s["median_lead"],
                     "physical_ceiling_10s": float(np.mean(np.asarray(orc) >= 10)) if orc else float("nan")})
    pd.DataFrame(sens).to_csv(TAB / "sensitivity.csv", index=False)
    M["sensitivity"] = sens

    # ------------------------------------------------------------------ 10. prefetch
    log("prefetch latency model")
    leads = [0.5, 1.0, 1.5, 2.0, 3.0, 4.0, 5.0, 7.0, 10.0, 15.0]
    pos = [t for t in sim_test if t.episodes and t.kind in POSITIVE_KINDS]
    cur = completion_vs_lead(pos, leads, np.random.default_rng(5))
    req99 = {k: required_lead(leads, v, 0.99) for k, v in cur.items()}
    req95 = {k: required_lead(leads, v, 0.95) for k, v in cur.items()}
    tiers = [nm_ for nm_, _ in DEFAULT.prefetch.tiers]
    crit = max(req99[k] for k in tiers[:3])
    full = req99[tiers[3]]
    M["prefetch"] = {"leads": leads, "completion": cur, "required_lead_99": req99, "required_lead_95": req95,
                     "critical_required_99": crit, "full_required_99": full,
                     "tier_bytes": {nm_: b for nm_, b in DEFAULT.prefetch.tiers}}
    plots.prefetch_curves(leads, cur, req99, FIG / "fig5_prefetch.png")
    plots.prefetch_curves(leads, cur, req99, FIG / "fig5_prefetch_small.png", compact=True)
    # end-to-end: does the data actually arrive when each detector raises the alarm?
    e2e = {}
    rng = np.random.default_rng(9)
    shares = {tr.name: float(rng.uniform(*DEFAULT.prefetch.share)) for tr in sim_test}
    by_name = {tr.name: tr for tr in sim_test}
    for n in DETECTORS:
        crit_ok = full_ok = n_ev = 0
        lead_crit = 0
        for o in outcomes[n]["sim"]:
            for e in o.events:
                n_ev += 1
                if not np.isfinite(e.lead):
                    continue
                tr = by_name[o.trace]
                done = run_prefetch(Link(tr.truth["t"], tr.truth["sinr"], shares[tr.name]), e.onset - e.lead, e.onset)
                crit_ok += all(np.isfinite(done[:3]))
                full_ok += all(np.isfinite(done))
                lead_crit += e.lead >= crit
        e2e[n] = {"events": n_ev, "critical_data_saved": crit_ok / n_ev, "full_bundle_saved": full_ok / n_ev,
                  "recall_at_critical_lead": lead_crit / n_ev}
    M["end_to_end"] = e2e
    log(f"critical tiers need {crit:g} s, full bundle {full:g} s; end-to-end: "
        + ", ".join(f"{n}={v['critical_data_saved']:.2f}/{v['full_bundle_saved']:.2f}" for n, v in e2e.items()))

    # ------------------------------------------------------------------ 11. data cost
    cost = {}
    for n in DETECTORS:
        fa = test[n]["real"]["fa_per_h"]
        cost[n] = {"fa_per_h": fa, "mb_per_day_full_refetch": daily_cost_mb(fa, conditional=False),
                   "mb_per_day_etag": daily_cost_mb(fa, conditional=True)}
    M["data_cost_10h_shift"] = cost

    # ------------------------------------------------------------------ 12. fleet memory
    log("fleet dead-zone memory simulation")
    fleet_runs = {}
    fleet_settings = {"main": dict(n_sites=300, visits_per_day=400, alpha=1.0),
                      "flatter_popularity": dict(n_sites=300, visits_per_day=400, alpha=0.7),
                      "bigger_city": dict(n_sites=900, visits_per_day=400, alpha=1.0)}
    if args.quick:
        fleet_settings = {"main": dict(n_sites=80, visits_per_day=60, alpha=1.0)}
    for key, kw in fleet_settings.items():
        rows_f = run_fleet(facs["sanket"], days=7, seed=7, **kw)
        dsum = daily_summary(rows_f, ev_cfg.min_lead_s)
        dsum.to_csv(TAB / f"fleet_{key}.csv", index=False)
        dfr = pd.DataFrame(rows_f)
        pub_noloss = dfr[dfr.published & ~dfr.loss]
        fleet_runs[key] = {"settings": kw, "daily": dsum.to_dict(orient="records"),
                           "memory_false_trigger_rate": float(pub_noloss.memory_false.mean()) if len(pub_noloss) else None,
                           "visits": len(dfr)}
        if key == "main":
            plots.fleet(dsum, FIG / "fig6_fleet.png", ev_cfg.target_accuracy)
            plots.fleet(dsum, FIG / "fig6_fleet_small.png", ev_cfg.target_accuracy, compact=True)
        log(f"  fleet[{key}]: day1 hybrid={dsum.hybrid.iloc[0]:.2f} day7 hybrid={dsum.hybrid.iloc[-1]:.2f} "
            f"signal={dsum.signal.mean():.2f}")
    M["fleet"] = fleet_runs

    # ------------------------------------------------------------------ 13. runtime footprint
    det = SanketDetector(**{k: v for k, v in params["sanket"].items()})
    tr = sim_test[0]
    xs = [(float(t), (None if not np.isfinite(r) else float(r)), (None if not np.isfinite(q) else float(q)),
           (None if not np.isfinite(nb) else float(nb))) for t, r, q, nb in zip(tr.t, tr.rsrp, tr.rsrq, tr.nbr)]
    n_ops = 0
    t1 = time.perf_counter()
    for rep in range(200):
        det.reset()
        for t, r, q, nb in xs:
            det.step(t + rep * 10_000.0, r, q, nb)
            n_ops += 1
    us = (time.perf_counter() - t1) / n_ops * 1e6
    M["runtime"] = {"python_us_per_reading": us, "readings_timed": n_ops,
                    "state_floats": 3 * 7 + 6, "note": "pure Python, single core"}
    log(f"runtime: {us:.1f} us per reading in pure Python")

    # ------------------------------------------------------------------ 14. figures
    log("figures")
    # anatomy: a scooter ramp entry where SANKET warns >= 10 s ahead
    names = sorted(by_name)
    o_s = {o.trace: o for o in outcomes["sanket"]["sim"]}
    o_t = {o.trace: o for o in outcomes["threshold"]["sim"]}
    cand = []
    for nm_ in names:
        tr = by_name[nm_]
        if tr.kind != "basement_ride" or not o_s[nm_].events:
            continue
        e = o_s[nm_].events[0]
        if np.isfinite(e.lead) and 12 <= e.lead <= 30 and not o_s[nm_].false_alarms:
            cand.append((abs(e.lead - 16), nm_))
    if cand:
        nm_ = sorted(cand)[0][1]
        tr = by_name[nm_]
        T = tr.episodes[0][0]
        first = lambda o: next((a for a in o.alarms if T - ev_cfg.max_lead_s <= a < T), None)  # noqa: E731
        plots.anatomy(tr, {"sanket": first(o_s[nm_]), "threshold": first(o_t[nm_])}, FIG / "fig1_anatomy.png",
                      "A rider takes a scooter down a basement ramp (unseen test profile)")
        M["anatomy_example"] = {"trace": nm_, "geometry": tr.meta["geometry"], "s0": tr.meta["s0"]}
    chosen = {n: (max(test[n]["real"]["fa_per_h"], 0.1), test[n]["sim"]["recall10"]) for n in DETECTORS}
    plots.tradeoff(curves, chosen, FIG / "fig3_tradeoff.png", ev_cfg.target_accuracy)
    leads_by = {n: [e.lead for o in outcomes[n]["sim"] if o.kind in POSITIVE_KINDS for e in o.events]
                for n in ("sanket", "slope_ttl", "threshold")}
    plots.lead_cdf(leads_by, oracle, FIG / "fig4_leads.png")
    # common-mode evidence (calibration data, the analysis that motivated the test)
    loose = dict(params["sanket"], nbr_veto=False)
    fp = {k: loose[k] for k in ("meas_sd", "q")}
    b_r = fx.extract(factory("sanket", fp), real_cal, ev_cfg)
    b_s = fx.extract(factory("sanket", fp), sim_cal, ev_cfg)
    cl = lambda F: fx.cond_sanket(F, fp["q"], loose["theta"], loose["horizon"], loose["p_thr"], loose["min_rate"],  # noqa: E731
                                  rsrq_mode=loose.get("rsrq_mode", "off"))
    a_r = fx.fire(b_r, cl(b_r.F), loose["persistence"], COOLDOWN_S)
    a_s = fx.fire(b_s, cl(b_s.F), loose["persistence"], COOLDOWN_S)
    fa_i = a_r[b_r.fa_eligible[a_r]]
    fa_i = fa_i[b_r.F["n_n"][fa_i] >= 2]
    j = np.searchsorted(a_s, b_s.ev_lo)
    ok = j < a_s.size
    firsts = a_s[np.where(ok, j, 0)]
    tp_i = firsts[ok & (firsts < b_s.ev_hi)]
    tp_i = tp_i[b_s.F["n_n"][tp_i] >= 2]
    plots.common_mode(np.column_stack([b_r.F["rate"][fa_i], b_r.F["n_rate"][fa_i]]),
                      np.column_stack([b_s.F["rate"][tp_i], b_s.F["n_rate"][tp_i]]), FIG / "fig7_common_mode.png")
    M["common_mode_analysis"] = {
        "real_false_alarms_with_neighbour": int(fa_i.size), "sim_true_detections_with_neighbour": int(tp_i.size),
        "median_rate_gap_false_alarms": float(np.median(b_r.F["n_rate"][fa_i] - b_r.F["rate"][fa_i])) if fa_i.size else None,
        "median_rate_gap_true": float(np.median(b_s.F["n_rate"][tp_i] - b_s.F["rate"][tp_i])) if tp_i.size else None}

    # ------------------------------------------------------------------ 15. golden vectors + dashboard data
    log("golden vectors and dashboard data")
    export_golden(params["sanket"], sim_test, inj_test)
    export_dashboard(params, sim_test, inj_test, featured=M.get("anatomy_example", {}).get("trace"))

    # ------------------------------------------------------------------ 16. headline
    s = test["sanket"]
    d7 = fleet_runs["main"]["daily"][-1]
    M["headline"] = {
        "sanket_sim_recall10": s["sim"]["recall10"], "sanket_sim_recall10_ci": s["sim"]["recall10_ci"],
        "sanket_sim_recall15": s["sim"]["recall15"], "sanket_sim_specificity": s["sim"]["specificity"],
        "sanket_sim_accuracy": s["sim"]["accuracy"], "sanket_real_fa_per_h": s["real"]["fa_per_h"],
        "sanket_real_fa_per_h_ci": s["real"]["fa_per_h_ci"], "sanket_inj_recall10": s["inj"]["recall10"],
        "sanket_median_lead": s["sim"]["median_lead"], "threshold_sim_recall10": test["threshold"]["sim"]["recall10"],
        "threshold_real_fa_per_h": test["threshold"]["real"]["fa_per_h"],
        "critical_required_lead_s": crit, "full_required_lead_s": full,
        "sanket_critical_data_saved": e2e["sanket"]["critical_data_saved"],
        "threshold_critical_data_saved": e2e["threshold"]["critical_data_saved"],
        "physical_ceiling_10s": M["oracle"]["p_ge_10"],
        "hybrid_day1": fleet_runs["main"]["daily"][0]["hybrid"], "hybrid_day7": d7["hybrid"],
        "hybrid_days_to_target": next((r["day"] for r in fleet_runs["main"]["daily"]
                                       if r["hybrid"] >= ev_cfg.target_accuracy), None),
        "runtime_us": us,
    }
    (RES / "metrics.json").write_text(json.dumps(jsonable(M), indent=2))
    export_dashboard_metrics(M)
    write_results_md(M)
    log("done -> results/metrics.json, results/RESULTS.md, results/figures/")


def export_golden(p, sim_test, inj_test):
    """Per-tick reference outputs so the JavaScript and Java ports can be checked exactly."""
    picks = []
    for kind in ("basement_ride", "basement_walk", "deep_indoor", "cell_edge_handover", "underpass"):
        picks += [t for t in sim_test if t.kind == kind][:2]
    picks += inj_test[:2]
    # synthetic edge cases: glitches, gaps, repeats, out-of-range values
    t = np.arange(0, 120, 1.0)
    r = np.where(t < 50, -92.0, -92.0 - 2.5 * (t - 50)).round()
    r[[10, 11]] = np.nan
    r[30] = -200.0
    r[70:75] = np.nan
    q = np.full(t.size, -11.0)
    nb = np.where(t < 60, -95.0, np.nan)
    from sanket.simulate import Trace
    picks.append(Trace("edge-cases", "synthetic", t, r, q, nb, np.full(t.size, -1), [], {}))
    out = {"params": p, "traces": []}
    for tr in picks:
        det = SanketDetector(**p)
        ticks = []
        for i in range(tr.t.size):
            rr = float(tr.rsrp[i]) if np.isfinite(tr.rsrp[i]) else None
            qq = float(tr.rsrq[i]) if np.isfinite(tr.rsrq[i]) else None
            nn = float(tr.nbr[i]) if np.isfinite(tr.nbr[i]) else None
            alarm = det.step(float(tr.t[i]), rr, qq, nn)
            sn = det.snapshot()
            ticks.append([float(tr.t[i]), rr, qq, nn, bool(alarm), sn["n"], sn["level"], sn["rate"],
                          sn["p00"], sn["p01"], sn["p11"]])
        out["traces"].append({"name": tr.name, "ticks": ticks})
    (ROOT / "data" / "golden").mkdir(parents=True, exist_ok=True)
    (ROOT / "data" / "golden" / "sanket_golden.json").write_text(json.dumps(jsonable(out)))


def export_dashboard(params, sim_test, inj_test, featured=None):
    """A small curated set of traces for the interactive demo (detectors run live in JS)."""
    sel = [t for t in sim_test if t.name == featured]
    for kind in POSITIVE_KINDS + CONTROL_KINDS:
        sel += [t for t in sim_test if t.kind == kind and t.name != featured
                and (bool(t.episodes) == (kind in POSITIVE_KINDS))][:6]
    sel += inj_test[:6]
    items = []
    for tr in sel:
        item = {"name": tr.name, "kind": tr.kind, "featured": tr.name == featured, "t": tr.t.tolist(),
                "rsrp": tr.rsrp.tolist(),
                "rsrq": tr.rsrq.tolist(), "nbr": tr.nbr.tolist(),
                "episodes": [[e[0], e[1] if math.isfinite(e[1]) else None] for e in tr.episodes],
                "t_entry": tr.meta.get("t_entry")}
        if tr.truth is not None and "s_dbm" in tr.truth:
            tt = tr.truth["t"]
            item["true_rsrp"] = np.interp(tr.t, tt, tr.truth["s_dbm"]).round(1).tolist()
            item["sinr"] = np.interp(tr.t, tt, tr.truth["sinr"]).round(1).tolist()
            item["pos"] = np.interp(tr.t, tt, tr.truth["s"]).round(1).tolist()
            item["loss_db"] = np.interp(tr.t, tt, tr.truth["L"]).round(1).tolist()
        elif tr.truth is not None and "level" in tr.truth:
            item["true_rsrp"] = np.round(tr.truth["level"], 1).tolist()
            item["loss_db"] = np.round(tr.truth["L"], 1).tolist()
        items.append(item)
    web = ROOT / "web"
    web.mkdir(exist_ok=True)
    payload = {"params": params, "traces": items,
               "source_note": "Simulated profiles (SANKET simulator) and real UCC LTE traces with an injected "
                              "basement entry (Raca et al., MMSys 2018, CC-BY-4.0)."}
    (web / "data.js").write_text("window.SANKET_DATA = " + json.dumps(jsonable(payload)) + ";\n")


def export_dashboard_metrics(M):
    """The numbers the demo page quotes, straight from this run (nothing typed by hand)."""
    keep = {"generated_utc": M["generated_utc"], "test": M["test"], "params": M["params"],
            "prefetch": {k: M["prefetch"][k] for k in ("required_lead_99", "critical_required_99",
                                                       "full_required_99", "tier_bytes")},
            "end_to_end": M["end_to_end"], "oracle": M["oracle"], "data": M["data"],
            "fleet_daily": M["fleet"]["main"]["daily"], "headline": M["headline"],
            "cost": M["data_cost_10h_shift"]}
    (ROOT / "web" / "metrics.js").write_text("window.SANKET_METRICS = " + json.dumps(jsonable(keep)) + ";\n")


def write_results_md(M):
    t = M["test"]
    lines = ["# SANKET - results (auto-generated by experiments/run_all.py)", "",
             f"Generated {M['generated_utc']} - version {M['version']}", "",
             "## Test-set results (tuned on calibration data only)", "",
             "| Detector | Sim: warned >= 10 s | Sim: >= 15 s | Silent when no loss followed | Sim accuracy | Real false alarms / h | Real-noise entries >= 10 s | Median lead (s) |",
             "|---|---|---|---|---|---|---|---|"]
    lab = plots.LABEL
    for n in DETECTORS:
        s, r, i = t[n]["sim"], t[n]["real"], t[n]["inj"]
        lines.append(f"| {lab[n]} | {s['recall10'] * 100:.1f}% | {s['recall15'] * 100:.1f}% | {s['specificity'] * 100:.1f}% | "
                     f"{s['accuracy'] * 100:.1f}% | {r['fa_per_h']:.2f} | {i['recall10'] * 100:.1f}% | {s['median_lead']:.1f} |")
    h = jsonable(M["headline"])
    def fmt(v):
        if isinstance(v, list):
            return "[" + ", ".join(fmt(x) for x in v) + "]"
        return f"{v:.4g}" if isinstance(v, float) else str(v)
    lines += ["", "## Headline", ""] + [f"- **{k}**: {fmt(v)}" for k, v in h.items()]
    (RES / "RESULTS.md").write_text("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
