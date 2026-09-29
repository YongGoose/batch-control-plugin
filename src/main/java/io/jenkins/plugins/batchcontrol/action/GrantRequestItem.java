package io.jenkins.plugins.batchcontrol.action;

import hudson.Util;
import hudson.model.Failure;
import hudson.model.ModelObject;
import hudson.security.ACL;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.SystemBuildCheck;
import io.jenkins.plugins.batchcontrol.ui.ApproverInput;
import io.jenkins.plugins.batchcontrol.ui.ApproverOptions;
import io.jenkins.plugins.batchcontrol.ui.Dates;
import io.jenkins.plugins.batchcontrol.ui.FormErrors;
import io.jenkins.plugins.batchcontrol.ui.SectionAccess;
import jakarta.servlet.ServletException;
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

    // ---------------------------------------------------------------- SYSTEM builds (D-50a)

    /**
     * D-50a (SPEC item 2): whether this page shows the fixed warning that builds can run as
     * SYSTEM on this instance. Only for a pending request that includes CONFIGURE, only while
     * {@link SystemBuildCheck#buildsMayRunAsSystem()} holds, and only to a viewer who may decide
     * this request ({@link #isCanDecide()}) or holds {@code BatchControl/Manage}; the requester
     * sees it only as one of those. The warning is instance-wide and names nothing, so it tells
     * no viewer anything about the jobs in the scope.
     */
    public boolean isShowSystemBuildWarning() {
        if (!isPending() || request.getActions() == null
                || !request.getActions().contains(GrantAction.CONFIGURE)) {
            return false;
        }
        if (!isCanDecide() && !Jenkins.get().hasPermission(BatchControlPermissions.MANAGE)) {
            return false;
        }
        return SystemBuildCheck.buildsMayRunAsSystem();
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
            throws IOException, ServletException {
        Jenkins.get().checkPermission(BatchControlPermissions.APPROVE);
        call(req, rsp, new FormErrors("approve"),
                () -> GrantRequestService.get().approve(request.getId(), comment), "comment", "comment");
    }

    /** POST {@code reject?comment=...} — approver decision (service enforces non-empty comment). */
    @RequirePOST
    public void doReject(StaplerRequest2 req, StaplerResponse2 rsp, @QueryParameter String comment)
            throws IOException, ServletException {
        Jenkins.get().checkPermission(BatchControlPermissions.APPROVE);
        FormErrors errors = new FormErrors("reject");
        if (Util.fixEmptyAndTrim(comment) == null) {
            // A rejection needs a comment; the service refuses it too.
            refresh().renderRefusal(req, rsp, errors.field("comment",
                    "Enter a rejection comment: the requester sees it as the reason."));
            return;
        }
        call(req, rsp, errors, () -> GrantRequestService.get().reject(request.getId(), comment),
                "comment", "comment");
    }

    /** POST {@code cancel} — authenticated users only; service enforces requester-or-Manage. */
    @RequirePOST
    public void doCancel(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException, ServletException {
        Authentication authentication = Jenkins.getAuthentication2();
        if (ACL.isAnonymous2(authentication)) {
            throw new AccessDeniedException("Authentication is required to cancel a grant request");
        }
        call(req, rsp, new FormErrors("cancel"), () -> GrantRequestService.get().cancel(request.getId()));
    }

    /**
     * POST {@code changeApprover} with the repeated {@code approvers} field — replaces the
     * designated set of a PENDING grant request (D-26, D-37). The service enforces
     * requester-only, PENDING and eligibility, and records (previous set, new set, by, at).
     */
    @RequirePOST
    public void doChangeApprover(StaplerRequest2 req, StaplerResponse2 rsp)
            throws IOException, ServletException {
        Jenkins.get().checkPermission(BatchControlPermissions.REQUEST_GRANT);
        FormErrors errors = new FormErrors("changeApprover");
        List<String> approvers;
        try {
            approvers = ApproverInput.read(req, null);
        } catch (Failure e) {
            refresh().renderRefusal(req, rsp, errors.field("approvers", e.getMessage()));
            return;
        }
        if (approvers.isEmpty()) {
            refresh().renderRefusal(req, rsp, errors.field("approvers", "Check at least one approver."));
            return;
        }
        call(req, rsp, errors, () -> GrantRequestService.get().changeApprovers(request.getId(), approvers),
                "approver", "approvers");
    }

    // ---------------------------------------------------------------- helpers

    /** The refusal of form {@code form} on this request, or an empty one (DEF-09, Jelly). */
    public FormErrors formErrors(String form) {
        return FormErrors.current(form);
    }

    /**
     * Runs a service call and redirects back to this page; a refusal is shown on this page next
     * to the form it concerns, with the input kept (e2e-03 DEF-09), instead of a bare error page.
     * No state logic here.
     */
    private void call(StaplerRequest2 req, StaplerResponse2 rsp, FormErrors errors,
                      Runnable serviceCall, String... keywords) throws IOException, ServletException {
        try {
            serviceCall.run();
        } catch (IllegalArgumentException | IllegalStateException e) {
            refresh().renderRefusal(req, rsp, errors.fromService(e.getMessage(), keywords));
            return;
        }
        rsp.sendRedirect2(".");
    }

    /** This request as stored now, so a refusal is shown with the current state. */
    private GrantRequestItem refresh() {
        GrantRequest current = GrantRequestService.get().load(request.getId());
        return current == null ? this : new GrantRequestItem(current);
    }

    private void renderRefusal(StaplerRequest2 req, StaplerResponse2 rsp, FormErrors errors)
            throws IOException, ServletException {
        errors.render(req, rsp, this);
    }
}
