package io.jenkins.plugins.batchcontrol.queue;

import hudson.model.Action;
import java.util.Objects;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Invisible marker action that authorizes exactly one queue submission for an approved run
 * request (D-23). The marker itself carries only the request id; the queue gate
 * ({@link ApprovalQueueDecisionHandler}) validates it against the stored request (status,
 * target job, expiry) and atomically claims the request's single consumption ticket, so
 * replaying the marker (re-queue, rebuild, another job) is always refused.
 *
 * <p>The action is persisted onto the executed {@code Run}, which is how the request id stays
 * readable on the build afterwards.
 */
@Restricted(NoExternalUse.class)
public class ApprovedRunAction implements Action {

    private final String requestId;

    public ApprovedRunAction(String requestId) {
        this.requestId = Objects.requireNonNull(requestId, "requestId");
    }

    public String getRequestId() {
        return requestId;
    }

    @Override
    public String getIconFileName() {
        return null; // invisible
    }

    @Override
    public String getDisplayName() {
        return null;
    }

    @Override
    public String getUrlName() {
        return null;
    }
}
