package io.jenkins.plugins.batchcontrol.store;

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Per-month run counters for the history summary, maintained incrementally from the
 * append-only {@code runs/YYYY-MM.jsonl} bucket: a summary request only reads the bytes appended
 * since the previous one.
 *
 * @param runs     run records in the month
 * @param success  runs with result {@code SUCCESS}
 * @param failure  runs with result {@code FAILURE}
 * @param unstable runs with result {@code UNSTABLE}
 */
@Restricted(NoExternalUse.class)
public record RunMonthStats(long runs, long success, long failure, long unstable) {

    static final RunMonthStats EMPTY = new RunMonthStats(0, 0, 0, 0);

    RunMonthStats plus(String result) {
        return new RunMonthStats(runs + 1,
                success + ("SUCCESS".equals(result) ? 1 : 0),
                failure + ("FAILURE".equals(result) ? 1 : 0),
                unstable + ("UNSTABLE".equals(result) ? 1 : 0));
    }
}
