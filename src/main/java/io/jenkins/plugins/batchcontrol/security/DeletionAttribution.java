package io.jenkins.plugins.batchcontrol.security;

import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.security.ACL;
import java.util.Map;
import java.util.WeakHashMap;
import jenkins.model.Jenkins;
import jenkins.model.queue.ItemDeletion;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.springframework.security.core.Authentication;

/**
 * Who deleted an item (SPEC 6: the history names who did what). Core's {@code AbstractItem.delete()}
 * deletes the items below a folder as SYSTEM, on the thread deleting the folder, so the current
 * authentication names SYSTEM for each of them. The user who started the folder's deletion is
 * remembered from {@code onCheckDelete} ({@link #remember}) and named for the items below it
 * ({@link #deletingUser}) while the folder is registered as being deleted
 * ({@link ItemDeletion#isRegistered}). A deletion genuinely started by SYSTEM stays SYSTEM.
 *
 * <p>{@link WindowItemListener} keeps the entries: it remembers in {@code onCheckDelete} and forgets
 * in {@code onDeleted}, and its ordinal runs it after every other item listener, so any listener
 * may ask {@link #deletingUser} from its own {@code onDeleted}.
 */
@Restricted(NoExternalUse.class)
public final class DeletionAttribution {

    /**
     * The items whose deletion a user (not SYSTEM) started on this thread, with that user's name.
     * An entry counts only while its item is registered in {@link ItemDeletion}, so one left by a
     * deletion that was refused or failed never names anyone. Weak keys: such an entry cannot keep
     * its item in memory either.
     */
    private static final ThreadLocal<Map<Item, String>> DELETING = new ThreadLocal<>();

    private DeletionAttribution() {
    }

    /**
     * Remembers the current user as the one deleting {@code item}, unless the current
     * authentication is SYSTEM (an item below a folder being deleted, attributed through the
     * folder's entry). Call from {@code onCheckDelete} once no veto can follow.
     */
    static void remember(Item item) {
        Authentication auth = Jenkins.getAuthentication2();
        if (ACL.SYSTEM2.equals(auth)) {
            return;
        }
        Map<Item, String> started = DELETING.get();
        if (started == null) {
            started = new WeakHashMap<>();
            DELETING.set(started);
        }
        started.keySet().removeIf(other -> !ItemDeletion.isRegistered(other)); // left by refused or failed deletions
        started.put(item, auth.getName());
    }

    /** Forgets {@code item}'s entry. Call from {@code onDeleted}, after every other listener. */
    static void forget(Item item) {
        Map<Item, String> started = DELETING.get();
        if (started != null) {
            started.remove(item);
            if (started.isEmpty()) {
                DELETING.remove();
            }
        }
    }

    /**
     * Who deleted {@code item}: the current user, or, when core deletes it as SYSTEM because a
     * folder above it is being deleted on this thread, the user who started that folder's deletion.
     * Valid from an {@code onDeleted} listener.
     */
    public static String deletingUser(Item item) {
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
}
