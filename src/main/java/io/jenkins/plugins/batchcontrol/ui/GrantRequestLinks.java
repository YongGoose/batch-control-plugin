package io.jenkins.plugins.batchcontrol.ui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Util;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Job;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Links to the new grant request form on the Grants screen, prefilled for one item and one
 * action (U-01 convention: {@code new?scopeType=&scopeFullName=&actions=}, D-66).
 * Used by refusal pages (a change or a move that needs a permission window, backlog #83).
 *
 * <p>Read-only. The item is resolved as the viewer, so an item the viewer cannot see yields no
 * link, and the Grants screen resolves the name again before it prefills anything.
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
     * the viewer, is not a job or folder (the Jenkins root has no permission window), or the
     * action is not one a window can carry.
     *
     * @param fullName full name of the job or folder the window would cover
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
        if (parsed == null || item == null) {
            return null;
        }
        // D-65: a folder is suggested as FOLDER (the folder and everything below it), which covers
        // every change these refusals are about, nested folders included; the form also offers
        // FOLDER_ONLY, the narrower window, which does not reach into nested folders.
        String type;
        if (item instanceof Job) {
            type = "JOB";
        } else if (item instanceof ItemGroup) {
            type = "FOLDER";
        } else {
            return null;
        }
        return url(type, item.getFullName(), parsed.name());
    }

    static String url(String scopeType, String fullName, String action) {
        // D-66: the form page (the Grants list no longer carries the form).
        return "batch-control/grants/new?scopeType=" + scopeType + "&scopeFullName="
                + Util.rawEncode(fullName) + "&actions=" + action;
    }
}
