package io.github.c0ldsheep.sanket.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GuardPolicyTest {
    @Test
    public void alarmProtectsIsHeldAndThenClears() {
        GuardPolicy g = new GuardPolicy();
        GuardPolicy.Inputs in = new GuardPolicy.Inputs();
        in.alarm = true;
        assertTrue(g.update(0.0, in));
        assertEquals(GuardPolicy.Level.PROTECT, g.level());
        assertEquals("Signal falling fast", g.reasons().get(0));
        in.alarm = false;
        assertFalse(g.update(10.0, in));
        assertEquals(GuardPolicy.Level.PROTECT, g.level());
        assertTrue(g.update(20.0, in));
        assertEquals(GuardPolicy.Level.CLEAR, g.level());
    }

    @Test
    public void losingAndRegainingSignalTakeEffectAtOnce() {
        GuardPolicy g = new GuardPolicy();
        GuardPolicy.Inputs in = new GuardPolicy.Inputs();
        in.alarm = true;
        g.update(0.0, in);
        in.alarm = false;
        in.connected = false;
        g.update(1.0, in);
        assertEquals(GuardPolicy.Level.OFFLINE, g.level());
        in.connected = true;
        g.update(2.0, in);
        assertEquals(GuardPolicy.Level.CLEAR, g.level());
    }

    @Test
    public void earlyHintsOnlyWatchAndAKnownZoneProtects() {
        GuardPolicy g = new GuardPolicy();
        GuardPolicy.Inputs in = new GuardPolicy.Inputs();
        in.movement = VerticalMotion.Movement.UP_FAST;
        in.health = LinkHealth.State.NO_INTERNET;
        g.update(0.0, in);
        assertEquals(GuardPolicy.Level.WATCH, g.level());
        assertEquals(2, g.reasons().size());
        in.atKnownZone = true;
        g.update(1.0, in);
        assertEquals(GuardPolicy.Level.PROTECT, g.level());
    }
}
