package io.jenkins.plugins.batchcontrol.model;

import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.Ids;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * A request for a temporary (JIT) change permission (SPEC item 8, section 3).
 * Persisted as XStream XML at {@code requests/grant/<id>.xml}.
 *
 * <p>Timestamps are persisted as epoch milliseconds ({@code long}) because the Jenkins XStream
 * class filter does not allow {@code java.time.Instant}; the accessors expose {@link Instant}.
 *
 * <p>Status transitions must only be performed by the policy services
 * ({@code policy.GrantRequestService}); other code must not call {@link #setStatus}.
 */
@Restricted(NoExternalUse.class)
public final class GrantRequest {

    private final String id;
    private final GrantScope scope;
    private final List<GrantAction> actions;
    private final int durationMinutes;
    private final String reason;
    private final String requester;
    private final String approver;
    private RequestStatus status;
    private final long createdAtMillis;
    private Long decidedAtMillis;
    private String decisionComment;

    private GrantRequest(String id, GrantScope scope, List<GrantAction> actions, int durationMinutes,
                         String reason, String requester, String approver, RequestStatus status,
                         Instant createdAt) {
        this.id = id;
        this.scope = scope;
        this.actions = new ArrayList<>(actions);
        this.durationMinutes = durationMinutes;
        this.reason = reason;
        this.requester = requester;
        this.approver = approver;
        this.status = status;
        this.createdAtMillis = createdAt.toEpochMilli();
    }

    /**
     * Creates a new PENDING grant request. The id and creation time come from
     * {@link BatchClock} (id format {@code yyyyMMdd-HHmmss-<6 random alnum>}).
     * Duplicate actions are collapsed while preserving order.
     */
    public static GrantRequest create(GrantScope scope, List<GrantAction> actions, int durationMinutes,
                                      String reason, String requester, String approver) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(actions, "actions");
        List<GrantAction> distinct = new ArrayList<>(new LinkedHashSet<>(actions));
        return new GrantRequest(Ids.newId(), scope, distinct, durationMinutes, reason,
                requester, approver, RequestStatus.PENDING, BatchClock.now());
    }

    public String getId() {
        return id;
    }

    public GrantScope getScope() {
        return scope;
    }

    /** A defensive copy; the requested actions never change after creation. */
    public List<GrantAction> getActions() {
        return actions == null ? new ArrayList<>() : new ArrayList<>(actions);
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public String getReason() {
        return reason;
    }

    public String getRequester() {
        return requester;
    }

    public String getApprover() {
        return approver;
    }

    public RequestStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return Instant.ofEpochMilli(createdAtMillis);
    }

    public Instant getDecidedAt() {
        return decidedAtMillis == null ? null : Instant.ofEpochMilli(decidedAtMillis);
    }

    public String getDecisionComment() {
        return decisionComment;
    }

    /** Only the policy services may transition the status. */
    public void setStatus(RequestStatus status) {
        this.status = Objects.requireNonNull(status, "status");
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAtMillis = decidedAt == null ? null : decidedAt.toEpochMilli();
    }

    public void setDecisionComment(String decisionComment) {
        this.decisionComment = decisionComment;
    }
}
