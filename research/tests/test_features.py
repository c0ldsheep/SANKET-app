"""The vectorised tuning path must raise exactly the same alarms as the streaming detectors."""
import numpy as np
import pytest

from sanket import features as fx
from sanket.evaluate import run_detector
from sanket.realdata import load_ucc
from sanket.simulate import simulate_dataset
from sanket.tuning import COOLDOWN_S, condition, detector_params, factory


@pytest.fixture(scope="module")
def traces():
    sim = simulate_dataset(60, seed=1234, keep_truth=False)
    real = [tr for tr in load_ucc(mobilities=("pedestrian", "car")) if tr.duration_s < 1500][:4]
    return sim + real


CASES = [
    ("threshold", {}, {"theta": -110.0, "persistence": 2}),
    ("naive_gradient", {"delta_s": 4.0}, {"g": 1.0, "persistence": 1}),
    ("slope_ttl", {"window_s": 10.0, "method": "ols"}, {"theta": -121.0, "horizon": 15.0, "min_rate": 0.5,
                                                        "persistence": 2}),
    ("slope_ttl", {"window_s": 6.0, "method": "theilsen"}, {"theta": -124.0, "horizon": 20.0, "min_rate": 0.25,
                                                            "persistence": 1}),
    ("sanket", {"meas_sd": 2.5, "q": 0.2}, {"theta": -124.0, "horizon": 20.0, "p_thr": 0.5, "min_rate": 0.25,
                                            "persistence": 2, "veto": None, "rsrq_mode": "off"}),
    ("sanket", {"meas_sd": 1.5, "q": 0.8}, {"theta": -121.0, "horizon": 15.0, "p_thr": 0.7, "min_rate": 0.5,
                                            "persistence": 1, "veto": (6.0, 1.0), "rsrq_mode": "confirm"}),
    ("sanket", {"meas_sd": 3.5, "q": 0.05}, {"theta": -118.0, "horizon": 25.0, "p_thr": 0.3, "min_rate": 0.0,
                                             "persistence": 3, "veto": (10.0, 0.5), "rsrq_mode": "off"}),
]


@pytest.mark.parametrize("name,fparams,cparams", CASES)
def test_bulk_equals_streaming(traces, name, fparams, cparams):
    bank = fx.extract(factory(name, fparams), traces)
    idx = fx.fire(bank, condition(name, bank.F, fparams, cparams), cparams.get("persistence", 1), COOLDOWN_S)
    bulk = {}
    for i in idx:
        bulk.setdefault(bank.names[bank.tid[i]], []).append(float(bank.t[i]))
    stream = run_detector(factory(name, detector_params(fparams, cparams)), traces)
    stream = {k: v for k, v in stream.items() if v}
    assert bulk == stream
    assert sum(len(v) for v in stream.values()) > 0, "case raised no alarms; it would test nothing"


def test_sanket_dual_mode_matches(traces):
    fparams = {"meas_sd": 2.5, "q": 0.2}
    c = {"theta": -121.0, "horizon": 15.0, "p_thr": 0.5, "min_rate": 0.25, "persistence": 1}
    bank = fx.extract(factory("sanket", fparams), traces)
    cond = fx.cond_sanket(bank.F, fparams["q"], c["theta"], c["horizon"], c["p_thr"], c["min_rate"],
                          rsrq_mode="dual")
    idx = fx.fire(bank, cond, 1, COOLDOWN_S)
    bulk = sorted((bank.names[bank.tid[i]], float(bank.t[i])) for i in idx)
    stream = run_detector(factory("sanket", {**fparams, **c, "rsrq_mode": "dual"}), traces)
    stream = sorted((k, t) for k, v in stream.items() for t in v)
    assert bulk == stream
