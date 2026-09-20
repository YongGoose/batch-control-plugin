package io.jenkins.plugins.batchcontrol.ui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Job;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import java.util.List;
import jenkins.model.Jenkins;

/**
 * Computes the approver candidates shown in request forms: the global approver list intersected
 * with the job-level restriction when one is configured (SPEC items 3 and 5).
 *
 * <p>The current user is filtered out because a requester may not approve their own request —
 * except for administrators when {@code allowAdminSelfApproval} is on (SPEC item 2). This is
 * display-side convenience only; {@code RunRequestService} re-validates eligibility on
 * submission.
 */
public final class ApproverOptions {

    private ApproverOptions() {
    }

    /**
     * @param job the target job, or null when it no longer exists (falls back to the global list)
     * @return eligible approver ids, possibly empty (the views must then warn "no approvers")
     */
    public static List<String> forJob(@CheckForNull Job<?, ?> job) {
        BatchControlGlobalConfiguration configuration = BatchControlGlobalConfiguration.get();
        List<String> options = configuration.getApprovers();
        if (job != null) {
            BatchControlJobProperty property = job.getProperty(BatchControlJobProperty.class);
            if (property != null && !property.getJobApprovers().isEmpty()) {
                options.retainAll(property.getJobApprovers());
            }
        }
        String currentUser = Jenkins.getAuthentication2().getName();
        boolean selfAllowed = configuration.isAllowAdminSelfApproval()
                && Jenkins.get().hasPermission(Jenkins.ADMINISTER);
        if (!selfAllowed) {
            options.remove(currentUser);
        }
        return options;
    }
}
