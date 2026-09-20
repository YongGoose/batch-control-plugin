package io.jenkins.plugins.batchcontrol.policy;

import hudson.model.Action;
import hudson.model.CauseAction;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.SimpleParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.queue.ApprovedCause;
import io.jenkins.plugins.batchcontrol.queue.ApprovedRunAction;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import io.jenkins.plugins.batchcontrol.store.Store;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.springframework.security.access.AccessDeniedException;

/**
 * The single entry point for every {@link RunRequest} state transition (SPEC items 3, 5, 7;
 * D-20/D-21/D-22/D-23). No other class may change a request's status.
 *
 * <p><b>Concurrency (D-20)</b>: every load→validate→transition→persist sequence runs under one
 * {@link ReentrantLock}, which makes each transition an effective compare-and-set: concurrent
 * approve/approve or approve/cancel calls serialize, the loser sees the already-changed status
 * and is refused. The lock is never held across {@link Queue} operations (queue submission and
 * queue inspection happen outside it) to avoid lock-order inversion with the queue gate, which
 * calls back into {@link #consumeMarker} while Jenkins holds the queue lock.
 *
 * <p><b>Failure families</b>: {@link IllegalArgumentException} for input validation,
 * {@link IllegalStateException} for wrong-state transitions, {@link AccessDeniedException}
 * (403 on the web layer) for authorization refusals.
 */
@Restricted(NoExternalUse.class)
public final class RunRequestService {

    private static final Logger LOGGER = Logger.getLogger(RunRequestService.class.getName());

    /** D-22 size limits: reason and per-parameter value. */
    private static final int MAX_REASON_LENGTH = 4000;
    private static final int MAX_PARAMETER_VALUE_LENGTH = 10000;

    private static final RunRequestService INSTANCE = new RunRequestService();

    private final ReentrantLock lock = new ReentrantLock();
    private final Store store = FileStore.get();

    private RunRequestService() {
    }

    public static RunRequestService get() {
        return INSTANCE;
    }

    // ---------------------------------------------------------------- read API

    /** Loads a request by id, or {@code null}. */
    public RunRequest load(String id) {
        return store.loadRunRequest(id);
    }

    /** All stored requests, in creation order. */
    public List<RunRequest> list() {
        return store.listRunRequests();
    }

    // ---------------------------------------------------------------- creation (SPEC 5, D-22)

