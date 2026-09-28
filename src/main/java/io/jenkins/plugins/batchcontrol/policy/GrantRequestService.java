package io.jenkins.plugins.batchcontrol.policy;

import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Job;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.Approvers;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.CreateNamePattern;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.ops.NotificationDispatcher;
import io.jenkins.plugins.batchcontrol.ops.NotificationEvent;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import io.jenkins.plugins.batchcontrol.store.Store;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.springframework.security.access.AccessDeniedException;

/**
 * The single entry point for every {@link GrantRequest} state transition (SPEC items 3 and 8).
 * No other class may change a grant request's status.
 *
 * <p><b>Concurrency (D-20 discipline, same as {@link RunRequestService})</b>: every
 * load→validate→transition→persist sequence runs under one {@link ReentrantLock}, which makes
 * each transition an effective compare-and-set together with the store's atomic rewrite.
 * Approval creates and registers the {@link Grant} inside the same critical section, so a
 * request can never yield two grants.
 *
 * <p><b>Failure families</b>: {@link IllegalArgumentException} for input validation,
 * {@link IllegalStateException} for wrong-state transitions, {@link AccessDeniedException}
 * (403 on the web layer) for authorization refusals.
 *
 * <p><b>S-15</b>: {@link #create} and {@link #approve} refuse outright while change control is off,
 * because the switch is a kill switch and a window approved while it is off would otherwise take
 * effect unreviewed the moment it was turned back on. See {@code checkChangeControlEnabled}.
 */
@Restricted(NoExternalUse.class)
public final class GrantRequestService {

    private static final Logger LOGGER = Logger.getLogger(GrantRequestService.class.getName());

    /** Same reason size cap as run requests (D-22). */
    private static final int MAX_REASON_LENGTH = 4000;

    private static final GrantRequestService INSTANCE = new GrantRequestService();

    private final ReentrantLock lock = new ReentrantLock();
    private final Store store = FileStore.get();

    private GrantRequestService() {
    }

    public static GrantRequestService get() {
        return INSTANCE;
    }

    // ---------------------------------------------------------------- read API

    /** Loads a grant request by id, or {@code null}. */
    public GrantRequest load(String id) {
        return store.loadGrantRequest(id);
    }

    /** All stored grant requests, in creation order. */
    public List<GrantRequest> list() {
        return store.listGrantRequests();
    }

    // ---------------------------------------------------------------- creation (SPEC 3, 8)

    /**
     * Creates a PENDING grant request. The requester is the current authentication; validation
     * failures throw {@link IllegalArgumentException}.
     *
     * <p>Rules: at least one action; duration in {@code (0, maxGrantMinutes]}; non-empty reason
     * of at most {@value #MAX_REASON_LENGTH} characters; the approver must be on the global
     * list and must not be the requester (admin exception per the self-approval policy); the
     * scope target must exist (a job for JOB scope, a folder for FOLDER scope).
     */
    public GrantRequest create(GrantScope scope, List<GrantAction> actions, int durationMinutes,
                               String reason, String approver) {
        return create(scope, actions, durationMinutes, reason, Approvers.of(approver), null);
    }

    /** Approver-set form without a name restriction (D-37). */
    public GrantRequest create(GrantScope scope, List<GrantAction> actions, int durationMinutes,
                               String reason, List<String> approvers) {
        return create(scope, actions, durationMinutes, reason, approvers, null);
    }

