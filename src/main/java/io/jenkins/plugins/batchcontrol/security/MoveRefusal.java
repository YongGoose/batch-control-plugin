package io.jenkins.plugins.batchcontrol.security;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Failure;
import hudson.model.Item;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.springframework.security.access.AccessDeniedException;

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

    /** Full name of the item whose move was refused (kept as a name: the exception is serializable). */
    private final String itemFullName;

    /** Full name of the destination group; empty for the Jenkins root. */
    private final String destinationFullName;
    private final boolean deleteMissing;
    private final boolean createMissing;

    MoveRefusal(String message, String itemFullName, String destinationFullName,
                boolean deleteMissing, boolean createMissing) {
        super(message);
        this.itemFullName = itemFullName;
        this.destinationFullName = destinationFullName == null ? "" : destinationFullName;
        this.deleteMissing = deleteMissing;
        this.createMissing = createMissing;
    }

    /**
     * Full name of the group the item was to be moved into, or the empty string for the Jenkins
     * root (which no permission window can cover). The refusal page links the Create
     * window request for it.
     */
    public String getDestinationFullName() {
        return destinationFullName;
    }

    /**
     * Whether the refusal lacks Item/Delete on the item and a Delete window on the item would supply
     * it. {@code false} when a missing part cannot come from any window: Delete on an item group
     * that is not a job (D-71), or Create in the Jenkins root (no window names it) or in a group that
     * is not a regular folder. Such a move needs an administrator, so no window is suggested.
     */
    public boolean isDeleteMissing() {
        return deleteMissing;
    }

    /**
     * Whether the refusal lacks Item/Create on the destination, either entirely or because the
     * active Create window's name restriction does not admit the item's name; a Create window on
     * {@link #getDestinationFullName()} that admits the name would supply it. {@code false} when
     * the move cannot be authorised through windows at all (see {@link #isDeleteMissing()}).
     */
    public boolean isCreateMissing() {
        return createMissing;
    }

    /** Full name of the item whose move was refused. */
    public String getItemFullName() {
        return itemFullName;
    }

    /**
     * For the refusal page's link back to the item: the item's URL relative to the Jenkins root
     * (for example {@code job/prod/job/x/}), or {@code null} when the current user may not open it.
     * The item is looked up as the current user, so Jenkins' Item/Read rule applies; an item the
     * user can only discover (Item/Discover) gets no link either, since opening it would be refused.
     */
    @CheckForNull
    public String getItemUrl() {
        try {
            Item item = Jenkins.get().getItemByFullName(itemFullName);
            return item == null ? null : item.getUrl();
        } catch (AccessDeniedException e) {
            return null; // discoverable but not readable
        }
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
