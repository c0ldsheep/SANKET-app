package io.github.c0ldsheep.sanket.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BackoffTest {
    @Test
    public void waitsLongerEachTimeUpToTenMinutes() {
        assertEquals(30, Backoff.delayS(0));
        assertEquals(60, Backoff.delayS(1));
        assertEquals(600, Backoff.delayS(4));
        assertEquals(600, Backoff.delayS(40));
        assertEquals(30, Backoff.delayS(-3));
    }

    @Test
    public void retriesOnlyAnswersThatCanRecover() {
        assertTrue(Backoff.retryable(503));
        assertTrue(Backoff.retryable(429));
        assertFalse(Backoff.retryable(403));
        assertFalse(Backoff.retryable(404));
    }

    @Test
    public void readsRetryAfterInSeconds() {
        assertEquals(120, Backoff.retryAfterS("120"));
        assertEquals(600, Backoff.retryAfterS("86400"));
        assertEquals(1, Backoff.retryAfterS("0"));
        assertEquals(-1, Backoff.retryAfterS("Wed, 21 Oct 2026 07:28:00 GMT"));
        assertEquals(-1, Backoff.retryAfterS(null));
    }
}
