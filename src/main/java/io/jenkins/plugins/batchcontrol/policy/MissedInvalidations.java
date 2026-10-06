package io.jenkins.plugins.batchcontrol.policy;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * The invalidations an open request missed because its file could not be read (or its end could not
 * be written) when they happened: its job was renamed, moved or deleted (D-21, SPEC 6a), or another
 * request of its job was approved (S-13-07). Which job an unreadable request names is unknown, so
 * each missed invalidation is kept with the job it was about and checked once the request can be read
 * again: the policy service then ends the request INVALIDATED if the invalidation applies to it,
 * before it can be approved or run. Kept in memory only (derived state); a restart forgets them, and
 * a request that is readable at the next start is an ordinary open request again.
 *
 * <p>Bounded: an id keeps at most {@value #MAX_PER_ID} missed invalidations; beyond that they are
 * folded into one that applies to every job, so the request is invalidated whatever job it names
 * (fail safe).
 */
@Restricted(NoExternalUse.class)
final class MissedInvalidations {

    /** The missed invalidations kept per request before they are folded into one for any job. */
    static final int MAX_PER_ID = 64;

    /**
     * One missed invalidation: of the requests on {@code jobFullName} (and, with
     * {@code withDescendants}, of the items below it; {@code null} for any job), for {@code reason}.
     */
    private record Missed(@CheckForNull String jobFullName, boolean withDescendants, String reason) {

        boolean appliesTo(@CheckForNull String job) {
            if (jobFullName == null) {
                return true;
            }
            return job != null && (job.equals(jobFullName) || withDescendants && job.startsWith(jobFullName + "/"));
        }
    }

    private final Map<String, List<Missed>> byId = new ConcurrentHashMap<>();

    /** Request {@code id} may have missed the invalidation of the requests on {@code jobFullName}. */
    void add(String id, String jobFullName, boolean withDescendants, String reason) {
        byId.compute(id, (key, missed) -> {
            List<Missed> next = missed == null ? new ArrayList<>() : new ArrayList<>(missed);
            if (next.size() >= MAX_PER_ID) {
                return List.of(new Missed(null, true, reason));
            }
            next.add(new Missed(jobFullName, withDescendants, reason));
            return List.copyOf(next);
        });
    }

    /**
     * The reason of the first missed invalidation of request {@code id} that applies to a request on
     * {@code jobFullName}, or {@code null}.
     */
    @CheckForNull
    String reasonFor(String id, @CheckForNull String jobFullName) {
        List<Missed> missed = byId.get(id);
        if (missed == null) {
            return null;
        }
        for (Missed m : missed) {
            if (m.appliesTo(jobFullName)) {
                return m.reason();
            }
        }
        return null;
    }

    /** Whether request {@code id} has missed any invalidation. */
    boolean contains(String id) {
        return byId.containsKey(id);
    }

    /** Forgets request {@code id} (applied, or it turned out not to concern it). */
    void forget(String id) {
        byId.remove(id);
    }

    /** The ids of the requests that missed an invalidation, as a copy. */
    Set<String> ids() {
        return Set.copyOf(byId.keySet());
    }

    boolean isEmpty() {
        return byId.isEmpty();
    }
}
