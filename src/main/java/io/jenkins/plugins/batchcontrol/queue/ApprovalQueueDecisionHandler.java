package io.jenkins.plugins.batchcontrol.queue;

import hudson.Extension;
import hudson.model.Action;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Failure;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.security.ACL;
import hudson.triggers.SCMTrigger;
import hudson.triggers.TimerTrigger;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.BlockedAttemptAudit;
import java.time.Duration;
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
import org.springframework.security.core.Authentication;

/**
 * The queue gate (SPEC item 6, D-03): every run path goes through
 * {@link Queue.QueueDecisionHandler}, so approval-required jobs are controlled at one point.
 *
 * <p>Decision order:
 * <ol>
 *   <li>run control off or task not a job → pass;</li>
 *   <li>job not approval-required: a human submission (user, CLI, Replay, approved request)
 *       passes; anything else continues at the timer step (D-46a);</li>
 *   <li>approval marker present → validate and consume it (D-23), pass or refuse quietly;</li>
 *   <li>Pipeline Replay → refuse quietly (the replay UI has no error channel for a Failure) and
 *       record it (#21);</li>
 *   <li>remote (build-token) cause → refuse quietly and record the attempt (S-14);</li>
 *   <li>user-originated causes (UserIdCause, incl. the CLI subtype) → throw
 *       {@link Failure} with guidance and a link to the request screen (no silent failure,
 *       PoC finding D-1);</li>
 *   <li>automatic retry → judged by the retried build's causes; a retry of an approved or
 *       manual run is refused quietly (#36);</li>
 *   <li>timer cause → pass unless {@code blockTimer}, refused quietly (unattended) and
 *       recorded (#21); what the setting lets through must also be activated (SPEC item 6a);</li>
 *   <li>upstream cause → D-16 policy: pass unless {@code blockUpstream}; with
 *       {@code blockUpstream} only allow-listed upstream jobs pass, an empty/unset list
 *       blocks all; refused quietly (unattended, the upstream build surfaces the failure) and
 *       recorded (#21); what the setting lets through must also be activated (SPEC item 6a);</li>
 *   <li>SCM causes and anything unknown/empty → pass only if activated (D-46b).</li>
 * </ol>
 *
 * <p>Steps from the timer step on apply to every job, with or without the job property and
 * whatever {@code approvalRequired} says; a computed child's activation is carried by its
 * computed-folder ancestor (D-46).
 *
 * <p>A quiet refusal of a timer, upstream, SCM, unclassified or Replay submission writes a
 * {@link ChangeType#TRIGGER_BLOCKED} record, coalesced per job and cause kind to one per
 * {@link #TRIGGER_AUDIT_INTERVAL} through {@link BlockedAttemptAudit}. The append is the only
 * store I/O on this path and it touches one file under that file's own lock stripe (#18), so it
 * never waits behind retention or any other bulk work.
 */
@Extension
@Restricted(NoExternalUse.class)
public class ApprovalQueueDecisionHandler extends Queue.QueueDecisionHandler {

    private static final Logger LOGGER =
            Logger.getLogger(ApprovalQueueDecisionHandler.class.getName());

    /** workflow-cps is an optional dependency; the replay cause is matched by name. */
    private static final String REPLAY_CAUSE_CLASS =
            "org.jenkinsci.plugins.workflow.cps.replay.ReplayCause";

    /** naginator is not a dependency; its retry cause is matched by name (#36). */
    private static final String NAGINATOR_CAUSE_CLASS = "com.chikli.hudson.plugin.naginator.NaginatorCause";

    /** An INFO line per job and kind is written at most this often; the rest go to FINE. */
    static final long INFO_INTERVAL_MILLIS = 60L * 60L * 1000L;

    /** At most one {@link ChangeType#TRIGGER_BLOCKED} record per job and cause kind this often (#21). */
    static final Duration TRIGGER_AUDIT_INTERVAL = Duration.ofHours(1);

    /** Cause kinds of a {@link ChangeType#TRIGGER_BLOCKED} record. */
    static final String KIND_TIMER = "TIMER";
    static final String KIND_UPSTREAM = "UPSTREAM";
    static final String KIND_REPLAY = "REPLAY";
    static final String KIND_SCM = "SCM";
    static final String KIND_OTHER = "OTHER";

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
        boolean approvalRequired = property != null && property.isApprovalRequired();
        List<Cause> causes = collectCauses(actions);
        List<Cause> effective = retryAwareCauses(causes);

        // 1. Approved submission: the marker authorizes exactly one queue entry (D-23), whether or
        // not the job requires approval (D-47): a re-use is refused and recorded on every job.
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

