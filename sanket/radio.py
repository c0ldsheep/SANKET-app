"""Radio-layer formulas (3GPP based) and the ground-truth definition of link loss.

All powers are per resource element (RE, 15 kHz), the unit in which RSRP is defined.
"""
from __future__ import annotations

import math

import numpy as np

from .config import RadioConfig


def db2lin(x):
    return np.power(10.0, np.asarray(x, dtype=float) / 10.0)


def lin2db(x):
    return 10.0 * np.log10(np.asarray(x, dtype=float))


def noise_per_re_dbm(noise_figure_db: float, scs_hz: float = 15e3) -> float:
    """Thermal noise in one resource element: -174 dBm/Hz + 10 log10(15 kHz) + NF."""
    return -174.0 + 10.0 * math.log10(scs_hz) + noise_figure_db


def sinr_db(s_mw, i_mw, n_mw):
    """Reference-signal SINR from serving power, total interference and noise (all mW per RE)."""
    return lin2db(s_mw / (i_mw + n_mw))


def rsrq_db(s_mw, i_mw, n_mw, load):
    """RSRQ = N*RSRP / RSSI (TS 36.214) with RSSI measured over 12 subcarriers per RB.

    Per subcarrier the UE sees load*S from its own cell plus interference and noise, so
    RSRQ = S / (12 (load*S + I + N)). An empty cell (load ~ 1/6, reference signals only)
    gives the -3 dB best case; a fully loaded clean cell gives -10.8 dB; as the signal
    sinks into the noise floor RSRQ collapses towards -20 dB and below.
    """
    return lin2db(s_mw / (12.0 * (load * s_mw + i_mw + n_mw)))


def spectral_efficiency(sinr, cfg: RadioConfig = RadioConfig()):
    """3GPP TR 36.942 A.1: Thr = alpha*log2(1+SINR), 0 below SINR_min, capped at Thr_max (bps/Hz)."""
    sinr = np.asarray(sinr, dtype=float)
    se = cfg.alpha * np.log2(1.0 + np.power(10.0, sinr / 10.0))
    se = np.minimum(se, cfg.thr_max_bps_hz)
    return np.where(sinr < cfg.sinr_min_db, 0.0, se)


def loss_episodes(t, s_dbm, sinr, cfg: RadioConfig = RadioConfig()):
    """Ground-truth link-loss episodes from the *true* (noise-free) serving power and SINR.

    A phone loses service by either of the two 3GPP mechanisms:
      * connected mode - radio link failure: SINR below Qout continuously for T310;
      * idle mode - no suitable cell: RSRP below Qrxlevmin for ``oos_s`` seconds.
    The onset is the start of the bad interval (that is when data stops flowing).
    Recovery needs RSRP >= Qrxlevmin + margin and SINR >= Qin for ``recover_s`` seconds.

    Returns a list of (onset_s, end_s, cause); end_s is inf if the trace ends while lost.
    """
    t = np.asarray(t, dtype=float)
    s_dbm = np.asarray(s_dbm, dtype=float)
    sinr = np.asarray(sinr, dtype=float)
    eps = 1e-9
    episodes = []
    lost = False
    rlf_start = oos_start = good_start = None
    onset = cause = None
    for k in range(t.size):
        tk = t[k]
        if not lost:
            if sinr[k] < cfg.qout_db:
                rlf_start = tk if rlf_start is None else rlf_start
            else:
                rlf_start = None
            if s_dbm[k] < cfg.qrxlevmin_dbm:
                oos_start = tk if oos_start is None else oos_start
            else:
                oos_start = None
            hits = []
            if rlf_start is not None and tk - rlf_start >= cfg.t310_s - eps:
                hits.append((rlf_start, "rlf"))
            if oos_start is not None and tk - oos_start >= cfg.oos_s - eps:
                hits.append((oos_start, "no_cell"))
            if hits:
                onset, cause = min(hits)
                lost = True
                good_start = None
        else:
            good = s_dbm[k] >= cfg.qrxlevmin_dbm + cfg.recover_margin_db and sinr[k] >= cfg.qin_db
            if good:
                good_start = tk if good_start is None else good_start
                if tk - good_start >= cfg.recover_s - eps:
                    episodes.append((float(onset), float(tk), cause))
                    lost = False
                    rlf_start = oos_start = good_start = None
            else:
                good_start = None
    if lost:
        episodes.append((float(onset), math.inf, cause))
    return episodes
