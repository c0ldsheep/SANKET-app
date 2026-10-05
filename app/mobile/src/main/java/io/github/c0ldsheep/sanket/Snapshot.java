package io.github.c0ldsheep.sanket;

import io.github.c0ldsheep.sanket.core.GuardPolicy;
import io.github.c0ldsheep.sanket.core.LinkHealth;
import io.github.c0ldsheep.sanket.core.VerticalMotion;
import java.util.Collections;
import java.util.List;

/**
 * What the screen and the notification show. {@link Engine} fills a new instance once per tick and
 * publishes it through a volatile field; after that nobody writes to it.
 */
final class Snapshot {
    /** Which radio feeds the detector on the mobile-data SIM. OTHER is 2G or 3G: connected, nothing to predict. */
    enum Tech { LTE, NR, OTHER, NONE }

    /** A reason the phone is offline on purpose, which is not a signal loss. */
    enum Offline { NONE, AIRPLANE, NO_SIM }

    /** One SIM, as the screen shows it. Readings the phone did not report are NaN. */
    static final class Sim {
        final String name;
        final boolean data;
        final double lteDbm;
        final double nrDbm;
        final double legacyDbm;
        final String legacyTech;

        Sim(String name, boolean data, double lteDbm, double nrDbm, double legacyDbm, String legacyTech) {
            this.name = name;
            this.data = data;
            this.lteDbm = lteDbm;
            this.nrDbm = nrDbm;
            this.legacyDbm = legacyDbm;
            this.legacyTech = legacyTech;
        }
    }

    /** The service is running: protection, downloads only, or the demo. */
    boolean running;
    /** The user turned protection on. */
    boolean protecting;
    boolean demo;
    double demoSeconds = Double.NaN;
    GuardPolicy.Level level = GuardPolicy.Level.CLEAR;
    List<String> reasons = Collections.emptyList();
    double risk;
    double horizonS = 10.0;
    double timeToLossS = Double.POSITIVE_INFINITY;
    Offline offline = Offline.NONE;
    Tech tech = Tech.NONE;
    boolean onWifi;
    boolean lowPower;
    List<Sim> sims = Collections.emptyList();
    LinkHealth.State health = LinkHealth.State.OK;
    double latencyMs = Double.NaN;
    /** Lifts can be sensed: by the barometer, or by the accelerometer on phones without one. */
    boolean motionSensed;
    /** Sensed by the accelerometer, which knows lift rides but not ramps or stairs. */
    boolean liftOnly;
    VerticalMotion.Movement movement = VerticalMotion.Movement.LEVEL;
    double verticalSpeed;
    /** Seconds between this phone's signal refreshes, or NaN while still learning. */
    double refreshS = Double.NaN;
    /** A known dead zone here, described, or "" when there is none. */
    String place = "";
    String placeNote = "";
    /** The other SIM's name when it has clearly better signal here, or "". */
    String betterSim = "";
    /** Minutes in the prepared offline notice, or 0 when none is ready. */
    int noticeBackMin;

    static Snapshot idle() { return new Snapshot(); }
}
