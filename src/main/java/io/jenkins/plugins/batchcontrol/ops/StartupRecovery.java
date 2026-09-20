package io.jenkins.plugins.batchcontrol.ops;

import hudson.init.InitMilestone;
import hudson.init.Initializer;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
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

    /** Whether recovery has completed for the currently running Jenkins instance. */
    public static boolean isCompletedForCurrentSession() {
        Jenkins current = Jenkins.getInstanceOrNull();
        return current != null && completedFor.get() == current;
    }
}
