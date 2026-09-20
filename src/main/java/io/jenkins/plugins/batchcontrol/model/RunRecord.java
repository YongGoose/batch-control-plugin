package io.jenkins.plugins.batchcontrol.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Summary of one build execution (SPEC section 3). Appended to the monthly JSONL file
 * {@code runs/YYYY-MM.jsonl}; the month bucket is derived from {@link #getStartedAt()}.
 */
@Restricted(NoExternalUse.class)
public final class RunRecord {

    private final String runId;
    private final String jobFullName;
    private final int number;
    private final CauseType causeType;
    private final String result;
    private final Instant startedAt;
    private final long durationMs;
    private String user;
    private Map<String, String> parameters = new LinkedHashMap<>();
    private String abortedBy;
    private String runRequestId;

    public RunRecord(String runId, String jobFullName, int number, CauseType causeType,
                     String result, Instant startedAt, long durationMs) {
        this.runId = Objects.requireNonNull(runId, "runId");
        this.jobFullName = Objects.requireNonNull(jobFullName, "jobFullName");
        this.number = number;
        this.causeType = Objects.requireNonNull(causeType, "causeType");
        this.result = result;
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.durationMs = durationMs;
    }

    public String getRunId() {
        return runId;
    }

    public String getJobFullName() {
        return jobFullName;
    }

    public int getNumber() {
        return number;
    }

    public CauseType getCauseType() {
        return causeType;
    }

    public String getResult() {
        return result;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public Map<String, String> getParameters() {
        return parameters == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parameters);
    }

    public void setParameters(Map<String, String> parameters) {
        this.parameters = parameters == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parameters);
    }

    public String getAbortedBy() {
        return abortedBy;
    }

    public void setAbortedBy(String abortedBy) {
        this.abortedBy = abortedBy;
    }

    public String getRunRequestId() {
        return runRequestId;
    }

    public void setRunRequestId(String runRequestId) {
        this.runRequestId = runRequestId;
    }
}
