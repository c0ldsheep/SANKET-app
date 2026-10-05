package io.github.c0ldsheep.sanket.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Combines every signal into one protection level for the rest of the app.
 *
 * <ul>
 *   <li>{@link Level#PROTECT}: the tuned detector raised its alarm, or the phone is at a known
 *       dead zone. Save every transfer's progress now and record the offline notice.</li>
 *   <li>{@link Level#WATCH}: an early hint (rising risk, 5G fading, fast vertical movement, slow
 *       or missing internet). Save progress, which costs nothing, and show no alert.</li>
 *   <li>{@link Level#OFFLINE}: no usable connection.</li>
 * </ul>
 * A level is held for a few seconds after its cause disappears, so the screen does not flicker.
 * Losing the connection and getting it back always take effect at once.
 */
public final class GuardPolicy {
    /** Protection level, from relaxed to offline. */
    public enum Level { CLEAR, WATCH, PROTECT, OFFLINE }

    /** One tick's evidence. */
    public static final class Inputs {
        /** A usable cellular reading on the data SIM, or a working Wi-Fi network. */
        public boolean connected = true;
        /** The tuned detector raised its alarm at this tick. */
        public boolean alarm;
        /** The detector's chance of losing signal within its horizon. */
        public double risk;
        public boolean nrFading;
        public VerticalMotion.Movement movement = VerticalMotion.Movement.LEVEL;
        public LinkHealth.State health = LinkHealth.State.OK;
        public boolean atKnownZone;
    }

    static final double WATCH_RISK = 0.3;
    static final double PROTECT_HOLD_S = 20.0;
    static final double WATCH_HOLD_S = 10.0;

    private Level level = Level.CLEAR;
    private double holdUntil = Double.NEGATIVE_INFINITY;
    private List<String> reasons = Collections.emptyList();

    /** Feeds one tick; returns true when the level changed. */
    public boolean update(double t, Inputs in) {
        List<String> why = new ArrayList<>();
        Level target;
        if (!in.connected) {
            target = Level.OFFLINE;
            why.add("No usable signal");
        } else {
            if (in.alarm) why.add("Signal falling fast");
            if (in.atKnownZone) why.add("Known dead zone here");
            if (!why.isEmpty()) {
                target = Level.PROTECT;
            } else {
                if (in.risk >= WATCH_RISK) why.add("Signal getting weaker");
                if (in.nrFading) why.add("5G fading");
                if (in.movement == VerticalMotion.Movement.UP_FAST) why.add("Going up fast, maybe a lift");
                if (in.movement == VerticalMotion.Movement.DOWN_FAST) why.add("Going down fast, maybe a lift or ramp");
                if (in.health == LinkHealth.State.SLOW) why.add("Internet slowing down");
                if (in.health == LinkHealth.State.NO_INTERNET) why.add("Connected but no internet");
                target = why.isEmpty() ? Level.CLEAR : Level.WATCH;
            }
        }
        Level previous = level;
        boolean allowed = target == Level.OFFLINE || level == Level.OFFLINE
                || rank(target) >= rank(level) || t >= holdUntil;
        if (allowed) {
            level = target;
            reasons = Collections.unmodifiableList(why);
            holdUntil = t + (target == Level.PROTECT ? PROTECT_HOLD_S : target == Level.WATCH ? WATCH_HOLD_S : 0.0);
        }
        return level != previous;
    }

    public Level level() { return level; }

    /** Plain-language reasons for the current level. */
    public List<String> reasons() { return reasons; }

    private static int rank(Level l) { return l == Level.PROTECT ? 2 : l == Level.WATCH ? 1 : 0; }
}
