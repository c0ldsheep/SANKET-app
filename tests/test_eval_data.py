"""Evaluation rules, statistics, real-data loading, field logs, injection and prefetch."""
import math

import numpy as np
import pytest

from sanket.config import EvalConfig
from sanket.evaluate import match, poisson_rate_ci, summarize, wilson
from sanket.prefetch import Link, daily_cost_mb, required_lead, run_prefetch
from sanket.realdata import (UCC_ZIP, hold_fill, inject_descent, candidate_windows, load_field_log, load_ucc,
                             lte_episodes, resequence_seconds, split_files)
from sanket.simulate import Trace


def trace(n=300, episodes=(), name="x"):
    t = np.arange(n, dtype=float)
    r = np.full(n, -95.0)
    for on, end, _ in episodes:
        r[(t >= on) & (t < end)] = np.nan
    return Trace(name, "test", t, r, np.full(n, -11.0), np.full(n, np.nan), np.full(n, -1), list(episodes), {})


def test_match_classifies_timely_late_missed_and_false():
    cfg = EvalConfig()
    tr = trace(episodes=[(100.0, 150.0, "x"), (220.0, 260.0, "x")])
    o = match(tr, [10.0, 85.0, 120.0, 215.0], cfg)
    assert [e.status for e in o.events] == ["timely", "late"]
    assert o.events[0].lead == 15.0 and o.events[1].lead == 5.0
    assert o.false_alarms == [10.0]                     # 120 s is during the outage, not counted
    tr2 = trace(episodes=[(100.0, 150.0, "x")])
    assert match(tr2, [], cfg).events[0].status == "missed"


def test_alarm_too_early_is_false_not_a_detection():
    cfg = EvalConfig()
    tr = trace(episodes=[(200.0, 250.0, "x")])
    o = match(tr, [100.0], cfg)                         # 100 s before the loss (> 60 s window)
    assert o.events[0].status == "missed" and o.false_alarms == [100.0]


def test_exposure_excludes_outages_and_prewindows():
    cfg = EvalConfig()
    tr = trace(n=300, episodes=[(100.0, 150.0, "x")])
    o = match(tr, [], cfg)
    assert o.exposure_s == pytest.approx(300 - (150 - 40))


def test_summary_counts_and_accuracy():
    cfg = EvalConfig()
    pos = match(trace(episodes=[(100.0, 150.0, "x")], name="p"), [80.0], cfg)
    neg_ok = match(trace(name="n1"), [], cfg)
    neg_bad = match(trace(name="n2"), [50.0], cfg)
    s = summarize([pos, neg_ok, neg_bad], cfg)
    assert s["recall10"] == 1.0 and s["specificity"] == 0.5 and s["accuracy"] == pytest.approx(2 / 3)
    assert s["false_alarms"] == 1


def test_confidence_intervals():
    lo, hi = wilson(85, 100)
    assert lo == pytest.approx(0.7672, abs=1e-3) and hi == pytest.approx(0.9069, abs=1e-3)
    lo, hi = poisson_rate_ci(0, 10.0)
    assert lo == 0.0 and hi == pytest.approx(0.3689, abs=1e-3)


# ------------------------------------------------------------------ real data
def test_ucc_dataset_loads_with_checksum():
    trs = load_ucc()
    assert len(trs) == 135
    assert sum(t.duration_s for t in trs) / 3600 == pytest.approx(52.4, abs=0.3)
    cal, test = split_files([t for t in trs if t.meta["mobility"] != "train"])
    assert not {t.name for t in cal} & {t.name for t in test}


def test_resequence_and_hold_fill():
    sec, keep = resequence_seconds(np.array([0, 1, 1, 3, 3, 3, 7]))
    assert sec[keep].tolist() == [0, 1, 2, 3, 4, 7] and keep.tolist() == [1, 1, 1, 1, 1, 0, 1]
    sec, keep = resequence_seconds(np.array([5, 5, 6, 6, 7, 7]))   # ~2 Hz logging: drift <= 1 s
    assert sec[keep].tolist() == [5, 6, 7, 8]
    assert np.all(sec[keep] - np.array([5, 5, 6, 6, 7, 7])[keep] <= 1)
    present = np.array([1, 0, 1, 0, 0, 0, 1], dtype=bool)
    a = np.array([1.0, np.nan, 3.0, np.nan, np.nan, np.nan, 7.0])
    hold_fill(present, (a,), max_gap=2)
    assert a.tolist()[:3] == [1.0, 1.0, 3.0] and np.isnan(a[3])     # 3-s gap is a real gap


