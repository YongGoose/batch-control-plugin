package io.jenkins.plugins.batchcontrol.queue;

import hudson.Extension;
import hudson.cli.CLICommand;
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
import io.jenkins.plugins.batchcontrol.ui.ApprovalRequiredFailure;
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
import org.kohsuke.stapler.Ancestor;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;

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
 *   <li>Pipeline Replay → refuse and record it (#21); a person on the Replay page gets the
 *       refusal page and the CLI a one-line error (e2e-03 DEF-16, DEF-14), anything else is
 *       refused quietly;</li>
 *   <li>remote (build-token) cause → refuse and record the attempt (S-14); inside an HTTP
 *       request the caller gets a plain-text 403 (DEF-33/34), elsewhere the refusal is quiet;</li>
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
    static final String KIND_RETRY = "RETRY";
    static final String KIND_REBUILD = "REBUILD";

    /** build-token-root's root action; not a dependency, matched by name (S-18-03). */
    private static final String BUILD_TOKEN_ROOT_ACTION_CLASS =
            "org.jenkinsci.plugins.build_token_root.BuildRootAction";

    /** The CLI {@code build} command's cause (a {@code UserIdCause} subtype), matched by name. */
    private static final String CLI_CAUSE_CLASS = "hudson.cli.BuildCommand$CLICause";

    /** The rebuild plugin is not a dependency; its cause is matched by name (e2e-03 DEF-03). */
    private static final String REBUILD_CAUSE_CLASS = "com.sonyericsson.rebuild.RebuildCause";

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

            // 2. Pipeline Replay: refused and recorded; explained only to a person (see below).
            // S-22-05: a Replay only when the submission's own re-run cause is the Replay (a Rebuild
            // of a replayed build carries a copied ReplayCause and is judged as a Rebuild).
            for (Cause cause : KIND_REPLAY.equals(lastRerunKind(causes)) ? causes : List.<Cause>of()) {
                if (REPLAY_CAUSE_CLASS.equals(cause.getClass().getName())) {
                    logRateLimited("replay", job,
                            () -> "Blocked replay of approval-required job '" + job.getFullName() + "'");
                    // D-51a (S-21-09): a person's Replay (on the Replay page or through the CLI) is
                    // recorded per attempt, naming the replayed build; anything else is unattended.
                    boolean person = !ACL.SYSTEM2.equals(Jenkins.getAuthentication2())
                            && (CLICommand.getCurrent() != null
                                    || (Stapler.getCurrentRequest2() != null && isHumanSubmission(causes)));
                    if (person) {
                        String user = Jenkins.getAuthentication2().getName();
                        String source = sourceBuild(causes);
                        recordPersonRefusal(job, KIND_REPLAY, source, user, "Blocked a Pipeline Replay of job '"
                                + job.getFullName() + "'" + (source.isEmpty() ? "" : " build #" + source) + " by '"
                                + user + "' - the job requires an approved batch-control run request");
                    } else {
                        recordTriggerBlocked(job, KIND_REPLAY, "approvalRequired",
                                "Blocked a Pipeline Replay of job '" + job.getFullName()
                                        + "' - the job requires an approved batch-control run request");
                    }
                    // e2e-03 DEF-16: a person pressing Run on the Replay page gets the refusal
                    // page instead of the replay action's generic "not buildable" crash page;
                    // the CLI gets a one-line error (DEF-14). Anything else stays quiet.
                    if (CLICommand.getCurrent() != null) {
                        throw new IllegalStateException(replayRefusedMessage(job));
                    }
                    if (Stapler.getCurrentRequest2() != null && isHumanSubmission(causes)) {
                        throw new ApprovalRequiredFailure(job, replayRefusedMessage(job));
                    }
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
            // The attempt is written to the audit history — the same reasoning D-30 applies to a
            // blocked marker re-use. The record is bounded (S-21): see BlockedAttemptAudit. Inside an
            // HTTP request the script caller also gets a plain-text 403 saying what to do (e2e
            // re-audit DEF-33/34): otherwise core answers its "scheduled" 302 and build-token-root
            // an empty 403. Without a current request (queue maintenance, Groovy, other plugins'
            // background threads) the refusal stays a quiet false, so nothing is thrown there.
            // S-18-03: a Retry a person clicks copies the retried build's causes, a RemoteCause
            // included, next to that person's fresh UserIdCause. It is that person's submission
            // (D-47, DEF-32), so it skips this step and is refused and recorded at step 4.
            // S-19-01: naginator strips copied UserIdCauses from a Retry, so a UserIdCause present is
            // the clicking person's own; without one the submission stays on the step-3 path.
            boolean userClickedRetry = isAutomaticRetry(causes) && isUserClickedRetry()
                    && hasUserIdCause(causes);
            for (Cause cause : userClickedRetry ? List.<Cause>of() : causes) {
                if (cause instanceof Cause.RemoteCause) {
                    LOGGER.warning(() -> "Blocked a remote (build-token) run of approval-required job '"
                            + job.getFullName() + "': " + cause.getShortDescription());
                    try {
                        BlockedAttemptAudit.get().record(ChangeType.REMOTE_RUN_BLOCKED,
                                job.getFullName(), job.getFullName(),
                                Jenkins.getAuthentication2().getName(),
                                "Blocked a remote run submission of job '" + job.getFullName()
                                        + "' - the job requires an approved batch-control run request and "
                                        + "a build token does not substitute for one - " + cause.getShortDescription());
                    } catch (RuntimeException e) {
                        // As in recordTriggerBlocked: a store failure never turns the refusal into
                        // an exception on a non-request thread; the refusal is already logged above.
                        LOGGER.log(Level.WARNING, e, () -> "Could not record the blocked remote run of job '"
                                + job.getFullName() + "'");
                    }
                    if (isTokenBuildEndpoint(job)) {
                        throw new RemoteRunRefusal(remoteRefusedMessage(job));
                    }
                    return false;
                }
            }

            // 4. User-originated (UI button, REST build endpoints, CLI): guide, never fail silently.
            // A refused Rebuild is also recorded (SPEC item 6, e2e-03 DEF-03): it re-runs an earlier
            // build without a new approval, and the plugin's own toast hides the guidance.
            // D-47: whether a person acts is judged on this submission. An automatic retry that
            // copied the retried build's UserIdCause is unattended and continues at step 5; only a
            // Retry a person clicks (NaginatorCause plus that person's fresh UserIdCause, inside
            // their HTTP request) is handled here.
            boolean retry = isAutomaticRetry(causes);
            if (!retry || userClickedRetry) {
                for (Cause cause : causes) {
                    if (cause instanceof Cause.UserIdCause) {
                        // S-22-05: the submission's own re-run cause is the last one; earlier ones
                        // were copied from the build it repeats.
                        String own = lastRerunKind(causes);
                        String rerun = KIND_RETRY.equals(own) || KIND_REBUILD.equals(own) ? own : null;
                        if (rerun != null) {
                            String user = Jenkins.getAuthentication2().getName();
                            String what = KIND_RETRY.equals(rerun) ? "a Retry" : "a Rebuild";
                            String source = sourceBuild(causes);
                            // e2e re-audit DEF-32: recorded under the person's name and never merged
                            // into the SYSTEM record of automatic retries, another person's record,
                            // or the same person's earlier refusal on another build.
                            recordPersonRefusal(job, rerun, source, user,
                                    "Blocked " + what + " of job '" + job.getFullName() + "'"
                                            + (source.isEmpty() ? "" : " build #" + source) + " by '" + user
                                            + "' - a re-run does not reuse an earlier approval; submit a new run request");
                        }
                        throw refusal(job, causes);
                    }
                }
            }

            // 5. Automatic retry (#36): judged by the causes of the build it retries. naginator copies
            // those next to its own cause, which is dropped here, so a retry of a timer or upstream run
            // meets the timer and upstream rules below. A retry of an approved or manual run is a
            // re-use of that run's approval and is refused quietly: the retry is unattended, and the
            // way to run the job again is a new request. A retry that presents the consumed marker
            // never gets here; step 1 refuses it and writes MARKER_REUSE_BLOCKED (D-30).
            for (Cause cause : effective) {
                // S-23-07: a copied ReplayCause also marks a person's run (a Replay is always manual).
                if (cause instanceof ApprovedCause || cause instanceof Cause.UserIdCause
                        || REPLAY_CAUSE_CLASS.equals(cause.getClass().getName())) {
                    logRateLimited("reuse", job, () -> "Blocked a re-run of job '" + job.getFullName()
                            + "' that re-uses an earlier approved or manual run without a new approval: " + causes);
                    // Recorded, not only logged (SPEC item 6, e2e-03 DEF-03).
                    String own = lastRerunKind(causes);
                    String kind = KIND_RETRY.equals(own) || KIND_REBUILD.equals(own) ? own : KIND_OTHER;
                    String what = KIND_RETRY.equals(kind) ? "a retry" : KIND_REBUILD.equals(kind) ? "a Rebuild" : "a re-run";
                    recordTriggerBlocked(job, kind, "approvalRequired", "Blocked " + what + " of job '"
                            + job.getFullName() + "' that re-uses an earlier "
                            + (cause instanceof ApprovedCause ? "approved" : "manual")
                            + " run without a new approval - submit a new run request");
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
     * Whether a person started this submission (D-46b, D-47, security-15 S-15-01): a user-type
     * cause ({@code UserIdCause} and its CLI subtype, the deprecated {@code UserCause}, a Rebuild
     * click, which carries the clicking user's {@code UserIdCause}, or a Pipeline Replay) submitted
     * by anyone but SYSTEM. An anonymous user with Item/Build pressing Build Now is a person acting.
     * Code running as SYSTEM (a script, a CLI or Replay call made as SYSTEM, a {@code UserIdCause}
     * built under SYSTEM) is unattended. An approved request's cause counts only through its marker,
     * which is consumed before this is asked. A build token ({@code RemoteCause}) and an automatic
     * retry (D-47), whatever causes it copied from the build it retries, are unattended.
     */
    @SuppressWarnings("deprecation")
    private static boolean isHumanSubmission(List<Cause> causes) {
        if (isAutomaticRetry(causes)) {
            // D-47: the person in a retry's cause list acted on the retried build, not on this one.
            return false;
        }
        if (ACL.SYSTEM2.equals(Jenkins.getAuthentication2())) {
            return false;
        }
        for (Cause cause : causes) {
            if (cause instanceof Cause.UserIdCause || cause instanceof Cause.UserCause
                    || REPLAY_CAUSE_CLASS.equals(cause.getClass().getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasUserIdCause(List<Cause> causes) {
        for (Cause cause : causes) {
            if (cause instanceof Cause.UserIdCause) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCause(List<Cause> causes, String className) {
        for (Cause cause : causes) {
            if (className.equals(cause.getClass().getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a retry submission was made by a person (D-47, DEF-32): naginator's Retry link is
     * served inside the clicking user's HTTP request, while its automatic retry is scheduled from
     * a run listener with no current request.
     */
    private static boolean isUserClickedRetry() {
        return Stapler.getCurrentRequest2() != null && !ACL.SYSTEM2.equals(Jenkins.getAuthentication2());
    }

    /**
     * Whether the current HTTP request is one of the build-token endpoints (S-18-03): core's
     * {@code build} or {@code buildWithParameters} web method of {@code job} itself, or
     * build-token-root's action (matched by class name; it is not a dependency). Only those callers
     * get the plain-text refusal; any other code scheduling with a {@code RemoteCause} on a request
     * thread keeps the quiet {@code false} it always got.
     */
    private static boolean isTokenBuildEndpoint(Job<?, ?> job) {
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        if (req == null) {
            return false;
        }
        List<Ancestor> ancestors = req.getAncestors();
        if (ancestors.isEmpty()) {
            return false;
        }
        Object last = ancestors.get(ancestors.size() - 1).getObject();
        if (last != null && BUILD_TOKEN_ROOT_ACTION_CLASS.equals(last.getClass().getName())) {
            return true;
        }
        if (last != job) {
            return false;
        }
        String path = req.getRequestURI();
        if (path == null) {
            return false;
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        String lastToken = path.substring(path.lastIndexOf('/') + 1);
        return "build".equals(lastToken) || "buildWithParameters".equals(lastToken);
    }

    /**
     * The kind of the submission's own re-run cause (S-22-05): the last naginator, Rebuild or
     * Replay cause in the list, since both naginator and Rebuild copy the repeated build's causes
     * before adding their own; {@code null} when there is none.
     */
    private static String lastRerunKind(List<Cause> causes) {
        for (int i = causes.size() - 1; i >= 0; i--) {
            String name = causes.get(i).getClass().getName();
            if (NAGINATOR_CAUSE_CLASS.equals(name)) {
                return KIND_RETRY;
            }
            if (REBUILD_CAUSE_CLASS.equals(name)) {
                return KIND_REBUILD;
            }
            if (REPLAY_CAUSE_CLASS.equals(name)) {
                return KIND_REPLAY;
            }
        }
        return null;
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
        hudson.model.Item subject = ActivationService.activationSubject(job);
        String carrier = subject.getFullName();
        String notActivated = carrier.equals(job.getFullName())
                ? "the job is not activated" : "its folder '" + carrier + "' is not activated";
        logRateLimited("activation-" + kind, job, () -> "Blocked " + what + " of job '" + job.getFullName()
                + "' (" + notActivated + ")");
        // e2e-04 FD-07: a refusal after a HOLD is not merged into a record written before the job
        // was activated: the time the activation last ended is part of the coalescing key.
        io.jenkins.plugins.batchcontrol.model.ActivationState state = ActivationService.get().getState(subject);
        String epoch = state == null || state.getDeactivatedAt() == null ? "never-activated"
                : "held-" + state.getDeactivatedAt().toEpochMilli();
        recordTriggerBlocked(job, kind, "activation", "Blocked " + what + " of job '" + job.getFullName()
                + "' - " + notActivated + "; an approved activation request puts it into service", epoch);
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
        recordTriggerBlocked(job, kind, blockingSwitch, text, "");
    }

    /**
     * As {@link #recordTriggerBlocked(Job, String, String, String)}. The coalescing key is the job,
     * the cause kind, the blocking switch and {@code epoch} (e2e-04 FD-07): a refusal for another
     * reason, or after the state behind the switch changed, gets its own record.
     */
    private static void recordTriggerBlocked(Job<?, ?> job, String kind, String blockingSwitch, String text,
                                             String epoch) {
        String fullName = job.getFullName();
        String key = fullName + '|' + kind + '|' + blockingSwitch + (epoch.isEmpty() ? "" : '|' + epoch);
        try {
            BlockedAttemptAudit.get().recordCoalesced(ChangeType.TRIGGER_BLOCKED,
                    key, TRIGGER_AUDIT_INTERVAL, fullName,
                    Jenkins.getAuthentication2().getName(),
                    "cause=" + kind + " switch=" + blockingSwitch + ": " + text
                            + " (repeats within an hour are merged into this record)");
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, e, () -> "Could not record the blocked " + kind
                    + " submission of job '" + fullName + "'");
        }
    }

    /**
     * Writes the {@link ChangeType#TRIGGER_BLOCKED} record of a re-run a person submitted and was
     * refused (e2e-03 DEF-32). Unlike the unattended refusals it is not merged per hour: a person
     * acts rarely, and in the container a second user's Retry, of a later build, within the hour
     * vanished into the first one's record. Only a repeat of the same attempt by the same user
     * within {@link BlockedAttemptAudit}'s short cooldown is merged, so a double click stays one
     * record. Per user the records are budgeted (D-51a, {@link BlockedAttemptAudit#recordPersonRefusal}).
     * A store failure is logged, like {@link #recordTriggerBlocked}.
     */
    private static void recordPersonRefusal(Job<?, ?> job, String kind, String sourceBuild, String user,
                                            String text) {
        String fullName = job.getFullName();
        try {
            BlockedAttemptAudit.get().recordPersonRefusal(ChangeType.TRIGGER_BLOCKED,
                    fullName + '|' + kind + '#' + sourceBuild, fullName, user, sourceBuild,
                    "cause=" + kind + " switch=approvalRequired: " + text);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, e, () -> "Could not record the blocked " + kind
                    + " submission of job '" + fullName + "' by '" + user + "'");
        }
    }

    /**
     * The number of the build a person's re-run repeats (D-51a, S-21-09), or {@code ""} when
     * unknown: naginator's {@code NaginatorCause#getSourceBuildNumber}, Pipeline's
     * {@code ReplayCause#getOriginalNumber} (both read reflectively: neither plugin is a
     * dependency), or for a Rebuild its {@code RebuildCause}, an {@link Cause.UpstreamCause} naming
     * the rebuilt build.
     */
    private static String sourceBuild(List<Cause> causes) {
        // S-22-05: from the last re-run cause, the submission's own; earlier ones are inherited.
        for (int i = causes.size() - 1; i >= 0; i--) {
            Cause cause = causes.get(i);
            String name = cause.getClass().getName();
            String getter = NAGINATOR_CAUSE_CLASS.equals(name) ? "getSourceBuildNumber"
                    : REPLAY_CAUSE_CLASS.equals(name) ? "getOriginalNumber" : null;
            if (getter != null) {
                try {
                    Object number = cause.getClass().getMethod(getter).invoke(cause);
                    return number == null ? "" : number.toString();
                } catch (ReflectiveOperationException | RuntimeException e) {
                    LOGGER.log(Level.FINE, "Cannot read the re-run build number", e);
                    return "";
                }
            }
            if (REBUILD_CAUSE_CLASS.equals(name) && cause instanceof Cause.UpstreamCause) {
                return Integer.toString(((Cause.UpstreamCause) cause).getUpstreamBuild());
            }
        }
        return "";
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
     * The refusal of a user-originated run (e2e-03 DEF-02). Inside an HTTP request it is the
     * {@link ApprovalRequiredFailure} page, which links the request form only for users who may
     * open it; for the CLI an {@link IllegalStateException} (e2e-03 DEF-14); elsewhere (scripts)
     * the plain {@link Failure} with the same message as before.
     */
    private static RuntimeException refusal(Job<?, ?> job, List<Cause> causes) {
        String message = approvalRequiredMessage(job);
        // e2e-03 DEF-14: the CLI prints an IllegalStateException as one "ERROR: <message>" line
        // with exit code 4; any other exception type is reported as an unexpected failure with a
        // server stack trace. A CLI build carries CLICause; remote transports also set the
        // current command.
        if (CLICommand.getCurrent() != null || hasCause(causes, CLI_CAUSE_CLASS)) {
            return new IllegalStateException(message);
        }
        return Stapler.getCurrentRequest2() != null
                ? new ApprovalRequiredFailure(job, message) : new Failure(message);
    }

    /** The plain-text refusal of a build-token submission (e2e re-audit DEF-33/34). */
    private static String remoteRefusedMessage(Job<?, ?> job) {
        return "Not scheduled: job '" + job.getFullName() + "' requires an approved batch-control run "
                + "request, and a build token does not substitute for one. " + requestHint(job);
    }

    /** The refusal of a Pipeline Replay a person submitted (e2e-03 DEF-16). */
    private static String replayRefusedMessage(Job<?, ?> job) {
        return "Replay is not available for job '" + job.getFullName() + "': it only runs through an "
                + "approved batch-control run request, and a replay would run changed code without one. "
                + requestHint(job);
    }

    /**
     * English guidance with the word "approval" and a link containing "batch-control", pointing
     * at the per-job request form (spec-review-S2 MINOR 2: that is where a request is created;
     * the global landing page only lists requests).
     */
    private static String approvalRequiredMessage(Job<?, ?> job) {
        return "Approval required: job '" + job.getFullName()
                + "' only runs through an approved batch-control run request. " + requestHint(job);
    }

    /** Where a run request for {@code job} is submitted. */
    private static String requestHint(Job<?, ?> job) {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        String rootUrl = jenkins != null && jenkins.getRootUrl() != null ? jenkins.getRootUrl() : "/";
        return "Submit a run request at " + rootUrl + job.getUrl()
                + "batch-control/ and wait for approval.";
    }
}
