# SANKET for Android

The app version of SANKET. It watches the phone's signal and keeps your downloads safe when the signal drops in a
lift or a basement. What each part does, and when it helps, is in [../FEATURES.md](../FEATURES.md).

## Install the test build

Open the public download page, https://github.com/c0ldsheep/SANKET-app, on an Android 10 or newer phone and follow
its three steps. No GitHub account is needed. Test builds are signed with the development key, so a new one installs
over the old one and keeps your data.

## Publishing a test build

This repository is private, so its Releases page works only for people logged in to GitHub with access. Anyone else
gets GitHub's "Not Found" reply instead of the app, and if that reply is saved under the APK's name, Android says
"There was a problem parsing the package". That happened with 0.2.0. So test builds go on the public download
page, and each one is checked the way a stranger would get it:

1. Build and check: `./gradlew :core:test :mobile:lintDebug :mobile:assembleDebug`, then
   `apksigner verify --print-certs` on the APK. The certificate must stay the same, or updates will fail.
2. Write down the APK's size and SHA-256, and put them in the release notes and on the download page.
3. Publish it as a normal release (not a pre-release, so "latest" points to it) on the download repository, and
   update the version link in its README.
4. Download the link with no login, in a private browser window or with `curl -L`. It must give the same size and
   SHA-256.
5. Install it on a phone from that link.

## Build

Requirements: JDK 17 or newer, and the Android SDK with platform 35.

```
cd app
./gradlew :core:test :mobile:lintDebug :mobile:assembleDebug
```

The app runs on Android 10 (API 29) and newer. The result is `mobile/build/outputs/apk/debug/mobile-debug.apk`.

## How it is organised

- **`core/`**: plain Java with no Android code, so it is tested on a computer. It holds the detector (identical to
  the study's Python code on all 1,737 reference readings), lift detection, internet health, the 5G early warning,
  phone calibration, the place memory, the safety log, the offline notice, the policy that combines them, and the
  small rules the screen depends on (link cleaning, file names, retry timing, signal words, outage tracking).
  44 unit tests.
- **`mobile/`**: the Android parts.
  - `GuardService` reads the radio, the barometer, location and the network once a second while protection runs.
  - `Engine` turns those readings into the status the screen and the notification show.
  - `Transfers` downloads straight into Downloads/SANKET and continues after drops; `ResumeJob` finishes
    downloads after a restart, even when the app is not running.
  - `MainActivity` is the one screen; `ProtectionTile` is the Quick Settings tile.
  - Everything SANKET keeps is stored encrypted (`SecureFiles`, `Stores`).

The look is described in [DESIGN.md](DESIGN.md).

## Permissions, and why

| Permission | Why |
|---|---|
| Location | Android shows nearby towers only with it, and the place memory needs it. Read only while protection runs. |
| Phone state | Lists the SIMs, so the signal of both SIMs can be read. Android words this as "make and manage phone calls"; SANKET never makes, reads or records calls. |
| Notifications | The status while protecting, the "signal may drop soon" warning and the "download complete" notice. |
| Internet, network state | Downloads, and checking whether the internet really works. |
| Foreground service (location, data sync) | Keeps protection running, with a visible notification, while you use it. |
| Run at startup | Lets unfinished downloads continue by themselves after the phone restarts. |
| Vibrate | Two short taps with a warning, so riders feel it without looking. |

## Security and privacy

- No third-party runtime libraries: only the Android SDK and our own code. JUnit is used for tests only.
- Everything stays on the phone, encrypted with AES-256-GCM. The key lives in the Android Keystore and is deleted
  together with the data.
- No cloud backup, no account, no analytics and nothing uploaded. Only https links are downloaded.
- Positions are rounded to about 11 m, and the safety log keeps 30 days.

## How it was checked

- 44 unit tests in `core/`, and Android lint with no issues.
- On an Android 13 emulator: every screen in light and dark mode; the demo trip; a real 8.6 MB download cut three
  times (once by airplane mode) and a 16.1 MB download interrupted by a phone restart, both byte for byte identical
  to direct downloads; the fix-it hints; the Quick Settings tile; and 3G fallback.

## Limits today

- Not yet tested on real phones in real buildings. The thresholds for lifts, 5G and slow internet are starting
  values, to be tuned with field recordings. Standalone 5G (Jio True 5G) support is experimental.
- SANKET predicts drops on 4G and 5G. On 2G and 3G it still protects downloads but cannot warn early.
- Sharing places and notes, the dispatch notice and safety alerts need the fleet server, which is planned.
- English only for now.
