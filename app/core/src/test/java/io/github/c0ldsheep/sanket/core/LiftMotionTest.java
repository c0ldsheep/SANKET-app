package io.github.c0ldsheep.sanket.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Random;
import java.util.function.DoubleUnaryOperator;
import org.junit.Test;

public class LiftMotionTest {
    private static final double G = 9.81;
    /** The phone tilted in a pocket: gravity spread over all three axes. */
    private static final double[] TILT = unit(0.3, 0.5, 0.81);

    /** Accelerometer readings at 25 Hz with 0.03 m/s² of noise on each axis. */
    private static void feed(LiftMotion m, double from, double to, DoubleUnaryOperator extra, Random r) {
        for (int i = 0; from + 0.04 * i < to - 1e-9; i++) {
            double t = from + 0.04 * i;
            double f = G + extra.applyAsDouble(t);
            m.onSample(t, f * TILT[0] + 0.03 * r.nextGaussian(), f * TILT[1] + 0.03 * r.nextGaussian(),
                    f * TILT[2] + 0.03 * r.nextGaussian());
        }
    }

    /** A lift's smooth start or stop: {@code peak} m/s² for about {@code length} seconds from {@code at}. */
    private static double bump(double t, double at, double length, double peak) {
        if (t < at || t > at + length) return 0.0;
        double s = Math.sin(Math.PI * (t - at) / length);
        return peak * s * s;
    }

    /** Starts at {@code at}, cruises, stops 15 s later. Going down feels lighter first. */
    private static DoubleUnaryOperator ride(double at, int direction) {
        return t -> bump(t, at, 2.0, 0.6 * direction) + bump(t, at + 17.0, 2.0, -0.6 * direction);
    }

    @Test
    public void liftGoingDownIsFeltFromItsStart() {
        LiftMotion m = new LiftMotion();
        Random r = new Random(1);
        feed(m, 0.0, 30.0, ride(10.0, -1), r);
        assertTrue(m.available());
        LiftMotion during = new LiftMotion();
        feed(during, 0.0, 13.0, ride(10.0, -1), new Random(1));
        assertEquals(VerticalMotion.Movement.DOWN_FAST, during.movement(13.0));
        assertTrue(during.speed(13.0) < -0.4);
        assertEquals(ZoneMemory.Kind.LIFT, during.classify(13.0));
        // After the stop, the ride is over but the place still counts as a lift for a minute.
        assertEquals(VerticalMotion.Movement.LEVEL, m.movement(30.0));
        assertEquals(ZoneMemory.Kind.LIFT, m.classify(30.0));
        feed(m, 30.0, 100.0, t -> 0.0, r);
        assertEquals(ZoneMemory.Kind.UNKNOWN, m.classify(100.0));
    }

    @Test
    public void liftGoingUpFeelsHeavierFirst() {
        LiftMotion m = new LiftMotion();
        feed(m, 0.0, 14.0, ride(10.0, 1), new Random(2));
        assertEquals(VerticalMotion.Movement.UP_FAST, m.movement(14.0));
        assertTrue(m.speed(14.0) > 0.4);
    }

    @Test
    public void walkingAndRidingAreNotLifts() {
        LiftMotion walk = new LiftMotion();
        Random r = new Random(3);
        feed(walk, 0.0, 60.0, t -> 2.5 * Math.sin(2.0 * Math.PI * 1.9 * t) + 0.3 * r.nextGaussian(), r);
        assertEquals(VerticalMotion.Movement.LEVEL, walk.movement(60.0));
        assertEquals(ZoneMemory.Kind.UNKNOWN, walk.classify(60.0));
        // A scooter braking: the right size of bump, but the engine and road shake the phone.
        LiftMotion scooter = new LiftMotion();
        Random s = new Random(4);
        feed(scooter, 0.0, 40.0, t -> bump(t, 20.0, 2.0, 0.6) + 0.4 * s.nextGaussian(), s);
        assertEquals(VerticalMotion.Movement.LEVEL, scooter.movement(23.0));
    }

    @Test
    public void joltsAndPickingUpThePhoneAreIgnored() {
        LiftMotion m = new LiftMotion();
        Random r = new Random(5);
        feed(m, 0.0, 40.0, t -> bump(t, 10.0, 0.2, 3.0) + bump(t, 20.0, 0.3, -2.0), r);
        assertEquals(VerticalMotion.Movement.LEVEL, m.movement(12.0));
        assertEquals(VerticalMotion.Movement.LEVEL, m.movement(40.0));
        assertEquals(ZoneMemory.Kind.UNKNOWN, m.classify(40.0));
    }

    @Test
    public void aStartWithNoStopFadesOut() {
        LiftMotion m = new LiftMotion();
        Random r = new Random(6);
        feed(m, 0.0, 14.0, t -> bump(t, 10.0, 2.0, 0.6), r);
        assertEquals(VerticalMotion.Movement.UP_FAST, m.movement(14.0));
        feed(m, 14.0, 40.0, t -> 0.0, r);
        assertEquals(VerticalMotion.Movement.LEVEL, m.movement(40.0));
    }

    @Test
    public void anOffsetSensorIsLearned() {
        // Cheap sensors often read 9.6 or 10.0 at rest; the ride must still be felt.
        LiftMotion m = new LiftMotion();
        Random r = new Random(7);
        feed(m, 0.0, 13.0, t -> 0.25 + ride(10.0, -1).applyAsDouble(t), r);
        assertEquals(VerticalMotion.Movement.DOWN_FAST, m.movement(13.0));
    }

    @Test
    public void noAccelerometerMeansUnknown() {
        LiftMotion m = new LiftMotion();
        assertFalse(m.available());
        assertEquals(VerticalMotion.Movement.LEVEL, m.movement(10.0));
        assertEquals(ZoneMemory.Kind.UNKNOWN, m.classify(10.0));
    }

    private static double[] unit(double x, double y, double z) {
        double n = Math.sqrt(x * x + y * y + z * z);
        return new double[] {x / n, y / n, z / n};
    }
}
