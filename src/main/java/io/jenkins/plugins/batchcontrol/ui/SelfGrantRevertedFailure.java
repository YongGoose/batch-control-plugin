package io.jenkins.plugins.batchcontrol.ui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Failure;
import hudson.model.Item;
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
 * Tells the saving user that the self-grant guard reverted the authorization part of their save
 * (SPEC item 2, D-48): their Configure permission on the item comes only from a temporary grant,
 * which cannot add permanent permission entries, so the item's authorization entries were
 * restored while the rest of the save was kept.
 *
 * <p>Answers HTTP 403. A browser (a request that accepts {@code text/html}) gets
 * {@code index.jelly} with the standard layout; any other caller (a {@code config.xml} POST,
 * {@code createItem} from a script) gets {@link #getMessage()} as plain text. Only the item's
 * full name is kept (the exception is {@link java.io.Serializable}); it is escaped on the page and
 * written as {@code text/plain} otherwise. It never changes state.
 */
@Restricted(NoExternalUse.class)
public class SelfGrantRevertedFailure extends Failure {

    private static final long serialVersionUID = 1L;

    private final String itemFullName;

    /** @param item the item whose authorization entries the guard restored */
    public SelfGrantRevertedFailure(Item item) {
        super(message(item.getFullName()));
        this.itemFullName = item.getFullName();
    }

    private static String message(String itemFullName) {
        return "The authorization entries of '" + itemFullName + "' were not kept, because your"
                + " Configure permission comes only from a temporary grant, which cannot add"
                + " permanent permission entries. Your other changes were saved. Ask an"
                + " administrator for a permanent entry.";
    }

    public String getItemFullName() {
        return itemFullName;
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
