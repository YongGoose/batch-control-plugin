package io.jenkins.plugins.batchcontrol.ui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Failure;
import hudson.model.Item;
import hudson.model.Job;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

/**
 * The refusal of a change that needs a permission window the user does not hold (SPEC item 8),
 * for example the delete veto (e2e-03 DEF-26). Thrown in place of a plain {@link Failure}.
 *
 * <p>The advice depends on the refused user, and is decided when the refusal is built (in the
 * refused user's own request): a user who may open the Grants screen and request windows
 * ({@code BatchControl/RequestGrant}, change control on) is pointed at the new-request form
 * prefilled for the item and action; anyone else is told whom to ask, because the Grants screen
 * would answer 403 to them. {@link #getMessage()} carries the same advice as plain text for the
 * CLI and scripts; in a browser {@link #generateResponse} renders {@code index.jelly}, which
 * links the form only for the first kind of user. HTTP 400 like the {@link Failure} it replaces;
 * it never changes state.
 */
@Restricted(NoExternalUse.class)
public class GrantRequiredFailure extends Failure {

    private static final long serialVersionUID = 1L;

    private final String itemFullName;

    private final String scopeType;

    private final String action;

    private final boolean canRequest;

    /**
     * @param item the item the refused change is about
     * @param action the permission window that would allow it
     * @param change the refused change in words, for example "deleting"
     */
    public GrantRequiredFailure(Item item, GrantAction action, String change) {
        super(message(item, action, change, canRequestGrants()));
        this.itemFullName = item.getFullName();
        // D-65: a folder is suggested as FOLDER, not FOLDER_ONLY: deleting a folder takes its
        // nested folders with it, which a folder-only window does not reach.
        this.scopeType = item instanceof Job ? "JOB" : "FOLDER";
        this.action = action.name();
        this.canRequest = canRequestGrants();
    }

    /** Whether the current user may open the Grants screen and submit a grant request. */
    static boolean canRequestGrants() {
        return new SectionAccess().isGrants()
                && Jenkins.get().hasPermission(BatchControlPermissions.REQUEST_GRANT);
    }

    private static String message(Item item, GrantAction action, String change, boolean canRequest) {
        String name = action.name();
        String window = name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT);
        String head = "Change control: " + change + " '" + item.getFullName()
                + "' needs an approved " + window + " permission window, and you do not hold one. ";
        if (canRequest) {
            return head + "Request one under Batch Control > Grants (Request Change Permission, action "
                    + window + ") and try again once it is approved.";
        }
        return head + "You may not request permission windows yourself: ask a Jenkins "
                + "administrator to give you the Batch Control/RequestGrant permission, or to make "
                + "this change for you.";
    }

    public String getItemFullName() {
        return itemFullName;
    }

    /** "Delete", "Configure" or "Create", for the page. */
    public String getActionLabel() {
        return action.charAt(0) + action.substring(1).toLowerCase(Locale.ROOT);
    }

    /** Whether the refused user may request the window, so the page links the form. */
    public boolean isCanRequest() {
        return canRequest;
    }

    /**
     * Root-relative URL of the new-request form prefilled for the item and action. The Grants
     * screen resolves the name again as the viewer, so nothing here is echoed unchecked.
     */
    public String getRequestUrl() {
        return GrantRequestLinks.url(scopeType, itemFullName, action);
    }

    /** The item as the viewer sees it, or {@code null}. */
    @CheckForNull
    public Item getItem() {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        return jenkins == null ? null : Visibility.findVisibleItem(itemFullName);
    }

    @Override
    public void generateResponse(StaplerRequest2 req, StaplerResponse2 rsp, Object node,
                                 @CheckForNull Throwable throwable)
            throws IOException, ServletException {
        RequestDispatcher view = req.getView(this, "index.jelly");
        if (view == null) {
            super.generateResponse(req, rsp, node, throwable);
            return;
        }
        rsp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        view.forward(req, rsp);
    }

    @Override
    public void generateResponse(StaplerRequest2 req, StaplerResponse2 rsp, Object node)
            throws IOException, ServletException {
        generateResponse(req, rsp, node, null);
    }
}
