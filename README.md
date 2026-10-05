# SANKET for Android

SANKET (संकेत, "early sign") warns a few seconds before your phone loses mobile signal in a lift, a basement or a
parking ramp, and saves your downloads first. If a download is cut anyway, it continues from where it stopped.

## Download

**[Download SANKET 0.2.0 (APK, 260 KB)](https://github.com/c0ldsheep/SANKET-app/releases/download/v0.2.0/SANKET-0.2.0-test.apk)**

For Android 10 or newer. No GitHub account needed. All versions are on the [Releases](https://github.com/c0ldsheep/SANKET-app/releases) page.

## Install

1. Open the link above **on the phone**. If Chrome says the file might be harmful, tap **Download anyway**.
2. Tap **Open**. The first time, Android asks whether Chrome may install apps: tap **Settings**, turn on
   **Allow from this source**, and go back.
3. Tap **Install**. If an older SANKET is on the phone, the button says **Update**, and your data is kept.

If Google Play Protect asks about the app, let it scan the app, then install.

## "There was a problem parsing the package"

Android shows this when the file on the phone is not the whole app, for example after a download that was cut off
or a link that needed a login.

1. Open **Files**, then **Downloads**, and delete every `SANKET…apk` file.
2. Download again from the link above.
3. Check the size before you open it: **260 KB** (some apps show **266 kB**, the same file counted another way).
   Any other size is not the app.

## About this build

- A test build, signed with our development key. It passes 44 automated tests and was checked on an Android 13
  emulator. The field test in real basements and lifts is next.
- Your data stays on the phone, encrypted: no account, no server, no analytics.
- SHA-256 of the APK: `e32e6b9a83de366dcd8651f88bf59f4ee66ed4143af5a94f3a68273bad8e90e3`

SANKET is our answer to PRAYAS problem PRAYAS-COMP-26-S-020 at Terna Engineering College. The source code and the
study will be published with our submission. Team Invicti.
