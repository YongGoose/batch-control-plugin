package io.jenkins.plugins.batchcontrol.ui;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

/**
 * HTTP verb guard for the Batch Control URL spaces.
 *
 * <p>The screens are served straight from {@code index.jelly} (no {@code doIndex}), and a Jelly
 * view renders for any verb. Nothing in these subtrees is modified by {@code PUT},
 * {@code DELETE} or {@code PATCH} — records are append-only (SPEC item 4), and every state
 * change is a named {@code @RequirePOST} endpoint — so the section gates call
 * {@link #refuseUnsupported()} to answer 405 for those verbs instead of rendering a page.
 */
@Restricted(NoExternalUse.class)
public final class HttpVerbs {

    private static final String ALLOW = "GET, HEAD, POST";

    private HttpVerbs() {
    }

    /**
     * Throws a 405 response unless the current request is {@code GET}, {@code HEAD} or
     * {@code POST}. Call it after the permission check, so an unauthorised caller learns nothing
     * from the answer.
     */
    public static void refuseUnsupported() {
        StaplerRequest2 current = Stapler.getCurrentRequest2();
        if (current == null) {
            return;
        }
        String method = current.getMethod();
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                || "POST".equalsIgnoreCase(method)) {
            return;
        }
        throw new MethodNotAllowed();
    }

    /**
     * The GET-only guard for a read-only URL served by a {@code do*} method (JSON and CSV
     * exports, redirect stubs): answers 405 with {@code Allow: GET, HEAD} unless the request is
     * {@code GET} or {@code HEAD}. Call it after the permission check.
     *
     * @return true when the request was refused and the caller must return without writing
     */
    public static boolean refuseNonGet(StaplerRequest2 req, StaplerResponse2 rsp)
            throws IOException {
        String method = req.getMethod();
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
            return false;
        }
        rsp.setHeader("Allow", "GET, HEAD");
        rsp.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                "This URL is read-only; only GET is allowed");
        return true;
    }

    /** 405 with an {@code Allow} header. */
    private static final class MethodNotAllowed extends HttpResponses.HttpResponseException {

        private static final long serialVersionUID = 1L;

        @Override
        public void generateResponse(StaplerRequest2 req, StaplerResponse2 rsp, Object node)
                throws IOException {
            rsp.setHeader("Allow", ALLOW);
            rsp.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                    "Batch Control records are append-only; use the named POST endpoints");
        }
    }
}
