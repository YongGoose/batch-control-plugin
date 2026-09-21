package io.jenkins.plugins.batchcontrol.action;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Failure;
import hudson.model.Job;
import hudson.model.ModelObject;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.model.IncidentStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.ops.IncidentService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.ui.ApproverOptions;
import io.jenkins.plugins.batchcontrol.ui.Dates;
import io.jenkins.plugins.batchcontrol.ui.RunLinks;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 * One incident at {@code /batch-control/incidents/<id>/} (SPEC item 11): detail view plus the
 * four state-changing POST endpoints ({@code acknowledge}, {@code resolve}, {@code comment},
 * {@code rerun}).
 *
 * <p>Every endpoint is {@code @RequirePOST} (GET never changes state) and performs its
 * permission check before delegating. All business rules (the OPEN → ACKNOWLEDGED → RESOLVED
 * state machine, comment recording, rerun request creation and linking) are enforced by
 * {@link IncidentService} — this class contains zero state logic.
 */
public class IncidentItem implements ModelObject {

    private final Incident incident;

    IncidentItem(Incident incident) {
        this.incident = incident;
    }

    // ---------------------------------------------------------------- view model

    public Incident getIncident() {
        return incident;
    }

    public String getId() {
        return incident.getId();
    }

    @Override
    public String getDisplayName() {
        return "Incident " + incident.getId();
    }

    /** Jelly helper: human-readable timestamp. */
    public String format(Instant instant) {
        return Dates.format(instant);
    }

    /** Root-relative build URL for the originating run, or null when the id is malformed. */
    @CheckForNull
    public String getRunUrl() {
        return RunLinks.runUrlFromRunId(incident.getRunId());
    }

    /** One-line parameter rendering; values come from the incident already masked. */
    public String parameters(Map<String, String> parameters) {
        return RunLinks.formatParameters(parameters);
    }

    /** Root-relative build URL for the resolving run, or null. */
    @CheckForNull
    public String getResolvedByRunUrl() {
        return RunLinks.runUrlFromRunId(incident.getResolvedByRunId());
    }

    /** Incident creation time (first transition). */
    @CheckForNull
    public Instant getCreatedAt() {
        return IncidentsSection.creationTime(incident);
    }

    /**
     * The stored console log excerpt as one string for the {@code <pre>} block. The Jelly
     * default escaping renders it as inert text; secrets were masked at capture time (D-19).
     */
    public String getLogTailText() {
        List<String> tail = incident.getLogTail();
        return tail == null || tail.isEmpty() ? "" : String.join("\n", tail);
    }

    /** Approver candidates for the rerun request form (global list ∩ job restriction). */
    public List<String> getApproverOptions() {
        return ApproverOptions.forJob(findJob());
    }

    /** View gating only; the service re-validates the state machine. */
    public boolean isCanAcknowledge() {
        return incident.getStatus() == IncidentStatus.OPEN;
    }

    /** View gating only; the service re-validates the state machine. */
    public boolean isCanResolve() {
        return incident.getStatus() == IncidentStatus.ACKNOWLEDGED;
    }

    /** Comments stay possible in every state (SPEC section 4: RESOLVED still takes comments). */
    public boolean isCanComment() {
        return true;
    }

    /** View gating for the rerun form; the endpoint re-checks the Request permission. */
    public boolean isCanRerun() {
        return Jenkins.get().hasPermission(BatchControlPermissions.REQUEST);
    }

    // ---------------------------------------------------------------- index (read-only)

    /**
     * Serves the bare detail URL {@code /batch-control/incidents/<id>/}. State transitions
     * happen only on the named endpoints below, so every verb except GET/HEAD is refused with
     * 405 here.
     */
    public void doIndex(StaplerRequest2 req, StaplerResponse2 rsp)
            throws IOException, ServletException {
        String method = req.getMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            rsp.setHeader("Allow", "GET, HEAD");
            rsp.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                    "Use the acknowledge/resolve/comment/rerun endpoints to act on an incident;"
                            + " only GET is allowed on this URL");
            return;
        }
        req.getView(this, "index.jelly").forward(req, rsp);
    }

    // ---------------------------------------------------------------- state-changing endpoints

    /** POST {@code acknowledge?comment=...} — OPEN → ACKNOWLEDGED. */
    @RequirePOST
    public void doAcknowledge(StaplerRequest2 req, StaplerResponse2 rsp,
            @QueryParameter String comment) throws IOException {
        Jenkins.get().checkPermission(BatchControlPermissions.VIEW_HISTORY);
        call(() -> IncidentService.get().acknowledge(incident.getId(), comment));
        rsp.sendRedirect2(".");
    }

    /** POST {@code resolve?comment=...} — ACKNOWLEDGED → RESOLVED. */
    @RequirePOST
    public void doResolve(StaplerRequest2 req, StaplerResponse2 rsp,
            @QueryParameter String comment) throws IOException {
        Jenkins.get().checkPermission(BatchControlPermissions.VIEW_HISTORY);
        call(() -> IncidentService.get().resolve(incident.getId(), comment));
        rsp.sendRedirect2(".");
    }

    /** POST {@code comment?comment=...} — adds a comment without a status change. */
    @RequirePOST
    public void doComment(StaplerRequest2 req, StaplerResponse2 rsp,
            @QueryParameter String comment) throws IOException {
        Jenkins.get().checkPermission(BatchControlPermissions.VIEW_HISTORY);
        call(() -> IncidentService.get().addComment(incident.getId(), comment));
        rsp.sendRedirect2(".");
    }

    /**
     * POST {@code rerun?approver=...} — creates a rerun {@link RunRequest} pre-filled with the
     * original parameters and linked back to this incident, then redirects to the new request's
     * detail page.
     */
    @RequirePOST
    public void doRerun(StaplerRequest2 req, StaplerResponse2 rsp,
            @QueryParameter String approver) throws IOException {
        Jenkins.get().checkPermission(BatchControlPermissions.REQUEST);
        RunRequest created;
        try {
            created = IncidentService.get().rerun(incident.getId(), approver);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new Failure(e.getMessage() == null ? "The rerun request was rejected"
                    : e.getMessage());
        }
        rsp.sendRedirect2(req.getContextPath() + "/batch-control/requests/" + created.getId() + "/");
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
        return Jenkins.get().getItemByFullName(incident.getJobFullName(), Job.class);
    }
}
