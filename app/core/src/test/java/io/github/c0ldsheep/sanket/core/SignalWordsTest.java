package io.github.c0ldsheep.sanket.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SignalWordsTest {
    @Test
    public void fourGBandsMatchThePhonesBarsAndTheLossLine() {
        assertEquals(SignalWords.Strength.STRONG, SignalWords.lte(-85));
        assertEquals(SignalWords.Strength.GOOD, SignalWords.lte(-100));
        assertEquals(SignalWords.Strength.FAIR, SignalWords.lte(-112));
        assertEquals(SignalWords.Strength.WEAK, SignalWords.lte(-120));
        assertEquals(SignalWords.Strength.ALMOST_NONE, SignalWords.lte(-124));
        assertEquals(SignalWords.Strength.NONE, SignalWords.lte(Double.NaN));
    }

    @Test
    public void fiveGHasItsOwnBandsAndBarsCountDown() {
        assertEquals(SignalWords.Strength.STRONG, SignalWords.nr(-75));
        assertEquals(SignalWords.Strength.WEAK, SignalWords.nr(-105));
        assertEquals(SignalWords.Strength.NONE, SignalWords.nr(Double.NaN));
        assertEquals(4, SignalWords.bars(SignalWords.Strength.STRONG));
        assertEquals(1, SignalWords.bars(SignalWords.Strength.WEAK));
        assertEquals(0, SignalWords.bars(SignalWords.Strength.ALMOST_NONE));
    }

    @Test
    public void twoAndThreeGUseTheGsmSteps() {
        assertEquals(SignalWords.Strength.STRONG, SignalWords.legacy(-80));
        assertEquals(SignalWords.Strength.FAIR, SignalWords.legacy(-100));
        assertEquals(SignalWords.Strength.ALMOST_NONE, SignalWords.legacy(-115));
        assertEquals(SignalWords.Strength.NONE, SignalWords.legacy(Double.NaN));
    }
}
