package io.jenkins.plugins.batchcontrol.store;

import io.jenkins.plugins.batchcontrol.model.ActivationRequest;
import io.jenkins.plugins.batchcontrol.model.ActivationState;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.model.RunRecord;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Durable, build-independent plugin store under {@code $JENKINS_HOME/batch-control/}
 * (layout in docs/ARCHITECTURE.md section 5). Records are append-only; there is no update or
 * delete API (retention cleanup is the only deletion path; config
 * snapshots are working data, not records, and keep only the latest version).
 *
 * <p>All methods throw {@link java.io.UncheckedIOException} on I/O failure.
 */
@Restricted(NoExternalUse.class)
public interface Store {

    /**
     * The store every caller uses (D-44). This is the one place that names the implementation,
     * so a database-backed store (D-11) replaces this line and nothing else.
     */
    static Store get() {
        return FileStore.get();
    }

    /**
     * Prepares derived in-memory data (indexes, caches) ahead of the first request so no later
     * save pays for it. Called once at startup; an implementation without such data does nothing.
     */
    default void warmUp() {
    }

    /** Writes (or rewrites, on a status transition) the request XML atomically. */
    void saveRunRequest(RunRequest request);

    /** Loads a request by id, or returns {@code null} if it does not exist. */
    RunRequest loadRunRequest(String id);

    /** Loads every stored run request, sorted by id (creation order). */
    List<RunRequest> listRunRequests();

    /**
     * Loads the PENDING and APPROVED run requests only, sorted by id (#13). Served from the
     * in-memory entity index, so closed requests are never read; this is what the per-minute
     * expiry work, startup recovery and rename invalidation iterate.
     */
    List<RunRequest> listOpenRunRequests();

    /**
     * The listing fields of every stored run request, sorted by id, from memory (#13). A listing
     * filters and pages on these and loads only the XML of the rows it renders.
     */
    List<RequestSummary> listRunRequestSummaries();

    /** Writes (or rewrites, on a status transition) the grant request XML atomically. */
    void saveGrantRequest(GrantRequest request);

    /** Loads a grant request by id, or returns {@code null} if it does not exist. */
    GrantRequest loadGrantRequest(String id);

    /** Loads every stored grant request, sorted by id (creation order). */
    List<GrantRequest> listGrantRequests();

    /** Loads the PENDING grant requests only, sorted by id, via the entity index (#13). */
    List<GrantRequest> listOpenGrantRequests();

    /** Writes (or rewrites, on revocation) the grant XML atomically. */
    void saveGrant(Grant grant);

    /** Loads a grant by id, or returns {@code null} if it does not exist. */
    Grant loadGrant(String id);

    /** Loads every stored grant, sorted by id (creation order). */
    List<Grant> listGrants();

    // ---------------------------------------------------------------- activation (#15, D-39)

    /** Writes (or rewrites, on a status transition) the activation request XML atomically. */
    void saveActivationRequest(ActivationRequest request);

    /** Loads an activation request by id, or returns {@code null} if it does not exist. */
    ActivationRequest loadActivationRequest(String id);

    /** Loads every stored activation request, sorted by id (creation order). */
    List<ActivationRequest> listActivationRequests();

    /** Loads the PENDING activation requests only, sorted by id, via the entity index (#13). */
    List<ActivationRequest> listOpenActivationRequests();

    /**
     * Writes the activation state of a job atomically to
     * {@code activations/<PathCodec-encoded full name>.xml}; the file name comes from
     * {@link ActivationState#getJobFullName()}.
     */
    void saveActivationState(ActivationState state);

    /** The stored activation state of a job, or {@code null} if none exists. */
    ActivationState loadActivationState(String jobFullName);

    /** Every stored activation state (unordered). */
    List<ActivationState> listActivationStates();

    /** Removes the activation state of a job; {@code true} if one existed. */
    boolean deleteActivationState(String jobFullName);

    /** Whether the one-time upgrade seeding marker {@code activations/.schema} exists. */
    boolean isActivationSchemaMarked();

    /** Writes the upgrade seeding marker {@code activations/.schema} (atomically). */
    void markActivationSchema();

    /**
     * Stores the latest config.xml snapshot of a job (diff baseline; only the latest version
     * is kept, ARCHITECTURE section 5).
     */
    void saveConfigSnapshot(String jobFullName, String configXml);

    /** The stored config snapshot text of a job, or {@code null} if none exists. */
    String loadConfigSnapshot(String jobFullName);

    /** Removes the config snapshot of a job (after deletion, rename or move). */
    void deleteConfigSnapshot(String jobFullName);

    /** Appends one run record to the monthly JSONL bucket derived from its start time. */
    void appendRunRecord(RunRecord record);

    /** Reads every run record of the given month (empty list if the month file is absent). */
    List<RunRecord> listRunRecords(YearMonth month);

