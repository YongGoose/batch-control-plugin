package io.jenkins.plugins.batchcontrol.listener;

import com.cloudbees.hudson.plugins.folder.computed.ComputedFolder;
import hudson.Extension;
import hudson.XmlFile;
import hudson.model.AbstractItem;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Job;
import hudson.model.listeners.ItemListener;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import io.jenkins.plugins.batchcontrol.store.Store;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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
 * <p>D-31 (widening D-17), extended by D-34: while run control is on, every newly created job
 * starts <em>locked</em> — {@code approvalRequired=true}, {@code blockTimer=true},
 * {@code blockUpstream=true} — independently of the creator and of the creation path, so the act
 * of creating a job starts nothing. D-17 only covered jobs created inside an active grant window,
 * which left every job an administrator created in the ordinary course of work uncontrolled, and
 * D-31 only covered the human cause. The internal property save is suppressed from recording (only
 * the CREATE record remains, no recursion). D-32 carves out the one class of job that cannot honour
 * that default — a child a container computes for itself.
 */
@Extension
@Restricted(NoExternalUse.class)
public class ItemChangeListener extends ItemListener {

    private static final Logger LOGGER = Logger.getLogger(ItemChangeListener.class.getName());

    @Override
    public void onCreated(Item item) {
        // D-31/D-34 hang off the run-control switch alone, so the default is applied before the
        // recording gate (which also answers to the change-control switch, D-13).
        applyActivationLockDefault(item);
        if (!ChangeRecording.isActive()) {
            return;
        }
        String user = ChangeRecording.currentUser();
        String fullName = item.getFullName();
        seedSnapshot(item);
        ChangeRecord record = ChangeRecord.create(ChangeType.CREATE, fullName, user, null);
        record.setGrantId(ChangeRecording.activeGrantIdFor(user, fullName, GrantAction.CREATE));
        Store.get().appendChangeRecord(record);
    }

    @Override
    public void onDeleted(Item item) {
        if (!ChangeRecording.isActive()) {
            return;
        }
        String user = ChangeRecording.currentUser();
        String fullName = item.getFullName();
        ChangeRecord record = ChangeRecord.create(ChangeType.DELETE, fullName, user, null);
        Grant deleteGrant = GrantService.get().findActiveGrant(user, fullName, GrantAction.DELETE,
                item instanceof ItemGroup);
        record.setGrantId(deleteGrant == null ? null : deleteGrant.getId());
        Store.get().appendChangeRecord(record);
        Store.get().deleteConfigSnapshot(fullName);
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
        Store.get().appendChangeRecord(record);
    }

    @Override
    public void onLocationChanged(Item item, String oldFullName, String newFullName) {
        if (!ChangeRecording.isActive()) {
            return;
        }
        // The diff baseline follows the item to its new full name.
        Store.get().deleteConfigSnapshot(oldFullName);
        seedSnapshot(item);
        // onLocationChanged fires for renames too (which onRenamed already recorded); a MOVE
        // is a location change whose parent path changed.
        if (parentOf(oldFullName).equals(parentOf(newFullName))) {
            return;
        }
        String user = ChangeRecording.currentUser();
        // #84 (e2e-08 UX-2): a move across folders is usually authorised by two windows, the
        // Delete window on the source item and the Create window on the destination. grantId
        // keeps one id (the Create window, else the Delete window) so the record still links to a
        // grant; the detail names every window, in the existing fields (no new format).
        String destination = parentOf(newFullName);
        Grant deleteGrant = GrantService.get().findActiveGrant(user, oldFullName, GrantAction.DELETE,
                item instanceof ItemGroup);
        Grant createGrant = destination.isEmpty() ? null
                : GrantService.get().findActiveCreateGrant(user, destination, item.getName());
        StringBuilder detail = new StringBuilder("Moved from '").append(oldFullName)
                .append("' to '").append(newFullName).append('\'');
        List<String> windows = new ArrayList<>();
        if (deleteGrant != null) {
            windows.add("Delete on '" + oldFullName + "' from grant " + deleteGrant.getId());
        }
        if (createGrant != null) {
            windows.add("Create in '" + destination + "' from grant " + createGrant.getId());
        }
        if (!windows.isEmpty()) {
            detail.append("; permission windows used: ").append(String.join(", ", windows));
        }
        ChangeRecord record = ChangeRecord.create(ChangeType.MOVE, newFullName, user, detail.toString());
        Grant primary = createGrant != null ? createGrant : deleteGrant;
        record.setGrantId(primary != null ? primary.getId()
                : ChangeRecording.activeGrantIdFor(user, newFullName, null));
        Store.get().appendChangeRecord(record);
    }

    // ---------------------------------------------------------------- D-31/D-34 (widen D-17)

