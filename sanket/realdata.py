"""Real measurement data.

* ``load_ucc``      - the public UCC 4G LTE drive-test dataset (Raca et al., MMSys 2018,
                      CC-BY-4.0, doi:10.5281/zenodo.1219679): 135 traces, ~52 h, 1 Hz,
                      RSRP/RSRQ/SNR, neighbour RSRP, cell id, network mode, five mobility types.
* ``load_field_log``- your own recordings: the SANKET Termux logger, G-NetTrack exports or
                      any CSV/TSV with a time column and an RSRP column.
* ``inject_descent``- trace-driven evaluation: overlay a physically modelled basement
                      entry onto a real trace so the detector meets *real* noise, holds,
                      handovers and glitches, with a known ground-truth loss time.
"""
from __future__ import annotations

import csv
import hashlib
import io
import math
import zipfile
from pathlib import Path

import numpy as np
import pandas as pd

from . import radio
from .config import DEFAULT, Config
from .simulate import Trace, _draw_geometry, _segments, building_loss, motion

ROOT = Path(__file__).resolve().parents[1]
UCC_ZIP = ROOT / "data" / "ucc_lte" / "LTE_Dataset.zip"
UCC_SHA256 = "45e95f8a6934da230067803983b45b20e5dfb0179431d43902c8193db770e6bd"
UCC_URL = "https://zenodo.org/records/1219679/files/LTE_Dataset.zip?download=1"


