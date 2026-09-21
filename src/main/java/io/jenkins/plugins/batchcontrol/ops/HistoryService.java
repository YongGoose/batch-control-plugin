package io.jenkins.plugins.batchcontrol.ops;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.model.IncidentStatus;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRecord;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import io.jenkins.plugins.batchcontrol.store.Store;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Read-only history queries and the monthly aggregation (SPEC item 12), consumed by the ui
 * layer (history screens, CSV exports, summary JSON). All filters are optional: a {@code null}
 * argument means "no constraint". Date ranges are inclusive calendar days in the
 * {@link BatchClock} zone.
 *
 * <p>Permission gating ({@code BatchControl/ViewHistory}) is the caller's duty — the web
 * actions check it before calling in; this service only reads the store.
 */
@Restricted(NoExternalUse.class)
public final class HistoryService {

    private static final HistoryService INSTANCE = new HistoryService();

    private final Store store = FileStore.get();

    private HistoryService() {
    }

    public static HistoryService get() {
        return INSTANCE;
    }

    // ---------------------------------------------------------------- filtered listings

    /**
     * Run records within the date range, optionally narrowed by job full name, triggering
     * user and result, in stored (chronological) order.
     */
    public List<RunRecord> runRecords(@CheckForNull LocalDate from, @CheckForNull LocalDate to,
            @CheckForNull String job, @CheckForNull String user, @CheckForNull String result) {
        List<RunRecord> matches = new ArrayList<>();
        for (YearMonth month : monthsInRange(from, to)) {
            for (RunRecord record : store.listRunRecords(month)) {
                if (inRange(record.getStartedAt(), from, to)
                        && matches(job, record.getJobFullName())
                        && matches(user, record.getUser())
                        && matches(result, record.getResult())) {
                    matches.add(record);
                }
            }
        }
        return matches;
    }

    /**
     * Incidents created within the date range, optionally narrowed by job full name, build
     * result and handling status, in creation order.
     */
    public List<Incident> incidents(@CheckForNull LocalDate from, @CheckForNull LocalDate to,
            @CheckForNull String job, @CheckForNull String result,
            @CheckForNull IncidentStatus status) {
        List<Incident> matches = new ArrayList<>();
        for (YearMonth month : monthsInRange(from, to)) {
            for (Incident incident : store.listIncidents(month)) {
                if (inRange(incident.getCreatedAt(), from, to)
                        && matches(job, incident.getJobFullName())
                        && matches(result, incident.getResult())
                        && (status == null || status == incident.getStatus())) {
                    matches.add(incident);
                }
            }
        }
        return matches;
    }

    /**
     * Change records within the date range, optionally narrowed by target (job full name or
     * configuration key) and acting user, in stored (chronological) order.
     */
    public List<ChangeRecord> changeRecords(@CheckForNull LocalDate from, @CheckForNull LocalDate to,
            @CheckForNull String target, @CheckForNull String user) {
        List<ChangeRecord> matches = new ArrayList<>();
        for (YearMonth month : monthsInRange(from, to)) {
            for (ChangeRecord record : store.listChangeRecords(month)) {
                if (inRange(record.getAt(), from, to)
                        && matches(target, record.getTarget())
                        && matches(user, record.getUser())) {
                    matches.add(record);
                }
            }
        }
        return matches;
    }

    /**
     * Run requests created within the date range, optionally narrowed by job full name,
     * requester and status, in creation order.
     */
    public List<RunRequest> runRequests(@CheckForNull LocalDate from, @CheckForNull LocalDate to,
            @CheckForNull String job, @CheckForNull String requester,
            @CheckForNull RequestStatus status) {
        List<RunRequest> matches = new ArrayList<>();
        for (RunRequest request : store.listRunRequests()) {
            if (inRange(request.getCreatedAt(), from, to)
                    && matches(job, request.getJobFullName())
                    && matches(requester, request.getRequester())
                    && (status == null || status == request.getStatus())) {
                matches.add(request);
            }
        }
        return matches;
    }

