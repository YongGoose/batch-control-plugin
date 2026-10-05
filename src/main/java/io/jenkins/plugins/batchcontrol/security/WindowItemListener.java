package io.jenkins.plugins.batchcontrol.security;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.listeners.ItemListener;
import hudson.security.ACL;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.model.queue.ItemDeletion;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.springframework.security.core.Authentication;

/**
 * D-74: permission windows follow their item, as matrix-auth's item permissions do.
 *
 * <ul>
 *   <li>{@code onLocationChanged} (rename, move; core also reports it for every item below a renamed
 *       or moved folder): the windows naming the item now name its new full name
 *       ({@link GrantService#followItem}).</li>
 *   <li>{@code onDeleted}: the windows naming the deleted item, or an item below it, end
 *       ({@link GrantService#endWindowsOf}), revoked by the user who deleted it. Core deletes the
 *       items below a folder as SYSTEM; their windows are revoked by the user who deleted the folder,
 *       remembered from {@code onCheckDelete} while that folder's deletion is under way (SPEC 6: the
 *       history names who did what).</li>
 *   <li>{@code onCreated} (and a copy, which core reports as a creation): a window still naming the
 *       new item's name belongs to an item that disappeared without an event, and ends
 *       ({@link GrantService#endWindowsOnNewItem}).</li>
 *   <li>{@code onLoaded} (startup): windows whose item no longer exists end
 *       ({@link GrantService#endWindowsOfMissingItems}).</li>
 * </ul>
 *
 * <p>Renaming through a window is refused (D-71c), so a window holder cannot move windows onto
 * other items by renaming. Not covered: an item replaced on disk outside Jenkins followed by a
 * reload, which fires no item event.
 *
 * <p>The ordinal runs this listener after the change-recording listeners (default ordinal 0), so
 * the DELETE, RENAME and MOVE records are still linked to the window used, under its old name.
 * Acts whatever the switches say: with change control off no window is active (S-15).
 */
@Extension(ordinal = -1000)
@Restricted(NoExternalUse.class)
public final class WindowItemListener extends ItemListener {

    private static final Logger LOGGER = Logger.getLogger(WindowItemListener.class.getName());

    /**
     * The items whose deletion a user (not SYSTEM) started on this thread, with that user's name.
     * Core deletes the items below a folder on the same thread, as SYSTEM, while the folder is
     * registered as being deleted ({@link ItemDeletion#isRegistered}); an entry counts only then, so
     * one left by a deletion that was refused or failed never names anyone. Weak keys: such an entry
     * cannot keep its item in memory either.
     */
    private static final ThreadLocal<Map<Item, String>> DELETING = new ThreadLocal<>();

    @Override
    public void onCheckDelete(Item item) {
        // Runs last (ordinal), so a veto by another listener has already been raised.
        Authentication auth = Jenkins.getAuthentication2();
        if (ACL.SYSTEM2.equals(auth)) {
            return; // an item below a folder being deleted: attributed through the folder's entry
        }
        Map<Item, String> started = DELETING.get();
        if (started == null) {
            started = new WeakHashMap<>();
            DELETING.set(started);
        }
        started.keySet().removeIf(other -> !ItemDeletion.isRegistered(other)); // left by refused or failed deletions
        started.put(item, auth.getName());
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
            GrantService.get().followItem(oldFullName, newFullName);
        } catch (RuntimeException e) {
            // Never fails the rename or move.
            LOGGER.log(Level.WARNING, "Could not update the permission windows of '" + oldFullName
                    + "', renamed or moved to '" + newFullName + "'", e);
        }
    }

    @Override
    public void onDeleted(Item item) {
        try {
            GrantService.get().endWindowsOf(item.getFullName(), deletingUser(item));
        } catch (RuntimeException e) {
            // Never fails the deletion.
            LOGGER.log(Level.WARNING, "Could not end the permission windows of the deleted item '"
                    + item.getFullName() + "'", e);
        } finally {
            Map<Item, String> started = DELETING.get();
            if (started != null) {
                started.remove(item);
                if (started.isEmpty()) {
                    DELETING.remove();
                }
            }
        }
    }

    /**
     * Who deleted {@code item}: the current user, or, when core deletes it as SYSTEM because a
     * folder above it is being deleted on this thread, the user who started that folder's deletion.
     */
    private static String deletingUser(Item item) {
        Authentication auth = Jenkins.getAuthentication2();
        Map<Item, String> started = DELETING.get();
        if (!ACL.SYSTEM2.equals(auth) || started == null) {
            return auth.getName();
        }
        for (ItemGroup<?> group = item.getParent(); group instanceof Item; group = ((Item) group).getParent()) {
            String user = started.get(group);
            if (user != null && ItemDeletion.isRegistered((Item) group)) {
                return user;
            }
        }
        return auth.getName();
    }

    @Override
    public void onLoaded() {
        try {
            // Runs as SYSTEM at startup, so every item is visible without switching authentication.
            Jenkins jenkins = Jenkins.get();
            GrantService.get().endWindowsOfMissingItems(name -> jenkins.getItemByFullName(name) != null);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not end the permission windows of items that no longer exist", e);
        }
    }
}
