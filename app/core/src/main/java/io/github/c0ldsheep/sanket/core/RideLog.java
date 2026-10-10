package io.github.c0ldsheep.sanket.core;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * A test-ride recording for field tests: one CSV row per second with what the phone measured and what SANKET
 * decided. The columns are the ones the study's loader reads ({@code load_field_log} in research/sanket/realdata.py),
 * so a ride recorded in a real basement can be scored like the study's own data.
 *
 * <p>Seconds count from the start on the wall clock, so a recording saved before the app was closed carries on
 * where it stopped. It stops taking rows after {@link #MAX_ROWS} (four hours).
 */
public final class RideLog {
    public static final String HEADER =
            "t_s,time,tech,rsrp,rsrq,nbr_rsrp,nr_rsrp,cell,risk,alarm,connected,wifi,movement,marker";
    public static final int MAX_ROWS = 4 * 3600;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    private final ZoneId zone;
    private final StringBuilder rows = new StringBuilder();
    private long startMs = -1L;
    private int count;
    private boolean markNext;

    public RideLog(ZoneId zone) { this.zone = zone; }

    public boolean started() { return startMs >= 0L; }

    public long startMs() { return startMs; }

    public int rows() { return count; }

    public boolean full() { return count >= MAX_ROWS; }

    /** Whole seconds since the recording started (0 when it hasn't). */
    public long seconds(long nowMs) { return started() ? Math.max(0L, (nowMs - startMs) / 1000L) : 0L; }

    public void start(long nowMs) {
        rows.setLength(0);
        count = 0;
        markNext = false;
        startMs = nowMs;
    }

    /** Marks the next row, for example "at the ramp now" or "lift doors closing". */
    public void mark() { markNext = true; }

    /**
     * Adds this second. {@code tech} is "lte", "nr", "other" (2G or 3G) or "none" (no mobile service); signal values
     * the phone did not report are NaN and stay empty.
     */
    public void add(long nowMs, String tech, double rsrp, double rsrq, double nbrRsrp, double nrRsrp, long cell,
                    double risk, boolean alarm, boolean connected, boolean wifi, String movement) {
        if (!started() || full()) return;
        rows.append(Math.round((nowMs - startMs) / 1000.0)).append(',')
                .append(CLOCK.format(Instant.ofEpochMilli(nowMs).atZone(zone))).append(',')
                .append(tech).append(',')
                .append(num(rsrp)).append(',')
                .append(num(rsrq)).append(',')
                .append(num(nbrRsrp)).append(',')
                .append(num(nrRsrp)).append(',')
                .append(cell == SanketDetector.UNKNOWN_CELL ? "" : Long.toString(cell)).append(',')
                .append(Double.isFinite(risk) ? String.format(Locale.ROOT, "%.3f", risk) : "").append(',')
                .append(alarm ? '1' : '0').append(',')
                .append(connected ? '1' : '0').append(',')
                .append(wifi ? '1' : '0').append(',')
                .append(movement).append(',')
                .append(markNext ? "mark" : "").append('\n');
        markNext = false;
        count++;
    }

    /** The whole recording, header first. */
    public String csv() { return HEADER + "\n" + rows; }

    /** Carries on with a recording saved earlier ({@link #csv()} text), started at {@code startMs}. */
    public void restore(long startMs, String csv) {
        start(startMs);
        int body = csv.startsWith(HEADER + "\n") ? HEADER.length() + 1 : 0;
        String text = csv.substring(body);
        if (!text.isEmpty() && !text.endsWith("\n")) text = text.substring(0, text.lastIndexOf('\n') + 1);
        rows.append(text);
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') count++;
    }

    public void clear() {
        rows.setLength(0);
        count = 0;
        markNext = false;
        startMs = -1L;
    }

    private static String num(double v) {
        if (!Double.isFinite(v)) return "";
        if (v == Math.rint(v)) return Long.toString((long) v);
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
