package io.github.c0ldsheep.sanket.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LayerWatchTest {
    @Test
    public void fastFiveGFallWarnsWhileFourGIsFine() {
        LayerWatch w = new LayerWatch();
        w.update(0.0, -90.0, -95.0);
        w.update(1.0, -90.0, -97.0);
        w.update(2.0, -91.0, -104.0);
        assertTrue(w.warning(2.0));
        assertFalse(w.warning(2.0 + LayerWatch.HOLD_S));
    }

    @Test
    public void weakFiveGVanishingWarnsButAStrongOneDoesNot() {
        LayerWatch weak = new LayerWatch();
        for (int t = 0; t < 4; t++) weak.update(t, -95.0, -107.0);
        weak.update(4.0, -95.0, Double.NaN);
        weak.update(5.0, -95.0, Double.NaN);
        assertTrue(weak.warning(5.0));

        LayerWatch strong = new LayerWatch();
        for (int t = 0; t < 4; t++) strong.update(t, -95.0, -85.0);
        strong.update(4.0, -95.0, Double.NaN);
        strong.update(5.0, -95.0, Double.NaN);
        assertFalse(strong.warning(5.0));
    }

    @Test
    public void noHintWhenFourGIsAlreadyWeak() {
        LayerWatch w = new LayerWatch();
        w.update(0.0, -115.0, -95.0);
        w.update(1.0, -116.0, -103.0);
        assertFalse(w.warning(1.0));
    }
}
