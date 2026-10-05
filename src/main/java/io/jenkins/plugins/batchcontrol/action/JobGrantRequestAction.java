package io.jenkins.plugins.batchcontrol.action;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Util;
import hudson.model.Action;
import hudson.model.Item;
import hudson.model.Job;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.ui.Dialogs;
import jenkins.model.Jenkins;
import jenkins.model.menu.event.DialogEvent;
import jenkins.model.menu.event.Event;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Sidebar entry "Request Change Permission" on a job, linking to the grant request screen with
 * this job already filled in (U-01).
 *
 * <p>Nothing about the change-control side of the plugin was discoverable from a job. Opening or
 * saving {@code /job/<name>/configure} without an active permission window answers core's stock
 * 403 "missing the Job/Configure permission", which says nothing about permission windows
 * existing or about {@code /batch-control/grants/} being where they are requested, and D-33
 * settled that intercepting that refusal page is not acceptable. The run side has no such
 * problem because {@link JobRequestAction} puts "Request Run" on the sidebar. This makes the
 * change side symmetric, and — the point of the fix — it is visible <em>before</em> the 403
 * rather than after it.
 *
 * <p>This action holds no URL space of its own: {@link #getUrlName()} returns a link to the
 * existing grant screen (relative to the job's URL, so it keeps the context path), pre-filling
 * this job's full name ({@code scopeFullName=<job>}; D-71: the window names this job only, see
 * {@code GrantsSection.getPrefillScopeFullName}), so the user lands on a form that is
 * already about the job they came from. There is no view, no {@code do*} method and no state:
 * every permission check that matters is the grant screen's own.
 *
 * <h2>Who sees it</h2>
 * All three conditions must hold, so the entry appears exactly for the people it can help:
 * <ol>
 *   <li>change control is on — with the switch off there are no permission windows to request,
 *       and SPEC item 1 requires that no change-control UI appears (an acceptance criterion in
 *       its own right);</li>
 *   <li>the caller holds {@code BatchControl/RequestGrant} — without it the grant screen's own
 *       gate would refuse them, and advertising a link that 403s is the problem U-01 is
 *       about, not a fix for it;</li>
 *   <li>the caller does <strong>not</strong> already hold {@code Item/Configure} on this job.
 *       Offering "request permission" to someone who already has the permission is noise, and
 *       it would put the entry on every administrator's sidebar on every job. This also covers
 *       the caller whose window is open right now: while a granted window confers
 *       {@code Item/Configure} the entry is absent, and it comes back by itself when the window
 *       expires — which is the moment the user needs it.</li>
 * </ol>
 * The job page already requires {@code Item/Read}, so no further visibility check is needed.
 */
@Restricted(NoExternalUse.class)
public class JobGrantRequestAction implements Action {

    /** Sidebar caption. Deliberately distinct from "Request Run" (U-02). */
    public static final String REQUEST_CHANGE_LABEL = "Request Change Permission";

    private final Job<?, ?> job;

    public JobGrantRequestAction(Job<?, ?> job) {
        this.job = job;
    }

    public Job<?, ?> getJob() {
        return job;
    }

    @Override
    @CheckForNull
    public String getIconFileName() {
        if (!BatchControlGlobalConfiguration.get().isChangeControlEnabled()) {
            return null;
        }
        if (!Jenkins.get().hasPermission(BatchControlPermissions.REQUEST_GRANT)) {
            return null;
        }
        if (job.hasPermission(Item.CONFIGURE)) {
            // Already able to change this job (standing permission, or a window open right now).
            return null;
        }
        return "symbol-key-outline plugin-ionicons-api";
    }

    @Override
    public String getDisplayName() {
        return REQUEST_CHANGE_LABEL;
    }

    /**
     * A link to the grant screen rather than a sub-URL of the job, written relative to the job's
     * own URL ({@code ../../batch-control/grants/?...} for {@code job/<name>/}).
     *
     * <p>Hosting review 2026-10-02: this used to be the root-relative
     * {@code /batch-control/grants/...}. Core's classic sidebar ({@code Functions.getActionUrl})
     * prefixes such a name with the context path, but the app bar and menus of the new job page
     * render {@link Action#getEvent()}, whose default is a link to {@link #getUrlName()} that the
     * page's script leaves untouched when it starts with {@code /} — so under a context path
     * ({@code /jenkins} with {@code mvn hpi:run}) the entry led to {@code /batch-control/...} on
     * the host and answered 404. Both renderers join a relative name to the job's URL (the classic
     * one to {@code <context>/<job.url>}, the new one to the URL of the job in the current
     * request, which {@link Job#getUrl()} also follows), so climbing one {@code ../} per segment
     * of the job URL lands on {@code <context>/batch-control/} in both, in folders and under views
     * alike. Overriding {@code getEvent()} instead would need {@code LinkEvent}, a
     * {@code @Restricted(Beta.class)} API in the 2.568.x baseline.
     *
     * <p>e2e-06 DEF-01: the URL carries one query parameter and no {@code &}. The new job page's
     * menu escapes the event URL a second time, so {@code &} arrived as {@code &amp;} and the
     * browser sent {@code amp;scopeFullName}, leaving the job unfilled. D-71: a window names one
     * item and there is no scope type, so the full name is the only parameter the form needs.
     *
     * <p>No routing is added under {@code /job/<name>/}: a name containing {@code ../} never
     * matches a URL token. The full name is passed through {@link Util#rawEncode} because
     * {@code Functions.getActionUrl} parses the value as a URI first and drops the sidebar entry
     * if it does not parse — a job name containing a space would be enough.
     */
    @Override
    public String getUrlName() {
        // D-66: the full-page form (the Grants list no longer starts with it).
        return toRoot(job) + "batch-control/grants/new?scopeFullName=" + Util.rawEncode(job.getFullName());
    }

    /**
     * D-66: root-relative URL of the dialog form, prefilled for this job (one query parameter, see
     * {@link #getUrlName()}). The classic sidebar entry ({@code action.jelly}) opens it in core's
     * dialog.
     */
    public String getDialogUrl() {
        return "batch-control/grants/dialog?scopeFullName=" + Util.rawEncode(job.getFullName());
    }

    /**
     * D-66: on the new job page the entry opens the dialog form, as core's own "Build with
     * Parameters" does ({@link DialogEvent}); the URL is relative to the job's URL.
     */
    @Override
    public Event getEvent() {
        return DialogEvent.of(toRoot(job) + getDialogUrl());
    }

    /**
     * {@code ../} once per path segment of {@link Job#getUrl()}, the way from the job's URL back
     * to the Jenkins root (which carries the context path, if any).
     */
    static String toRoot(Job<?, ?> job) {
        return Dialogs.toRoot(job.getUrl());
    }
}
