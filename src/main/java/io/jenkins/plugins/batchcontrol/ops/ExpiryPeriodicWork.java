package io.jenkins.plugins.batchcontrol.ops;

import hudson.Extension;
import hudson.model.PeriodicWork;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Periodic status refresh for expiry (SPEC items 7 and 8). Expiry truth is always the clock
 * comparison inside the policy services ({@code BatchClock}); this work only updates stored
 * statuses so overdue requests read EXPIRED. Tests invoke {@link #doRun()} directly.
 *
 * <p>The work is a no-op until {@link StartupRecovery} has completed for the current Jenkins
 * session: an APPROVED request from before a restart must be judged from the recovery moment
 * (SPEC item 7 exception), so it may not be expired before recovery has re-based it.
 */
@Extension
@Restricted(NoExternalUse.class)
public class ExpiryPeriodicWork extends PeriodicWork {

    private static final Logger LOGGER = Logger.getLogger(ExpiryPeriodicWork.class.getName());

    @Override
    public long getRecurrencePeriod() {
        return TimeUnit.MINUTES.toMillis(1);
    }

    @Override
    // PeriodicWork callback, not an HTTP entry point.
    @SuppressWarnings({"lgtm[jenkins/csrf]", "lgtm[jenkins/no-permission-check]"})
    public void doRun() {
        if (!StartupRecovery.isCompletedForCurrentSession()) {
            return;
        }
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            return;
        }
        // D-51a: close the refused re-run summaries whose window ended (their count record). First,
        // in its own try, so a failure of the expiry work below never skips it (S-22-03).
        try {
            io.jenkins.plugins.batchcontrol.store.BlockedAttemptAudit.get().flushPersonSummaries();
        } catch (RuntimeException e) {
            LOGGER.log(java.util.logging.Level.WARNING, "Could not close the refused re-run summaries", e);
        }
        // D-71c (security-36 S-36-03 (ii)): an unbinding whose file could not be written is
        // written again here (it already confers nothing in memory). Its own try, like the above.
        try {
            io.jenkins.plugins.batchcontrol.security.GrantService.get().retryUnsavedUnbindings();
        } catch (RuntimeException e) {
            LOGGER.log(java.util.logging.Level.WARNING, "Could not retry the unbindings of permission windows", e);
        }
        // Queue snapshot is taken outside the service lock (lock-order discipline): a request
        // whose approved submission is waiting in the queue is not "unsubmitted" and must not
        // expire while it waits for an executor. The snapshot instant is recorded first so the
        // service can recognize consumption tickets claimed after the snapshot (MINOR 1 fix).
        Instant queueSnapshotAt = BatchClock.now();
        Set<String> queuedIds = RunRequestService.queuedMarkerRequestIds(jenkins);
        RunRequestService.get().expireOverdue(queuedIds, queueSnapshotAt);
        // Pending grant requests expire on the same cadence (SPEC item 8, T-08-12).
        GrantRequestService.get().expireOverduePending();
        // Pending activation and hold requests follow the run-request timeout (SPEC item 6a).
        try {
            ActivationService.get().expireOverduePending();
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Expiry of pending activation requests failed", e);
        }
        // D-36: EXPIRING / GRANT_EXPIRING once per request or window, notifyBeforeExpiryMinutes
        // before the expiry. Guarded so a notification problem never stops the expiry work.
        try {
            RunRequestService.get().notifyExpiring();
            GrantRequestService.get().notifyExpiring();
            ActivationService.get().notifyExpiring();
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Expiry notifications failed; expiry itself is unaffected", e);
        }
    }
}
