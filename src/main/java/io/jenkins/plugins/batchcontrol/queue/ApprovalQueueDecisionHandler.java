package io.jenkins.plugins.batchcontrol.queue;

import hudson.Extension;
import hudson.model.Action;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Failure;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.triggers.SCMTrigger;
import hudson.triggers.TimerTrigger;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.BlockedAttemptAudit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * The queue gate (SPEC item 6, D-03): every run path goes through
 * {@link Queue.QueueDecisionHandler}, so approval-required jobs are controlled at one point.
 *
 * <p>Decision order:
 * <ol>
 *   <li>run control off, task not a job, or job not approval-required → pass;</li>
 *   <li>approval marker present → validate and consume it (D-23), pass or refuse quietly;</li>
 *   <li>Pipeline Replay → refuse quietly (the replay UI has no error channel for a Failure);</li>
 *   <li>remote (build-token) cause → refuse quietly and record the attempt (S-14);</li>
 *   <li>user-originated causes (UserIdCause, incl. the CLI subtype) → throw
 *       {@link Failure} with guidance and a link to the request screen (no silent failure,
 *       PoC finding D-1);</li>
 *   <li>timer cause → pass unless {@code blockTimer}, refused quietly (unattended);</li>
 *   <li>upstream cause → D-16 policy: pass unless {@code blockUpstream}; with
 *       {@code blockUpstream} only allow-listed upstream jobs pass, an empty/unset list
 *       blocks all; refused quietly (unattended, the upstream build surfaces the failure);</li>
 *   <li>SCM causes and anything unknown/empty → pass (logged).</li>
 * </ol>
 */
@Extension
@Restricted(NoExternalUse.class)
public class ApprovalQueueDecisionHandler extends Queue.QueueDecisionHandler {

    private static final Logger LOGGER =
            Logger.getLogger(ApprovalQueueDecisionHandler.class.getName());

    /** workflow-cps is an optional dependency; the replay cause is matched by name. */
    private static final String REPLAY_CAUSE_CLASS =
            "org.jenkinsci.plugins.workflow.cps.replay.ReplayCause";

    /** An INFO line per job and kind is written at most this often; the rest go to FINE. */
    static final long INFO_INTERVAL_MILLIS = 60L * 60L * 1000L;

    /** Bound on the rate-limit map; it is simply cleared when full. */
    private static final int MAX_RATE_LIMIT_ENTRIES = 10_000;

    private static final String RATE_LIMIT_SUFFIX =
            " (further occurrences for this job are logged at FINE for the next hour)";

    /** Last INFO time in epoch millis, keyed by kind plus job full name. */
    private static final ConcurrentMap<String, Long> LAST_INFO = new ConcurrentHashMap<>();