    // ---------------------------------------------------------------- monthly summary (T-12-04)

    /**
     * The monthly aggregation (SPEC item 12), keyed exactly as the summary JSON contract:
     * {@code runs, success, failure, unstable, incidentsOpen, incidentsResolved,
     * requestsApproved, requestsRejected} — in that (insertion) order.
     *
     * <ul>
     *   <li>{@code runs} and the three result counts come from the month's run records;</li>
     *   <li>{@code incidentsOpen} counts the month's unresolved incidents (OPEN and
     *       ACKNOWLEDGED), {@code incidentsResolved} the RESOLVED ones;</li>
     *   <li>{@code requestsApproved}/{@code requestsRejected} count requests DECIDED in the
     *       month: a decision timestamp with status REJECTED is a rejection, any other decided
     *       status (APPROVED, EXECUTED, or later expiry/invalidation after approval) was an
     *       approval.</li>
     * </ul>
     */
    public Map<String, Long> monthlySummary(YearMonth month) {
        Objects.requireNonNull(month, "month");
        long runs = 0;
        long success = 0;
        long failure = 0;
        long unstable = 0;
        for (RunRecord record : store.listRunRecords(month)) {
            runs++;
            String result = record.getResult();
            if ("SUCCESS".equals(result)) {
                success++;
            } else if ("FAILURE".equals(result)) {
                failure++;
            } else if ("UNSTABLE".equals(result)) {
                unstable++;
            }
        }
        long incidentsOpen = 0;
        long incidentsResolved = 0;
        for (Incident incident : store.listIncidents(month)) {
            if (incident.getStatus() == IncidentStatus.RESOLVED) {
                incidentsResolved++;
            } else {
                incidentsOpen++;
            }
        }
        long requestsApproved = 0;
        long requestsRejected = 0;
        for (RunRequest request : store.listRunRequests()) {
            Instant decidedAt = request.getDecidedAt();
            if (decidedAt == null || !month.equals(monthOf(decidedAt))) {
                continue;
            }
            if (request.getStatus() == RequestStatus.REJECTED) {
                requestsRejected++;
            } else {
                requestsApproved++;
            }
        }
        Map<String, Long> summary = new LinkedHashMap<>();
        summary.put("runs", runs);
        summary.put("success", success);
        summary.put("failure", failure);
        summary.put("unstable", unstable);
        summary.put("incidentsOpen", incidentsOpen);
        summary.put("incidentsResolved", incidentsResolved);
        summary.put("requestsApproved", requestsApproved);
        summary.put("requestsRejected", requestsRejected);
        return summary;
    }

    // ---------------------------------------------------------------- internals

    private static ZoneId zone() {
        return BatchClock.clock().getZone();
    }

    private static YearMonth monthOf(Instant instant) {
        return YearMonth.from(instant.atZone(zone()));
    }

    /** The stored months intersected with the requested date range (open-ended when null). */
    private List<YearMonth> monthsInRange(@CheckForNull LocalDate from, @CheckForNull LocalDate to) {
        YearMonth first = from == null ? null : YearMonth.from(from);
        YearMonth last = to == null ? null : YearMonth.from(to);
        List<YearMonth> months = new ArrayList<>();
        for (YearMonth month : store.listStoredMonths()) {
            if ((first == null || !month.isBefore(first)) && (last == null || !month.isAfter(last))) {
                months.add(month);
            }
        }
        return months;
    }

    /** Whether the instant falls on a calendar day within [from, to] (inclusive, clock zone). */
    private static boolean inRange(Instant at, @CheckForNull LocalDate from, @CheckForNull LocalDate to) {
        LocalDate day = at.atZone(zone()).toLocalDate();
        return (from == null || !day.isBefore(from)) && (to == null || !day.isAfter(to));
    }

    private static boolean matches(@CheckForNull String filter, @CheckForNull String value) {
        return filter == null || filter.isEmpty() || filter.equals(value);
    }
}
