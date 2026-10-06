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
 * <p>The request work is a no-op until {@link StartupRecovery} has completed for the current Jenkins
 * session: an APPROVED request from before a restart must be judged from the recovery moment
 * (SPEC item 7 exception), so it may not be expired before recovery has re-based it. Writing the
 * ends of permission windows that could not be written before (D-74) does not wait for it, nor
 * does saving the items whose fail-closed change could not be saved before, nor writing the
 * cancelled queue items of approved runs that could not be written before.
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
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            return;
        }
        // D-74: write the ends of permission windows whose grant file could not be written when they
        // ended (they confer nothing meanwhile). Independent of startup recovery, and in its own try.
        try {
            io.jenkins.plugins.batchcontrol.security.GrantService.get().flushUnsavedEnds();
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not write the ends of permission windows", e);
        }
        // Likewise for items whose fail-closed change (the D-34 lock of a new job, the removal of a
        // creation payload's authorization property) is in effect but could not be saved.
        try {
            io.jenkins.plugins.batchcontrol.listener.UnsavedItemWrites.retry();
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not save the items whose fail-closed change is not saved yet", e);
        }
        // T-GAP-384: and for the cancelled queue items of approved runs whose request file could not be written.
        RunRequestService.get().retryUnsavedQueueCancels();
        // T-GAP-205: copies that stopped half-way outside an HTTP request (a script, the CLI over WebSocket).
        try {
            io.jenkins.plugins.batchcontrol.listener.GrantViolationGuard.Baseline.finishStaleCopies();
        } catch (RuntimeException | LinkageError e) {
            LOGGER.log(Level.WARNING, "Could not check the items left by copies that did not complete", e);
        }
        if (!StartupRecovery.isCompletedForCurrentSession()) {
            return;
        }
        // D-51a: close the refused re-run summaries whose window ended (their count record). First,
        // in its own try, so a failure of the expiry work below never skips it (S-22-03).
        try {
            io.jenkins.plugins.batchcontrol.store.BlockedAttemptAudit.get().flushPersonSummaries();
        } catch (RuntimeException e) {
            LOGGER.log(java.util.logging.Level.WARNING, "Could not close the refused re-run summaries", e);
        }
        // Every step below runs in its own try, so a failure of one (a request file that cannot be
        // read or written) never skips the steps after it.
        //
        // Queue snapshot is taken outside the service lock (lock-order discipline): a request
        // whose approved submission is waiting in the queue is not "unsubmitted" and must not
        // expire while it waits for an executor. The snapshot instant is recorded first so the
        // service can recognize consumption tickets claimed after the snapshot (MINOR 1 fix).
        try {
            Instant queueSnapshotAt = BatchClock.now();
            Set<String> queuedIds = RunRequestService.queuedMarkerRequestIds(jenkins);
            RunRequestService.get().expireOverdue(queuedIds, queueSnapshotAt);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Expiry of run requests failed", e);
        }
        // Pending grant requests expire on the same cadence (SPEC item 8, T-08-12).
        try {
            GrantRequestService.get().expireOverduePending();
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Expiry of pending change requests failed", e);
        }
        // Pending activation and hold requests follow the run-request timeout (SPEC item 6a).
        try {
            ActivationService.get().expireOverduePending();
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Expiry of pending activation requests failed", e);
        }
        // D-36: EXPIRING / GRANT_EXPIRING once per request or window, notifyBeforeExpiryMinutes
        // before the expiry. Guarded so a notification problem never stops the expiry work, and
        // one kind apart from the others so a failure of one never skips the notices of the next.
        try {
            RunRequestService.get().notifyExpiring();
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Expiry notifications of run requests failed; expiry itself is unaffected", e);
        }
        try {
            GrantRequestService.get().notifyExpiring();
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Expiry notifications of change requests and permission windows failed;"
                    + " expiry itself is unaffected", e);
        }
        try {
            ActivationService.get().notifyExpiring();
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Expiry notifications of activation requests failed; expiry itself is unaffected", e);
        }
    }
}