def test_lte_episodes_need_three_seconds():
    t = np.arange(20, dtype=float)
    lte = np.ones(20, dtype=bool)
    lte[5:7] = False                                     # 2 s blip: ignored
    lte[10:15] = False                                   # 5 s: an episode
    assert lte_episodes(t, lte) == [(10.0, 15.0, "lte_lost")]


def test_field_log_formats(tmp_path):
    gnet = tmp_path / "gnettrack.txt"
    gnet.write_text("Timestamp\tLevel\tQual\tNetworkTech\n" + "\n".join(
        f"2026.10.06_10.00.{s:02d}\t{-90 - s}\t-11\t{'LTE' if s < 40 else 'UMTS'}" for s in range(50)))
    tr = load_field_log(gnet)
    assert tr.t.size == 50 and tr.rsrp[0] == -90 and np.isnan(tr.rsrp[45])
    assert tr.episodes and tr.episodes[0][0] == 40.0
    mine = tmp_path / "sanket_log.csv"
    mine.write_text("t_unix,rsrp,rsrq,nbr_rsrp,ci,marker\n" + "\n".join(
        f"{1790000000 + s},{-95 if s < 30 else ''},{-12 if s < 30 else ''},,,{'basement' if s == 20 else ''}"
        for s in range(40)))
    tr2 = load_field_log(mine)
    assert tr2.t.size == 40 and tr2.episodes[0][0] == 30.0 and tr2.meta["marks"] == [(20.0, "basement")]
    with pytest.raises(ValueError):
        bad = tmp_path / "bad.csv"
        bad.write_text("a,b\n1,2\n")
        load_field_log(bad)


def test_injection_preserves_holds_and_creates_a_loss():
    trs = [t for t in load_ucc(mobilities=("pedestrian",))]
    tr = next(t for t in trs if candidate_windows(t).size)
    i0 = int(candidate_windows(tr)[0])
    inj = inject_descent(tr, i0, np.random.default_rng(1), kind="basement_walk")
    real = tr.rsrp[i0 - 60:i0 + 150]
    before = slice(0, 55)
    assert np.array_equal(inj.rsrp[before], real[before], equal_nan=True)   # untouched before entry
    same_real = real[1:] == real[:-1]
    same_inj = inj.rsrp[1:] == inj.rsrp[:-1]
    ok = np.isfinite(inj.rsrp[1:]) & np.isfinite(inj.rsrp[:-1])
    assert np.all(same_inj[same_real & ok])                                   # holds stay holds
    assert inj.episodes and inj.episodes[0][0] > 60.0


# ------------------------------------------------------------------ prefetch
def test_prefetch_more_warning_never_hurts():
    t = np.arange(0, 60, 0.1)
    sinr = np.clip(10 - 0.5 * t, -15, 10)
    link = Link(t, sinr, share=0.2)
    T = 40.0
    done_counts = [sum(np.isfinite(run_prefetch(link, T - lead, T))) for lead in (0.2, 1, 2, 4, 8, 15)]
    assert done_counts == sorted(done_counts) and done_counts[0] == 0 and done_counts[-1] == 4


def test_prefetch_etag_hit_is_cheaper():
    t = np.arange(0, 30, 0.1)
    link = Link(t, np.full(t.size, 0.0), share=0.1)
    full = run_prefetch(link, 1.0, 29.0)
    cached = run_prefetch(link, 1.0, 29.0, cached={0, 1, 2, 3})
    assert cached[-1] < full[-1]


def test_cost_and_required_lead_helpers():
    assert required_lead([1, 2, 3], [0.5, 0.995, 1.0], 0.99) == 2
    assert math.isnan(required_lead([1, 2], [0.1, 0.2], 0.99))
    assert daily_cost_mb(2.0, hours=10, conditional=False) == pytest.approx(2 * 10 * 431_000 / 1e6)
    assert daily_cost_mb(2.0, hours=10, conditional=True) == pytest.approx(2 * 10 * 4 * 400 / 1e6)
