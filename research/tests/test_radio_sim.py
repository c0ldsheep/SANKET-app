"""Radio formulas, ground-truth loss detection and the simulator."""
import math

import numpy as np
import pytest

from sanket import radio
from sanket.config import DEFAULT, RadioConfig, SCENARIO_MIX
from sanket.simulate import building_loss, motion, simulate_dataset, simulate_profile


def test_noise_floor_per_resource_element():
    assert radio.noise_per_re_dbm(7.0) == pytest.approx(-125.24, abs=0.01)


def test_rsrq_physical_limits():
    s = radio.db2lin(-80.0)
    assert float(radio.rsrq_db(s, 0.0, 1e-30, 1 / 6)) == pytest.approx(-3.01, abs=0.01)   # empty cell
    assert float(radio.rsrq_db(s, 0.0, 1e-30, 1.0)) == pytest.approx(-10.79, abs=0.01)    # full, clean
    deep = float(radio.rsrq_db(radio.db2lin(-135.0), 0.0, radio.db2lin(-125.0), 1.0))
    assert deep < -20.0                                                                    # buried in noise


def test_tr36942_mapping():
    se = radio.spectral_efficiency(np.array([-11.0, -10.0, 0.0, 40.0]))
    assert se[0] == 0.0
    assert se[1] > 0.0
    assert se[2] == pytest.approx(0.6)                  # 0.6 * log2(1 + 1)
    assert se[3] == pytest.approx(4.4)                  # capped


def test_loss_episode_rules():
    cfg = RadioConfig()
    t = np.arange(0, 30, 0.1)
    s = np.full(t.size, -100.0)
    sinr = np.full(t.size, 5.0)
    assert radio.loss_episodes(t, s, sinr, cfg) == []
    s2 = s.copy()
    s2[(t >= 10) & (t < 11.5)] = -130.0                 # 1.5 s below Qrxlevmin: not long enough
    assert radio.loss_episodes(t, s2, sinr, cfg) == []
    s3 = s.copy()
    s3[t >= 12] = -130.0                                # stays below: out of service from 12 s
    ep = radio.loss_episodes(t, s3, sinr, cfg)
    assert len(ep) == 1 and ep[0][0] == pytest.approx(12.0) and math.isinf(ep[0][1]) and ep[0][2] == "no_cell"
    sinr4 = sinr.copy()
    sinr4[(t >= 5) & (t < 9)] = -12.0                   # radio link failure, then recovery
    ep = radio.loss_episodes(t, s, sinr4, cfg)
    assert len(ep) == 1 and ep[0][0] == pytest.approx(5.0) and ep[0][2] == "rlf"
    assert ep[0][1] == pytest.approx(9.0 + cfg.recover_s, abs=0.11)


def test_motion_reaches_the_end_and_dwells():
    t, s, v = motion([(0.0, 5.0), (30.0, 2.0)], -100.0, 10.0, 0.1)
    assert s[0] == -100.0 and s[-1] == pytest.approx(30.0)
    assert np.all(np.diff(s) >= 0)
    assert t[-1] - t[np.argmax(s >= 30.0)] == pytest.approx(10.0, abs=0.11)


def test_basement_loss_is_monotone_and_bounded():
    g = dict(kind="basement_ride", ramp_len=25.0, loss_b1=30.0, g_in=0.4, cap=45.0, b2=False, d_in=40.0,
             ramp2_len=20.0, loss_b2=12.0)
    s = np.linspace(-50, 200, 500)
    L = building_loss(g, s)
    assert np.all(np.diff(L) >= -1e-9)
    assert L[0] == 0.0 and L.max() == pytest.approx(45.0)


def test_simulation_is_reproducible_and_android_like():
    a = simulate_profile("basement_ride", np.random.default_rng(42))
    b = simulate_profile("basement_ride", np.random.default_rng(42))
    assert np.array_equal(a.rsrp, b.rsrp, equal_nan=True)
    v = a.rsrp[np.isfinite(a.rsrp)]
    assert np.all(v == np.round(v)) and v.min() >= -140 and v.max() <= -43
    q = a.rsrq[np.isfinite(a.rsrq)]
    assert np.all(q == np.round(q)) and q.min() >= -34 and q.max() <= 3
    for on, end, _ in a.episodes:                        # no readings while the link is down
        m = (a.t >= on) & (a.t < end)
        assert np.all(np.isnan(a.rsrp[m]))


def test_dataset_mix_and_loss_rates():
    ds = simulate_dataset(400, seed=11, keep_truth=False)
    kinds = [t.kind for t in ds]
    for k, frac in SCENARIO_MIX.items():
        assert abs(kinds.count(k) - frac * 400) <= 1
    rate = lambda k: np.mean([bool(t.episodes) for t in ds if t.kind == k])  # noqa: E731
    assert rate("basement_ride") > 0.6 and rate("basement_walk") > 0.6
    assert rate("street_ride") < 0.1 and rate("lobby_entry") < 0.1


def test_repeats_like_real_phones():
    ds = simulate_dataset(100, seed=3, keep_truth=False)
    rep = np.mean([np.mean(np.diff(t.rsrp[np.isfinite(t.rsrp)]) == 0) for t in ds])
    assert 0.45 < rep < 0.7                              # UCC: ~0.6
