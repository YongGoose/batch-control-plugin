package io.jenkins.plugins.batchcontrol.ui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Failure;
import hudson.model.Item;
import hudson.model.ItemGroup;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.ItemKind;
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
 *
 * <p>D-71: when no window can allow the change at all — Delete of an item group that is not a
 * job (a folder, a multibranch project, an organization folder), or Create in a group that is not
 * a regular folder — nobody is pointed at the form (the request would be refused at submission);
 * the page and the message say that an administrator has to make the change.
 */
@Restricted(NoExternalUse.class)
public class GrantRequiredFailure extends Failure {

    private static final long serialVersionUID = 1L;

    private final String itemFullName;

    private final String action;

    private final boolean canRequest;

    /** D-71: whether a window carrying {@link #action} can name the item at all. */
    private final boolean windowApplies;

    /** The item's kind in words ("Pipeline", "Folder", ...), for the page and the message. */
    private final String itemKindName;

    /** A sub-item of a job (a matrix configuration, a Maven module), which no window can name. */
    private final boolean partOfJob;

    /** Whether the item contains other items (deleting it would delete them too). */
    private final boolean group;

    /**
     * @param item the item the refused change is about; for a refused Create, the folder the new
     *             item was to be created in (the window would name that folder)
     * @param action the permission window that would allow it
     * @param change the refused change in words, for example "deleting" or "creating an item in"
     */
    public GrantRequiredFailure(Item item, GrantAction action, String change) {
        super(message(item, action, change, canRequestGrants()));
        this.itemFullName = item.getFullName();
        this.action = action.name();
        this.canRequest = canRequestGrants();
        this.windowApplies = GrantRequestLinks.applies(item, action);
        this.itemKindName = kindName(item);
        this.partOfJob = ItemKind.of(item) == null;
        this.group = item instanceof ItemGroup;
    }

    /** Whether the current user may open the Grants screen and submit a grant request. */
    static boolean canRequestGrants() {
        return new SectionAccess().isGrants()
                && Jenkins.get().hasPermission(BatchControlPermissions.REQUEST_GRANT);
    }

    private static String kindName(Item item) {
        ItemKind kind = ItemKind.of(item);
        return kind == null ? "item" : kind.getDisplayName();
    }

    private static String message(Item item, GrantAction action, String change, boolean canRequest) {
        String name = action.name();
        String window = name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT);
        if (!GrantRequestLinks.applies(item, action)) {
            // D-71: no window can allow it, so nobody is told to request one.
            String head = "Change control: " + change + " '" + item.getFullName() + "' was refused, and no "
                    + "permission window can allow it: ";
            if (ItemKind.of(item) == null) {
                return head + "it is part of another job and cannot be named by a permission window. Ask a "
                        + "Jenkins administrator to make this change for you.";
            }
            head += "a window's " + window + " applies only to "
                    + (action == GrantAction.DELETE ? "a job" : "a folder") + ", not to " + kindName(item) + " '"
                    + item.getFullName() + "'. ";
            if (action != GrantAction.DELETE) {
                return head + "Ask a Jenkins administrator to make this change for you.";
            }
            return head + (item instanceof ItemGroup ? "Deleting it would also delete every item inside it. " : "")
                    + "Ask a Jenkins administrator to delete (or move) it.";
        }
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

    /**
     * Whether the refused user may request the window and a window can allow the change (D-71),
     * so the page links the form.
     */
    public boolean isCanRequest() {
        return canRequest && windowApplies;
    }

    /**
     * D-71: whether a permission window can allow the change at all. {@code false} for Delete of
     * an item group that is not a job and for Create in a group that is not a regular folder:
     * only an administrator can make such a change.
     */
    public boolean isWindowApplies() {
        return windowApplies;
    }

    /** Whether the refused change is a delete (the page then says "delete (or move) it"). */
    public boolean isDelete() {
        return GrantAction.DELETE.name().equals(action);
    }

    /** The kind of the item in words, for example "Folder" or "Multibranch Pipeline". */
    public String getItemKindName() {
        return itemKindName;
    }

    /** Whether the item is part of a job (no window can name it), for the page's wording. */
    public boolean isPartOfJob() {
        return partOfJob;
    }

    /** Whether the item contains other items, for the page's wording of a refused delete. */
    public boolean isGroup() {
        return group;
    }

    /**
     * Root-relative URL of the new-request form prefilled for the item and action
     * ({@code batch-control/grants/new?scopeFullName=<item>&actions=<ACTION>}), or {@code null}
     * when no window can allow the change. The Grants screen resolves the name again as the
     * viewer, so nothing here is echoed unchecked.
     */
    @CheckForNull
    public String getRequestUrl() {
        return windowApplies ? GrantRequestLinks.url(itemFullName, GrantAction.valueOf(action)) : null;
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
