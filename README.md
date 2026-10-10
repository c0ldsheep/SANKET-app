# SANKET for Android

SANKET (संकेत, "early sign") warns a few seconds before your phone loses mobile signal in a lift, a basement or a
parking ramp, and saves your downloads first. If a download is cut anyway, it continues from where it stopped.

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

SANKET is our answer to PRAYAS problem PRAYAS-COMP-26-S-020 at Terna Engineering College, made by Team Invicti with
our mentor, Dr. Rohini Palve. Website: https://c0ldsheep.github.io/SANKET-app/. Source code and the study:
https://github.com/c0ldsheep/PRAYAS-S020-SANKET. Results dashboard: https://c0ldsheep.github.io/SANKET-app/dashboard/.
