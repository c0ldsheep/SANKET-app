# SANKET

SANKET (संकेत, "early sign") warns a few seconds before your phone loses mobile signal in a lift, a basement or a
parking ramp, and saves what matters first: a delivery order, a half-finished download, an upload in progress. It
began as our answer to PRAYAS problem PRAYAS-COMP-26-S-020 at Terna Engineering College, Navi Mumbai, made by Team
Invicti with our mentor, Dr. Rohini Palve.

**[Website](https://c0ldsheep.github.io/SANKET-app/) · [Download the app](#download) ·
[Results dashboard](https://c0ldsheep.github.io/SANKET-app/dashboard/) · [The study](research) ·
[The app's code](app) · [Every feature](FEATURES.md) · [Our roadmap](ROADMAP.md)**

Everything for SANKET is in this one repository:

| Part | What it is | Status |
|---|---|---|
| [`research/`](research) | The PRAYAS study: a simulator calibrated on 52.4 h of real 4G recordings, five detectors compared fairly on unseen data, results and an interactive dashboard | Complete |
| [`app/`](app) | The Android app built on the study: protected downloads, lift detection, a memory of dead zones, a safety log, test rides for field tests | Unit-tested and checked on an Android 13 emulator; field testing is next |
| [`docs/`](docs) | The website and the online dashboard, served at https://c0ldsheep.github.io/SANKET-app/ | Live |
| [Releases](https://github.com/c0ldsheep/SANKET-app/releases) | Every test build of the app, with its size and checksum | 0.4.0 is the latest |

## Download

**[Download SANKET 0.4.0 (APK, 290 KB)](https://github.com/c0ldsheep/SANKET-app/releases/download/v0.4.0/SANKET-0.4.0-test.apk)**

For Android 10 or newer. No GitHub account needed. All versions are on the [Releases](https://github.com/c0ldsheep/SANKET-app/releases) page.

## Install

1. Open the link above **on the phone**. If Chrome says the file might be harmful, tap **Download anyway**.
2. Tap **Open**. The first time, Android asks whether Chrome may install apps: tap **Settings**, turn on
   **Allow from this source**, and go back.
3. Tap **Install**. If an older SANKET is on the phone, the button says **Update**, and your data is kept.

If Google Play Protect asks about the app, let it scan the app, then install.

## "There was a problem parsing the package"

Android shows this when it can't read the file: the download was cut off, the link needed a login, or the phone's
installer got stuck.

1. Open **Files**, then **Downloads**, and delete every `SANKET…apk` file.
2. Restart the phone.
3. Download again from the link above, and check the size before you open it: **290 KB** (some apps show
   **297 kB**, the same file counted another way). Any other size is not the app.

## About this build

- A test build, signed with our development key. It passes 56 automated tests and was checked on an Android 13
  emulator. The field test in real basements and lifts is next.
- Your data stays on the phone, encrypted: no account, no server, no analytics.
- Try the demo: it replays a simulated ride into a basement from our study and protects a sample download while
  the signal dies.
- New in 0.4.0: **Record a test ride**, for testing SANKET in real basements and lifts. It saves the signal every
  second with SANKET's warnings, kept encrypted until you share it as a CSV file (no GPS). Also an About screen,
  and the demo now says it replays a test ride from our study.
- New in 0.3.0: lifts are noticed on phones without a barometer, and downloads of 100 MB or more ask before
  using mobile data.
- SHA-256 of the APK: `e6c3aefe7f14afda0ad5455a23a52d947f635f70b71e3176947539d8a4a3817f`

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

cd app && ./gradlew :core:test :mobile:assembleDebug            # the Android app and its 56 unit tests
```

## Credits

UCC 4G LTE dataset: D. Raca, J. J. Quinlan, A. H. Zahran, C. J. Sreenan, ACM MMSys 2018, CC BY 4.0.
Building-entry losses: arXiv:2605.23483. Throughput model: 3GPP TR 36.942. AI assistance: Claude (Anthropic) and
Gemini, as stated in our PRAYAS submission.

Made by Team Invicti (Computer Engineering, Terna Engineering College, Navi Mumbai) with our mentor, Dr. Rohini
Palve.
