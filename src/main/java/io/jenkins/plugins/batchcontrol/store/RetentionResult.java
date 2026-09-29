package io.jenkins.plugins.batchcontrol.store;

import java.util.List;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * What one retention pass over the XML entities deleted (#13).
 *
 * @param runRequests   closed run requests deleted
 * @param grantRequests closed grant requests deleted
 * @param grantIds      ids of the ended grants deleted
 * @param activationRequests closed activation requests deleted (#15)
 */
@Restricted(NoExternalUse.class)
public record RetentionResult(int runRequests, int grantRequests, List<String> grantIds,
                              int activationRequests) {

    public RetentionResult {
        grantIds = List.copyOf(grantIds);
    }

    /** The form without activation requests. */
    public RetentionResult(int runRequests, int grantRequests, List<String> grantIds) {
        this(runRequests, grantRequests, grantIds, 0);
    }

    /** Whether anything was deleted. */
    public boolean isEmpty() {
        return runRequests == 0 && grantRequests == 0 && grantIds.isEmpty() && activationRequests == 0;
    }
}
