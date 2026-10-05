package io.github.c0ldsheep.sanket.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Places where this phone has lost signal before, kept separately for each mobile operator.
 *
 * <p>A zone stores only a rounded position (about 11 m), the operator, the kind of place, a short
 * note and two decaying counts: visits that lost signal and visits that kept it. A zone becomes
 * known after losses on two separate visits. Both counts halve every 30 days, so a basement that
 * gets an indoor booster fades out by itself, and zones that fade below a small weight are
 * deleted. Reports from mock locations, from vague fixes, or without a recorded signal drop are
 * refused, so a fake-GPS app cannot plant zones.
 */
public final class ZoneMemory {
    /** What kind of place the signal was lost in. */
    public enum Kind { BASEMENT, LIFT, INDOOR, UNKNOWN }

    /** Outcome of {@link #recordLoss}. */
    public enum Result { ADDED, UPDATED, SAME_VISIT, REJECTED_MOCK, REJECTED_ACCURACY, REJECTED_NO_EVIDENCE }

    /** Reports closer than this to a zone's centre belong to it. */
    public static final double RADIUS_M = 40.0;
    static final double MAX_ACCURACY_M = 50.0;
    static final double HALF_LIFE_DAYS = 30.0;
    /** Losses closer together than this are one visit. */
    static final long SAME_VISIT_MS = 10L * 60L * 1000L;
    static final double FORGET_BELOW = 0.25;
    static final int NOTE_MAX = 200;
    static final int OUTAGES_KEPT = 10;
    private static final double DAY_MS = 86_400_000.0;
    private static final Pattern PHONE_NUMBER = Pattern.compile("\\+?\\d[\\d\\s-]{7,}\\d");

    /** One remembered place. */
    public static final class Zone {
        public final String id;
        public final String operator;
        public final long createdMs;
        double lat;
        double lon;
        Kind kind;
        double losses;
        double passes;
        long updatedMs;
        long lastLossMs;
        String note = "";
        final List<Integer> outagesS = new ArrayList<>();

        Zone(String id, String operator, double lat, double lon, Kind kind, long createdMs) {
            this.id = id;
            this.operator = operator;
            this.lat = lat;
            this.lon = lon;
            this.kind = kind;
            this.createdMs = createdMs;
            this.updatedMs = createdMs;
        }

        public double lat() { return lat; }
        public double lon() { return lon; }
        public Kind kind() { return kind; }
        public String note() { return note; }
        public double losses() { return losses; }
        public double passes() { return passes; }
        public long updatedMs() { return updatedMs; }
        public long lastLossMs() { return lastLossMs; }
        public List<Integer> outages() { return Collections.unmodifiableList(outagesS); }

        /** Share of visits that lost signal, after decay, smoothed so that one visit is not certainty. */
        public double confidence(long nowMs) {
            double d = decay(nowMs);
            return (losses * d + 1.0) / ((losses + passes) * d + 2.0);
        }

        /** Losses on two separate visits, and still more losses than passes. */
        public boolean known(long nowMs) { return losses * decay(nowMs) >= 1.5 && confidence(nowMs) >= 0.5; }

        /** Median outage in seconds, or -1 when no outage here has been timed yet. */
        public int typicalOutageS() {
            if (outagesS.isEmpty()) return -1;
            List<Integer> v = new ArrayList<>(outagesS);
            Collections.sort(v);
            return v.get(v.size() / 2);
        }

        double decay(long nowMs) {
            return Math.pow(0.5, Math.max(0L, nowMs - updatedMs) / (HALF_LIFE_DAYS * DAY_MS));
        }

        void age(long nowMs) {
            double d = decay(nowMs);
            losses *= d;
            passes *= d;
            updatedMs = Math.max(updatedMs, nowMs);
        }
    }

    private final List<Zone> zones = new ArrayList<>();

