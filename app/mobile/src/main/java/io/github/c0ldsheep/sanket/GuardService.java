package io.github.c0ldsheep.sanket;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import io.github.c0ldsheep.sanket.core.GuardPolicy;
import java.util.List;

/**
 * Runs protection in the background while the user wants it, or while downloads are active. It
 * feeds {@link Engine} once a second from the radio, the barometer, location and the network, and
 * shows the status as a notification. When it was started only for downloads it stops itself two
 * minutes after the last one, so it uses battery only while it helps.
 */
public final class GuardService extends Service implements Engine.Listener {
    static final String ACTION_START = "io.github.c0ldsheep.sanket.action.START";
    static final String ACTION_DOWNLOADS = "io.github.c0ldsheep.sanket.action.DOWNLOADS";
    static final String ACTION_DEMO = "io.github.c0ldsheep.sanket.action.DEMO";
    static final String ACTION_STOP_DEMO = "io.github.c0ldsheep.sanket.action.STOP_DEMO";
    private static final String TAG = "SanketService";
    private static final long IDLE_STOP_MS = 120_000L;
    private static final long ALIVE_EVERY_MS = 15_000L;
    private static final long NOTIFY_EVERY_MS = 2_000L;
    private static final long POWER_EVERY_MS = 30_000L;
    private static final long ALERT_GAP_MS = 3L * 60L * 1000L;
    private static final int LOW_BATTERY_PERCENT = 15;

    /** The running instance, so the notification and the tile can stop protection. */
    private static volatile GuardService current;

    private Engine engine;
    private HandlerThread worker;
    private Handler handler;
    private SensorManager sensors;
    private LocationManager locations;
    private ConnectivityManager connectivity;
    private volatile boolean manual;
    // The fields below are used only on the worker thread.
    private boolean active;
    private boolean demo;
    private boolean inputs;
    private boolean lowPower;
    private long idleSince = -1L;
    private long aliveWrittenAt;
    private long notifiedAt;
    private long powerCheckedAt = Long.MIN_VALUE / 2;
    private long alertedAt = Long.MIN_VALUE / 2;

    private final Runnable tick = this::onTick;