def sha256(path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _clean(x, lo, hi):
    x = pd.to_numeric(x, errors="coerce").astype(float)
    return x.where((x >= lo) & (x <= hi))


def lte_episodes(t, lte, min_off_s=3.0):
    """Loss episodes = LTE -> not-LTE transitions lasting at least ``min_off_s``.

    Returns [(onset, end, cause)] with cause 'lte_lost'. ``lte`` must be on a 1 Hz grid.
    """
    eps = []
    n = len(lte)
    k = 1
    while k < n:
        if lte[k - 1] and not lte[k]:
            m = k
            while m < n and not lte[m]:
                m += 1
            off = (t[m] if m < n else t[-1] + 1.0) - t[k]
            if off >= min_off_s:
                eps.append((float(t[k]), float(t[m]) if m < n else math.inf, "lte_lost"))
            k = m
        else:
            k += 1
    return eps


def resequence_seconds(raw_sec: np.ndarray):
    """The logger's clock sometimes stamps two readings in the same second and skips the next.

    A duplicate moves into the following second only if that second has no reading of its
    own; otherwise it is dropped. Timestamps therefore never drift by more than 1 s (some
    traces log at ~2 Hz for a while; those collapse to the first reading of each second).
    Returns (seconds, keep_mask) in logging order.
    """
    raw = np.asarray(raw_sec, dtype=np.int64)
    present = set(raw.tolist())
    used = set()
    out = np.full(raw.size, -1, dtype=np.int64)
    keep = np.zeros(raw.size, dtype=bool)
    for i, v in enumerate(raw.tolist()):
        if v not in used:
            slot = v
        elif (v + 1) not in present and (v + 1) not in used:
            slot = v + 1
        else:
            continue
        used.add(slot)
        out[i] = slot
        keep[i] = True
    return out, keep


def hold_fill(present, arrays, max_gap=2):
    """Fill gaps of up to ``max_gap`` seconds with the previous reading, as a 1 Hz app would see."""
    n = present.size
    i = 1
    while i < n:
        if not present[i] and present[i - 1]:
            j = i
            while j < n and not present[j]:
                j += 1
            if j - i <= max_gap and j < n:
                for a in arrays:
                    a[i:j] = a[i - 1]
                present[i:j] = True
            i = j
        else:
            i += 1


def ucc_frame_to_trace(df: pd.DataFrame, name: str, mobility: str) -> Trace:
    ts = pd.to_datetime(df["Timestamp"], format="%Y.%m.%d_%H.%M.%S", errors="coerce")
    df = df.assign(_ts=ts).dropna(subset=["_ts"]).reset_index(drop=True)
    raw = ((df["_ts"] - df["_ts"].iloc[0]).dt.total_seconds()).round().astype(int).to_numpy()
    sec, keep = resequence_seconds(raw)
    df, sec = df[keep].reset_index(drop=True), sec[keep]
    n = int(sec.max()) + 1
    t = np.arange(n, dtype=float)
    lte = np.zeros(n, dtype=bool)
    rsrp = np.full(n, np.nan)
    rsrq = np.full(n, np.nan)
    nbr = np.full(n, np.nan)
    cell = np.full(n, -1, dtype=int)
    is_lte = (df["NetworkMode"].astype(str) == "LTE").to_numpy()
    lte[sec] = is_lte
    r = _clean(df["RSRP"], -140, -43).to_numpy()
    q = _clean(df["RSRQ"], -34, 3).to_numpy()
    nb = _clean(df["NRxRSRP"], -140, -43).to_numpy() if "NRxRSRP" in df else np.full(len(df), np.nan)
    ci = pd.to_numeric(df["CellID"], errors="coerce").fillna(-1).astype(int).to_numpy()
    rsrp[sec] = np.where(is_lte, r, np.nan)
    rsrq[sec] = np.where(is_lte, q, np.nan)
    nbr[sec] = np.where(is_lte, nb, np.nan)
    cell[sec] = np.where(is_lte, ci, -1)
    present = np.zeros(n, dtype=bool)
    present[sec] = True
    hold_fill(present, (lte, rsrp, rsrq, nbr, cell))
    episodes = lte_episodes(t, lte)
    meta = dict(mobility=mobility, file=name, operator=str(df["Operatorname"].iloc[0]),
                lte_frac=float(lte.mean()), source="UCC 4G dataset (Raca et al. 2018, CC-BY-4.0)")
    return Trace(name=f"ucc-{mobility}-{name}", kind=f"real_{mobility}", t=t, rsrp=rsrp, rsrq=rsrq, nbr=nbr,
                 cell=cell, episodes=episodes, meta=meta, truth=None, lte=lte)


def load_ucc(zip_path=UCC_ZIP, mobilities=None, verify=True) -> list:
    zip_path = Path(zip_path)
    if not zip_path.exists():
        raise FileNotFoundError(f"{zip_path} missing - download it from {UCC_URL}")
    if verify and sha256(zip_path) != UCC_SHA256:
        raise ValueError(f"{zip_path} does not match the published dataset checksum")
    out = []
    with zipfile.ZipFile(zip_path) as z:
        for info in sorted(z.infolist(), key=lambda i: i.filename):
            parts = info.filename.split("/")
            if len(parts) != 3 or not parts[2].endswith(".csv") or parts[0] != "Dataset":
                continue
            mobility = parts[1]
            if mobilities and mobility not in mobilities:
                continue
            with z.open(info) as f:
                df = pd.read_csv(f, na_values=["-"], low_memory=False)
            if len(df) < 30:
                continue
            out.append(ucc_frame_to_trace(df, parts[2][:-4], mobility))
    return out


def split_files(traces, frac_cal=0.5, salt="sanket-v1"):
    """Deterministic calibration / test split by file name hash, stratified by mobility."""
    cal, test = [], []
    by = {}
    for tr in traces:
        by.setdefault(tr.meta.get("mobility", tr.kind), []).append(tr)
    for _, group in sorted(by.items()):
        group = sorted(group, key=lambda tr: hashlib.sha256((salt + tr.name).encode()).hexdigest())
        k = int(round(frac_cal * len(group)))
        cal += group[:k]
        test += group[k:]
    return cal, test


# --------------------------------------------------------------------------------------
# Field logs (your own recordings)
# --------------------------------------------------------------------------------------
_TIME = ("t_s", "t_unix", "timestamp", "time", "t", "datetime", "date_time", "date")
_RSRP = ("rsrp", "level", "lte_rsrp", "rsrp_dbm", "dbm")
_RSRQ = ("rsrq", "qual", "lte_rsrq", "rsrq_db")
_NET = ("networkmode", "networktech", "network", "tech", "type")
_NBR = ("nrxrsrp", "nbr_rsrp", "neighbour_rsrp", "neighbor_rsrp")
_CELL = ("cellid", "ci", "cell", "cell_id")
_MARK = ("marker", "event", "note")


def _pick(cols, names):
    low = {c.strip().lower(): c for c in cols}
    for n in names:
        if n in low:
            return low[n]
    return None


def _parse_time(col: pd.Series) -> np.ndarray:
    num = pd.to_numeric(col, errors="coerce")
    if num.notna().mean() > 0.95:
        v = num.to_numpy(dtype=float)
        if np.nanmedian(v) > 1e11:      # epoch milliseconds
            v = v / 1000.0
        return v - np.nanmin(v)
    for fmt in ("%Y.%m.%d_%H.%M.%S", "%Y-%m-%d %H:%M:%S", "%Y-%m-%dT%H:%M:%S", None):
        ts = pd.to_datetime(col, format=fmt, errors="coerce") if fmt else pd.to_datetime(col, errors="coerce")
        if ts.notna().mean() > 0.95:
            return (ts - ts.min()).dt.total_seconds().to_numpy()
    raise ValueError("could not understand the time column")


def load_field_log(path, name=None, network_lte_values=("lte", "4g", "lte_ca", "lte+", "nr_nsa")) -> Trace:
    """Import a field recording into a 1 Hz Trace.

    Loss episodes are taken from the log itself: the phone leaving LTE (network column) or
    reporting no RSRP for 3 s or more. Rows from the SANKET logger, G-NetTrack (Level =
    RSRP, Qual = RSRQ for 4G) and simple CSVs are understood automatically.
    """
    path = Path(path)
    raw = path.read_text(encoding="utf-8", errors="replace")
    try:
        dialect = csv.Sniffer().sniff(raw[:4096], delimiters=",\t;")
        sep = dialect.delimiter
    except csv.Error:
        sep = ","
    df = pd.read_csv(io.StringIO(raw), sep=sep, na_values=["-", "", "NaN", "nan", "null"], low_memory=False)
    cols = list(df.columns)
    ct, cr = _pick(cols, _TIME), _pick(cols, _RSRP)
    if ct is None or cr is None:
        raise ValueError(f"{path.name}: need a time column {_TIME} and an RSRP column {_RSRP}; found {cols}")
    cq, cn, cnb, cc, cm = (_pick(cols, x) for x in (_RSRQ, _NET, _NBR, _CELL, _MARK))
    sec = _parse_time(df[ct])
    keep = np.isfinite(sec)
    df, sec = df[keep], np.round(sec[keep]).astype(int)
    order = np.argsort(sec, kind="stable")
    df, sec = df.iloc[order], sec[order]
    first = ~pd.Series(sec).duplicated(keep="last").to_numpy()     # last reading of each second wins
    df, sec = df[first], sec[first]
    n = int(sec.max()) + 1 if sec.size else 0
    if n == 0:
        raise ValueError(f"{path.name}: no rows")
    t = np.arange(n, dtype=float)
    rsrp = np.full(n, np.nan)
    rsrq = np.full(n, np.nan)
    nbr = np.full(n, np.nan)
    cell = np.full(n, -1, dtype=int)
    present = np.zeros(n, dtype=bool)
    present[sec] = True
    r = _clean(df[cr], -140, -43).to_numpy()
    if cn is not None:
        net = df[cn].astype(str).str.strip().str.lower().to_numpy()
        is_lte = np.isin(net, network_lte_values)
        r = np.where(is_lte, r, np.nan)
    rsrp[sec] = r
    if cq is not None:
        rsrq[sec] = np.where(np.isfinite(r), _clean(df[cq], -34, 3).to_numpy(), np.nan)
    if cnb is not None:
        nbr[sec] = np.where(np.isfinite(r), _clean(df[cnb], -140, -43).to_numpy(), np.nan)
    if cc is not None:
        cell[sec] = pd.to_numeric(df[cc], errors="coerce").fillna(-1).astype(int).to_numpy()
    # seconds with no row at all: hold the previous reading (a 1 Hz logger may skip a tick)
    hold_fill(present, (rsrp, rsrq, nbr, cell))
    lte = np.isfinite(rsrp)
    episodes = lte_episodes(t, lte)
    marks = []
    if cm is not None:
        mk = df[cm].fillna("").astype(str).str.strip()
        marks = [(float(s_), m_) for s_, m_ in zip(sec, mk) if m_ and m_.lower() != "nan"]
    meta = dict(file=path.name, source="field log", marks=marks, mobility="field")
    return Trace(name=name or path.stem, kind="field", t=t, rsrp=rsrp, rsrq=rsrq, nbr=nbr, cell=cell,
                 episodes=episodes, meta=meta, truth=None, lte=lte)


# --------------------------------------------------------------------------------------
# Trace-driven injection
# --------------------------------------------------------------------------------------
def _held_index(x):
    """For each sample, the index where its current value started (preserves sample-and-hold)."""
    idx = np.arange(x.size)
    same = np.zeros(x.size, dtype=bool)
    same[1:] = (x[1:] == x[:-1]) | (np.isnan(x[1:]) & np.isnan(x[:-1]))
    start = np.where(same, 0, idx)
    return np.maximum.accumulate(start)


def candidate_windows(tr: Trace, pre_s=60, post_s=150):
    """Start indices where [i-pre, i+post) is all LTE with valid RSRP and no real loss."""
    lte = tr.lte if tr.lte is not None else np.isfinite(tr.rsrp)
    fin = np.isfinite(tr.rsrp)
    n = lte.size
    if n < pre_s + post_s:
        return np.array([], dtype=int)
    c_off = np.concatenate([[0], np.cumsum(~lte)])
    c_nan = np.concatenate([[0], np.cumsum(~fin)])
    i = np.arange(pre_s, n - post_s)
    off = c_off[i + post_s] - c_off[i - pre_s]
    nan = c_nan[i + post_s] - c_nan[i - pre_s]
    # all LTE, and at most 3 % glitchy readings (the detector rides through those)
    return i[(off == 0) & (nan <= 0.03 * (pre_s + post_s))]


def inject_descent(tr: Trace, i0: int, rng, cfg: Config = DEFAULT, kind=None, pre_s=60, post_s=150) -> Trace:
    """Overlay a basement / deep-indoor entry starting at tick ``i0`` of a real trace."""
    kind = kind or str(rng.choice(["basement_ride", "basement_walk", "deep_indoor"], p=[0.45, 0.3, 0.25]))
    g = _draw_geometry(kind, rng)
    segs, _, dwell = _segments(g)
    segs = [seg for seg in segs if seg[0] > 0.0]          # descent part only, starts at the entrance
    tm, sm, _ = motion(segs, 0.0, dwell, cfg.meas.dt)
    lo, hi = i0 - pre_s, i0 + post_s
    t = tr.t[lo:hi] - tr.t[lo]
    t_entry = float(pre_s)
    L = np.interp(t - t_entry, tm, building_loss(g, sm), left=0.0, right=float(building_loss(g, sm[-1:])[0]))
    real = tr.rsrp[lo:hi].astype(float)
    hold = _held_index(real)
    L_held = L[hold]
    a = np.power(10.0, -L_held / 10.0)
    rsrp = np.round(real - L_held)
    # RSRQ: attenuate serving signal and interference together, the noise floor stays put
    q_real = tr.rsrq[lo:hi].astype(float)
    n0 = radio.db2lin(radio.noise_per_re_dbm(8.0))
    s_mw = radio.db2lin(real)
    d = s_mw / (12.0 * radio.db2lin(q_real))
    x = np.maximum(d - n0, 0.05 * s_mw)
    rsrq = np.round(radio.lin2db(s_mw * a / (12.0 * (x * a + n0))))
    rsrq = np.where(np.isfinite(q_real), rsrq, np.nan)
    nbr = np.round(tr.nbr[lo:hi].astype(float) - L_held)
    # ground truth: smoothed real level minus the structure loss
    level = pd.Series(real).rolling(5, center=True, min_periods=1).median().to_numpy() - L
    sinr_proxy = np.full(level.size, 30.0)          # RLF not modelled for injected events
    episodes = radio.loss_episodes(t, level, sinr_proxy, cfg.radio)
    for on, end, _ in episodes:
        m = (t >= on) & (t < end)
        rsrp[m] = rsrq[m] = nbr[m] = np.nan
    rsrp = np.where((rsrp >= -140) & (rsrp <= -43), rsrp, np.nan)
    meta = dict(base=tr.name, mobility=tr.meta.get("mobility"), i0=int(i0), geometry=g, t_entry=t_entry,
                injected=True, positive_kind=True)
    return Trace(name=f"inj-{tr.name}-{i0}", kind=f"inj_{kind}", t=t, rsrp=rsrp, rsrq=rsrq, nbr=nbr,
                 cell=tr.cell[lo:hi].copy(), episodes=episodes, meta=meta, truth=dict(t=t, L=L, level=level),
                 lte=np.ones(t.size, dtype=bool))


def injection_set(traces, n, seed, cfg: Config = DEFAULT, mobilities=("pedestrian", "car", "bus")):
    rng = np.random.default_rng(seed)
    pool = [(tr, candidate_windows(tr)) for tr in traces if tr.meta.get("mobility") in mobilities]
    pool = [(tr, c) for tr, c in pool if c.size]
    if not pool:
        return []
    weights = np.array([c.size for _, c in pool], dtype=float)
    weights /= weights.sum()
    out = []
    for _ in range(n):
        tr, cands = pool[int(rng.choice(len(pool), p=weights))]
        out.append(inject_descent(tr, int(rng.choice(cands)), rng, cfg))
    return out
