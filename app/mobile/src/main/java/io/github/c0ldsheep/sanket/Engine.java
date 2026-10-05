package io.github.c0ldsheep.sanket;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import io.github.c0ldsheep.sanket.core.DeviceProfile;
import io.github.c0ldsheep.sanket.core.GuardPolicy;
import io.github.c0ldsheep.sanket.core.LayerWatch;
import io.github.c0ldsheep.sanket.core.LinkHealth;
import io.github.c0ldsheep.sanket.core.OfflineNotice;
import io.github.c0ldsheep.sanket.core.OutageTracker;
import io.github.c0ldsheep.sanket.core.SafetyLog;
import io.github.c0ldsheep.sanket.core.SanketDetector;
import io.github.c0ldsheep.sanket.core.VerticalMotion;
import io.github.c0ldsheep.sanket.core.ZoneMemory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Everything SANKET knows, in one place for the app process: the detector and the signals around
 * it, the place memory, the safety log and the downloads. {@link GuardService} feeds it once a
 * second; the screen reads {@link #snapshot()}. Methods are synchronized; a tick costs a radio
 * query plus a few microseconds of arithmetic.
 */
final class Engine implements Transfers.Listener {
    /** Callbacks to the service, made on its worker thread. */
    interface Listener {
        void onLevelChanged(GuardPolicy.Level level, boolean downloadsActive);

        void onLongOutage(int minutes);
    }

    /** A loss waiting to be logged once it has lasted long enough to matter. */
    private static final class PendingLoss {
        final long timeMs;
        final double lat;
        final double lon;
        final String operator;
        final double signal;
        final String detail;

        PendingLoss(long timeMs, double lat, double lon, String operator, double signal, String detail) {
            this.timeMs = timeMs;
            this.lat = lat;
            this.lon = lon;
            this.operator = operator;
            this.signal = signal;
            this.detail = detail;
        }
    }

    static final long LOG_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L;
    private static final String PREF_ALIVE = "service_alive";
    private static final String PREF_LAST_TICK = "service_last_tick";
    private static final String PREF_BOOT = "service_boot";
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
    private final OutageTracker outages = new OutageTracker();
    private final Map<String, String> carrierNames;
    private DeviceProfile profile = new DeviceProfile();
    private LayerWatch layer = new LayerWatch();
    private GuardPolicy policy = new GuardPolicy();
    private SanketDetector detector;
    private DemoTrace demo;
    private Listener listener;
    private volatile Snapshot snapshot = Snapshot.idle();

    private boolean running;
    private boolean protecting;
    private boolean lowPower;
    private Location fix;
    private boolean onWifi;
    private int detectorSub = Integer.MIN_VALUE;
    private Snapshot.Tech detectorTech = Snapshot.Tech.NONE;
    private PendingLoss pendingLoss;
    private ZoneMemory.Zone lossZone;
    private double lastDropT = Double.NEGATIVE_INFINITY;
    private double lastGoodSignal = Double.NaN;
    private ZoneMemory.Zone visiting;
    private boolean lossDuringVisit;
    private int noticeBackS;
    private String lastNotice = "";
    private long savedAtMs;

    private Engine(Context app) {
        this.app = app;
        stores = new Stores(app);
        radio = new RadioReader(app);
        stores.loadZones(zones);
        stores.loadLog(log);
        stores.loadProfile(profile);
        carrierNames = stores.loadCarrierNames();
        long now = System.currentTimeMillis();
        zones.purge(now);
        log.dropBefore(now - LOG_RETENTION_MS);
        transfers = new Transfers(app, stores, this);
        detector = new SanketDetector(params());
    }

    Snapshot snapshot() { return snapshot; }

    /** Notes that the service is running, so the screen can tell later if the phone stopped it. */
    void markAlive() {
        stores.prefs().edit().putBoolean(PREF_ALIVE, true).putLong(PREF_LAST_TICK, System.currentTimeMillis())
                .putInt(PREF_BOOT, bootCount()).apply();
    }

    void markStopped() { stores.prefs().edit().putBoolean(PREF_ALIVE, false).apply(); }

    /** True once if the service was running but stopped without the user, usually by a battery saver. */
    boolean stoppedBySystem() {
        SharedPreferences p = stores.prefs();
        if (!p.getBoolean(PREF_ALIVE, false) || snapshot.running) return false;
        if (System.currentTimeMillis() - p.getLong(PREF_LAST_TICK, 0L) <= 60_000L) return false;
        markStopped();
        // A restart stops the service too, and that is not the battery saver's doing.
        return p.getInt(PREF_BOOT, -1) == bootCount();
    }

    private int bootCount() {
        return Settings.Global.getInt(app.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
    }

    synchronized void start(boolean demoMode, boolean userProtection, Listener l) {
        listener = l;
        protecting = userProtection;
        demo = demoMode ? DemoTrace.load(app) : null;
        // The demo uses the study's exact settings, so it reproduces the study's timeline.
        detector = new SanketDetector(demoMode ? SanketDetector.Params.tuned() : params());
        detectorSub = Integer.MIN_VALUE;
        detectorTech = Snapshot.Tech.NONE;
        policy = new GuardPolicy();
        layer = new LayerWatch();
        outages.reset();
        pendingLoss = null;
        lossZone = null;
        visiting = null;
        noticeBackS = 0;
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

    synchronized void setProtecting(boolean on) { protecting = on; }

    synchronized void setLowPower(boolean on) { lowPower = on; }

    /** One step: once a second, or four times faster in the demo. Returns false when a demo ends. */
    synchronized boolean tick() {
        if (!running) return false;
        long now = System.currentTimeMillis();
        boolean demoMode = demo != null;
        double t;
        List<RadioReader.Sim> sims;
        RadioReader.Status status;
        if (demoMode) {
            DemoTrace.Row r = demo.next();
            if (r == null) return false;
            t = r.t;
            sims = Collections.singletonList(new RadioReader.Sim(0, 0, true, "", app.getString(R.string.demo_sim),
                    r.rsrp, r.rsrq, Double.NaN, r.nbr, SanketDetector.UNKNOWN_CELL, Double.NaN,
                    SanketDetector.UNKNOWN_CELL, Double.NaN, ""));
            status = RadioReader.Status.NORMAL;
        } else {
            t = SystemClock.elapsedRealtime() / 1000.0;
            sims = radio.read();
            status = radio.status();
            rememberCarrierNames(sims);
        }
        RadioReader.Sim data = dataSim(sims);

        // 4G feeds the detector when the phone reports it; standalone 5G (Jio True 5G) when it does not.
        Snapshot.Tech tech = Snapshot.Tech.NONE;
        double signal = Double.NaN;
        double quality = Double.NaN;
        double neighbour = Double.NaN;
        long cell = SanketDetector.UNKNOWN_CELL;
        if (data != null) {
            if (SanketDetector.validRsrp(data.rsrp)) {
                tech = Snapshot.Tech.LTE;
                signal = data.rsrp;
                quality = data.rsrq;
                neighbour = data.neighbour;
                cell = data.cell;
            } else if (RadioReader.validNr(data.nr)) {
                tech = Snapshot.Tech.NR;
                signal = data.nr;
                neighbour = data.nrNeighbour;
                cell = data.nrCell;
            } else if (!Double.isNaN(data.legacy)) {
                tech = Snapshot.Tech.OTHER;   // 2G or 3G: still connected, but nothing to predict from
            }
            // A different SIM or radio means a different scale: start the trend afresh.
            boolean newSim = data.subId != detectorSub;
            boolean newTech = tech != Snapshot.Tech.NONE && detectorTech != Snapshot.Tech.NONE && tech != detectorTech;
            if (!demoMode && (newSim || newTech)) {
                if (detectorSub != Integer.MIN_VALUE || newTech) {
                    detector = new SanketDetector(params());
                    layer = new LayerWatch();
                }
                detectorSub = data.subId;
            }
            if (tech != Snapshot.Tech.NONE) detectorTech = tech;
        }

        boolean alarm = false;
        double risk = 0.0;
        if (data != null) {
            if (!demoMode) profile.onSample(t, signal, quality);
            alarm = detector.step(t, signal, quality, neighbour, cell);
            risk = detector.risk();
            layer.update(t, data.rsrp, data.nr);
            if (SanketDetector.validRsrp(signal)) lastGoodSignal = signal;
        }
        if (alarm || risk >= WATCH_RISK) lastDropT = t;
        boolean wifi = !demoMode && onWifi;
        boolean cellular = tech != Snapshot.Tech.NONE;
        String op = data == null ? "" : data.operator;

        Snapshot.Offline offline = Snapshot.Offline.NONE;
        if (status.airplane) offline = Snapshot.Offline.AIRPLANE;
        else if (!status.simPresent) offline = Snapshot.Offline.NO_SIM;
        if (offline == Snapshot.Offline.NONE) {
            trackLoss(t, now, cellular || wifi, op);
        } else {
            // Switching on airplane mode, or having no SIM, is a choice and not a signal loss.
            outages.reset();
            pendingLoss = null;
            lossZone = null;
        }
        transfers.setOffline(offline == Snapshot.Offline.AIRPLANE ? Transfers.Offline.AIRPLANE
                : !wifi && !status.dataEnabled ? Transfers.Offline.DATA_OFF : Transfers.Offline.NONE);

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
        if (policy.update(t, in)) onLevelChanged(now, op, signal, f, near);
        if (!demoMode && now - savedAtMs > 60_000L) save();
        publish(now, t, sims, data, tech, offline, risk, near, f, demoMode);
        return true;
    }

    synchronized void onPressure(double t, double hPa) { vertical.onPressure(t, hPa); }

    synchronized void onLocation(Location l) {
        if (l == null) return;
        if (fix == null || l.getTime() - fix.getTime() > 10_000L || l.getAccuracy() <= fix.getAccuracy()) fix = l;
    }

    synchronized void onNetwork(boolean connected, boolean validated, boolean wifi, boolean unmetered) {
        health.onNetwork(SystemClock.elapsedRealtime() / 1000.0, connected, validated);
        onWifi = connected && validated && wifi;
        transfers.onNetwork(connected && validated, unmetered);
    }

    synchronized List<ZoneMemory.Zone> places() { return new ArrayList<>(zones.zones()); }

    synchronized String describe(ZoneMemory.Zone z) {
        String note = z.note().isEmpty() ? "" : "\n" + app.getString(R.string.place_note, z.note());
        int share = (int) Math.round(100.0 * z.confidence(System.currentTimeMillis()));
        return app.getString(R.string.place_row, kindText(z.kind()), networkName(z.operator), share,
                outageText(z.typicalOutageS()), note);
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

    synchronized boolean logEmpty() { return log.entries().isEmpty(); }

    synchronized String safetyLogText() { return log.export(); }

    /** Deletes places, notes, the log, the phone profile, unfinished downloads, the key and settings. */
    synchronized void wipe() {
        zones.clear();
        log.clear();
        visiting = null;
        lossZone = null;
        pendingLoss = null;
        profile = new DeviceProfile();
        carrierNames.clear();
        transfers.clearAll();
        stores.wipe();
    }

    @Override
    public void onLatency(double millis) {
        synchronized (this) {
            health.onLatency(SystemClock.elapsedRealtime() / 1000.0, millis);
        }
    }

    @Override
    public void onFinished(Transfers.Item it) { Notes.downloadDone(app, it); }

    @Override
    public void onChanged() { ResumeJob.sync(app, transfers); }

    private SanketDetector.Params params() {
        SanketDetector.Params p = SanketDetector.Params.tuned();
        p.heartbeatS = profile.heartbeatS();
        return p;
    }

    private void trackLoss(double t, long now, boolean connected, String op) {
        switch (outages.tick(t, connected)) {
            case STARTED: {
                if (demo != null) return;
                Location f = recentFix(now, 120_000L);
                ZoneMemory.Kind kind = vertical.classify(t);
                boolean evidence = t - lastDropT <= EVIDENCE_WINDOW_S
                        || kind == ZoneMemory.Kind.LIFT || kind == ZoneMemory.Kind.BASEMENT;
                String detail = kindText(kind);
                if (f != null) {
                    ZoneMemory.Result r = zones.recordLoss(now, f.getLatitude(), f.getLongitude(), f.getAccuracy(),
                            isMock(f), op, kind, evidence);
                    lossZone = zones.nearest(f.getLatitude(), f.getLongitude(), op, ZoneMemory.RADIUS_M);
                    detail += ", place " + r.name().toLowerCase(Locale.ROOT).replace('_', ' ');
                } else {
                    lossZone = null;
                    detail += ", no recent position";
                }
                if (visiting != null) lossDuringVisit = true;
                pendingLoss = new PendingLoss(now, latitude(f), longitude(f), op, lastGoodSignal, detail);
                save();
                break;
            }
            case CONFIRMED:
                if (demo != null || pendingLoss == null) return;
                PendingLoss p = pendingLoss;
                pendingLoss = null;
                log.append(p.timeMs, "LOSS", p.lat, p.lon, p.operator, p.signal, p.detail);
                save();
                break;
            case ENDED: {
                if (demo != null) return;
                int seconds = (int) Math.max(1L, Math.round(t - outages.since()));
                int typical = lossZone == null ? -1 : lossZone.typicalOutageS();
                zones.recordOutage(lossZone, seconds);
                if (outages.confirmed()) {
                    Location f = recentFix(now, 120_000L);
                    double lat = latitude(f);
                    double lon = longitude(f);
                    log.append(now, "RECOVER", lat, lon, op, lastGoodSignal, "back after " + seconds + " s");
                    if (seconds >= LONG_OUTAGE_S && (typical < 0 || seconds > 3 * typical)) {
                        log.append(now, "LONG_OUTAGE", lat, lon, op, Double.NaN, "offline for " + seconds / 60 + " min");
                        if (listener != null) listener.onLongOutage(seconds / 60);
                    }
                }
                pendingLoss = null;
                lossZone = null;
                transfers.resumeWaiting();
                save();
                break;
            }
            default:
                break;
        }
    }

    /** Counts a visit through a zone that kept signal: evidence the place may have been fixed. */
    private void trackVisit(long now, Location f, String op) {
        ZoneMemory.Zone inside = zones.nearest(f.getLatitude(), f.getLongitude(), op, ZoneMemory.RADIUS_M);
        if (inside == visiting) return;
        if (visiting != null && !lossDuringVisit && !outages.lost()) zones.recordPass(now, visiting);
        visiting = inside;
        lossDuringVisit = false;
    }

    private void onLevelChanged(long now, String op, double signal, Location f, ZoneMemory.Zone near) {
        GuardPolicy.Level level = policy.level();
        if (level != GuardPolicy.Level.CLEAR) transfers.checkpointAll();
        if (level == GuardPolicy.Level.PROTECT) {
            String why = String.join(", ", policy.reasons());
            double lat = latitude(f);
            double lon = longitude(f);
            noticeBackS = near != null && near.typicalOutageS() > 0 ? near.typicalOutageS() : DEFAULT_BACK_S;
            // Prepared for the fleet server, which will send it to dispatch.
            lastNotice = OfflineNotice.json(now, near == null ? null : near.id, lat, lon, op, noticeBackS, why);
            if (demo == null) {
                log.append(now, "PROTECT", lat, lon, op, signal,
                        why + "; offline notice ready, back in about " + noticeBackS + " s");
            }
        } else {
            noticeBackS = 0;
        }
        if (listener != null) listener.onLevelChanged(level, transfers.busy());
    }

    private void publish(long now, double t, List<RadioReader.Sim> sims, RadioReader.Sim data, Snapshot.Tech tech,
                         Snapshot.Offline offline, double risk, ZoneMemory.Zone near, Location f, boolean demoMode) {
        Snapshot s = new Snapshot();
        s.running = true;
        s.protecting = protecting && !demoMode;
        s.demo = demoMode;
        s.demoSeconds = demoMode ? t : Double.NaN;
        s.level = policy.level();
        s.reasons = policy.reasons();
        s.risk = risk;
        s.horizonS = detector.horizon();
        s.timeToLossS = detector.timeToLoss();
        s.offline = offline;
        s.tech = tech;
        s.onWifi = !demoMode && onWifi;
        s.lowPower = lowPower && !demoMode;
        List<Snapshot.Sim> list = new ArrayList<>();
        for (RadioReader.Sim sim : sims) {
            list.add(new Snapshot.Sim(simName(sim), sim.data, sim.rsrp, sim.nr, sim.legacy, sim.legacyTech));
        }
        s.sims = Collections.unmodifiableList(list);
        if (!demoMode) {
            s.health = health.state(t);
            s.latencyMs = health.latencyMs();
            s.barometer = vertical.available();
            s.movement = vertical.movement(t);
            s.verticalSpeed = vertical.speed();
            s.refreshS = profile.samples() >= DeviceProfile.MIN_SAMPLES ? profile.refreshIntervalS() : Double.NaN;
        }
        if (near != null) {
            s.place = app.getString(R.string.place_known_sub, kindText(near.kind()), outageText(near.typicalOutageS()));
            s.placeNote = near.note();
        }
        s.betterSim = betterSim(now, sims, data, f, risk, near);
        s.noticeBackMin = s.level == GuardPolicy.Level.PROTECT && noticeBackS > 0
                ? (int) Math.max(1L, Math.round(noticeBackS / 60.0)) : 0;
        snapshot = s;
    }

    /** The other SIM's name when it has clearly better signal where the data SIM is in trouble. */
    private String betterSim(long now, List<RadioReader.Sim> sims, RadioReader.Sim data, Location f, double risk,
                             ZoneMemory.Zone near) {
        if (sims.size() < 2 || data == null || (near == null && risk < WATCH_RISK)) return "";
        for (RadioReader.Sim other : sims) {
            if (other == data || !SanketDetector.validRsrp(other.rsrp) || other.rsrp < OTHER_SIM_MIN_RSRP) continue;
            boolean deadThereToo = f != null
                    && zones.nearestKnown(now, f.getLatitude(), f.getLongitude(), other.operator, KNOWN_ZONE_ALERT_M) != null;
            boolean clearlyBetter = !SanketDetector.validRsrp(data.rsrp) || other.rsrp >= data.rsrp + 6.0;
            if (!deadThereToo && clearlyBetter) return simName(other);
        }
        return "";
    }

    private void rememberCarrierNames(List<RadioReader.Sim> sims) {
        boolean changed = false;
        for (RadioReader.Sim sim : sims) {
            if (sim.operator.isEmpty() || sim.name.isEmpty()) continue;
            if (!sim.name.equals(carrierNames.get(sim.operator))) {
                carrierNames.put(sim.operator, sim.name);
                changed = true;
            }
        }
        if (changed) stores.saveCarrierNames(carrierNames);
    }

    private String simName(RadioReader.Sim sim) {
        return sim.name.isEmpty() ? app.getString(R.string.sim_unknown) : sim.name;
    }

    private String networkName(String code) {
        String name = carrierNames.get(code);
        return name != null && !name.isEmpty() ? name : app.getString(R.string.network_code, code);
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
