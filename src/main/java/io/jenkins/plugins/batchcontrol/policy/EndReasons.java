package io.jenkins.plugins.batchcontrol.policy;

import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * The reason texts of requests that end without a decision (e2e-04 FD-05, D-54). An expired
 * request carries the reason in its decision comment, which the request pages show as "Why it
 * expired"; the same text goes into the EXPIRED notification.
 */
@Restricted(NoExternalUse.class)
public final class EndReasons {

    private EndReasons() {
    }

    /** A pending request passed the pending timeout. */
    public static String pendingExpired() {
        int hours = BatchControlGlobalConfiguration.get().getPendingTimeoutHours();
        return "Expired: not decided within " + hours + (hours == 1 ? " hour." : " hours.");
    }

    /** An approved run request did not start within the approved-run timeout. */
    public static String approvedNotStarted() {
        int minutes = BatchControlGlobalConfiguration.get().getApprovedRunTimeoutMinutes();
        return "Expired: approved but not started within " + minutes + (minutes == 1 ? " minute." : " minutes.");
    }

    /**
     * The decision comment of an expired request: the reason, followed by an approver's earlier
     * comment when there is one (it is kept, not replaced).
     */
    public static String withEarlierComment(String reason, String earlierComment) {
        if (earlierComment == null || earlierComment.trim().isEmpty()) {
            return reason;
        }
        return reason + "\nApproval comment: " + earlierComment.trim();
    }
}
