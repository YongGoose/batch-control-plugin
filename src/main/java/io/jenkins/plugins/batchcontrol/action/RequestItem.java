package io.jenkins.plugins.batchcontrol.action;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Failure;
import hudson.model.Job;
import hudson.model.ModelObject;
import hudson.security.ACL;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.ui.ApproverOptions;
import io.jenkins.plugins.batchcontrol.ui.Dates;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

/**
 * One run request at {@code /batch-control/requests/<id>/}: detail view plus the four
 * state-changing POST endpoints ({@code approve}, {@code reject}, {@code cancel},
 * {@code changeApprover}).
 *
 * <p>Every endpoint is {@code @RequirePOST} (GET never changes state) and performs its permission
 * check before delegating; all business rules (approver eligibility, comment requirements,
 * requester-only/requester-or-Manage rules, atomic state transitions) are enforced by
 * {@link RunRequestService} — this class contains zero state logic.
 */
@Restricted(NoExternalUse.class)
public class RequestItem implements ModelObject {

    private final RunRequest request;

    RequestItem(RunRequest request) {
        this.request = request;
    }

    // ---------------------------------------------------------------- view model

    public RunRequest getRequest() {
        return request;
    }

    public String getId() {
        return request.getId();
    }

    @Override
    public String getDisplayName() {
        return "Run Request " + request.getId();
    }

    /** Jelly helper: human-readable timestamp. */
    public String format(Instant instant) {
        return Dates.format(instant);
    }

    /** Job URL relative to the Jenkins root if the job exists and the user may see it, else null. */
    @CheckForNull
    public String getJobUrl() {
        Job<?, ?> job = findJob();
        return job == null ? null : job.getUrl();
    }

    /** Approver candidates for the change-approver form (global list ∩ job restriction). */
    public List<String> getApproverOptions() {
        return ApproverOptions.forJob(findJob());
    }

    public boolean isPending() {
        return request.getStatus() == RequestStatus.PENDING;
    }

    /** Whether the current user is the requester (view gating only; the service re-checks). */
    public boolean isOwnedByCurrentUser() {
        String requester = request.getRequester();
        return requester != null && requester.equals(Jenkins.getAuthentication2().getName());
    }

    /** View gating for the approve/reject forms; the endpoints re-check for real. */
    public boolean isCanDecide() {
        return isPending() && Jenkins.get().hasPermission(BatchControlPermissions.APPROVE);
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

    // ---------------------------------------------------------------- index (read-only)

    /**
     * Serves the bare detail URL {@code /batch-control/requests/<id>/}. The request history is
     * append-only (SPEC item 4): there is no modify/delete HTTP API on this resource, so every
     * verb except GET/HEAD is refused with 405. The decision endpoints below are separate URLs
     * ({@code approve}, {@code reject}, {@code cancel}, {@code changeApprover}) and are not
     * affected by this guard.
     */
    public void doIndex(StaplerRequest2 req, StaplerResponse2 rsp)
            throws IOException, ServletException {
        String method = req.getMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            rsp.setHeader("Allow", "GET, HEAD");
            rsp.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                    "Run requests are append-only; only GET is allowed on this URL");
            return;
        }
        req.getView(this, "index.jelly").forward(req, rsp);
    }

    // ---------------------------------------------------------------- state-changing endpoints

    /** POST {@code approve?comment=...} — approver decision (comment optional). */
    @RequirePOST
    public void doApprove(StaplerRequest2 req, StaplerResponse2 rsp, @QueryParameter String comment)
            throws IOException {
        Jenkins.get().checkPermission(BatchControlPermissions.APPROVE);
        call(() -> RunRequestService.get().approve(request.getId(), comment));
        rsp.sendRedirect2(".");
    }

    /** POST {@code reject?comment=...} — approver decision (service enforces non-empty comment). */
    @RequirePOST
    public void doReject(StaplerRequest2 req, StaplerResponse2 rsp, @QueryParameter String comment)
            throws IOException {
        Jenkins.get().checkPermission(BatchControlPermissions.APPROVE);
        call(() -> RunRequestService.get().reject(request.getId(), comment));
        rsp.sendRedirect2(".");
    }

    /** POST {@code cancel} — authenticated users only; service enforces requester-or-Manage. */
    @RequirePOST
    public void doCancel(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        Authentication authentication = Jenkins.getAuthentication2();
        if (ACL.isAnonymous2(authentication)) {
            throw new AccessDeniedException("Authentication is required to cancel a run request");
        }
        call(() -> RunRequestService.get().cancel(request.getId()));
        rsp.sendRedirect2(".");
    }

    /** POST {@code changeApprover?approver=...} — service enforces requester-only + eligibility. */
    @RequirePOST
    public void doChangeApprover(StaplerRequest2 req, StaplerResponse2 rsp,
            @QueryParameter String approver) throws IOException {
        Jenkins.get().checkPermission(BatchControlPermissions.REQUEST);
        call(() -> RunRequestService.get().changeApprover(request.getId(), approver));
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

    @CheckForNull
    private Job<?, ?> findJob() {
        // getItemByFullName is permission-aware: returns null when the job is gone or invisible.
        return Jenkins.get().getItemByFullName(request.getJobFullName(), Job.class);
    }
}
