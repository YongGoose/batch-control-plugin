package io.jenkins.plugins.batchcontrol.listener;

import hudson.Extension;
import hudson.model.Cause;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.listeners.RunListener;
import hudson.triggers.SCMTrigger;
import hudson.triggers.TimerTrigger;
import io.jenkins.plugins.batchcontrol.model.CauseType;
import io.jenkins.plugins.batchcontrol.model.RunRecord;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.ops.IncidentService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.queue.ApprovedCause;
import io.jenkins.plugins.batchcontrol.store.Store;
import java.time.Instant;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.CauseOfInterruption;
import jenkins.model.InterruptedBuildAction;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Appends a {@link RunRecord} for EVERY completed build — Freestyle, Pipeline and multibranch
 * children alike (SPEC item 10) — and drives the SPEC item 11 automatics: incident registration
 * and the successful-rerun {@code resolvedByRunId} link.
 *
 * <p>Recording is active while ANY global switch is on (D-13) and fully off when both are off.
 *
 * <p>{@code onFinalized} is the single append point (never {@code onCompleted} as well, so a
 * build is never recorded twice): at finalization the result, the duration, the console log and
 * the {@link InterruptedBuildAction} of an aborted build are all reliably present, for
 * Freestyle and Pipeline runs alike.
 */
@Extension
@Restricted(NoExternalUse.class)
public class RunRecordListener extends RunListener<Run<?, ?>> {

    private static final Logger LOGGER = Logger.getLogger(RunRecordListener.class.getName());

    @Override
    public void onFinalized(Run<?, ?> run) {
        if (!ChangeRecording.isActive()) {
            return; // D-13: no switch on, the plugin records nothing at all
        }
        // The three concerns are isolated: a failure in one must not lose the others.
        try {
            Store.get().appendRunRecord(buildRecord(run));
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, e, () -> "Failed to append the run record of "
                    + run.getFullDisplayName());
        }
        try {
            IncidentService.get().openForRun(run);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, e, () -> "Failed to open an incident for "
                    + run.getFullDisplayName());
        }
        try {
            linkResolvedRerun(run);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, e, () -> "Failed to link the successful rerun "
                    + run.getFullDisplayName() + " to its incident");
        }
    }

    // ---------------------------------------------------------------- record capture

    private static RunRecord buildRecord(Run<?, ?> run) {
        String jobFullName = run.getParent().getFullName();
        String runId = jobFullName + "#" + run.getNumber();
        ApprovedCause approved = ownApproval(run);
        Result result = run.getResult();
        RunRecord record = new RunRecord(runId, jobFullName, run.getNumber(),
                classify(run, approved),
                result == null ? null : result.toString(),
                Instant.ofEpochMilli(run.getStartTimeInMillis()),
                run.getDuration());
        if (approved != null) {
            record.setRunRequestId(approved.getRequestId());
        }
        Cause.UserIdCause userCause = run.getCause(Cause.UserIdCause.class);
        if (approved != null && approved.getRequester() != null) {
            // e2e-04 FD-09: a run started by an approved request is the requester's run (History,
            // runs.csv, the dashboard and the user= filter); the request id stays on the record.
            record.setUser(approved.getRequester());
        } else if (userCause != null) {
            record.setUser(userCause.getUserId());
        }
        record.setParameters(IncidentService.maskedParameters(run));
        record.setAbortedBy(abortedBy(run));
        return record;
    }

    /**
     * The run's {@link ApprovedCause} only when this run is that request's own execution (the
     * request's {@code executedRunId} names it), else {@code null} (security-25 S-25-01). A Rebuild
     * or a retry copies the cause of the build it repeats; such a run is its clicker's, not the
     * earlier requester's, so its user, cause type and request link come from its own causes.
     */
    @edu.umd.cs.findbugs.annotations.CheckForNull
    static ApprovedCause ownApproval(Run<?, ?> run) {
        ApprovedCause approved = run.getCause(ApprovedCause.class);
        if (approved == null) {
            return null;
        }
        String runId = run.getParent().getFullName() + "#" + run.getNumber();
        try {
            RunRequest request = RunRequestService.get().load(approved.getRequestId());
            return request != null && runId.equals(request.getExecutedRunId()) ? approved : null;
        } catch (RuntimeException e) {
            java.util.logging.Logger.getLogger(RunRecordListener.class.getName()).log(java.util.logging.Level.WARNING,
                    "Could not read run request " + approved.getRequestId() + " for " + runId, e);
            return null;
        }
    }

    /**
     * SPEC item 10 cause classification: USER / TIMER / UPSTREAM / APPROVED_REQUEST / SCM /
     * OTHER. The approved-request marker cause wins over everything; the remaining families
     * are checked in user → timer → upstream → scm order ({@code BuildUpstreamCause} of the
     * {@code build} step is an {@code UpstreamCause} subclass and classifies as UPSTREAM).
     */
    private static CauseType classify(Run<?, ?> run, ApprovedCause approved) {
        if (approved != null) {
            return CauseType.APPROVED_REQUEST;
        }
        if (run.getCause(Cause.UserIdCause.class) != null) {
            return CauseType.USER;
        }
        if (run.getCause(TimerTrigger.TimerTriggerCause.class) != null) {
            return CauseType.TIMER;
        }
        if (run.getCause(Cause.UpstreamCause.class) != null) {
            return CauseType.UPSTREAM;
        }
        if (run.getCause(SCMTrigger.SCMTriggerCause.class) != null) {
            return CauseType.SCM;
        }
        return CauseType.OTHER;
    }

    /** The interrupting user Jenkins recorded on an aborted build, or {@code null}. */
    private static String abortedBy(Run<?, ?> run) {
        InterruptedBuildAction interrupted = run.getAction(InterruptedBuildAction.class);
        if (interrupted == null) {
            return null;
        }
        List<CauseOfInterruption> causes = interrupted.getCauses();
        if (causes == null) {
            return null;
        }
        for (CauseOfInterruption cause : causes) {
            if (cause instanceof CauseOfInterruption.UserInterruption) {
                return ((CauseOfInterruption.UserInterruption) cause).getUserId();
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- rerun auto-link (T-11-02)

    /**
     * SPEC item 11: when a build executed for an incident-linked request ends SUCCESS, the
     * incident records {@code resolvedByRunId} (status untouched — resolution is a human
     * decision).
     */
    private static void linkResolvedRerun(Run<?, ?> run) {
        if (run.getResult() != Result.SUCCESS) {
            return;
        }
        ApprovedCause approved = ownApproval(run);
        if (approved == null) {
            return;
        }
        RunRequest request = RunRequestService.get().load(approved.getRequestId());
        if (request == null || request.getIncidentId() == null) {
            return;
        }
        String runId = run.getParent().getFullName() + "#" + run.getNumber();
        IncidentService.get().linkResolvedBy(request.getIncidentId(), runId);
    }
}
