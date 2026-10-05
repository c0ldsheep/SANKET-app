package io.github.c0ldsheep.sanket.core;

/**
 * Vertical movement from the barometer: lifts, ramps and stairs.
 *
 * <p>Near sea level, air pressure drops by about 0.12 hPa for every metre you go up, so the
 * pressure trend gives vertical speed. A lift moves at 1 to 2.5 m/s, much faster than stairs
 * (about 0.2 m/s), and a scooter on a basement ramp drops a few metres in a few seconds. The same
 * level-and-trend filter as the detector smooths the readings, and its innovation gate
 * down-weights jumps from doors or air conditioning. Phones without a barometer never call
 * {@link #onPressure}, and everything here then reports "unknown" or "level".
 */
public final class VerticalMotion {
    /** Fast vertical movement right now. */
    public enum Movement { LEVEL, UP_FAST, DOWN_FAST }

    /** Metres per hPa near sea level. */
    public static final double METRES_PER_HPA = 8.3;
    /** Vertical speed (m/s) that means a lift or a ramp rather than walking or stairs. */
    static final double FAST_M_S = 0.6;
    /** Speed only a lift reaches; ramps and stairs stay below it. */
    static final double LIFT_M_S = 0.9;
    /** Seconds the fast movement must last before it is reported. */
    static final double HOLD_S = 2.0;
    /** Height change (m) that counts as changing floor. */
    static final double FLOOR_M = 2.5;
    private static final int HISTORY_S = 180;

    private final LocalLinearTrend filter = new LocalLinearTrend(0.04, 0.01, 0.05, 4.0);
    private final double[] histT = new double[HISTORY_S];
    private final double[] histP = new double[HISTORY_S];
    private final double[] histV = new double[HISTORY_S];
    private int histSize;
    private int histNext;
    private double fastSince = Double.NaN;
    private int fastSign;

    /** One barometer reading: time in seconds, pressure in hPa. */
    public void onPressure(double t, double hPa) {
        if (!(hPa > 300.0 && hPa < 1100.0)) return;
        filter.update(t, hPa);
        double v = speed();
        int sign = Math.abs(v) >= FAST_M_S ? (v > 0.0 ? 1 : -1) : 0;
        if (sign != fastSign) {
            fastSign = sign;
            fastSince = sign == 0 ? Double.NaN : t;
        }
        int last = (histNext + HISTORY_S - 1) % HISTORY_S;
        if (histSize == 0 || t - histT[last] >= 1.0) {
            histT[histNext] = t;
            histP[histNext] = filter.level;
            histV[histNext] = v;
            histNext = (histNext + 1) % HISTORY_S;
            if (histSize < HISTORY_S) histSize++;
        }
    }

    /** True once at least one barometer reading has arrived. */
    public boolean available() { return filter.n > 0; }

    /** Vertical speed in m/s, positive going up; 0 until the filter has settled. */
    public double speed() { return filter.n >= 3 ? -filter.rate * METRES_PER_HPA : 0.0; }

    public Movement movement(double t) {
        if (fastSign == 0 || t - fastSince < HOLD_S) return Movement.LEVEL;
        return fastSign > 0 ? Movement.UP_FAST : Movement.DOWN_FAST;
    }

    /** Height gained over the last {@code seconds} (m, negative = went down); NaN without data. */
    public double heightChange(double t, double seconds) {
        if (histSize < 2) return Double.NaN;
        int newest = (histNext + HISTORY_S - 1) % HISTORY_S;
        for (int k = 0; k < histSize; k++) {
            int i = (histNext + HISTORY_S - histSize + k) % HISTORY_S;
            if (histT[i] >= t - seconds) {
                return i == newest ? Double.NaN : (histP[i] - histP[newest]) * METRES_PER_HPA;
            }
        }
        return Double.NaN;
    }

    /** Fastest vertical speed (m/s, either direction) over the last {@code seconds}. */
    double peakSpeed(double t, double seconds) {
        double peak = 0.0;
        for (int k = 0; k < histSize; k++) {
            int i = (histNext + HISTORY_S - histSize + k) % HISTORY_S;
            if (histT[i] >= t - seconds) peak = Math.max(peak, Math.abs(histV[i]));
        }
        return peak;
    }

    /** The most likely kind of place, judged from the last minute of movement. */
    public ZoneMemory.Kind classify(double t) {
        double dh = heightChange(t, 60.0);
        if (Double.isNaN(dh)) return ZoneMemory.Kind.UNKNOWN;
        if (Math.abs(dh) >= FLOOR_M && peakSpeed(t, 60.0) >= LIFT_M_S) return ZoneMemory.Kind.LIFT;
        if (dh <= -FLOOR_M) return ZoneMemory.Kind.BASEMENT;
        return ZoneMemory.Kind.INDOOR;
    }
}