    /**
     * The phone lost signal here. {@code dropSeen}: the detector saw the signal fall before the
     * loss, which a planted report cannot fake.
     */
    public Result recordLoss(long nowMs, double lat, double lon, double accuracyM, boolean mock, String operator,
                             Kind kind, boolean dropSeen) {
        if (mock) return Result.REJECTED_MOCK;
        if (!(accuracyM > 0.0) || accuracyM > MAX_ACCURACY_M || !validPosition(lat, lon)) {
            return Result.REJECTED_ACCURACY;
        }
        if (!dropSeen) return Result.REJECTED_NO_EVIDENCE;
        double rLat = round(lat);
        double rLon = round(lon);
        Zone z = nearest(rLat, rLon, operator, RADIUS_M);
        if (z == null) {
            String id = String.format(Locale.ROOT, "%s@%.4f,%.4f", operator, rLat, rLon);
            z = new Zone(id, operator, rLat, rLon, kind, nowMs);
            z.losses = 1.0;
            z.lastLossMs = nowMs;
            zones.add(z);
            return Result.ADDED;
        }
        if (nowMs - z.lastLossMs < SAME_VISIT_MS) return Result.SAME_VISIT;
        z.age(nowMs);
        double w = 1.0 / (z.losses + 1.0);
        z.lat = round(z.lat + (rLat - z.lat) * w);
        z.lon = round(z.lon + (rLon - z.lon) * w);
        z.losses += 1.0;
        z.lastLossMs = nowMs;
        if (kind != Kind.UNKNOWN) z.kind = kind;
        return Result.UPDATED;
    }

    /** How long the signal stayed away after a loss in this zone. */
    public void recordOutage(Zone z, int seconds) {
        if (z == null || seconds <= 0) return;
        z.outagesS.add(seconds);
        while (z.outagesS.size() > OUTAGES_KEPT) z.outagesS.remove(0);
    }

    /** The phone went through this zone and kept its signal. */
    public void recordPass(long nowMs, Zone z) {
        if (z == null) return;
        z.age(nowMs);
        z.passes += 1.0;
    }

    /** Closest zone of this operator within {@code withinM} metres, or null. */
    public Zone nearest(double lat, double lon, String operator, double withinM) {
        return closest(0L, lat, lon, operator, withinM, false);
    }

    /** Closest known zone of this operator within {@code withinM} metres, or null. */
    public Zone nearestKnown(long nowMs, double lat, double lon, String operator, double withinM) {
        return closest(nowMs, lat, lon, operator, withinM, true);
    }

    private Zone closest(long nowMs, double lat, double lon, String operator, double withinM, boolean knownOnly) {
        Zone best = null;
        double bestD = withinM;
        for (Zone z : zones) {
            if (!Objects.equals(z.operator, operator) || (knownOnly && !z.known(nowMs))) continue;
            double d = distanceM(lat, lon, z.lat, z.lon);
            if (d <= bestD) {
                best = z;
                bestD = d;
            }
        }
        return best;
    }

    /** Saves a short note such as "use gate 3; lift B has no signal". Phone numbers are removed. */
    public void setNote(Zone z, String note) {
        if (z != null) z.note = cleanNote(note);
    }

    /** Deletes zones that have faded out or that most visits now pass with signal. Returns the count. */
    public int purge(long nowMs) {
        int removed = 0;
        for (Iterator<Zone> it = zones.iterator(); it.hasNext(); ) {
            Zone z = it.next();
            double d = z.decay(nowMs);
            boolean faded = (z.losses + z.passes) * d < FORGET_BELOW;
            boolean fixed = z.passes * d >= 3.0 && z.confidence(nowMs) < 0.2;
            if (faded || fixed) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    public boolean remove(Zone z) { return zones.remove(z); }

    public void clear() { zones.clear(); }

    public List<Zone> zones() { return Collections.unmodifiableList(zones); }

    /** Re-creates a zone from storage. */
    public Zone restore(String id, String operator, double lat, double lon, Kind kind, double losses, double passes,
                        long createdMs, long updatedMs, long lastLossMs, String note, List<Integer> outages) {
        Zone z = new Zone(id, operator, round(lat), round(lon), kind, createdMs);
        z.losses = losses;
        z.passes = passes;
        z.updatedMs = updatedMs;
        z.lastLossMs = lastLossMs;
        z.note = cleanNote(note);
        if (outages != null) {
            for (Integer s : outages) {
                if (s != null) recordOutage(z, s);
            }
        }
        zones.add(z);
        return z;
    }

    static String cleanNote(String s) {
        if (s == null) return "";
        String t = PHONE_NUMBER.matcher(s.trim()).replaceAll("[number removed]");
        return t.length() > NOTE_MAX ? t.substring(0, NOTE_MAX) : t;
    }

    static boolean validPosition(double lat, double lon) {
        return lat >= -90.0 && lat <= 90.0 && lon >= -180.0 && lon <= 180.0 && !(lat == 0.0 && lon == 0.0);
    }

    /** Rounds a coordinate to 4 decimals, about 11 m. NaN stays NaN. */
    public static double round(double degrees) {
        return Double.isNaN(degrees) ? degrees : Math.round(degrees * 1e4) / 1e4;
    }

    /** Great-circle distance in metres. */
    public static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = p2 - p1;
        double dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2.0 * 6_371_008.8 * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}
