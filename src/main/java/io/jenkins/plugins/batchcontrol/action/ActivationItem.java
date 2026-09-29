package io.jenkins.plugins.batchcontrol.action;

import edu.umd.cs.findbugs.annotations.CheckForNull;
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
import io.jenkins.plugins.batchcontrol.ui.SectionAccess;
import io.jenkins.plugins.batchcontrol.ui.Visibility;
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
        return SectionAccess.activations();
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
        return isPending() && isOwnedByCurrentUser()
                && Jenkins.get().hasPermission(BatchControlPermissions.REQUEST);
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
            throws IOException {
        Jenkins.get().checkPermission(BatchControlPermissions.APPROVE);
        call(() -> ActivationService.get().approve(request.getId(), comment));
        rsp.sendRedirect2(".");
    }

    /** POST {@code reject} with a {@code comment} (the service refuses an empty one). */
    @RequirePOST
    public void doReject(StaplerRequest2 req, StaplerResponse2 rsp, @QueryParameter String comment)
            throws IOException {
        Jenkins.get().checkPermission(BatchControlPermissions.APPROVE);
        call(() -> ActivationService.get().reject(request.getId(), comment));
        rsp.sendRedirect2(".");
    }

    /** POST {@code cancel}; Request or Manage, and the service enforces requester-or-Manage. */
    @RequirePOST
    public void doCancel(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        Jenkins.get().checkAnyPermission(BatchControlPermissions.REQUEST, BatchControlPermissions.MANAGE);
        call(() -> ActivationService.get().cancel(request.getId()));
        rsp.sendRedirect2(".");
    }

    /** POST {@code changeApprover} with the repeated {@code approvers} field (D-26, D-37). */
    @RequirePOST
    public void doChangeApprover(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        Jenkins.get().checkPermission(BatchControlPermissions.REQUEST);
        List<String> approvers = ApproverInput.read(req, null);
        call(() -> ActivationService.get().changeApprovers(request.getId(), approvers));
        rsp.sendRedirect2(".");
    }

    // ---------------------------------------------------------------- helpers

    /** Turns the service's validation errors into a {@link Failure} (HTTP 400) with the message. */
    private static void call(Runnable serviceCall) {
        try {
            serviceCall.run();
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new Failure(e.getMessage() == null ? "The operation was rejected" : e.getMessage());
        }
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
