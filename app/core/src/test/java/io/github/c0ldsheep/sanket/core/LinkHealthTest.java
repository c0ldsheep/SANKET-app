package io.github.c0ldsheep.sanket.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LinkHealthTest {
    @Test
    public void connectedButUnvalidatedBecomesNoInternetAfterTheGrace() {
        LinkHealth h = new LinkHealth();
        assertEquals(LinkHealth.State.NO_NETWORK, h.state(0.0));
        h.onNetwork(0.0, true, false);
        assertEquals(LinkHealth.State.OK, h.state(4.0));
        assertEquals(LinkHealth.State.NO_INTERNET, h.state(5.0));
        h.onNetwork(6.0, true, true);
        assertEquals(LinkHealth.State.OK, h.state(6.0));
    }

    @Test
    public void steadyLatencyIsFineAndRisingLatencyIsSlow() {
        LinkHealth h = new LinkHealth();
        h.onNetwork(0.0, true, true);
        for (int t = 0; t < 10; t++) h.onLatency(t, 150.0);
        assertEquals(LinkHealth.State.OK, h.state(10.0));
        for (int t = 10; t < 22; t++) h.onLatency(t, 150.0 * Math.exp(0.3 * (t - 9)));
        assertEquals(LinkHealth.State.SLOW, h.state(22.0));
    }
}
