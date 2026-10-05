package io.github.c0ldsheep.sanket;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.Build;
import android.os.SystemClock;
import io.github.c0ldsheep.sanket.core.DeviceProfile;
import io.github.c0ldsheep.sanket.core.GuardPolicy;
import io.github.c0ldsheep.sanket.core.LayerWatch;
import io.github.c0ldsheep.sanket.core.LinkHealth;
import io.github.c0ldsheep.sanket.core.OfflineNotice;
import io.github.c0ldsheep.sanket.core.SafetyLog;
import io.github.c0ldsheep.sanket.core.SanketDetector;
import io.github.c0ldsheep.sanket.core.VerticalMotion;
import io.github.c0ldsheep.sanket.core.ZoneMemory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Everything SANKET knows, in one place for the app process: the detector and the signals around
 * it, the place memory, the safety log and the downloads. {@link GuardService} feeds it once a
 * second; the screen reads {@link #snapshot()}. Methods are synchronized; a tick costs a radio
 * query plus a few microseconds of arithmetic.
 */
final class Engine {
    /** Callbacks to the service, made on its worker thread. */
    interface Listener {
        void onLevelChanged(GuardPolicy.Level level, boolean downloadsActive);

        void onLongOutage(int minutes);
    }

    static final long LOG_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L;
    private static final String PREF_ALIVE = "service_alive";
    private static final String PREF_LAST_TICK = "service_last_tick";
    private static final double KNOWN_ZONE_ALERT_M = 60.0;
    private static final int LONG_OUTAGE_S = 600;
    private static final double EVIDENCE_WINDOW_S = 60.0;
    private static final double WATCH_RISK = 0.3;
    private static final double OTHER_SIM_MIN_RSRP = -105.0;
    private static final int DEFAULT_BACK_S = 180;

    // Holds only the application context, which lives as long as the process: no leak.
    @SuppressLint("StaticFieldLeak")
    private static Engine instance;

    static synchronized Engine get(Context context) {
        if (instance == null) instance = new Engine(context.getApplicationContext());
        return instance;
    }

    final Transfers transfers;
    private final Context app;
    private final Stores stores;
    private final RadioReader radio;
    private final ZoneMemory zones = new ZoneMemory();
    private final SafetyLog log = new SafetyLog();
    private final LinkHealth health = new LinkHealth();
    private final VerticalMotion vertical = new VerticalMotion();
    private DeviceProfile profile = new DeviceProfile();
    private LayerWatch layer = new LayerWatch();
    private GuardPolicy policy = new GuardPolicy();
    private SanketDetector detector;
    private DemoTrace demo;
    private Listener listener;
    private volatile Snapshot snapshot = Snapshot.idle();

    private boolean running;
    private Location fix;
    private boolean onWifi;
    private int badTicks;
    private int goodTicks;
    private boolean lost;
    private double lostSinceT;
    private ZoneMemory.Zone lossZone;
    private double lastDropT = Double.NEGATIVE_INFINITY;
    private double lastGoodRsrp = Double.NaN;
    private ZoneMemory.Zone visiting;
    private boolean lossDuringVisit;
    private String notice = "";
    private long savedAtMs;

    private Engine(Context app) {
        this.app = app;
        stores = new Stores(app);
        radio = new RadioReader(app);
        stores.loadZones(zones);
        stores.loadLog(log);
        stores.loadProfile(profile);
        long now = System.currentTimeMillis();
        zones.purge(now);
        log.dropBefore(now - LOG_RETENTION_MS);
        transfers = new Transfers(app, stores, this::onLatency);
        detector = new SanketDetector(params());
    }

    Snapshot snapshot() { return snapshot; }

    /** Notes that protection is running, so the screen can tell later if the phone stopped it. */
    void markAlive() {
        stores.prefs().edit().putBoolean(PREF_ALIVE, true).putLong(PREF_LAST_TICK, System.currentTimeMillis()).apply();
    }

    void markStopped() { stores.prefs().edit().putBoolean(PREF_ALIVE, false).apply(); }

    /** True once if protection was running but stopped without the user, usually by a battery saver. */
    boolean stoppedBySystem() {
        SharedPreferences p = stores.prefs();
        boolean stopped = p.getBoolean(PREF_ALIVE, false) && !snapshot.running
                && System.currentTimeMillis() - p.getLong(PREF_LAST_TICK, 0L) > 60_000L;
        if (stopped) markStopped();
        return stopped;
    }

    synchronized void start(boolean demoMode, Listener l) {
        listener = l;
        demo = demoMode ? DemoTrace.load(app) : null;
        detector = new SanketDetector(params());
        policy = new GuardPolicy();
        layer = new LayerWatch();
        badTicks = 0;
        goodTicks = 0;
        lost = false;
        lossZone = null;
        visiting = null;
        notice = "";
        lastDropT = Double.NEGATIVE_INFINITY;
        running = true;
    }

    synchronized void stop() {
        if (running && demo == null) save();
        running = false;
        demo = null;
        listener = null;
        snapshot = Snapshot.idle();
    }

    /** One step: once a second, or four times faster in the demo. Returns false when a demo ends. */
    synchronized boolean tick() {
        if (!running) return false;
        long now = System.currentTimeMillis();
        boolean demoMode = demo != null;
        double t;
        List<RadioReader.Sim> sims;
        if (demoMode) {
            DemoTrace.Row r = demo.next();
            if (r == null) return false;
            t = r.t;
            sims = Collections.singletonList(new RadioReader.Sim(0, true, "", app.getString(R.string.demo_sim),
                    r.rsrp, r.rsrq, Double.NaN, r.nbr, SanketDetector.UNKNOWN_CELL));
        } else {
            t = SystemClock.elapsedRealtime() / 1000.0;
            sims = radio.read();
        }
        RadioReader.Sim data = dataSim(sims);
        boolean alarm = false;
        double risk = 0.0;
        if (data != null) {
            if (!demoMode) profile.onSample(t, data.rsrp, data.rsrq);
            alarm = detector.step(t, data.rsrp, data.rsrq, data.neighbour, data.cell);
            risk = detector.risk();
            layer.update(t, data.rsrp, data.nr);
            if (SanketDetector.validRsrp(data.rsrp)) lastGoodRsrp = data.rsrp;
        }
        if (alarm || risk >= WATCH_RISK) lastDropT = t;
        boolean wifi = !demoMode && onWifi;
        boolean cellular = data != null && (SanketDetector.validRsrp(data.rsrp) || RadioReader.validNr(data.nr));
        String op = data == null ? "" : data.operator;
        trackLoss(t, now, cellular || wifi, op, data);
        Location f = demoMode ? null : recentFix(now, 30_000L);
        ZoneMemory.Zone near = null;
        if (f != null && !isMock(f)) {
            near = zones.nearestKnown(now, f.getLatitude(), f.getLongitude(), op, KNOWN_ZONE_ALERT_M);
            trackVisit(now, f, op);
        }
        GuardPolicy.Inputs in = new GuardPolicy.Inputs();
        in.connected = cellular || wifi;
        in.alarm = alarm && !wifi;
        in.risk = wifi ? 0.0 : risk;
        in.nrFading = !wifi && layer.warning(t);
        in.movement = demoMode ? VerticalMotion.Movement.LEVEL : vertical.movement(t);
        in.health = demoMode ? LinkHealth.State.OK : health.state(t);
        in.atKnownZone = near != null && !wifi;
        if (policy.update(t, in)) onLevelChanged(now, op, data, f, near);
        if (!demoMode && now - savedAtMs > 60_000L) save();
        publish(now, t, sims, data, risk, near, f, demoMode);
        return true;
    }

    synchronized void onPressure(double t, double hPa) { vertical.onPressure(t, hPa); }

    synchronized void onLocation(Location l) {
        if (l == null) return;
        if (fix == null || l.getTime() - fix.getTime() > 10_000L || l.getAccuracy() <= fix.getAccuracy()) fix = l;
    }

    synchronized void onNetwork(boolean connected, boolean validated, boolean wifi) {
        health.onNetwork(SystemClock.elapsedRealtime() / 1000.0, connected, validated);
        onWifi = connected && validated && wifi;
        transfers.onNetwork(connected && validated);
    }

    synchronized List<ZoneMemory.Zone> places() { return new ArrayList<>(zones.zones()); }

    synchronized String describe(ZoneMemory.Zone z) {
        String note = z.note().isEmpty() ? "" : "\n" + app.getString(R.string.place_note, z.note());
        int share = (int) Math.round(100.0 * z.confidence(System.currentTimeMillis()));
        return app.getString(R.string.place_row, kindText(z.kind()), z.operator, share, outageText(z.typicalOutageS()), note);
    }

    synchronized void setNote(ZoneMemory.Zone z, String note) {
        zones.setNote(z, note);
        save();
    }

    synchronized void forget(ZoneMemory.Zone z) {
        zones.remove(z);
        if (visiting == z) visiting = null;
        if (lossZone == z) lossZone = null;
        save();
    }

    synchronized String safetyLogText() { return log.export(); }

    /** Deletes places, notes, the log, the phone profile, unfinished downloads, the key and settings. */
    synchronized void wipe() {
        zones.clear();
        log.clear();
        visiting = null;
        lossZone = null;
        profile = new DeviceProfile();
        transfers.clearAll();
        stores.wipe();
    }

    private void onLatency(double millis) {
        synchronized (this) {
            health.onLatency(SystemClock.elapsedRealtime() / 1000.0, millis);
        }
    }

    private SanketDetector.Params params() {
        SanketDetector.Params p = SanketDetector.Params.tuned();
        p.heartbeatS = profile.heartbeatS();
        return p;
    }

    private void trackLoss(double t, long now, boolean connected, String op, RadioReader.Sim data) {
        if (connected) {
            goodTicks++;
            badTicks = 0;
        } else {
            badTicks++;
            goodTicks = 0;
        }
        if (!lost && badTicks >= 2) {
            lost = true;
            lostSinceT = t;
            if (demo != null) return;
            Location f = recentFix(now, 120_000L);
            ZoneMemory.Kind kind = vertical.classify(t);
            boolean evidence = t - lastDropT <= EVIDENCE_WINDOW_S
                    || kind == ZoneMemory.Kind.LIFT || kind == ZoneMemory.Kind.BASEMENT;
            String detail = kindText(kind);
            if (f != null) {
                ZoneMemory.Result r = zones.recordLoss(now, f.getLatitude(), f.getLongitude(), f.getAccuracy(), isMock(f),
                        op, kind, evidence);
                lossZone = zones.nearest(f.getLatitude(), f.getLongitude(), op, ZoneMemory.RADIUS_M);
                detail += ", place " + r.name().toLowerCase(Locale.ROOT).replace('_', ' ');
            } else {
                lossZone = null;
                detail += ", no recent position";
            }
            if (visiting != null) lossDuringVisit = true;
            log.append(now, "LOSS", latitude(f), longitude(f), op, lastGoodRsrp, detail);
            save();
        } else if (lost && goodTicks >= 2) {
            lost = false;
            if (demo != null) return;
            int seconds = (int) Math.max(1L, Math.round(t - lostSinceT));
            int typical = lossZone == null ? -1 : lossZone.typicalOutageS();
            zones.recordOutage(lossZone, seconds);
            Location f = recentFix(now, 120_000L);
            double lat = latitude(f);
            double lon = longitude(f);
            log.append(now, "RECOVER", lat, lon, op, data == null ? Double.NaN : data.rsrp, "back after " + seconds + " s");
            if (seconds >= LONG_OUTAGE_S && (typical < 0 || seconds > 3 * typical)) {
                log.append(now, "LONG_OUTAGE", lat, lon, op, Double.NaN, "offline for " + seconds / 60 + " min");
                if (listener != null) listener.onLongOutage(seconds / 60);
            }
            lossZone = null;
            transfers.resumeWaiting();
            save();
        }
    }

    /** Counts a visit through a zone that kept signal: evidence the place may have been fixed. */
    private void trackVisit(long now, Location f, String op) {
        ZoneMemory.Zone inside = zones.nearest(f.getLatitude(), f.getLongitude(), op, ZoneMemory.RADIUS_M);
        if (inside == visiting) return;
        if (visiting != null && !lossDuringVisit && !lost) zones.recordPass(now, visiting);
        visiting = inside;
        lossDuringVisit = false;
    }

    private void onLevelChanged(long now, String op, RadioReader.Sim data, Location f, ZoneMemory.Zone near) {
        GuardPolicy.Level level = policy.level();
        if (level != GuardPolicy.Level.CLEAR) transfers.checkpointAll();
        if (level == GuardPolicy.Level.PROTECT) {
            String why = String.join(", ", policy.reasons());
            double lat = latitude(f);
            double lon = longitude(f);
            int back = near != null && near.typicalOutageS() > 0 ? near.typicalOutageS() : DEFAULT_BACK_S;
            notice = OfflineNotice.json(now, near == null ? null : near.id, lat, lon, op, back, why);
            if (demo == null) {
                log.append(now, "PROTECT", lat, lon, op, data == null ? Double.NaN : data.rsrp,
                        why + "; offline notice ready, back in about " + back + " s");
            }
        }
        if (listener != null) listener.onLevelChanged(level, transfers.busy());
    }

    private void publish(long now, double t, List<RadioReader.Sim> sims, RadioReader.Sim data, double risk,
                         ZoneMemory.Zone near, Location f, boolean demoMode) {
        Snapshot s = new Snapshot();
        s.running = true;
        s.demo = demoMode;
        s.demoSeconds = demoMode ? t : Double.NaN;
        s.level = policy.level();
        s.reasons = policy.reasons();
        s.risk = risk;
        s.horizonS = detector.horizon();
        s.timeToLossS = detector.timeToLoss();
        List<String> lines = new ArrayList<>();
        for (RadioReader.Sim sim : sims) lines.add(simLine(sim));
        if (!demoMode) {
            lines.add(healthLine(t));
            if (vertical.available()) lines.add(movementLine(t));
            lines.add(profile.samples() >= DeviceProfile.MIN_SAMPLES
                    ? app.getString(R.string.phone_profile, profile.refreshIntervalS(), profile.noiseDb())
                    : app.getString(R.string.phone_learning));
        }
        if (near != null) {
            String line = app.getString(R.string.place_known, kindText(near.kind()).toLowerCase(Locale.ROOT),
                    outageText(near.typicalOutageS()));
            if (!near.note().isEmpty()) line += " " + app.getString(R.string.place_note, near.note());
            lines.add(line);
        }
        String hint = otherSimHint(now, sims, data, f, risk, near);
        if (!hint.isEmpty()) lines.add(hint);
        if (s.level == GuardPolicy.Level.PROTECT && !notice.isEmpty()) lines.add(app.getString(R.string.notice_ready));
        s.details = Collections.unmodifiableList(lines);
        snapshot = s;
    }

    private String simLine(RadioReader.Sim s) {
        StringBuilder b = new StringBuilder(s.name.isEmpty() ? app.getString(R.string.sim_unknown) : s.name);
        if (s.data) b.append(app.getString(R.string.sim_data_suffix));
        b.append(": ");
        if (SanketDetector.validRsrp(s.rsrp)) {
            b.append(app.getString(R.string.sim_lte, Math.round(s.rsrp)));
            if (!Double.isNaN(s.rsrq)) b.append(app.getString(R.string.sim_quality, Math.round(s.rsrq)));
        } else {
            b.append(app.getString(R.string.sim_no_4g));
        }
        if (RadioReader.validNr(s.nr)) b.append(app.getString(R.string.sim_nr, Math.round(s.nr)));
        return b.toString();
    }

    private String healthLine(double t) {
        switch (health.state(t)) {
            case OK:
                double ms = health.latencyMs();
                return Double.isNaN(ms) ? app.getString(R.string.net_ok) : app.getString(R.string.net_ok_ms, Math.round(ms));
            case SLOW:
                return app.getString(R.string.net_slow);
            case NO_INTERNET:
                return app.getString(R.string.net_no_internet);
            default:
                return app.getString(R.string.net_none);
        }
    }

    private String movementLine(double t) {
        switch (vertical.movement(t)) {
            case UP_FAST:
                return app.getString(R.string.move_up, vertical.speed());
            case DOWN_FAST:
                return app.getString(R.string.move_down, -vertical.speed());
            default:
                return app.getString(R.string.move_level);
        }
    }

    /** Suggests the other SIM when it has clearly better signal where the data SIM is in trouble. */
    private String otherSimHint(long now, List<RadioReader.Sim> sims, RadioReader.Sim data, Location f, double risk,
                                ZoneMemory.Zone near) {
        if (sims.size() < 2 || data == null || (near == null && risk < WATCH_RISK)) return "";
        for (RadioReader.Sim other : sims) {
            if (other == data || !SanketDetector.validRsrp(other.rsrp) || other.rsrp < OTHER_SIM_MIN_RSRP) continue;
            boolean deadThereToo = f != null
                    && zones.nearestKnown(now, f.getLatitude(), f.getLongitude(), other.operator, KNOWN_ZONE_ALERT_M) != null;
            boolean clearlyBetter = !SanketDetector.validRsrp(data.rsrp) || other.rsrp >= data.rsrp + 6.0;
            if (!deadThereToo && clearlyBetter) {
                return app.getString(R.string.sim_hint, other.name.isEmpty() ? app.getString(R.string.sim_unknown) : other.name);
            }
        }
        return "";
    }

    private String kindText(ZoneMemory.Kind kind) {
        switch (kind) {
            case BASEMENT:
                return app.getString(R.string.kind_basement);
            case LIFT:
                return app.getString(R.string.kind_lift);
            case INDOOR:
                return app.getString(R.string.kind_indoor);
            default:
                return app.getString(R.string.kind_unknown);
        }
    }

    private String outageText(int seconds) {
        return seconds > 0 ? app.getString(R.string.outage_typical, seconds) : app.getString(R.string.outage_unknown);
    }

    private void save() {
        stores.saveZones(zones);
        stores.saveLog(log);
        stores.saveProfile(profile);
        savedAtMs = System.currentTimeMillis();
    }

    private Location recentFix(long now, long maxAgeMs) {
        return fix != null && now - fix.getTime() <= maxAgeMs ? fix : null;
    }

    /** Latitude of a fix, or NaN when there is none. */
    private static double latitude(Location f) { return f == null ? Double.NaN : f.getLatitude(); }

    private static double longitude(Location f) { return f == null ? Double.NaN : f.getLongitude(); }

    private static RadioReader.Sim dataSim(List<RadioReader.Sim> sims) {
        for (RadioReader.Sim s : sims) if (s.data) return s;
        return sims.isEmpty() ? null : sims.get(0);
    }

    @SuppressWarnings("deprecation")
    static boolean isMock(Location l) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? l.isMock() : l.isFromMockProvider();
    }
}
