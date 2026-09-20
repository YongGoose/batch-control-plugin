package io.jenkins.plugins.batchcontrol.store;

import java.time.Clock;
import java.time.Instant;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Single source of time for the whole plugin. All time reads go through this holder so tests
 * can install a fixed {@link Clock} instead of manipulating the system clock or waiting on timers.
 */
@Restricted(NoExternalUse.class)
public final class BatchClock {

    private static volatile Clock clock = Clock.systemDefaultZone();

    private BatchClock() {
    }

    /** The current clock (system default zone unless a test installed another one). */
    public static Clock clock() {
        return clock;
    }

    /** Current instant according to {@link #clock()}. */
    public static Instant now() {
        return clock.instant();
    }

    /** Installs a fixed/offset clock for tests. Always pair with {@link #reset()}. */
    public static void setForTest(Clock testClock) {
        if (testClock == null) {
            throw new IllegalArgumentException("testClock must not be null");
        }
        clock = testClock;
    }

    /** Restores the system default zone clock. */
    public static void reset() {
        clock = Clock.systemDefaultZone();
    }
}
