package io.github.c0ldsheep.sanket.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.ZoneId;
import org.junit.Test;

public class RideLogTest {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final long T0 = 1_791_788_400_000L;   // 2026-10-12 12:30:00 IST

    @Test
    public void writesTheColumnsTheStudyReads() {
        RideLog log = new RideLog(IST);
        log.start(T0);
        log.add(T0, "lte", -92, -11, -101, Double.NaN, 405874L, 0.0213, false, true, false, "level");
        log.add(T0 + 1_000L, "none", Double.NaN, Double.NaN, Double.NaN, Double.NaN, SanketDetector.UNKNOWN_CELL,
                Double.NaN, true, false, false, "down");
        String[] lines = log.csv().split("\n");
        assertEquals(RideLog.HEADER, lines[0]);
        assertEquals("0,2026-10-12 12:30:00,lte,-92,-11,-101,,405874,0.021,0,1,0,level,", lines[1]);
        assertEquals("1,2026-10-12 12:30:01,none,,,,,,,1,0,0,down,", lines[2]);
        assertEquals(2, log.rows());
    }

    @Test
    public void aMarkGoesOnTheNextRowOnly() {
        RideLog log = new RideLog(IST);
        log.start(T0);
        log.mark();
        log.add(T0, "lte", -100.5, -14, Double.NaN, Double.NaN, 7L, 0.5, false, true, false, "level");
        log.add(T0 + 1_000L, "lte", -101, -14, Double.NaN, Double.NaN, 7L, 0.5, false, true, false, "level");
        String[] lines = log.csv().split("\n");
        assertTrue(lines[1].endsWith(",mark"));
        assertTrue(lines[1].contains(",-100.5,"));
        assertTrue(lines[2].endsWith(","));
    }

    @Test
    public void secondsCountFromTheStartOnTheWallClock() {
        RideLog log = new RideLog(IST);
        assertFalse(log.started());
        assertEquals(0L, log.seconds(T0));
        log.start(T0);
        log.add(T0 + 2_600L, "lte", -95, -10, Double.NaN, Double.NaN, 1L, 0.0, false, true, false, "level");
        assertTrue(log.csv().split("\n")[1].startsWith("3,"));
        assertEquals(61L, log.seconds(T0 + 61_400L));
    }

    @Test
    public void restoreCarriesOnFromASavedRecording() {
        RideLog log = new RideLog(IST);
        log.start(T0);
        for (int i = 0; i < 3; i++) {
            log.add(T0 + i * 1_000L, "lte", -90 - i, -10, Double.NaN, Double.NaN, 1L, 0.1, false, true, false, "up");
        }
        RideLog back = new RideLog(IST);
        back.restore(T0, log.csv());
        assertEquals(log.csv(), back.csv());
        assertEquals(3, back.rows());
        back.add(T0 + 3_000L, "nr", Double.NaN, Double.NaN, Double.NaN, -98, 2L, 0.1, false, true, false, "level");
        assertEquals(4, back.rows());
        assertTrue(back.csv().endsWith("3,2026-10-12 12:30:03,nr,,,,-98,2,0.100,0,1,0,level,\n"));
    }

    @Test
    public void stopsTakingRowsWhenFull() {
        RideLog log = new RideLog(IST);
        log.start(T0);
        for (int i = 0; i < RideLog.MAX_ROWS + 5; i++) {
            log.add(T0 + i * 1_000L, "lte", -90, -10, Double.NaN, Double.NaN, 1L, 0.0, false, true, false, "level");
        }
        assertTrue(log.full());
        assertEquals(RideLog.MAX_ROWS, log.rows());
        log.clear();
        assertFalse(log.started());
        assertEquals(RideLog.HEADER + "\n", log.csv());
    }
}