    /**
     * One page of the run records of {@code months} that match {@code filter}, newest first
     * (start time, then run id). Reads newest first and stops after {@code maxScanned} records,
     * so the cost of a page load is bounded whatever a month holds (#13).
     *
     * @param offset     matches to skip (page index times page size)
     * @param limit      rows to return
     * @param maxScanned records to read at most; see {@link #MAX_SCANNED_RECORDS}
     */
    RecordPage<RunRecord> pageRunRecords(Collection<YearMonth> months, Predicate<? super RunRecord> filter,
                                         int offset, int limit, int maxScanned);

    /**
     * As {@link #pageRunRecords(Collection, Predicate, int, int, int)}, counting only records whose
     * start time lies inside {@code period} toward {@code maxScanned} (security-10 S-03).
     */
    RecordPage<RunRecord> pageRunRecords(Collection<YearMonth> months, Period period,
                                         Predicate<? super RunRecord> filter,
                                         int offset, int limit, int maxScanned);

    /** Run counters of one month, maintained incrementally from the bucket (#13). */
    RunMonthStats runMonthStats(YearMonth month);

    /** Appends one change record to the monthly JSONL bucket derived from its timestamp. */
    void appendChangeRecord(ChangeRecord record);

    /** Reads every change record of the given month (empty list if the month file is absent). */
    List<ChangeRecord> listChangeRecords(YearMonth month);

    /**
     * As {@link #pageRunRecords} for change records (time, then id, newest first). Diff patches
     * are read for the returned rows only.
     */
    RecordPage<ChangeRecord> pageChangeRecords(Collection<YearMonth> months,
                                               Predicate<? super ChangeRecord> filter,
                                               int offset, int limit, int maxScanned);

    /** As {@link #pageRunRecords(Collection, Period, Predicate, int, int, int)} for change records. */
    RecordPage<ChangeRecord> pageChangeRecords(Collection<YearMonth> months, Period period,
                                               Predicate<? super ChangeRecord> filter,
                                               int offset, int limit, int maxScanned);

    /**
     * Persists a freshly opened incident: writes {@code incidents/<id>.xml} and appends the
     * monthly index line ({@code incidents/index/YYYY-MM.jsonl}, bucket derived from the
     * incident's creation time).
     */
    void createIncident(Incident incident);

    /** Rewrites an existing incident's XML atomically (handling transitions, links). */
    void saveIncident(Incident incident);

    /** Loads an incident by id, or returns {@code null} if it does not exist. */
    Incident loadIncident(String id);

    /**
     * Loads every incident created in the given month via the monthly index, in index
     * (creation) order. Index lines whose XML has been deleted are skipped.
     */
    List<Incident> listIncidents(YearMonth month);

    /**
     * As {@link #pageRunRecords} for incidents, walking the monthly index newest first (creation
     * time, then id); every index line read counts against {@code maxScanned}.
     */
    RecordPage<Incident> pageIncidents(Collection<YearMonth> months, Predicate<? super Incident> filter,
                                       int offset, int limit, int maxScanned);

    /**
     * As {@link #pageRunRecords(Collection, Period, Predicate, int, int, int)} for incidents:
     * {@code indexFilter} is tested on the index line and an incident's XML is loaded only when it
     * passes (security-10 S-06); {@code filter} then sees the loaded incident.
     */
    RecordPage<Incident> pageIncidents(Collection<YearMonth> months, Period period,
                                       Predicate<? super IncidentSummary> indexFilter,
                                       Predicate<? super Incident> filter,
                                       int offset, int limit, int maxScanned);

    /**
     * Every month for which any record bucket exists (runs, changes or incident index),
     * sorted ascending. Used by retention cleanup.
     */
    List<YearMonth> listStoredMonths();

    /**
     * Deletes every record bucket of the given month: the runs and changes JSONL files, the
     * diff patches of that month's change records, the incident XMLs listed in that month's
     * index and the index file itself. This is the ONLY deletion path of the store
     * (SPEC item 4: append-only apart from retention cleanup).
     *
     * @return {@code true} if anything was deleted
     */
    boolean deleteMonth(YearMonth month);

    /**
     * Retention of the XML entities (#13): deletes closed run requests (not PENDING or APPROVED)
     * and closed grant requests (not PENDING) whose last activity lies before {@code cutoff}, and
     * grants that stopped conferring anything (expiry or revocation) before it. A grant request
     * is kept while a grant issued from it is kept. Each file is deleted under its own lock, so
     * no store lock is held for longer than one deletion (#18).
     */
    RetentionResult deleteClosedEntitiesBefore(Instant cutoff);

    /** Default {@code maxScanned} of the page queries: the per-request record cap (#13). */
    int MAX_SCANNED_RECORDS = 50_000;
}
