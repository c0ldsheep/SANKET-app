package io.github.c0ldsheep.sanket.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OutageTrackerTest {
    @Test
    public void briefDropoutStartsAndEndsWithoutBeingConfirmed() {
        OutageTracker o = new OutageTracker();
        assertEquals(OutageTracker.Event.NONE, o.tick(0, false));
        assertEquals(OutageTracker.Event.STARTED, o.tick(1, false));
        assertEquals(OutageTracker.Event.NONE, o.tick(2, false));
        assertEquals(OutageTracker.Event.NONE, o.tick(3, true));
        assertEquals(OutageTracker.Event.ENDED, o.tick(4, true));
        assertFalse(o.confirmed());
        assertEquals(1.0, o.since(), 0.0);
    }

    @Test
    public void longOutageIsConfirmedOnceThenEnds() {
        OutageTracker o = new OutageTracker();
        o.tick(0, false);
        assertEquals(OutageTracker.Event.STARTED, o.tick(1, false));
        for (int t = 2; t < 11; t++) assertEquals(OutageTracker.Event.NONE, o.tick(t, false));
        assertEquals(OutageTracker.Event.CONFIRMED, o.tick(11, false));
        assertEquals(OutageTracker.Event.NONE, o.tick(12, false));
        o.tick(13, true);
        assertEquals(OutageTracker.Event.ENDED, o.tick(14, true));
        assertTrue(o.confirmed());
    }

    @Test
    public void resetForgetsAnOutageInProgress() {
        OutageTracker o = new OutageTracker();
        o.tick(0, false);
        o.tick(1, false);
        assertTrue(o.lost());
        o.reset();
        assertFalse(o.lost());
        assertEquals(OutageTracker.Event.NONE, o.tick(2, true));
    }
}
