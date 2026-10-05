package io.github.c0ldsheep.sanket;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
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
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import io.github.c0ldsheep.sanket.core.GuardPolicy;

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
    private static final String TAG = "SanketService";
    private static final String CHANNEL_STATUS = "status";
    private static final String CHANNEL_ALERTS = "alerts";
    private static final int NOTE_STATUS = 1;
    private static final int NOTE_ALERT = 2;
    private static final long IDLE_STOP_MS = 120_000L;
    private static final long ALIVE_EVERY_MS = 15_000L;

    private Engine engine;
    private HandlerThread worker;
    private Handler handler;
    private SensorManager sensors;
    private LocationManager locations;
    private ConnectivityManager connectivity;
    // The fields below are used only on the worker thread.
    private boolean active;
    private boolean demo;
    private boolean manual;
    private boolean inputs;
    private long idleSince = -1L;
    private long aliveWrittenAt;

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
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
        }

        @Override
        public void onLost(Network n) { engine.onNetwork(false, false, false); }
    };

    static void send(Context ctx, String action) {
        ctx.startForegroundService(new Intent(ctx, GuardService.class).setAction(action));
    }

    static void stop(Context ctx) { ctx.stopService(new Intent(ctx, GuardService.class)); }

    @Override
    public void onCreate() {
        super.onCreate();
        engine = Engine.get(this);
        sensors = getSystemService(SensorManager.class);
        locations = getSystemService(LocationManager.class);
        connectivity = getSystemService(ConnectivityManager.class);
        createChannels();
        worker = new HandlerThread("sanket-guard");
        worker.start();
        handler = new Handler(worker.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null || intent.getAction() == null ? ACTION_START : intent.getAction();
        goForeground();
        boolean wantDemo = ACTION_DEMO.equals(action);
        boolean wantManual = ACTION_START.equals(action);
        handler.post(() -> {
            if (wantManual) manual = true;
            if (!active || wantDemo != demo) {
                begin(wantDemo);
            } else if (!demo) {
                unregisterInputs();   // permissions may have changed since the last start
                registerInputs();
            }
        });
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
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
        super.onDestroy();
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        Log.i(TAG, "Foreground time limit reached; stopping");
        stopSelf();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onLevelChanged(GuardPolicy.Level level, boolean downloadsActive) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTE_STATUS, statusNotification(statusText(level)));
        if (level == GuardPolicy.Level.PROTECT && downloadsActive) {
            alert(getString(R.string.alert_drop_title), getString(R.string.alert_drop_text));
        }
    }

    @Override
    public void onLongOutage(int minutes) {
        alert(getResources().getQuantityString(R.plurals.alert_long_title, minutes, minutes), getString(R.string.alert_long_text));
    }

    private void begin(boolean demoMode) {
        handler.removeCallbacks(tick);
        if (inputs) unregisterInputs();
        demo = demoMode;
        active = true;
        idleSince = -1L;
        engine.start(demoMode, this);
        if (!demoMode) registerInputs();
        handler.post(tick);
    }

    private void onTick() {
        if (!active) return;
        if (!engine.tick()) {
            stopSelf();
            return;
        }
        if (!demo && !manual) {
            long now = SystemClock.elapsedRealtime();
            if (engine.transfers.busy()) {
                idleSince = -1L;
            } else if (idleSince < 0L) {
                idleSince = now;
            } else if (now - idleSince > IDLE_STOP_MS) {
                stopSelf();
                return;
            }
        }
        if (!demo && SystemClock.elapsedRealtime() - aliveWrittenAt > ALIVE_EVERY_MS) {
            aliveWrittenAt = SystemClock.elapsedRealtime();
            engine.markAlive();
        }
        handler.postDelayed(tick, demo ? 250L : 1000L);
    }

    private void registerInputs() {
        Sensor barometer = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_PRESSURE);
        if (barometer != null) {
            sensors.registerListener(pressure, barometer, SensorManager.SENSOR_DELAY_NORMAL, 1_000_000, handler);
        }
        if (connectivity != null) connectivity.registerDefaultNetworkCallback(network, handler);
        if (locations != null
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                for (String provider : new String[] {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                    if (locations.isProviderEnabled(provider)) {
                        locations.requestLocationUpdates(provider, 5_000L, 10f, location, worker.getLooper());
                    }
                }
            } catch (SecurityException | IllegalArgumentException e) {
                Log.w(TAG, "Location unavailable", e);
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
        Notification note = statusNotification(getString(R.string.status_clear));
        try {
            startForeground(NOTE_STATUS, note, type);
        } catch (SecurityException e) {
            Log.w(TAG, "Location type refused; continuing without it", e);
            startForeground(NOTE_STATUS, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        }
    }

    private void createChannels() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_STATUS, getString(R.string.channel_status),
                NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ALERTS, getString(R.string.channel_alerts),
                NotificationManager.IMPORTANCE_DEFAULT));
    }

    private Notification statusNotification(String text) {
        return new Notification.Builder(this, CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(openApp())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void alert(String title, String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.notify(NOTE_ALERT, new Notification.Builder(this, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build());
    }

    private PendingIntent openApp() {
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private String statusText(GuardPolicy.Level level) {
        switch (level) {
            case WATCH:
                return getString(R.string.status_watch);
            case PROTECT:
                return getString(R.string.status_protect);
            case OFFLINE:
                return getString(R.string.status_offline);
            default:
                return getString(R.string.status_clear);
        }
    }
}
