package io.github.c0ldsheep.sanket.core;

/**
 * SANKET streaming link-loss detector: a Java port of {@code research/sanket/core.py} (SanketDetector).
 *
 * <p>Pure Java with no Android dependencies, so the same class can be unit-tested on a laptop
 * and dropped into an Android app. Feed it one reading per tick (normally once per second):
 * RSRP and RSRQ of the serving LTE cell and the strongest neighbour's RSRP, using
 * {@code Double.NaN} for "unavailable" (Android's {@code CellInfo.UNAVAILABLE}). When
 * {@link #step} returns {@code true}, prefetch the delivery manifest.
 *
 * <p>The arithmetic repeats the Python reference operation for operation;
 * {@code GoldenVectorsTest} replays the Python golden vectors and requires identical alarms.
 * Not thread-safe: use one instance per SIM / subscription from one thread.
 */
public final class SanketDetector {
    public static final double RSRP_MIN = -140.0, RSRP_MAX = -43.0, RSRQ_MIN = -34.0, RSRQ_MAX = 3.0;
    public static final long UNKNOWN_CELL = Long.MIN_VALUE;
    static final int UNAVAILABLE = 0, STALE = 1, NEW = 2, FIRST = 3;

    /** All tunable settings. {@link #tuned()} gives the values chosen in the PRAYAS study. */
    public static final class Params {
        public double measSd = 2.0, q = 0.2, gate = 3.5, theta = -124.0, horizon = 15.0, pThr = 0.5, minRate = 0.3;
        public String rsrqMode = "off";
        public double thetaQ = -18.0, confirmEps = 0.3, minRateQ = 0.2;
        public boolean nbrVeto = false;
        public double nbrDelta = 6.0, nbrDiff = 1.0, nbrMaxAge = 6.0, rsrqMeasSd = 1.5, rsrqQ = 0.1;
        public int persistence = 2, warmup = 3;
        public double cooldownS = 60.0, heartbeatS = 4.0, graceS = 3.0;
        public boolean dedupe = true, resetOnCellChange = false;

        /** Defaults identical to the Python constructor. */
        public static Params pythonDefaults() { return new Params(); }

        /** Settings tuned on the calibration data (results/params.json). */
        public static Params tuned() {
            Params p = new Params();
            p.measSd = 3.0; p.q = 0.1; p.theta = -124.0; p.horizon = 10.0; p.pThr = 0.7; p.minRate = 1.0;
            p.persistence = 1; p.rsrqMode = "off"; p.nbrVeto = true; p.nbrDelta = 3.0; p.nbrDiff = 1.5;
            return p;
        }
    }

    static boolean finite(double x) { return !Double.isNaN(x) && !Double.isInfinite(x); }
    public static boolean validRsrp(double x) { return finite(x) && x >= RSRP_MIN && x <= RSRP_MAX; }
    public static boolean validRsrq(double x) { return finite(x) && x >= RSRQ_MIN && x <= RSRQ_MAX; }

    /** Standard-normal quantile (AS241, same steps as sanket.core.z_for_probability). */
    public static double zForProbability(double p) {
        if (!(p > 0.0 && p < 1.0)) throw new IllegalArgumentException("probability must be in (0, 1)");
        double q = p - 0.5, r, num, den;
        if (Math.abs(q) <= 0.425) {
            r = 0.180625 - q * q;
            num = (((((((2.5090809287301226727e+3 * r + 3.3430575583588128105e+4) * r + 6.7265770927008700853e+4) * r
                    + 4.5921953931549871457e+4) * r + 1.3731693765509461125e+4) * r + 1.9715909503065514427e+3) * r
                    + 1.3314166789178437745e+2) * r + 3.3871328727963666080e+0) * q;
            den = (((((((5.2264952788528545610e+3 * r + 2.8729085735721942674e+4) * r + 3.9307895800092710610e+4) * r
                    + 2.1213794301586595867e+4) * r + 5.3941960214247511077e+3) * r + 6.8718700749205790830e+2) * r
                    + 4.2313330701600911252e+1) * r + 1.0);
            return num / den;
        }
        r = q <= 0.0 ? p : 1.0 - p;
        r = Math.sqrt(-Math.log(r));
        if (r <= 5.0) {
            r = r - 1.6;
            num = (((((((7.74545014278341407640e-4 * r + 2.27238449892691845833e-2) * r + 2.41780725177450611770e-1) * r
                    + 1.27045825245236838258e+0) * r + 3.64784832476320460504e+0) * r + 5.76949722146069140550e+0) * r
                    + 4.63033784615654529590e+0) * r + 1.42343711074968357734e+0);
            den = (((((((1.05075007164441684324e-9 * r + 5.47593808499534494600e-4) * r + 1.51986665636164571966e-2) * r
                    + 1.48103976427480074590e-1) * r + 6.89767334985100004550e-1) * r + 1.67638483018380384940e+0) * r
                    + 2.05319162663775882187e+0) * r + 1.0);
        } else {
            r = r - 5.0;
            num = (((((((2.01033439929228813265e-7 * r + 2.71155556874348757815e-5) * r + 1.24266094738807843860e-3) * r
                    + 2.65321895265761230930e-2) * r + 2.96560571828504891230e-1) * r + 1.78482653991729133580e+0) * r
                    + 5.46378491116411436990e+0) * r + 6.65790464350110377720e+0);
            den = (((((((2.04426310338993978564e-15 * r + 1.42151175831644588870e-7) * r + 1.84631831751005468180e-5) * r
                    + 7.86869131145613259100e-4) * r + 1.48753612908506148525e-2) * r + 1.36929880922735805310e-1) * r
                    + 5.99832206555887937690e-1) * r + 1.0);
        }
        double x = num / den;
        return q < 0.0 ? -x : x;
    }

