package io.jenkins.plugins.batchcontrol.store;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.ParameterValue;
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
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Durable, build-independent plugin store under {@code $JENKINS_HOME/batch-control/}
 * (layout in docs/ARCHITECTURE.md section 5). Records are append-only; there is no update or
 * delete API (retention cleanup is the only deletion path; config
 * snapshots are working data, not records, and keep only the latest version).
 *
 * <p>All methods throw {@link java.io.UncheckedIOException} on I/O failure, except that a failed
 * save of an XML entity (request, grant, incident, activation) throws {@link StoreWriteException}
 * and leaves nothing behind (D-72b (2), security-35 S-35-03).
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
     * save pays for it. Called once at startup.
     */
    void warmUp();

    /**
     * Writes (or rewrites, on a status transition) the request XML {@code <id>.xml} atomically.
     * Never touches the request's typed values (D-74).
     *
     * @throws StoreWriteException when it cannot be written; nothing is left behind
     */
    void saveRunRequest(RunRequest request);

    /**
     * Stores a new request (D-72, D-74): {@code <id>.xml}, then its typed values in
     * {@code <id>.values.xml} when there are any (a request without parameters has no values file).
     * Each file is written atomically; when either cannot be written, neither is left behind.
     *
     * @throws StoreWriteException when it cannot be stored; nothing is left behind
     */
    void saveNewRunRequest(RunRequest request, List<ParameterValue> values);

    /**
     * Loads a request by id from {@code <id>.xml}, or returns {@code null} if it does not exist.
     * Its typed values are never read here (D-74): this is the read for screens, badges, listings,
     * listeners and periodic work.
     */
    RunRequest loadRunRequest(String id);

    /**
     * The typed values of request {@code id} from {@code <id>.values.xml} (D-74), or {@code null}
     * when there is no values file (the request has no parameters, or its values were deleted when
     * its run started or it ended). Only for approving, submitting, recovering and disposing of a
     * request. An element that could not be loaded may be {@code null}.
     *
     * @throws java.io.UncheckedIOException when the file exists but cannot be read
     */
    @CheckForNull
    List<ParameterValue> loadRunRequestValues(String id);

    /**
     * Deletes the typed values of request {@code id} ({@code <id>.values.xml}), when the approved
     * run starts or the request ends (D-72b (5), D-74); nothing happens when there are none.
     */
    void deleteRunRequestValues(String id);

    /**
     * Loads every stored run request, sorted by creation time. A file that cannot be read (corrupt,
     * or not readable at all) is left out with a warning, so one such file never breaks a listing.
     */
    List<RunRequest> listRunRequests();

    /**
     * Loads the PENDING and APPROVED run requests only, sorted by
     * creation time (#13). Served from the in-memory entity index, so closed requests are never
     * read; this is what the per-minute expiry work, startup recovery and rename invalidation
     * iterate.
     *
     * <p>An open request whose file cannot be read (permission, I/O error or damaged content) is
     * left out with a warning naming it, so it is never expired, approved, run, recovered or counted
     * while unreadable, and it never breaks the listing for the others. Its index entry is kept, so
     * the next listing reads it again and it is back as soon as it can be read.
     */
    default List<RunRequest> listOpenRunRequests() {
        return listOpenRunRequests(id -> { });
    }

    /**
     * As {@link #listOpenRunRequests()}, and hands the id of every open request left out because its
     * file cannot be read to {@code unreadable}, for a caller that must act on it once it can be read
     * (an invalidation it would otherwise miss).
     */
    List<RunRequest> listOpenRunRequests(Consumer<String> unreadable);

    /**
     * The listing fields of every stored run request, sorted by id, from memory (#13). A listing
     * filters and pages on these and loads only the XML of the rows it renders.
     */
    List<RequestSummary> listRunRequestSummaries();

    /**
     * Whether at least one stored run request, in any status, was filed by {@code userId} (D-38b).
     * Answered from the in-memory entity index; no request file and no item is read.
     */
    boolean hasRunRequestBy(String userId);

    /** Writes (or rewrites, on a status transition) the grant request XML atomically. */
    void saveGrantRequest(GrantRequest request);

    /** Loads a grant request by id, or returns {@code null} if it does not exist. */
    GrantRequest loadGrantRequest(String id);

    /** Loads every stored grant request, sorted by id (creation order). */
    List<GrantRequest> listGrantRequests();

    /**
     * Loads the PENDING grant requests only, sorted by id, via the entity index (#13). An open request
     * whose file cannot be read is left out with a warning and its index entry kept, as for
     * {@link #listOpenRunRequests()}.
     */
    List<GrantRequest> listOpenGrantRequests();

    /** Writes (or rewrites, on revocation) the grant XML atomically. */
    void saveGrant(Grant grant);

    /** Loads a grant by id, or returns {@code null} if it does not exist. */
    Grant loadGrant(String id);

    /**
     * Loads every stored grant, sorted by id (creation order). A file that cannot be read (corrupt,
     * or not readable at all) is left out with a warning: that grant confers nothing, and the
     * permission checks of everyone else keep working.
     */
    List<Grant> listGrants();

    // ---------------------------------------------------------------- activation (#15, D-39)

    /** Writes (or rewrites, on a status transition) the activation request XML atomically. */
    void saveActivationRequest(ActivationRequest request);

    /** Loads an activation request by id, or returns {@code null} if it does not exist. */
    ActivationRequest loadActivationRequest(String id);

    /** Loads every stored activation request, sorted by id (creation order). */
    List<ActivationRequest> listActivationRequests();

    /**
     * Loads the PENDING activation requests only, sorted by id, via the entity index (#13). An open
     * request whose file cannot be read is left out with a warning and its index entry kept, as for
     * {@link #listOpenRunRequests()}.
     */
    default List<ActivationRequest> listOpenActivationRequests() {
        return listOpenActivationRequests(id -> { });
    }

    /**
     * As {@link #listOpenActivationRequests()}, and hands the id of every open request left out
     * because its file cannot be read to {@code unreadable}.
     */
    List<ActivationRequest> listOpenActivationRequests(Consumer<String> unreadable);

    /**
     * Whether at least one stored activation or hold request, in any status, was filed by
     * {@code userId} (D-38b). Answered from the in-memory entity index.
     */
    boolean hasActivationRequestBy(String userId);

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

    /**
     * The stored config snapshot text of a job, or {@code null} if none exists.
     *
     * @throws java.io.UncheckedIOException when a snapshot exists but cannot be read, also when
     *         something other than a file is in its place
     */
    String loadConfigSnapshot(String jobFullName);

    /**
     * Whether anything is stored where the config snapshot of a job belongs: a snapshot, or
     * something else in its place (which {@link #loadConfigSnapshot} refuses). Nothing is read, so
     * seeding the missing snapshots of every item costs one file system lookup per item.
     */
    boolean hasConfigSnapshot(String jobFullName);

    /** Removes the config snapshot of a job (after deletion, rename or move). */
    void deleteConfigSnapshot(String jobFullName);

    /** Appends one run record to the monthly JSONL bucket derived from its start time. */
    void appendRunRecord(RunRecord record);

    /** Reads every run record of the given month (empty list if the month file is absent). */
    List<RunRecord> listRunRecords(YearMonth month);

    /**
     * One page of the run records of {@code months} that match {@code filter}, newest first
     * (start time, then run id). Reads newest first and stops after {@code maxScanned} records,
     * so the cost of a page load is bounded whatever a month holds (#13); only records whose start
     * time lies inside {@code period} count toward {@code maxScanned} (security-10 S-03).
     *
     * @param offset     matches to skip (page index times page size)
     * @param limit      rows to return
     * @param maxScanned records to read at most; see {@link #MAX_SCANNED_RECORDS}
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
     * As {@link #pageRunRecords} for change records (time, then id, newest first), over all of
     * {@code months}. Diff patches are read for the returned rows only.
     */
    RecordPage<ChangeRecord> pageChangeRecords(Collection<YearMonth> months,
                                               Predicate<? super ChangeRecord> filter,
                                               int offset, int limit, int maxScanned);

    /** As {@link #pageRunRecords} for change records, within {@code period}. */
    RecordPage<ChangeRecord> pageChangeRecords(Collection<YearMonth> months, Period period,
                                               Predicate<? super ChangeRecord> filter,
                                               int offset, int limit, int maxScanned);

    /**
     * S-39-03: every {@code GRANT_REVOKE} change record of a grant in {@code grantIds} with a time at
     * or after {@code since}, newest first, for re-ending windows at startup (D-74). Bounded by time,
     * not by a record count: the change log is read back only to the first record appended before
     * {@code since}, and every record after it is read, however many there are. The page is
     * {@linkplain RecordPage#isTruncated() truncated} when that could not be done completely.
     *
     * <p>Reading also stops at the first record before {@code since} that {@code boundary} accepts
     * (T-SEC-109): the caller's statement that no line appended before that record can be the end of
     * any window in {@code grantIds}, so the append-order slack is not read past it.
     *
     * @throws java.io.UncheckedIOException when the change log cannot be read
     */
    RecordPage<ChangeRecord> grantRevokeRecordsSince(Instant since, Set<String> grantIds,
                                                     Predicate<? super ChangeRecord> boundary);

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
