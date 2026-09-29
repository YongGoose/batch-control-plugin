package io.jenkins.plugins.batchcontrol.policy;

import com.cloudbees.hudson.plugins.folder.computed.ComputedFolder;
import hudson.model.Item;
import hudson.model.Job;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ActivationRequest;
import io.jenkins.plugins.batchcontrol.model.ActivationState;
import io.jenkins.plugins.batchcontrol.model.Approvers;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.ops.NotificationDispatcher;
import io.jenkins.plugins.batchcontrol.ops.NotificationEvent;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.Store;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.springframework.security.access.AccessDeniedException;

/**
 * Activation approval (SPEC item 6a, D-39): whether a run-controlled job may run unattended, and
 * the single entry point for every {@link ActivationRequest} state transition and every
 * {@link ActivationState} write. No other class changes either.
 *
 * <p>The activation lives in the plugin store, outside the job's {@code config.xml}, so none of
 * the configuration write paths (web form, REST, CLI, script, JCasC) can activate a job. It is
 * changed only by an approved {@code ACTIVATE}/{@code HOLD} request and, once, by the upgrade
 * seeding ({@link #seedExistingJobs()}).
 *
 * <p><b>Concurrency</b>: as in {@link RunRequestService}, every load, validate, transition and
 * persist sequence runs under one {@link ReentrantLock}, so a transition is an effective
 * compare-and-set. {@link #isActivated(Job)} takes no lock: the queue gate calls it while Jenkins
 * holds the queue lock, and it is answered from an in-memory cache (filled from the store on first
 * use per job and kept in step by every write here).
 *
 * <p><b>Failure families</b>: {@link IllegalArgumentException} for input validation,
 * {@link IllegalStateException} for wrong-state transitions, {@link AccessDeniedException} (403)
 * for authorization refusals.
 */
@Restricted(NoExternalUse.class)
public final class ActivationService {

    private static final Logger LOGGER = Logger.getLogger(ActivationService.class.getName());

    /** Same reason size cap as run and grant requests (D-22). */
    private static final int MAX_REASON_LENGTH = 4000;

    private static final ActivationService INSTANCE = new ActivationService();

    private final ReentrantLock lock = new ReentrantLock();
    private final Store store = Store.get();

    /** Job full name to "activated", for the current Jenkins session only. */
    private final Map<String, Boolean> activatedCache = new ConcurrentHashMap<>();
    private final Object cacheMonitor = new Object();
    private volatile WeakReference<Jenkins> cacheFor = new WeakReference<>(null);

    private ActivationService() {
    }

    public static ActivationService get() {
        return INSTANCE;
    }

    // ---------------------------------------------------------------- read API

