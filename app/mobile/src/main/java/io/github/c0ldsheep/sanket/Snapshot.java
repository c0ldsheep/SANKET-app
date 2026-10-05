package io.github.c0ldsheep.sanket;

import io.github.c0ldsheep.sanket.core.GuardPolicy;
import java.util.Collections;
import java.util.List;

/**
 * What the screen shows. {@link Engine} fills a new instance once per tick and publishes it
 * through a volatile field; after that nobody writes to it.
 */
final class Snapshot {
    boolean running;
    boolean demo;
    double demoSeconds = Double.NaN;
    GuardPolicy.Level level = GuardPolicy.Level.CLEAR;
    List<String> reasons = Collections.emptyList();
    double risk;
    double horizonS = 10.0;
    double timeToLossS = Double.POSITIVE_INFINITY;
    List<String> details = Collections.emptyList();

    static Snapshot idle() { return new Snapshot(); }
}