    /**
     * D-31 + D-34: run control on + the new item is a job → the job starts <em>locked</em>, with
     * {@code approvalRequired}, {@code blockTimer} and {@code blockUpstream} all on and no upstream
     * allow list. No creator and no creation path is exempt, which is why this sits on
     * {@link ItemListener#onCreated}: every creation entry point core offers (the New Item form, a
     * {@code createItem} config.xml POST, the CLI {@code create-job}, a job copy, a Job DSL
     * generation) ends in {@code ItemGroupMixIn}, which fires this event once per created item.
     * D-17 (creation inside a grant window) is the subset that stays covered. A child computed by
     * its container reaches this method through a different door — {@code ChildObserver#created} —
     * and is the one case D-32 turns away.
     *
     * <p>Why all three switches and not just {@code approvalRequired}: that switch refuses human
     * causes only (SPEC item 6, D-25), so a job created with a cron — inside a grant window or
     * not — used to start running immediately and keep running after the window closed, which is
     * the hole D-17 was written to close. D-34 therefore separates creating a job from operating
     * it: bringing the job into service means turning a switch off in its configuration, and that
     * change is itself change-controlled and recorded (SPEC items 8 and 9).
     *
     * <p>A value supplied in the creation payload does not win for any of the three switches, nor
     * for {@code allowedUpstreamJobs}: the default is what SPEC item 8 pins, and letting the
     * payload opt out would reopen the hole through the easiest path (a {@code config.xml} POST, a
     * CLI create, a Job DSL seed, a copy source) while leaving only a CREATE record behind. Opting
     * out is a subsequent configuration change, which is recorded and — while change control is
     * on — gated (P-13 for {@code approvalRequired}, P-14 for the two new switches and the allow
     * list). {@code jobApprovers} is the one setting carried over, because it can only narrow.
     *
     * <p>The property save is a plugin-internal write: it is suppressed from CONFIGURE recording
     * (which also guards against listener recursion through the save fired by
     * {@code addProperty}); the snapshot seeded afterwards already contains it.
     *
     * <p>D-32 is the single exemption: a job a container computes for itself (see
     * {@link #isComputedChild}).
     */
    private static void applyActivationLockDefault(Item item) {
        if (!BatchControlGlobalConfiguration.get().isRunControlEnabled()) {
            return;
        }
        if (!(item instanceof Job)) {
            return;
        }
        applyActivationLock((Job<?, ?>) item, "New job", "creating the job has not put it into service");
    }

    /**
     * Applies the D-34 lock to {@code job} exactly as for a newly created job ({@code approvalRequired},
     * {@code blockTimer} and {@code blockUpstream} on, {@code allowedUpstreamJobs} emptied,
     * {@code jobApprovers} kept), unless it is a computed child (D-32) or already locked. Used for
     * creation and for a job moved under change control (D-59a). The caller decides whether the
     * switches call for it. A failure is logged, never thrown.
     *
     * @param what    how the log line names the job, for example {@code "New job"}
     * @param outcome the end of the log line, saying what the lock means here
     */
    public static void applyActivationLock(Job<?, ?> job, String what, String outcome) {
        String fullName = job.getFullName();
        if (isComputedChild(job)) {
            LOGGER.fine(() -> "Job '" + fullName + "' is a computed child of '"
                    + job.getParent().getFullName() + "'; the activation lock does "
                    + "not apply to it. Its runs are still recorded.");
            return;
        }
        BatchControlJobProperty existing = job.getProperty(BatchControlJobProperty.class);
        if (existing != null && existing.isActivationLocked()) {
            return;
        }
        boolean previouslySuppressed = ChangeRecording.beginSuppression();
        try {
            BatchControlJobProperty applied = existing == null
                    ? BatchControlJobProperty.activationLocked()
                    : existing.withActivationLock();
            if (existing != null) {
                job.removeProperty(BatchControlJobProperty.class);
            }
            try {
                addProperty(job, applied);
            } catch (IOException e) {
                // S-20, fail closed. Core's removeProperty and addProperty each call save()
                // (hudson/model/Job#removeProperty, #addProperty), so the rebuild is two
                // persisted steps with a window between them. If the second fails the job is left
                // with NO BatchControlJobProperty at all — losing not just approvalRequired but
                // blockTimer, blockUpstream, allowedUpstreamJobs and jobApprovers, i.e. every
                // control over the unattended trigger paths — and the failure is only a WARNING,
                // so the job would go on running uncontrolled and unnoticed. Put the property the
                // job already had back before reporting, so the worst outcome of a failed rebuild
                // is the controls the creator supplied rather than none.
                if (existing != null) {
                    restoreAfterFailedRebuild(job, existing, fullName, e);
                }
                throw e;
            }
            LOGGER.info(() -> what + " '" + fullName + "' starts locked while run control is on: "
                    + "approvalRequired, blockTimer and blockUpstream are all on, so " + outcome);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to apply the activation lock to the job '" + fullName + "'", e);
        } finally {
            ChangeRecording.endSuppression(previouslySuppressed);
        }
    }

    /**
     * Puts {@code existing} back after the D-31/D-34 rebuild removed it and failed to add the
     * replacement (S-20). Still inside the suppressed window, so the restore is not recorded as a
     * user CONFIGURE change.
     *
     * <p>If the restore itself fails there is nothing further to try — both writes go through the
     * same {@code save()} — so it is attached to the original failure as a suppressed exception
     * rather than replacing it: the operator needs to read "the default could not be applied"
     * first and "and the job now has no property" second.
     */
    private static void restoreAfterFailedRebuild(Job<?, ?> job, BatchControlJobProperty existing,
                                                  String fullName, IOException failure) {
        try {
            addProperty(job, existing);
            LOGGER.log(Level.WARNING, () -> "Applying the new-job activation lock to '"
                    + fullName + "' failed; the job's previous batch-control property was restored,"
                    + " so its existing controls stay in force");
        } catch (IOException restoreFailure) {
            failure.addSuppressed(restoreFailure);
            LOGGER.log(Level.SEVERE, () -> "Job '" + fullName + "' was left with no batch-control"
                    + " property: applying the new-job activation lock failed and restoring"
                    + " the previous property failed as well. The job is not run-controlled until"
                    + " its configuration is saved again");
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void addProperty(Job job, BatchControlJobProperty property) throws IOException {
        job.addProperty(property);
    }

    /**
     * D-32: is this item a child a container generates and regenerates on its own? Such a child is
     * exempt from the D-31/D-34 default (D-32 survives D-34 unchanged — the switches could never be
     * turned off again), because the two things the default relies on do not hold for it: it
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
                Store.get().saveConfigSnapshot(item.getFullName(), config.asString());
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
