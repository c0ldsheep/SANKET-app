"""Learned dead-zone memory: the fleet remembers where phones lose signal.

Why: a signal-only predictor cannot warn earlier than the moment the signal starts to fall
(the entrance). Riders, however, keep returning to the same cloud kitchens, malls and
apartment basements. When a phone loses the link and later reconnects, it reports the last
GPS fix before the loss (an entrance location - no route, no identity). After ``k_min``
independent reports a zone is published to every rider's phone; approaching within
``radius_m`` of a published zone triggers the prefetch *before* the signal moves at all.

Privacy by design: only building-entrance coordinates and a count are stored; a zone needs
reports from at least ``k_min`` different trips before anyone sees it.
"""
from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np

from .config import DEFAULT, Config
from .evaluate import evaluable_onsets
from .simulate import _draw_geometry, simulate_profile

SITE_KINDS = {"basement_ride": 0.4, "basement_walk": 0.2, "deep_indoor": 0.2, "lobby_entry": 0.2}


@dataclass
class Site:
    idx: int
    kind: str
    geometry: dict
    s0: float
    reports: list = field(default_factory=list)


def make_city(n_sites, rng, cfg: Config = DEFAULT):
    kinds = list(SITE_KINDS)
    probs = np.array([SITE_KINDS[k] for k in kinds])
    sites = []
    for i in range(n_sites):
        kind = str(rng.choice(kinds, p=probs / probs.sum()))
        g = _draw_geometry(kind, rng)
        s0 = float(np.clip(rng.normal(cfg.env.outdoor_rsrp_mean, cfg.env.outdoor_rsrp_sd), *cfg.env.outdoor_rsrp_clip))
        sites.append(Site(i, kind, g, s0))
    return sites


def zipf_weights(n, alpha):
    w = 1.0 / np.arange(1, n + 1) ** alpha
    return w / w.sum()


def run_fleet(detector_factory, days=7, visits_per_day=400, n_sites=300, alpha=1.0, radius_m=50.0,
              gps_sd_m=8.0, report_sd_m=10.0, k_min=2, seed=7, cfg: Config = DEFAULT, min_lead=10.0):
    """Simulate a fleet learning dead zones day by day.

    Returns a per-visit table with the signal-only, memory-only and combined lead times.
    """
    rng = np.random.default_rng(seed)
    sites = make_city(n_sites, rng, cfg)
    order = rng.permutation(n_sites)                  # popularity is independent of site kind
    w = zipf_weights(n_sites, alpha)
    pop = np.empty(n_sites)
    pop[order] = w
    rows = []
    for day in range(days):
        for v in range(visits_per_day):
            site = sites[int(rng.choice(n_sites, p=pop))]
            vr = np.random.default_rng([seed, day, v])
            g = dict(site.geometry)
            g["v_out"] = float(vr.uniform(*_v_out_range(site.kind)))      # each trip differs
            tr = simulate_profile(site.kind, vr, cfg, name=f"d{day}v{v}", geometry=g,
                                  s0_override=site.s0 + float(vr.normal(0, 2.0)), keep_truth=True)
            det = detector_factory()
            sig = []
            for i in range(tr.t.size):
                r, q, nb = tr.rsrp[i], tr.rsrq[i], tr.nbr[i]
                if det.step(float(tr.t[i]), _f(r), _f(q), _f(nb), None):
                    sig.append(float(tr.t[i]))
            # memory trigger on the approach (GPS works outdoors)
            published = len(site.reports) >= k_min
            t_mem = np.nan
            if published:
                centre = float(np.mean(site.reports))
                s_tick = np.interp(tr.t, tr.truth["t"], tr.truth["s"])
                s_hat = s_tick + vr.normal(0.0, gps_sd_m, s_tick.size)
                hit = np.flatnonzero((s_tick < 0.0) & (np.abs(s_hat - centre) < radius_m))
                if hit.size:
                    t_mem = float(tr.t[hit[0]])
            onsets = evaluable_onsets(tr, cfg.eval)
            T = onsets[0] if onsets else np.nan
            lead_sig = _lead(sig, T, cfg.eval.max_lead_s)
            lead_mem = (T - t_mem) if (np.isfinite(T) and np.isfinite(t_mem) and T - t_mem > 0) else np.nan
            if np.isfinite(lead_mem) and lead_mem > cfg.eval.max_lead_s:
                lead_mem = cfg.eval.max_lead_s     # a cached manifest is still fresh; cap for reporting
            lead_hyb = np.nanmax([lead_sig, lead_mem]) if (np.isfinite(lead_sig) or np.isfinite(lead_mem)) else np.nan
            rows.append(dict(day=day, site=site.idx, kind=site.kind, loss=bool(np.isfinite(T)), published=published,
                             lead_signal=lead_sig, lead_memory=lead_mem, lead_hybrid=lead_hyb,
                             memory_false=bool(published and np.isfinite(t_mem) and not tr.episodes)))
            if tr.episodes:                       # phone reconnects later and reports the entrance
                site.reports.append(float(vr.normal(0.0, report_sd_m)))
    return rows


def _v_out_range(kind):
    return (4.0, 8.0) if kind == "basement_ride" else (1.0, 1.5)


def _f(x):
    x = float(x)
    return x if np.isfinite(x) else None


def _lead(alarms, T, max_lead):
    if not np.isfinite(T):
        return np.nan
    w = [a for a in alarms if T - max_lead <= a < T]
    return float(T - w[0]) if w else np.nan


def daily_summary(rows, min_lead=10.0):
    import pandas as pd
    df = pd.DataFrame(rows)
    ev = df[df.loss]
    out = []
    for day, g in ev.groupby("day"):
        out.append(dict(day=int(day) + 1, events=len(g),
                        signal=float(np.mean(g.lead_signal >= min_lead)),
                        memory=float(np.mean(g.lead_memory >= min_lead)),
                        hybrid=float(np.mean(g.lead_hybrid >= min_lead)),
                        known_zone=float(np.mean(g.published))))
    return pd.DataFrame(out)
