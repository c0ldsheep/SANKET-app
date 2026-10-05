"""Physics-based simulator: what a delivery rider's phone measures around building entries.

Pipeline for one profile (all at 10 Hz internally)
  motion            rider position s(t) along the path; the entrance is at s = 0
  building loss     L(s) from the scenario geometry (ramps, basement levels, walls)
  cells             serving cell A and best neighbour B, each with its own shadowing field
                    (spatially correlated, 3GPP-style), plus background interference
  handover          A3 event: neighbour > serving + 3 dB for 320 ms -> swap
  truth             SINR and RSRQ from per-RE powers; loss episodes from radio.loss_episodes
  measurement       modem measures every 200 ms with estimation noise, L3 EWMA filter,
                    reports every T_rep seconds, integer dBm, Android valid ranges
  app log           the app polls once per second and sees the latest report (repeats!)

The detector only ever sees the app log. Ground truth comes from hidden physical state.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field

import numpy as np
from scipy.signal import lfilter

from . import radio
from .config import DEFAULT, POSITIVE_KINDS, SCENARIO_MIX, SCENARIOS, Config


@dataclass
class Trace:
    name: str
    kind: str
    t: np.ndarray            # tick times, s
    rsrp: np.ndarray         # observed serving RSRP, dBm (NaN = unavailable)
    rsrq: np.ndarray         # observed serving RSRQ, dB
    nbr: np.ndarray          # observed best-neighbour RSRP, dBm (NaN = none)
    cell: np.ndarray         # serving cell id per tick (-1 unknown)
    episodes: list           # ground-truth loss episodes [(onset, end, cause)]
    meta: dict = field(default_factory=dict)
    truth: dict | None = None
    lte: np.ndarray | None = None   # bool per tick: phone on LTE (real data); None = always

    @property
    def onsets(self):
        return [e[0] for e in self.episodes]

    @property
    def duration_s(self) -> float:
        return float(self.t[-1] - self.t[0]) if self.t.size else 0.0


def smoothstep(x):
    x = np.clip(x, 0.0, 1.0)
    return x * x * (3.0 - 2.0 * x)


def _u(rng, lohi):
    lo, hi = lohi
    return float(lo) if lo == hi else float(rng.uniform(lo, hi))


# --------------------------------------------------------------------------------------
# Motion and geometry
# --------------------------------------------------------------------------------------
def motion(segments, s_start, dwell_s, dt, tau=1.5, t_max=1200.0):
    """Rider position over time.

    ``segments`` is a list of (s_end, target_speed): while s < s_end the rider tends to
    that speed through a first-order lag (braking / accelerating take ~2*tau seconds).
    After the last s_end the rider stops for ``dwell_s`` seconds.
    """
    s_final = segments[-1][0]
    v = segments[0][1]
    s = s_start
    t_list, s_list, v_list = [0.0], [s], [v]
    i = 0
    k = 0
    a = min(1.0, dt / tau)
    while s < s_final and k * dt < t_max:
        while i < len(segments) - 1 and s >= segments[i][0]:
            i += 1
        v += (segments[i][1] - v) * a
        s = min(s + max(v, 0.05) * dt, s_final)
        k += 1
        t_list.append(k * dt)
        s_list.append(s)
        v_list.append(v)
    n_dwell = int(round(dwell_s / dt))
    for _ in range(n_dwell):
        k += 1
        t_list.append(k * dt)
        s_list.append(s_final)
        v_list.append(0.0)
    return np.array(t_list), np.array(s_list), np.array(v_list)


def _draw_geometry(kind, rng):
    p = SCENARIOS[kind]
    g = {"kind": kind}
    g["v_out"] = _u(rng, p["v_out"])
    if kind in ("basement_ride", "basement_walk"):
        g.update(t_out=_u(rng, p["t_out"]), v_ramp=_u(rng, p["v_ramp"]), ramp_len=_u(rng, p["ramp_len"]),
                 loss_b1=_u(rng, p["loss_b1"]), v_in=_u(rng, p["v_in"]), d_in=_u(rng, p["d_in"]),
                 g_in=_u(rng, p["g_in"]), cap=_u(rng, p["cap"]), dwell=_u(rng, p["dwell"]),
                 b2=bool(rng.uniform() < p["p_b2"]))
        g.update(ramp2_len=_u(rng, p["ramp2_len"]), loss_b2=_u(rng, p["loss_b2"]), d_in2=_u(rng, p["d_in2"]))
        g["cap"] = max(g["cap"], g["loss_b1"])
    elif kind in ("deep_indoor", "lobby_entry"):
        g.update(t_out=_u(rng, p["t_out"]), wall=_u(rng, p["wall"]), g_in=_u(rng, p["g_in"]),
                 d_in=_u(rng, p["d_in"]), cap=_u(rng, p["cap"]), dwell=_u(rng, p["dwell"]))
        g["cap"] = max(g["cap"], g["wall"])
    elif kind == "underpass":
        g.update(t_out=_u(rng, p["t_out"]), length=_u(rng, p["length"]), depth=_u(rng, p["depth"]),
                 edge=_u(rng, p["edge"]), t_after=_u(rng, p["t_after"]))
    elif kind == "street_ride":
        g.update(t_total=_u(rng, p["t_total"]))
    elif kind == "cell_edge_handover":
        g.update(t_total=_u(rng, p["t_total"]), k=_u(rng, p["k_db_per_m"]))
    else:
        raise ValueError(f"unknown scenario {kind!r}")
    return g


def _segments(g):
    """Path segments (s_end, speed), start position and dwell for a geometry."""
    kind = g["kind"]
    v_out = g["v_out"]
    if kind in ("basement_ride", "basement_walk"):
        s_start = -v_out * g["t_out"]
        brake = 12.0 if kind == "basement_ride" else 2.0
        segs = [(-brake, v_out), (g["ramp_len"], g["v_ramp"]), (g["ramp_len"] + g["d_in"], g["v_in"])]
        if g["b2"]:
            r2 = g["ramp_len"] + g["d_in"]
            segs += [(r2 + g["ramp2_len"], g["v_ramp"]), (r2 + g["ramp2_len"] + g["d_in2"], g["v_in"])]
        return segs, s_start, g["dwell"]
    if kind in ("deep_indoor", "lobby_entry"):
        s_start = -v_out * g["t_out"]
        return [(0.0, v_out), (g["d_in"], v_out * 0.9)], s_start, g["dwell"]
    if kind == "underpass":
        s_start = -v_out * g["t_out"]
        return [(g["length"] + v_out * g["t_after"], v_out)], s_start, 0.0
    # street_ride, cell_edge_handover
    return [(v_out * g["t_total"], v_out)], 0.0, 0.0


def building_loss(g, s):
    """Extra path loss (dB) caused by the structure at path position s (m)."""
    kind = g["kind"]
    s = np.asarray(s, dtype=float)
    if kind in ("basement_ride", "basement_walk"):
        L = g["loss_b1"] * smoothstep(s / g["ramp_len"]) + g["g_in"] * np.clip(s - g["ramp_len"], 0.0, None)
        L = np.minimum(L, g["cap"])
        if g["b2"]:
            r2 = g["ramp_len"] + g["d_in"]
            L = L + g["loss_b2"] * smoothstep((s - r2) / g["ramp2_len"])
        return L
    if kind in ("deep_indoor", "lobby_entry"):
        L = g["wall"] * smoothstep(s / 3.0) + g["g_in"] * np.clip(s - 3.0, 0.0, None)
        return np.minimum(L, g["cap"])
    if kind == "underpass":
        e, n = g["edge"], g["length"]
        return g["depth"] * smoothstep(s / e) * (1.0 - smoothstep((s - (n - e)) / e))
    return np.zeros_like(s)


def _indoor(g, s):
    if g["kind"] in ("basement_ride", "basement_walk", "deep_indoor", "lobby_entry"):
        return s >= 0.0
    return np.zeros_like(s, dtype=bool)


def shadow_field(rng, s_grid, indoor_grid, env):
    """Spatially correlated log-normal shadowing (AR(1) in distance), dB."""
    n = s_grid.size
    ds = float(s_grid[1] - s_grid[0]) if n > 1 else 1.0
    e = rng.standard_normal(n)
    out = np.empty(n)
    split = int(np.argmax(indoor_grid)) if indoor_grid.any() else n   # indoor part is a suffix
    last = None
    if split > 0:
        rho = math.exp(-ds / env.shadow_dcorr_out)
        c = math.sqrt(1.0 - rho * rho)
        # y[0] = e[0] (stationary start), y[k] = rho*y[k-1] + c*e[k]
        y, _ = lfilter([c], [1.0, -rho], e[:split], zi=[(1.0 - c) * e[0]])
        out[:split] = y * env.shadow_sd_out
        last = y[-1]
    if split < n:
        rho = math.exp(-ds / env.shadow_dcorr_in)
        c = math.sqrt(1.0 - rho * rho)
        zi = [rho * last] if last is not None else [(1.0 - c) * e[split]]
        y, _ = lfilter([c], [1.0, -rho], e[split:], zi=zi)
        out[split:] = y * env.shadow_sd_in
    return out


def ar_field(rng, s_grid, sd, dcorr):
    """Stationary AR(1) random field along the path with standard deviation ``sd`` (dB)."""
    n = s_grid.size
    if sd <= 0.0:
        return np.zeros(n)
    ds = float(s_grid[1] - s_grid[0]) if n > 1 else 1.0
    rho = math.exp(-ds / dcorr)
    c = math.sqrt(1.0 - rho * rho)
    e = rng.standard_normal(n)
    y, _ = lfilter([c], [1.0, -rho], e, zi=[(1.0 - c) * e[0]])
    return sd * y


# --------------------------------------------------------------------------------------
# One profile
# --------------------------------------------------------------------------------------
def simulate_profile(kind: str, rng: np.random.Generator, cfg: Config = DEFAULT, name: str = "",
                     geometry: dict | None = None, s0_override: float | None = None,
                     keep_truth: bool = True) -> Trace:
    env, mc, rc = cfg.env, cfg.meas, cfg.radio
    g = dict(geometry) if geometry is not None else _draw_geometry(kind, rng)
    g["kind"] = kind
    segs, s_start, dwell = _segments(g)
    t, s, v = motion(segs, s_start, dwell, mc.dt)

    # cells ---------------------------------------------------------------------------
    s0 = s0_override if s0_override is not None else float(np.clip(
        rng.normal(env.outdoor_rsrp_mean, env.outdoor_rsrp_sd), *env.outdoor_rsrp_clip))
    gap0 = _u(rng, env.gap_mean_db)
    ho_offset = _u(rng, env.ho_offset_db)
    sir_bg = _u(rng, env.bg_sir_db)
    load = _u(rng, env.load)
    nbr_load = _u(rng, env.nbr_load)
    nf = _u(rng, rc.noise_figure_db)
    n0_dbm = radio.noise_per_re_dbm(nf, rc.scs_hz)

    L = building_loss(g, s)
    s_grid = np.arange(s.min() - 1.0, s.max() + 1.5, 0.5)
    ind_grid = _indoor(g, s_grid)

    def field(sd, dcorr):
        return np.interp(s, s_grid, ar_field(rng, s_grid, sd, dcorr))

    sh_serv = np.interp(s, s_grid, shadow_field(rng, s_grid, ind_grid, env))
    sh_bg = np.interp(s, s_grid, shadow_field(rng, s_grid, ind_grid, env))
    site = field(env.site_sd, env.site_dcorr)
    gap = gap0 + field(env.gap_sd, env.gap_dcorr)
    inside = smoothstep(np.where(_indoor(g, s), s, 0.0) / 5.0) if g["kind"] in (
        "basement_ride", "basement_walk", "deep_indoor", "lobby_entry") else np.zeros_like(s)
    diff_sd = env.indoor_cell_diff_sd / math.sqrt(2.0)
    in_a = inside * field(diff_sd, env.indoor_cell_diff_dcorr)
    in_b = inside * field(diff_sd, env.indoor_cell_diff_dcorr)
    edge = np.zeros_like(s)
    if kind == "cell_edge_handover":
        k = g["k"]
        gap = gap - gap0 + min(gap0, -4.0)          # start clearly below the serving cell
        zone = (abs(min(gap0, -4.0)) + ho_offset + 5.0) / (2.0 * k)
        ds = s - s[0]
        d0 = float(rng.uniform(0.25, 0.45)) * (s[-1] - s[0])
        w = np.clip(ds - d0, 0.0, zone)
        edge = -k * w                               # serving fades, neighbour rises
        gap = gap + 2.0 * k * w
        g["zone_m"], g["zone_start_m"] = zone, d0
    common = s0 + site + sh_serv + edge - L
    p_a = common + in_a
    p_b = common + gap + in_b
    p_bg = s0 - sir_bg + env.bg_shadow_scale * sh_bg - L

    # handover (A3 with time-to-trigger) on true powers --------------------------------
    serving = np.zeros(t.size, dtype=np.int8)
    cur, run = 0, 0.0
    for i in range(t.size):
        other = p_b[i] if cur == 0 else p_a[i]
        mine = p_a[i] if cur == 0 else p_b[i]
        run = run + mc.dt if other > mine + ho_offset else 0.0
        if run >= env.a3_ttt_s - 1e-9:
            cur, run = 1 - cur, 0.0
        serving[i] = cur
    s_dbm = np.where(serving == 0, p_a, p_b)
    n_dbm = np.where(serving == 0, p_b, p_a)
    s_mw, n_mw, bg_mw = radio.db2lin(s_dbm), radio.db2lin(n_dbm), radio.db2lin(p_bg)
    noise_mw = radio.db2lin(n0_dbm)
    i_mw = nbr_load * n_mw + bg_mw
    sinr = radio.sinr_db(s_mw, i_mw, noise_mw)
    episodes = radio.loss_episodes(t, s_dbm, sinr, rc)

    # RSRQ of each cell (each sees the other as interference)
    rsrq_a = radio.rsrq_db(radio.db2lin(p_a), nbr_load * radio.db2lin(p_b) + bg_mw, noise_mw, load)
    rsrq_b = radio.rsrq_db(radio.db2lin(p_b), load * radio.db2lin(p_a) + bg_mw, noise_mw, nbr_load)

    # modem measurement chain -----------------------------------------------------------
    step = max(1, int(round(mc.meas_period_s / mc.dt)))
    mi = np.arange(0, t.size, step)
    a = mc.l3_alpha

    def l3(x):
        y, _ = lfilter([a], [1.0, -(1.0 - a)], x, zi=[(1.0 - a) * x[0]])
        return y

    sd_r = np.where(v[mi] > 0.3, mc.rsrp_noise_db_moving, mc.rsrp_noise_db)
    f_a = l3(p_a[mi] + sd_r * rng.standard_normal(mi.size))
    f_b = l3(p_b[mi] + sd_r * rng.standard_normal(mi.size))
    fq_a = l3(rsrq_a[mi] + rng.normal(0, mc.rsrq_noise_db, mi.size))
    fq_b = l3(rsrq_b[mi] + rng.normal(0, mc.rsrq_noise_db, mi.size))
    srv_m = serving[mi]
    meas_t = t[mi]
    rep_rsrp = np.where(srv_m == 0, f_a, f_b)
    rep_nbr = np.where(srv_m == 0, f_b, f_a)
    rep_rsrq = np.where(srv_m == 0, fq_a, fq_b)

    t_rep = float(rng.choice(mc.report_periods_s, p=mc.report_probs))
    phase = float(rng.uniform(0.0, t_rep))
    rep_times = np.arange(phase, t[-1] + 1e-9, t_rep)
    idx = np.searchsorted(meas_t, rep_times, side="right") - 1
    ok = idx >= 0
    rep_times, idx = rep_times[ok], idx[ok]

    def android_int(x, lo, hi):
        r = np.round(x)
        return np.where((r >= lo) & (r <= hi), r, np.nan)

    r_rsrp = android_int(rep_rsrp[idx], -140, -43)
    r_rsrq = android_int(rep_rsrq[idx], -34, 3)
    r_nbr = android_int(rep_nbr[idx], -140, -43)
    # neighbour reports come and go (two-state Markov chain at the report instants)
    p_on = _u(rng, env.nbr_avail)
    if p_on < 1.0 and r_nbr.size:
        a_off = min(1.0, t_rep / env.nbr_on_mean_s)              # on -> off per report
        a_on = min(1.0, a_off * p_on / (1.0 - p_on))             # off -> on keeps the duty cycle
        on = np.empty(r_nbr.size, dtype=bool)
        on[0] = rng.uniform() < p_on
        u = rng.uniform(size=r_nbr.size)
        for i in range(1, r_nbr.size):
            on[i] = (u[i] >= a_off) if on[i - 1] else (u[i] < a_on)
        r_nbr = np.where(on, r_nbr, np.nan)
    r_cell = srv_m[idx].astype(int)
    lost = np.zeros(rep_times.size, dtype=bool)
    for on, end, _ in episodes:
        lost |= (rep_times >= on) & (rep_times < end)
    r_rsrp[lost] = np.nan
    r_rsrq[lost] = np.nan
    r_nbr[lost] = np.nan

    ticks = np.arange(0.0, t[-1] + 1e-9, mc.log_period_s)
    j = np.searchsorted(rep_times, ticks, side="right") - 1
    have = j >= 0
    jj = np.where(have, j, 0)
    obs_rsrp = np.where(have, r_rsrp[jj], np.nan)
    obs_rsrq = np.where(have, r_rsrq[jj], np.nan)
    obs_nbr = np.where(have, r_nbr[jj], np.nan)
    obs_cell = np.where(have, r_cell[jj], -1)
    # a held value cannot outlive a loss onset: the phone shows "no service" immediately
    for on, end, _ in episodes:
        m = (ticks >= on) & (ticks < end)
        obs_rsrp[m] = obs_rsrq[m] = obs_nbr[m] = np.nan

    t_entry = float(np.interp(0.0, s, t)) if (s.min() < 0.0 <= s.max()) else float("nan")
    meta = dict(geometry=g, s0=s0, gap0=gap0, ho_offset=ho_offset, nbr_avail=p_on, sir_bg=sir_bg,
                load=load, nbr_load=nbr_load, nf=nf,
                n0_dbm=n0_dbm, t_rep=t_rep, t_entry=t_entry,
                positive_kind=kind in POSITIVE_KINDS, n_handovers=int(np.count_nonzero(np.diff(serving))))
    truth = None
    if keep_truth:
        truth = dict(t=t, s=s, v=v, L=L, s_dbm=s_dbm, n_dbm=n_dbm, sinr=sinr, serving=serving,
                     rsrq=np.where(serving == 0, rsrq_a, rsrq_b))
    return Trace(name=name or kind, kind=kind, t=ticks, rsrp=obs_rsrp, rsrq=obs_rsrq, nbr=obs_nbr,
                 cell=obs_cell, episodes=episodes, meta=meta, truth=truth)


def simulate_dataset(n: int, seed: int, cfg: Config = DEFAULT, mix: dict | None = None,
                     keep_truth: bool = True, prefix: str = "sim") -> list:
    """``n`` profiles with scenario kinds drawn from ``mix`` (fractions are exact up to rounding)."""
    mix = mix or SCENARIO_MIX
    kinds = list(mix)
    counts = np.floor(np.array([mix[k] for k in kinds]) * n).astype(int)
    rem = n - counts.sum()
    order = np.argsort([-(mix[k] * n - c) for k, c in zip(kinds, counts)])
    for i in order[:rem]:
        counts[i] += 1
    rng = np.random.default_rng(seed)
    plan = [k for k, c in zip(kinds, counts) for _ in range(c)]
    rng.shuffle(plan)
    out = []
    for i, kind in enumerate(plan):
        sub = np.random.default_rng([seed, i])
        out.append(simulate_profile(kind, sub, cfg, name=f"{prefix}{seed}-{i:05d}-{kind}", keep_truth=keep_truth))
    return out
