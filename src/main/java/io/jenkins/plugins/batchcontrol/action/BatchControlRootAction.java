package io.jenkins.plugins.batchcontrol.action;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.model.RootAction;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import jenkins.model.Jenkins;

/**
 * Global "Batch Control" page at {@code /batch-control}.
 *
 * <p>URL space (fixed contract, asserted by tests):
 * <ul>
 *   <li>{@code /batch-control/} — landing page</li>
 *   <li>{@code /batch-control/requests/} — run request list (paged, newest first)</li>
 *   <li>{@code /batch-control/requests/<id>/} — request detail with decision endpoints</li>
 *   <li>{@code /batch-control/grants/} — grant request list, active grants, create/decision/revoke
 *       endpoints</li>
 *   <li>{@code /batch-control/changes/} — change record list (per month, read-only)</li>
 * </ul>
 *
 * <p>The sidebar icon is hidden when the user has no plugin permission <em>and</em> run control
 * is off, but the URLs stay routable; view access is enforced by
 * {@link RequestsSection#getTarget()}.
 */
@Extension
public class BatchControlRootAction implements RootAction {

    @Override
    @CheckForNull
    public String getIconFileName() {
        Jenkins jenkins = Jenkins.get();
        boolean anyPermission = jenkins.hasPermission(BatchControlPermissions.REQUEST)
                || jenkins.hasPermission(BatchControlPermissions.APPROVE)
                || jenkins.hasPermission(BatchControlPermissions.REQUEST_GRANT)
                || jenkins.hasPermission(BatchControlPermissions.VIEW_HISTORY)
                || jenkins.hasPermission(BatchControlPermissions.MANAGE);
        if (!anyPermission && !BatchControlGlobalConfiguration.get().isRunControlEnabled()) {
            // Hide from the sidebar but keep the URL space routable.
            return null;
        }
        return "symbol-check";
    }

    @Override
    public String getDisplayName() {
        return "Batch Control";
    }

    @Override
    public String getUrlName() {
        return "batch-control";
    }

    /** Stapler: serves {@code /batch-control/requests/...}. */
    public RequestsSection getRequests() {
        return new RequestsSection();
    }

    /** Stapler: serves {@code /batch-control/grants/...}. */
    public GrantsSection getGrants() {
        return new GrantsSection();
    }

    /** Stapler: serves {@code /batch-control/changes/...}. */
    public ChangesSection getChanges() {
        return new ChangesSection();
    }
}
