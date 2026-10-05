"""The phone script: Termux JSON parsing and log round-trips (no phone needed)."""
import json
import subprocess
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "live"))
from sanket_live import load_params, parse_cellinfo  # noqa: E402

from sanket.realdata import load_field_log  # noqa: E402


def test_parse_registered_lte_and_best_neighbour():
    cells = [{"type": "lte", "registered": True, "rsrp": -97, "rsrq": -11, "dbm": -97, "ci": 12345},
             {"type": "lte", "registered": False, "rsrp": -101},
             {"type": "lte", "registered": False, "rsrp": -99},
             {"type": "nr", "registered": False, "ss_rsrp": -90}]
    assert parse_cellinfo(json.dumps(cells)) == (-97.0, -11.0, -99.0, 12345, "lte")


def test_parse_falls_back_to_nr_and_rejects_junk():
    nr = [{"type": "nr", "registered": True, "ss_rsrp": -105, "ss_rsrq": -12, "nci": 7}]
    assert parse_cellinfo(json.dumps(nr)) == (-105.0, -12.0, None, 7, "nr")
    for junk in ("", "not json", "{}", "[]", json.dumps([{"type": "lte", "registered": True, "rsrp": 2147483647}])):
        rsrp = parse_cellinfo(junk)[0]
        assert rsrp is None


def test_params_file_loads_and_bad_file_falls_back(tmp_path):
    p = load_params(ROOT / "results" / "params.json")
    assert "theta" in p and "horizon" in p
    bad = tmp_path / "bad.json"
    bad.write_text('{"params": {"sanket": {"theta": "oops"}}}')
    assert load_params(bad)["theta"] == -124.0


def test_demo_run_writes_a_log_the_importer_reads(tmp_path):
    out = tmp_path / "demo.csv"
    r = subprocess.run([sys.executable, str(ROOT / "live" / "sanket_live.py"), "--demo", "--speed", "500",
                        "--out", str(out)], capture_output=True, text=True, timeout=120, stdin=subprocess.DEVNULL)
    assert r.returncode == 0, r.stderr
    assert "PREFETCH NOW" in r.stdout and "LINK LOST - order data already cached" in r.stdout
    tr = load_field_log(out)
    assert tr.episodes and np.isfinite(tr.rsrp).sum() > 50