    /** Turns a polled reading stream into measurement events (stale repeats, glitches, outages). */
    static final class StreamAdapter {
        final double heartbeat, grace;
        final boolean dedupe, resetOnCellChange;
        boolean hasLast;
        double lastRsrp, lastRsrq, tLast, tBad;
        boolean hasBad;
        long cell;

        StreamAdapter(double heartbeat, boolean dedupe, boolean resetOnCellChange, double grace) {
            if (!(heartbeat > 0) || !(grace >= 0)) throw new IllegalArgumentException("bad heartbeat/grace");
            this.heartbeat = heartbeat; this.dedupe = dedupe; this.resetOnCellChange = resetOnCellChange; this.grace = grace;
            reset();
        }

        void reset() { hasLast = false; hasBad = false; cell = UNKNOWN_CELL; }

        int push(double t, double rsrp, double rsrq, long cellId) {
            if (!validRsrp(rsrp)) {
                if (hasLast) {
                    if (!hasBad) { tBad = t; hasBad = true; }
                    if (t - tBad < grace) return STALE;
                }
                reset();
                return UNAVAILABLE;
            }
            hasBad = false;
            double q = validRsrq(rsrq) ? rsrq : Double.NaN;
            int status;
            if (!hasLast) status = FIRST;
            else if (resetOnCellChange && cellId != UNKNOWN_CELL && cell != UNKNOWN_CELL && cellId != cell) status = FIRST;
            else if (dedupe && lastRsrp == rsrp && sameQ(lastRsrq, q) && (t - tLast) < heartbeat) return STALE;
            else status = NEW;
            hasLast = true; lastRsrp = rsrp; lastRsrq = q; tLast = t;
            if (cellId != UNKNOWN_CELL) cell = cellId;
            return status;
        }

        private static boolean sameQ(double a, double b) { return (Double.isNaN(a) && Double.isNaN(b)) || a == b; }
    }

    // forecast(): returns {mean, sd}
    static double[] forecast(double level, double rate, double p00, double p01, double p11, double q, double h) {
        double mean = level + rate * h;
        double v = p00 + 2.0 * h * p01 + h * h * p11 + q * h * h * h / 3.0;
        return new double[] {mean, Math.sqrt(v > 0.0 ? v : 0.0)};
    }

    private final Params p;
    private final double zThr;
    private final StreamAdapter adapter, nbrAdapter;
    final LocalLinearTrend kf, kq, kn;
    private int nMeas, run;
    private boolean hasLastAlarm, hasNbrT;
    private double lastAlarm, nbrT;

    public SanketDetector(Params params) {
        this.p = params;
        if (p.persistence < 1 || p.warmup < 1 || p.cooldownS < 0) throw new IllegalArgumentException("bad persistence/warmup/cooldown");
        if (!p.rsrqMode.equals("off") && !p.rsrqMode.equals("confirm") && !p.rsrqMode.equals("dual"))
            throw new IllegalArgumentException("rsrqMode must be off, confirm or dual");
        zThr = zForProbability(p.pThr);
        adapter = new StreamAdapter(p.heartbeatS, p.dedupe, p.resetOnCellChange, p.graceS);
        nbrAdapter = new StreamAdapter(p.heartbeatS, p.dedupe, false, p.graceS);
        kf = new LocalLinearTrend(p.measSd, p.q, 1.0, p.gate);
        kq = new LocalLinearTrend(p.rsrqMeasSd, p.rsrqQ, 0.5, p.gate);
        kn = new LocalLinearTrend(p.measSd, p.q, 1.0, p.gate);
        reset();
    }

