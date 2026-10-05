"""SANKET (संकेत, "early sign") - predictive cellular link-loss detection for delivery apps.

Package layout
--------------
core        dependency-free streaming detectors (the part that would ship inside an app;
            also runs on a phone under Termux)
radio       3GPP-based radio formulas: noise floor, RSRQ/SINR, throughput, link-loss ground truth
simulate    physics-based simulator of riders entering basements / indoor hubs (+ control scenarios)
realdata    loaders for the public UCC LTE drive-test traces, field logs, and trace injection
features    runs the streaming filters over many traces and evaluates trigger rules in bulk
evaluate    event matching, metrics and confidence intervals
prefetch    prefetch latency / tiered download model and data-cost accounting
deadzone    learned dead-zone memory (crowd-sourced geofence) simulation
tuning      fair parameter search for every detector under the same false-alarm budget

Only ``core`` is imported eagerly so that the phone script can use it without NumPy.
"""

__version__ = "1.0.0"
