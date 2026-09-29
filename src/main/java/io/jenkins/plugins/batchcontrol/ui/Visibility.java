package io.jenkins.plugins.batchcontrol.ui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Item;
import hudson.model.Job;
import io.jenkins.plugins.batchcontrol.model.ActivationRequest;
import io.jenkins.plugins.batchcontrol.model.Approvers;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.springframework.security.access.AccessDeniedException;

/**
 * Per-object visibility predicates for the request and grant screens (P-09, security review
 * S-01). The section gates stay coarse; these predicates decide which rows a caller may see,
 * and the SAME predicate gates the detail URL, so list and detail can never diverge. A
 * non-visible object renders exactly like a nonexistent one (404) to hide its existence.
 *
 * <p>Read-only: only public model getters, {@link #findVisibleJob} / {@link #findVisibleItem}
 * and {@code hasPermission} are used. State-changing endpoints keep their own permission
 * checks — nothing here replaces them.
 */
@Restricted(NoExternalUse.class)
public final class Visibility {

    private Visibility() {
    }

    /**
     * Resolves a job by full name for display purposes, treating "the caller may not see it"
     * exactly like "it does not exist" (S-16).
     *
     * <p>{@code Jenkins.getItemByFullName} is permission-aware but it does <em>not</em> report
     * an invisible item by returning {@code null}: when the caller holds {@code Item/Discover}
     * and not {@code Item/Read}, core <strong>throws</strong> {@link AccessDeniedException}
     * (that is how {@code Item/Discover} is specified — "the item exists, log in to see it").
     * Only a caller with neither permission gets {@code null}.
     *
     * <p>Letting that exception escape a Jelly-facing getter is worse than a 403: Jenkins'
     * JEXL evaluator catches it and resolves the whole expression to {@code null}, so the page
     * still answers HTTP 200 while the row list comes back empty and the record count renders
     * as a blank. One request filed against a discover-only job then wipes out every other row
     * on {@code /batch-control/requests/}, including the caller's own, and nothing anywhere
     * tells the caller that anything was withheld. A user who cannot see their own request
     * concludes it was never filed.
     *
     * <p>So every display-time lookup funnels through here instead, and a discover-only job is
     * treated exactly like an invisible or deleted one: that row drops out and the rest of the
     * screen renders normally. The disclosure boundary is unchanged — nothing is shown that
     * {@code Item/Read} would not have shown — and the side effect of the old behaviour
     * (roughly 190 log lines per swallowed lookup) disappears with it.
     *
     * @param fullName the job's full name as stored on the record, possibly {@code null}
     * @return the job, or {@code null} when it does not exist, is not a {@link Job}, or is not
     *         visible to the caller
     */
    @CheckForNull
    public static Job<?, ?> findVisibleJob(@CheckForNull String fullName) {
        if (fullName == null || fullName.isEmpty()) {
            return null;
        }
        try {
            return Jenkins.get().getItemByFullName(fullName, Job.class);
        } catch (AccessDeniedException e) {
            return null;
        }
    }

    /**
     * The {@link Item} form of {@link #findVisibleJob}, for call sites that accept folders too.
     *
     * @param fullName the item's full name, possibly {@code null}
     * @return the item, or {@code null} when it does not exist or is not visible to the caller
     */
    @CheckForNull
    public static Item findVisibleItem(@CheckForNull String fullName) {
        if (fullName == null || fullName.isEmpty()) {
            return null;
        }
        try {
            return Jenkins.get().getItemByFullName(fullName);
        } catch (AccessDeniedException e) {
            // Item/Discover without Item/Read, anywhere along the folder path: the caller may
            // learn that the item exists but not read it, which for display purposes is the
            // same as not existing. Deliberately not logged: this is an ordinary matrix-auth
            // configuration, not an error, and the old behaviour's log flood was itself a
            // finding (S-16).
            return null;
        }
    }

    // ---------------------------------------------------------------- run-link rule (D-44)

