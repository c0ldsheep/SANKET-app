package io.github.c0ldsheep.sanket.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DeviceProfileTest {
    /** Polls once a second while the modem changes its value every {@code refreshS} seconds. */
    private static DeviceProfile poll(int refreshS, int seconds) {
        DeviceProfile p = new DeviceProfile();
        for (int t = 0; t < seconds; t++) p.onSample(t, -90.0 - ((t / refreshS) % 2), -10.0);
        return p;
    }

    @Test
    public void twoSecondRefreshKeepsTheDefaultTimeout() {
        DeviceProfile p = poll(2, 100);
        assertEquals(2.0, p.refreshIntervalS(), 1e-9);
        assertEquals(4.0, p.heartbeatS(), 1e-9);
        assertEquals(1.4826 / Math.sqrt(2.0), p.noiseDb(), 1e-9);
    }

    @Test
    public void slowPhoneGetsALongerTimeout() {
        DeviceProfile p = poll(5, 200);
        assertEquals(5.0, p.refreshIntervalS(), 1e-9);
        assertEquals(10.0, p.heartbeatS(), 1e-9);
    }

    @Test
    public void estimatesWaitForEnoughDataOrUseSavedOnes() {
        DeviceProfile p = poll(2, 10);
        assertTrue(Double.isNaN(p.refreshIntervalS()));
        assertEquals(4.0, p.heartbeatS(), 1e-9);
        p.restore(3.0, 2.0, 500);
        assertEquals(6.0, p.heartbeatS(), 1e-9);
        assertEquals(500, p.samples());
    }
}
