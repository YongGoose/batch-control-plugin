package io.jenkins.plugins.batchcontrol.action;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Util;
import hudson.model.Action;
import hudson.model.Item;
import hudson.model.Job;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import jenkins.model.Jenkins;
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
 * <p>This action holds no URL space of its own: {@link #getUrlName()} returns a root-relative
 * link to the existing grant screen, pre-selecting {@code JOB} scope and this job's full name
 * (see {@code GrantsSection.getPrefillScopeFullName}), so the user lands on a form that is
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
     * A root-relative link to the grant screen rather than a sub-URL of the job.
     *
     * <p>{@code Functions.getActionUrl} prefixes a name starting with {@code /} with the context
     * path and otherwise leaves it alone, so no routing is added under {@code /job/<name>/}. The
     * full name is passed through {@link Util#rawEncode} because that method parses the value as
     * a URI first and drops the sidebar entry if it does not parse — a job name containing a
     * space would be enough.
     */
    @Override
    public String getUrlName() {
        return "/batch-control/grants/?scopeType=JOB&scopeFullName="
                + Util.rawEncode(job.getFullName());
    }
}
