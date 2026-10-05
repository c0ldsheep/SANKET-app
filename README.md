# SANKET

SANKET (संकेत, "early sign") predicts a mobile-signal drop a few seconds before it happens, so a phone can save
what matters first: a delivery order, a half-finished download, an upload in progress. It began as our answer to
PRAYAS problem PRAYAS-COMP-26-S-020 at Terna Engineering College (mentor: Dr. Rohini Palve).

| Folder | What it is | Status |
|---|---|---|
| [`research/`](research) | The PRAYAS study: a simulator calibrated on 52.4 h of real 4G recordings, five detectors compared fairly on unseen data, results and an interactive dashboard | Complete |
| [`app/`](app) | The Android app built on the study: protected downloads, lift detection, a memory of dead zones, a safety log | Builds cleanly and passes its tests; field testing is next |

**Every feature, the situation it helps in and its status: [FEATURES.md](FEATURES.md).**

## What the study found (unseen test data)

- SANKET warned at least 10 s before the loss in **43.8%** of basement entries, against 10.2% for a simple
  threshold, at 1.76 false alarms per hour on real rides.
- It got the critical order data onto the phone before the loss in **80.2%** of entries (simple threshold: 46.4%).
  That data needs only 3 s of warning.
- There is a physical limit: in 19% of entries the signal goes from normal to nothing in under 10 s, so the signal
  alone cannot reach 85% at 10 s. With a shared memory of dead zones, warnings reached **95%** by day 7.

![A scooter goes down a basement ramp: SANKET warns 16 s before the link is lost, a simple threshold 2 s before](research/results/figures/fig1_anatomy.png)

## Try it

```
cd research && ./setup.sh                                       # the study: installs, then runs its 56 tests
./.venv/bin/python experiments/run_all.py --reuse-params       # reproduces every number and figure (about 2 min)
open web/SANKET_dashboard.html                                  # interactive dashboard, works offline

cd app && ./gradlew :core:test :mobile:assembleDebug            # the Android app and its 28 unit tests
```

## Credits

UCC 4G LTE dataset: D. Raca, J. J. Quinlan, A. H. Zahran, C. J. Sreenan, ACM MMSys 2018, CC BY 4.0.
Building-entry losses: arXiv:2605.23483. Throughput model: 3GPP TR 36.942. AI assistance: Claude (Anthropic) and
Gemini, as stated in our PRAYAS submission.

Team Invicti: Durva Gosavi and Sahil Waradkar, Computer Engineering, Terna Engineering College.
