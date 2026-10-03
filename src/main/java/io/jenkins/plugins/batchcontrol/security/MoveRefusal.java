package io.jenkins.plugins.batchcontrol.security;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Failure;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

/**
 * D-59: the answer to a move that {@link MoveGuard} refused, in the D-48 style: HTTP 403 and a
 * plain message saying what the move needs and what the user is missing. A browser (a request
 * that accepts {@code text/html}) gets the class's {@code index.jelly} with the standard layout
 * when that view exists; any other caller, and a browser while the view does not exist, gets
 * {@link #getMessage()} as {@code text/plain}. Nothing has changed when it is sent.
 *
 * <p>It is also thrown from the grant layer's Item/Move check; Stapler serves an exception that is
 * an {@link org.kohsuke.stapler.HttpResponse} as that response.
 */
@Restricted(NoExternalUse.class)
public class MoveRefusal extends Failure {

    private static final long serialVersionUID = 1L;

    MoveRefusal(String message) {
        super(message);
    }

    @Override
    public void generateResponse(StaplerRequest2 req, StaplerResponse2 rsp, Object node,
                                 @CheckForNull Throwable throwable)
            throws IOException, ServletException {
        rsp.setStatus(HttpServletResponse.SC_FORBIDDEN);
        String accept = req.getHeader("Accept");
        RequestDispatcher view = accept != null && accept.contains("text/html")
                ? req.getView(this, "index.jelly") : null;
        if (view != null) {
            view.forward(req, rsp);
            return;
        }
        rsp.setContentType("text/plain;charset=UTF-8");
        rsp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        rsp.setHeader("X-Content-Type-Options", "nosniff");
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
