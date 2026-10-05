# SANKET for Android

The app version of SANKET. It watches the phone's signal and keeps your downloads safe when the signal drops in a
lift or a basement. What each part does, and when it helps, is in [../FEATURES.md](../FEATURES.md).

## Build

Requirements: JDK 17 or newer, and the Android SDK with platform 35.

```
cd app
./gradlew :core:test :mobile:lintDebug :mobile:assembleDebug
```

The app runs on Android 10 (API 29) and newer. The result, `mobile/build/outputs/apk/debug/mobile-debug.apk`,
installs with `adb install`, or by copying it to a phone and opening it.

## How it is organised

- **`core/`**: plain Java with no Android code, so it is tested on a computer. It holds the detector (identical to
  the study's Python code on all 1,737 reference readings), lift detection, internet health, the 5G early warning,
  phone calibration, the place memory, the safety log, the offline notice and the policy that combines them.
  28 unit tests.
- **`mobile/`**: the Android parts. A foreground service reads the radio, barometer, location and network once a
  second; downloads resume where they stopped; everything is stored encrypted; one screen shows it all.

## Permissions, and why

| Permission | Why |
|---|---|
| Location | Android shows neighbour cells only with it, and the place memory needs it. Read only while protection runs. |
| Phone state | Lists the SIMs, so the signal of both SIMs can be read. |
| Notifications | The status while protecting, and the "signal may drop soon" alert. |
| Internet, network state | Downloads, and checking whether the internet really works. |
| Foreground service (location, data sync) | Keeps protection running, with a visible notification, while you use it. |

## Security and privacy

- No third-party runtime libraries: only the Android SDK and our own code. JUnit is used for tests only.
- Everything stays on the phone, encrypted with AES-256-GCM. The key lives in the Android Keystore and is deleted
  together with the data.
- No cloud backup, no account, no analytics and nothing uploaded. Only https links are downloaded.
- Positions are rounded to about 11 m, and the safety log keeps 30 days.

## Limits today

- Not yet tested on real phones in real buildings. The thresholds for lifts, 5G and slow internet are starting
  values, to be tuned with field recordings.
- Sharing places and notes, the dispatch notice and safety alerts need the fleet server, which is planned.
- English only for now.
