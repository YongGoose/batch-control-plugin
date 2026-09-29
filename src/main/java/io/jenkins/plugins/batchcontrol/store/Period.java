package io.jenkins.plugins.batchcontrol.store;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.time.Instant;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * The time range of a page query (security-10 S-03): only records inside it count toward the
 * query's record cap. Lines outside it are recognised from their raw timestamp and skipped
 * without being parsed; reading stops at the first line appended before {@code from}.
 *
 * @param from        inclusive start, or {@code null} for no lower bound
 * @param toExclusive exclusive end, or {@code null} for no upper bound
 */
@Restricted(NoExternalUse.class)
public record Period(@CheckForNull Instant from, @CheckForNull Instant toExclusive) {

    /** No bounds: every record counts toward the cap. */
    public static final Period ALL = new Period(null, null);

    boolean isBefore(long epochMillis) {
        return from != null && epochMillis < from.toEpochMilli();
    }

    boolean isAfter(long epochMillis) {
        return toExclusive != null && epochMillis >= toExclusive.toEpochMilli();
    }
}
