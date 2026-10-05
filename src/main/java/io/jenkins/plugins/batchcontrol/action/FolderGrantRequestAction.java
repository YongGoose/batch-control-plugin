package io.jenkins.plugins.batchcontrol.action;

import com.cloudbees.hudson.plugins.folder.AbstractFolder;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Util;
import hudson.model.Action;
import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.ui.Dialogs;
import jenkins.model.Jenkins;
import jenkins.model.menu.event.DialogEvent;
import jenkins.model.menu.event.Event;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * R4-14 (D-66): the folder counterpart of {@link JobGrantRequestAction}, a "Request Change
 * Permission" entry on a folder that opens the grant request form prefilled for the folder
 * ({@code scopeFullName=<folder>}). D-71: the window names this folder only and confers no
 * permission on the items inside it (its settings still affect them, D-71a); Create starts checked
 * for a regular folder, Configure for a computed one.
 *
 * <p>spec-review-S6 m-5: the entry has exactly one URL form, the same as the job entry's:
 * {@code batch-control/grants/new?scopeFullName=<rawEncode(full name)>} (relative to the folder's
 * URL) and, for the dialog, the same single parameter on {@code batch-control/grants/dialog}.
 * There is no {@code actions} parameter: the form preselects the action from the item it resolves
 * ({@code GrantsSection#isPrefillAction}).
 *
 * <p>No URL space, no view of its own and no state: the entry links to the Grants screen's form
 * ({@code /batch-control/grants/new}, or its dialog), whose own gate and {@code @RequirePOST}
 * create endpoint make every check that matters.
 *
 * <h2>Who sees it</h2>
 * Change control is on, the caller holds {@code BatchControl/RequestGrant}, and the caller does
 * not already hold every permission a window can carry here: {@code Item/Configure} on the
 * folder, and {@code Item/Create} on it when it is a regular folder (D-71: a window's Create
 * applies only to a regular folder, and its Delete never to a folder) — so administrators, and a
 * holder of open windows for all of them, see no entry. The folder page already requires
 * {@code Item/Read}.
 */
@Restricted(NoExternalUse.class)
public class FolderGrantRequestAction implements Action {

    private final AbstractFolder<?> folder;

    public FolderGrantRequestAction(AbstractFolder<?> folder) {
        this.folder = folder;
    }

    @Override
    @CheckForNull
    public String getIconFileName() {
        if (!BatchControlGlobalConfiguration.get().isChangeControlEnabled()) {
            return null;
        }
        if (!Jenkins.get().hasPermission(BatchControlPermissions.REQUEST_GRANT)) {
            return null;
        }
        boolean createApplies = GrantScope.createAppliesTo(folder);
        if (folder.hasPermission(Item.CONFIGURE) && (!createApplies || folder.hasPermission(Item.CREATE))) {
            return null;
        }
        return "symbol-key-outline plugin-ionicons-api";
    }

    @Override
    public String getDisplayName() {
        return JobGrantRequestAction.REQUEST_CHANGE_LABEL;
    }

    /**
     * The full-page form, relative to the folder's URL (see {@link JobGrantRequestAction#getUrlName()}
     * for why it is relative and carries a single query parameter). D-71: a window names one item,
     * so the folder's full name is all the form needs; it resolves the name to tell its kind.
     */
    @Override
    public String getUrlName() {
        return Dialogs.toRoot(folder.getUrl()) + "batch-control/grants/new?scopeFullName="
                + Util.rawEncode(folder.getFullName());
    }

    /** Root-relative URL of the dialog form, prefilled for this folder. */
    public String getDialogUrl() {
        return "batch-control/grants/dialog?scopeFullName=" + Util.rawEncode(folder.getFullName());
    }

    /** As {@link JobGrantRequestAction#getEvent()}: the dialog, on pages that render events. */
    @Override
    public Event getEvent() {
        return DialogEvent.of(Dialogs.toRoot(folder.getUrl()) + getDialogUrl());
    }
}
