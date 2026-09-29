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
 */
@Restricted(NoExternalUse.class)
public record RetentionResult(int runRequests, int grantRequests, List<String> grantIds) {

    public RetentionResult {
        grantIds = List.copyOf(grantIds);
    }

    /** Whether anything was deleted. */
    public boolean isEmpty() {
        return runRequests == 0 && grantRequests == 0 && grantIds.isEmpty();
    }
}