    /**
     * Whether the job may run on timer and upstream causes as far as activation is concerned
     * (SPEC item 6a). The queue gate ANDs this with {@code blockTimer}/{@code blockUpstream}.
     *
     * <p>The answer is truthful: a job is activated only if an activated state is stored for it,
     * so a computed child (D-32), which nobody activates, reports not activated. It is the queue
     * gate that exempts computed children from the activation check, because they are not
     * controlled. A state that cannot be read counts as not activated (fail closed).
     */
    public boolean isActivated(Job<?, ?> job) {
        Objects.requireNonNull(job, "job");
        String fullName = job.getFullName();
        Map<String, Boolean> cache = cache();
        Boolean cached = cache.get(fullName);
        if (cached != null) {
            return cached;
        }
        try {
            ActivationState state = store.loadActivationState(fullName);
            boolean activated = state != null && state.isActivated();
            cache.put(fullName, activated);
            return activated;
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, e, () -> "Could not read the activation state of '" + fullName
                    + "'; treating the job as not activated");
            return false;
        }
    }

    /** The stored activation state of a job, or {@code null} if the job was never activated or held. */
    public ActivationState getState(String jobFullName) {
        return store.loadActivationState(jobFullName);
    }

    /** The stored activation state of a job, or {@code null}. */
    public ActivationState getState(Job<?, ?> job) {
        return getState(job.getFullName());
    }

    /** Loads an activation request by id, or {@code null}. */
    public ActivationRequest load(String id) {
        return store.loadActivationRequest(id);
    }

    /** All stored activation requests, in creation order. */
    public List<ActivationRequest> list() {
        return store.listActivationRequests();
    }

    /** The PENDING activation and hold requests, in creation order (the approval inbox). */
    public List<ActivationRequest> listPending() {
        List<ActivationRequest> pending = new ArrayList<>();
        for (ActivationRequest request : store.listOpenActivationRequests()) {
            if (request.getStatus() == RequestStatus.PENDING) {
                pending.add(request);
            }
        }
        return pending;
    }

    /** The PENDING requests on which {@code userId} is a designated approver. */
    public List<ActivationRequest> listPendingFor(String userId) {
        List<ActivationRequest> mine = new ArrayList<>();
        for (ActivationRequest request : listPending()) {
            if (request.isDesignatedApprover(userId)) {
                mine.add(request);
            }
        }
        return mine;
    }

    /** The PENDING requests of one job, in creation order. */
    public List<ActivationRequest> listPendingForJob(String jobFullName) {
        List<ActivationRequest> forJob = new ArrayList<>();
        for (ActivationRequest request : listPending()) {
            if (request.getJobFullName().equals(jobFullName)) {
                forJob.add(request);
            }
        }
        return forJob;
    }

    /** Every stored request of one job, in creation order. */
    public List<ActivationRequest> listForJob(String jobFullName) {
        List<ActivationRequest> forJob = new ArrayList<>();
        for (ActivationRequest request : store.listActivationRequests()) {
            if (request.getJobFullName().equals(jobFullName)) {
                forJob.add(request);
            }
        }
        return forJob;
    }

    // ---------------------------------------------------------------- creation (SPEC 6a)

    /**
     * Creates a PENDING activation or hold request for the job. The requester is the current
     * authentication and needs {@code BatchControl/Request} plus {@code Item/Read} on the job
     * (D-39). Validation as for run requests: a reason of at most {@value #MAX_REASON_LENGTH}
     * characters and a designated approver set that passes the SPEC item 3 rules, including the
     * job's own approver list (D-37, #23).
     *
     * <p>{@code ACTIVATE} is refused for a job that is already activated and {@code HOLD} for a job
     * that is not, since neither could change anything. A computed child (D-32) is not controlled
     * and cannot be the subject of a request.
     */
    public ActivationRequest create(Job<?, ?> job, ActivationRequest.Action action, String reason,
                                    List<String> approvers) {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(action, "action");
        Jenkins.get().checkPermission(BatchControlPermissions.REQUEST);
        job.checkPermission(Item.READ);
        String requester = Jenkins.getAuthentication2().getName();

        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("A reason is required to create an activation request.");
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("The reason must not exceed "
                    + MAX_REASON_LENGTH + " characters.");
        }
        if (isComputedChild(job)) {
            throw new IllegalArgumentException("Job '" + job.getFullName() + "' is generated by its folder "
                    + "and is not run-controlled, so it needs no activation (D-32).");
        }
        boolean activated = isActivated(job);
        if (action == ActivationRequest.Action.ACTIVATE && activated) {
            throw new IllegalArgumentException("Job '" + job.getFullName() + "' is already activated.");
        }
        if (action == ActivationRequest.Action.HOLD && !activated) {
            throw new IllegalArgumentException("Job '" + job.getFullName() + "' is not activated, so it "
                    + "cannot be put on hold.");
        }
        List<String> designated = ApprovalPolicy.checkDesignation(requester, approvers, job);

        ActivationRequest request = ActivationRequest.create(job.getFullName(), action, reason,
                requester, designated);
        lock.lock();
        try {
            store.saveActivationRequest(request);
        } finally {
            lock.unlock();
        }
        NotificationDispatcher.activation(NotificationEvent.REQUEST_CREATED, request);
        return request;
    }

    // ---------------------------------------------------------------- decisions (SPEC 3, 6a)

    /**
     * Approves a PENDING request and applies it: {@code ACTIVATE} marks the job activated and
     * writes an {@link ChangeType#ACTIVATED} record, {@code HOLD} marks it not activated and writes
     * a {@link ChangeType#HELD} record. The caller must be a designated approver holding
     * {@code BatchControl/Approve} (or the administrator self-approval path), exactly as for a run
     * request. A request past its pending timeout is expired instead; a request whose job no
     * longer exists is invalidated.
     */
    public ActivationRequest approve(String id, String comment) {
        ActivationRequest request;
        lock.lock();
        try {
            request = require(id);
            if (request.getStatus() != RequestStatus.PENDING) {
                throw new IllegalStateException("Activation request " + id + " is "
                        + request.getStatus() + " and can no longer be approved.");
            }
            boolean selfApproval = ApprovalPolicy.checkJobDecision(request.getId(), request.getRequester(),
                    request.getApprovers(), request.getJobFullName());
            Instant now = BatchClock.now();
            if (pendingExpired(request, now)) {
                request.setStatus(RequestStatus.EXPIRED);
                store.saveActivationRequest(request);
                throw new IllegalStateException("Activation request " + id
                        + " passed its pending timeout and is now EXPIRED.");
            }
            // The decision checks are complete; jobForPolicy looks the job up as SYSTEM so an
            // approver without Item/Read on the job can still decide (#26 rule for run requests).
            if (ApprovalPolicy.jobForPolicy(request.getJobFullName()) == null) {
                request.setStatus(RequestStatus.INVALIDATED);
                request.setDecisionComment("Target job no longer exists");
                store.saveActivationRequest(request);
                throw new IllegalStateException("Job '" + request.getJobFullName()
                        + "' no longer exists; activation request " + id + " is now INVALIDATED.");
            }
            String decider = Jenkins.getAuthentication2().getName();
            request.setStatus(RequestStatus.APPROVED);
            request.setDecidedAt(now);
            request.setDecidedBy(decider);
            request.setDecisionComment(comment);
            request.setSelfApproved(selfApproval);
            store.saveActivationRequest(request);
            applyApproved(request, decider, now, selfApproval);
        } finally {
            lock.unlock();
        }
        NotificationDispatcher.activation(NotificationEvent.APPROVED, request);
        return request;
    }

    /** Writes the state and the change record of an approved request; under {@link #lock}. */
    private void applyApproved(ActivationRequest request, String decider, Instant now, boolean selfApproval) {
        String fullName = request.getJobFullName();
        boolean activate = request.getAction() == ActivationRequest.Action.ACTIVATE;
        ActivationState state;
        if (activate) {
            state = ActivationState.activated(fullName, decider, now, request.getId());
        } else {
            ActivationState existing = store.loadActivationState(fullName);
            state = existing == null
                    ? ActivationState.held(fullName, decider, now, request.getId())
                    : existing.heldBy(decider, now, request.getId());
        }
        saveState(state);
        String detail = (activate ? "Activated" : "Put on hold") + " by approval of activation request "
                + request.getId() + " (" + request.getAction() + ", requested by " + request.getRequester()
                + (selfApproval ? ", self-approved" : "") + ")";
        store.appendChangeRecord(ChangeRecord.create(activate ? ChangeType.ACTIVATED : ChangeType.HELD,
                fullName, decider, detail));
        LOGGER.info(() -> "Job '" + fullName + "' " + (activate ? "activated" : "put on hold")
                + " by '" + decider + "' (activation request " + request.getId() + ")");
    }

    /** Rejects a PENDING request; the comment is mandatory (SPEC item 5 rule). Nothing else changes. */
    public ActivationRequest reject(String id, String comment) {
        if (comment == null || comment.trim().isEmpty()) {
            throw new IllegalArgumentException("A comment is required to reject an activation request.");
        }
        ActivationRequest request;
        lock.lock();
        try {
            request = require(id);
            if (request.getStatus() != RequestStatus.PENDING) {
                throw new IllegalStateException("Activation request " + id + " is "
                        + request.getStatus() + " and can no longer be rejected.");
            }
            ApprovalPolicy.checkJobDecision(request.getId(), request.getRequester(), request.getApprovers(),
                    request.getJobFullName());
            request.setStatus(RequestStatus.REJECTED);
            request.setDecidedAt(BatchClock.now());
            request.setDecidedBy(Jenkins.getAuthentication2().getName());
            request.setDecisionComment(comment);
            store.saveActivationRequest(request);
        } finally {
            lock.unlock();
        }
        NotificationDispatcher.activation(NotificationEvent.REJECTED, request);
        return request;
    }

    /** Cancels a PENDING request; requester or a Manage holder only (SPEC item 7 rule). */
    public ActivationRequest cancel(String id) {
        String caller = Jenkins.getAuthentication2().getName();
        lock.lock();
        try {
            ActivationRequest request = require(id);
            if (!Approvers.sameUser(caller, request.getRequester())
                    && !Jenkins.get().hasPermission(BatchControlPermissions.MANAGE)) {
                throw new AccessDeniedException(
                        "Only the requester or a Manage holder may cancel activation request " + id + ".");
            }
            if (request.getStatus() != RequestStatus.PENDING) {
                throw new IllegalStateException("Activation request " + id + " is "
                        + request.getStatus() + "; only PENDING requests can be cancelled.");
            }
            request.setStatus(RequestStatus.CANCELLED);
            store.saveActivationRequest(request);
            return request;
        } finally {
            lock.unlock();
        }
    }

    /** Single-approver form of {@link #changeApprovers(String, List)}. */
    public ActivationRequest changeApprover(String id, String newApprover) {
        return changeApprovers(id, Approvers.of(newApprover));
    }

    /**
     * Replaces the designated approver set of a PENDING request; requester only (D-26, D-37).
     * Recorded as (previous set, new set, changed by, time).
     */
    public ActivationRequest changeApprovers(String id, List<String> newApprovers) {
        String caller = Jenkins.getAuthentication2().getName();
        ActivationRequest request;
        lock.lock();
        try {
            request = require(id);
            if (!Approvers.sameUser(caller, request.getRequester())) {
                throw new AccessDeniedException(
                        "Only the requester may change the approvers of activation request " + id + ".");
            }
            if (request.getStatus() != RequestStatus.PENDING) {
                throw new IllegalStateException("Activation request " + id + " is "
                        + request.getStatus() + "; the approvers can only be changed while PENDING.");
            }
            // #23: resolved as SYSTEM after the requester check above (ApprovalPolicy.jobForPolicy).
            Job<?, ?> job = ApprovalPolicy.jobForPolicy(request.getJobFullName());
            List<String> designated = ApprovalPolicy.checkDesignation(request.getRequester(), newApprovers, job);
            request.addApproverChange(new ActivationRequest.ApproverChange(
                    request.getApprovers(), designated, caller, BatchClock.now()));
            request.setApprovers(designated);
            store.saveActivationRequest(request);
        } finally {
            lock.unlock();
        }
        NotificationDispatcher.activation(NotificationEvent.APPROVERS_CHANGED, request);
        return request;
    }

    // ---------------------------------------------------------------- expiry (SPEC 7)

    /**
     * Expires PENDING requests past {@code pendingTimeoutHours}, the run-request timeout. Called
     * by the periodic work; the truth stays the clock comparison ({@link #approve} expires a late
     * request on its own).
     */
    public void expireOverduePending() {
        Instant now = BatchClock.now();
        for (ActivationRequest snapshot : store.listOpenActivationRequests()) {
            if (snapshot.getStatus() != RequestStatus.PENDING) {
                continue;
            }
            lock.lock();
            try {
                ActivationRequest request = store.loadActivationRequest(snapshot.getId());
                if (request != null && request.getStatus() == RequestStatus.PENDING
                        && pendingExpired(request, now)) {
                    request.setStatus(RequestStatus.EXPIRED);
                    store.saveActivationRequest(request);
                    LOGGER.info(() -> "Activation request " + request.getId() + " expired (pending timeout)");
                }
            } finally {
                lock.unlock();
            }
        }
    }

    /** D-36: sends {@link NotificationEvent#EXPIRING} once per PENDING request close to its timeout. */
    public void notifyExpiring() {
        Instant now = BatchClock.now();
        Duration lead = Duration.ofMinutes(
                BatchControlGlobalConfiguration.get().getNotifyBeforeExpiryMinutes());
        for (ActivationRequest snapshot : store.listOpenActivationRequests()) {
            if (snapshot.getStatus() != RequestStatus.PENDING || snapshot.isExpiringNotified()) {
                continue;
            }
            ActivationRequest notified = null;
            lock.lock();
            try {
                ActivationRequest request = store.loadActivationRequest(snapshot.getId());
                if (request != null && request.getStatus() == RequestStatus.PENDING
                        && !request.isExpiringNotified()) {
                    Instant expiresAt = pendingExpiry(request);
                    if (now.isBefore(expiresAt) && !now.isBefore(expiresAt.minus(lead))) {
                        request.setExpiringNotified(true);
                        store.saveActivationRequest(request);
                        notified = request;
                    }
                }
            } finally {
                lock.unlock();
            }
            if (notified != null) {
                NotificationDispatcher.activation(NotificationEvent.EXPIRING, notified);
            }
        }
    }

    // ---------------------------------------------------------------- item lifecycle (SPEC 6a)

    /**
     * A job was renamed or moved: its activation follows it (SPEC item 6a), and PENDING requests
     * on the old name end INVALIDATED, as run requests do (D-21: the approver reviewed another
     * identity). Runs regardless of the switches; it is bookkeeping, not control.
     */
    public void relocate(String oldFullName, String newFullName) {
        lock.lock();
        try {
            ActivationState state = store.loadActivationState(oldFullName);
            if (state != null) {
                saveState(state.copyFor(newFullName));
                deleteState(oldFullName);
            } else {
                // A state left behind under the new name by an earlier job must not be inherited.
                deleteState(newFullName);
            }
            invalidatePending(oldFullName, "Target job renamed or moved: '" + oldFullName
                    + "' -> '" + newFullName + "'");
        } finally {
            lock.unlock();
        }
    }

    /**
     * A job was deleted: its activation is removed (SPEC item 6a) and its PENDING requests end
     * INVALIDATED, so a later job of the same name starts not activated and cannot be activated by
     * a request that was about another job. For a deleted folder the same applies to everything
     * stored below it.
     */
    public void remove(String fullName, boolean withDescendants) {
        lock.lock();
        try {
            deleteState(fullName);
            invalidatePending(fullName, "Target job deleted: '" + fullName + "'");
            if (withDescendants) {
                String prefix = fullName + "/";
                for (ActivationState state : store.listActivationStates()) {
                    if (state.getJobFullName() != null && state.getJobFullName().startsWith(prefix)) {
                        deleteState(state.getJobFullName());
                    }
                }
                for (ActivationRequest request : store.listOpenActivationRequests()) {
                    if (request.getJobFullName().startsWith(prefix)) {
                        invalidatePending(request.getJobFullName(),
                                "Target job deleted with its folder: '" + fullName + "'");
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * A job was created. While run control is on it starts not activated (SPEC item 6a): any state
     * stored under its name belongs to a job that no longer exists and is discarded. While run
     * control is off it is recorded as activated at creation with
     * {@code activatedBy = uncontrolled} (D-45), so turning run control on later never stops a
     * schedule created in between; an {@link ChangeType#ACTIVATED} record is written when change
     * recording is active (change control on). A computed child (D-32) needs no state.
     */
    public void onJobCreated(Job<?, ?> job) {
        Objects.requireNonNull(job, "job");
        String fullName = job.getFullName();
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        lock.lock();
        try {
            if (cfg.isRunControlEnabled() || isComputedChild(job)) {
                if (store.loadActivationState(fullName) != null) {
                    LOGGER.info(() -> "Discarding a stale activation state stored for the new job '" + fullName + "'");
                    deleteState(fullName);
                }
                return;
            }
            saveState(ActivationState.activated(fullName, ActivationState.UNCONTROLLED, BatchClock.now(), null));
            if (cfg.isChangeControlEnabled()) {
                store.appendChangeRecord(ChangeRecord.create(ChangeType.ACTIVATED, fullName,
                        ActivationState.UNCONTROLLED, "Activated at creation: the job was created while run "
                                + "control was off, so it counts as in service (D-45)"));
            }
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------- upgrade seeding (SPEC 6a)

    /**
     * One-time upgrade seeding: when {@code activations/.schema} is absent, every job that exists
     * now (other than a computed child, D-32) is recorded as activated with
     * {@code activatedBy = upgrade} and one {@link ChangeType#ACTIVATED} record, and then the marker
     * is written. With the marker present this does nothing, so it never re-seeds, not even after
     * the activation files are removed. A job that already has a stored state is left alone, so a
     * seeding interrupted before the marker was written completes without duplicates.
     *
     * <p>Seeding does not depend on the switches: installing with run control off and turning it
     * on later must not stop an existing schedule either. With run control off the state has no
     * effect.
     *
     * <p>Must run with every job visible: it is called from an initializer, which runs as SYSTEM.
     *
     * @return the number of jobs seeded, or {@code -1} when the marker was already present
     */
    public int seedExistingJobs() {
        lock.lock();
        try {
            if (store.isActivationSchemaMarked()) {
                return -1;
            }
            Instant now = BatchClock.now();
            int seeded = 0;
            for (Job<?, ?> job : Jenkins.get().allItems(Job.class)) {
                if (isComputedChild(job)) {
                    continue;
                }
                String fullName = job.getFullName();
                if (store.loadActivationState(fullName) != null) {
                    continue;
                }
                saveState(ActivationState.activated(fullName, ActivationState.UPGRADE, now, null));
                store.appendChangeRecord(ChangeRecord.create(ChangeType.ACTIVATED, fullName,
                        ActivationState.UPGRADE, "Activated by upgrade: the job existed when activation "
                                + "approval was installed, so its schedule keeps running (SPEC item 6a)"));
                seeded++;
            }
            store.markActivationSchema();
            int count = seeded;
            LOGGER.info(() -> "Activation approval installed: " + count + " existing jobs recorded as activated "
                    + "(activatedBy=upgrade); jobs created from now on start not activated");
            return seeded;
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------- internals

    /**
     * D-32: a job its parent computes for itself (a multibranch branch job, an organization
     * folder's repositories) is not controlled and needs no activation. Same test as the D-31/D-34
     * default in {@code listener.ItemChangeListener}.
     */
    public static boolean isComputedChild(Item item) {
        return item.getParent() instanceof ComputedFolder;
    }

    private void saveState(ActivationState state) {
        store.saveActivationState(state);
        cache().put(state.getJobFullName(), state.isActivated());
    }

    private void deleteState(String fullName) {
        store.deleteActivationState(fullName);
        cache().remove(fullName);
    }

    /** Ends the PENDING requests of one job as INVALIDATED; under {@link #lock}. */
    private void invalidatePending(String jobFullName, String reason) {
        for (ActivationRequest request : store.listOpenActivationRequests()) {
            if (request.getStatus() == RequestStatus.PENDING && request.getJobFullName().equals(jobFullName)) {
                request.setStatus(RequestStatus.INVALIDATED);
                request.setDecisionComment(reason);
                request.setDecidedAt(BatchClock.now());
                store.saveActivationRequest(request);
                LOGGER.info(() -> "Activation request " + request.getId() + " invalidated: " + reason);
            }
        }
    }

    /** The cache of the current Jenkins session; a new session (restart, next test) starts empty. */
    private Map<String, Boolean> cache() {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (cacheFor.get() != jenkins) {
            synchronized (cacheMonitor) {
                if (cacheFor.get() != jenkins) {
                    activatedCache.clear();
                    cacheFor = new WeakReference<>(jenkins);
                }
            }
        }
        return activatedCache;
    }

    private ActivationRequest require(String id) {
        ActivationRequest request = store.loadActivationRequest(id);
        if (request == null) {
            throw new IllegalArgumentException("No such activation request: " + id);
        }
        return request;
    }

    private static boolean pendingExpired(ActivationRequest request, Instant now) {
        return pendingExpiry(request).isBefore(now);
    }

    private static Instant pendingExpiry(ActivationRequest request) {
        Duration timeout = Duration.ofHours(
                BatchControlGlobalConfiguration.get().getPendingTimeoutHours());
        return request.getCreatedAt().plus(timeout);
    }
}
