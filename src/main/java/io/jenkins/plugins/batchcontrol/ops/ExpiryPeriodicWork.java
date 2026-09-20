package io.jenkins.plugins.batchcontrol.ops;

import hudson.Extension;
import hudson.model.PeriodicWork;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.TimeUnit;
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

    @Override
    public long getRecurrencePeriod() {
        return TimeUnit.MINUTES.toMillis(1);
    }

    @Override
    public void doRun() {
        if (!StartupRecovery.isCompletedForCurrentSession()) {
            return;
        }
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            return;
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
    }
}
