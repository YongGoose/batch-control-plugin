package io.jenkins.plugins.batchcontrol.ui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Failure;
import hudson.model.Item;
import hudson.model.Job;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
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
 * Tells the saving user that the guard reverted the authorization part of their save (SPEC item 2,
 * D-48, D-58a, D-58b): the item is guarded because a permission window covers it or it was
 * changed under one and not yet reviewed, so a widening of its authorization is undone whoever
 * saves it. The rest of the save was kept. The text says how the guard ends (Mark as reviewed)
 * and, for a viewer who may open it, links the job's Batch Control page (DEF-39).
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
        return "Some authorization entries of '" + itemFullName + "' were not kept: the item is"
                + " guarded because it is covered by a temporary permission window, or was changed"
                + " under one and has not been reviewed. Changes that widen its authorization are"
                + " undone, whoever saves it, until someone uses Mark as reviewed on the Batch"
                + " Control monitor under Manage Jenkins (an administrator) or on the job's Batch"
                + " Control page (a user with Configure permission on it and Batch Control/Request)."
                + " Your other changes were saved. While a window still covers it, ask an"
                + " administrator.";
    }

    /**
     * DEF-39: the root-relative URL of the job's Batch Control page, where "Mark as reviewed" is
     * offered, when the viewer may open it (the page exists only for a job, and only for a
     * {@code BatchControl/Request} holder who may read the job); else {@code null}.
     */
    @CheckForNull
    public String getReviewPageUrl() {
        Item item = Visibility.findVisibleItem(itemFullName);
        if (!(item instanceof Job) || !item.hasPermission(BatchControlPermissions.REQUEST)) {
            return null;
        }
        return item.getUrl() + "batch-control/";
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
