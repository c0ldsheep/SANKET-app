"""How much warning does a prefetch actually need?

A trigger at time t0 starts downloading the delivery manifest in priority tiers over a link
whose quality keeps falling until the loss onset T. The transfer model is deliberately
simple and conservative:
  * radio wakes from idle (RRC promotion), then TCP + TLS 1.3 handshakes (2 RTT);
  * each tier is one HTTP request: 1 RTT + server think time + transfer;
  * transfer follows TCP slow start (initial window 10 MSS, doubling every RTT) capped by
    the radio rate  share * bandwidth * SE(SINR)  (3GPP TR 36.942 attenuated Shannon);
  * RTT grows when SINR < 0 dB (HARQ retransmissions, scheduling);
  * nothing more arrives after T.
"""
from __future__ import annotations

import math

import numpy as np

from .config import DEFAULT, PrefetchConfig, RadioConfig
from .radio import spectral_efficiency


class Link:
    """Time-varying link capacity from a 10 Hz SINR trace."""

    def __init__(self, t, sinr_db, share, pcfg: PrefetchConfig = DEFAULT.prefetch,
                 rcfg: RadioConfig = DEFAULT.radio):
        self.t = np.asarray(t, dtype=float)
        self.sinr = np.asarray(sinr_db, dtype=float)
        self.rate_bps = share * pcfg.bandwidth_hz * spectral_efficiency(self.sinr, rcfg)
        self.p = pcfg

    def _at(self, arr, t):
        i = int(np.searchsorted(self.t, t, side="right")) - 1
        return arr[min(max(i, 0), arr.size - 1)]

    def rate(self, t):
        return float(self._at(self.rate_bps, t))

    def rtt(self, t):
        return self.p.rtt_s if self._at(self.sinr, t) >= 0.0 else self.p.rtt_bad_s


def run_prefetch(link: Link, t0: float, t_loss: float, tiers=None, warm=False, cached=None):
    """Simulate the tiered download. Returns completion time of each tier (inf = not done).

    ``cached`` is a set of tier indices whose content is already on the phone and unchanged
    (an ETag revalidation then only costs one round trip and ~400 bytes).
    """
    p = link.p
    tiers = tiers or p.tiers
    cached = cached or set()
    done = [math.inf] * len(tiers)
    t = t0
    if not warm:
        t += p.rrc_promotion_s
        t += 2.0 * link.rtt(t)                     # TCP + TLS 1.3
    if t >= t_loss:
        return done
    cwnd = float(p.init_cwnd_segments * p.mss_bytes)
    for k, (_, size) in enumerate(tiers):
        t += link.rtt(t) / 2.0 + p.server_s           # request reaches the server, server thinks
        remaining = float(p.not_modified_bytes if k in cached else size)
        while remaining > 0.0 and t < t_loss:
            rtt = link.rtt(t)
            rate = link.rate(t)
            if rate <= 0.0:                            # SINR below the MCS floor: nothing moves
                t += rtt
                continue
            cap = rate / 8.0 * rtt                     # bytes the radio can carry in one RTT
            window = min(cwnd, cap)
            if remaining <= window:                    # last bytes: serialisation + one-way delay
                t += rtt / 2.0 + remaining * 8.0 / rate
                remaining = 0.0
            else:
                remaining -= window
                t += rtt
                if cwnd <= cap:                        # slow start until the radio is the limit
                    cwnd *= 2.0
        if remaining > 0.0 or t > t_loss:
            break
        done[k] = t
    return done


def completion_vs_lead(traces, leads, rng, pcfg: PrefetchConfig = DEFAULT.prefetch,
                       rcfg: RadioConfig = DEFAULT.radio):
    """P(tier k finished before the loss | trigger `lead` seconds before it), per tier.

    Uses simulated profiles with ground truth (their SINR trace). One random resource
    share per event, the same for every lead, so curves are paired comparisons.
    """
    rows = []
    for tr in traces:
        if not tr.episodes or tr.truth is None:
            continue
        T = tr.episodes[0][0]
        share = float(rng.uniform(*pcfg.share))
        link = Link(tr.truth["t"], tr.truth["sinr"], share, pcfg, rcfg)
        for lead in leads:
            t0 = T - lead
            if t0 < tr.truth["t"][0]:
                continue
            done = run_prefetch(link, t0, T)
            rows.append([lead] + [np.isfinite(d) for d in done])
    arr = np.array(rows, dtype=float)
    out = {}
    for j, (name, _) in enumerate(pcfg.tiers):
        out[name] = [float(arr[arr[:, 0] == L, j + 1].mean()) if np.any(arr[:, 0] == L) else float("nan")
                     for L in leads]
    return out


def required_lead(curve_leads, curve_probs, target=0.99):
    for L, pr in zip(curve_leads, curve_probs):
        if pr >= target:
            return float(L)
    return float("nan")


def daily_cost_mb(fa_per_h, hours=10.0, pcfg: PrefetchConfig = DEFAULT.prefetch, conditional=True, tiers=None):
    """Mobile data wasted per day by false alarms. With ETag revalidation an unchanged
    manifest costs one 304 response per tier instead of the full payload."""
    tiers = tiers or pcfg.tiers
    per = len(tiers) * pcfg.not_modified_bytes if conditional else sum(size for _, size in tiers)
    return fa_per_h * hours * per / 1e6
