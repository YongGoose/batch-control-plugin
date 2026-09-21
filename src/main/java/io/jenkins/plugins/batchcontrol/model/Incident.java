package io.jenkins.plugins.batchcontrol.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * An automatically registered error case (SPEC items 11 and section 3): opened for every build
 * whose result is in the configured {@code incidentResults}, then handled by people
 * ({@code OPEN -> ACKNOWLEDGED -> RESOLVED}).
 *
 * <p>Persisted as XStream XML at {@code incidents/<id>.xml} with a monthly JSONL index at
 * {@code incidents/index/YYYY-MM.jsonl} (ARCHITECTURE section 5). Timestamps are stored as
 * epoch milliseconds; the accessors expose {@link Instant}.
 *
 * <p>Status transitions must only be performed by {@code ops.IncidentService}; other code must
 * not call {@link #setStatus}. The stored {@link #getParameters() parameters} and
 * {@link #getLogTail() logTail} are already masked (D-19); the plaintext of a sensitive
 * parameter never reaches this object.
 */
@Restricted(NoExternalUse.class)
public final class Incident {

    private final String id;
    private final String runId;
    private final String jobFullName;
    private final String result;
    private IncidentStatus status;
    private List<IncidentTransition> transitions = new ArrayList<>();
    private List<String> logTail = new ArrayList<>();
    private List<String> rerunRequestIds = new ArrayList<>();
    private String resolvedByRunId;
    private Map<String, String> parameters = new LinkedHashMap<>();
    private final long createdAtMillis;

    public Incident(String id, String runId, String jobFullName, String result, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.runId = Objects.requireNonNull(runId, "runId");
        this.jobFullName = Objects.requireNonNull(jobFullName, "jobFullName");
        this.result = Objects.requireNonNull(result, "result");
        this.status = IncidentStatus.OPEN;
        this.createdAtMillis = Objects.requireNonNull(createdAt, "createdAt").toEpochMilli();
    }

    public String getId() {
        return id;
    }

    public String getRunId() {
        return runId;
    }

    public String getJobFullName() {
        return jobFullName;
    }

    public String getResult() {
        return result;
    }

    public IncidentStatus getStatus() {
        return status;
    }

    /** Only {@code ops.IncidentService} may transition the status. */
    public void setStatus(IncidentStatus status) {
        this.status = Objects.requireNonNull(status, "status");
    }

    /** A defensive copy of the handling history, in append order. */
    public List<IncidentTransition> getTransitions() {
        return transitions == null ? new ArrayList<>() : new ArrayList<>(transitions);
    }

    public void addTransition(IncidentTransition transition) {
        if (transitions == null) {
            transitions = new ArrayList<>();
        }
        transitions.add(Objects.requireNonNull(transition, "transition"));
    }

    /** The masked console log excerpt (at most 100 lines, D-19). */
    public List<String> getLogTail() {
        return logTail == null ? new ArrayList<>() : new ArrayList<>(logTail);
    }

    public void setLogTail(List<String> logTail) {
        this.logTail = logTail == null ? new ArrayList<>() : new ArrayList<>(logTail);
    }

    public List<String> getRerunRequestIds() {
        return rerunRequestIds == null ? new ArrayList<>() : new ArrayList<>(rerunRequestIds);
    }

    public void addRerunRequestId(String requestId) {
        if (rerunRequestIds == null) {
            rerunRequestIds = new ArrayList<>();
        }
        rerunRequestIds.add(Objects.requireNonNull(requestId, "requestId"));
    }

    public String getResolvedByRunId() {
        return resolvedByRunId;
    }

    public void setResolvedByRunId(String resolvedByRunId) {
        this.resolvedByRunId = resolvedByRunId;
    }

    /** The failed build's parameters, sensitive values already masked. */
    public Map<String, String> getParameters() {
        return parameters == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parameters);
    }

    public void setParameters(Map<String, String> parameters) {
        this.parameters = parameters == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(parameters);
    }

    public Instant getCreatedAt() {
        return Instant.ofEpochMilli(createdAtMillis);
    }
}
