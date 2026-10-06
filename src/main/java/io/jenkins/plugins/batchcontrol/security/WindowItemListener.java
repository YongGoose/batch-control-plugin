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
 * D-74: permission windows follow their item, as matrix-auth's item permissions do.
 *
 * <ul>
 *   <li>{@code onLocationChanged} (rename, move; core also reports it for every item below a renamed
 *       or moved folder): the windows naming the item now name its new full name
 *       ({@link GrantService#followItem}); a window already naming the new name, a window whose old
 *       name another item has taken again, and a window whose file cannot be updated end instead
 *       (S-39-02).</li>
 *   <li>{@code onDeleted}: the windows naming the deleted item, or an item below it, end
 *       ({@link GrantService#endWindowsOf}), revoked by the user who deleted it. Core deletes the
 *       items below a folder as SYSTEM; their windows are revoked by the user who deleted the folder,
 *       remembered from {@code onCheckDelete} while that folder's deletion is under way
 *       ({@link DeletionAttribution}; SPEC 6: the history names who did what).</li>
 *   <li>{@code onCreated} (and a copy, which core reports as a creation): a window still naming the
 *       new item's name, in any letter case, belongs to an item that disappeared without an event,
 *       and ends ({@link GrantService#endWindowsOnNewItem}).</li>
 *   <li>{@code onLoaded} (startup): windows whose item no longer exists under exactly the name they
 *       give end ({@link GrantService#endWindowsOfMissingItems}).</li>
 * </ul>
 *
 * <p>Renaming through a window is refused (D-71c), so a window holder cannot move windows onto
 * other items by renaming. Not covered: an item replaced on disk outside Jenkins followed by a
 * reload, which fires no item event.
 *
 * <p>The ordinal runs this listener after the change-recording listeners (default ordinal 0), so
 * the DELETE, RENAME and MOVE records are still linked to the window used, under its old name, and
 * they can still ask {@link DeletionAttribution#deletingUser} who deleted an item, whose entry this
 * listener forgets in {@code onDeleted}.
 * Acts whatever the switches say: with change control off no window is active (S-15).
 *
 * <p>Each handler logs what it could not do and never fails the item operation. A grant file that
 * cannot be read is left out of the window cache (it confers nothing), so what still reaches these
 * handlers' catch blocks is a {@code batch-control/grants/} directory that cannot be listed while
 * the cache is being loaded.
 */
@Extension(ordinal = -1000)
@Restricted(NoExternalUse.class)
public final class WindowItemListener extends ItemListener {

    private static final Logger LOGGER = Logger.getLogger(WindowItemListener.class.getName());

    @Override
    public void onCheckDelete(Item item) {
        // Runs last (ordinal), so a veto by another listener has already been raised.
        DeletionAttribution.remember(item);
    }

    @Override
    public void onCreated(Item item) {
        try {
            GrantService.get().endWindowsOnNewItem(item.getFullName());
        } catch (RuntimeException e) {
            // Never fails the creation.
            LOGGER.log(Level.WARNING, "Could not end stale permission windows under the name of the new item '"
                    + item.getFullName() + "'", e);
        }
    }

    @Override
    public void onLocationChanged(Item item, String oldFullName, String newFullName) {
        try {
            GrantService.get().followItem(item, oldFullName, newFullName);
        } catch (RuntimeException e) {
            // Never fails the rename or move.
            LOGGER.log(Level.WARNING, "Could not update the permission windows of '" + oldFullName
                    + "', renamed or moved to '" + newFullName + "'", e);
        }
    }

    @Override
    public void onDeleted(Item item) {
        try {
            GrantService.get().endWindowsOf(item, DeletionAttribution.deletingUser(item));
        } catch (RuntimeException e) {
            // Never fails the deletion.
            LOGGER.log(Level.WARNING, "Could not end the permission windows of the deleted item '"
                    + item.getFullName() + "'", e);
        } finally {
            DeletionAttribution.forget(item); // last listener (ordinal): every other one has asked
        }
    }

    @Override
    public void onLoaded() {
        try {
            // Runs as SYSTEM at startup, so every item is visible without switching authentication.
            // S-39-02: Jenkins finds an item under any letter case of its name; a window naming
            // another spelling than the item's own full name is not about that item and ends.
            Jenkins jenkins = Jenkins.get();
            GrantService.get().endWindowsOfMissingItems(name -> {
                Item found = jenkins.getItemByFullName(name);
                return found != null && name.equals(found.getFullName());
            });
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not end the permission windows of items that no longer exist", e);
        }
    }
}
