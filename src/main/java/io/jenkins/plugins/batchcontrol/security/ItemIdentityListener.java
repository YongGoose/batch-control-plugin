package io.jenkins.plugins.batchcontrol.security;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.listeners.ItemListener;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * D-71a: keeps the binding of windows to their items in step with item events, so that a window
 * never applies to another item even when the other item's directory has the same identity
 * ({@link ItemIdentity}: an inode number reused by the file system after a deletion).
 *
 * <ul>
 *   <li>{@code onDeleted}: windows naming the deleted item, or an item below it, are unbound for
 *       good ({@link GrantService#forgetDeletedItem}). Core fires this before the name is free
 *       again, so no re-creation under that name can come first.</li>
 *   <li>{@code onLocationChanged} (rename, move; also fired for every item below a renamed or
 *       moved folder): windows naming the old name are unbound for good (a renamed item loses its
 *       window, fail-closed), and so are windows still bound under the new name, which cannot
 *       belong to the item that arrived ({@link GrantService#forgetRelocatedItem}). The cached
 *       identities of the item and of everything below it are dropped.</li>
 *   <li>{@code onCreated} (and a copy, which core reports as a creation): windows still bound under
 *       the new item's name belong to an item that disappeared without Jenkins seeing it (deleted
 *       on disk, then "Reload Configuration from Disk", which fires no item event); they are
 *       unbound ({@link GrantService#forgetNewItemName}).</li>
 *   <li>{@code onLoaded} (startup only; core does not fire it on a reload): the identity cache is
 *       dropped and windows whose item no longer exists are unbound
 *       ({@link GrantService#unbindMissingItems}).</li>
 * </ul>
 *
 * <p>The ordinal runs this listener after the change-recording listeners (default ordinal 0), so
 * the DELETE, RENAME and MOVE records are still linked to the window used before it is unbound.
 * Acts whatever the switches say: with change control off no window is active (S-15), so there is
 * nothing to unbind, and dropping cache entries changes no decision.
 */
@Extension(ordinal = -1000)
@Restricted(NoExternalUse.class)
public final class ItemIdentityListener extends ItemListener {

    private static final Logger LOGGER = Logger.getLogger(ItemIdentityListener.class.getName());

    @Override
    public void onCreated(Item item) {
        try {
            GrantService.get().forgetNewItemName(item.getFullName());
        } catch (RuntimeException e) {
            // Never fails the creation.
            LOGGER.log(Level.WARNING, "Could not unbind stale permission windows under the name of the new item '"
                    + item.getFullName() + "'", e);
        }
    }

    @Override
    public void onRenamed(Item item, String oldName, String newName) {
        ItemIdentity.forget(item);
    }

    @Override
    public void onLocationChanged(Item item, String oldFullName, String newFullName) {
        ItemIdentity.forget(item);
        try {
            GrantService.get().forgetRelocatedItem(oldFullName, newFullName);
        } catch (RuntimeException e) {
            // Never fails the rename or move; the windows are still matched by name and identity.
            LOGGER.log(Level.WARNING, "Could not unbind the permission windows of '" + oldFullName
                    + "', renamed or moved to '" + newFullName + "'", e);
        }
    }

    @Override
    public void onDeleted(Item item) {
        try {
            GrantService.get().forgetDeletedItem(item.getFullName());
        } catch (RuntimeException e) {
            // Never fails the deletion; the window is still matched by identity.
            LOGGER.log(Level.WARNING, "Could not unbind the permission windows of the deleted item '"
                    + item.getFullName() + "'", e);
        }
    }

    @Override
    public void onLoaded() {
        ItemIdentity.forgetAll();
        try {
            // Runs as SYSTEM at startup, so every item is visible without switching authentication.
            Jenkins jenkins = Jenkins.get();
            GrantService.get().unbindMissingItems(name -> jenkins.getItemByFullName(name) != null);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not unbind the permission windows of items that no longer exist", e);
        }
    }
}
