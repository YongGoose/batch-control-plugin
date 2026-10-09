package io.jenkins.plugins.batchcontrol.listener;

import hudson.model.AbstractItem;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.model.queue.ItemDeletion;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Items whose configuration in memory carries a fail-closed change that could not be saved: the
 * D-34 lock of a new or moved job (SPEC item 8, {@link ItemChangeListener#applyActivationLock}) or
 * the removal of an authorization property a grant holder's creation payload carried (SPEC item 2,
 * D-35c, {@link GrantViolationGuard}). The change is in effect at once, because Jenkins reads the
 * item's configuration from memory; only its {@code config.xml} is stale. As for a window whose end
 * could not be written (D-74, {@code GrantService#flushUnsavedEnds}), the write is retried: the
 * periodic work saves each such item again ({@link #retry()}) until a save succeeds.
 *
 * <p>The retried save is an ordinary save of the item as it is in memory, so the configuration
 * history records what it wrote (a {@code CONFIGURE} record by SYSTEM). While an item is listed
 * here its configuration snapshot (taken from the stale file) is not the state in effect, which
 * {@link GrantViolationGuard} takes into account.
 *
 * <p>The list is kept in memory only: if Jenkins stops before a retry succeeds, the item is loaded
 * from its stale file (the GRANT_VIOLATION record of a failed removal says so).
 */
@Restricted(NoExternalUse.class)
public final class UnsavedItemWrites {

    private static final Logger LOGGER = Logger.getLogger(UnsavedItemWrites.class.getName());

    /** Item to what in it is not saved yet (for the log); weak keys, guarded by itself. */
    private static final Map<AbstractItem, String> PENDING = new WeakHashMap<>();

    private UnsavedItemWrites() {
    }

    /** Notes that {@code what} (for example "the activation lock") is in effect on {@code item} but not saved. */
    static void add(AbstractItem item, String what) {
        synchronized (PENDING) {
            PENDING.merge(item, what, (a, b) -> a.equals(b) ? a : a + " and " + b);
        }
    }

    /** Whether {@code item} carries a change that is in effect but not saved yet. */
    static boolean isPending(AbstractItem item) {
        synchronized (PENDING) {
            return PENDING.containsKey(item);
        }
    }

    /**
     * Saves every listed item again (called by the periodic work, which runs as SYSTEM). An item
     * whose save succeeds leaves the list; one that is no longer in Jenkins (deleted) is dropped;
     * one that is being deleted, or whose directory is gone, is not written and stays for the next
     * run (which drops it once the deletion has removed it from its parent); one whose save fails
     * again stays for the next run. Never throws.
     *
     * <p>Note 284 (c), LIMITATIONS 13: the periodic work runs on the Jenkins timer, so a retry can
     * meet a deletion of the same item. Core's {@code AbstractItem.delete()} registers the item with
     * {@link ItemDeletion}, runs {@code performDelete()} (which removes the item's directory) under
     * the item's monitor, deregisters it, and only then removes the item from its parent. A
     * registration check alone therefore still passes after the directory is gone, and the save that
     * follows ({@code XmlFile.write} creates missing directories) wrote {@code config.xml} back into
     * the deleted job's directory. The deletion checks and the save are therefore made under the
     * item's monitor, the one {@code AbstractItem.save()} and {@code performDelete()} hold: either
     * the save completes before {@code performDelete()} starts (which then removes what it wrote),
     * or the retry sees the deletion registered or the directory gone and writes nothing. Only the
     * item's own monitor is held (no lock of this plugin), and under it only {@link ItemDeletion}'s
     * read lock is taken, which core never holds while waiting for an item's monitor.
     */
    public static void retry() {
        Map<AbstractItem, String> items;
        synchronized (PENDING) {
            if (PENDING.isEmpty()) {
                return;
            }
            // A strong copy: an entry of the weak map may lose its key while it is being worked on.
            items = new LinkedHashMap<>(PENDING);
        }
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            return;
        }
        for (Map.Entry<AbstractItem, String> entry : items.entrySet()) {
            AbstractItem item = entry.getKey();
            String what = entry.getValue();
            String fullName = item.getFullName();
            try {
                if (jenkins.getItemByFullName(fullName) != item) {
                    remove(item, what);
                    LOGGER.info(() -> "'" + fullName + "' is no longer in Jenkins; " + what + " is not saved again");
                    continue;
                }
                if (!saveUnlessDeleting(item)) {
                    LOGGER.fine(() -> "'" + fullName + "' is being deleted or its directory is gone; " + what
                            + " is not saved now (dropped by a later run once the item is gone)");
                    continue;
                }
                remove(item, what);
                LOGGER.info(() -> "Saved " + what + " of '" + fullName + "', which was in effect but not saved until now");
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.FINE, e, () -> "Still cannot save " + what + " of '" + fullName + "'; retried later");
            }
        }
    }

    /**
     * Saves {@code item} unless it is being deleted (it or a folder above it is registered with
     * {@link ItemDeletion}) or its directory is confirmed absent ({@code performDelete()} has run,
     * the item may still be in its parent). Checked and saved under the item's monitor, see
     * {@link #retry()}. An item whose directory cannot be checked (an unreadable parent directory)
     * is saved as before: if that save fails too, the item stays listed.
     *
     * @return whether the item was saved
     */
    private static boolean saveUnlessDeleting(AbstractItem item) throws IOException {
        synchronized (item) {
            if (ItemDeletion.contains(item) || Files.notExists(item.getRootDir().toPath())) {
                return false;
            }
            item.save();
            return true;
        }
    }

    /** Drops {@code item} unless something else was noted on it meanwhile (that is saved by the next run). */
    private static void remove(AbstractItem item, String what) {
        synchronized (PENDING) {
            PENDING.remove(item, what);
        }
    }
}
