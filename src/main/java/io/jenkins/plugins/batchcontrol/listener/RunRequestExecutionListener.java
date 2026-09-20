package io.jenkins.plugins.batchcontrol.listener;

import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.queue.ApprovedRunAction;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Completes the APPROVED → EXECUTED transition (SPEC section 4): when a build carrying the
 * approval marker starts, the linked request is marked EXECUTED with the run id
 * ({@code jobFullName#number}).
 */
@Extension
@Restricted(NoExternalUse.class)
public class RunRequestExecutionListener extends RunListener<Run<?, ?>> {

    @Override
    public void onStarted(Run<?, ?> run, TaskListener listener) {
        ApprovedRunAction marker = run.getAction(ApprovedRunAction.class);
        if (marker == null) {
            return;
        }
        String runId = run.getParent().getFullName() + "#" + run.getNumber();
        RunRequestService.get().markExecuted(marker.getRequestId(), runId);
    }
}