    public void reset() { adapter.reset(); nMeas = 0; run = 0; hasLastAlarm = false; resetSignal(); }

    private void resetSignal() { kf.reset(); kq.reset(); kn.reset(); nbrAdapter.reset(); hasNbrT = false; }

    /** One tick. Returns true when the prefetch should start now. */
    public boolean step(double t, double rsrp, double rsrq, double nbrRsrp) { return step(t, rsrp, rsrq, nbrRsrp, UNKNOWN_CELL); }

    public boolean step(double t, double rsrp, double rsrq, double nbrRsrp, long cellId) {
        int status = adapter.push(t, rsrp, rsrq, cellId);
        if (status == UNAVAILABLE) {
            if (nMeas != 0) resetSignal();
            nMeas = 0; run = 0;
            return false;
        }
        if (status == FIRST) { resetSignal(); nMeas = 0; }
        if (status == FIRST || status == NEW) {
            kf.update(t, rsrp);
            if (validRsrq(rsrq)) kq.update(t, rsrq);
            nMeas += 1;
        }
        onTick(t, nbrRsrp);
        boolean cond = nMeas >= p.warmup && condition(t);
        run = cond ? run + 1 : 0;
        if (run >= p.persistence && (!hasLastAlarm || t - lastAlarm >= p.cooldownS)) {
            lastAlarm = t; hasLastAlarm = true;
            return true;
        }
        return false;
    }

    private void onTick(double t, double nbr) {
        kf.predictTo(t);
        if (kq.ready()) kq.predictTo(t);
        int status = nbrAdapter.push(t, nbr, Double.NaN, UNKNOWN_CELL);
        if (status == UNAVAILABLE) { kn.reset(); hasNbrT = false; }
        else {
            if (status == FIRST) kn.reset();
            if (status == FIRST || status == NEW) { kn.update(t, nbr); nbrT = t; hasNbrT = true; }
            kn.predictTo(t);
        }
    }

    private boolean condition(double t) {
        double h = p.horizon;
        double[] f = forecast(kf.level, kf.rate, kf.p00, kf.p01, kf.p11, kf.q, h);
        boolean main = (p.theta - f[0]) >= zThr * f[1] && kf.rate <= -p.minRate;
        if (!p.rsrqMode.equals("off")) {
            if (p.rsrqMode.equals("confirm")) {
                if (kq.n >= 2 && kq.rate > p.confirmEps) main = false;
            } else if (kq.n >= 2) {
                double[] g = forecast(kq.level, kq.rate, kq.p00, kq.p01, kq.p11, kq.q, h);
                if ((p.thetaQ - g[0]) >= zThr * g[1] && kq.rate <= -p.minRateQ) main = true;
            }
        }
        if (main && p.nbrVeto && kn.n >= 2 && hasNbrT && (t - nbrT) <= p.nbrMaxAge) {
            if (kn.level >= kf.level - p.nbrDelta && (kn.rate - kf.rate) >= p.nbrDiff) main = false;
        }
        return main;
    }

    /** Horizon of the risk forecast, in seconds. */
    public double horizon() { return p.horizon; }

    /** Chance that RSRP is at or below the loss level {@link #horizon()} seconds from now (for display). */
    public double risk() {
        if (!kf.ready()) return 0.0;
        double[] f = forecast(kf.level, kf.rate, kf.p00, kf.p01, kf.p11, kf.q, p.horizon);
        if (f[1] <= 0.0) return f[0] <= p.theta ? 1.0 : 0.0;
        return normalCdf((p.theta - f[0]) / f[1]);
    }

    /** Seconds until the trend line reaches the loss level; infinite while the signal is not falling. */
    public double timeToLoss() {
        if (!kf.ready() || kf.rate >= 0.0) return Double.POSITIVE_INFINITY;
        return Math.max(0.0, (kf.level - p.theta) / -kf.rate);
    }

    /** Standard normal CDF through the complementary error function (Numerical Recipes erfcc, error below 1.2e-7). */
    static double normalCdf(double x) {
        double z = Math.abs(x) / Math.sqrt(2.0);
        double t = 1.0 / (1.0 + 0.5 * z);
        double erfc = t * Math.exp(-z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418
                + t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587
                + t * (-0.82215223 + t * 0.17087277)))))))));
        return x >= 0.0 ? 1.0 - 0.5 * erfc : 0.5 * erfc;
    }

    public int measurements() { return nMeas; }
    public double level() { return kf.level; }
    /** Estimated temporal gradient of RSRP in dB/s (negative = falling). */
    public double rate() { return kf.rate; }
    public double[] covariance() { return new double[] {kf.p00, kf.p01, kf.p11}; }
}
