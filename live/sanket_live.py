#!/usr/bin/env python3
"""SANKET live: run the link-loss early warning on a real phone, or replay a recording.

Needs only Python 3 (standard library) and the ``sanket/core.py`` file next to this folder.

  On an Android phone (Termux + Termux:API apps, location permission granted to Termux:API):
      python live/sanket_live.py --termux            # live, 1 reading per second, logs to data/field/
      python live/sanket_live.py --termux --notify   # also vibrate + notification when it fires

  On a laptop:
      python live/sanket_live.py --demo              # built-in walk into a basement
      python live/sanket_live.py --replay data/field/my_walk.csv --speed 4

Press Enter at any time to drop a marker into the log (e.g. "I am at the ramp now").
Privacy: nothing leaves the phone. Only signal readings and the serving cell id are logged
(no GPS, no IMEI/IMSI, no phone number).
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import os
import random
import select
import shutil
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from sanket.core import SanketDetector, valid_rsrp, valid_rsrq  # noqa: E402

DEFAULT_PARAMS = {"meas_sd": 3.0, "q": 0.1, "theta": -124.0, "horizon": 10.0, "p_thr": 0.7, "min_rate": 1.0,
                  "persistence": 1, "rsrq_mode": "off", "nbr_veto": True, "nbr_delta": 3.0, "nbr_diff": 1.5}
DEMO_TRACE = Path(__file__).resolve().parent / "demo_trace.csv"
NR_WARNING = ("Phone is on 5G NR; SANKET was tuned for LTE. Using NR SS-RSRP anyway. "
              "For the demo, set Mobile network > Preferred network type to 4G/LTE.")


def load_params(path: Path) -> dict:
    """Tuned SanketDetector parameters from results/params.json (falls back to the tuned defaults)."""
    try:
        data = json.loads(path.read_text())
        p = dict(data["params"]["sanket"])
        SanketDetector(**p)                      # validates names and values
        return p
    except (OSError, KeyError, ValueError, TypeError):
        return dict(DEFAULT_PARAMS)


# --------------------------------------------------------------------------------------
# Sources: each yields (t_seconds, rsrp, rsrq, nbr_rsrp, cell_id, tech)
# --------------------------------------------------------------------------------------
def parse_cellinfo(text: str):
    """Pick the registered LTE cell (or NR as a fallback) from termux-telephony-cellinfo JSON."""
    try:
        cells = json.loads(text)
    except json.JSONDecodeError:
        return None, None, None, None, None
    if not isinstance(cells, list):
        return None, None, None, None, None
    lte = [c for c in cells if isinstance(c, dict) and c.get("type") == "lte"]
    nr = [c for c in cells if isinstance(c, dict) and c.get("type") == "nr"]
    serving = next((c for c in lte if c.get("registered") is True), None)
    tech = "lte"
    if serving is None:
        serving = next((c for c in nr if c.get("registered") is True), None)
        tech = "nr" if serving is not None else None
    if serving is None:
        return None, None, None, None, None
    if tech == "lte":
        rsrp = serving.get("rsrp", serving.get("dbm"))
        rsrq = serving.get("rsrq")
        cell = serving.get("ci")
        neigh = [c.get("rsrp", c.get("dbm")) for c in lte if c is not serving and not c.get("registered")]
    else:
        rsrp = serving.get("ss_rsrp", serving.get("dbm"))
        rsrq = serving.get("ss_rsrq")
        cell = serving.get("nci")
        neigh = [c.get("ss_rsrp", c.get("dbm")) for c in nr if c is not serving and not c.get("registered")]
    neigh = [float(v) for v in neigh if isinstance(v, (int, float)) and valid_rsrp(v)]
    rsrp = float(rsrp) if isinstance(rsrp, (int, float)) and valid_rsrp(rsrp) else None
    rsrq = float(rsrq) if isinstance(rsrq, (int, float)) and valid_rsrq(rsrq) else None
    cell = cell if isinstance(cell, (int, str)) else None
    return rsrp, rsrq, (max(neigh) if neigh else None), cell, tech


def termux_source(period=1.0):
    exe = shutil.which("termux-telephony-cellinfo")
    if exe is None:
        sys.exit("termux-telephony-cellinfo not found. Install the Termux:API app and run: pkg install termux-api")
    t0 = time.monotonic()
    warned = False
    while True:
        tick = time.monotonic()
        try:
            out = subprocess.run([exe], capture_output=True, text=True, timeout=8, check=False).stdout
        except subprocess.TimeoutExpired:
            out = ""
        rsrp, rsrq, nbr, cell, tech = parse_cellinfo(out)
        if tech == "nr" and not warned:
            print("\n" + NR_WARNING + "\n")
            warned = True
        yield tick - t0, rsrp, rsrq, nbr, cell, tech or "-"
        time.sleep(max(0.0, period - (time.monotonic() - tick)))


def replay_source(path: Path, speed: float):
    """Replay a CSV recorded by this script (or any CSV with time + rsrp columns)."""
    with open(path, newline="", encoding="utf-8", errors="replace") as f:
        rows = list(csv.DictReader(f))
    if not rows:
        sys.exit(f"{path} has no rows")
    cols = {c.lower(): c for c in rows[0]}
    ct = next((cols[c] for c in ("t_s", "t_unix", "t", "time", "timestamp") if c in cols), None)
    cr = next((cols[c] for c in ("rsrp", "level", "dbm") if c in cols), None)
    if ct is None or cr is None:
        sys.exit(f"{path}: need a time column and an RSRP column")
    cq = next((cols[c] for c in ("rsrq", "qual") if c in cols), None)
    cn = next((cols[c] for c in ("nbr_rsrp", "nrxrsrp") if c in cols), None)
    cc = next((cols[c] for c in ("cell", "ci", "cellid") if c in cols), None)

    def num(v):
        try:
            x = float(v)
            return x if math.isfinite(x) else None
        except (TypeError, ValueError):
            return None

    t_first = None
    start = time.monotonic()
    for r in rows:
        t = num(r[ct])
        if t is None:
            continue
        t_first = t if t_first is None else t_first
        rel = t - t_first
        wait = start + rel / speed - time.monotonic()
        if wait > 0:
            time.sleep(wait)
        yield rel, num(r[cr]), num(r[cq]) if cq else None, num(r[cn]) if cn else None, \
            (r[cc] if cc else None), "replay"


def demo_source(speed: float, seed=7):
    """A rider walks 60 s on the street, then 25 s down a ramp into a basement (synthetic)."""
    rnd = random.Random(seed)
    start = time.monotonic()
    shadow = 0.0
    value = None
    for k in range(150):
        t = float(k)
        wait = start + t / speed - time.monotonic()
        if wait > 0:
            time.sleep(wait)
        shadow = 0.9 * shadow + rnd.gauss(0, 0.8)
        loss = 0.0 if t < 60 else min(45.0, 1.4 * (t - 60) ** 1.1)
        true = -92.0 + shadow - loss
        if k % 2 == 0:                                      # the modem refreshes every 2 s
            value = round(true + rnd.gauss(0, 1.5))
        rsrp = value if value is not None and value >= -124 else None
        nbr = round(true - 4 + rnd.gauss(0, 1.5)) if rsrp is not None else None
        yield t, rsrp, (-11.0 if rsrp is not None else None), nbr, 1, "demo"


# --------------------------------------------------------------------------------------
# Display + logging
# --------------------------------------------------------------------------------------
def bar(rsrp):
    if rsrp is None:
        return "[" + " " * 20 + "]"
    n = max(0, min(20, int(round((rsrp + 130) / 3.5))))
    return "[" + "#" * n + "." * (20 - n) + "]"


def notify(lead):
    for cmd in (["termux-vibrate", "-d", "400"],
                ["termux-notification", "--title", "SANKET: signal about to drop",
                 "--content", f"Could drop within {lead:.0f} s. Order data saved for offline use."]):
        exe = shutil.which(cmd[0])
        if exe:
            try:
                subprocess.run([exe] + cmd[1:], timeout=5, check=False, capture_output=True)
            except subprocess.TimeoutExpired:
                pass


def enter_pressed() -> bool:
    if not sys.stdin or not sys.stdin.isatty():
        return False
    try:
        ready, _, _ = select.select([sys.stdin], [], [], 0)
    except (OSError, ValueError):
        return False
    if ready:
        sys.stdin.readline()
        return True
    return False


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--termux", action="store_true", help="read the phone's cell info via Termux:API")
    src.add_argument("--replay", type=Path, help="replay a recorded CSV")
    src.add_argument("--demo", action="store_true", help="built-in synthetic basement walk")
    ap.add_argument("--speed", type=float, default=1.0, help="replay/demo speed-up factor")
    ap.add_argument("--params", type=Path, default=ROOT / "results" / "params.json")
    ap.add_argument("--out", type=Path, default=None, help="CSV log path (default data/field/sanket_<time>.csv)")
    ap.add_argument("--notify", action="store_true", help="vibrate + Android notification on alarm (Termux)")
    args = ap.parse_args(argv)
    if args.speed <= 0:
        ap.error("--speed must be positive")

    params = load_params(args.params)
    det = SanketDetector(**params)
    if args.termux:
        source = termux_source()
    elif args.replay:
        source = replay_source(args.replay, args.speed)
    elif DEMO_TRACE.exists():                   # the unseen test profile shown in Figure 1
        source = replay_source(DEMO_TRACE, args.speed)
    else:
        source = demo_source(args.speed)

    out = args.out or ROOT / "data" / "field" / f"sanket_{datetime.now():%Y%m%d_%H%M%S}.csv"
    out.parent.mkdir(parents=True, exist_ok=True)
    print(f"SANKET live  |  horizon {params['horizon']:.0f} s, loss level {params['theta']:.0f} dBm  |  log: {out}")
    print("Press Enter to add a marker. Ctrl+C to stop.\n")
    print(f"{'time':>6}  {'RSRP':>5} {'':22} {'RSRQ':>5}  {'trend':>8}  {'risk':>5}  status")
    alarms = 0
    marker_n = 0
    with open(out, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["t_s", "iso_time", "rsrp", "rsrq", "nbr_rsrp", "cell", "rat", "level", "rate_db_s",
                    "risk", "time_to_loss_s", "alarm", "marker"])
        try:
            for t, rsrp, rsrq, nbr, cell, tech in source:
                marker = ""
                if enter_pressed():
                    marker_n += 1
                    marker = f"mark{marker_n}"
                fired = det.step(t, rsrp, rsrq, nbr)
                ready = det.kf.ready and det.n_meas > 0
                rate = det.kf.rate if ready else float("nan")
                risk = det.risk() if ready else 0.0
                ttl = det.time_to_loss() if ready else float("inf")
                cached = det.last_alarm is not None and t - det.last_alarm < 600.0
                if fired:
                    alarms += 1
                    status = (f"*** PREFETCH NOW: falling {abs(rate):.1f} dB/s, could drop within "
                              f"{params['horizon']:.0f} s ***")
                    if args.notify:
                        notify(params["horizon"])
                elif rsrp is None:
                    status = ("LINK LOST - order data already cached, rider keeps working" if cached
                              else "LINK LOST - nothing cached")
                elif cached:
                    status = "order data cached (offline-ready)"
                elif risk >= 0.3:
                    status = "watching: signal falling"
                else:
                    status = "ok"
                r_txt = f"{rsrp:5.0f}" if rsrp is not None else "  -- "
                q_txt = f"{rsrq:5.0f}" if rsrq is not None else "  -- "
                trend = f"{rate:+6.2f}/s" if math.isfinite(rate) else "    --  "
                print(f"{t:6.0f}  {r_txt} {bar(rsrp)} {q_txt}  {trend}  {risk * 100:4.0f}%  {status}"
                      + (f"  [{marker}]" if marker else ""), flush=True)
                w.writerow([f"{t:.2f}", datetime.now().isoformat(timespec="seconds"),
                            "" if rsrp is None else int(rsrp), "" if rsrq is None else int(rsrq),
                            "" if nbr is None else int(nbr), "" if cell is None else cell, tech,
                            f"{det.kf.level:.2f}" if ready else "", f"{rate:.3f}" if ready else "",
                            f"{risk:.3f}", "" if not math.isfinite(ttl) else f"{ttl:.1f}", int(fired), marker])
                f.flush()
        except KeyboardInterrupt:
            pass
    print(f"\nStopped. {alarms} alarm(s). Log saved to {out}")
    print("Analyse it with:  ./.venv/bin/python -c \"from sanket.realdata import load_field_log as L; "
          f"t=L('{out}'); print(t.episodes)\"")


if __name__ == "__main__":
    main()