    private final SensorEventListener pressure = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            long now = SystemClock.elapsedRealtimeNanos();
            long stamp = Math.abs(event.timestamp - now) < 10_000_000_000L ? event.timestamp : now;
            engine.onPressure(stamp / 1e9, event.values[0]);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    };

    // All four methods are implemented: before Android 11 they had no default implementations.
    private final LocationListener location = new LocationListener() {
        @Override
        public void onLocationChanged(Location l) { engine.onLocation(l); }

        @Override
        public void onProviderEnabled(String provider) { }

        @Override
        public void onProviderDisabled(String provider) { }

        @Override
        @SuppressWarnings("deprecation")
        public void onStatusChanged(String provider, int status, Bundle extras) { }
    };

    private final ConnectivityManager.NetworkCallback network = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) {
            engine.onNetwork(true, caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
        }

        @Override
        public void onLost(Network n) { engine.onNetwork(false, false, false, false); }
    };

    static void send(Context ctx, String action) {
        ctx.startForegroundService(new Intent(ctx, GuardService.class).setAction(action));
    }

    /** Stops everything at once, for "delete my data". */
    static void stop(Context ctx) { ctx.stopService(new Intent(ctx, GuardService.class)); }

    /** True while the user's protection is on. */
    static boolean protecting() {
        GuardService s = current;
        return s != null && s.manual;
    }

    /** Turns protection off, from the screen, the notification or the tile. Downloads keep going. */
    static void requestStop() {
        GuardService s = current;
        if (s != null) s.handler.post(s::stopProtection);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        current = this;
        engine = Engine.get(this);
        sensors = getSystemService(SensorManager.class);
        locations = getSystemService(LocationManager.class);
        connectivity = getSystemService(ConnectivityManager.class);
        Notes.createChannels(this);
        worker = new HandlerThread("sanket-guard");
        worker.start();
        handler = new Handler(worker.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null || intent.getAction() == null ? ACTION_START : intent.getAction();
        goForeground();
        if (ACTION_START.equals(action)) manual = true;
        handler.post(() -> {
            if (ACTION_DEMO.equals(action)) {
                begin(true);
            } else if (ACTION_STOP_DEMO.equals(action)) {
                if (demo) endDemo();
                else if (!active) stopSelf();   // the demo had already ended
            } else if (!active || demo) {
                if (!demo) begin(false);
                else engine.setProtecting(manual);
            } else {
                engine.setProtecting(manual);
                unregisterInputs();   // permissions may have changed since the last start
                registerInputs();
            }
            ProtectionTile.refresh(this);
        });
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        current = null;
        Handler h = handler;
        h.post(() -> {
            h.removeCallbacks(tick);
            if (inputs) unregisterInputs();
            active = false;
            engine.stop();
        });
        engine.markStopped();
        worker.quitSafely();
        stopForeground(STOP_FOREGROUND_REMOVE);
        ProtectionTile.refresh(this);
        super.onDestroy();
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        // Android 15 limits background data work to six hours a day. Unfinished downloads
        // continue later through ResumeJob.
        Log.i(TAG, "Foreground time limit reached; stopping");
        stopSelf();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onLevelChanged(GuardPolicy.Level level, boolean downloadsActive) {
        notifyStatus();
        long now = SystemClock.elapsedRealtime();
        if (level == GuardPolicy.Level.PROTECT && downloadsActive && !demo && now - alertedAt >= ALERT_GAP_MS) {
            alertedAt = now;
            Notes.alert(this, getString(R.string.alert_drop_title), getString(R.string.alert_drop_text));
        }
    }

    @Override
    public void onLongOutage(int minutes) {
        Notes.alert(this, getResources().getQuantityString(R.plurals.alert_long_title, minutes, minutes),
                getString(R.string.alert_long_text));
    }

    private void begin(boolean demoMode) {
        handler.removeCallbacks(tick);
        if (inputs) unregisterInputs();
        demo = demoMode;
        active = true;
        idleSince = -1L;
        engine.start(demoMode, manual, this);
        if (!demoMode) registerInputs();
        handler.post(tick);
    }

    /** After the demo, real protection carries on if it was on, or while downloads need it. */
    private void endDemo() {
        if (manual || engine.transfers.busy()) {
            begin(false);
        } else {
            stopSelf();
        }
    }

    private void stopProtection() {
        manual = false;
        engine.setProtecting(false);
        if (demo || engine.transfers.busy()) {
            notifyStatus();
        } else {
            stopSelf();
        }
        ProtectionTile.refresh(this);
    }

    private void onTick() {
        if (!active) return;
        if (!engine.tick()) {
            endDemo();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (!demo && !manual) {
            if (engine.transfers.busy()) {
                idleSince = -1L;
            } else if (idleSince < 0L) {
                idleSince = now;
            } else if (now - idleSince > IDLE_STOP_MS) {
                stopSelf();
                return;
            }
        }
        if (!demo && now - aliveWrittenAt > ALIVE_EVERY_MS) {
            aliveWrittenAt = now;
            engine.markAlive();
        }
        if (!demo && now - powerCheckedAt > POWER_EVERY_MS) {
            powerCheckedAt = now;
            checkPower();
        }
        if (now - notifiedAt >= NOTIFY_EVERY_MS) notifyStatus();
        handler.postDelayed(tick, demo ? 250L : 1000L);
    }

    /** In Battery Saver or below 15%, SANKET stops using GPS; network location is enough for places. */
    private void checkPower() {
        PowerManager pm = getSystemService(PowerManager.class);
        BatteryManager bm = getSystemService(BatteryManager.class);
        boolean saver = pm != null && pm.isPowerSaveMode();
        int percent = bm == null ? 100 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        boolean charging = bm != null && bm.isCharging();
        boolean low = saver || (percent > 0 && percent <= LOW_BATTERY_PERCENT && !charging);
        if (low == lowPower) return;
        lowPower = low;
        engine.setLowPower(low);
        if (inputs) {
            unregisterInputs();
            registerInputs();
        }
    }

    private void notifyStatus() {
        notifiedAt = SystemClock.elapsedRealtime();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(Notes.STATUS_ID, Notes.status(this, engine.snapshot(), engine.transfers));
    }

    private void registerInputs() {
        Sensor barometer = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_PRESSURE);
        if (barometer != null) {
            sensors.registerListener(pressure, barometer, SensorManager.SENSOR_DELAY_NORMAL, 1_000_000, handler);
        }
        if (connectivity != null) connectivity.registerDefaultNetworkCallback(network, handler);
        if (locations != null
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            List<String> available = locations.getAllProviders();
            String[] wanted = lowPower
                    ? new String[] {LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}
                    : new String[] {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER,
                            LocationManager.PASSIVE_PROVIDER};
            // Registered even while switched off, so updates start as soon as Location is turned on.
            for (String provider : wanted) {
                if (!available.contains(provider)) continue;
                try {
                    locations.requestLocationUpdates(provider, 5_000L, 10f, location, worker.getLooper());
                } catch (SecurityException | IllegalArgumentException e) {
                    Log.w(TAG, "Location from " + provider + " unavailable", e);
                }
            }
        }
        inputs = true;
    }

    private void unregisterInputs() {
        if (sensors != null) sensors.unregisterListener(pressure);
        if (connectivity != null) {
            try {
                connectivity.unregisterNetworkCallback(network);
            } catch (IllegalArgumentException e) {
                Log.d(TAG, "Network callback was not registered");
            }
        }
        if (locations != null) locations.removeUpdates(location);
        inputs = false;
    }

    private void goForeground() {
        int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        }
        Notification note = Notes.status(this, engine.snapshot(), engine.transfers);
        try {
            startForeground(Notes.STATUS_ID, note, type);
        } catch (SecurityException e) {
            Log.w(TAG, "Location type refused; continuing without it", e);
            startForeground(Notes.STATUS_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        }
    }
}
