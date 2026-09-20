package io.jenkins.plugins.batchcontrol.action;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Failure;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 * Routing object for {@code /batch-control/grants/active/...}. Active grants are listed on the
 * grants index page; this subtree only carries the revoke endpoint
 * {@code POST /batch-control/grants/active/<grantId>/revoke}.
 *
 * <p>The whole subtree sits behind the {@link GrantsSection#getTarget()} permission gate; the
 * revoke endpoint additionally requires {@code Manage} (SPEC item 8).
 */
public class ActiveGrantsSection {

    /**
     * Serves the bare {@code /batch-control/grants/active/} URL: nothing to show here, so a GET
     * is redirected to the grants index. Every other verb is refused with 405 (no state change
     * on this URL).
     */
    public void doIndex(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        String method = req.getMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            rsp.setHeader("Allow", "GET, HEAD");
            rsp.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                    "Only GET is allowed on this URL");
            return;
        }
        rsp.sendRedirect2("..");
    }

    /**
     * Stapler: serves {@code /batch-control/grants/active/<grantId>/...}. The id is not resolved
     * against the store here; {@link GrantService#revoke} validates it and rejects unknown or
     * already-revoked grants.
     */
    @CheckForNull
    public Item getDynamic(String grantId) {
        if (grantId == null || grantId.isEmpty()) {
            return null;
        }
        return new Item(grantId);
    }

    /** One active grant; only carries the revoke endpoint. */
    public static final class Item {

        private final String grantId;

        Item(String grantId) {
            this.grantId = grantId;
        }

        /**
         * Serves a GET of {@code /batch-control/grants/active/<grantId>/}: there is no detail
         * view for a single grant, so redirect to the grants index. GET never changes state; the
         * only state change in this subtree is the {@code @RequirePOST} revoke endpoint below.
         */
        public void doIndex(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
            String method = req.getMethod();
            if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
                rsp.setHeader("Allow", "GET, HEAD");
                rsp.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                        "Only GET is allowed on this URL");
                return;
            }
            rsp.sendRedirect2("../..");
        }

        /**
         * POST {@code /batch-control/grants/active/<grantId>/revoke} — immediately revokes the
         * grant. {@code Manage} only (SPEC item 8); a GET never reaches the service because of
         * {@code @RequirePOST}.
         */
        @RequirePOST
        public void doRevoke(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
            Jenkins.get().checkPermission(BatchControlPermissions.MANAGE);
            try {
                GrantService.get().revoke(grantId);
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw new Failure(e.getMessage() == null ? "The revoke was rejected" : e.getMessage());
            }
            // Back to the grants index (base of this URL is .../grants/active/<grantId>/).
            rsp.sendRedirect2("../..");
        }
    }
}
