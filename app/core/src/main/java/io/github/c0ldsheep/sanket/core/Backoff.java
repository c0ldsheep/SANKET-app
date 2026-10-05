package io.github.c0ldsheep.sanket.core;

/**
 * When to try a download again after the server failed. Busy servers and broken connections
 * usually recover, so retries continue by themselves, spaced further apart each time and never
 * more than ten minutes apart.
 */
public final class Backoff {
    static final int[] DELAYS_S = {30, 60, 120, 300, 600};
    static final int MAX_S = 600;

    private Backoff() {}

    /** Seconds to wait before automatic try number {@code attempt} (counting from 0). */
    public static int delayS(int attempt) {
        return DELAYS_S[Math.max(0, Math.min(attempt, DELAYS_S.length - 1))];
    }

    /** True for answers that mean "try again later" rather than "this will never work". */
    public static boolean retryable(int httpCode) {
        return httpCode == 408 || httpCode == 425 || httpCode == 429 || httpCode == 500
                || httpCode == 502 || httpCode == 503 || httpCode == 504;
    }

    /** Seconds from a Retry-After header in its number form, kept between 1 and 600; -1 when absent or a date. */
    public static int retryAfterS(String header) {
        if (header == null) return -1;
        String v = header.trim();
        if (v.isEmpty() || v.length() > 9) return -1;
        for (int i = 0; i < v.length(); i++) {
            if (!Character.isDigit(v.charAt(i))) return -1;
        }
        return Math.max(1, Math.min(MAX_S, Integer.parseInt(v)));
    }
}
