package io.jenkins.plugins.batchcontrol.listener;

import hudson.Extension;
import hudson.XmlFile;
import hudson.model.AbstractItem;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.listeners.ItemListener;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Records item lifecycle events (SPEC item 9): CREATE, DELETE, RENAME and MOVE, with the acting
 * user, the {@link io.jenkins.plugins.batchcontrol.store.BatchClock} timestamp and — when the
 * actor holds an active matching grant — the grant id. Active while ANY global switch is on;
 * with both switches off nothing is written (SPEC item 9 last criterion, D-13).
 *
 * <p>CONFIGURE records (with the unified diff) come from {@link ConfigSnapshotListener}; this
 * listener seeds and relocates the diff baseline snapshots around lifecycle events.
 *
 * <p>D-31 (widening D-17): while run control is on, every newly created job gets
 * {@code approvalRequired=true}, independently of the creator and of the creation path. D-17 only
 * covered jobs created inside an active grant window, which left every job an administrator
 * created in the ordinary course of work uncontrolled. The internal property save is suppressed
 * from recording (only the CREATE record remains, no recursion).
 */
@Extension
@Restricted(NoExternalUse.class)
public class ItemChangeListener extends ItemListener {

    private static final Logger LOGGER = Logger.getLogger(ItemChangeListener.class.getName());

    @Override
    public void onCreated(Item item) {
        // D-31 hangs off the run-control switch alone, so it is applied before the recording
        // gate (which also answers to the change-control switch, D-13).
        applyApprovalRequiredDefault(item);
        if (!ChangeRecording.isActive()) {
            return;
        }
        String user = ChangeRecording.currentUser();
        String fullName = item.getFullName();
        seedSnapshot(item);
        ChangeRecord record = ChangeRecord.create(ChangeType.CREATE, fullName, user, null);
        record.setGrantId(ChangeRecording.activeGrantIdFor(user, fullName, GrantAction.CREATE));
        FileStore.get().appendChangeRecord(record);
    }

    @Override
    public void onDeleted(Item item) {
        if (!ChangeRecording.isActive()) {
            return;
        }
        String user = ChangeRecording.currentUser();
        String fullName = item.getFullName();
        ChangeRecord record = ChangeRecord.create(ChangeType.DELETE, fullName, user, null);
        record.setGrantId(ChangeRecording.activeGrantIdFor(user, fullName, GrantAction.DELETE));
        FileStore.get().appendChangeRecord(record);
        FileStore.get().deleteConfigSnapshot(fullName);
    }

    @Override
    public void onRenamed(Item item, String oldName, String newName) {
        if (!ChangeRecording.isActive()) {
            return;
        }
        String user = ChangeRecording.currentUser();
        String fullName = item.getFullName();
        ChangeRecord record = ChangeRecord.create(ChangeType.RENAME, fullName, user,
                "Renamed from '" + oldName + "' to '" + newName + "'");
        record.setGrantId(ChangeRecording.activeGrantIdFor(user, fullName, null));
        FileStore.get().appendChangeRecord(record);
    }

    @Override
    public void onLocationChanged(Item item, String oldFullName, String newFullName) {
        if (!ChangeRecording.isActive()) {
            return;
        }
        // The diff baseline follows the item to its new full name.
        FileStore.get().deleteConfigSnapshot(oldFullName);
        seedSnapshot(item);
        // onLocationChanged fires for renames too (which onRenamed already recorded); a MOVE
        // is a location change whose parent path changed.
        if (parentOf(oldFullName).equals(parentOf(newFullName))) {
            return;
        }
        String user = ChangeRecording.currentUser();
        ChangeRecord record = ChangeRecord.create(ChangeType.MOVE, newFullName, user,
                "Moved from '" + oldFullName + "' to '" + newFullName + "'");
        record.setGrantId(ChangeRecording.activeGrantIdFor(user, newFullName, null));
        FileStore.get().appendChangeRecord(record);
    }

    // ---------------------------------------------------------------- D-31 (widens D-17)

    /**
     * D-31: run control on + the new item is a job → the job starts with
     * {@code approvalRequired=true}. No creator and no creation path is exempt, which is why this
     * sits on {@link ItemListener#onCreated}: every creation entry point core offers (the New Item
     * form, a {@code createItem} config.xml POST, the CLI {@code create-job}, a job copy, a Job DSL
     * or multibranch generation) ends in {@code ItemGroupMixIn}, which fires this event once per
     * created item. D-17 (creation inside a grant window) is the subset that stays covered.
     *
     * <p>An {@code approvalRequired=false} supplied in the creation payload does not win: the
     * default is what SPEC item 8 pins, and letting the payload opt out would reopen the hole
     * through the easiest path while leaving only a CREATE record behind. Opting out is a
     * subsequent configuration change, which is itself change-controlled and recorded (P-13).
     *
     * <p>The property save is a plugin-internal write: it is suppressed from CONFIGURE recording
     * (which also guards against listener recursion through the save fired by
     * {@code addProperty}); the snapshot seeded afterwards already contains it.
     */
    private static void applyApprovalRequiredDefault(Item item) {
        if (!BatchControlGlobalConfiguration.get().isRunControlEnabled()) {
            return;
        }
        if (!(item instanceof Job)) {
            return;
        }
        Job<?, ?> job = (Job<?, ?>) item;
        String fullName = job.getFullName();
        BatchControlJobProperty existing = job.getProperty(BatchControlJobProperty.class);
        if (existing != null && existing.isApprovalRequired()) {
            return;
        }
        ChangeRecording.beginSuppression();
        try {
            BatchControlJobProperty applied = existing == null
                    ? new BatchControlJobProperty(true)
                    : existing.withApprovalRequired(true);
            if (existing != null) {
                job.removeProperty(BatchControlJobProperty.class);
            }
            addProperty(job, applied);
            LOGGER.info(() -> "New job '" + fullName
                    + "' starts with approvalRequired=true while run control is on (D-31)");
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to apply the approvalRequired=true default to the "
                    + "newly created job '" + fullName + "' (D-31)", e);
        } finally {
            ChangeRecording.endSuppression();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void addProperty(Job job, BatchControlJobProperty property) throws IOException {
        job.addProperty(property);
    }

    // ---------------------------------------------------------------- snapshots

    /** Stores the item's current config.xml as the diff baseline. */
    private static void seedSnapshot(Item item) {
        if (!(item instanceof AbstractItem)) {
            return;
        }
        try {
            XmlFile config = ((AbstractItem) item).getConfigFile();
            if (config.exists()) {
                FileStore.get().saveConfigSnapshot(item.getFullName(), config.asString());
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to snapshot config of '" + item.getFullName() + "'", e);
        }
    }

    private static String parentOf(String fullName) {
        int idx = fullName.lastIndexOf('/');
        return idx < 0 ? "" : fullName.substring(0, idx);
    }
}
