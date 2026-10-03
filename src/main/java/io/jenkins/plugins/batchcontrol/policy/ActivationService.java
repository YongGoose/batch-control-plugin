package io.jenkins.plugins.batchcontrol.policy;

import com.cloudbees.hudson.plugins.folder.computed.ComputedFolder;
import hudson.model.Item;
import hudson.model.Job;
import hudson.security.ACL;
import hudson.security.ACLContext;
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
import io.jenkins.plugins.batchcontrol.security.ItemIdentity;
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
import java.util.concurrent.atomic.AtomicLong;
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

    /**
     * A cached state: whether it is activated, the directory marker it is bound to, and when the
     * activation last ended (S-25-03: read by the queue gate from memory, never from disk).
     */
    private record Cached(boolean activated, String identity, Long deactivatedAtMillis) {
        static final Cached NOT_ACTIVATED = new Cached(false, null, null);

        static Cached of(ActivationState state) {
            java.time.Instant ended = state.getDeactivatedAt();
            return new Cached(state.isActivated(), state.getItemIdentity(), ended == null ? null : ended.toEpochMilli());
        }
    }

    /** Item full name to its cached state, for the current Jenkins session only. */
    private final Map<String, Cached> activatedCache = new ConcurrentHashMap<>();
    private final Object cacheMonitor = new Object();
    /**
     * security-13 S-13-04: bumped by every cache write, under {@link #cacheMonitor}. A reader fills
     * the cache from the store only if no write happened since it started reading, so a HOLD or a
     * deletion can never be overwritten by a value read before it.
     */
    private final AtomicLong generation = new AtomicLong();
    private volatile WeakReference<Jenkins> cacheFor = new WeakReference<>(null);

    private ActivationService() {
    }

    public static ActivationService get() {
        return INSTANCE;
    }

    // ---------------------------------------------------------------- read API

    /**
     * Whether an approved activation is stored for this very item. The answer is
     * truthful: a computed child, which nobody activates, reports not activated; whether it may run
     * is {@link #mayRunUnattended(Job)}, which asks its computed-folder ancestor (D-46c).
     *
     * <p>Fails closed: a state that cannot be read, or one bound to another directory than the
     * item's current one (a re-created item under an old name, S-13-09), counts as not activated.
     */
    public boolean isActivated(Item item) {
        Objects.requireNonNull(item, "item");
        String fullName = item.getFullName();
        Cached cached = cache().get(fullName);
        if (cached == null) {
            long before = generation.get();
            try {
                ActivationState state = store.loadActivationState(fullName);
                cached = state == null ? Cached.NOT_ACTIVATED : Cached.of(state);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "Could not read the activation state of '" + fullName
                        + "'; treating it as not activated");
                return false;
            }
            synchronized (cacheMonitor) {
                if (generation.get() == before) {
                    cache().putIfAbsent(fullName, cached);
                }
            }
        }
        if (!cached.activated()) {
            return false;
        }
        if (cached.identity() != null) {
            String current = ItemIdentity.of(item.getRootDir());
            if (current != null && !current.equals(cached.identity())) {
                LOGGER.fine(() -> "The activation stored for '" + fullName + "' belongs to another directory; "
                        + "treating the item as not activated");
                return false;
            }
        }
        return true;
    }

    /**
     * e2e-04 FD-07, security-25 S-25-03: the part of a refusal record's coalescing key that changes
     * when the item's activation ends: {@code never-activated}, {@code held-<epoch millis>}, or
     * {@code unknown} when the state cannot be read. Answered from the activation cache that
     * {@link #isActivated(Item)} fills, so the queue gate does not read the disk; never throws.
     */
    public String holdEpoch(Item item) {
        try {
            Cached cached = cache().get(item.getFullName());
            if (cached == null) {
                isActivated(item); // fills the cache (or fails closed and leaves it empty)
                cached = cache().get(item.getFullName());
            }
            if (cached == null) {
                return "unknown";
            }
            return cached.deactivatedAtMillis() == null ? "never-activated" : "held-" + cached.deactivatedAtMillis();
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "Could not tell when the activation of '" + item.getFullName() + "' ended", e);
            return "unknown";
        }
    }

    /** Job form of {@link #isActivated(Item)}. */
    public boolean isActivated(Job<?, ?> job) {
        return isActivated((Item) job);
    }

    /**
     * The queue gate's activation input (SPEC item 6a, D-46): whether the job may start on a cause
     * that is not a human submission. A computed child asks the item that carries its activation
     * ({@link #activationSubject}); any other job asks itself.
     */
    public boolean mayRunUnattended(Job<?, ?> job) {
        return isActivated(activationSubject(job));
    }

    /**
     * The item whose activation governs {@code item} (D-46c): the item itself, or for a computed
     * child its nearest computed-folder ancestor, resolved again while that folder is itself a
     * computed child (a repository project of an organization folder is carried by the
     * organization folder, which is the item that was created and can be requested on).
     */
    public static Item activationSubject(Item item) {
        Item subject = item;
        while (subject.getParent() instanceof ComputedFolder) {
            subject = (Item) subject.getParent();
        }
        return subject;
    }

    /**
     * Whether the item carries an activation of its own: a job or a computed folder that is not
     * itself a computed child (D-46).
     */
    public static boolean isSubject(Item item) {
        return (item instanceof Job || item instanceof ComputedFolder) && !isComputedChild(item);
    }

    /** The stored activation state of an item, or {@code null} if none is stored. */
    public ActivationState getState(String jobFullName) {
        return store.loadActivationState(jobFullName);
    }

    /** The stored activation state of an item (a job or a computed folder), or {@code null}. */
    public ActivationState getState(Item item) {
        return getState(item.getFullName());
    }

    /** Job form of {@link #getState(Item)}. */
    public ActivationState getState(Job<?, ?> job) {
        return getState((Item) job);
    }

    /** Loads an activation request by id, or {@code null}. */
    public ActivationRequest load(String id) {
        return store.loadActivationRequest(id);
    }

    /** All stored activation requests, in creation order. */
    public List<ActivationRequest> list() {
        return store.listActivationRequests();
    }

    /**
     * D-38b: whether {@code auth} filed at least one activation or hold request, in any status
     * (the requester always sees their own, P-09). Answered from the store's in-memory index; no
     * item is visited.
     */
    public boolean hasOwnRequests(org.springframework.security.core.Authentication auth) {
        if (auth == null || ACL.isAnonymous2(auth)) {
            return false;
        }
        return store.hasActivationRequestBy(auth.getName());
    }

    /**
     * D-38b: whether the current user could request an activation or hold of {@code item}: the
     * permission checks of {@link #create(Item, ActivationRequest.Action, String, List)}, namely
     * {@code BatchControl/Request} and {@code Item/Read} on the item. Screens use it to show the
     * request form; {@code create} still checks for real.
     */
    public boolean canRequest(Item item) {
        return item != null && item.hasPermission(BatchControlPermissions.REQUEST)
                && item.hasPermission(Item.READ);
    }

    /**
     * D-38b: whether the current user may cancel {@code request}: its requester holding
     * {@code BatchControl/Request} on the item, or a Manage holder. Permission only.
     */
    public boolean canCancel(ActivationRequest request) {
        return ApprovalPolicy.callerIsRequesterWithRequest(request.getRequester(), request.getJobFullName())
                || Jenkins.get().hasPermission(BatchControlPermissions.MANAGE);
    }

    /**
     * D-38b: whether the current user may change the approvers of {@code request}: its requester
     * holding {@code BatchControl/Request} on the item. Permission only.
     */
    public boolean canChangeApprovers(ActivationRequest request) {
        return ApprovalPolicy.callerIsRequesterWithRequest(request.getRequester(), request.getJobFullName());
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

    /** Job form of {@link #create(Item, ActivationRequest.Action, String, List)}. */
    public ActivationRequest create(Job<?, ?> job, ActivationRequest.Action action, String reason,
                                    List<String> approvers) {
        return create((Item) job, action, reason, approvers);
    }

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
    public ActivationRequest create(Item job, ActivationRequest.Action action, String reason,
                                    List<String> approvers) {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(action, "action");
        // D-38b: Request is checked on the item (granted there, on a folder, or globally).
        job.checkPermission(BatchControlPermissions.REQUEST);
        job.checkPermission(Item.READ);
        String requester = Jenkins.getAuthentication2().getName();

        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("A reason is required to create an activation request.");
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("The reason must not exceed "
                    + MAX_REASON_LENGTH + " characters.");
        }
        if (!isSubject(job)) {
            Item carrier = activationSubject(job);
            throw new IllegalArgumentException("'" + job.getFullName() + "' does not carry an activation of "
                    + "its own" + (carrier != job ? "; request it on '" + carrier.getFullName() + "'" : "")
                    + ".");
        }
        boolean activated = isActivated(job);
        if (action == ActivationRequest.Action.ACTIVATE && activated) {
            throw new IllegalArgumentException("Job '" + job.getFullName() + "' is already activated.");
        }
        if (action == ActivationRequest.Action.HOLD && !activated) {
            throw new IllegalArgumentException("Job '" + job.getFullName() + "' is not activated, so it "
                    + "cannot be put on hold.");
        }
        List<String> designated = ApprovalPolicy.checkDesignation(requester, approvers,
                job instanceof Job ? (Job<?, ?>) job : null);

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
                String reason = EndReasons.pendingExpired();
                request.setStatus(RequestStatus.EXPIRED);
                request.setDecisionComment(reason);
                store.saveActivationRequest(request);
                NotificationDispatcher.activationEnded(NotificationEvent.EXPIRED, request, true, reason);
                throw new IllegalStateException("Activation request " + id
                        + " passed its pending timeout and is now EXPIRED.");
            }
            // The decision checks are complete; jobForPolicy looks the job up as SYSTEM so an
            // approver without Item/Read on the job can still decide (#26 rule for run requests).
            Item subject = ApprovalPolicy.itemForPolicy(request.getJobFullName());
            if (subject == null || !isSubject(subject)) {
                request.setStatus(RequestStatus.INVALIDATED);
                request.setDecisionComment("Target job no longer exists");
                store.saveActivationRequest(request);
                NotificationDispatcher.activationEnded(NotificationEvent.INVALIDATED, request, true,
                        "Target job no longer exists");
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
            applyApproved(request, decider, now, selfApproval, ItemIdentity.of(subject.getRootDir()));
            // S-13-07: the other pending requests of the item were asked against the state before
            // this decision; a stale ACTIVATE must not be able to undo this HOLD (or the reverse).
            invalidatePending(request.getJobFullName(), "Superseded by the approval of activation request "
                    + request.getId());
        } finally {
            lock.unlock();
        }
        NotificationDispatcher.activation(NotificationEvent.APPROVED, request);
        return request;
    }

    /** Writes the state and the change record of an approved request; under {@link #lock}. */
    private void applyApproved(ActivationRequest request, String decider, Instant now, boolean selfApproval,
                               String identity) {
        String fullName = request.getJobFullName();
        boolean activate = request.getAction() == ActivationRequest.Action.ACTIVATE;
        ActivationState state;
        if (activate) {
            state = ActivationState.activated(fullName, decider, now, request.getId(), identity);
        } else {
            ActivationState existing = store.loadActivationState(fullName);
            state = existing == null
                    ? ActivationState.notActivated(fullName, identity).heldBy(decider, now, request.getId())
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
            if (!canCancel(request)) {
                throw new AccessDeniedException("Only the requester, holding BatchControl/Request on the job, "
                        + "or a Manage holder may cancel activation request " + id + ".");
            }
            if (request.getStatus() != RequestStatus.PENDING) {
                throw new IllegalStateException("Activation request " + id + " is "
                        + request.getStatus() + "; only PENDING requests can be cancelled.");
            }
            request.setStatus(RequestStatus.CANCELLED);
            // e2e-03 DEF-13: the history names who cancelled and when (the requester or a
            // Manage holder), in the same fields a decision uses.
            request.setDecidedAt(BatchClock.now());
            request.setDecidedBy(caller);
            store.saveActivationRequest(request);
            NotificationDispatcher.activationEnded(NotificationEvent.CANCELLED, request, true, "Cancelled by " + caller);
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
            if (!canChangeApprovers(request)) {
                throw new AccessDeniedException("Only the requester, holding BatchControl/Request on the job, "
                        + "may change the approvers of activation request " + id + ".");
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
                    String reason = EndReasons.pendingExpired();
                    request.setStatus(RequestStatus.EXPIRED);
                    request.setDecisionComment(reason);
                    store.saveActivationRequest(request);
                    NotificationDispatcher.activationEnded(NotificationEvent.EXPIRED, request, true, reason);
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
     * A job was renamed or moved: its activation follows it, and PENDING requests
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
     * A job was deleted: its activation is removed and its PENDING requests end
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
     * An item was created (SPEC item 6a, D-45, D-46). For an item that carries its own activation
     * (a job or a computed folder that is not a computed child):
     * <ul>
     *   <li>run control on: an explicit not-activated state is stored (S-13-06), so a retried
     *       seeding never activates it and nothing left under the name is inherited (S-13-04);</li>
     *   <li>run control off: it is recorded as activated by {@link ActivationState#UNCONTROLLED}
     *       with an {@link ChangeType#ACTIVATED} record (S-13-10), so turning run control on later
     *       never stops a schedule created in between.</li>
     * </ul>
     * A computed child carries no state of its own; anything stored under its name is discarded.
     * The state is bound to the item's directory marker (S-13-09).
     */
    public void onItemCreated(Item item) {
        Objects.requireNonNull(item, "item");
        String fullName = item.getFullName();
        lock.lock();
        try {
            if (!isSubject(item)) {
                // S-14-03: whatever the item is, a state left under its name is not its own.
                if (store.loadActivationState(fullName) != null) {
                    deleteState(fullName);
                }
                return;
            }
            String identity = ItemIdentity.of(item.getRootDir());
            if (BatchControlGlobalConfiguration.get().isRunControlEnabled()) {
                saveState(ActivationState.notActivated(fullName, identity));
                return;
            }
            saveState(ActivationState.activated(fullName, ActivationState.UNCONTROLLED, BatchClock.now(), null,
                    identity));
            store.appendChangeRecord(ChangeRecord.create(ChangeType.ACTIVATED, fullName,
                    ActivationState.UNCONTROLLED, "Activated at creation: '" + fullName + "' was created while "
                            + "run control was off, so it counts as in service"));
        } finally {
            lock.unlock();
        }
    }

    /** Job form of {@link #onItemCreated(Item)}. */
    public void onJobCreated(Job<?, ?> job) {
        onItemCreated(job);
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
        // ACL.SYSTEM2 switch (S-13-11): seeding must see every job and computed folder whoever the
        // calling thread runs as. No permission check precedes it because nothing is decided for a
        // user here: it only records the items that exist at first install, once.
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            return seedAsSystem();
        }
    }

    private int seedAsSystem() {
        lock.lock();
        try {
            if (store.isActivationSchemaMarked()) {
                return -1;
            }
            Instant now = BatchClock.now();
            int seeded = 0;
            for (Item item : Jenkins.get().allItems(Item.class)) {
                if (!isSubject(item)) {
                    continue;
                }
                String fullName = item.getFullName();
                if (store.loadActivationState(fullName) != null) {
                    continue;
                }
                saveState(ActivationState.activated(fullName, ActivationState.UPGRADE, now, null,
                        ItemIdentity.of(item.getRootDir())));
                store.appendChangeRecord(ChangeRecord.create(ChangeType.ACTIVATED, fullName,
                        ActivationState.UPGRADE, "Activated by upgrade: the job existed when activation "
                                + "approval was installed, so its schedule keeps running"));
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
        // S-13-04/05: the cache is reset before the write, so a failed write can only leave the
        // item not activated in memory, never activated.
        setCached(state.getJobFullName(), Cached.NOT_ACTIVATED);
        store.saveActivationState(state);
        setCached(state.getJobFullName(), Cached.of(state));
    }

    /**
     * Removes the stored state (S-13-05): the cache says "not activated" first, and when the file
     * cannot be deleted an explicit not-activated state is written over it, so a failure never
     * leaves the item activated.
     */
    private void deleteState(String fullName) {
        setCached(fullName, Cached.NOT_ACTIVATED);
        try {
            store.deleteActivationState(fullName);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, e, () -> "Could not delete the activation state of '" + fullName
                    + "'; overwriting it with a not-activated state");
            try {
                store.saveActivationState(ActivationState.notActivated(fullName, null));
            } catch (RuntimeException again) {
                e.addSuppressed(again);
                LOGGER.log(Level.SEVERE, e, () -> "The activation state of '" + fullName + "' could neither be "
                        + "deleted nor overwritten; it stays not activated until the next restart reads the file");
            }
        }
    }

    private void setCached(String fullName, Cached value) {
        synchronized (cacheMonitor) {
            generation.incrementAndGet();
            cache().put(fullName, value);
        }
    }

    /** Ends the PENDING requests of one job as INVALIDATED; under {@link #lock}. */
    private void invalidatePending(String jobFullName, String reason) {
        for (ActivationRequest request : store.listOpenActivationRequests()) {
            if (request.getStatus() == RequestStatus.PENDING && request.getJobFullName().equals(jobFullName)) {
                request.setStatus(RequestStatus.INVALIDATED);
                request.setDecisionComment(reason);
                request.setDecidedAt(BatchClock.now());
                store.saveActivationRequest(request);
                NotificationDispatcher.activationEnded(NotificationEvent.INVALIDATED, request, true, reason);
                LOGGER.info(() -> "Activation request " + request.getId() + " invalidated: " + reason);
            }
        }
    }

    /** The cache of the current Jenkins session; a new session (restart, next test) starts empty. */
    private Map<String, Cached> cache() {
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
