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
 * <p>Only page views are affected: while a page is rendered (any request but a POST to the
 * rebuild action, FD-16) the validator withholds the rebuild action, so no screen links it. A
 * POST to the build's {@code rebuild/} endpoint still reaches the action, so a direct call is refused by the queue
 * gate with its explanation and recorded (SPEC item 6, e2e-03 DEF-03), and code outside a web
 * request sees the action as before. With run control off, or on a job that does not require
 * approval, nothing changes.
 */
@Extension(optional = true)
@Restricted(NoExternalUse.class)
public class ApprovalRebuildValidator extends RebuildValidator {

    private static final long serialVersionUID = 1L;

    @Override
    public boolean isApplicable(Run build) {
        if (build == null || !RunRequestService.requiresApprovalToRun(build.getParent())) {
            return false;
        }
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        if (req == null) {
            return false;
        }
        // e2e-04 FD-16: a POST that re-renders a page (a refused form showing the job sidebar) is a
        // page view too; only a request to the rebuild action itself reaches the action, so the
        // queue gate refuses and records it as before.
        return !"POST".equalsIgnoreCase(req.getMethod()) || !targetsRebuild(req);
    }

    /** Whether the request goes to the rebuild plugin's action ({@code .../rebuild} or below it). */
    private static boolean targetsRebuild(StaplerRequest2 req) {
        String path = req.getRequestURI();
        // S-28-13: the rebuild action of a build (a number or a permalink), not an item named "rebuild".
        return path != null && path.matches(".*/(\\d+|last[A-Za-z]*Build)/rebuild(/.*)?");
    }
}
