# SANKET for Android

The app version of SANKET. It watches the phone's signal and keeps your downloads safe when the signal drops in a
lift or a basement. What each part does, and when it helps, is in [../FEATURES.md](../FEATURES.md).

## Install the test build

Download the APK from the repository's **Releases** page and open it on an Android 10 or newer phone. Android will
ask you to allow installing apps from your browser or file manager the first time. Test builds are signed with the
development key, so a new one installs over the old one and keeps your data.

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