        if (approvalRequired) {

            // 2. Pipeline Replay: refused quietly (ReplayAction.run has no Failure channel).
            for (Cause cause : causes) {
                if (REPLAY_CAUSE_CLASS.equals(cause.getClass().getName())) {
                    logRateLimited("replay", job,
                            () -> "Blocked replay of approval-required job '" + job.getFullName() + "'");
                    recordTriggerBlocked(job, KIND_REPLAY, "approvalRequired",
                            "Blocked a Pipeline Replay of job '" + job.getFullName()
                                    + "' - the job requires an approved batch-control run request");
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

            // 5. Automatic retry (#36): judged by the causes of the build it retries. naginator copies
            // those next to its own cause, which is dropped here, so a retry of a timer or upstream run
            // meets the timer and upstream rules below. A retry of an approved or manual run is a
            // re-use of that run's approval and is refused quietly: the retry is unattended, and the
            // way to run the job again is a new request. A retry that presents the consumed marker
            // never gets here; step 1 refuses it and writes MARKER_REUSE_BLOCKED (D-30).
            for (Cause cause : effective) {
                if (cause instanceof ApprovedCause || cause instanceof Cause.UserIdCause) {
                    logRateLimited("reuse", job, () -> "Blocked a re-run of job '" + job.getFullName()
                            + "' that re-uses an earlier approved or manual run without a new approval: " + causes);
                    return false;
                }
            }
        } else if (isHumanSubmission(causes)) {
            // approvalRequired governs human-originated runs only (D-46a): on a job without it a
            // person may start the job, while unattended causes still need the activation below.
            // Judged on the submission's own causes (D-47): an automatic retry carries the retried
            // build's causes, but nobody is acting now, so it continues to the activation check.
            return true;
        }

        // D-46: from here on the cause is not a human submission, and the job needs its own
        // settings to let the cause through AND an approved activation, whatever approvalRequired
        // says and whether or not the job has the property at all.

        // 6. Timer (cron): blocked quietly per job setting, then activation.
        for (Cause cause : effective) {
            if (cause instanceof TimerTrigger.TimerTriggerCause) {
                if (property != null && property.isBlockTimer()) {
                    logRateLimited("timer", job, () -> "Blocked timer-triggered run of job '"
                            + job.getFullName() + "' (blockTimer=true)");
                    recordTriggerBlocked(job, KIND_TIMER, "blockTimer",
                            "Blocked a timer-triggered run of job '" + job.getFullName()
                                    + "' - clear blockTimer in the job configuration to let its schedule run");
                    return false;
                }
                // SPEC item 6a: clearing blockTimer never activates a job on its own.
                return activatedOrRefuse(job, KIND_TIMER, "a timer-triggered run");
            }
        }

        // 7. Upstream (includes the Pipeline build step's BuildUpstreamCause subtype): D-16, then activation.
        for (Cause cause : effective) {
            if (cause instanceof Cause.UpstreamCause) {
                String upstream = ((Cause.UpstreamCause) cause).getUpstreamProject();
                if (property == null || !property.isBlockUpstream()
                        || property.getAllowedUpstreamJobs().contains(upstream)) {
                    return activatedOrRefuse(job, KIND_UPSTREAM, "an upstream-triggered run from '" + upstream + "'");
                }
                // D-16: with blockUpstream an empty/unset allow list blocks every upstream job.
                logRateLimited("upstream", job, () -> "Blocked upstream-triggered run of job '"
                        + job.getFullName()
                        + "' from '" + upstream + "' (blockUpstream=true, not on the allow list)");
                recordTriggerBlocked(job, KIND_UPSTREAM, "blockUpstream",
                        "Blocked an upstream-triggered run of job '" + job.getFullName() + "' from '"
                                + upstream + "' - clear blockUpstream or add the upstream job to the"
                                + " allow list in the job configuration");
                return false;
            }
        }

        // 8. SCM (polling, push hooks) and every other unattended or unclassified cause: activation
        // only (D-46b); there is no job switch for them.
        for (Cause cause : effective) {
            if (cause instanceof SCMTrigger.SCMTriggerCause) {
                return activatedOrRefuse(job, KIND_SCM, "an SCM-triggered run");
            }
        }
        return activatedOrRefuse(job, KIND_OTHER, "an unattended run (" + describe(causes) + ")");
    }

    /**
     * Whether a person started this submission (D-46b, D-47, security-15 S-15-01): the UI, REST or
     * CLI build ({@code UserIdCause} and its CLI subtype, the deprecated {@code UserCause}) or a
     * Pipeline Replay, submitted by an authenticated user who is not SYSTEM, and for a user cause
     * naming a real user id (not {@code null}, SYSTEM or anonymous). A Rebuild click carries the
     * clicking user's {@code UserIdCause} and so counts as a person. Code running as SYSTEM (a
     * script, a CLI or Replay call made as SYSTEM, a {@code UserIdCause} built under SYSTEM, which
     * carries no user id) is unattended. An approved request's cause counts only through its
     * marker, which is consumed before this is asked. A build token ({@code RemoteCause}) and an
     * automatic retry (D-47), whatever causes it copied from the build it retries, are unattended.
     */
    @SuppressWarnings("deprecation")
    private static boolean isHumanSubmission(List<Cause> causes) {
        if (isAutomaticRetry(causes)) {
            // D-47: the person in a retry's cause list acted on the retried build, not on this one.
            return false;
        }
        Authentication submitter = Jenkins.getAuthentication2();
        if (ACL.SYSTEM2.equals(submitter) || ACL.isAnonymous2(submitter)) {
            return false;
        }
        for (Cause cause : causes) {
            if (cause instanceof Cause.UserIdCause) {
                if (isRealUser(((Cause.UserIdCause) cause).getUserId())) {
                    return true;
                }
            } else if (cause instanceof Cause.UserCause) {
                if (isRealUser(((Cause.UserCause) cause).getUserName())) {
                    return true;
                }
            } else if (REPLAY_CAUSE_CLASS.equals(cause.getClass().getName())) {
                return true;
            }
        }
        return false;
    }

    /** A user id that names a person: not {@code null}/blank, SYSTEM or anonymous (S-15-01). */
    private static boolean isRealUser(String id) {
        return id != null && !id.isBlank() && !ACL.SYSTEM_USERNAME.equals(id)
                && !ACL.ANONYMOUS_USERNAME.equals(id) && !"anonymous".equals(id);
    }

    /** Whether the submission is an automatic retry: a cause {@link #retryAwareCauses} strips. */
    private static boolean isAutomaticRetry(List<Cause> causes) {
        return retryAwareCauses(causes).size() != causes.size();
    }

    private static String describe(List<Cause> causes) {
        if (causes.isEmpty()) {
            return "no cause";
        }
        List<String> names = new ArrayList<>();
        for (Cause cause : causes) {
            names.add(cause.getClass().getSimpleName());
        }
        return String.join(" ", names);
    }

    /**
     * The activation input of the unattended gate (SPEC item 6a, D-39, D-46): a cause that the
     * job's own settings let through passes only if the item carrying the job's activation is
     * activated (the job itself, or for a computed child its computed-folder ancestor). Activation
     * lives in the plugin store, outside the job configuration, so no configuration write can
     * substitute for it. A refusal is quiet and recorded like the other unattended refusals, with
     * {@code switch=activation}.
     */
    private static boolean activatedOrRefuse(Job<?, ?> job, String kind, String what) {
        if (ActivationService.get().mayRunUnattended(job)) {
            return true;
        }
        String carrier = ActivationService.activationSubject(job).getFullName();
        String notActivated = carrier.equals(job.getFullName())
                ? "the job is not activated" : "its folder '" + carrier + "' is not activated";
        logRateLimited("activation-" + kind, job, () -> "Blocked " + what + " of job '" + job.getFullName()
                + "' (" + notActivated + ")");
        recordTriggerBlocked(job, kind, "activation", "Blocked " + what + " of job '" + job.getFullName()
                + "' - " + notActivated + "; an approved activation request puts it into service");
        return false;
    }

    /**
     * Writes the coalesced {@link ChangeType#TRIGGER_BLOCKED} record of a quiet refusal (#21). The
     * detail starts with the cause kind and the blocking switch so the history and
     * {@code changes.csv} can be filtered on them. A store failure is logged and does not turn the
     * refusal into an exception: the queue gate must keep refusing even when the audit write
     * fails, and the refusal is already in the controller log.
     */
    private static void recordTriggerBlocked(Job<?, ?> job, String kind, String blockingSwitch, String text) {
        String fullName = job.getFullName();
        try {
            BlockedAttemptAudit.get().recordCoalesced(ChangeType.TRIGGER_BLOCKED,
                    fullName + '|' + kind, TRIGGER_AUDIT_INTERVAL, fullName,
                    Jenkins.getAuthentication2().getName(),
                    "cause=" + kind + " switch=" + blockingSwitch + ": " + text
                            + " (repeats within an hour are merged into this record)");
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, e, () -> "Could not record the blocked " + kind
                    + " submission of job '" + fullName + "'");
        }
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

    /**
     * The causes a submission is judged by: a naginator retry cause is dropped, which leaves the
     * retried build's causes that naginator copies into every retry (#36). naginator is not a
     * dependency, so its cause is matched by class name. An upstream cause naming the job itself
     * is not a retry: a job may legitimately trigger itself, so it keeps the upstream policy
     * (D-16, security-07 S-01).
     */
    private static List<Cause> retryAwareCauses(List<Cause> causes) {
        List<Cause> effective = new ArrayList<>(causes.size());
        for (Cause cause : causes) {
            if (!NAGINATOR_CAUSE_CLASS.equals(cause.getClass().getName())) {
                effective.add(cause);
            }
        }
        return effective;
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
