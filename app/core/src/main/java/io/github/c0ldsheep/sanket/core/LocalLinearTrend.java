package io.github.c0ldsheep.sanket.core;

/**
 * Kalman filter with state [level, rate] and white-acceleration process noise q (units^2/s^3).
 * The detector uses it for RSRP; the app also uses it for air pressure and response times.
 */
final class LocalLinearTrend {
    final double measVar, q, rate0Var, gate;
    double level, rate, p00, p01, p11, t;
    boolean hasT;
    int n;

    LocalLinearTrend(double measSd, double q, double rate0Sd, double gate) {
        if (!(measSd > 0) || !(q > 0) || !(rate0Sd > 0)) throw new IllegalArgumentException("bad filter settings");
        this.measVar = measSd * measSd; this.q = q; this.rate0Var = rate0Sd * rate0Sd; this.gate = gate;
        reset();
    }

    void reset() { level = rate = p00 = p01 = p11 = 0.0; hasT = false; n = 0; }

    boolean ready() { return n > 0; }

    void init(double tt, double z) { level = z; rate = 0.0; p00 = measVar; p01 = 0.0; p11 = rate0Var; t = tt; hasT = true; n = 1; }

    void predictTo(double tt) {
        if (!hasT) return;
        double dt = tt - t;
        if (dt <= 0.0) return;
        double a00 = p00, a01 = p01, a11 = p11;
        level = level + rate * dt;
        p00 = a00 + 2.0 * dt * a01 + dt * dt * a11 + q * dt * dt * dt / 3.0;
        p01 = a01 + dt * a11 + q * dt * dt / 2.0;
        p11 = a11 + q * dt;
        t = tt;
    }

    void update(double tt, double z) {
        if (n == 0) { init(tt, z); return; }
        predictTo(tt);
        double r = measVar;
        double s = p00 + r;
        double y = z - level;
        if (gate > 0.0) {
            double nis = y * y / s;
            double g2 = gate * gate;
            if (nis > g2) { r = r * nis / g2; s = p00 + r; }
        }
        double k0 = p00 / s, k1 = p01 / s;
        double a00 = p00, a01 = p01, a11 = p11;
        level = level + k0 * y;
        rate = rate + k1 * y;
        p11 = a11 - k1 * a01;
        p01 = (1.0 - k0) * a01;
        p00 = (1.0 - k0) * a00;
        n += 1;
    }
}
