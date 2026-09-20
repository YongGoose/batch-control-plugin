package io.jenkins.plugins.batchcontrol.policy;

import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Job;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
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
        Objects.requireNonNull(scope, "scope");
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
        ApprovalPolicy.checkDesignation(requester, approver, null);
        checkScopeExists(scope);

        GrantRequest request = GrantRequest.create(scope, actions, durationMinutes, reason,
                requester, approver);
        lock.lock();
        try {
            store.saveGrantRequest(request);
        } finally {
            lock.unlock();
        }
        return request;
    }

    /** The scope target must exist so approvers never approve a window on a phantom path. */
    private static void checkScopeExists(GrantScope scope) {
        String fullName = scope.getFullName();
        if (scope.getType() == GrantScope.Type.JOB) {
            Item item = Jenkins.get().getItemByFullName(fullName);
            if (!(item instanceof Job)) {
                throw new IllegalArgumentException("No such job: '" + fullName + "'.");
            }
        } else {
            if (fullName.isEmpty()) {
                return; // the Jenkins root is a valid folder scope
            }
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
     * @return the created, immediately effective {@link Grant}
     */
    public Grant approve(String id, String comment) {
        lock.lock();
        try {
            GrantRequest request = require(id);
            if (request.getStatus() != RequestStatus.PENDING) {
                throw new IllegalStateException("Grant request " + id + " is "
                        + request.getStatus() + " and can no longer be approved.");
            }
            ApprovalPolicy.checkDecision(request.getId(), request.getRequester(), request.getApprover());
            Instant now = BatchClock.now();
            if (pendingExpired(request, now)) {
                request.setStatus(RequestStatus.EXPIRED);
                store.saveGrantRequest(request);
                throw new IllegalStateException("Grant request " + id
                        + " passed its pending timeout and is now EXPIRED.");
            }
            request.setStatus(RequestStatus.APPROVED);
            request.setDecidedAt(now);
            request.setDecisionComment(comment);
            store.saveGrantRequest(request);
            Grant grant = Grant.createFor(request, now);
            // Registration persists the grant and makes it effective in the same critical
            // section, so approval and effectiveness are atomic.
            GrantService.get().register(grant);
            LOGGER.info(() -> "Grant " + grant.getId() + " created for user '" + grant.getUser()
                    + "' on " + grant.getScope() + " until " + grant.getExpiresAt());
            return grant;
        } finally {
            lock.unlock();
        }
    }

    /** Rejects a PENDING grant request; the comment is mandatory (SPEC item 5 rule reused). */
    public GrantRequest reject(String id, String comment) {
        if (comment == null || comment.trim().isEmpty()) {
            throw new IllegalArgumentException("A comment is required to reject a grant request.");
        }
        lock.lock();
        try {
            GrantRequest request = require(id);
            if (request.getStatus() != RequestStatus.PENDING) {
                throw new IllegalStateException("Grant request " + id + " is "
                        + request.getStatus() + " and can no longer be rejected.");
            }
            ApprovalPolicy.checkDecision(request.getId(), request.getRequester(), request.getApprover());
            request.setStatus(RequestStatus.REJECTED);
            request.setDecidedAt(BatchClock.now());
            request.setDecisionComment(comment);
            store.saveGrantRequest(request);
            return request;
        } finally {
            lock.unlock();
        }
    }

    /** Cancels a PENDING grant request; requester or a Manage holder only (SPEC item 7 rule). */
    public GrantRequest cancel(String id) {
        String caller = Jenkins.getAuthentication2().getName();
        lock.lock();
        try {
            GrantRequest request = require(id);
            if (!caller.equals(request.getRequester())
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

    // ---------------------------------------------------------------- internals

    private GrantRequest require(String id) {
        GrantRequest request = store.loadGrantRequest(id);
        if (request == null) {
            throw new IllegalArgumentException("No such grant request: " + id);
        }
        return request;
    }

    private static boolean pendingExpired(GrantRequest request, Instant now) {
        Duration timeout = Duration.ofHours(
                BatchControlGlobalConfiguration.get().getPendingTimeoutHours());
        return now.isAfter(request.getCreatedAt().plus(timeout));
    }
}
