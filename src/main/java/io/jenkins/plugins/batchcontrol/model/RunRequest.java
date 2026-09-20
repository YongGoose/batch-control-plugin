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
 * <p>Timestamps are persisted as epoch milliseconds ({@code long}) because the Jenkins XStream
 * class filter does not allow {@code java.time.Instant}; the accessors expose {@link Instant}.
 *
 * <p>Status transitions must only be performed by the policy services
 * ({@code policy.RunRequestService}); other code must not call {@link #setStatus}.
 */
@Restricted(NoExternalUse.class)
public final class RunRequest {

    /** One approver change entry: (from, to, by, at). */
    public static final class ApproverChange {
        private final String from;
        private final String to;
        private final String by;
        private final long atMillis;

        public ApproverChange(String from, String to, String by, Instant at) {
            this.from = from;
            this.to = to;
            this.by = by;
            this.atMillis = Objects.requireNonNull(at, "at").toEpochMilli();
        }

        public String getFrom() {
            return from;
        }

        public String getTo() {
            return to;
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
    private final Map<String, String> parameters;
    private final String reason;
    private final String requester;
    private String approver;
    private RequestStatus status;
    private final long createdAtMillis;
    private Long decidedAtMillis;
    private String decisionComment;
    private boolean selfApproved;
    private List<ApproverChange> approverChanges = new ArrayList<>();
    private String incidentId;
    private String executedRunId;

    private RunRequest(String id, String jobFullName, Map<String, String> parameters, String reason,
                       String requester, String approver, RequestStatus status, Instant createdAt) {
        this.id = id;
        this.jobFullName = jobFullName;
        this.parameters = new LinkedHashMap<>(parameters);
        this.reason = reason;
        this.requester = requester;
        this.approver = approver;
        this.status = status;
        this.createdAtMillis = createdAt.toEpochMilli();
    }

    /**
     * Creates a new PENDING request. The id and creation time come from
     * {@link BatchClock} (id format {@code yyyyMMdd-HHmmss-<6 random alnum>}).
     */
    public static RunRequest create(String jobFullName, Map<String, String> parameters, String reason,
                                    String requester, String approver) {
        Objects.requireNonNull(jobFullName, "jobFullName");
        Objects.requireNonNull(parameters, "parameters");
        return new RunRequest(Ids.newId(), jobFullName, parameters, reason, requester, approver,
                RequestStatus.PENDING, BatchClock.now());
    }

    public String getId() {
        return id;
    }

    public String getJobFullName() {
        return jobFullName;
    }

    /** A defensive copy; the stored parameters never change after creation. */
    public Map<String, String> getParameters() {
        return parameters == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parameters);
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

    public void setApprover(String approver) {
        this.approver = approver;
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
}
