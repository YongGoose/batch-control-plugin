package io.jenkins.plugins.batchcontrol.listener;

import com.cloudbees.hudson.plugins.folder.computed.ComputedFolder;
import com.cloudbees.hudson.plugins.folder.computed.FolderComputation;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.XmlFile;
import hudson.model.AbstractItem;
import hudson.model.Executor;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Queue;
import hudson.model.Saveable;
import hudson.model.listeners.ItemListener;
import hudson.model.listeners.SaveableListener;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.store.SecretMasker;
import io.jenkins.plugins.batchcontrol.store.Store;
import io.jenkins.plugins.batchcontrol.store.UnifiedDiff;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Records CONFIGURE changes with a unified diff (SPEC item 9) and maintains the per-item config
 * snapshots ({@code snapshots/<encoded fullName>.xml}, latest only — ARCHITECTURE section 5).
 *
 * <p>{@link SaveableListener} is the one hook every config-writing path goes through — UI form
 * submit ({@code save()}), REST {@code config.xml} POST and CLI {@code update-job}
 * ({@code updateByXml}), Job DSL, and programmatic saves — so nothing escapes the record
 * (SPEC item 9: "regardless of the path").
 *
 * <p>Both diff sides are masked with {@link SecretMasker} BEFORE diffing, so no hunk can ever
 * carry a secret. When only secret payloads changed (both sides mask to the same text), a
 * masked-change note is stored instead of an empty diff.
 *
 * <p>Both sides are compared and diffed in their {@link ConfigNormalizer normalised} form, so a
 * save that changes no user-editable configuration (an unchanged job saved after a plugin
 * upgrade, a computed folder saving itself during indexing) writes no record, no patch and no new
 * snapshot (SPEC item 9, #20). The snapshot keeps the raw file, which other readers parse.
 *
 * <p>Saves of items whose parent is a {@link ComputedFolder} (branch jobs, the projects of an
 * organization folder) are skipped only when the parent's own indexing makes them: that
 * configuration is generated. Their runs are still recorded (D-32). Any other save of such a
 * child (script, Job DSL, REST, CLI) is recorded, because that edit is in effect until the next
 * indexing (security-07 S-04).
 *
 * <p>A snapshot that cannot be read or written never stops the record (SPEC item 9, ARCHITECTURE
 * section 1, T-GAP-385): when the previous snapshot exists but cannot be read (also when something
 * other than a file is in its place), the CONFIGURE record is written without a diff and with a
 * note saying why; a snapshot that cannot be written is logged and the record is written anyway.
 * Nothing escapes this listener.
 *
 * <p>A save that finds no snapshot at all is recorded too, without a diff and with a note saying
 * that no earlier configuration of the item was recorded, and its configuration becomes the
 * baseline (SPEC item 9: every path's change is recorded). Snapshots are seeded when an item is
 * created ({@link ItemChangeListener}) and, for the items that exist when recording becomes active,
 * at startup and when a switch turns recording on
 * ({@link io.jenkins.plugins.batchcontrol.ops.SnapshotSeeding}), so a save finds none only before
 * that seeding reached its item or after the snapshot could not be written.
 *
 * <p>A save that is part of creating the item ({@link #isCreationSave}) stores the baseline and
 * writes no record, with or without an earlier snapshot: the CREATE record stands for it (#20). A
 * computed folder saving itself during its own indexing also only stores the baseline when there is
 * none to compare with, because such a save changes no user-editable configuration (#20).
 *
 * <p>The read → diff → snapshot swap of one item runs under that item's lock stripe, so rapid
 * consecutive saves chain baseline-consistently (each diff is previous-config vs new-config,
 * T-RT-20) while saves of unrelated items do not wait for each other. Seeding takes the same lock.
 */
@Extension
@Restricted(NoExternalUse.class)
public class ConfigSnapshotListener extends SaveableListener {

    private static final Logger LOGGER = Logger.getLogger(ConfigSnapshotListener.class.getName());

    private static final int STRIPES = 64;

    /** Serializes snapshot read → diff → snapshot write per item so records never lose a revision. */
    private static final ReentrantLock[] LOCKS = new ReentrantLock[STRIPES];

    static {
        for (int i = 0; i < STRIPES; i++) {
            LOCKS[i] = new ReentrantLock();
        }
    }

    /**
     * The items whose save is being reported to the save listeners right now (full name to the number
     * of such saves): marked by {@link SaveStart}, which runs before every other save listener, and
     * cleared once this listener has handled the save. Seeding leaves such an item alone, so a baseline
     * is never taken from a configuration whose own save has not reached this listener yet (that save
     * would then compare equal to it and go unrecorded); the save stores the baseline itself. A count,
     * because a save listener may save the same item again while its first save is being reported.
     */
    private static final ConcurrentHashMap<String, Integer> SAVING = new ConcurrentHashMap<>();

    /**
     * The core methods that build a new item ({@link #isCreationSave}): {@code createProject} (which a
     * copy uses as well) saves the new item before it adds it to its parent; {@code createProjectFromXML}
     * adds the item to its parent and then calls {@code onCreatedFromScratch}, where an organization
     * folder saves itself several times; and a copy loads the copied configuration into a new object.
     * All of this happens before the creation is announced.
     */
    private static final Set<String> BUILDING_FRAMES = Set.of(
            "hudson.model.ItemGroupMixIn#createProject",
            "hudson.model.ItemGroupMixIn#createProjectFromXML",
            "hudson.model.ItemGroupMixIn#copy");

    /**
     * The core methods that announce a creation ({@link #isCreationSave}). Their listeners (other
     * plugins' included) may save the new item, and they may save other items too, so a save inside
     * them belongs to the creation only when it saves the announced item or an item inside it
     * ({@link #ANNOUNCED}).
     */
    private static final Set<String> ANNOUNCING_FRAMES = Set.of(
            "hudson.model.listeners.ItemListener#fireOnCreated",
            "hudson.model.listeners.ItemListener#fireOnCopied");

    /** The method names of {@link #BUILDING_FRAMES} and {@link #ANNOUNCING_FRAMES}, checked first. */
    private static final Set<String> CREATION_METHODS = Set.of(
            "createProject", "createProjectFromXML", "copy", "fireOnCreated", "fireOnCopied");

    /**
     * The items whose creation is being announced on this thread, innermost last: set by
     * {@link CreationAnnounced} (the first item listener to run) and cleared by
     * {@link CreationAnnouncedEnd} (the last), which core runs whatever the listeners in between
     * throw. Removed when empty, so pooled threads keep nothing.
     */
    private static final ThreadLocal<List<Item>> ANNOUNCED = new ThreadLocal<>();

    /** The outcome of {@link #seedIfMissing} for one item. */
    public enum Seeding {
        /** The item had no snapshot; its configuration was stored as one. */
        SEEDED,
        /** The item already had a snapshot (or something else is in its place); nothing was done. */
        PRESENT,
        /**
         * Not seeded on purpose: it has no configuration file, it is not (or no longer) in its parent,
         * or a save of it is being reported (that save stores the baseline).
         */
        SKIPPED,
        /** The configuration could not be read or the snapshot could not be written. */
        FAILED
    }

    private static ReentrantLock lockFor(String fullName) {
        return LOCKS[Math.floorMod(fullName.hashCode(), STRIPES)];
    }

    /**
     * Whether this thread is running the computation (indexing) of {@code folder} itself. The
     * computation is a {@link FolderComputation} executed on a one-off executor, so the current
     * executor's executable identifies it exactly; SCM events, scripts, Job DSL, REST and CLI saves
     * are never inside it (security-07 S-04, T-SEC-34).
     */
    private static boolean isIndexing(ComputedFolder<?> folder) {
        Executor executor = Executor.currentExecutor();
        if (executor == null) {
            return false;
        }
        Queue.Executable executable = executor.getCurrentExecutable();
        return executable instanceof FolderComputation
                && ((FolderComputation<?>) executable).getParent() == folder;
    }

    @Override
    public void onChange(Saveable o, XmlFile file) {
        if (!(o instanceof Item)) {
            return;
        }
        Item item = (Item) o;
        String fullName = item.getFullName();
        try {
            record(item, fullName, file);
        } finally {
            SAVING.computeIfPresent(fullName, (name, count) -> count > 1 ? count - 1 : null);
        }
    }

    private static void record(Item item, String fullName, XmlFile file) {
        if (ChangeRecording.isSuppressed() || !ChangeRecording.isActive()) {
            return;
        }
        if (item.getParent() instanceof ComputedFolder && isIndexing((ComputedFolder<?>) item.getParent())) {
            return; // generated by indexing (#20); runs are still recorded (D-32)
        }
        ReentrantLock lock = lockFor(fullName);
        lock.lock();
        try {
            String newXml;
            try {
                newXml = file.asString();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Cannot read saved config of '" + fullName + "'", e);
                return;
            }
            Store store = Store.get();
            String oldXml;
            try {
                oldXml = store.loadConfigSnapshot(fullName);
            } catch (RuntimeException e) {
                // T-GAP-385, SPEC 9, ARCHITECTURE 1: the change is recorded whatever happens to the diff.
                LOGGER.log(Level.WARNING, "Cannot read the config snapshot of '" + fullName
                        + "'; the configuration change is recorded without a diff", e);
                saveSnapshot(store, fullName, newXml);
                append(store, item, fullName, null, NO_DIFF_DETAIL);
                return;
            }
            if (oldXml == null) {
                recordWithoutBaseline(store, item, fullName, newXml);
                return;
            }
            if (oldXml.equals(newXml)) {
                return; // no-op save, nothing changed
            }
            String normalizedOld = ConfigNormalizer.normalize(oldXml);
            String normalizedNew = ConfigNormalizer.normalize(newXml);
            if (normalizedOld.equals(normalizedNew)) {
                // Only plugin versions or persisted actions differ: no user-editable change, and the
                // snapshot is left as it is (it still compares equal next time).
                return;
            }
            if (isCreationSave(item)) {
                // Part of creating the item (an organization folder saves itself several times while
                // it is created): its CREATE record stands for it, the configuration is the baseline.
                saveSnapshot(store, fullName, newXml);
                return;
            }
            // Mask BOTH sides before diffing so no hunk can ever carry a secret.
            String maskedOld = SecretMasker.mask(normalizedOld);
            String maskedNew = SecretMasker.mask(normalizedNew);
            String diff;
            if (maskedOld.equals(maskedNew)) {
                diff = "Only secret values changed; secrets are stored as "
                        + SecretMasker.MASK + " and never in plaintext.";
            } else {
                diff = UnifiedDiff.diff(maskedOld, maskedNew);
            }
            saveSnapshot(store, fullName, newXml);
            append(store, item, fullName, diff, null);
        } finally {
            lock.unlock();
        }
    }

    /**
     * A save that finds no snapshot of its item (SPEC item 9): the change is recorded without a diff,
     * with {@link #NO_BASELINE_DETAIL}, because what it changed cannot be shown, and the configuration
     * becomes the item's baseline. Only the baseline is stored for a save that is part of creating the
     * item (its CREATE record comes from {@link ItemChangeListener}) and for a computed folder saving
     * itself during its own indexing (#20). Under the item's lock.
     */
    private static void recordWithoutBaseline(Store store, Item item, String fullName, String newXml) {
        boolean ownIndexing = item instanceof ComputedFolder && isIndexing((ComputedFolder<?>) item);
        if (ownIndexing || isCreationSave(item)) {
            saveSnapshot(store, fullName, newXml);
            return;
        }
        LOGGER.fine(() -> "No configuration snapshot of '" + fullName + "' is stored; its configuration change is"
                + " recorded without a diff and its configuration becomes the baseline");
        append(store, item, fullName, null, NO_BASELINE_DETAIL);
        saveSnapshot(store, fullName, newXml);
    }

    /**
     * Whether a save of {@code item} is part of creating it, whether or not a snapshot of it exists.
     * <ul>
     *   <li>An item that is not in its parent is being created (or loaded): core saves a new item before
     *       it adds it to its parent.</li>
     *   <li>Otherwise the innermost core creation method on the stack decides. Inside a method that
     *       builds the item ({@link #BUILDING_FRAMES}) the save belongs to the creation. Inside one that
     *       announces it ({@link #ANNOUNCING_FRAMES}) it belongs to the creation only when it saves the
     *       announced item or an item inside it ({@link #ANNOUNCED}): a listener saving another item
     *       while a creation is announced changes that item, and the change is recorded.</li>
     * </ul>
     * Asked only by a save that would otherwise write a record (it changed the configuration, or no
     * baseline exists), so a no-op save never walks the stack.
     */
    static boolean isCreationSave(Item item) {
        if (!isRegistered(item)) {
            return true;
        }
        String innermost = StackWalker.getInstance().walk(frames -> frames
                .filter(frame -> CREATION_METHODS.contains(frame.getMethodName()))
                .map(frame -> frame.getClassName() + '#' + frame.getMethodName())
                .filter(frame -> BUILDING_FRAMES.contains(frame) || ANNOUNCING_FRAMES.contains(frame))
                .findFirst()
                .orElse(null));
        if (innermost == null) {
            return false;
        }
        return BUILDING_FRAMES.contains(innermost) || isAnnounced(item);
    }

    /** Whether the creation of {@code item}, or of an item it is inside, is being announced on this thread. */
    private static boolean isAnnounced(Item item) {
        List<Item> announced = ANNOUNCED.get();
        if (announced == null) {
            return false;
        }
        Item current = item;
        while (current != null) {
            for (Item created : announced) {
                if (created == current) {
                    return true;
                }
            }
            ItemGroup<? extends Item> parent = current.getParent();
            current = parent instanceof Item ? (Item) parent : null;
        }
        return false;
    }

    /**
     * Whether {@code item} is the object its parent holds under its name. A lookup that fails counts
     * as registered, so a save is recorded rather than taken for a creation.
     */
    private static boolean isRegistered(Item item) {
        ItemGroup<? extends Item> parent = item.getParent();
        if (parent == null) {
            return true;
        }
        // ACL.SYSTEM2 switch, for this lookup only: whether core has put this object into its parent
        // yet decides nothing for any user (no permission is checked or granted here), and a lookup
        // as the saving user cannot tell (it hides an item the user cannot read and refuses one the
        // user may only discover).
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            return parent.getItem(item.getName()) == item;
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "Could not look up '" + item.getFullName() + "' in its parent", e);
            return true;
        }
    }

    /**
     * Stores the current configuration of {@code item} as its baseline if no snapshot of it is stored
     * (SPEC item 9), for {@link io.jenkins.plugins.batchcontrol.ops.SnapshotSeeding}. The configuration
     * file is read once, and only when no snapshot exists; a snapshot, or something else in its place,
     * is left alone. Runs under the item's lock, so it never interleaves with a save of the same item;
     * an item whose save is being reported ({@link #SAVING}) or that is not in its parent (deleted or
     * replaced meanwhile) is skipped. Never throws.
     */
    public static Seeding seedIfMissing(Item item) {
        if (!(item instanceof AbstractItem)) {
            return Seeding.SKIPPED;
        }
        String fullName = item.getFullName();
        ReentrantLock lock = lockFor(fullName);
        lock.lock();
        try {
            if (SAVING.containsKey(fullName) || !isRegistered(item)) {
                return Seeding.SKIPPED;
            }
            Store store = Store.get();
            if (store.hasConfigSnapshot(fullName)) {
                return Seeding.PRESENT;
            }
            XmlFile config = ((AbstractItem) item).getConfigFile();
            if (!config.exists()) {
                return Seeding.SKIPPED;
            }
            store.saveConfigSnapshot(fullName, config.asString());
            return Seeding.SEEDED;
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.FINE, "Could not seed the config snapshot of '" + fullName + "'", e);
            return Seeding.FAILED;
        } finally {
            lock.unlock();
        }
    }

    /** The note of a CONFIGURE record written without a diff (T-GAP-385). */
    static final String NO_DIFF_DETAIL = "No diff: the previous configuration of this item could not be read"
            + " from its snapshot.";

    /** The note of a CONFIGURE record of a save that found no snapshot of its item (SPEC item 9). */
    static final String NO_BASELINE_DETAIL = "No diff: no earlier configuration of this item was recorded.";

    /**
     * Writes {@code xml} as the item's new diff baseline. A failure is logged and does not stop the
     * record (SPEC 9: a change is recorded whatever happens to the diff); the next change is then
     * compared with whatever baseline is left.
     */
    private static void saveSnapshot(Store store, String fullName, String xml) {
        try {
            store.saveConfigSnapshot(fullName, xml);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not write the config snapshot of '" + fullName
                    + "' (the diff baseline); configuration changes are recorded all the same", e);
        }
    }

    /**
     * Appends the CONFIGURE record of {@code item}, with {@code diff} (or none) and {@code detail}
     * (or none). A store failure is logged; nothing escapes the listener.
     */
    private static void append(Store store, Item item, String fullName, @CheckForNull String diff,
                               @CheckForNull String detail) {
        String user = ChangeRecording.currentUser();
        try {
            ChangeRecord record = ChangeRecord.create(ChangeType.CONFIGURE, fullName, user, detail);
            record.setDiff(diff);
            record.setGrantId(ChangeRecording.configureGrantIdFor(user, item));
            store.appendChangeRecord(record);
        } catch (RuntimeException e) {
            LOGGER.log(Level.SEVERE, "Could not record the configuration change of '" + fullName + "' by '" + user
                    + "'", e);
        }
    }

    /**
     * Marks the save of an item as being reported ({@link #SAVING}) before any other save listener
     * runs, so seeding never takes a baseline from a configuration whose save has not reached
     * {@link ConfigSnapshotListener} yet. {@link ConfigSnapshotListener#onChange} clears the mark.
     */
    @Extension(ordinal = Integer.MAX_VALUE)
    @Restricted(NoExternalUse.class)
    public static final class SaveStart extends SaveableListener {

        @Override
        public void onChange(Saveable o, XmlFile file) {
            if (o instanceof Item) {
                SAVING.merge(((Item) o).getFullName(), 1, Integer::sum);
            }
        }
    }

    /**
     * Marks the item whose creation (or copy) is being announced on this thread ({@link #ANNOUNCED})
     * before any other item listener runs, so {@link #isCreationSave} can tell a save of the new item
     * from a save of another item made by a listener of that announcement.
     */
    @Extension(ordinal = Integer.MAX_VALUE)
    @Restricted(NoExternalUse.class)
    public static final class CreationAnnounced extends ItemListener {

        @Override
        public void onCreated(Item item) {
            List<Item> announced = ANNOUNCED.get();
            if (announced == null) {
                announced = new ArrayList<>(2);
                ANNOUNCED.set(announced);
            }
            announced.add(item);
        }

        @Override
        public void onCopied(Item src, Item item) {
            onCreated(item);
        }
    }

    /**
     * Clears the mark of {@link CreationAnnounced} after every other item listener has run (core calls
     * each listener whatever the previous ones threw).
     */
    @Extension(ordinal = Integer.MIN_VALUE)
    @Restricted(NoExternalUse.class)
    public static final class CreationAnnouncedEnd extends ItemListener {

        @Override
        public void onCreated(Item item) {
            List<Item> announced = ANNOUNCED.get();
            if (announced == null) {
                return;
            }
            for (int i = announced.size() - 1; i >= 0; i--) {
                if (announced.get(i) == item) {
                    announced.remove(i);
                    break;
                }
            }
            if (announced.isEmpty()) {
                ANNOUNCED.remove();
            }
        }

        @Override
        public void onCopied(Item src, Item item) {
            onCreated(item);
        }
    }
}
