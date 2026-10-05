package io.github.c0ldsheep.sanket.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ZoneMemoryTest {
    private static final long H = 3_600_000L;
    private static final long DAY = 24L * H;
    private static final double LAT = 19.03301;
    private static final double LON = 73.02972;
    private static final String JIO = "405874";

    @Test
    public void fakeVagueOrUnprovenReportsAreRefused() {
        ZoneMemory m = new ZoneMemory();
        assertEquals(ZoneMemory.Result.REJECTED_MOCK, m.recordLoss(0, LAT, LON, 10, true, JIO, ZoneMemory.Kind.BASEMENT, true));
        assertEquals(ZoneMemory.Result.REJECTED_ACCURACY, m.recordLoss(0, LAT, LON, 120, false, JIO, ZoneMemory.Kind.BASEMENT, true));
        assertEquals(ZoneMemory.Result.REJECTED_ACCURACY, m.recordLoss(0, 0.0, 0.0, 10, false, JIO, ZoneMemory.Kind.BASEMENT, true));
        assertEquals(ZoneMemory.Result.REJECTED_NO_EVIDENCE, m.recordLoss(0, LAT, LON, 10, false, JIO, ZoneMemory.Kind.BASEMENT, false));
        assertTrue(m.zones().isEmpty());
    }

    @Test
    public void twoSeparateVisitsMakeAZoneKnownForThatOperatorOnly() {
        ZoneMemory m = new ZoneMemory();
        assertEquals(ZoneMemory.Result.ADDED, m.recordLoss(0, LAT, LON, 10, false, JIO, ZoneMemory.Kind.LIFT, true));
        assertEquals(ZoneMemory.Result.SAME_VISIT, m.recordLoss(5 * 60_000L, LAT, LON, 10, false, JIO, ZoneMemory.Kind.LIFT, true));
        assertNull(m.nearestKnown(H, LAT, LON, JIO, 60));
        assertEquals(ZoneMemory.Result.UPDATED, m.recordLoss(H, LAT + 0.0001, LON, 12, false, JIO, ZoneMemory.Kind.LIFT, true));
        ZoneMemory.Zone z = m.nearestKnown(H, LAT, LON, JIO, 60);
        assertNotNull(z);
        assertEquals(ZoneMemory.Kind.LIFT, z.kind());
        assertNull(m.nearestKnown(H, LAT, LON, "40445", 60));
        assertNull(m.nearestKnown(H, LAT + 0.01, LON, JIO, 60));
    }

    @Test
    public void visitsThatKeepSignalRetireAZone() {
        ZoneMemory m = new ZoneMemory();
        m.recordLoss(0, LAT, LON, 10, false, JIO, ZoneMemory.Kind.BASEMENT, true);
        m.recordLoss(H, LAT, LON, 10, false, JIO, ZoneMemory.Kind.BASEMENT, true);
        ZoneMemory.Zone z = m.zones().get(0);
        for (int i = 0; i < 4; i++) m.recordPass(2 * H, z);
        assertFalse(z.known(2 * H));
        assertEquals(0, m.purge(2 * H));
        for (int i = 0; i < 8; i++) m.recordPass(3 * H, z);
        assertEquals(1, m.purge(3 * H));
        assertTrue(m.zones().isEmpty());
    }

    @Test
    public void oldZonesFadeAway() {
        ZoneMemory m = new ZoneMemory();
        m.recordLoss(0, LAT, LON, 10, false, JIO, ZoneMemory.Kind.BASEMENT, true);
        m.recordLoss(H, LAT, LON, 10, false, JIO, ZoneMemory.Kind.BASEMENT, true);
        assertTrue(m.zones().get(0).known(H));
        assertFalse(m.zones().get(0).known(H + 31 * DAY));
        assertEquals(1, m.purge(H + 150 * DAY));
    }

    @Test
    public void notesLosePhoneNumbersAndOutagesGiveATypicalTime() {
        ZoneMemory m = new ZoneMemory();
        m.recordLoss(0, LAT, LON, 10, false, JIO, ZoneMemory.Kind.BASEMENT, true);
        ZoneMemory.Zone z = m.zones().get(0);
        m.setNote(z, "  Use gate 3, call 98200 12345 if closed ");
        assertEquals("Use gate 3, call [number removed] if closed", z.note());
        assertEquals(-1, z.typicalOutageS());
        m.recordOutage(z, 120);
        m.recordOutage(z, 60);
        m.recordOutage(z, 300);
        assertEquals(120, z.typicalOutageS());
        assertEquals(19.0330, z.lat(), 1e-9);
    }
}
