package io.jenkins.plugins.batchcontrol.ui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Util;
import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.model.ItemKind;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Links to the new grant request form on the Grants screen, prefilled for one item and one
 * action (U-01 convention: {@code new?scopeFullName=&actions=}, D-66; D-71: a window names exactly
 * one item, so there is no scope type). Used by refusal pages (a change or a move that needs a
 * permission window).
 *
 * <p>Read-only. The item is resolved as the viewer, so an item the viewer cannot see yields no
 * link, and the Grants screen resolves the name again before it prefills anything. No link is
 * offered for an action that cannot apply to the item ({@link #applies}), because the request
 * would be refused at submission.
 */
@Restricted(NoExternalUse.class)
public final class GrantRequestLinks {

    private GrantRequestLinks() {
    }

    /** Whether the current user may open the Grants screen and submit a grant request. */
    public static boolean canRequest() {
        return GrantRequiredFailure.canRequestGrants();
    }

    /**
     * Root-relative URL of the prefilled form, or {@code null} when the item is not visible to
     * the viewer, cannot be named by a window (the Jenkins root, a sub-item of a job), the action
     * is not one a window can carry, or it cannot apply to the item (D-71).
     *
     * @param fullName full name of the job or folder the window would name; for CREATE, the
     *                 folder the new item is to be created in
     * @param action a {@link GrantAction} name (CREATE, CONFIGURE, DELETE)
     */
    @CheckForNull
    public static String url(@CheckForNull String fullName, @CheckForNull String action) {
        GrantAction parsed;
        try {
            parsed = action == null ? null : GrantAction.valueOf(action);
        } catch (IllegalArgumentException e) {
            parsed = null;
        }
        Item item = Visibility.findVisibleItem(fullName);
        if (parsed == null || item == null || !applies(item, parsed)) {
            return null;
        }
        return url(item.getFullName(), parsed);
    }

    /**
     * D-71: whether a permission window carrying {@code action} can name {@code item}: it must be
     * a top-level item (a job or a folder of any kind); CREATE applies only to a regular folder
     * ({@link GrantScope#createAppliesTo}) and DELETE only to a job
     * ({@link GrantScope#deleteAppliesTo}). The service refuses the others at submission.
     */
    public static boolean applies(@CheckForNull Item item, GrantAction action) {
        if (ItemKind.of(item) == null) {
            return false;
        }
        switch (action) {
            case CREATE:
                return GrantScope.createAppliesTo(item);
            case DELETE:
                return GrantScope.deleteAppliesTo(item);
            default:
                return true;
        }
    }

    /** The form page's URL for an item already resolved and an action that applies to it. */
    static String url(String fullName, GrantAction action) {
        // D-66: the form page (the Grants list no longer carries the form).
        return "batch-control/grants/new?scopeFullName=" + Util.rawEncode(fullName) + "&actions=" + action.name();
    }
}
