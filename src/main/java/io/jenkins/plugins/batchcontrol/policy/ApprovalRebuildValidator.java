package io.jenkins.plugins.batchcontrol.policy;

import com.sonyericsson.rebuild.RebuildValidator;
import hudson.Extension;
import hudson.model.Run;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;

/**
 * e2e-03 DEF-25 (SPEC item 6 and section 6 usability): the rebuild plugin's Rebuild and Rebuild
 * Last entries are not offered on builds of a job whose manual runs need an approved run request
 * ({@link RunRequestService#requiresApprovalToRun}), because the queue gate refuses every such
 * rebuild. The rebuild plugin is an optional dependency; without it this extension is not loaded.
 *
 * <p>Only page views are affected: while a page is rendered (any request but one to the rebuild
 * action itself, FD-16) the validator withholds the rebuild action, so no page links it. The
 * build's {@code rebuild/} endpoint still reaches the action, so a direct call is refused by the
 * queue gate with its explanation and recorded (SPEC item 6, e2e-03 DEF-03), and code outside a
 * web request sees the action as before. Context menus are not withheld from (e2e-07 DEF-06):
 * the rebuild plugin would otherwise put an entry with a null URL into them. With run control off, or on a job that does not require
 * approval, nothing changes.
 */
@Extension(optional = true)
@Restricted(NoExternalUse.class)
public class ApprovalRebuildValidator extends RebuildValidator {

    private static final long serialVersionUID = 1L;

    @Override
    public boolean isApplicable(Run build) {
        if (build == null) {
            return false;
        }
        if (!RunRequestService.requiresApprovalToRun(build.getParent()) && !markedForViewer(build)) {
            return false;
        }
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        if (req == null) {
            return false;
        }
        // e2e-07 DEF-06: the rebuild plugin's project-level "Rebuild Last" keeps its icon whatever a
        // validator says, but takes its URL from the last completed build's rebuild action. Withheld
        // there, the entry has a null URL, core's context menu passes it on, and the new job page's
        // "More actions" menu fails as a whole. Context menus therefore see the rebuild entries with
        // their real URLs; they lead to the rebuild action, which is reachable (below), and running
        // the rebuild is refused by the queue gate with its explanation and recorded.
        if (isContextMenu(req)) {
            return false;
        }
        // e2e-04 FD-16: a POST that re-renders a page (a refused form showing the job sidebar) is a
        // page view too; only a request to the rebuild action itself (its page or its submission)
        // reaches the action, so the queue gate refuses and records the run as before.
        return !targetsRebuild(req);
    }

    /** Whether the request asks for a context menu (core's {@code contextMenu}, {@code childrenContextMenu}). */
    private static boolean isContextMenu(StaplerRequest2 req) {
        String path = req.getRequestURI();
        return path != null && path.matches(".*/(contextMenu|childrenContextMenu)/?");
    }

    /**
     * e2e-03 DEF-41 (D-58c): a run replayed under a grant cannot be re-run by anyone but an
     * administrator, so Rebuild is not offered on it to anyone else (while change control is on).
     */
    @SuppressWarnings("deprecation") // getActions(): the persisted actions only, see below
    private static boolean markedForViewer(Run<?, ?> build) {
        if (!io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration.get().isChangeControlEnabled()
                || jenkins.model.Jenkins.get().hasPermission(jenkins.model.Jenkins.ADMINISTER)) {
            return false;
        }
        // The persisted actions only: getAction(Class) would ask the transient action factories,
        // among them the rebuild plugin's, which asks this validator again (endless recursion).
        for (hudson.model.Action action : build.getActions()) {
            if (action instanceof io.jenkins.plugins.batchcontrol.queue.ReplayUnderGrantAction) {
                return true;
            }
        }
        return false;
    }

    /** Whether the request goes to the rebuild plugin's action ({@code .../rebuild} or below it). */
    private static boolean targetsRebuild(StaplerRequest2 req) {
        String path = req.getRequestURI();
        // S-28-13: the rebuild action of a build (a number or a permalink), not an item named "rebuild".
        return path != null && path.matches(".*/(\\d+|last[A-Za-z]*Build)/rebuild(/.*)?");
    }
}
