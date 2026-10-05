package io.github.c0ldsheep.sanket.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A tamper-evident record of signal losses and recoveries, for the user's own safety or for an
 * investigation if something happens underground.
 *
 * <p>Each entry carries the SHA-256 hash of the entry before it, so editing or deleting a line in
 * the middle breaks the chain and {@link #verify()} reports it. Positions are rounded like the
 * zone memory, and old entries are dropped from the start when they pass the retention period.
 */
public final class SafetyLog {
    static final String GENESIS = "0000000000000000000000000000000000000000000000000000000000000000";

    /** One logged event. */
    public static final class Entry {
        public final long timeMs;
        public final String event;
        public final double lat;
        public final double lon;
        public final String operator;
        public final double rsrp;
        public final String detail;
        public final String prevHash;
        public final String hash;

        Entry(long timeMs, String event, double lat, double lon, String operator, double rsrp, String detail,
              String prevHash, String hash) {
            this.timeMs = timeMs;
            this.event = event;
            this.lat = lat;
            this.lon = lon;
            this.operator = operator;
            this.rsrp = rsrp;
            this.detail = detail;
            this.prevHash = prevHash;
            this.hash = hash;
        }

        String body() {
            return String.format(Locale.ROOT, "%d|%s|%.4f|%.4f|%s|%.0f|%s", timeMs, event, lat, lon, operator, rsrp, detail);
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    public Entry append(long timeMs, String event, double lat, double lon, String operator, double rsrp, String detail) {
        String prev = entries.isEmpty() ? GENESIS : entries.get(entries.size() - 1).hash;
        Entry draft = new Entry(timeMs, clean(event), ZoneMemory.round(lat), ZoneMemory.round(lon), clean(operator),
                rsrp, clean(detail), prev, "");
        Entry e = new Entry(draft.timeMs, draft.event, draft.lat, draft.lon, draft.operator, draft.rsrp, draft.detail,
                prev, sha256(prev + "\n" + draft.body()));
        entries.add(e);
        return e;
    }

    /** True when no entry was changed or removed from the middle. */
    public boolean verify() {
        if (entries.isEmpty()) return true;
        String prev = entries.get(0).prevHash;
        for (Entry e : entries) {
            if (!e.prevHash.equals(prev) || !e.hash.equals(sha256(e.prevHash + "\n" + e.body()))) return false;
            prev = e.hash;
        }
        return true;
    }

    /** Drops entries older than {@code cutoffMs} from the start; the rest still verifies. */
    public int dropBefore(long cutoffMs) {
        int n = 0;
        while (!entries.isEmpty() && entries.get(0).timeMs < cutoffMs) {
            entries.remove(0);
            n++;
        }
        return n;
    }

    public List<Entry> entries() { return Collections.unmodifiableList(entries); }

    public void clear() { entries.clear(); }

    /** Re-adds a stored entry exactly as saved, so {@link #verify()} checks what was on disk. */
    public void restore(long timeMs, String event, double lat, double lon, String operator, double rsrp, String detail,
                        String prevHash, String hash) {
        entries.add(new Entry(timeMs, event, lat, lon, operator, rsrp, detail, prevHash, hash));
    }

    /** Plain-text export, oldest first, times in UTC. */
    public String export() {
        StringBuilder sb = new StringBuilder();
        sb.append("SANKET safety log (times in UTC). Chain check: ").append(verify() ? "intact" : "BROKEN").append('\n');
        for (Entry e : entries) {
            sb.append(Instant.ofEpochMilli(e.timeMs)).append("  ").append(e.event).append("  ")
                    .append(Double.isNaN(e.lat) ? "no position" : String.format(Locale.ROOT, "%.4f,%.4f", e.lat, e.lon))
                    .append("  ").append(e.operator.isEmpty() ? "-" : e.operator).append("  ")
                    .append(Double.isNaN(e.rsrp) ? "no signal" : String.format(Locale.ROOT, "%.0f dBm", e.rsrp));
            if (!e.detail.isEmpty()) sb.append("  ").append(e.detail);
            sb.append("  #").append(e.hash, 0, 12).append('\n');
        }
        return sb.toString();
    }

    static String clean(String s) {
        return s == null ? "" : s.replace('|', '/').replace('\n', ' ').replace('\r', ' ');
    }

    static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is available on every Java platform", e);
        }
    }
}