    @Override
    public boolean shouldSchedule(Queue.Task p, List<Action> actions) {
        if (!BatchControlGlobalConfiguration.get().isRunControlEnabled()) {
            return true;
        }
        if (!(p instanceof Job)) {
            return true;
        }
        Job<?, ?> job = (Job<?, ?>) p;
        BatchControlJobProperty property = job.getProperty(BatchControlJobProperty.class);
        if (property == null || !property.isApprovalRequired()) {
            return true;
        }

        // 1. Approved submission: the marker authorizes exactly one queue entry (D-23).
        for (Action action : actions) {
            if (action instanceof ApprovedRunAction) {
                ApprovedRunAction marker = (ApprovedRunAction) action;
                boolean consumed = RunRequestService.get()
                        .consumeMarker(marker.getRequestId(), job.getFullName());
                if (!consumed) {
                    LOGGER.warning(() -> "Blocked submission of job '" + job.getFullName()
                            + "' with an invalid or already consumed approval marker (request "
                            + marker.getRequestId() + ")");
                }
                return consumed;
            }
        }

        List<Cause> causes = collectCauses(actions);

        // 2. Pipeline Replay: refused quietly (ReplayAction.run has no Failure channel).
        for (Cause cause : causes) {
            if (REPLAY_CAUSE_CLASS.equals(cause.getClass().getName())) {
                logRateLimited("replay", job,
                        () -> "Blocked replay of approval-required job '" + job.getFullName() + "'");
                return false;
            }
        }

        // 3. Remote trigger with a build token: refused quietly and recorded (S-14).
        //
        // Core mints Cause.RemoteCause — not UserIdCause — as soon as a build token is presented
        // on /job/X/build or /job/X/buildWithParameters (one shared static,
        // ParameterizedJobMixIn#getBuildCause, so both endpoints behave identically), and
        // BuildAuthorizationToken#checkPermission returns on a token match before it would reach
        // checkPermission(Item.BUILD). Before this branch existed such a submission fell through
        // to step 7 and passed, which made the two endpoints SPEC item 6 names as blocked
        // bypassable by anyone holding the token string.
        //
        // Refused quietly, like the timer and upstream refusals and unlike step 4: the caller is
        // a script reading an HTTP status, so guidance text has no reader. Quiet is why the
        // attempt is written to the audit history instead — the same reasoning D-30 applies to a
        // blocked marker re-use. The record is bounded (S-21): see BlockedAttemptAudit.
        for (Cause cause : causes) {
            if (cause instanceof Cause.RemoteCause) {
                LOGGER.warning(() -> "Blocked a remote (build-token) run of approval-required job '"
                        + job.getFullName() + "': " + cause.getShortDescription());
                BlockedAttemptAudit.get().record(ChangeType.REMOTE_RUN_BLOCKED,
                        job.getFullName(), job.getFullName(),
                        Jenkins.getAuthentication2().getName(),
                        "Blocked a remote run submission of job '" + job.getFullName()
                                + "' - the job requires an approved batch-control run request and "
                                + "a build token does not substitute for one - " + cause.getShortDescription());
                return false;
            }
        }

        // 4. User-originated (UI button, REST build endpoints, CLI): guide, never fail silently.
        for (Cause cause : causes) {
            if (cause instanceof Cause.UserIdCause) {
                throw new Failure(approvalRequiredMessage(job));
            }
        }

        // 5. Timer (cron): pass by default, blocked quietly per job setting.
        for (Cause cause : causes) {
            if (cause instanceof TimerTrigger.TimerTriggerCause) {
                if (property.isBlockTimer()) {
                    logRateLimited("timer", job, () -> "Blocked timer-triggered run of job '"
                            + job.getFullName() + "' (blockTimer=true)");
                    return false;
                }
                return true;
            }
        }

        // 6. Upstream (includes the Pipeline build step's BuildUpstreamCause subtype): D-16.
        for (Cause cause : causes) {
            if (cause instanceof Cause.UpstreamCause) {
                if (!property.isBlockUpstream()) {
                    return true;
                }
                String upstream = ((Cause.UpstreamCause) cause).getUpstreamProject();
                List<String> allowed = property.getAllowedUpstreamJobs();
                if (allowed.contains(upstream)) {
                    return true;
                }
                // D-16: with blockUpstream an empty/unset allow list blocks every upstream job.
                logRateLimited("upstream", job, () -> "Blocked upstream-triggered run of job '"
                        + job.getFullName()
                        + "' from '" + upstream + "' (blockUpstream=true, not on the allow list)");
                return false;
            }
        }

        // 7. SCM causes pass; unknown or empty cause sets pass with a log line.
        for (Cause cause : causes) {
            if (cause instanceof SCMTrigger.SCMTriggerCause) {
                return true;
            }
        }
        logRateLimited("unclassified", job, () -> "Letting job '"
                + job.getFullName()
                + "' pass the approval gate with unclassified causes: " + causes);
        return true;
    }

    /**
     * Logs at INFO at most once per {@link #INFO_INTERVAL_MILLIS} per kind and job, and at FINE
     * otherwise, so a frequent cron on a locked job cannot flood the log.
     */
    private static void logRateLimited(String kind, Job<?, ?> job, Supplier<String> message) {
        if (claimInfoSlot(kind + '|' + job.getFullName())) {
            LOGGER.info(() -> message.get() + RATE_LIMIT_SUFFIX);
        } else {
            LOGGER.log(Level.FINE, message);
        }
    }

    private static boolean claimInfoSlot(String key) {
        if (!LOGGER.isLoggable(Level.INFO)) {
            return false;
        }
        long now = BatchClock.now().toEpochMilli();
        if (LAST_INFO.size() >= MAX_RATE_LIMIT_ENTRIES) {
            LAST_INFO.clear();
        }
        boolean[] claimed = {false};
        LAST_INFO.compute(key, (k, last) -> {
            if (last == null || now - last >= INFO_INTERVAL_MILLIS) {
                claimed[0] = true;
                return now;
            }
            return last;
        });
        return claimed[0];
    }

    private static List<Cause> collectCauses(List<Action> actions) {
        List<Cause> causes = new ArrayList<>();
        for (Action action : actions) {
            if (action instanceof CauseAction) {
                causes.addAll(((CauseAction) action).getCauses());
            }
        }
        return causes;
    }

    /**
     * English guidance with the word "approval" and a link containing "batch-control", pointing
     * at the per-job request form (spec-review-S2 MINOR 2: that is where a request is created;
     * the global landing page only lists requests).
     */
    private static String approvalRequiredMessage(Job<?, ?> job) {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        String rootUrl = jenkins != null && jenkins.getRootUrl() != null ? jenkins.getRootUrl() : "/";
        return "Approval required: job '" + job.getFullName()
                + "' only runs through an approved batch-control run request. "
                + "Submit a run request at " + rootUrl + job.getUrl()
                + "batch-control/ and wait for approval.";
    }
}
