package io.jenkins.plugins.batchcontrol.store;

import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.RunRecord;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import java.time.YearMonth;
import java.util.List;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Durable, build-independent plugin store under {@code $JENKINS_HOME/batch-control/}
 * (layout in docs/ARCHITECTURE.md section 5). Records are append-only; there is no update or
 * delete API (retention cleanup, added in a later slice, is the only deletion path).
 *
 * <p>Slice S2+ will extend this interface with grant requests, grants, incidents and
 * config snapshots following the same patterns (XStream XML per entity with state,
 * monthly JSONL for append-only records).
 *
 * <p>All methods throw {@link java.io.UncheckedIOException} on I/O failure.
 */
@Restricted(NoExternalUse.class)
public interface Store {

    /** Writes (or rewrites, on a status transition) the request XML atomically. */
    void saveRunRequest(RunRequest request);

    /** Loads a request by id, or returns {@code null} if it does not exist. */
    RunRequest loadRunRequest(String id);

    /** Loads every stored run request, sorted by id (creation order). */
    List<RunRequest> listRunRequests();

    /** Appends one run record to the monthly JSONL bucket derived from its start time. */
    void appendRunRecord(RunRecord record);

    /** Reads every run record of the given month (empty list if the month file is absent). */
    List<RunRecord> listRunRecords(YearMonth month);

    /** Appends one change record to the monthly JSONL bucket derived from its timestamp. */
    void appendChangeRecord(ChangeRecord record);

    /** Reads every change record of the given month (empty list if the month file is absent). */
    List<ChangeRecord> listChangeRecords(YearMonth month);
}
