package io.jenkins.plugins.batchcontrol.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Item;
import hudson.model.ItemGroup;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * S-09, D-71a: an opaque marker of an item's directory on disk. Recorded with a D-35c
 * created-item record (S-09) and, at approval, with every grant (D-71a), so that an item deleted
 * and recreated under the same name, or another item renamed, moved or swapped into that name, is
 * not taken for the one the record or the window is about.
 *
 * <p>The file key (device and inode on POSIX file systems) is used where the file system has one:
 * it survives restarts, renames and moves inside {@code $JENKINS_HOME}, and a recreated directory
 * gets a new one. Without a file key (Windows) the creation time is used, which is a real birth
 * time there. A directory copied back from a backup gets a new marker, which only takes the
 * grant's permissions on it away (fail-safe).
 *
 * <p><b>Reuse after deletion (D-71a).</b> The marker tells apart items that exist at the same
 * time (a rename, move or swap). It is not relied on to tell a deleted item from one that comes to
 * have its name later: Linux file systems (ext4, xfs) commonly give a new directory the inode
 * number just freed, and NTFS keeps a re-created name's creation time for a while ("tunneling").
 * That case is closed by item events instead, independent of the marker's value
 * ({@link ItemIdentityListener}):
 * <ul>
 *   <li>a window whose item is deleted, renamed or moved through Jenkins is unbound for good
 *       ({@link GrantService#forgetDeletedItem}, {@link GrantService#forgetRelocatedItem}); a D-35c
 *       created-item record is dropped on deletion ({@link GrantService#forgetCreatedItem}). Core
 *       fires the deletion event before it frees the name in the parent's item map
 *       ({@code Jenkins#onDeleted}, {@code AbstractFolder#onDeleted}), so no item can be created
 *       under that name first;</li>
 *   <li>a window still bound under a name at which another item arrives (created, copied, renamed
 *       or moved there) is unbound: its own item disappeared without an event (deleted on disk and
 *       reloaded; core's reload fires no item event) ({@link GrantService#forgetNewItemName},
 *       {@link GrantService#forgetRelocatedItem});</li>
 *   <li>at startup, windows and created-item records whose item no longer exists are unbound or
 *       dropped ({@link GrantService#unbindMissingItems}, {@link GrantService#pruneCreatedItems});</li>
 *   <li>a window also compares the item's kind (S-34-03).</li>
 * </ul>
 * What remains is a directory put in place on disk with "Reload Configuration from Disk", or
 * between a shutdown and a start, by someone with file-system access to {@code $JENKINS_HOME} and
 * Overall/Administer, which is administrator territory (SPEC section 7).
 * Not chosen: adding the directory's birth time (Java 21 on Linux reports the last-modified time
 * instead, which changes with every save, so windows would stop working at random), and a marker
 * file with a random id inside the item's directory (a new file in core's directories, outside the
 * storage layout of ARCHITECTURE section 5, and duplicated by any copy of the directory).
 *
 * <p><b>Cache (D-71a).</b> Permission checks are the hot path: a page render asks many
 * permissions of many items. {@link #of(Item)} is only reached once an active window of the user
 * names the item (the name match is in memory, {@link GrantService}), and its answer is cached per
 * {@link Item} object, so a page that asks the same item's permissions many times reads its
 * directory's attributes once:
 * <ul>
 *   <li>Keys are the live item objects, compared by identity and held weakly. A deleted item, an
 *       item replaced by "Reload Configuration from Disk" and an item recreated under the same
 *       name are different objects, so a cached answer is never served for another item, and an
 *       entry disappears with its item.</li>
 *   <li>A rename or move keeps the same object and normally the same directory identity; when the
 *       directory had to be copied instead of renamed it gets a new one, so the entries of the
 *       item and everything below it are dropped on {@code onRenamed} and {@code onLocationChanged}
 *       ({@link ItemIdentityListener}), and the whole cache at startup. A reload replaces every
 *       item object, so the old entries are never served again and go with their objects.</li>
 *   <li>A missing directory is never cached (an item being created, or being renamed while its
 *       directory moves), so a transient {@code null} does not stick.</li>
 *   <li>Entries also expire after {@link #TTL} on the plugin clock and the cache holds at most
 *       {@link #MAX_ENTRIES} of them, as a backstop for a directory replaced on disk behind
 *       Jenkins' back.</li>
 * </ul>
 * Deletion keeps the entry (the object is gone with it), so the DELETE change record can still be
 * linked to the window used, after the directory is gone.
 */
@Restricted(NoExternalUse.class)
public final class ItemIdentity {

    /** Backstop expiry of a cached identity, on the plugin clock. */
    static final Duration TTL = Duration.ofMinutes(10);

    /** Bound of the cache; only items named by an active window of someone are ever cached. */
    static final int MAX_ENTRIES = 10_000;

    /** Caffeine time source on the plugin clock, in nanoseconds since the epoch (no timer). */
    private static final Ticker BATCH_CLOCK_TICKER = () -> {
        Instant now = BatchClock.now();
        return now.getEpochSecond() * 1_000_000_000L + now.getNano();
    };

    private static final Cache<Item, String> CACHE = Caffeine.newBuilder()
            .weakKeys()
            .ticker(BATCH_CLOCK_TICKER)
            .expireAfterWrite(TTL)
            .maximumSize(MAX_ENTRIES)
            .build();

    private ItemIdentity() {
    }

    /** The marker of {@code rootDir}, or {@code null} when it does not exist or cannot be read. Uncached. */
    @CheckForNull
    public static String of(@CheckForNull File rootDir) {
        if (rootDir == null) {
            return null;
        }
        try {
            BasicFileAttributes attributes = Files.readAttributes(rootDir.toPath(), BasicFileAttributes.class);
            Object key = attributes.fileKey();
            return key != null ? "key:" + key : "created:" + attributes.creationTime().toMillis();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * D-71a: the marker of {@code item}'s directory, cached per item object (see the class
     * description), or {@code null} when it cannot be read and was not cached before.
     */
    @CheckForNull
    public static String of(@CheckForNull Item item) {
        if (item == null) {
            return null;
        }
        String cached = CACHE.getIfPresent(item);
        if (cached != null) {
            return cached;
        }
        File rootDir;
        try {
            rootDir = item.getRootDir();
        } catch (RuntimeException e) {
            return null;
        }
        String computed = of(rootDir);
        if (computed != null) {
            CACHE.put(item, computed);
        }
        return computed;
    }

    /** Drops the cached identity of {@code item} and of every item below it (rename, move). */
    static void forget(@CheckForNull Item item) {
        if (item == null) {
            return;
        }
        CACHE.asMap().keySet().removeIf(cached -> cached == item || isBelow(cached, item));
    }

    /** Drops every cached identity (items reloaded). */
    static void forgetAll() {
        CACHE.invalidateAll();
    }

    private static boolean isBelow(Item candidate, Item ancestor) {
        if (!(ancestor instanceof ItemGroup)) {
            return false;
        }
        for (ItemGroup<?> parent = candidate.getParent(); parent instanceof Item; parent = ((Item) parent).getParent()) {
            if (parent == ancestor) {
                return true;
            }
        }
        return false;
    }
}
