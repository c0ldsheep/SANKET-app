package io.github.c0ldsheep.sanket.core;

/**
 * Tells "no signal" apart from "signal but no internet" and from "internet slowing down".
 *
 * <p>Two inputs: the system's own check of the default network (Android marks a network as
 * validated once it has reached the internet) and the response times of the app's own requests.
 * Response times are tracked in log space with the same level-and-trend filter the detector uses
 * for RSRP, so a latency that keeps rising is noticed before requests time out. The thresholds
 * are starting values, to be tuned with field recordings.
 */
public final class LinkHealth {
    /** Health of the default network. */
    public enum State { OK, SLOW, NO_INTERNET, NO_NETWORK }

    /** Seconds a connected network may stay unvalidated before it counts as "no internet". */
    static final double UNVALIDATED_GRACE_S = 5.0;
    /** Response time treated as too slow to finish a transfer before a drop. */
    static final double SLOW_LATENCY_MS = 2000.0;
    /** Latency growth (natural log per second) treated as a jam: about 15% per second. */
    static final double RISING_LOG_RATE = 0.14;
    /** Latency estimates older than this are ignored. */
    static final double MAX_AGE_S = 30.0;

    private final LocalLinearTrend latency = new LocalLinearTrend(0.35, 0.01, 0.1, 3.0);
    private boolean connected;
    private boolean validated;
    private double unvalidatedSince = Double.NaN;

    /** The default network changed. {@code validated}: the system confirmed it reaches the internet. */
    public void onNetwork(double t, boolean connected, boolean validated) {
        this.connected = connected;
        this.validated = connected && validated;
        if (connected && !validated) {
            if (Double.isNaN(unvalidatedSince)) unvalidatedSince = t;
        } else {
            unvalidatedSince = Double.NaN;
        }
        if (!connected) latency.reset();
    }

    /** Time from sending a request to the first byte of its response, in milliseconds. */
    public void onLatency(double t, double millis) {
        if (!(millis > 0.0) || Double.isInfinite(millis)) return;
        latency.update(t, Math.log(millis));
    }

    public State state(double t) {
        if (!connected) return State.NO_NETWORK;
        if (!validated && t - unvalidatedSince >= UNVALIDATED_GRACE_S) return State.NO_INTERNET;
        if (latency.n >= 3 && t - latency.t <= MAX_AGE_S) {
            double level = latency.level + latency.rate * Math.max(0.0, t - latency.t);
            boolean rising = latency.n >= 5 && latency.rate >= RISING_LOG_RATE;
            if (level >= Math.log(SLOW_LATENCY_MS) || rising) return State.SLOW;
        }
        return State.OK;
    }

    /** Latest response-time estimate in milliseconds, or NaN before any request. */
    public double latencyMs() { return latency.n > 0 ? Math.exp(latency.level) : Double.NaN; }

    public boolean validated() { return validated; }
}
