package io.jenkins.plugins.batchcontrol.action;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Util;
import hudson.model.Failure;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ModelObject;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.model.ActivationRequest;
import io.jenkins.plugins.batchcontrol.model.Approvers;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.ui.ActivationView;
import io.jenkins.plugins.batchcontrol.ui.ApproverInput;
import io.jenkins.plugins.batchcontrol.ui.ApproverOptions;
import io.jenkins.plugins.batchcontrol.ui.Dates;
import io.jenkins.plugins.batchcontrol.ui.FormErrors;
import io.jenkins.plugins.batchcontrol.ui.RequestScope;
import io.jenkins.plugins.batchcontrol.ui.SectionAccess;
import io.jenkins.plugins.batchcontrol.ui.Visibility;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 * One activation or hold request at {@code /batch-control/activations/<id>/} (SPEC item 6a),
 * the URL the notifications link to: detail view plus the state-changing POST endpoints
 * {@code approve}, {@code reject}, {@code cancel} and {@code changeApprover}, following
 * {@link RequestItem}.
 *
 * <p>Every endpoint is {@code @RequirePOST} with its permission check on the next line; the
 * business rules (designated approver only, self-approval, requester-or-Manage cancel,
 * requester-only approver change, atomic transitions) are enforced by {@link ActivationService}.
 */
@Restricted(NoExternalUse.class)
public class ActivationItem implements ModelObject {

    private final ActivationRequest request;

    ActivationItem(ActivationRequest request) {
        this.request = request;
    }

    // ---------------------------------------------------------------- view model

    public ActivationRequest getRequest() {
        return request;
    }

    public String getId() {
        return request.getId();
    }

    @Override
    public String getDisplayName() {
        return (isHold() ? "Hold Request " : "Activation Request ") + request.getId();
    }

    /** S-13-08: ACTIVATE and HOLD worded so they cannot be mistaken for each other. */
    public String getActionLabel() {
        return ActivationView.actionLabel(request.getAction());
    }

    /** Permissions for this screen's {@code l:layout} (the same set its section gate checks). */
    public Permission[] getViewPermissions() {
        return SectionAccess.viewPermissions(SectionAccess.activations(), SectionAccess.canOpenActivations());
    }

    /** Jelly helper: human-readable timestamp. */
    public String format(@CheckForNull Instant instant) {
        return Dates.format(instant);
    }

    /** Jelly helper: an approver set for display ({@code a1, a2}). */
    public String join(@CheckForNull List<String> approvers) {
        return Approvers.display(approvers);
    }

    /**
     * Root-relative URL of the job or computed folder (D-46c) when the viewer may read it, else
     * {@code null} (plain text).
     */
    @CheckForNull
    public String getJobUrl() {
        Item item = Visibility.findVisibleItem(request.getJobFullName());
        return item != null && item.hasPermission(Item.READ) ? item.getUrl() : null;
    }

    public boolean isPending() {
        return request.getStatus() == RequestStatus.PENDING;
    }

    /** Whether the request asks to put the job on hold (the view words the decision accordingly). */
    public boolean isHold() {
        return request.getAction() == ActivationRequest.Action.HOLD;
    }

    private boolean isOwnedByCurrentUser() {
        return Approvers.sameUser(Jenkins.getAuthentication2().getName(), request.getRequester());
    }

    /** View gating for the decision forms: a designated approver holding Approve (D-29, D-37). */
    public boolean isCanDecide() {
        return isPending() && Jenkins.get().hasPermission(BatchControlPermissions.APPROVE)
                && request.isDesignatedApprover(Jenkins.getAuthentication2().getName());
    }

    /** View gating for the cancel link; the service enforces requester-or-Manage. */
    public boolean isCanCancel() {
        return isPending()
                && (isOwnedByCurrentUser() || Jenkins.get().hasPermission(BatchControlPermissions.MANAGE));
    }

    /** View gating for the change-approver form; the service enforces requester-only. */
    public boolean isCanChangeApprover() {
        // D-38b: Request on the request's job (or a folder above it, or Jenkins).
        return isPending() && isOwnedByCurrentUser()
                && RequestScope.of(request.getJobFullName()).hasPermission(BatchControlPermissions.REQUEST);
    }

    /** Approver candidates for the change-approver form (global list ∩ job restriction). */
    public List<String> getApproverOptions() {
        return ApproverOptions.forJob(findJob());
    }

    /** Whether the change-approver picker starts with this candidate checked. */
    public boolean isDesignated(String approver) {
        return request.isDesignatedApprover(approver);
    }

    // ---------------------------------------------------------------- state-changing endpoints

    /** POST {@code approve} with an optional {@code comment}. */
    @RequirePOST
    public void doApprove(StaplerRequest2 req, StaplerResponse2 rsp, @QueryParameter String comment)
            throws IOException, ServletException {
        Jenkins.get().checkPermission(BatchControlPermissions.APPROVE);
        call(req, rsp, new FormErrors("approve"),
                () -> ActivationService.get().approve(request.getId(), comment), "comment", "comment");
    }

    /** POST {@code reject} with a {@code comment} (the service refuses an empty one). */
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
        call(req, rsp, errors, () -> ActivationService.get().reject(request.getId(), comment),
                "comment", "comment");
    }

    /**
     * POST {@code cancel}; Request on the request's item (D-38b) or Manage, and the service
     * enforces requester-or-Manage.
     */
    @RequirePOST
    public void doCancel(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException, ServletException {
        RequestScope.of(request.getJobFullName()).checkAnyPermission(BatchControlPermissions.REQUEST,
                BatchControlPermissions.MANAGE);
        call(req, rsp, new FormErrors("cancel"), () -> ActivationService.get().cancel(request.getId()));
    }

    /** POST {@code changeApprover} with the repeated {@code approvers} field (D-26, D-37). */
    @RequirePOST
    public void doChangeApprover(StaplerRequest2 req, StaplerResponse2 rsp)
            throws IOException, ServletException {
        // D-38b: Request is checked on the request's job; the service enforces requester-only.
        RequestScope.of(request.getJobFullName()).checkPermission(BatchControlPermissions.REQUEST);
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
        call(req, rsp, errors, () -> ActivationService.get().changeApprovers(request.getId(), approvers),
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
    private ActivationItem refresh() {
        ActivationRequest current = ActivationService.get().load(request.getId());
        return current == null ? this : new ActivationItem(current);
    }

    private void renderRefusal(StaplerRequest2 req, StaplerResponse2 rsp, FormErrors errors)
            throws IOException, ServletException {
        errors.render(req, rsp, this);
    }

    /**
     * The target job, or {@code null} when it is gone, invisible to the caller (S-16) or a
     * computed folder (the approver options then fall back to the global list).
     */
    @CheckForNull
    private Job<?, ?> findJob() {
        return Visibility.findVisibleJob(request.getJobFullName());
    }
}
