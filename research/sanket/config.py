"""Every modelling assumption in one place, with where it comes from.

Values marked [ASSUMED] are engineering judgement inside physically plausible ranges;
they are varied in the sensitivity analysis. Values marked [CALIBRATED] were fitted to
the public UCC LTE drive-test traces before any detector was evaluated.
"""
from dataclasses import dataclass, field


@dataclass(frozen=True)
class RadioConfig:
    scs_hz: float = 15e3                     # LTE subcarrier spacing (one resource element)
    noise_figure_db: tuple = (7.0, 9.0)      # [ASSUMED] typical handset receiver noise figure
    # Radio link monitoring (3GPP TS 36.133): Qout ~ 10 % hypothetical PDCCH BLER, Qin ~ 2 %.
    # The SINR values that correspond are implementation dependent; -8 / -6 dB are the
    # commonly used approximations. [ASSUMED]
    qout_db: float = -8.0
    qin_db: float = -6.0
    t310_s: float = 1.0                      # TS 36.331 T310 (network-configurable, 1 s typical) [ASSUMED]
    # Idle-mode cell-selection criterion (TS 36.304 S-criterion): a cell is only usable when
    # RSRP > Qrxlevmin (broadcast in SIB1). [ASSUMED value; swept -120 / -124 / -128 dBm]
    qrxlevmin_dbm: float = -124.0
    oos_s: float = 2.0                       # time below Qrxlevmin before the phone is out of service [ASSUMED]
    recover_margin_db: float = 3.0
    recover_s: float = 2.0
    # 3GPP TR 36.942 Annex A.1, DL baseline: attenuated, truncated Shannon bound.
    alpha: float = 0.6
    sinr_min_db: float = -10.0
    thr_max_bps_hz: float = 4.4


@dataclass(frozen=True)
class MeasurementConfig:
    dt: float = 0.1                          # physics time step (s)
    meas_period_s: float = 0.2               # modem measurement period (s)
    l3_alpha: float = 0.5                    # TS 36.331 L3 filter, filterCoefficient k=4 -> a = 1/2^(k/4)
    # Per-measurement estimation error. A still phone averages fast fading well; a moving one
    # does not (UCC: residual 1.24 dB static vs 2.8-3.3 dB moving, at any speed).
    rsrp_noise_db: float = 2.2               # [CALIBRATED] still: -> 1.27 dB after the L3 filter
    rsrp_noise_db_moving: float = 3.5        # [CALIBRATED] moving
    rsrq_noise_db: float = 2.0               # [CALIBRATED] (UCC RSRQ residual 1.33 dB)
    report_periods_s: tuple = (1.0, 2.0, 3.0)
    report_probs: tuple = (0.1, 0.6, 0.3)    # [CALIBRATED] UCC: ~60 % of 1 Hz samples are repeats
    log_period_s: float = 1.0                # app polls / logs once per second


@dataclass(frozen=True)
class EnvironmentConfig:
    # Outdoor serving RSRP at street level. UCC medians are -94 (car) to -98 dBm (pedestrian). [ASSUMED]
    outdoor_rsrp_mean: float = -92.0
    outdoor_rsrp_sd: float = 7.0
    outdoor_rsrp_clip: tuple = (-110.0, -72.0)
    bg_sir_db: tuple = (6.0, 15.0)           # other-cell interference below serving [ASSUMED]
    bg_shadow_scale: float = 0.4             # background = sum of many cells, so it fades less
    load: tuple = (0.5, 1.0)                 # serving-cell resource load (sets RSRQ)
    nbr_load: tuple = (0.4, 1.0)
    # Large-scale shadowing. [CALIBRATED] on UCC traces (best fit sigma 3 dB, decorrelation
    # 35 m; 3GPP TR 38.901 UMa LOS uses 4 dB / 37 m, tested in the sensitivity analysis).
    shadow_sd_out: float = 3.0
    shadow_dcorr_out: float = 35.0
    shadow_sd_in: float = 3.0                # [ASSUMED] inside the structure (independent per cell,
    shadow_dcorr_in: float = 5.0             #  so cells do not fade identically indoors)
    # Optional slow variation common to all cells (off: tying every cell to one slow fade
    # left no neighbour to hand over to and produced unrealistic street-level outages).
    site_sd: float = 0.0
    site_dcorr: float = 200.0
    # Best neighbour = serving + gap(s); the gap wanders. [CALIBRATED] to UCC neighbour-minus-
    # serving percentiles (5/25/50/75/95 % = -9/-5/-2/0/+8 dB).
    gap_mean_db: tuple = (-4.5, 1.0)
    gap_sd: float = 3.5
    gap_dcorr: float = 30.0
    # Inside the structure each cell also fades on its own a little (different openings / paths).
    indoor_cell_diff_sd: float = 2.5         # [ASSUMED] swept in the sensitivity analysis
    indoor_cell_diff_dcorr: float = 5.0
    ho_offset_db: tuple = (2.0, 8.0)         # A3 offset + hysteresis (network-configured) [ASSUMED]
    a3_ttt_s: float = 0.32                   # time-to-trigger
    # Neighbour readings are patchy on real phones: UCC has them 58 % of the time, in runs of ~14 s.
    nbr_avail: tuple = (0.3, 1.0)            # [CALIBRATED] per-trace availability
    nbr_on_mean_s: float = 14.0


