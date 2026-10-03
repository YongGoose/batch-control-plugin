package io.jenkins.plugins.batchcontrol.action;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Util;
import hudson.model.Failure;
import hudson.model.Job;
import hudson.model.ModelObject;
import hudson.model.Result;
import hudson.model.Run;
import hudson.security.ACL;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.Approvers;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.ui.ApproverInput;
import io.jenkins.plugins.batchcontrol.ui.ApproverOptions;
import io.jenkins.plugins.batchcontrol.ui.Dates;
import io.jenkins.plugins.batchcontrol.ui.FormErrors;
import io.jenkins.plugins.batchcontrol.ui.RunLinks;
import io.jenkins.plugins.batchcontrol.ui.Visibility;
import io.jenkins.plugins.batchcontrol.ui.RequestScope;
import io.jenkins.plugins.batchcontrol.ui.SectionAccess;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.Stapler;
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

    /** How many recent runs of the target job the decision screen shows by default (T-E2E-05). */
    private static final int DEFAULT_RECENT_RUN_LIMIT = 5;

    /**
     * The only sizes the recent-run table will ever load, smallest first. This is an allow-list
     * rather than a range: loading build history costs I/O on the controller, so the largest entry
     * is a hard server-side cap that no query parameter can exceed.
     */
    private static final List<Integer> RECENT_RUN_LIMIT_OPTIONS = List.of(5, 10, 20, 50);

    private final RunRequest request;

    /** Per-request cache: the Jelly asks for the list more than once. */
    private List<RecentRun> recentRuns;

    /** Whether the job has more runs than {@link #getRecentRunLimit()} showed. */
    private boolean recentRunsTruncated;

    /** Per-request cache of the resolved {@code runs} query parameter. */
    private Integer recentRunLimit;

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

    /**
     * D-38a: whether the detail page states that the requester does not hold {@code Item/Build}
     * on the job (evaluated for the requester, not the viewer).
     */
    public boolean isRequesterLacksBuild() {
        return io.jenkins.plugins.batchcontrol.ui.RequesterPermission.lacksBuild(getRequest());
    }

    /** D-38a: the frozen sentence shown when {@link #isRequesterLacksBuild()} holds. */
    public String getRequesterLacksBuildNotice() {
        return io.jenkins.plugins.batchcontrol.ui.RequesterPermission.notice();
    }

    /** Job URL relative to the Jenkins root if the job exists and the user may see it, else null. */
    @CheckForNull
    public String getJobUrl() {
        Job<?, ?> job = findJob();
        return job == null ? null : job.getUrl();
    }

    /**
     * Root-relative URL of the build this request produced.
     *
     * @return null when the request produced no run, when the stored id is not of the form
     *         {@code jobFullName#number}, when the build has since been deleted, or when the
     *         job is not visible to the caller (P-09). The view then renders the stored id as
     *         plain text instead of a dead link.
     */
    @CheckForNull
    public String getExecutedRunUrl() {
        String runId = request.getExecutedRunId();
        if (runId == null) {
            return null;
        }
        int hash = runId.lastIndexOf('#');
        // Only link inside the request's own job: the id is stored data, not a routing input.
        if (hash <= 0 || !runId.substring(0, hash).equals(request.getJobFullName())) {
            return null;
        }
        // The run-link rule shared by every screen (D-44): Item/Read on the job, else plain text.
        String url = Visibility.runUrlFromRunId(runId);
        if (url == null) {
            return null;
        }
        // The detail screen additionally drops the link of a build deleted since it ran.
        Job<?, ?> job = findJob();
        return job != null && job.getBuildByNumber(Integer.parseInt(runId.substring(hash + 1))) != null
                ? url
                : null;
    }

    /**
     * The most recent runs of the requested job, newest first, so an approver can see how the
     * job behaved last time without opening a second tab (T-E2E-05). At most
     * {@link #getRecentRunLimit()} rows.
     *
     * <p>P-09: the job is resolved through the permission-aware {@link #findJob()}, so a caller
     * who may see the request but not the job — and a request whose job has been deleted — gets
     * an empty list; the run history of an invisible job is never disclosed. The {@code runs}
     * query parameter only changes how many rows this list holds, never whether the job is
     * resolved, so it cannot be used to step around that check. The view distinguishes the two
     * cases through {@link #getJobUrl()}.
     */
    public List<RecentRun> getRecentRuns() {
        if (recentRuns == null) {
            int limit = getRecentRunLimit();
            List<RecentRun> rows = new ArrayList<>();
            boolean truncated = false;
            Job<?, ?> job = findJob();
            if (job != null) {
                for (Run<?, ?> run : job.getBuilds()) {
                    if (rows.size() >= limit) {
                        // One build past the limit was touched only to learn that more exist.
                        truncated = true;
                        break;
                    }
                    rows.add(new RecentRun(run));
                }
            }
            recentRunsTruncated = truncated;
            recentRuns = rows;
        }
        return recentRuns;
    }

    /**
     * How many recent runs this rendering shows, from the {@code runs} query parameter
     * ({@code ?runs=20}) and defaulting to {@value #DEFAULT_RECENT_RUN_LIMIT}.
     *
     * <p>The value is matched against {@link #RECENT_RUN_LIMIT_OPTIONS} rather than clamped, so
     * anything else — a value above the cap such as {@code ?runs=100000}, a negative number, a
     * non-multiple such as {@code 7}, or text — silently falls back to the default instead of
     * producing an error page. That makes the largest option the enforced upper bound on how much
     * build history one page load may read, whatever the caller types in the URL.
     */
    public int getRecentRunLimit() {
        if (recentRunLimit == null) {
            recentRunLimit = resolveRecentRunLimit();
        }
        return recentRunLimit;
    }

    /** How many recent-run rows were actually found (at most {@link #getRecentRunLimit()}). */
    public int getRecentRunCount() {
        return getRecentRuns().size();
    }

    /** The sizes offered by the {@code ?runs=} links below the recent-run table. */
    public List<Integer> getRecentRunLimitOptions() {
        return RECENT_RUN_LIMIT_OPTIONS;
    }

    /**
     * Whether the job has more runs than were shown, so the view can tell "these are all of them"
     * apart from "this is the newest page of a longer history". Call after
     * {@link #getRecentRuns()}.
     */
    public boolean isRecentRunsTruncated() {
        getRecentRuns();
        return recentRunsTruncated;
    }

    private static int resolveRecentRunLimit() {
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        if (req == null) {
            return DEFAULT_RECENT_RUN_LIMIT;
        }
        String raw = req.getParameter("runs");
        if (raw == null) {
            return DEFAULT_RECENT_RUN_LIMIT;
        }
        int requested;
        try {
            requested = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_RECENT_RUN_LIMIT;
        }
        return RECENT_RUN_LIMIT_OPTIONS.contains(requested) ? requested : DEFAULT_RECENT_RUN_LIMIT;
    }

    /** Approver candidates for the change-approver form (global list ∩ job restriction). */
    public List<String> getApproverOptions() {
        return ApproverOptions.forJob(findJob());
    }

    public boolean isPending() {
        return request.getStatus() == RequestStatus.PENDING;
    }

    /**
     * Whether the request is approved but its build has not been recorded yet (UX-10).
     *
     * <p>Approving redirects back to this read-only page, and the APPROVED → EXECUTED transition
     * plus the run id are written later by the execution listener, so the page the approver lands
     * on is one refresh behind. The view uses this to say so. It turns false as soon as the status
     * moves on (EXECUTED, EXPIRED, INVALIDATED) and is false for PENDING, REJECTED and CANCELLED.
     */
    public boolean isAwaitingExecution() {
        return request.getStatus() == RequestStatus.APPROVED && request.getExecutedRunId() == null;
    }

    /**
     * D-55 (SPEC item 3): whether the request's job is disabled, so the decision form says to
     * enable it first and offers no Approve button, and an approved request says that it waits.
     * Resolved through the permission-aware {@link #findJob()} (P-09): for a viewer who may not
     * read the job this is {@code false}, and the service's own refusal of the approval is what
     * they see. Display only; the service enforces the refusal.
     */
    public boolean isJobDisabled() {
        Job<?, ?> job = findJob();
        return job instanceof ParameterizedJobMixIn.ParameterizedJob
                && ((ParameterizedJobMixIn.ParameterizedJob<?, ?>) job).isDisabled();
    }

    /** The approved-but-not-run timeout, for the disabled-job notice. */
    public int getApprovedRunTimeoutMinutes() {
        return BatchControlGlobalConfiguration.get().getApprovedRunTimeoutMinutes();
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

    /** Jelly helper: an approver set for display ({@code a1, a2}); empty for none. */
    public String join(@CheckForNull List<String> approvers) {
        return Approvers.display(approvers);
    }

    /**
     * Whether the change-approver picker starts with this candidate checked: the current
     * members, so the form edits the set rather than retyping it.
     */
    public boolean isDesignated(String approver) {
        return request.isDesignatedApprover(approver);
    }

    /** View gating for the cancel link; the service enforces requester-or-Manage. */
    public boolean isCanCancel() {
        // D-38b: the service's predicate (requester with Request on the job, or Manage).
        return isPending() && RunRequestService.get().canCancel(request);
    }

    /** View gating for the change-approver form; the service enforces requester-only. */
    public boolean isCanChangeApprover() {
        // D-38b: the service's predicate (requester with Request on the job).
        return isPending() && RunRequestService.get().canChangeApprovers(request);
    }

    // ---------------------------------------------------------------- screen access (Jelly)


    /** Permissions for this screen's {@code l:layout} (the same set its section gate checks). */
    public Permission[] getViewPermissions() {
        return SectionAccess.viewPermissions(SectionAccess.requests(), SectionAccess.canOpenRequests());
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
                () -> RunRequestService.get().approve(request.getId(), comment), "comment", "comment");
    }

    /** POST {@code reject?comment=...} — approver decision (service enforces non-empty comment). */
    @RequirePOST
    public void doReject(StaplerRequest2 req, StaplerResponse2 rsp, @QueryParameter String comment)
            throws IOException, ServletException {
        Jenkins.get().checkPermission(BatchControlPermissions.APPROVE);
        FormErrors errors = new FormErrors("reject");
        if (Util.fixEmptyAndTrim(comment) == null) {
            // SPEC item 5: a rejection needs a comment; the service refuses it too.
            errors.field("comment", "Enter a rejection comment: the requester sees it as the reason.");
            refresh().renderRefusal(req, rsp, errors);
            return;
        }
        call(req, rsp, errors, () -> RunRequestService.get().reject(request.getId(), comment),
                "comment", "comment");
    }

    /** POST {@code cancel} — authenticated users only; service enforces requester-or-Manage. */
    @RequirePOST
    public void doCancel(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException, ServletException {
        Authentication authentication = Jenkins.getAuthentication2();
        if (ACL.isAnonymous2(authentication)) {
            throw new AccessDeniedException("Authentication is required to cancel a run request");
        }
        call(req, rsp, new FormErrors("cancel"), () -> RunRequestService.get().cancel(request.getId()));
    }

    /**
     * POST {@code changeApprover} with the repeated {@code approvers} field — replaces the
     * designated set (D-26, D-37); the service enforces requester-only, PENDING and eligibility.
     */
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
        call(req, rsp, errors, () -> RunRequestService.get().changeApprovers(request.getId(), approvers),
                "approver", "approvers");
    }

    /** The refusal of form {@code form} on this request, or an empty one (DEF-09, Jelly). */
    public FormErrors formErrors(String form) {
        return FormErrors.current(form);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Runs a service call and redirects back to this page; a refusal (the service's validation
     * and state errors) is shown on this page next to the form it concerns, with the input kept
     * (e2e-03 DEF-09), instead of a bare error page. No state logic here.
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

    /**
     * This request as stored now: a refusal is often "someone else decided first", and the page
     * shown with it must show that state, not the snapshot this item was resolved with.
     */
    private RequestItem refresh() {
        RunRequest current = RunRequestService.get().load(request.getId());
        return current == null ? this : new RequestItem(current);
    }

    private void renderRefusal(StaplerRequest2 req, StaplerResponse2 rsp, FormErrors errors)
            throws IOException, ServletException {
        errors.render(req, rsp, this);
    }

    /**
     * The request's target job, or null when it is gone or invisible to the caller.
     *
     * <p>S-16: this goes through {@link Visibility#findVisibleJob} rather than calling
     * {@code getItemByFullName} directly. That lookup does not report an invisible job by
     * returning null — with {@code Item/Discover} and without {@code Item/Read} it throws
     * {@code AccessDeniedException}, which Jelly's expression evaluator swallows into a blank
     * value, so this page used to answer HTTP 200 with the job link, the recent-run table and
     * the approver dropdown all silently missing. The helper restores the null-means-invisible
     * contract the callers below assume.
     */
    @CheckForNull
    private Job<?, ?> findJob() {
        return Visibility.findVisibleJob(request.getJobFullName());
    }

    /**
     * One row of the recent-run table. Everything is read off the {@link Run} at construction
     * time so the view never holds a live model object; all fields are plain text rendered
     * through Jelly's default escaping.
     */
    @Restricted(NoExternalUse.class)
    public static final class RecentRun {

        private final int number;
        private final String url;
        private final String result;
        private final String started;
        private final String duration;

        RecentRun(Run<?, ?> run) {
            this.number = run.getNumber();
            this.url = run.getUrl();
            // One read: getResult() is @CheckForNull and is null while the build is running.
            Result runResult = run.getResult();
            this.result = run.isBuilding()
                    ? "IN PROGRESS"
                    : runResult == null ? "UNKNOWN" : runResult.toString();
            this.started = Dates.format(Instant.ofEpochMilli(run.getStartTimeInMillis()));
            this.duration = run.isBuilding() ? "" : RunLinks.formatDuration(run.getDuration());
        }

        public int getNumber() {
            return number;
        }

        /** Root-relative build URL ({@code job/a/12/}). */
        public String getUrl() {
            return url;
        }

        public String getResult() {
            return result;
        }

        public String getStarted() {
            return started;
        }

        public String getDuration() {
            return duration;
        }
    }
}
