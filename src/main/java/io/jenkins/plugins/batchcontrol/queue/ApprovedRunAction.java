package io.jenkins.plugins.batchcontrol.queue;

import hudson.model.Action;
import hudson.model.Queue;
import java.util.List;
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
 *
 * <p>security-26 S-26-01: it is a {@link Queue.QueueAction} that always asks for its own queue
 * item, so {@code Queue.scheduleInternal} never folds an approved submission into an item of the
 * same job that is already waiting (which would spend the ticket and lose the marker), and never
 * folds another submission into it. This does not open a second run: every submission still
 * passes the decision handlers first, and the gate claims the request's single consumption
 * ticket there, so a second submission carrying the same marker is refused before this is asked.
 */
@Restricted(NoExternalUse.class)
public class ApprovedRunAction implements Action, Queue.QueueAction {

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

    /** Always a separate queue item (S-26-01). */
    @Override
    public boolean shouldSchedule(List<Action> actions) {
        return true;
    }
}
