package io.github.c0ldsheep.sanket.core;

import java.util.Arrays;

/**
 * Learns how this phone reports signal: how often the modem refreshes RSRP and how much the
 * readings jitter. Phones differ a lot, and it matters: in our study 47.9% of entries were warned
 * in time with 1 s refreshes but only 29.3% with 5 s refreshes. The app sets the detector's
 * stale-reading timeout from the measured refresh interval.
 *
 * <p>A value that repeats unchanged looks the same as no refresh, so the measured interval is an
 * upper bound on the true one. That is the safe direction for the timeout.
 */
public final class DeviceProfile {
    /** Refreshes needed before the live estimates are used. */
    public static final int MIN_SAMPLES = 20;
    private static final int KEEP = 120;

    private final double[] gaps = new double[KEEP];
    private final double[] steps = new double[KEEP];
    private int count;
    private double lastT = Double.NaN;
    private double lastRsrp;
    private double lastRsrq;
    private double savedInterval = Double.NaN;
    private double savedNoise = Double.NaN;
    private int savedCount;

    /** One polled reading; the same value repeats until the modem refreshes it. */
    public void onSample(double t, double rsrp, double rsrq) {
        if (!SanketDetector.validRsrp(rsrp)) {
            lastT = Double.NaN;
            return;
        }
        if (Double.isNaN(lastT)) {
            lastT = t;
            lastRsrp = rsrp;
            lastRsrq = rsrq;
            return;
        }
        boolean sameQ = rsrq == lastRsrq || (Double.isNaN(rsrq) && Double.isNaN(lastRsrq));
        if (rsrp == lastRsrp && sameQ) return;
        double gap = t - lastT;
        if (gap > 0.0 && gap <= 30.0) {
            int i = count % KEEP;
            gaps[i] = gap;
            steps[i] = Math.abs(rsrp - lastRsrp);
            count++;
        }
        lastT = t;
        lastRsrp = rsrp;
        lastRsrq = rsrq;
    }

    /** Refreshes seen, in this session or saved from earlier ones. */
    public int samples() { return Math.max(count, savedCount); }

    /** Median seconds between fresh readings, or NaN until {@link #MIN_SAMPLES} refreshes. */
    public double refreshIntervalS() { return count >= MIN_SAMPLES ? median(gaps) : savedInterval; }

    /** Rough reading noise in dB, from the median step between fresh readings, or NaN. */
    public double noiseDb() { return count >= MIN_SAMPLES ? 1.4826 * median(steps) / Math.sqrt(2.0) : savedNoise; }

    /** Stale-reading timeout for the detector: twice the refresh interval, kept between 4 and 10 s. */
    public double heartbeatS() {
        double r = refreshIntervalS();
        return Double.isNaN(r) ? 4.0 : Math.max(4.0, Math.min(10.0, 2.0 * r));
    }

    /** Restores estimates saved by an earlier session; live data replaces them once there is enough. */
    public void restore(double intervalS, double noiseDb, int samples) {
        savedInterval = intervalS;
        savedNoise = noiseDb;
        savedCount = samples;
    }

    private double median(double[] values) {
        int n = Math.min(count, KEEP);
        double[] v = Arrays.copyOf(values, n);
        Arrays.sort(v);
        return n % 2 == 1 ? v[n / 2] : 0.5 * (v[n / 2 - 1] + v[n / 2]);
    }
}
