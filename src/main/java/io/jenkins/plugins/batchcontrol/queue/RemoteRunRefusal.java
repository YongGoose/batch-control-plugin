package io.jenkins.plugins.batchcontrol.queue;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Failure;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

/**
 * The HTTP answer to a refused build-token (remote) submission of an approval-required job
 * (SPEC item 6, SPEC section 6 usability, e2e re-audit DEF-33/DEF-34).
 *
 * <p>The caller is a script reading a status line: core's {@code /job/X/build?token=} would
 * otherwise answer its "scheduled" 302 and build-token-root a 403 with an empty body. This answers
 * HTTP 403 with a one-paragraph {@code text/plain} body saying that the job needs an approved run
 * request and where to submit one. It never changes state.
 */
@Restricted(NoExternalUse.class)
final class RemoteRunRefusal extends Failure {

    private static final long serialVersionUID = 1L;

    RemoteRunRefusal(String message) {
        super(message);
    }

    @Override
    public void generateResponse(StaplerRequest2 req, StaplerResponse2 rsp, Object node,
                                 @CheckForNull Throwable throwable)
            throws IOException, ServletException {
        rsp.setStatus(HttpServletResponse.SC_FORBIDDEN);
        rsp.setContentType("text/plain;charset=UTF-8");
        PrintWriter writer = rsp.getWriter();
        writer.println(getMessage());
        writer.flush();
    }

    @Override
    public void generateResponse(StaplerRequest2 req, StaplerResponse2 rsp, Object node)
            throws IOException, ServletException {
        generateResponse(req, rsp, node, null);
    }
}