# Scenario parameter ranges (uniform unless stated). Building-entry losses are anchored on
# Rohrig & Cramer (2026), LTE-M in an underground car park: -27 dB at the first underground
# level, -38 dB one level lower, -52 dB two levels lower (Table III, arXiv:2605.23483).
SCENARIOS = {
    # --- entries that usually end in link loss -----------------------------------------
    "basement_ride": dict(   # rider rides a scooter down the ramp into basement parking
        v_out=(4.0, 8.0), t_out=(30.0, 80.0), v_ramp=(1.5, 3.5), ramp_len=(15.0, 40.0),
        loss_b1=(22.0, 35.0), v_in=(1.5, 3.0), d_in=(15.0, 60.0), g_in=(0.2, 0.6), cap=(35.0, 50.0),
        p_b2=0.35, ramp2_len=(15.0, 30.0), loss_b2=(10.0, 15.0), d_in2=(10.0, 40.0), dwell=(20.0, 40.0)),
    "basement_walk": dict(   # rider parks outside and walks down stairs / a ramp
        v_out=(1.0, 1.5), t_out=(30.0, 80.0), v_ramp=(0.7, 1.2), ramp_len=(8.0, 20.0),
        loss_b1=(22.0, 35.0), v_in=(1.0, 1.5), d_in=(10.0, 50.0), g_in=(0.2, 0.6), cap=(35.0, 50.0),
        p_b2=0.25, ramp2_len=(8.0, 15.0), loss_b2=(10.0, 15.0), d_in2=(10.0, 30.0), dwell=(20.0, 40.0)),
    "deep_indoor": dict(     # walking deep into a concrete cloud-kitchen hub / mall
        v_out=(1.0, 1.5), t_out=(30.0, 80.0), wall=(10.0, 18.0), g_in=(0.3, 0.8), d_in=(30.0, 80.0),
        cap=(35.0, 55.0), dwell=(20.0, 40.0)),
    # --- look-alikes that should NOT need a prefetch (ground truth still decides) ---------
    "lobby_entry": dict(     # ground-floor shop / lobby: a step down that plateaus
        v_out=(1.0, 1.5), t_out=(30.0, 80.0), wall=(6.0, 14.0), g_in=(0.0, 0.15), d_in=(5.0, 20.0),
        cap=(20.0, 20.0), dwell=(20.0, 40.0)),
    "underpass": dict(       # riding under a flyover / through a short underpass
        v_out=(5.0, 10.0), t_out=(30.0, 60.0), length=(20.0, 80.0), depth=(6.0, 18.0), edge=(6.0, 12.0),
        t_after=(30.0, 60.0)),
    "street_ride": dict(v_out=(3.0, 9.0), t_total=(120.0, 240.0)),
    "cell_edge_handover": dict(  # serving cell fades while the neighbour rises -> handover
        v_out=(4.0, 8.0), t_total=(90.0, 180.0), k_db_per_m=(0.04, 0.15)),
}

POSITIVE_KINDS = ("basement_ride", "basement_walk", "deep_indoor")
CONTROL_KINDS = ("lobby_entry", "underpass", "street_ride", "cell_edge_handover")
SCENARIO_MIX = {"basement_ride": 0.25, "basement_walk": 0.15, "deep_indoor": 0.15,
                "lobby_entry": 0.12, "underpass": 0.12, "street_ride": 0.11, "cell_edge_handover": 0.10}


@dataclass(frozen=True)
class EvalConfig:
    min_lead_s: float = 10.0                 # problem statement: 10-15 s predictive window
    stretch_lead_s: float = 15.0
    max_lead_s: float = 60.0                 # an alarm earlier than this is not "predicting" this loss
    fa_budget_per_h: float = 2.0             # tuning constraint on real traces
    target_accuracy: float = 0.85
    real_fa_mobility: tuple = ("car", "bus", "pedestrian", "static")  # trains are not delivery riders


@dataclass(frozen=True)
class PrefetchConfig:
    # Delivery manifest split into priority tiers (bytes, compressed). [ASSUMED sizes]
    tiers: tuple = (("T0 order token + OTP", 2_000),
                    ("T1 customer drop card", 4_000),
                    ("T2 route + turn-by-turn", 25_000),
                    ("T3 offline map tiles", 400_000))
    bandwidth_hz: float = 10e6               # one 10 MHz LTE carrier
    share: tuple = (0.10, 0.50)              # fraction of cell resources this user gets [ASSUMED]
    rtt_s: float = 0.06                      # radio + core round trip at good SINR [ASSUMED]
    rtt_bad_s: float = 0.16                  # at SINR < 0 dB (HARQ / scheduling) [ASSUMED]
    rrc_promotion_s: float = 0.2             # idle -> connected [ASSUMED]
    server_s: float = 0.15                   # backend think time per request [ASSUMED]
    mss_bytes: int = 1460
    init_cwnd_segments: int = 10             # RFC 6928 initial window
    not_modified_bytes: int = 400            # HTTP 304 response incl. headers (ETag hit)


@dataclass(frozen=True)
class Config:
    radio: RadioConfig = field(default_factory=RadioConfig)
    meas: MeasurementConfig = field(default_factory=MeasurementConfig)
    env: EnvironmentConfig = field(default_factory=EnvironmentConfig)
    eval: EvalConfig = field(default_factory=EvalConfig)
    prefetch: PrefetchConfig = field(default_factory=PrefetchConfig)


DEFAULT = Config()
