package io.github.c0ldsheep.sanket.core;

/**
 * Turns a signal reading in dBm into the words and bars people already know from their phone.
 *
 * <p>The 4G bands follow Android's default bar thresholds (-98, -108 and -118 dBm), with the last
 * step at SANKET's own no-service line, -124 dBm. 5G SS-RSRP uses Android's default 5G thresholds.
 */
public final class SignalWords {
    /** How strong the signal is, from best to none. */
    public enum Strength { STRONG, GOOD, FAIR, WEAK, ALMOST_NONE, NONE }

    private SignalWords() {}

    /** Strength of a 4G RSRP reading; NaN means no reading. */
    public static Strength lte(double rsrp) {
        if (!SanketDetector.validRsrp(rsrp)) return Strength.NONE;
        if (rsrp >= -98.0) return Strength.STRONG;
        if (rsrp >= -108.0) return Strength.GOOD;
        if (rsrp >= -118.0) return Strength.FAIR;
        if (rsrp > -124.0) return Strength.WEAK;
        return Strength.ALMOST_NONE;
    }

    /** Strength of a 5G SS-RSRP reading; NaN means no reading. */
    public static Strength nr(double ssRsrp) {
        if (!(ssRsrp >= -140.0 && ssRsrp <= -44.0)) return Strength.NONE;
        if (ssRsrp >= -80.0) return Strength.STRONG;
        if (ssRsrp >= -90.0) return Strength.GOOD;
        if (ssRsrp >= -100.0) return Strength.FAIR;
        if (ssRsrp >= -110.0) return Strength.WEAK;
        return Strength.ALMOST_NONE;
    }

    /** Strength of a 2G or 3G reading in dBm, on Android's default GSM steps; NaN means no reading. */
    public static Strength legacy(double dbm) {
        if (!(dbm >= -140.0 && dbm < 0.0)) return Strength.NONE;
        if (dbm >= -89.0) return Strength.STRONG;
        if (dbm >= -97.0) return Strength.GOOD;
        if (dbm >= -103.0) return Strength.FAIR;
        if (dbm >= -109.0) return Strength.WEAK;
        return Strength.ALMOST_NONE;
    }

    /** Signal bars out of four. */
    public static int bars(Strength s) {
        switch (s) {
            case STRONG:
                return 4;
            case GOOD:
                return 3;
            case FAIR:
                return 2;
            case WEAK:
                return 1;
            default:
                return 0;
        }
    }
}
