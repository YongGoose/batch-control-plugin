package io.jenkins.plugins.batchcontrol.action;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Util;
import hudson.model.Failure;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ModelObject;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.model.IncidentStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.ops.IncidentService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.ui.ApproverInput;
import io.jenkins.plugins.batchcontrol.ui.ApproverOptions;
import io.jenkins.plugins.batchcontrol.ui.Dates;
import io.jenkins.plugins.batchcontrol.ui.FormErrors;
import io.jenkins.plugins.batchcontrol.ui.Visibility;
import io.jenkins.plugins.batchcontrol.ui.SectionAccess;
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
 * One incident at {@code /batch-control/incidents/<id>/} (SPEC item 11): detail view plus the
 * four state-changing POST endpoints ({@code acknowledge}, {@code resolve}, {@code comment},
 * {@code rerun}).
 *
 * <p>Every endpoint is {@code @RequirePOST} (GET never changes state) and performs its
 * permission check before delegating. All business rules (the OPEN → ACKNOWLEDGED → RESOLVED
 * state machine, comment recording, rerun request creation and linking) are enforced by
 * {@link IncidentService} — this class contains zero state logic.
 */
@Restricted(NoExternalUse.class)
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

    /**
     * Root-relative build URL for the originating run; null (plain text) when the id is
     * malformed or the viewer lacks Item/Read on the job (D-44, {@link Visibility#runUrlFromRunId}).
     */
    @CheckForNull
    public String getRunUrl() {
        return Visibility.runUrlFromRunId(incident.getRunId());
    }

    /** Root-relative build URL for the resolving run, or null (same rule as {@link #getRunUrl()}). */
    @CheckForNull
    public String getResolvedByRunUrl() {
        return Visibility.runUrlFromRunId(incident.getResolvedByRunId());
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

    /**
     * View gating for the rerun form (e2e-03 DEF-12): {@code BatchControl/Request} and
     * {@code Item/Read} on the incident's job, the permissions a run request needs (D-38a;
     * {@code Item/Build} is not required). A user without {@code BatchControl/Request} is not
     * offered the form. The endpoint and the service re-check.
     */
    public boolean isCanRerun() {
        return IncidentService.get().canRerun(incident);
    }

    /** Whether the incident's job still exists and is visible, so a rerun is possible at all. */
    public boolean isJobAvailable() {
        return findJob() != null;
    }

    /**
     * e2e-04 UX-19: the job's URL for a viewer who may read it (P-09, {@link #findJob()}), else
     * {@code null}. Its run request form is {@code <url>batch-control/}; the view links that form
     * only together with {@link #isCanRerun()}, the same Request + Item/Read test the form itself
     * applies (D-38a).
     */
    public String getJobUrl() {
        Job<?, ?> job = findJob();
        return job == null ? null : job.getUrl();
    }

    /** The refusal of form {@code form} on this request, or an empty one (DEF-09, Jelly). */
    public FormErrors formErrors(String form) {
        return FormErrors.current(form);
    }

    // ---------------------------------------------------------------- screen access (Jelly)


    /** Permissions for this screen's {@code l:layout} (the same set its section gate checks). */
    public Permission[] getViewPermissions() {
        return SectionAccess.history();
    }

    /** Link predicates: a link to another screen is rendered only if the user may open it. */
    public SectionAccess getLinks() {
        return new SectionAccess();
    }

    // ---------------------------------------------------------------- state-changing endpoints

    /** POST {@code acknowledge?comment=...} — OPEN → ACKNOWLEDGED. */
    @RequirePOST
    public void doAcknowledge(StaplerRequest2 req, StaplerResponse2 rsp,
            @QueryParameter String comment)
            throws IOException, ServletException {
        Jenkins.get().checkPermission(BatchControlPermissions.VIEW_HISTORY);
        call(req, rsp, new FormErrors("acknowledge"),
                () -> IncidentService.get().acknowledge(incident.getId(), comment));
    }

    /** POST {@code resolve?comment=...} — ACKNOWLEDGED → RESOLVED. */
    @RequirePOST
    public void doResolve(StaplerRequest2 req, StaplerResponse2 rsp,
            @QueryParameter String comment)
            throws IOException, ServletException {
        Jenkins.get().checkPermission(BatchControlPermissions.VIEW_HISTORY);
        call(req, rsp, new FormErrors("resolve"),
                () -> IncidentService.get().resolve(incident.getId(), comment));
    }

    /** POST {@code comment?comment=...} — adds a comment without a status change. */
    @RequirePOST
    public void doComment(StaplerRequest2 req, StaplerResponse2 rsp,
            @QueryParameter String comment)
            throws IOException, ServletException {
        Jenkins.get().checkPermission(BatchControlPermissions.VIEW_HISTORY);
        FormErrors errors = new FormErrors("comment");
        if (comment == null || comment.trim().isEmpty()) {
            refresh().renderRefusal(req, rsp, errors.field("comment", "Enter a comment."));
            return;
        }
        call(req, rsp, errors, () -> IncidentService.get().addComment(incident.getId(), comment));
    }

    /**
     * POST {@code rerun} with the repeated {@code approvers} field (the single {@code approver}
     * field the rerun form posts is read too) — creates a rerun {@link RunRequest} pre-filled
     * with the original parameters and linked back to this incident, then redirects to the new
     * request's detail page.
     */
    @RequirePOST
    public void doRerun(StaplerRequest2 req, StaplerResponse2 rsp)
            throws IOException, ServletException {
        // D-38a / D-57: a rerun is submitted from the Incidents screen (ViewHistory) and needs
        // BatchControl/Request on the incident's job, checked below once the job is known.
        Jenkins.get().checkPermission(BatchControlPermissions.VIEW_HISTORY);
        // S-06: mirror JobRequestAction.doSubmit — no run requests for jobs the caller cannot
        // read. The existence lookup runs as SYSTEM2 because the caller-scoped lookup returns
        // null for an existing-but-unreadable job, which would silently skip exactly the check
        // this exists for; the permission check itself runs as the real caller after the
        // context is closed. When the job is truly gone, the service decides what a rerun of
        // it means. This is the one lookup that deliberately does NOT go through
        // Visibility.findVisibleJob (S-16): under SYSTEM2 the lookup cannot throw
        // AccessDeniedException, and swallowing an invisible job into null here would skip the
        // Item/Read check below instead of enforcing it.
        Job<?, ?> job;
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            job = Jenkins.get().getItemByFullName(incident.getJobFullName(), Job.class);
        }
        if (job != null) {
            job.checkPermission(Item.READ);
            job.checkPermission(BatchControlPermissions.REQUEST);
            // D-38a: Item/Build is not required to request a rerun; the approval decides.
        } else {
            Jenkins.get().checkPermission(BatchControlPermissions.REQUEST);
        }
        FormErrors errors = new FormErrors("rerun");
        List<String> approvers = List.of();
        try {
            approvers = ApproverInput.read(req, null);
        } catch (Failure e) {
            errors.field("approvers", e.getMessage());
        }
        if (approvers.isEmpty()) {
            errors.field("approvers", "Check at least one approver.");
        }
        if (errors.isEmpty()) {
            try {
                RunRequest created = IncidentService.get().rerun(incident.getId(), approvers);
                rsp.sendRedirect2(req.getContextPath() + "/batch-control/requests/"
                        + Util.rawEncode(created.getId()) + "/");
                return;
            } catch (IllegalArgumentException | IllegalStateException e) {
                errors.fromService(e.getMessage(), "approver", "approvers");
            }
        }
        refresh().renderRefusal(req, rsp, errors);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Runs a service call and redirects back to this page; a refusal is shown on this page next
     * to the form it concerns, with the input kept (e2e-03 DEF-09), instead of a bare error page.
     * No state logic here.
     */
    private void call(StaplerRequest2 req, StaplerResponse2 rsp, FormErrors errors,
                      Runnable serviceCall) throws IOException, ServletException {
        try {
            serviceCall.run();
        } catch (IllegalArgumentException | IllegalStateException e) {
            refresh().renderRefusal(req, rsp, errors.fromService(e.getMessage(), "comment", "comment"));
            return;
        }
        rsp.sendRedirect2(".");
    }

    /** This incident as stored now, so a refusal is shown with the current state. */
    private IncidentItem refresh() {
        Incident current = IncidentService.get().load(incident.getId());
        return current == null ? this : new IncidentItem(current);
    }

    private void renderRefusal(StaplerRequest2 req, StaplerResponse2 rsp, FormErrors errors)
            throws IOException, ServletException {
        errors.render(req, rsp, this);
    }

    /**
     * The incident's job, or null when it is gone or invisible to the caller.
     *
     * <p>S-16: routed through {@link Visibility#findVisibleJob}, because
     * {@code getItemByFullName} signals "you may discover this but not read it" by throwing
     * {@code AccessDeniedException}, not by returning null — and a throw from a Jelly-facing
     * getter is swallowed into a blank value instead of an error, which degrades this page
     * silently.
     */
    @CheckForNull
    private Job<?, ?> findJob() {
        return Visibility.findVisibleJob(incident.getJobFullName());
    }
}
