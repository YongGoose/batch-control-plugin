package io.jenkins.plugins.batchcontrol.ops;

import hudson.Extension;
import hudson.model.PeriodicWork;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Periodic status refresh for expiry (SPEC item 7). Expiry truth is always the clock
 * comparison inside the policy service ({@code BatchClock}); this work only updates stored
 * statuses so overdue requests read EXPIRED. Tests invoke {@link #doRun()} directly.
 *
 * <p>The work is a no-op until {@link StartupRecovery} has completed for the current Jenkins
 * session: an APPROVED request from before a restart must be judged from the recovery moment
 * (SPEC item 7 exception), so it may not be expired before recovery has re-based it.
 *
 * <p>S3 hook: pending grant requests will be expired here as well.
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
        // expire while it waits for an executor.
        Set<String> queuedIds = RunRequestService.queuedMarkerRequestIds(jenkins);
        RunRequestService.get().expireOverdue(queuedIds);
    }
}
