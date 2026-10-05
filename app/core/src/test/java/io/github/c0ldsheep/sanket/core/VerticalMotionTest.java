package io.github.c0ldsheep.sanket.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Random;
import java.util.function.DoubleUnaryOperator;
import org.junit.Test;

public class VerticalMotionTest {
    /** Barometer readings at 5 Hz with 0.01 hPa of noise. */
    private static void feed(VerticalMotion m, double from, double to, DoubleUnaryOperator pressure, Random r) {
        for (int i = 0; from + 0.2 * i < to - 1e-9; i++) {
            double t = from + 0.2 * i;
            m.onPressure(t, pressure.applyAsDouble(t) + 0.01 * r.nextGaussian());
        }
    }

    @Test
    public void liftGoingUpIsSeenAndClassified() {
        VerticalMotion m = new VerticalMotion();
        Random r = new Random(1);
        feed(m, 0.0, 10.0, t -> 1005.0, r);
        assertEquals(VerticalMotion.Movement.LEVEL, m.movement(10.0));
        feed(m, 10.0, 30.0, t -> 1005.0 - 0.15 * (t - 10.0), r);   // 1.25 m/s up
        assertEquals(VerticalMotion.Movement.UP_FAST, m.movement(29.8));
        assertEquals(ZoneMemory.Kind.LIFT, m.classify(29.8));
        assertTrue(m.heightChange(29.8, 60.0) > 20.0);
    }

    @Test
    public void slowDescentIsABasement() {
        VerticalMotion m = new VerticalMotion();
        Random r = new Random(2);
        feed(m, 0.0, 10.0, t -> 1005.0, r);
        feed(m, 10.0, 30.0, t -> 1005.0 + 0.04 * (t - 10.0), r);   // 0.33 m/s down, 6.6 m in all
        assertEquals(VerticalMotion.Movement.LEVEL, m.movement(29.8));
        assertEquals(ZoneMemory.Kind.BASEMENT, m.classify(29.8));
    }

    @Test
    public void standingStillIsIndoorAndNoBarometerIsUnknown() {
        VerticalMotion m = new VerticalMotion();
        feed(m, 0.0, 60.0, t -> 1005.0, new Random(3));
        assertEquals(VerticalMotion.Movement.LEVEL, m.movement(59.8));
        assertEquals(ZoneMemory.Kind.INDOOR, m.classify(59.8));
        VerticalMotion none = new VerticalMotion();
        assertFalse(none.available());
        assertEquals(ZoneMemory.Kind.UNKNOWN, none.classify(10.0));
    }
}