    /**
     * Creates a PENDING grant request designating an approver set (D-37) and, for CREATE, an
     * optional name restriction (D-40): an exact item name or a {@code /regex/} matched against
     * the whole name of the new item. A blank restriction means none; an invalid one (bad regex,
     * invalid item name, over {@value CreateNamePattern#MAX_LENGTH} characters) is refused with
     * {@link IllegalArgumentException}, as is a restriction on a request without CREATE.
     */
    public GrantRequest create(GrantScope scope, List<GrantAction> actions, int durationMinutes,
                               String reason, List<String> approvers, String createNamePattern) {
        Objects.requireNonNull(scope, "scope");
        // S-15, refused before any other validation: the switch being off is a precondition of the
        // whole feature rather than a property of this request, so it must not depend on the request
        // being well-formed, and the caller should read "change control is off" rather than a
        // complaint about a field of a request that could never have taken effect anyway.
        checkChangeControlEnabled("requested", scope.getFullName());
        // The RequestGrant permission is enforced by the HTTP layer (GrantsSection.doCreate,
        // T-08-13); the service stays callable by internal flows acting for a named requester.
        String requester = Jenkins.getAuthentication2().getName();

        if (actions == null || actions.isEmpty()) {
            throw new IllegalArgumentException("At least one action (CREATE, CONFIGURE, DELETE) "
                    + "must be requested.");
        }
        int max = BatchControlGlobalConfiguration.get().getMaxGrantMinutes();
        if (durationMinutes <= 0) {
            throw new IllegalArgumentException("The grant duration must be a positive number "
                    + "of minutes.");
        }
        if (durationMinutes > max) {
            throw new IllegalArgumentException("The grant duration must not exceed "
                    + max + " minutes (maxGrantMinutes).");
        }
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("A reason is required to create a grant request.");
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("The reason must not exceed "
                    + MAX_REASON_LENGTH + " characters.");
        }
        // Approver rules mirror run requests; there is no per-job approver restriction here.
        List<String> designated = ApprovalPolicy.checkDesignation(requester, approvers, null);
        String pattern = CreateNamePattern.normalize(createNamePattern);
        if (pattern != null) {
            if (!actions.contains(GrantAction.CREATE)) {
                throw new IllegalArgumentException("A name restriction applies only to a request "
                        + "that includes CREATE.");
            }
            CreateNamePattern.parse(pattern); // D-40: validated at submission
        }
        checkScopeExists(scope);

