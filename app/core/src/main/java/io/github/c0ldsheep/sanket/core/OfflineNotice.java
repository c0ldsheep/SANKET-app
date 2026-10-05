package io.github.c0ldsheep.sanket.core;

import java.time.Instant;
import java.util.Locale;

/**
 * The short message a phone sends just before it goes quiet in a known dead zone, so a dispatch
 * system does not mark the rider unresponsive or reassign the order while they are in a lift or
 * basement. Sending it needs the fleet server, which is planned; the app already builds the
 * message and records it in the safety log.
 */
public final class OfflineNotice {
    private OfflineNotice() {}

    /** JSON for one notice. {@code zoneId} may be null; NaN coordinates are written as null. */
    public static String json(long timeMs, String zoneId, double lat, double lon, String operator, int expectedBackS,
                              String reason) {
        StringBuilder sb = new StringBuilder(200).append('{');
        sb.append("\"type\":\"going_offline\"");
        sb.append(",\"time\":");
        quote(sb, Instant.ofEpochMilli(timeMs).toString());
        sb.append(",\"zone\":");
        if (zoneId == null) sb.append("null");
        else quote(sb, zoneId);
        sb.append(",\"lat\":").append(number(ZoneMemory.round(lat)));
        sb.append(",\"lon\":").append(number(ZoneMemory.round(lon)));
        sb.append(",\"operator\":");
        quote(sb, operator == null ? "" : operator);
        sb.append(",\"expected_back_s\":").append(Math.max(0, expectedBackS));
        sb.append(",\"reason\":");
        quote(sb, reason == null ? "" : reason);
        return sb.append('}').toString();
    }

    private static String number(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? "null" : String.format(Locale.ROOT, "%.4f", v);
    }

    static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }
}
