package io.jenkins.plugins.batchcontrol.store;

import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.model.RunRecord;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import java.time.YearMonth;
import java.util.List;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Durable, build-independent plugin store under {@code $JENKINS_HOME/batch-control/}
 * (layout in docs/ARCHITECTURE.md section 5). Records are append-only; there is no update or
 * delete API (retention cleanup, added in a later slice, is the only deletion path; config
 * snapshots are working data, not records, and keep only the latest version).
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

    /** Writes (or rewrites, on a status transition) the grant request XML atomically. */
    void saveGrantRequest(GrantRequest request);

    /** Loads a grant request by id, or returns {@code null} if it does not exist. */
    GrantRequest loadGrantRequest(String id);

    /** Loads every stored grant request, sorted by id (creation order). */
    List<GrantRequest> listGrantRequests();

    /** Writes (or rewrites, on revocation) the grant XML atomically. */
    void saveGrant(Grant grant);

    /** Loads a grant by id, or returns {@code null} if it does not exist. */
    Grant loadGrant(String id);

    /** Loads every stored grant, sorted by id (creation order). */
    List<Grant> listGrants();

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

    /** Appends one change record to the monthly JSONL bucket derived from its timestamp. */
    void appendChangeRecord(ChangeRecord record);

    /** Reads every change record of the given month (empty list if the month file is absent). */
    List<ChangeRecord> listChangeRecords(YearMonth month);

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
}
