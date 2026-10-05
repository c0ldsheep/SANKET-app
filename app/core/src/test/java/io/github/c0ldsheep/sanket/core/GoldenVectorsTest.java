package io.github.c0ldsheep.sanket.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class GoldenVectorsTest {
    @Test
    public void javaDetectorMatchesThePythonReferenceTickForTick() throws Exception {
        String text;
        try (InputStream in = GoldenVectorsTest.class.getResourceAsStream("/sanket_golden.json")) {
            assertNotNull("golden file missing", in);
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int[] r = GoldenCheck.run(text);
        assertEquals("traces", 13, r[0]);
        assertEquals("ticks", 1737, r[1]);
        assertEquals("alarms", 10, r[2]);
        assertEquals("mismatches", 0, r[3]);
    }
}
