package io.github.c0ldsheep.sanket.core;

/**
 * Lift rides from the accelerometer, for phones without a barometer (many budget phones).
 *
 * <p>Standing still, the accelerometer measures gravity, about 9.81 m/s², however the phone is
 * held. When a lift starts going up you feel heavier for a second or two, because the floor
 * pushes harder; going down you feel lighter. Its stop does the opposite. So a ride shows up as a
 * smooth bump in the size of the measured force, about 0.3 to 1.2 m/s² for 1 to 3 seconds, a
 * quiet cruise, then the opposite bump. The bump's area is the speed the lift reached. Walking,
 * riding and handling the phone shake it far more than a lift does, so bumps count only while
 * the phone is otherwise still, as people are while the doors close.
 *
 * <p>A ride is reported from its first bump, when the signal is about to fade, for up to
 * {@link #REPORT_S} seconds or until the opposite bump. Unlike the barometer, this cannot tell a
 * basement from an upper floor, and a very smooth car braking hard can look like a lift going up;
 * both only make SANKET save progress early, which costs nothing.
 */
public final class LiftMotion {
    /** Peak force change (m/s²) of a lift starting or stopping. */
    static final double BUMP_MS2 = 0.22;
    /** The force change where a bump begins and ends. */
    static final double EDGE_MS2 = 0.1;
    /** Shorter bumps are jolts; longer ones are not lifts. */
    static final double BUMP_MIN_S = 0.6;
    static final double BUMP_MAX_S = 4.0;
    /** Speed a bump must give (m/s): lifts reach 0.5 to 2.5 m/s, more in towers. */
    static final double SPEED_MIN = 0.4;
    static final double SPEED_MAX = 4.0;
    /** Shaking (m/s², per reading) of a phone that is held still or in the pocket of someone standing. */
    static final double STILL_MS2 = 0.15;
    /** Standing still this long before a bump, as people do while the doors close. */
    static final double STILL_BEFORE_S = 1.5;
    /** How long a ride is reported after its first bump, if no stop is felt. */
    static final double REPORT_S = 20.0;
    /** A first bump with no stop after this long was not a ride. */
    static final double RIDE_MAX_S = 120.0;
    /** A ride this recent makes a lost-signal place a lift. */
    static final double RECENT_S = 60.0;

    private static final double SMOOTH_S = 0.25;
    private static final double NOISE_S = 1.0;
    private static final double LEARN_S = 20.0;
    private static final double SETTLE_S = 3.0;
    private static final double GAP_S = 0.5;

    private double firstT = Double.NaN;
    private double lastT = Double.NaN;
    private double lastM;
    private double smooth;
    private double noiseVar;
    private double rest;
    private double stillSince = Double.NaN;

    private int bumpSign;
    private double bumpStart;
    private double bumpArea;
    private double bumpPeak;
    private boolean bumpClean;

    private int rideSign;
    private double rideStart;
    private double rideSpeed;
    private double lastRideEnd = Double.NaN;

    /** One accelerometer reading: time in seconds and the three axes in m/s². */
    public void onSample(double t, double ax, double ay, double az) {
        double m = Math.sqrt(ax * ax + ay * ay + az * az);
        if (!(m > 4.0 && m < 16.0)) {   // a fall, a knock or a broken reading
            stillSince = Double.NaN;
            bumpClean = false;
            return;
        }
        if (Double.isNaN(lastT) || t - lastT > GAP_S) {
            // First reading, or readings stopped for a while: start the filters again.
            if (Double.isNaN(firstT)) {
                firstT = t;
                rest = m;
            }
            lastT = t;
            lastM = m;
            smooth = m;
            noiseVar = 0.0;
            stillSince = Double.NaN;
            bumpSign = 0;
            return;
        }
        double dt = t - lastT;
        if (!(dt > 0.0)) return;
        // Shaking from the change between readings: a lift's smooth bump barely moves it, steps and engines do.
        double step = m - lastM;
        lastT = t;
        lastM = m;
        smooth += (m - smooth) * dt / (SMOOTH_S + dt);
        noiseVar += (step * step / 2.0 - noiseVar) * dt / (NOISE_S + dt);
        boolean still = Math.sqrt(noiseVar) < STILL_MS2;
        if (!still) {
            stillSince = Double.NaN;
        } else if (Double.isNaN(stillSince)) {
            stillSince = t;
        }
        boolean settled = t - firstT >= SETTLE_S;
        double dev = smooth - rest;
        if (still && bumpSign == 0 && (!settled || Math.abs(dev) < EDGE_MS2)) {
            // Learn the force at rest, which includes the sensor's own offset; quickly at first.
            double learn = settled ? LEARN_S : 0.5;
            rest += (smooth - rest) * dt / (learn + dt);
            dev = smooth - rest;
        }
        if (!settled) return;
        if (bumpSign != 0 && t - bumpStart > BUMP_MAX_S) {
            // Too long for a lift: the resting force was learned while the phone moved. Learn it again.
            bumpSign = 0;
            if (still) rest = smooth;
            return;
        }
        int sign = Math.abs(dev) < EDGE_MS2 ? 0 : (dev > 0.0 ? 1 : -1);
        if (bumpSign != 0 && sign != bumpSign) endBump(t);
        if (bumpSign == 0 && sign != 0) {
            bumpSign = sign;
            bumpStart = t;
            bumpArea = 0.0;
            bumpPeak = 0.0;
            bumpClean = still && t - stillSince >= STILL_BEFORE_S;
        }
        if (bumpSign != 0) {
            bumpArea += dev * dt;
            bumpPeak = Math.max(bumpPeak, Math.abs(dev));
            if (!still) bumpClean = false;
        }
        if (rideSign != 0 && t - rideStart > RIDE_MAX_S) rideSign = 0;
    }

    private void endBump(double t) {
        int sign = bumpSign;
        bumpSign = 0;
        double length = t - bumpStart;
        double speed = Math.abs(bumpArea);
        if (!bumpClean || bumpPeak < BUMP_MS2 || length < BUMP_MIN_S || length > BUMP_MAX_S
                || speed < SPEED_MIN || speed > SPEED_MAX) {
            return;
        }
        if (rideSign == 0) {
            // Heavier means the lift is pushing you up; lighter means it has started down.
            rideSign = sign;
            rideStart = t;
            rideSpeed = speed;
        } else if (sign == -rideSign) {
            rideSign = 0;
            lastRideEnd = t;
        }
    }

    /** True once readings have arrived. */
    public boolean available() { return !Double.isNaN(firstT); }

    /** A lift ride in progress, as fast vertical movement. */
    public VerticalMotion.Movement movement(double t) {
        if (rideSign == 0 || t - rideStart > REPORT_S) return VerticalMotion.Movement.LEVEL;
        return rideSign > 0 ? VerticalMotion.Movement.UP_FAST : VerticalMotion.Movement.DOWN_FAST;
    }

    /** The lift's speed (m/s, positive going up) while a ride is reported, else 0. */
    public double speed(double t) {
        return movement(t) == VerticalMotion.Movement.LEVEL ? 0.0 : rideSign * rideSpeed;
    }

    /** LIFT during a ride or within a minute of one; otherwise unknown, since floors cannot be counted. */
    public ZoneMemory.Kind classify(double t) {
        boolean riding = rideSign != 0 && t - rideStart <= RIDE_MAX_S;
        boolean recent = !Double.isNaN(lastRideEnd) && t - lastRideEnd <= RECENT_S;
        return riding || recent ? ZoneMemory.Kind.LIFT : ZoneMemory.Kind.UNKNOWN;
    }
}
