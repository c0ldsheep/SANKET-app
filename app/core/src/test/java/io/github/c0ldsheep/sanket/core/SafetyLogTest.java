package io.github.c0ldsheep.sanket.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SafetyLogTest {
    @Test
    public void chainVerifiesAndCatchesAnEditedLine() {
        SafetyLog log = new SafetyLog();
        log.append(1_000L, "PROTECT", 19.03301, 73.02972, "405874", -112.0, "Signal falling fast");
        log.append(5_000L, "LOSS", 19.03301, 73.02972, "405874", Double.NaN, "basement");
        log.append(95_000L, "RECOVER", Double.NaN, Double.NaN, "405874", -98.0, "back after 90 s");
        assertTrue(log.verify());

        SafetyLog copy = new SafetyLog();
        for (SafetyLog.Entry e : log.entries()) {
            String detail = e.event.equals("LOSS") ? "edited" : e.detail;
            copy.restore(e.timeMs, e.event, e.lat, e.lon, e.operator, e.rsrp, detail, e.prevHash, e.hash);
        }
        assertFalse(copy.verify());
    }

    @Test
    public void droppingOldEntriesKeepsTheRestVerifiable() {
        SafetyLog log = new SafetyLog();
        for (int i = 0; i < 5; i++) log.append(i * 1_000L, "LOSS", 19.0, 73.0, "405874", -120.0, "");
        assertEquals(2, log.dropBefore(2_000L));
        assertEquals(3, log.entries().size());
        assertTrue(log.verify());
        assertTrue(log.export().contains("Chain check: intact"));
    }
}
