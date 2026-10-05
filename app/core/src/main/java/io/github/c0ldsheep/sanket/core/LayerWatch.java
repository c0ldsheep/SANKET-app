package io.github.c0ldsheep.sanket.core;

/**
 * Early warning from the 5G layer.
 *
 * <p>Mid-band 5G (3.5 GHz) gets through concrete worse than 4G, so near a building entrance it
 * usually fades first. When a 5G reading that was already weak disappears, or the 5G level falls
 * fast, while 4G still looks fine, a 4G drop is likely to follow. This is a hypothesis that field
 * recordings must confirm, so it only raises the cheap WATCH level (save progress), never the
 * full alarm. A strong 5G reading that vanishes is ignored, because phones also release 5G when
 * traffic stops.
 */
public final class LayerWatch {
    static final double NR_MIN = -140.0;
    static final double NR_MAX = -44.0;
    /** A fall of this many dB within {@link #FALL_WINDOW_S} counts as fading. */
    static final double FALL_DB = 6.0;
    static final double FALL_WINDOW_S = 3.0;
    /** 5G must have been at or below this level before vanishing to count as fading out. */
    static final double WEAK_NR = -105.0;
    /** 4G must still be at or above this level for the 5G hint to add anything. */
    static final double LTE_FINE = -110.0;
    static final double HOLD_S = 15.0;
    private static final int KEEP = 8;

    private final double[] times = new double[KEEP];
    private final double[] levels = new double[KEEP];
    private int size;
    private int next;
    private int steady;
    private int missing;
    private double warnUntil = Double.NEGATIVE_INFINITY;

    /** One tick: the data SIM's 4G RSRP and 5G SS-RSRP, NaN when not reported. */
    public void update(double t, double lteRsrp, double nrRsrp) {
        boolean lteFine = SanketDetector.validRsrp(lteRsrp) && lteRsrp >= LTE_FINE;
        if (nrRsrp >= NR_MIN && nrRsrp <= NR_MAX) {
            for (int k = 0; k < size; k++) {
                int i = (next + KEEP - size + k) % KEEP;
                if (t - times[i] <= FALL_WINDOW_S && levels[i] - nrRsrp >= FALL_DB && lteFine) {
                    warnUntil = t + HOLD_S;
                    break;
                }
            }
            times[next] = t;
            levels[next] = nrRsrp;
            next = (next + 1) % KEEP;
            if (size < KEEP) size++;
            steady++;
            missing = 0;
        } else {
            missing++;
            if (missing == 2 && steady >= 3 && size > 0 && levels[(next + KEEP - 1) % KEEP] <= WEAK_NR && lteFine) {
                warnUntil = t + HOLD_S;
            }
            if (missing >= 2) {
                steady = 0;
                size = 0;
            }
        }
    }

    public boolean warning(double t) { return t < warnUntil; }
}
