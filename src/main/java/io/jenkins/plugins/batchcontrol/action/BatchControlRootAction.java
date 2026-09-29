package io.jenkins.plugins.batchcontrol.action;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.model.RootAction;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.ui.SectionAccess;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Global "Batch Control" page at {@code /batch-control}.
 *
 * <p>URL space (fixed contract, asserted by tests):
 * <ul>
 *   <li>{@code /batch-control/} — landing page</li>
 *   <li>{@code /batch-control/requests/} — run request list (paged, newest first)</li>
 *   <li>{@code /batch-control/requests/<id>/} — request detail with decision endpoints</li>
 *   <li>{@code /batch-control/activations/} — activation approval inbox and list;
 *       {@code <id>/} detail with approve/reject/cancel/changeApprover endpoints (SPEC item 6a)</li>
 *   <li>{@code /batch-control/grants/} — grant request list, active grants, create/decision/revoke
 *       endpoints</li>
 *   <li>{@code /batch-control/changes/} — change record list (per month, read-only)</li>
 *   <li>{@code /batch-control/dashboard/} — run record dashboard (last 7 days by default)</li>
 *   <li>{@code /batch-control/incidents/} — incident list; {@code <id>/} detail with
 *       acknowledge/resolve/comment/rerun endpoints</li>
 *   <li>{@code /batch-control/history/} — filtered history, {@code summary} JSON and CSV
 *       exports</li>
 * </ul>
 *
 * <p>The action is <em>absent</em> for a user who holds none of the Batch Control permissions
 * (SPEC item 2, #31): {@link #getIconFileName()} and {@link #getUrlName()} both return
 * {@code null}, so it is not listed and {@code /batch-control/} and every URL beneath it answer
 * 404. A user who holds some Batch Control permission but not the one a section needs gets 403
 * from that section's gate.
 */
@Extension
@Restricted(NoExternalUse.class)
public class BatchControlRootAction implements RootAction {

    /** Whether the current user may use this action at all. */
    private static boolean isVisible() {
        return SectionAccess.hasAny(SectionAccess.anyPermission());
    }

    @Override
    @CheckForNull
    public String getIconFileName() {
        return isVisible() ? "symbol-shield-checkmark-outline plugin-ionicons-api" : null;
    }

    @Override
    public String getDisplayName() {
        return "Batch Control";
    }

    /**
     * {@code null} without any Batch Control permission: per {@link hudson.model.Action#getUrlName()}
     * that makes the action unreachable, so the URL space answers 404 instead of disclosing it.
     */
    @Override
    @CheckForNull
    public String getUrlName() {
        return isVisible() ? "batch-control" : null;
    }

    /** Permissions for the landing page's {@code l:layout}: any Batch Control permission. */
    public Permission[] getViewPermissions() {
        return SectionAccess.anyPermission();
    }

    /** Link predicates for the side panel: each entry is shown only if it can be opened. */
    public SectionAccess getLinks() {
        return new SectionAccess();
    }

    /** Stapler: serves {@code /batch-control/requests/...}. */
    public RequestsSection getRequests() {
        return new RequestsSection();
    }

    /** Stapler: serves {@code /batch-control/activations/...}. */
    public ActivationsSection getActivations() {
        return new ActivationsSection();
    }

    /**
     * Landing-page inbox line: how many PENDING activation or hold requests name the viewer as a
     * designated approver; 0 without {@code BatchControl/Approve}.
     */
    public int getPendingActivationCount() {
        if (!Jenkins.get().hasPermission(BatchControlPermissions.APPROVE)) {
            return 0;
        }
        return ActivationService.get().listPendingFor(Jenkins.getAuthentication2().getName()).size();
    }

    /** Stapler: serves {@code /batch-control/grants/...}. */
    public GrantsSection getGrants() {
        return new GrantsSection();
    }

    /** Stapler: serves {@code /batch-control/changes/...}. */
    public ChangesSection getChanges() {
        return new ChangesSection();
    }

    /** Stapler: serves {@code /batch-control/dashboard/...}. */
    public DashboardSection getDashboard() {
        return new DashboardSection();
    }

    /** Stapler: serves {@code /batch-control/incidents/...}. */
    public IncidentsSection getIncidents() {
        return new IncidentsSection();
    }

    /** Stapler: serves {@code /batch-control/history/...}. */
    public HistorySection getHistory() {
        return new HistorySection();
    }
}