        GrantRequest request = GrantRequest.create(scope, actions, durationMinutes, reason,
                requester, designated, pattern);
        lock.lock();
        try {
            store.saveGrantRequest(request);
        } finally {
            lock.unlock();
        }
        NotificationDispatcher.grant(NotificationEvent.REQUEST_CREATED, request);
        return request;
    }

    /**
     * The scope target must exist so approvers never approve a window on a phantom path.
     *
     * <p>S-03: an empty full name is rejected for every scope type. A root scope would be
     * instance-wide, which SPEC item 8 never defines; root-scope grants stay impossible until a
     * deliberate DECISIONS entry introduces them.
     *
     * <p>S-13: this runs both at creation and again at approval, so a request whose target was
     * deleted or renamed in between — or one persisted by a build that predates this rule —
     * cannot turn into a live grant. {@code GrantScope.includes} additionally matches nothing
     * for an empty scope name, so even a hand-edited store file cannot confer anything.
     */
    private static void checkScopeExists(GrantScope scope) {
        String fullName = scope.getFullName();
        if (fullName == null || fullName.isEmpty()) {
            throw new IllegalArgumentException("root-scope grants are not supported");
        }
        if (scope.getType() == GrantScope.Type.JOB) {
            Item item = Jenkins.get().getItemByFullName(fullName);
            if (!(item instanceof Job)) {
                throw new IllegalArgumentException("No such job: '" + fullName + "'.");
            }
        } else {
            Item item = Jenkins.get().getItemByFullName(fullName);
            if (!(item instanceof ItemGroup)) {
                throw new IllegalArgumentException("No such folder: '" + fullName + "'.");
            }
        }
    }

    // ---------------------------------------------------------------- decisions (SPEC 3, 8)

    /**
     * Approves a PENDING grant request and creates the grant. The caller must be the designated
     * approver holding the Approve permission (or the admin self-approval path). The grant
     * window is {@code [now, now + durationMinutes)} on the {@link BatchClock}; SPEC item 8:
     * the requester holds the permissions immediately.
     *
     * <p>The stored scope is re-validated here (S-13), so approval fails with
     * {@link IllegalArgumentException} if the target no longer exists or the scope is a root
     * scope. The lookup is caller-scoped like every other item lookup in this service: the
     * approver must be able to see the scope target to approve a window on it.
     *
     * @return the created, immediately effective {@link Grant}
     */
    public Grant approve(String id, String comment) {
        Grant created;
        GrantRequest approved;
        lock.lock();
        try {
            GrantRequest request = require(id);
            // S-15: after require(id), so the refusal record names the scope the window was for and
            // the history stays queryable by job; before every other check, because an approval that
            // cannot confer anything should not turn on whether the request is still PENDING or the
            // caller happens to be its designated approver. Existence was already disclosed to any
            // caller by require(id) before this change, so nothing new leaks.
            checkChangeControlEnabled("approved", request.getScope().getFullName());
            if (request.getStatus() != RequestStatus.PENDING) {
                throw new IllegalStateException("Grant request " + id + " is "
                        + request.getStatus() + " and can no longer be approved.");
            }
            ApprovalPolicy.checkDecision(request.getId(), request.getRequester(), request.getApprovers());
            // S-13: re-validate the stored scope before it becomes a live grant. Creation-time
            // validation does not bind a request that was persisted earlier (or whose target has
            // since been deleted or renamed), and approval is the last point where a bad scope
            // can still be stopped. Deliberately after checkDecision, so a non-approver learns
            // nothing about the scope's validity.
            checkScopeExists(request.getScope());
            Instant now = BatchClock.now();
            if (pendingExpired(request, now)) {
                request.setStatus(RequestStatus.EXPIRED);
                store.saveGrantRequest(request);
                throw new IllegalStateException("Grant request " + id
                        + " passed its pending timeout and is now EXPIRED.");
            }
            request.setStatus(RequestStatus.APPROVED);
            request.setDecidedAt(now);
            request.setDecidedBy(Jenkins.getAuthentication2().getName());
            request.setDecisionComment(comment);
            store.saveGrantRequest(request);
            Grant grant = Grant.createFor(request, now);
            // Registration persists the grant and makes it effective in the same critical
            // section, so approval and effectiveness are atomic.
            GrantService.get().register(grant);
            LOGGER.info(() -> "Grant " + grant.getId() + " created for user '" + grant.getUser()
                    + "' on " + grant.getScope() + " until " + grant.getExpiresAt());
            created = grant;
            approved = request;
        } finally {
            lock.unlock();
        }
        NotificationDispatcher.grant(NotificationEvent.APPROVED, approved);
        return created;
    }

    /** Rejects a PENDING grant request; the comment is mandatory (SPEC item 5 rule reused). */
    public GrantRequest reject(String id, String comment) {
        if (comment == null || comment.trim().isEmpty()) {
            throw new IllegalArgumentException("A comment is required to reject a grant request.");
        }
        GrantRequest request;
        lock.lock();
        try {
            request = require(id);
            if (request.getStatus() != RequestStatus.PENDING) {
                throw new IllegalStateException("Grant request " + id + " is "
                        + request.getStatus() + " and can no longer be rejected.");
            }
            ApprovalPolicy.checkDecision(request.getId(), request.getRequester(), request.getApprovers());
            request.setStatus(RequestStatus.REJECTED);
            request.setDecidedAt(BatchClock.now());
            request.setDecidedBy(Jenkins.getAuthentication2().getName());
            request.setDecisionComment(comment);
            store.saveGrantRequest(request);
        } finally {
            lock.unlock();
        }
        NotificationDispatcher.grant(NotificationEvent.REJECTED, request);
        return request;
    }

    /** Single-approver form of {@link #changeApprovers(String, List)}. */
    public GrantRequest changeApprover(String id, String newApprover) {
        return changeApprovers(id, Approvers.of(newApprover));
    }

    /**
     * Replaces the designated approver set of a PENDING grant request; requester only (SPEC
     * item 3 rules reused, D-26, D-37). Recorded as (previous set, new set, changed by, time).
     */
    public GrantRequest changeApprovers(String id, List<String> newApprovers) {
        String caller = Jenkins.getAuthentication2().getName();
        GrantRequest request;
        lock.lock();
        try {
            request = require(id);
            if (!Approvers.sameUser(caller, request.getRequester())) {
                throw new AccessDeniedException(
                        "Only the requester may change the approvers of grant request " + id + ".");
            }
            if (request.getStatus() != RequestStatus.PENDING) {
                throw new IllegalStateException("Grant request " + id + " is "
                        + request.getStatus() + "; the approvers can only be changed while PENDING.");
            }
            List<String> designated = ApprovalPolicy.checkDesignation(request.getRequester(), newApprovers, null);
            request.addApproverChange(new GrantRequest.ApproverChange(
                    request.getApprovers(), designated, caller, BatchClock.now()));
            request.setApprovers(designated);
            store.saveGrantRequest(request);
        } finally {
            lock.unlock();
        }
        NotificationDispatcher.grant(NotificationEvent.APPROVERS_CHANGED, request);
        return request;
    }

    /** Cancels a PENDING grant request; requester or a Manage holder only (SPEC item 7 rule). */
    public GrantRequest cancel(String id) {
        String caller = Jenkins.getAuthentication2().getName();
        lock.lock();
        try {
            GrantRequest request = require(id);
            if (!Approvers.sameUser(caller, request.getRequester())
                    && !Jenkins.get().hasPermission(BatchControlPermissions.MANAGE)) {
                throw new AccessDeniedException(
                        "Only the requester or a Manage holder may cancel grant request " + id + ".");
            }
            if (request.getStatus() != RequestStatus.PENDING) {
                throw new IllegalStateException("Grant request " + id + " is "
                        + request.getStatus() + "; only PENDING requests can be cancelled.");
            }
            request.setStatus(RequestStatus.CANCELLED);
            store.saveGrantRequest(request);
            return request;
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------- expiry (SPEC 7, 8)

    /**
     * Expires PENDING grant requests past {@code pendingTimeoutHours}. Called by the periodic
     * work; expiry truth stays the clock comparison (a late approval attempt expires the
     * request on its own, see {@link #approve}).
     */
    public void expireOverduePending() {
        Instant now = BatchClock.now();
        for (GrantRequest snapshot : store.listGrantRequests()) {
            if (snapshot.getStatus() != RequestStatus.PENDING) {
                continue;
            }
            lock.lock();
            try {
                GrantRequest request = store.loadGrantRequest(snapshot.getId());
                if (request != null && request.getStatus() == RequestStatus.PENDING
                        && pendingExpired(request, now)) {
                    request.setStatus(RequestStatus.EXPIRED);
                    store.saveGrantRequest(request);
                    LOGGER.info(() -> "Grant request " + request.getId()
                            + " expired (pending timeout)");
                }
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * D-36: sends {@link NotificationEvent#EXPIRING} once for every PENDING grant request whose
     * pending timeout falls within {@code notifyBeforeExpiryMinutes}, and
     * {@link NotificationEvent#GRANT_EXPIRING} once for every active window ending within it. The
     * "notified" flags are persisted before dispatch, so a restart never resends.
     */
    public void notifyExpiring() {
        Instant now = BatchClock.now();
        Duration lead = Duration.ofMinutes(
                BatchControlGlobalConfiguration.get().getNotifyBeforeExpiryMinutes());
        for (GrantRequest snapshot : store.listGrantRequests()) {
            if (snapshot.getStatus() != RequestStatus.PENDING || snapshot.isExpiringNotified()) {
                continue;
            }
            GrantRequest notified = null;
            lock.lock();
            try {
                GrantRequest request = store.loadGrantRequest(snapshot.getId());
                if (request != null && request.getStatus() == RequestStatus.PENDING
                        && !request.isExpiringNotified()) {
                    Instant expiresAt = pendingExpiry(request);
                    if (now.isBefore(expiresAt) && !now.isBefore(expiresAt.minus(lead))) {
                        request.setExpiringNotified(true);
                        store.saveGrantRequest(request);
                        notified = request;
                    }
                }
            } finally {
                lock.unlock();
            }
            if (notified != null) {
                NotificationDispatcher.grant(NotificationEvent.EXPIRING, notified);
            }
        }
        for (Grant grant : GrantService.get().claimExpiringNotifications(lead)) {
            GrantRequest request = store.loadGrantRequest(grant.getGrantRequestId() != null
                    ? grant.getGrantRequestId() : grant.getId());
            NotificationDispatcher.grantExpiring(grant, request == null ? null : request.getReason());
        }
    }

    // ---------------------------------------------------------------- internals

    /**
     * S-15: refuses the transition while change control is off, and records the attempt.
     *
     * <p>The change-control switch is a kill switch. With it off a window confers nothing
     * ({@code security.GrantAwareACL}) and the windows that were open when it was flipped have been
     * revoked ({@code security.GrantService#revokeAllActive}). Letting a window still be requested
     * and approved would put the removed state straight back, only displaced in time: a window
     * approved while the switch is off would spring to life the moment it is turned back on, having
     * been reviewed by nobody at that point. Refusing at the source leaves nothing to resurrect,
     * which is why this is preferred over sweeping again when the switch goes on.
     *
     * <p>Only {@code create} and {@code approve} are gated. {@code reject}, {@code cancel} and the
     * pending-expiry sweep all <em>close</em> requests, and refusing those would strand every
     * pending request for as long as the switch is off, with nothing gained — a closed request
     * confers nothing either way.
     *
     * <p>{@link IllegalStateException} rather than {@link AccessDeniedException} on purpose: per
     * this class's failure families, the caller's authorization is not in question (the HTTP layer
     * already checked {@code RequestGrant}/{@code Approve}) — the instance is in a state where the
     * transition does not exist. Both web entry points already turn this family into a
     * {@code hudson.model.Failure} carrying the message, so the user reads the reason rather than a
     * bare 500 and no UI change is needed.
     *
     * @param attemptedTransition past participle used in the message and the record
     *                            ("requested", "approved")
     * @param scopeFullName the scope the window was for; becomes the record's target
     * @throws IllegalStateException if change control is off
     */
    private static void checkChangeControlEnabled(String attemptedTransition, String scopeFullName) {
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        if (cfg.isChangeControlEnabled()) {
            return;
        }
        String user = Jenkins.getAuthentication2().getName();
        String target = scopeFullName == null || scopeFullName.isEmpty()
                ? "(no scope)" : scopeFullName;
        // D-13 / SPEC item 9 last criterion: with BOTH switches off the plugin writes nothing at
        // all, so the record follows the same activity gate every other record does. The refusal
        // itself still applies — it is the recording that is conditional, not the behaviour.
        if (cfg.isRunControlEnabled()) {
            ChangeRecord record = ChangeRecord.create(ChangeType.GRANT_REQUEST_BLOCKED, target, user,
                    "A permission window for '" + target + "' could not be " + attemptedTransition
                            + ": change control is off");
            FileStore.get().appendChangeRecord(record);
        }
        LOGGER.info(() -> "Refused to let '" + user + "' have a permission window for '" + target
                + "' " + attemptedTransition + ": change control is off (S-15)");
        throw new IllegalStateException("Change control is off, so permission windows cannot be "
                + attemptedTransition + ". While the switch is off a window would confer nothing, "
                + "and it would take effect unreviewed as soon as the switch was turned back on. "
                + "Ask an administrator to enable change control first.");
    }

    private GrantRequest require(String id) {
        GrantRequest request = store.loadGrantRequest(id);
        if (request == null) {
            throw new IllegalArgumentException("No such grant request: " + id);
        }
        return request;
    }

    private static boolean pendingExpired(GrantRequest request, Instant now) {
        return now.isAfter(pendingExpiry(request));
    }

    private static Instant pendingExpiry(GrantRequest request) {
        Duration timeout = Duration.ofHours(
                BatchControlGlobalConfiguration.get().getPendingTimeoutHours());
        return request.getCreatedAt().plus(timeout);
    }
}
