package io.jenkins.plugins.batchcontrol.listener;

import com.cloudbees.hudson.plugins.folder.computed.ComputedFolder;
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
 * from recording (only the CREATE record remains, no recursion). D-32 carves out the one class of
 * job that cannot honour that default — a child a container computes for itself.
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
     * generation) ends in {@code ItemGroupMixIn}, which fires this event once per created item.
     * D-17 (creation inside a grant window) is the subset that stays covered. A child computed by
     * its container reaches this method through a different door — {@code ChildObserver#created} —
     * and is the one case D-32 turns away.
     *
     * <p>An {@code approvalRequired=false} supplied in the creation payload does not win: the
     * default is what SPEC item 8 pins, and letting the payload opt out would reopen the hole
     * through the easiest path while leaving only a CREATE record behind. Opting out is a
     * subsequent configuration change, which is itself change-controlled and recorded (P-13).
     *
     * <p>The property save is a plugin-internal write: it is suppressed from CONFIGURE recording
     * (which also guards against listener recursion through the save fired by
     * {@code addProperty}); the snapshot seeded afterwards already contains it.
     *
     * <p>D-32 is the single exemption: a job a container computes for itself (see
     * {@link #isComputedChild}).
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
        if (isComputedChild(job)) {
            LOGGER.fine(() -> "Job '" + fullName + "' is a computed child of '"
                    + job.getParent().getFullName() + "'; the approvalRequired=true default does "
                    + "not apply to it (D-32). Its runs are still recorded.");
            return;
        }
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

    /**
     * D-32: is this item a child a container generates and regenerates on its own? Such a child is
     * exempt from the D-31 default, because the two things D-31 relies on do not hold for it: it
     * has no configuration screen, so the documented way out ("edit the job, and the edit is
     * recorded") does not exist; and the container rebuilds the child's configuration on every
     * recomputation, so the property is not guaranteed to survive — the control would blink on and
     * off. Its runs are still recorded (SPEC item 10).
     *
     * <p>The test is the generic property "my parent computes its children", not "my parent is a
     * multibranch project": the item's parent being a {@code ComputedFolder}. Three facts from the
     * dependency sources make that the exact condition:
     * <ul>
     *   <li>{@code ComputedFolder} (cloudbees-folder) declares {@code computeChildren(ChildObserver,
     *       TaskListener)} abstract and calls it from {@code updateChildren} — being a
     *       {@code ComputedFolder} <em>is</em> the contract "I own and recreate my children".</li>
     *   <li>The creation event this listener answers to is fired by that machinery and nowhere
     *       else for such children: {@code ChildObserver#created(I)}, implemented by
     *       {@code ComputedFolder$FullReindexChildObserver} and
     *       {@code ComputedFolder$EventChildObserver}, calls {@code ItemListener.fireOnCreated}
     *       after adding the child to the folder. So every computed child — and only a computed
     *       child — arrives here with a {@code ComputedFolder} as its parent.</li>
     *   <li>{@code jenkins.branch.MultiBranchProject extends ComputedFolder<P>} (branch-api), which
     *       is what makes a multibranch branch job the case D-32 names, and
     *       {@code jenkins.branch.OrganizationFolder extends ComputedFolder<MultiBranchProject>},
     *       so the same rule covers the repository projects an organization folder computes.</li>
     * </ul>
     *
     * <p>No optional dependency is touched. branch-api and workflow-multibranch are test-scoped
     * here and are never loaded by this check; {@code cloudbees-folder} is a non-optional compile
     * dependency of this plugin, so Jenkins refuses to load batch-control without it and the class
     * is always present. That is why this is a plain {@code instanceof} rather than the class-name
     * match {@link io.jenkins.plugins.batchcontrol.queue.ApprovalQueueDecisionHandler} uses for
     * Pipeline's {@code ReplayCause} (workflow-cps is genuinely absent on some instances).
     *
     * <p>The container itself is never a concern: a {@code ComputedFolder} is an {@code
     * AbstractFolder}, not a {@code Job}, so it is already filtered out above.
     */
    private static boolean isComputedChild(Item item) {
        return item.getParent() instanceof ComputedFolder;
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
