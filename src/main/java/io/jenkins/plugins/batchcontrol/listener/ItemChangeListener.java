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
 * <p>D-17: when run control is on and a job is created by a user inside an active grant window,
 * the job automatically gets {@code approvalRequired=true} so a permission window can never be
 * used to plant an approval-free execution path. The internal property save is suppressed from
 * recording (only the CREATE record remains, no recursion).
 */
@Extension
@Restricted(NoExternalUse.class)
public class ItemChangeListener extends ItemListener {

    private static final Logger LOGGER = Logger.getLogger(ItemChangeListener.class.getName());

    @Override
    public void onCreated(Item item) {
        if (!ChangeRecording.isActive()) {
            return;
        }
        String user = ChangeRecording.currentUser();
        String fullName = item.getFullName();
        forceApprovalRequiredIfCreatedUnderGrant(item, user, fullName);
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

    // ---------------------------------------------------------------- D-17

    /**
     * D-17: run control on + the creator holds an active grant covering the new item → the job
     * defaults to {@code approvalRequired=true}. The property save is a plugin-internal write:
     * it is suppressed from CONFIGURE recording (guards against listener recursion through the
     * save fired by {@code addProperty}); the snapshot seeded afterwards already contains it.
     */
    private static void forceApprovalRequiredIfCreatedUnderGrant(Item item, String user, String fullName) {
        if (!BatchControlGlobalConfiguration.get().isRunControlEnabled()) {
            return;
        }
        if (!(item instanceof Job)) {
            return;
        }
        if (ChangeRecording.activeGrantIdFor(user, fullName, null) == null) {
            return;
        }
        Job<?, ?> job = (Job<?, ?>) item;
        BatchControlJobProperty existing = job.getProperty(BatchControlJobProperty.class);
        if (existing != null && existing.isApprovalRequired()) {
            return;
        }
        ChangeRecording.beginSuppression();
        try {
            if (existing != null) {
                job.removeProperty(BatchControlJobProperty.class);
            }
            addApprovalRequiredProperty(job);
            LOGGER.info(() -> "Job '" + fullName + "' was created by '" + user
                    + "' inside an active grant window; approvalRequired=true applied (D-17)");
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to apply approvalRequired=true to job '"
                    + fullName + "' created inside a grant window (D-17)", e);
        } finally {
            ChangeRecording.endSuppression();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void addApprovalRequiredProperty(Job job) throws IOException {
        job.addProperty(new BatchControlJobProperty(true));
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
