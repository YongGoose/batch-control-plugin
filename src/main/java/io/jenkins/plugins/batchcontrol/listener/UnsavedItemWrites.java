package io.jenkins.plugins.batchcontrol.listener;

import hudson.model.AbstractItem;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
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
     * one whose save fails again stays for the next run. Never throws.
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
                item.save();
                remove(item, what);
                LOGGER.info(() -> "Saved " + what + " of '" + fullName + "', which was in effect but not saved until now");
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.FINE, e, () -> "Still cannot save " + what + " of '" + fullName + "'; retried later");
            }
        }
    }

    /** Drops {@code item} unless something else was noted on it meanwhile (that is saved by the next run). */
    private static void remove(AbstractItem item, String what) {
        synchronized (PENDING) {
            PENDING.remove(item, what);
        }
    }
}
