package io.jenkins.plugins.batchcontrol.ops;

import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.util.ArrayList;
import java.util.List;
import java.lang.ref.WeakReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Restart durability (SPEC item 4): after every startup, APPROVED requests that never
 * executed are re-submitted exactly once (idempotent via the consumption ticket and the
 * restored-queue/existing-build checks in the policy service).
 *
 * <p>Runs after {@link InitMilestone#JOB_CONFIG_ADAPTED}, the same initializer band in which
 * Jenkins core restores the queue ({@code Queue.init}); the policy service coordinates with
 * that restore under the queue lock so the recovery dedup always sees the restored queue
 * items (T-RT-17). {@code after = COMPLETED} would stall initialization (JENKINS-37759).
 *
 * <p>The completion flag is tracked per Jenkins session (weak reference to the instance, so
 * test harnesses that boot several Jenkins in one JVM reset it naturally); it gates
 * {@link ExpiryPeriodicWork} so pre-restart approvals are never expired before recovery has
 * re-based their timeout to the recovery moment (SPEC item 7 exception).
 */
@Restricted(NoExternalUse.class)
public final class StartupRecovery {

    private static final Logger LOGGER = Logger.getLogger(StartupRecovery.class.getName());

    private static volatile WeakReference<Jenkins> completedFor = new WeakReference<>(null);

    private StartupRecovery() {
    }

    @Initializer(after = InitMilestone.JOB_CONFIG_ADAPTED)
    public static void recover() {
        prepareStore();
        try {
            RunRequestService.get().recoverApprovedRequests();
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Startup recovery of approved run requests failed", e);
        } finally {
            // Always mark the session recovered, even on failure: expiry must not stay
            // disabled for the whole session because one request could not be recovered.
            completedFor = new WeakReference<>(Jenkins.getInstanceOrNull());
        }
    }

    /**
     * Store upkeep that must happen before anything reads or writes (#17, #25, #13): repair month
     * buckets named under a non-ASCII-digit locale, move config snapshots written under the
     * pre-#25 shortened name form, and build the in-memory entity index now, so the first save on
     * the queue path never pays for it. Initializers run as the system (no switch happens here),
     * so {@code allItems()} sees every item.
     */
    private static void prepareStore() {
        FileStore store = FileStore.get();
        try {
            store.normalizeMonthFileNames();
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not repair month bucket names", e);
        }
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins != null) {
            try {
                List<String> names = new ArrayList<>();
                for (Item item : jenkins.allItems()) {
                    names.add(item.getFullName());
                }
                store.migrateLegacySnapshots(names);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Could not migrate legacy config snapshots", e);
            }
        }
        try {
            store.warmUp();
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not build the batch-control entity index", e);
        }
    }

    /** Whether recovery has completed for the currently running Jenkins instance. */
    public static boolean isCompletedForCurrentSession() {
        Jenkins current = Jenkins.getInstanceOrNull();
        return current != null && completedFor.get() == current;
    }
}
