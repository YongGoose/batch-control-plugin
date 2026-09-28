package io.jenkins.plugins.batchcontrol.action;

import hudson.model.Failure;
import hudson.model.ModelObject;
import hudson.security.ACL;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.ui.ApproverInput;
import io.jenkins.plugins.batchcontrol.ui.ApproverOptions;
import io.jenkins.plugins.batchcontrol.ui.Dates;
import io.jenkins.plugins.batchcontrol.ui.SectionAccess;
import java.io.IOException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

/**
 * One grant request at {@code /batch-control/grants/<id>/}: detail view plus the four
 * state-changing POST endpoints ({@code approve}, {@code reject}, {@code cancel},
 * {@code changeApprover}).
 *
 * <p>Every endpoint is {@code @RequirePOST} (GET never changes state) and performs its permission
 * check before delegating; all business rules (approver eligibility, comment requirements,
 * requester-or-Manage cancel rule, atomic state transitions, grant creation on approval) are
 * enforced by {@link GrantRequestService} — this class contains zero state logic.
 */
@Restricted(NoExternalUse.class)
public class GrantRequestItem implements ModelObject {

    private final GrantRequest request;

    GrantRequestItem(GrantRequest request) {
        this.request = request;
    }

    // ---------------------------------------------------------------- view model

    public GrantRequest getRequest() {
        return request;
    }

    public String getId() {
        return request.getId();
    }

    @Override
    public String getDisplayName() {
        return "Grant Request " + request.getId();
    }

    /** Jelly helper: human-readable timestamp. */
    public String format(Instant instant) {
        return Dates.format(instant);
    }

    /** Jelly helper: comma-joined action list ("CREATE, CONFIGURE"). */
    public String join(Collection<?> items) {
        return items == null
                ? ""
                : items.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }

    /**
     * Whether the request is still awaiting a decision. Compared by status name so the view
     * model does not depend on the concrete status enum type.
     */
    public boolean isPending() {
        return "PENDING".equals(String.valueOf(request.getStatus()));
    }

    /** Whether the current user is the requester (view gating only; the service re-checks). */
    public boolean isOwnedByCurrentUser() {
        String requester = request.getRequester();
        return requester != null && requester.equals(Jenkins.getAuthentication2().getName());
    }

    /**
     * View gating for the approve/reject forms: only a member of the designated set sees them
     * (D-29, D-37). The endpoints and the service re-check for real.
     */
    public boolean isCanDecide() {
        return isPending() && Jenkins.get().hasPermission(BatchControlPermissions.APPROVE)
                && request.isDesignatedApprover(Jenkins.getAuthentication2().getName());
    }

    /** View gating for the change-approver form; the service enforces requester-only. */
    public boolean isCanChangeApprover() {
        return isPending() && isOwnedByCurrentUser()
                && Jenkins.get().hasPermission(BatchControlPermissions.REQUEST_GRANT);
    }

    /** Approver candidates for the change-approver form (global list, self excluded). */
    public List<String> getApproverOptions() {
        return ApproverOptions.forJob(null);
    }

    /** Whether the change-approver picker starts with this candidate checked. */
    public boolean isDesignated(String approver) {
        return request.isDesignatedApprover(approver);
    }

    /** View gating for the cancel link; the service enforces requester-or-Manage. */
    public boolean isCanCancel() {
        return isPending()
                && (isOwnedByCurrentUser() || Jenkins.get().hasPermission(BatchControlPermissions.MANAGE));
    }

    // ---------------------------------------------------------------- screen access (Jelly)


    /** Permissions for this screen's {@code l:layout} (the same set its section gate checks). */
    public Permission[] getViewPermissions() {
        return SectionAccess.grants();
    }

    /** Link predicates: a link to another screen is rendered only if the user may open it. */
    public SectionAccess getLinks() {
        return new SectionAccess();
    }

    // ---------------------------------------------------------------- state-changing endpoints

    /** POST {@code approve?comment=...} — approver decision (comment optional). */
    @RequirePOST
    public void doApprove(StaplerRequest2 req, StaplerResponse2 rsp, @QueryParameter String comment)
            throws IOException {
        Jenkins.get().checkPermission(BatchControlPermissions.APPROVE);
        call(() -> GrantRequestService.get().approve(request.getId(), comment));
        rsp.sendRedirect2(".");
    }

    /** POST {@code reject?comment=...} — approver decision (service enforces non-empty comment). */
    @RequirePOST
    public void doReject(StaplerRequest2 req, StaplerResponse2 rsp, @QueryParameter String comment)
            throws IOException {
        Jenkins.get().checkPermission(BatchControlPermissions.APPROVE);
        call(() -> GrantRequestService.get().reject(request.getId(), comment));
        rsp.sendRedirect2(".");
    }

    /** POST {@code cancel} — authenticated users only; service enforces requester-or-Manage. */
    @RequirePOST
    public void doCancel(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        Authentication authentication = Jenkins.getAuthentication2();
        if (ACL.isAnonymous2(authentication)) {
            throw new AccessDeniedException("Authentication is required to cancel a grant request");
        }
        call(() -> GrantRequestService.get().cancel(request.getId()));
        rsp.sendRedirect2(".");
    }

    /**
     * POST {@code changeApprover} with the repeated {@code approvers} field — replaces the
     * designated set of a PENDING grant request (D-26, D-37). The service enforces
     * requester-only, PENDING and eligibility, and records (previous set, new set, by, at).
     */
    @RequirePOST
    public void doChangeApprover(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        Jenkins.get().checkPermission(BatchControlPermissions.REQUEST_GRANT);
        List<String> approvers = ApproverInput.read(req, null);
        call(() -> GrantRequestService.get().changeApprovers(request.getId(), approvers));
        rsp.sendRedirect2(".");
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Runs a service call and converts its validation errors into {@link Failure} so the user
     * sees the message instead of a stack trace. No state logic here.
     */
    private static void call(Runnable serviceCall) {
        try {
            serviceCall.run();
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new Failure(e.getMessage() == null ? "The operation was rejected" : e.getMessage());
        }
    }
}
