package io.jenkins.plugins.batchcontrol.policy;

import hudson.model.Job;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.Approvers;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.List;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.springframework.security.access.AccessDeniedException;

/**
 * Approver eligibility rules (SPEC items 2 and 3), shared by {@link RunRequestService} and
 * {@code GrantRequestService}. Two different failure families are used on purpose:
 *
 * <ul>
 *   <li>{@link IllegalArgumentException} for designation-time validation (bad input on request
 *       creation or approver change);</li>
 *   <li>{@link AccessDeniedException} for decision-time refusals (the caller may not decide
 *       this request), which the web layer surfaces as 403.</li>
 * </ul>
 */
@Restricted(NoExternalUse.class)
public final class ApprovalPolicy {

    private ApprovalPolicy() {
    }

    /** Whether the user id is on the global approver list. */
    public static boolean isListedApprover(String userId) {
        return userId != null
                && BatchControlGlobalConfiguration.get().getApprovers().contains(userId);
    }

    /** The job-level approver restriction, or an empty list when the job does not narrow it. */
    public static List<String> jobApproverRestriction(Job<?, ?> job) {
        if (job != null) {
            BatchControlJobProperty property = job.getProperty(BatchControlJobProperty.class);
            if (property != null) {
                return property.getJobApprovers();
            }
        }
        return List.of();
    }

    /** Whether the current caller is an administrator (Overall/Administer). */
    public static boolean callerIsAdmin() {
        return Jenkins.get().hasPermission(Jenkins.ADMINISTER);
    }

    /**
     * Validates a single-approver designation (pre D-37 form).
     *
     * @see #checkDesignation(String, List, Job)
     */
    public static void checkDesignation(String requester, String approver, Job<?, ?> job) {
        checkDesignation(requester, Approvers.of(approver), job);
    }

    /**
     * Validates an approver-set designation at creation / change time (SPEC item 3, D-37): at
     * least one approver, and every member is on the global list, allowed by the job-level
     * restriction and not the requester (administrator exception per the self-approval policy).
     *
     * @param requester the requesting user id (the current caller)
     * @param approvers the designated approver ids
     * @param job the target job, used for the optional job-level approver restriction
     * @return the normalized set (trimmed, de-duplicated, designation order)
     * @throws IllegalArgumentException if the designation violates the policy
     */
    public static List<String> checkDesignation(String requester, List<String> approvers, Job<?, ?> job) {
        List<String> normalized = Approvers.normalize(approvers);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("At least one approver must be designated.");
        }
        List<String> restriction = jobApproverRestriction(job);
        for (String approver : normalized) {
            if (!isListedApprover(approver)) {
                throw new IllegalArgumentException(
                        "User '" + approver + "' is not on the configured approver list.");
            }
            if (!restriction.isEmpty() && !restriction.contains(approver)) {
                throw new IllegalArgumentException("User '" + approver
                        + "' is not an allowed approver for this job.");
            }
            if (approver.equals(requester) && !selfApprovalAllowedForCaller()) {
                throw new IllegalArgumentException(
                        "You cannot designate yourself as an approver of your own request.");
            }
        }
        return normalized;
    }

    /**
     * Checks that the current caller may decide (approve/reject) the given request
     * (SPEC item 3: list membership AND the Approve permission at decision time,
     * SPEC item 2: admin self-approval policy, D-37: any member of the designated set).
     *
     * @return {@code true} when this decision is a self-approval (requester == decider)
     * @throws AccessDeniedException if the caller may not decide the request
     */
    public static boolean checkDecision(RunRequest request) {
        return checkDecision(request.getId(), request.getRequester(), request.getApprovers());
    }

    /**
     * Request-type-agnostic decision check, shared by run requests and grant requests
     * (SPEC item 8 reuses the SPEC item 3 approver rules). The caller must be a member of the
     * designated set (D-29 exclusivity applied to the set, D-37); the permission, list and
     * self-approval checks apply to the deciding user.
     *
     * @param requestId the request id (for error messages only)
     * @param requester the request's requester id
     * @param designatedApprovers the request's currently designated approver set
     * @return {@code true} when this decision is a self-approval (requester == decider)
     * @throws AccessDeniedException if the caller may not decide the request
     */
    public static boolean checkDecision(String requestId, String requester, List<String> designatedApprovers) {
        String caller = Jenkins.getAuthentication2().getName();
        if (designatedApprovers == null || !designatedApprovers.contains(caller)) {
            throw new AccessDeniedException(
                    "Only a designated approver may decide request " + requestId + ".");
        }
        // Both conditions are required at decision time: permission AND list membership.
        Jenkins.get().checkPermission(BatchControlPermissions.APPROVE);
        if (!isListedApprover(caller)) {
            throw new AccessDeniedException(
                    "User '" + caller + "' is no longer on the configured approver list.");
        }
        boolean selfApproval = caller.equals(requester);
        if (selfApproval && !selfApprovalAllowedForCaller()) {
            throw new AccessDeniedException(
                    "Separation of duties: you may not decide your own request.");
        }
        return selfApproval;
    }

    /** Self-designation/self-approval is only open to administrators, and only when enabled. */
    private static boolean selfApprovalAllowedForCaller() {
        return BatchControlGlobalConfiguration.get().isAllowAdminSelfApproval() && callerIsAdmin();
    }
}
