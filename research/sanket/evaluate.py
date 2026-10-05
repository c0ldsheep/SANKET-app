"""Event-level evaluation with honest definitions.

For every ground-truth loss onset T:
    the *first* alarm in [T - max_lead, T) decides the outcome
    lead = T - t_alarm;  timely if lead >= min_lead (10 s),  late if 0 < lead < min_lead,
    missed if there is no alarm in the window.
Every other alarm (not followed by a loss within max_lead s, and not during an outage) is a
*false alarm* = one redundant prefetch. False-alarm exposure time excludes outages and the
pre-loss windows. Profiles with no loss at all are *negatives*: correct when silent.

    detection rate (recall@10s) = timely / evaluable events
    specificity                 = silent negative profiles / negative profiles
    accuracy                    = (timely + silent negatives) / (events + negative profiles)
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field

import numpy as np
from scipy import stats

from .config import EvalConfig


@dataclass
class EventOutcome:
    trace: str
    kind: str
    onset: float
    lead: float            # NaN if missed
    status: str            # timely | late | missed
    t_entry: float = float("nan")
    oracle_lead: float = float("nan")   # time from decay onset (entrance) to loss


@dataclass
class TraceOutcome:
    trace: str
    kind: str
    alarms: list
    false_alarms: list
    exposure_s: float
    events: list = field(default_factory=list)
    negative: bool = False


def _valid_ticks(tr):
    v = np.isfinite(tr.rsrp)
    if tr.lte is not None:
        v &= tr.lte
    return v


def evaluable_onsets(tr, cfg: EvalConfig = EvalConfig(), history_s=30.0, min_history=0.75):
    """Onsets with at least ``history_s`` of mostly-valid readings before them (the detector
    is warm) and no overlap with an earlier outage."""
    valid = _valid_ticks(tr)
    out = []
    prev_end = -math.inf
    for on, end, _ in tr.episodes:
        lo = on - history_s
        m = (tr.t >= lo) & (tr.t < on)
        if m.any() and valid[m].mean() >= min_history and lo >= prev_end and lo >= tr.t[0]:
            out.append(on)
        prev_end = end
    return out


def match(tr, alarm_times, cfg: EvalConfig = EvalConfig()) -> TraceOutcome:
    alarms = np.asarray(sorted(alarm_times), dtype=float)
    onsets = [e[0] for e in tr.episodes]
    eval_on = set(evaluable_onsets(tr, cfg))
    in_window = np.zeros(alarms.size, dtype=bool)
    in_outage = np.zeros(alarms.size, dtype=bool)
    for on, end, _ in tr.episodes:
        in_window |= (alarms >= on - cfg.max_lead_s) & (alarms < on)
        in_outage |= (alarms >= on) & (alarms < end)
    fa = alarms[~in_window & ~in_outage]
    # exposure: valid ticks outside outages and pre-loss windows
    valid = _valid_ticks(tr)
    excl = np.zeros(tr.t.size, dtype=bool)
    for on, end, _ in tr.episodes:
        excl |= (tr.t >= on - cfg.max_lead_s) & (tr.t < end)
    dt = float(np.median(np.diff(tr.t))) if tr.t.size > 1 else 1.0
    exposure = float(np.count_nonzero(valid & ~excl)) * dt
    t_entry = float(tr.meta.get("t_entry", float("nan")))
    events = []
    for on in onsets:
        if on not in eval_on:
            continue
        w = alarms[(alarms >= on - cfg.max_lead_s) & (alarms < on)]
        if w.size:
            lead = float(on - w[0])
            status = "timely" if lead >= cfg.min_lead_s else "late"
        else:
            lead, status = float("nan"), "missed"
        events.append(EventOutcome(tr.name, tr.kind, float(on), lead, status, t_entry,
                                   float(on - t_entry) if math.isfinite(t_entry) else float("nan")))
    return TraceOutcome(tr.name, tr.kind, alarms.tolist(), fa.tolist(), exposure, events,
                        negative=len(tr.episodes) == 0)


# --------------------------------------------------------------------------------------
# Statistics
# --------------------------------------------------------------------------------------
def wilson(k: int, n: int, conf=0.95):
    if n == 0:
        return (float("nan"), float("nan"))
    z = stats.norm.ppf(0.5 + conf / 2)
    p = k / n
    den = 1 + z * z / n
    c = (p + z * z / (2 * n)) / den
    h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / den
    return (max(0.0, c - h), min(1.0, c + h))


def poisson_rate_ci(k: int, exposure: float, conf=0.95):
    """Exact (Garwood) interval for a Poisson rate k / exposure."""
    if exposure <= 0:
        return (float("nan"), float("nan"))
    a = 1 - conf
    lo = 0.0 if k == 0 else stats.chi2.ppf(a / 2, 2 * k) / 2
    hi = stats.chi2.ppf(1 - a / 2, 2 * k + 2) / 2
    return (lo / exposure, hi / exposure)


def bootstrap_median_ci(x, conf=0.95, n_boot=4000, seed=0):
    x = np.asarray([v for v in x if np.isfinite(v)], dtype=float)
    if x.size == 0:
        return (float("nan"), float("nan"))
    rng = np.random.default_rng(seed)
    meds = np.median(rng.choice(x, size=(n_boot, x.size), replace=True), axis=1)
    return (float(np.percentile(meds, 100 * (1 - conf) / 2)), float(np.percentile(meds, 100 * (1 + conf) / 2)))


def summarize(outcomes, cfg: EvalConfig = EvalConfig(), with_ci=True) -> dict:
    events = [e for o in outcomes for e in o.events]
    n_ev = len(events)
    leads = np.array([e.lead for e in events], dtype=float)
    timely = int(np.sum(leads >= cfg.min_lead_s))
    timely15 = int(np.sum(leads >= cfg.stretch_lead_s))
    late = int(np.sum((leads > 0) & (leads < cfg.min_lead_s)))
    missed = int(np.sum(~np.isfinite(leads)))
    exposure = sum(o.exposure_s for o in outcomes)
    n_fa = sum(len(o.false_alarms) for o in outcomes)
    n_alarm = sum(len(o.alarms) for o in outcomes)
    negs = [o for o in outcomes if o.negative]
    tn = sum(1 for o in negs if not o.alarms)
    hours = exposure / 3600.0
    res = {
        "events": n_ev, "timely": timely, "timely15": timely15, "late": late, "missed": missed,
        "recall10": timely / n_ev if n_ev else float("nan"),
        "recall15": timely15 / n_ev if n_ev else float("nan"),
        "any_warning": (n_ev - missed) / n_ev if n_ev else float("nan"),
        "median_lead": float(np.nanmedian(leads)) if np.isfinite(leads).any() else float("nan"),
        "false_alarms": n_fa, "exposure_h": hours, "fa_per_h": n_fa / hours if hours > 0 else float("nan"),
        "alarms": n_alarm, "precision": (n_alarm - n_fa) / n_alarm if n_alarm else float("nan"),
        "negatives": len(negs), "true_negatives": tn,
        "specificity": tn / len(negs) if negs else float("nan"),
        "accuracy": (timely + tn) / (n_ev + len(negs)) if (n_ev + len(negs)) else float("nan"),
    }
    if with_ci:
        res["recall10_ci"] = wilson(timely, n_ev)
        res["recall15_ci"] = wilson(timely15, n_ev)
        res["specificity_ci"] = wilson(tn, len(negs))
        res["accuracy_ci"] = wilson(timely + tn, n_ev + len(negs))
        res["fa_per_h_ci"] = poisson_rate_ci(n_fa, hours)
        res["median_lead_ci"] = bootstrap_median_ci(leads)
    return res


def run_detector(factory, traces):
    """Run a fresh streaming detector over each trace; returns {trace name: [alarm times]}."""
    out = {}
    for tr in traces:
        det = factory()
        alarms = []
        rsrp, rsrq, nbr, cell = tr.rsrp, tr.rsrq, tr.nbr, tr.cell
        for i in range(tr.t.size):
            c = int(cell[i])
            if det.step(float(tr.t[i]), _f(rsrp[i]), _f(rsrq[i]), _f(nbr[i]), c if c >= 0 else None):
                alarms.append(float(tr.t[i]))
        out[tr.name] = alarms
    return out


def _f(x):
    x = float(x)
    return x if math.isfinite(x) else None


def evaluate(factory, traces, cfg: EvalConfig = EvalConfig()):
    alarms = run_detector(factory, traces)
    outcomes = [match(tr, alarms[tr.name], cfg) for tr in traces]
    return summarize(outcomes, cfg), outcomes