    /**
     * Creates a PENDING run request for the given job. The requester is the current
     * authentication; validation failures throw {@link IllegalArgumentException}.
     */
    public RunRequest create(Job<?, ?> job, Map<String, String> parameters, String reason,
                             String approver) {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(parameters, "parameters");
        Jenkins.get().checkPermission(BatchControlPermissions.REQUEST);
        String requester = Jenkins.getAuthentication2().getName();

        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("A reason is required to create a run request.");
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("The reason must not exceed "
                    + MAX_REASON_LENGTH + " characters.");
        }
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            String value = entry.getValue();
            if (value != null && value.length() > MAX_PARAMETER_VALUE_LENGTH) {
                throw new IllegalArgumentException("Parameter '" + entry.getKey()
                        + "' exceeds " + MAX_PARAMETER_VALUE_LENGTH + " characters.");
            }
        }
        ApprovalPolicy.checkDesignation(requester, approver, job);

        RunRequest request = RunRequest.create(job.getFullName(), parameters, reason,
                requester, approver);
        lock.lock();
        try {
            store.saveRunRequest(request);
        } finally {
            lock.unlock();
        }
        return request;
    }

    // ---------------------------------------------------------------- decisions (SPEC 3, 5)

    /**
     * Approves a PENDING request and submits the build. The caller must be the designated
     * approver holding the Approve permission (or the admin self-approval path). The status is
     * committed to APPROVED before submission and is never rolled back by a submission failure
     * (quiet-down tolerance); startup recovery and expiry handle stragglers.
     */
    public RunRequest approve(String id, String comment) {
        RunRequest request;
        lock.lock();
        try {
            request = require(id);
            if (request.getStatus() != RequestStatus.PENDING) {
                throw new IllegalStateException("Request " + id + " is "
                        + request.getStatus() + " and can no longer be approved.");
            }
            boolean selfApproval = ApprovalPolicy.checkDecision(request);
            Instant now = BatchClock.now();
            // D-20 check-at-submit: a request whose pending timeout has already passed is
            // expired here instead of being approved, so it can never be submitted.
            if (pendingExpired(request, now)) {
                request.setStatus(RequestStatus.EXPIRED);
                store.saveRunRequest(request);
                throw new IllegalStateException("Request " + id
                        + " passed its pending timeout and is now EXPIRED.");
            }
            request.setStatus(RequestStatus.APPROVED);
            request.setDecidedAt(now);
            request.setDecisionComment(comment);
            request.setSelfApproved(selfApproval);
            request.setExpiryBase(now);
            store.saveRunRequest(request);
        } finally {
            lock.unlock();
        }
        // Submission happens outside the lock; the queue gate claims the consumption ticket.
        submitApproved(request);
        RunRequest reloaded = load(id);
        return reloaded != null ? reloaded : request;
    }

    /** Rejects a PENDING request; the comment is mandatory (SPEC 5). */
    public RunRequest reject(String id, String comment) {
        if (comment == null || comment.trim().isEmpty()) {
            throw new IllegalArgumentException("A comment is required to reject a request.");
        }
        lock.lock();
        try {
            RunRequest request = require(id);
            if (request.getStatus() != RequestStatus.PENDING) {
                throw new IllegalStateException("Request " + id + " is "
                        + request.getStatus() + " and can no longer be rejected.");
            }
            ApprovalPolicy.checkDecision(request);
            request.setStatus(RequestStatus.REJECTED);
            request.setDecidedAt(BatchClock.now());
            request.setDecisionComment(comment);
            store.saveRunRequest(request);
            return request;
        } finally {
            lock.unlock();
        }
    }

    /** Cancels a PENDING request; requester or a Manage holder only (SPEC 7). */
    public RunRequest cancel(String id) {
        String caller = Jenkins.getAuthentication2().getName();
        lock.lock();
        try {
            RunRequest request = require(id);
            if (!caller.equals(request.getRequester())
                    && !Jenkins.get().hasPermission(BatchControlPermissions.MANAGE)) {
                throw new AccessDeniedException(
                        "Only the requester or a Manage holder may cancel request " + id + ".");
            }
            if (request.getStatus() != RequestStatus.PENDING) {
                throw new IllegalStateException("Request " + id + " is "
                        + request.getStatus() + "; only PENDING requests can be cancelled.");
            }
            request.setStatus(RequestStatus.CANCELLED);
            store.saveRunRequest(request);
            return request;
        } finally {
            lock.unlock();
        }
    }

    /** Changes the designated approver of a PENDING request; requester only (SPEC 3). */
    public RunRequest changeApprover(String id, String newApprover) {
        String caller = Jenkins.getAuthentication2().getName();
        lock.lock();
        try {
            RunRequest request = require(id);
            if (!caller.equals(request.getRequester())) {
                throw new AccessDeniedException(
                        "Only the requester may change the approver of request " + id + ".");
            }
            if (request.getStatus() != RequestStatus.PENDING) {
                throw new IllegalStateException("Request " + id + " is "
                        + request.getStatus() + "; the approver can only be changed while PENDING.");
            }
            Job<?, ?> job = Jenkins.get().getItemByFullName(request.getJobFullName(), Job.class);
            ApprovalPolicy.checkDesignation(request.getRequester(), newApprover, job);
            String previous = request.getApprover();
            request.addApproverChange(new RunRequest.ApproverChange(
                    previous, newApprover, caller, BatchClock.now()));
            request.setApprover(newApprover);
            store.saveRunRequest(request);
            return request;
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------- queue gate integration

    /**
     * Atomically claims the single consumption ticket of an approved request (D-23), including
     * the D-20 check-at-submit expiry re-check. Called by the queue gate while Jenkins holds
     * the queue lock; must therefore never be called while holding this service's lock on
     * another thread path that also takes the queue lock.
     *
     * @return {@code true} if the submission is authorized (ticket claimed just now)
     */
    public boolean consumeMarker(String requestId, String jobFullName) {
        lock.lock();
        try {
            RunRequest request = store.loadRunRequest(requestId);
            if (request == null) {
                LOGGER.warning(() -> "Refusing approval marker for unknown request " + requestId);
                return false;
            }
            if (request.getStatus() != RequestStatus.APPROVED) {
                LOGGER.warning(() -> "Refusing approval marker for request " + requestId
                        + " in status " + request.getStatus());
                return false;
            }
            if (request.getQueuedAt() != null || request.getExecutedRunId() != null) {
                LOGGER.warning(() -> "Refusing re-use of the already consumed approval marker of request "
                        + requestId + " (D-23 single consumption)");
                return false;
            }
            if (!request.getJobFullName().equals(jobFullName)) {
                LOGGER.warning(() -> "Refusing approval marker of request " + requestId
                        + " (bound to job '" + request.getJobFullName() + "') on job '" + jobFullName + "'");
                return false;
            }
            Instant now = BatchClock.now();
            if (approvedExpired(request, now)) {
                // D-20 check-at-submit: an expired approval is never submitted.
                request.setStatus(RequestStatus.EXPIRED);
                store.saveRunRequest(request);
                LOGGER.warning(() -> "Refusing approval marker of request " + requestId
                        + ": the approved-run timeout passed before submission (now EXPIRED)");
                return false;
            }
            request.setQueuedAt(now);
            store.saveRunRequest(request);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Marks an APPROVED request as EXECUTED once its build has started (SPEC section 4). */
    public void markExecuted(String requestId, String runId) {
        lock.lock();
        try {
            RunRequest request = store.loadRunRequest(requestId);
            if (request == null) {
                return;
            }
            if (request.getStatus() == RequestStatus.APPROVED) {
                request.setStatus(RequestStatus.EXECUTED);
                request.setExecutedRunId(runId);
                store.saveRunRequest(request);
            }
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------- expiry (SPEC 7)

    /**
     * Expires overdue requests: PENDING past {@code pendingTimeoutHours} and APPROVED,
     * never-executed requests past {@code approvedRunTimeoutMinutes} whose submission is not
     * sitting in the queue right now.
     *
     * <p>Boundary-window hardening (spec-review-S2 MINOR 1): the queue snapshot is taken
     * outside this service's lock (lock-order discipline with the queue gate), so a marker
     * consumed between the snapshot and the per-request lock acquisition is missing from the
     * snapshot even though the request WAS submitted in time. The consumption ticket
     * ({@code queuedAt}), re-read here under the lock, closes that window: a ticket claimed at
     * or after the snapshot instant proves the snapshot is stale for this request, so it is
     * skipped this cycle (the next cycle sees it in the queue, executed, or genuinely gone).
     *
     * @param queuedRequestIds ids of requests that currently have a queue item carrying their
     *        approval marker (collected by the caller outside this service's lock)
     * @param queueSnapshotAt the instant just before the caller collected the snapshot
     */
    public void expireOverdue(Set<String> queuedRequestIds, Instant queueSnapshotAt) {
        Instant now = BatchClock.now();
        for (RunRequest snapshot : store.listRunRequests()) {
            RequestStatus status = snapshot.getStatus();
            if (status != RequestStatus.PENDING && status != RequestStatus.APPROVED) {
                continue;
            }
            lock.lock();
            try {
                RunRequest request = store.loadRunRequest(snapshot.getId());
                if (request == null) {
                    continue;
                }
                if (request.getStatus() == RequestStatus.PENDING && pendingExpired(request, now)) {
                    request.setStatus(RequestStatus.EXPIRED);
                    store.saveRunRequest(request);
                    LOGGER.info(() -> "Run request " + request.getId()
                            + " expired (pending timeout)");
                } else if (request.getStatus() == RequestStatus.APPROVED
                        && request.getExecutedRunId() == null
                        && !queuedRequestIds.contains(request.getId())
                        && ticketNotFresherThan(request, queueSnapshotAt)
                        && approvedExpired(request, now)) {
                    request.setStatus(RequestStatus.EXPIRED);
                    store.saveRunRequest(request);
                    LOGGER.info(() -> "Run request " + request.getId()
                            + " expired (approved-run timeout)");
                }
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * Whether the queue snapshot is authoritative for this request: true when the ticket was
     * never claimed, or was claimed strictly before the snapshot was taken (absent from the
     * snapshot then really means gone — e.g. the queue item was cleared). A ticket claimed at
     * or after the snapshot instant means the snapshot is stale for this request.
     */
    private static boolean ticketNotFresherThan(RunRequest request, Instant queueSnapshotAt) {
        Instant queuedAt = request.getQueuedAt();
        return queuedAt == null || queuedAt.isBefore(queueSnapshotAt);
    }

    // ---------------------------------------------------------------- invalidation (D-21)

    /**
     * Invalidates every PENDING/APPROVED request that targets the given (old) job full name
     * after a rename or move (D-21).
     *
     * @return the ids of the requests that were invalidated
     */
    public List<String> invalidateForJob(String oldFullName, String reason) {
        List<String> invalidated = new ArrayList<>();
        for (RunRequest snapshot : store.listRunRequests()) {
            if (!oldFullName.equals(snapshot.getJobFullName())) {
                continue;
            }
            RequestStatus status = snapshot.getStatus();
            if (status != RequestStatus.PENDING && status != RequestStatus.APPROVED) {
                continue;
            }
            lock.lock();
            try {
                RunRequest request = store.loadRunRequest(snapshot.getId());
                if (request == null) {
                    continue;
                }
                if (request.getStatus() == RequestStatus.PENDING
                        || request.getStatus() == RequestStatus.APPROVED) {
                    request.setStatus(RequestStatus.INVALIDATED);
                    request.setInvalidationReason(reason);
                    store.saveRunRequest(request);
                    invalidated.add(request.getId());
                    LOGGER.info(() -> "Run request " + request.getId() + " invalidated: " + reason);
                }
            } finally {
                lock.unlock();
            }
        }
        return invalidated;
    }

    // ---------------------------------------------------------------- startup recovery (SPEC 4, 7)

    /**
     * Re-submits APPROVED, never-executed requests exactly once after a restart (SPEC item 4).
     * Idempotency (T-RT-17): a request whose marker is already sitting in the restored queue,
     * or whose build already exists, is skipped; otherwise its consumption ticket is re-issued
     * and the request is submitted again. The approved-run timeout is judged from the recovery
     * moment (SPEC item 7 exception), implemented by moving the request's expiry base forward.
     *
     * <p>Ordering with the core queue restore: {@code Queue.init} (which restores
     * {@code queue.xml}) runs in the same {@code JOB_CONFIG_ADAPTED} initializer band as
     * {@link io.jenkins.plugins.batchcontrol.ops.StartupRecovery}, in undefined order, and
     * {@code Queue.load()} clears the live queue before reading. The whole recovery therefore
     * runs under the queue lock: if {@code queue.xml} is still present the persisted queue is
     * loaded here first (core's later {@code load()} re-reads the state re-saved below), so
     * the recovery dedup always sees the restored queue items.
     */
    public void recoverApprovedRequests() {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            return;
        }
        Queue.withLock(() -> recoverUnderQueueLock(jenkins));
    }

    private void recoverUnderQueueLock(Jenkins jenkins) {
        Queue queue = jenkins.getQueue();
        // Queue.load() renames queue.xml away after a successful restore, so an existing file
        // means the core restore has not happened yet in this session.
        java.io.File queueFile = new java.io.File(jenkins.getRootDir(), "queue.xml");
        boolean loadedHere = queueFile.exists();
        if (loadedHere) {
            queue.load();
        }
        Set<String> queuedIds = queuedMarkerRequestIds(jenkins);
        Instant now = BatchClock.now();
        for (RunRequest snapshot : store.listRunRequests()) {
            if (snapshot.getStatus() != RequestStatus.APPROVED || snapshot.getExecutedRunId() != null) {
                continue;
            }
            Job<?, ?> job = jenkins.getItemByFullName(snapshot.getJobFullName(), Job.class);
            if (job == null) {
                LOGGER.warning(() -> "Cannot recover run request " + snapshot.getId()
                        + ": job '" + snapshot.getJobFullName() + "' no longer exists");
                continue;
            }
            if (hasRunFor(job, snapshot.getId())) {
                continue; // a build for this request already exists; the run listener finishes it
            }
            boolean submit = false;
            RunRequest request = null;
            lock.lock();
            try {
                request = store.loadRunRequest(snapshot.getId());
                if (request == null || request.getStatus() != RequestStatus.APPROVED
                        || request.getExecutedRunId() != null) {
                    continue;
                }
                // SPEC 7 exception: downtime does not count against approvedRunTimeoutMinutes.
                request.setExpiryBase(now);
                if (queuedIds.contains(request.getId())) {
                    // The restored queue item will run it; just persist the new expiry base.
                    store.saveRunRequest(request);
                } else {
                    // Re-issue the consumption ticket for exactly one recovery submission.
                    request.setQueuedAt(null);
                    store.saveRunRequest(request);
                    submit = true;
                }
            } finally {
                lock.unlock();
            }
            if (submit) {
                LOGGER.info("Recovering approved run request " + request.getId()
                        + " for job " + request.getJobFullName());
                submitApproved(request);
            }
        }
        if (loadedHere) {
            // Re-save so the core Queue.init load() (running later in the same initializer
            // band) restores exactly the state recovery left behind.
            queue.save();
        }
    }

    /** Request ids whose approval marker is currently sitting on a queue item. */
    public static Set<String> queuedMarkerRequestIds(Jenkins jenkins) {
        Set<String> ids = new java.util.HashSet<>();
        for (Queue.Item item : jenkins.getQueue().getItems()) {
            ApprovedRunAction marker = item.getAction(ApprovedRunAction.class);
            if (marker != null) {
                ids.add(marker.getRequestId());
            }
        }
        return ids;
    }

    // ---------------------------------------------------------------- internals

    private RunRequest require(String id) {
        RunRequest request = store.loadRunRequest(id);
        if (request == null) {
            throw new IllegalArgumentException("No such run request: " + id);
        }
        return request;
    }

    private static boolean pendingExpired(RunRequest request, Instant now) {
        Duration timeout = Duration.ofHours(
                BatchControlGlobalConfiguration.get().getPendingTimeoutHours());
        return now.isAfter(request.getCreatedAt().plus(timeout));
    }

    private static boolean approvedExpired(RunRequest request, Instant now) {
        Instant base = request.getExpiryBase();
        if (base == null) {
            return false;
        }
        Duration timeout = Duration.ofMinutes(
                BatchControlGlobalConfiguration.get().getApprovedRunTimeoutMinutes());
        return now.isAfter(base.plus(timeout));
    }

    /** Whether a run for the given request id already exists on the job (recovery dedup). */
    private static boolean hasRunFor(Job<?, ?> job, String requestId) {
        for (Run<?, ?> run : job.getBuilds()) {
            ApprovedRunAction marker = run.getAction(ApprovedRunAction.class);
            if (marker != null && requestId.equals(marker.getRequestId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Submits the approved request to the queue. Runs as SYSTEM2 because the approver's
     * authority was already verified (ApprovalPolicy.checkDecision before the APPROVED commit,
     * or a previously committed approval during startup recovery); the submission itself must
     * not depend on the transient thread identity. The queue gate still validates and consumes
     * the marker. A refused or failed submission leaves the request APPROVED (quiet-down
     * tolerance); expiry or recovery handle it later.
     */
    private void submitApproved(RunRequest request) {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            return;
        }
        Job<?, ?> job = jenkins.getItemByFullName(request.getJobFullName(), Job.class);
        if (job == null) {
            LOGGER.warning(() -> "Approved run request " + request.getId()
                    + " targets missing job '" + request.getJobFullName() + "'; not submitted");
            return;
        }
        List<Action> actions = new ArrayList<>();
        List<ParameterValue> values = parameterValues(job, request.getParameters());
        if (!values.isEmpty()) {
            actions.add(new ParametersAction(values));
        }
        actions.add(new CauseAction(new ApprovedCause(
                request.getId(), request.getRequester(), request.getApprover())));
        actions.add(new ApprovedRunAction(request.getId()));
        // ACL.SYSTEM2 switch: permission checks are complete (see method javadoc).
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            Queue.Item item = ParameterizedJobMixIn.scheduleBuild2(job, 0,
                    actions.toArray(new Action[0]));
            if (item == null) {
                LOGGER.info(() -> "Submission of approved run request " + request.getId()
                        + " was not scheduled; the request stays APPROVED");
            }
        }
    }

    /** Reconstructs typed parameter values through the job's parameter definitions. */
    private static List<ParameterValue> parameterValues(Job<?, ?> job, Map<String, String> parameters) {
        List<ParameterValue> values = new ArrayList<>();
        ParametersDefinitionProperty definitions = job.getProperty(ParametersDefinitionProperty.class);
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            ParameterDefinition definition = definitions == null
                    ? null : definitions.getParameterDefinition(entry.getKey());
            if (definition instanceof SimpleParameterDefinition) {
                values.add(((SimpleParameterDefinition) definition).createValue(entry.getValue()));
            } else {
                values.add(new StringParameterValue(entry.getKey(), entry.getValue()));
            }
        }
        return values;
    }
}
