# Putting SANKET inside a delivery app (Android)

`src/org/sanket/SanketDetector.java` is the detector itself: plain Java, no Android imports,
**verified tick-for-tick against the Python reference** (`GoldenCheck` replays 1,737 golden ticks,
0 mismatches). This page shows how an app would feed it. The Kotlin below is illustrative: it
was not compiled in this repository (no Android SDK here). The API facts were checked against
the AOSP source.

## 1. Which Android APIs give us the inputs

| Input | API | Permission (checked in AOSP) | Notes |
|---|---|---|---|
| Serving RSRP / RSRQ | `TelephonyCallback.SignalStrengthsListener` → `SignalStrength.getCellSignalStrengths()` → `CellSignalStrengthLte.getRsrp()/getRsrq()` | none | API 31+. Event-driven: arrives when the modem reports a change. |
| Best-neighbour RSRP (common-mode test) | `TelephonyCallback.CellInfoListener` and `TelephonyManager.requestCellInfoUpdate()` | `READ_PHONE_STATE` + `ACCESS_FINE_LOCATION` | Delivery apps already hold location permission. Updates are cached and rate-limited (Android Q+), which is fine: the detector treats stale values correctly. |
| Valid ranges | RSRP −140…−43 dBm, RSRQ −34…3 dB, else `CellInfo.UNAVAILABLE` | n/a | Map `UNAVAILABLE` to `Double.NaN`. |
| Faster signal reports | `TelephonyManager.setSignalStrengthUpdateRequest()` | `MODIFY_PHONE_STATE` **or carrier privileges** | Not available to normal apps. A carrier partnership (e.g. a telecom operator) could enable it. SANKET does not need it. |

## 2. Wiring (illustrative Kotlin)

```kotlin
class LinkWatcher(private val tm: TelephonyManager, private val onPrefetch: () -> Unit) {
    private val detector = SanketDetector(SanketDetector.Params.tuned())
    @Volatile private var rsrp = Double.NaN
    @Volatile private var rsrq = Double.NaN
    @Volatile private var nbr = Double.NaN   // best neighbour RSRP from a CellInfoListener (not shown); NaN turns the common-mode test off
    private val exec = Executors.newSingleThreadScheduledExecutor()

    private val callback = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
        override fun onSignalStrengthsChanged(s: SignalStrength) {
            val lte = s.getCellSignalStrengths(CellSignalStrengthLte::class.java).firstOrNull()
            rsrp = lte?.rsrp?.takeIf { it != CellInfo.UNAVAILABLE }?.toDouble() ?: Double.NaN
            rsrq = lte?.rsrq?.takeIf { it != CellInfo.UNAVAILABLE }?.toDouble() ?: Double.NaN
        }
    }

    fun start() {
        tm.registerTelephonyCallback(exec, callback)
        // One tick per second, like the logger used in the study (the detector de-duplicates repeats).
        exec.scheduleAtFixedRate({
            val t = SystemClock.elapsedRealtime() / 1000.0
            if (detector.step(t, rsrp, rsrq, nbr)) onPrefetch()
        }, 0, 1, TimeUnit.SECONDS)
    }

    fun stop() { tm.unregisterTelephonyCallback(callback); exec.shutdown() }
}
```

Run it inside the foreground service the app already uses for live location during an order, and stop it
when no order is active. There is no polling of the radio and no extra wakelock. The detector costs about
1.4 µs of CPU per reading, measured in pure Python on a laptop (the Java port was not benchmarked).

## 3. What "prefetch" should do

1. **Fetch in priority order**: T0 order token and OTP → T1 customer drop card → T2 route → T3 offline
   map tiles. Our model says the first three need about 3 s of warning and tiles need about 10 s.
2. **Use conditional requests** (`If-None-Match` / ETag). A repeat prefetch of unchanged data costs about
   400 bytes per tier, so false alarms cost almost nothing (results: under 0.1 MB a day per rider).
3. **Store safely**: keep the bundle in app-private storage, encrypted with a non-exportable Android Keystore
   AES-GCM key, and **delete it when the order is delivered**. Customer name, address and phone are personal
   data under India's DPDP Act 2023; keep only what the trip needs, for as long as the trip lasts.
4. **Dead-zone memory**: on reconnect after a loss, send only the last GPS fix before the loss (an entrance
   location, with no route and no identity). The server publishes a zone after 2 or more independent reports.
   The phone then prefetches on entering a 50 m geofence around a published zone.

## 4. Build and verify the Java port on a laptop

```bash
JDK=/opt/homebrew/opt/openjdk/bin          # any JDK 11+
$JDK/javac -d /tmp/sanket android/src/org/sanket/*.java
$JDK/java -cp /tmp/sanket org.sanket.GoldenCheck data/golden/sanket_golden.json
# -> 13 traces, 1737 ticks, 10 alarms, 0 mismatches
```
