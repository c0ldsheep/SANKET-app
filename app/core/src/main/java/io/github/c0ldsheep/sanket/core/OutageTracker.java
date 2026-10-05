package io.github.c0ldsheep.sanket.core;

/**
 * Turns once-a-second "connected or not" into outages.
 *
 * <p>An outage starts after two silent ticks, which is when the place is recorded. It is worth a
 * line in the safety log only once it has lasted {@link #CONFIRM_S} seconds, so brief dropouts in
 * weak areas do not fill the log. It ends after two good ticks.
 */
public final class OutageTracker {
    /** What one tick changed. */
    public enum Event { NONE, STARTED, CONFIRMED, ENDED }

    static final int BAD_TICKS = 2;
    static final int GOOD_TICKS = 2;
    /** An outage this long is logged. */
    public static final double CONFIRM_S = 10.0;

    private int bad;
    private int good;
    private boolean lost;
    private boolean confirmed;
    private double since = Double.NaN;

    public Event tick(double t, boolean connected) {
        if (connected) {
            good++;
            bad = 0;
        } else {
            bad++;
            good = 0;
        }
        if (!lost && bad >= BAD_TICKS) {
            lost = true;
            confirmed = false;
            since = t;
            return Event.STARTED;
        }
        if (lost && !confirmed && !connected && t - since >= CONFIRM_S) {
            confirmed = true;
            return Event.CONFIRMED;
        }
        if (lost && good >= GOOD_TICKS) {
            lost = false;
            return Event.ENDED;
        }
        return Event.NONE;
    }

    /** Forgets any outage in progress, for example when the user switches on airplane mode. */
    public void reset() {
        bad = 0;
        good = 0;
        lost = false;
        confirmed = false;
        since = Double.NaN;
    }

    public boolean lost() { return lost; }

    /** Whether the current or just-ended outage lasted long enough to be logged. */
    public boolean confirmed() { return confirmed; }

    /** When the current or just-ended outage started. */
    public double since() { return since; }
}
