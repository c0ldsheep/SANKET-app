# SANKET for Android

SANKET (संकेत, "early sign") warns a few seconds before your phone loses mobile signal in a lift, a basement or a
parking ramp, and saves your downloads first. If a download is cut anyway, it continues from where it stopped.

## Download

**[Download SANKET 0.2.1 (APK, 262 KB)](https://github.com/c0ldsheep/SANKET-app/releases/download/v0.2.1/SANKET-0.2.1-test.apk)**

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
3. Download again from the link above, and check the size before you open it: **262 KB** (some apps show
   **268 kB**, the same file counted another way). Any other size is not the app.

## About this build

- A test build, signed with our development key. It passes 44 automated tests and was checked on an Android 13
  emulator. The field test in real basements and lifts is next.
- Your data stays on the phone, encrypted: no account, no server, no analytics.
- Try the demo: it replays a real ride into a basement and protects a sample download while the signal dies.
- SHA-256 of the APK: `d0cbbcd177389f62214bb07390ffe7e45c9a23532ea5eb409c74f3537b920753`

SANKET is our answer to PRAYAS problem PRAYAS-COMP-26-S-020 at Terna Engineering College. The source code and the
study will be published with our submission. Team Invicti.