    /**
     * Whether the caller holds {@code Item/Read} on the job with this full name. A job that does
     * not exist, is not a {@link Job}, or is only discoverable counts as not readable.
     */
    public static boolean canReadJob(@CheckForNull String jobFullName) {
        Job<?, ?> job = findVisibleJob(jobFullName);
        return job != null && job.hasPermission(Item.READ);
    }

    /**
     * The one run-link rule of every Batch Control screen (D-44, #22): a run links to its build
     * page only when the viewer holds {@code Item/Read} on the job; otherwise the view renders
     * the run as plain text. The history screens follow it too: they stay outside the P-09
     * record-visibility boundary (S-12, every {@code ViewHistory} holder sees every row), but a
     * link to a job the viewer cannot read would only advertise the job's URL and answer 404.
     *
     * @return the root-relative build URL ({@code job/a/job/b/12/}), or {@code null} for plain text
     */
    @CheckForNull
    public static String runUrl(@CheckForNull String jobFullName, int number) {
        if (number <= 0 || !canReadJob(jobFullName)) {
            return null;
        }
        return RunLinks.runUrl(jobFullName, number);
    }

    /**
     * {@link #runUrl(String, int)} for a stored run id of the form {@code jobFullName#number}.
     *
     * @return the root-relative build URL, or {@code null} when the id is malformed or the viewer
     *         may not read the job (the view then renders the id as plain text)
     */
    @CheckForNull
    public static String runUrlFromRunId(@CheckForNull String runId) {
        if (runId == null) {
            return null;
        }
        String url = RunLinks.runUrlFromRunId(runId);
        if (url == null) {
            return null;
        }
        return canReadJob(runId.substring(0, runId.lastIndexOf('#'))) ? url : null;
    }

    /**
     * A run request is visible iff the caller has Manage (or Overall/Administer), OR is the
     * requester, OR is a member of the designated approver set (D-37), OR holds Item/Read on the target job. When the
     * job no longer exists — or is invisible to the caller, including the discover-only case
     * handled by {@link #findVisibleJob} — only requester/approver/Manage still see it.
     */
    public static boolean canSeeRunRequest(RunRequest request) {
        if (isManager()) {
            return true;
        }
        String me = Jenkins.getAuthentication2().getName();
        if (me.equals(request.getRequester()) || request.isDesignatedApprover(me)) {
            return true;
        }
        Job<?, ?> job = findVisibleJob(request.getJobFullName());
        return job != null && job.hasPermission(Item.READ);
    }

    /**
     * A grant request is visible iff the caller has Manage, or is the requester or a member of
     * the designated approver set (D-37).
     */
    public static boolean canSeeGrantRequest(GrantRequest request) {
        if (isManager()) {
            return true;
        }
        String me = Jenkins.getAuthentication2().getName();
        return me.equals(request.getRequester()) || request.isDesignatedApprover(me);
    }

    /**
     * An activation or hold request is visible iff the caller has Manage, or is the requester or
     * a member of the designated approver set (P-09, SPEC item 6a). Unlike a run request,
     * {@code Item/Read} on the job does not make it visible: the request carries the approver set
     * and the reason, which the job page does not otherwise disclose.
     */
    public static boolean canSeeActivationRequest(ActivationRequest request) {
        if (isManager()) {
            return true;
        }
        String me = Jenkins.getAuthentication2().getName();
        return Approvers.sameUser(me, request.getRequester()) || request.isDesignatedApprover(me);
    }

    /** An active grant is visible iff the caller has Manage, or the grant is their own. */
    public static boolean canSeeGrant(Grant grant) {
        return isManager() || Jenkins.getAuthentication2().getName().equals(grant.getUser());
    }

    /** Manage (Overall/Administer implies it, but check both to match P-09 verbatim). */
    private static boolean isManager() {
        Jenkins jenkins = Jenkins.get();
        return jenkins.hasPermission(BatchControlPermissions.MANAGE)
                || jenkins.hasPermission(Jenkins.ADMINISTER);
    }
}
