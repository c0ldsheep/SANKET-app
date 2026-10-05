package io.github.c0ldsheep.sanket.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SanketDetectorTest {
    @Test
    public void normalCdfMatchesTableValues() {
        assertEquals(0.5, SanketDetector.normalCdf(0.0), 1e-7);
        assertEquals(0.975002, SanketDetector.normalCdf(1.96), 1e-6);
        assertEquals(0.158655, SanketDetector.normalCdf(-1.0), 1e-6);
    }

    @Test
    public void steadyFallRaisesTheAlarmAndTheRisk() {
        SanketDetector d = new SanketDetector(SanketDetector.Params.tuned());
        boolean alarm = false;
        for (int t = 0; t <= 20; t++) alarm |= d.step(t, -90.0 - 2.0 * Math.max(0, t - 5), -10.0, Double.NaN);
        assertTrue(alarm);
        assertTrue(d.risk() > 0.9);
        assertTrue(d.timeToLoss() < 10.0);
    }

    @Test
    public void steadySignalStaysQuiet() {
        SanketDetector d = new SanketDetector(SanketDetector.Params.tuned());
        for (int t = 0; t <= 30; t++) assertFalse(d.step(t, -85.0 + (t % 2), -9.0, Double.NaN));
        assertTrue(d.risk() < 0.01);
        assertTrue(d.timeToLoss() > 30.0);
    }
}
