package io.jenkins.plugins.batchcontrol.model;

import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.Ids;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * A request to put one job into service ({@link Action#ACTIVATE}) or to put it on hold
 * ({@link Action#HOLD}) (SPEC item 6a, D-39). Persisted as XStream XML at
 * {@code activation-requests/<id>.xml}.
 *
 * <p>Unlike a grant request, the subject is one specific named job and there is no window: an
 * approved request changes the job's {@link ActivationState} and is then closed.
 *
 * <p>Timestamps are persisted as epoch milliseconds, like the other request models (the Jenkins
 * XStream class filter does not allow {@code java.time.Instant}).
 *
 * <p>Status transitions are performed only by {@code policy.ActivationService}; other code must
 * not call {@link #setStatus}.
 */
@Restricted(NoExternalUse.class)
public final class ActivationRequest {

    /** What the request asks for. */
    public enum Action {
        /** Put the job into service: timer and upstream causes may run it (subject to its settings). */
        ACTIVATE,
        /** Put the job on hold: timer and upstream causes no longer run it. */
        HOLD
    }

    /** One approver change entry (D-37); same shape as {@link GrantRequest.ApproverChange}. */
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
    private final Action action;
    private final String reason;
    private final String requester;
    private List<String> approvers;
    private String decidedBy;
    private boolean selfApproved;
    private List<ApproverChange> approverChanges;
    /** D-36: the EXPIRING notification was sent (persisted so a restart does not resend). */
    private boolean expiringNotified;
    private RequestStatus status;
    private final long createdAtMillis;
    private Long decidedAtMillis;
    private String decisionComment;

    private ActivationRequest(String id, String jobFullName, Action action, String reason,
                              String requester, List<String> approvers, Instant createdAt) {
        this.id = id;
        this.jobFullName = jobFullName;
        this.action = action;
        this.reason = reason;
        this.requester = requester;
        this.approvers = Approvers.normalize(approvers);
        this.status = RequestStatus.PENDING;
        this.createdAtMillis = createdAt.toEpochMilli();
    }

    /** Creates a new PENDING request with a fresh id and the {@link BatchClock} time. */
    public static ActivationRequest create(String jobFullName, Action action, String reason,
                                           String requester, List<String> approvers) {
        Objects.requireNonNull(jobFullName, "jobFullName");
        Objects.requireNonNull(action, "action");
        return new ActivationRequest(Ids.newId(), jobFullName, action, reason, requester, approvers,
                BatchClock.now());
    }

    public String getId() {
        return id;
    }

    public String getJobFullName() {
        return jobFullName;
    }

    public Action getAction() {
        return action;
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

    /** Compatibility view: the first designated approver, or {@code null}. */
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

    /** Whether the deciding approver was the requester (administrator self-approval, SPEC item 2). */
    public boolean isSelfApproved() {
        return selfApproved;
    }

    public List<ApproverChange> getApproverChanges() {
        return approverChanges == null ? new ArrayList<>() : new ArrayList<>(approverChanges);
    }

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

    /** Only the policy service may transition the status. */
    public void setStatus(RequestStatus status) {
        this.status = Objects.requireNonNull(status, "status");
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAtMillis = decidedAt == null ? null : decidedAt.toEpochMilli();
    }

    public void setDecisionComment(String decisionComment) {
        this.decisionComment = decisionComment;
    }

    /** Only the policy service changes the designated set. */
    public void setApprovers(List<String> approvers) {
        this.approvers = Approvers.normalize(approvers);
    }

    /** Only the policy service records the deciding approver. */
    public void setDecidedBy(String decidedBy) {
        this.decidedBy = decidedBy;
    }

    public void setSelfApproved(boolean selfApproved) {
        this.selfApproved = selfApproved;
    }

    /** Only the policy service records approver changes. */
    public void addApproverChange(ApproverChange change) {
        if (approverChanges == null) {
            approverChanges = new ArrayList<>();
        }
        approverChanges.add(Objects.requireNonNull(change, "change"));
    }

    /** Only the policy service marks the D-36 EXPIRING notification as sent. */
    public void setExpiringNotified(boolean expiringNotified) {
        this.expiringNotified = expiringNotified;
    }
}
