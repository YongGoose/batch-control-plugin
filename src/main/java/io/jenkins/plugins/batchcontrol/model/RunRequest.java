package io.jenkins.plugins.batchcontrol.model;

import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.Ids;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * A request to run a specific job with a fixed set of parameters (SPEC section 3).
 * Persisted as XStream XML at {@code requests/run/<id>.xml}.
 *
 * <p>D-72, D-74: {@code parameters} is the masked display map derived from the submitted
 * {@link hudson.model.ParameterValue}s once at submission, the only form in which a request's
 * parameters are shown or written anywhere else. The typed values themselves are not part of this
 * object: the store keeps them in their own file ({@code requests/run/<id>.values.xml}), read only
 * to approve, submit, recover or dispose of the request, and deleted when the approved run starts
 * or the request ends.
 *
 * <p>Timestamps are persisted as epoch milliseconds ({@code long}) because the Jenkins XStream
 * class filter does not allow {@code java.time.Instant}; the accessors expose {@link Instant}.
 *
 * <p>Status transitions must only be performed by the policy services
 * ({@code policy.RunRequestService}); other code must not call {@link #setStatus}.
 */
@Restricted(NoExternalUse.class)
public final class RunRequest {

    /**
     * One approver change entry: (previous set, new set, changed by, at) (SPEC item 3, D-37).
     */
    public static final class ApproverChange {
        private final List<String> fromApprovers;
        private final List<String> toApprovers;
        private final String by;
        private final long atMillis;

        public ApproverChange(List<String> from, List<String> to, String by, Instant at) {
            this.fromApprovers = from == null ? new ArrayList<>() : new ArrayList<>(from);
            this.toApprovers = to == null ? new ArrayList<>() : new ArrayList<>(to);
            this.by = by;
            this.atMillis = Objects.requireNonNull(at, "at").toEpochMilli();
        }

        /** The previous designated set. */
        public List<String> getFromApprovers() {
            return fromApprovers == null ? new ArrayList<>() : new ArrayList<>(fromApprovers);
        }

        /** The new designated set. */
        public List<String> getToApprovers() {
            return toApprovers == null ? new ArrayList<>() : new ArrayList<>(toApprovers);
        }

        /** Compatibility view: the first member of the previous set, or {@code null}. */
        public String getFrom() {
            return fromApprovers == null || fromApprovers.isEmpty() ? null : fromApprovers.get(0);
        }

        /** Compatibility view: the first member of the new set, or {@code null}. */
        public String getTo() {
            return toApprovers == null || toApprovers.isEmpty() ? null : toApprovers.get(0);
        }

        public String getBy() {
            return by;
        }

        public Instant getAt() {
            return Instant.ofEpochMilli(atMillis);
        }
    }

    private final String id;
    private final String jobFullName;
    /** D-72: the masked display map derived from the submitted typed values. */
    private final Map<String, String> parameters;
    private final String reason;
    private final String requester;
    /** The designated approver set (D-37); any member may decide. */
    private List<String> approvers;
    /** The approver who approved or rejected the request (D-37); {@code null} until decided. */
    private String decidedBy;
    /** D-36: the EXPIRING notification was sent (persisted so a restart does not resend). */
    private boolean expiringNotified;
    private RequestStatus status;
    private final long createdAtMillis;
    private Long decidedAtMillis;
    private String decisionComment;
    private boolean selfApproved;
    private List<ApproverChange> approverChanges = new ArrayList<>();
    private String incidentId;
    private String executedRunId;
    /** When the approved build started (EXECUTED); {@code null} for requests stored before it existed. */
    private Long executedAtMillis;
    /**
     * Consumption ticket (D-23): the instant the approval marker was consumed by a queue
     * submission, or {@code null} while the ticket is still unused. Only the policy service
     * claims or re-issues it.
     */
    private Long queuedAtMillis;
    /**
     * Base instant for the approved-run timeout judgment. Set to {@code decidedAt} on approval
     * and moved forward to the recovery instant by startup recovery (SPEC item 7: downtime is
     * not counted against {@code approvedRunTimeoutMinutes}).
     */
    private Long expiryBaseMillis;
    /** Human-readable history note for an INVALIDATED request (D-21: target renamed/moved). */
    private String invalidationReason;
    /**
     * D-72b (7), security-35 S-35-07: when the queue item of the approved run was cancelled, or
     * {@code null}. Such a run is never submitted again (startup recovery skips it), and its
     * files are disposed of when the request ends.
     */
    private Long queueCancelledAtMillis;
    private RunRequest(String id, String jobFullName, Map<String, String> parameters, String reason,
                       String requester, List<String> approvers, RequestStatus status, Instant createdAt) {
        this.id = id;
        this.jobFullName = jobFullName;
        this.parameters = new LinkedHashMap<>(parameters);
        this.reason = reason;
        this.requester = requester;
        this.approvers = Approvers.normalize(approvers);
        this.status = status;
        this.createdAtMillis = createdAt.toEpochMilli();
    }

    /**
     * Creates a new PENDING request with a random UUID id (D-68) and its creation time from
     * {@link BatchClock}, holding the masked display map {@code parameters} (D-72). The typed values
     * the map was derived from are stored next to it by the store (D-74).
     */
    public static RunRequest create(String jobFullName, Map<String, String> parameters, String reason,
                                    String requester, List<String> approvers) {
        Objects.requireNonNull(jobFullName, "jobFullName");
        Objects.requireNonNull(parameters, "parameters");
        return new RunRequest(Ids.newRequestId(), jobFullName, parameters, reason, requester, approvers,
                RequestStatus.PENDING, BatchClock.now());
    }

    /** Single-approver form of {@link #create(String, Map, String, String, List)}. */
    public static RunRequest create(String jobFullName, Map<String, String> parameters, String reason,
                                    String requester, String approver) {
        return create(jobFullName, parameters, reason, requester, Approvers.of(approver));
    }

    /** XStream does not run field initialisers; an absent change list loads as empty. */
    private Object readResolve() {
        if (approverChanges == null) {
            approverChanges = new ArrayList<>();
        }
        return this;
    }

    public String getId() {
        return id;
    }

    public String getJobFullName() {
        return jobFullName;
    }

    /**
     * The masked display map (D-72): a sensitive value is {@code ********}, a file value
     * {@code [file] <original file name>}. A defensive copy; it never changes after creation.
     */
    public Map<String, String> getParameters() {
        return parameters == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parameters);
    }

    public String getReason() {
        return reason;
    }

    public String getRequester() {
        return requester;
    }

    /** The designated approver set (D-37), in designation order. */
    public List<String> getApprovers() {
        return approvers == null ? new ArrayList<>() : new ArrayList<>(approvers);
    }

    /**
     * Compatibility view (D-37): the first designated approver, or {@code null} with none.
     * New code uses {@link #getApprovers()} and {@link #getDecidedBy()}.
     */
    public String getApprover() {
        return approvers == null || approvers.isEmpty() ? null : approvers.get(0);
    }

    /** Whether {@code userId} is a member of the designated set. */
    public boolean isDesignatedApprover(String userId) {
        return Approvers.contains(approvers, userId);
    }

    /**
     * The user who closed the request: the approver who approved or rejected it, or the user who
     * cancelled it (e2e-03 DEF-13); {@code null} while undecided.
     */
    public String getDecidedBy() {
        return decidedBy;
    }

    /** Whether the D-36 EXPIRING notification was already sent. */
    public boolean isExpiringNotified() {
        return expiringNotified;
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

    public boolean isSelfApproved() {
        return selfApproved;
    }

    public List<ApproverChange> getApproverChanges() {
        return approverChanges == null ? new ArrayList<>() : new ArrayList<>(approverChanges);
    }

    public String getIncidentId() {
        return incidentId;
    }

    public String getExecutedRunId() {
        return executedRunId;
    }

    /** Only the policy services may transition the status. */
    public void setStatus(RequestStatus status) {
        this.status = Objects.requireNonNull(status, "status");
    }

    /** Only the policy services change the designated set. */
    public void setApprovers(List<String> approvers) {
        this.approvers = Approvers.normalize(approvers);
    }

    /** Only the policy services record the deciding approver. */
    public void setDecidedBy(String decidedBy) {
        this.decidedBy = decidedBy;
    }

    /** Only the policy services mark the D-36 EXPIRING notification as sent. */
    public void setExpiringNotified(boolean expiringNotified) {
        this.expiringNotified = expiringNotified;
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAtMillis = decidedAt == null ? null : decidedAt.toEpochMilli();
    }

    public void setDecisionComment(String decisionComment) {
        this.decisionComment = decisionComment;
    }

    public void setSelfApproved(boolean selfApproved) {
        this.selfApproved = selfApproved;
    }

    public void addApproverChange(ApproverChange change) {
        if (approverChanges == null) {
            approverChanges = new ArrayList<>();
        }
        approverChanges.add(Objects.requireNonNull(change, "change"));
    }

    public void setIncidentId(String incidentId) {
        this.incidentId = incidentId;
    }

    public void setExecutedRunId(String executedRunId) {
        this.executedRunId = executedRunId;
    }

    /** The instant the approved build started, or {@code null} if not executed (or stored before). */
    public Instant getExecutedAt() {
        return executedAtMillis == null ? null : Instant.ofEpochMilli(executedAtMillis);
    }

    public void setExecutedAt(Instant executedAt) {
        this.executedAtMillis = executedAt == null ? null : executedAt.toEpochMilli();
    }

    /** The instant the consumption ticket was claimed, or {@code null} if still unused. */
    public Instant getQueuedAt() {
        return queuedAtMillis == null ? null : Instant.ofEpochMilli(queuedAtMillis);
    }

    /** Only the policy service claims ({@code non-null}) or re-issues ({@code null}) the ticket. */
    public void setQueuedAt(Instant queuedAt) {
        this.queuedAtMillis = queuedAt == null ? null : queuedAt.toEpochMilli();
    }

    /** Base instant for the approved-run timeout; falls back to {@link #getDecidedAt()}. */
    public Instant getExpiryBase() {
        if (expiryBaseMillis != null) {
            return Instant.ofEpochMilli(expiryBaseMillis);
        }
        return getDecidedAt();
    }

    public void setExpiryBase(Instant expiryBase) {
        this.expiryBaseMillis = expiryBase == null ? null : expiryBase.toEpochMilli();
    }

    public String getInvalidationReason() {
        return invalidationReason;
    }

    /**
     * D-72b (7): when the queue item of the approved run was cancelled, or {@code null}. The
     * request stays APPROVED until the approved-run timeout ends it; it is never submitted again.
     */
    public Instant getQueueCancelledAt() {
        return queueCancelledAtMillis == null ? null : Instant.ofEpochMilli(queueCancelledAtMillis);
    }

    /** Only the policy service records the cancellation of the approved run's queue item. */
    public void setQueueCancelledAt(Instant queueCancelledAt) {
        this.queueCancelledAtMillis = queueCancelledAt == null ? null : queueCancelledAt.toEpochMilli();
    }

    public void setInvalidationReason(String invalidationReason) {
        this.invalidationReason = invalidationReason;
    }
}
