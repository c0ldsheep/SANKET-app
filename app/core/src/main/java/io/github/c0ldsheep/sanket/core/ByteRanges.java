package io.github.c0ldsheep.sanket.core;

/**
 * Reads HTTP Content-Range headers ("bytes 100-999/1000", RFC 9110 section 14.4), so a download
 * resumes only when the server really sent the bytes that come next.
 */
public final class ByteRanges {
    private ByteRanges() {}

    /** First byte of the range, or -1 when the header is missing, malformed or "bytes &#42;/total". */
    public static long first(String header) {
        String spec = spec(header);
        if (spec == null) return -1L;
        int dash = spec.indexOf('-');
        int slash = spec.indexOf('/');
        if (dash <= 0 || slash < dash) return -1L;
        return parse(spec.substring(0, dash));
    }

    /** Full length of the resource, or -1 when it is unknown ("&#42;") or the header is malformed. */
    public static long total(String header) {
        String spec = spec(header);
        if (spec == null) return -1L;
        int slash = spec.indexOf('/');
        return slash < 0 ? -1L : parse(spec.substring(slash + 1));
    }

    private static String spec(String header) {
        if (header == null) return null;
        String h = header.trim();
        return h.regionMatches(true, 0, "bytes ", 0, 6) ? h.substring(6).trim() : null;
    }

    private static long parse(String s) {
        try {
            long v = Long.parseLong(s.trim());
            return v >= 0 ? v : -1L;
        } catch (NumberFormatException e) {
            return -1L;
        }
    }
}
