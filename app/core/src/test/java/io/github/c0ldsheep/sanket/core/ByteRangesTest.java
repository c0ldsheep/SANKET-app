package io.github.c0ldsheep.sanket.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ByteRangesTest {
    @Test
    public void readsStartAndTotal() {
        assertEquals(100L, ByteRanges.first("bytes 100-999/1000"));
        assertEquals(1000L, ByteRanges.total("bytes 100-999/1000"));
        assertEquals(0L, ByteRanges.first("Bytes 0-9/*"));
        assertEquals(-1L, ByteRanges.total("bytes 0-9/*"));
    }

    @Test
    public void rejectsAnythingElse() {
        assertEquals(-1L, ByteRanges.first(null));
        assertEquals(-1L, ByteRanges.first("bytes */1000"));
        assertEquals(1000L, ByteRanges.total("bytes */1000"));
        assertEquals(-1L, ByteRanges.first("items 0-1/2"));
        assertEquals(-1L, ByteRanges.first("bytes x-9/10"));
    }
}
