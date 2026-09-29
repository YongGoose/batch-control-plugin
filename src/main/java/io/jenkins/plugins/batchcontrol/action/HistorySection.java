package io.jenkins.plugins.batchcontrol.action;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.ModelObject;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.model.Approvers;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.model.IncidentStatus;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRecord;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.ops.IncidentService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import io.jenkins.plugins.batchcontrol.store.RecordPage;
import io.jenkins.plugins.batchcontrol.store.RequestSummary;
import io.jenkins.plugins.batchcontrol.store.RunMonthStats;
import io.jenkins.plugins.batchcontrol.store.Store;
import io.jenkins.plugins.batchcontrol.ui.CsvWriter;
import io.jenkins.plugins.batchcontrol.ui.Dates;
import io.jenkins.plugins.batchcontrol.ui.FilterParser;
import io.jenkins.plugins.batchcontrol.ui.RunLinks;
import io.jenkins.plugins.batchcontrol.ui.HttpVerbs;
import io.jenkins.plugins.batchcontrol.ui.SectionAccess;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerProxy;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

/**
 * The history browser at {@code /batch-control/history/} (SPEC item 12): filtered listings of
 * run records, incidents, change records and run requests, a monthly aggregate JSON endpoint,
 * and CSV exports.
 *
 * <p>Blocked approval-marker re-use attempts (D-30) are an exception to the one-table-per-kind
 * layout: they are shown on every tab as an alert above the selected table, because they produce
 * no run record and so would otherwise be invisible on the default {@code runs} tab. See
 * {@link #getMarkerReuseItems()}.
 *
 * <p>URL space (fixed contract, asserted by tests):
 * <ul>
 *   <li>{@code /batch-control/history/} — filter form + one selectable table
 *       ({@code ?kind=runs|incidents|changes|requests}, filters {@code from, to, job, user,
 *       result, status}, paging {@code ?page=N})</li>
 *   <li>{@code GET /batch-control/history/summary?month=YYYY-MM} — monthly aggregate JSON with
 *       exactly the keys {@code runs, success, failure, unstable, incidentsOpen,
 *       incidentsResolved, requestsApproved, requestsRejected}</li>
 *   <li>{@code GET /batch-control/history/runs.csv|incidents.csv|changes.csv|requests.csv} —
 *       CSV exports honoring the same filter parameters (D-18 formula-injection sanitization
 *       via {@link CsvWriter})</li>
 * </ul>
 *
 * <p>Everything under this section requires {@code ViewHistory}; {@link #getTarget()} rejects
 * the whole subtree with 403 otherwise (T-12-01, T-12-05). All URLs are read-only: PUT/DELETE/PATCH
 * get 405 everywhere, and the JSON and CSV exports refuse every verb except GET/HEAD. Query parsing and validation live in {@link FilterParser}.
 */
@Restricted(NoExternalUse.class)
public class HistorySection implements ModelObject, StaplerProxy {

    /** Page size for every table. */
    public static final int PAGE_SIZE = 50;

    /** How many blocked marker re-use attempts the always-visible alert lists at most. */
    public static final int REUSE_ALERT_LIMIT = 10;

    /** Most CSV exports that may run at the same time, instance-wide (S-05). */
    public static final int MAX_CONCURRENT_EXPORTS = 2;

    private static final java.util.concurrent.Semaphore CSV_EXPORTS =
            new java.util.concurrent.Semaphore(MAX_CONCURRENT_EXPORTS);

    private static final int TOO_MANY_REQUESTS = 429;

    private static final List<String> KINDS = List.of("runs", "incidents", "changes", "requests");

    private FilterParser.Filter filter;
    private Listing listing;
    private RecordPage<ChangeRecord> markerReuse;
    private List<YearMonth> storedMonths;

    @Override
    public Object getTarget() {
        // Gate the entire /batch-control/history/** subtree, CSV and JSON included
        // (SPEC item 12: without ViewHistory every history screen and CSV is 403).
        Jenkins.get().checkAnyPermission(SectionAccess.history());
        HttpVerbs.refuseUnsupported();
        return this;
    }

    @Override
    public String getDisplayName() {
        return "History";
    }


    /** Permissions for this screen's {@code l:layout} (the same set its section gate checks). */
    public Permission[] getViewPermissions() {
        return SectionAccess.history();
    }

    /** Link predicates: a link to another screen is rendered only if the user may open it. */
    public SectionAccess getLinks() {
        return new SectionAccess();
    }

    // ---------------------------------------------------------------- monthly aggregate JSON

    /**
     * GET {@code summary?month=YYYY-MM} — the monthly aggregate as JSON (T-12-04). Absent month
     * defaults to the current month; a malformed month is a 400.
     */
    // Read-only GET view; permission enforced in getTarget(), non-GET answered 405.
    @SuppressWarnings({"lgtm[jenkins/csrf]", "lgtm[jenkins/no-permission-check]"})
    public void doSummary(StaplerRequest2 req, StaplerResponse2 rsp, @QueryParameter String month)
            throws IOException {
        if (refuseNonGet(req, rsp)) {
            return;
        }
        YearMonth target;
        if (month == null || month.trim().isEmpty()) {
            target = YearMonth.now(BatchClock.clock());
        } else {
            try {
                target = YearMonth.parse(month.trim());
            } catch (DateTimeParseException e) {
                rsp.sendError(HttpServletResponse.SC_BAD_REQUEST,
                        "The month parameter must have the form YYYY-MM");
                return;
            }
        }
        JSONObject json = new JSONObject();
        for (Map.Entry<String, Long> entry : summarize(target).entrySet()) {
            json.put(entry.getKey(), entry.getValue());
        }
        rsp.setContentType("application/json;charset=UTF-8");
        rsp.getWriter().print(json.toString());
    }

    // ---------------------------------------------------------------- CSV exports

    /**
     * Serves the four CSV export URLs ({@code runs.csv}, {@code incidents.csv},
     * {@code changes.csv}, {@code requests.csv}); anything else under this section is a 404.
     * Stapler cannot route dotted tokens to {@code do*} methods, hence the dynamic dispatch.
     */
    // Read-only CSV export (GET/HEAD only); ViewHistory enforced in getTarget(), no state change.
    @SuppressWarnings({"lgtm[jenkins/csrf]", "lgtm[jenkins/no-permission-check]"})
    public void doDynamic(StaplerRequest2 req, StaplerResponse2 rsp)
            throws IOException, ServletException {
        String rest = req.getRestOfPath();
        if (!"/runs.csv".equals(rest) && !"/incidents.csv".equals(rest)
                && !"/changes.csv".equals(rest) && !"/requests.csv".equals(rest)) {
            rsp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        if (refuseNonGet(req, rsp)) {
            return;
        }
        // S-05: an export has no span cap (it must be complete), so bound how many run at once
        // instance-wide; a further one is refused rather than queued.
        if (!CSV_EXPORTS.tryAcquire()) {
            rsp.setHeader("Retry-After", "30");
            rsp.sendError(TOO_MANY_REQUESTS,
                    "Too many CSV exports are running; try again in a moment");
            return;
        }
        try {
            switch (rest) {
                case "/runs.csv" -> writeRunsCsv(rsp);
                case "/incidents.csv" -> writeIncidentsCsv(rsp);
                case "/changes.csv" -> writeChangesCsv(rsp);
                default -> writeRequestsCsv(rsp);
            }
        } finally {
            CSV_EXPORTS.release();
        }
    }

    /*
     * CSV exports (#13) stay complete for the requested span (only months that exist in the
     * store are opened) but are written month by month, newest month first, so at most one
     * month's records are held at a time rather than every row of the span. The store has no
     * row-streaming read yet; see the ui-dev report.
     */

    private void writeRunsCsv(StaplerResponse2 rsp) throws IOException {
        CsvWriter csv = CsvWriter.open(rsp, "runs.csv");
        csv.row("runId", "jobFullName", "number", "causeType", "user", "parameters", "result",
                "startedAt", "durationMs", "abortedBy", "runRequestId");
        Predicate<RunRecord> match = runFilter();
        Comparator<RunRecord> order = Comparator.comparing(RunRecord::getStartedAt)
                .thenComparing(RunRecord::getRunId).reversed();
        for (YearMonth m : newestMonthFirst()) {
            List<RunRecord> month = new ArrayList<>();
            for (RunRecord r : FileStore.get().listRunRecords(m)) {
                if (match.test(r)) {
                    month.add(r);
                }
            }
            month.sort(order);
            for (RunRecord r : month) {
                csv.row(r.getRunId(), r.getJobFullName(), r.getNumber(), r.getCauseType(),
                        r.getUser(), RunLinks.formatParameters(r.getParameters()), r.getResult(),
                        r.getStartedAt(), r.getDurationMs(), r.getAbortedBy(), r.getRunRequestId());
            }
        }
    }

    private void writeIncidentsCsv(StaplerResponse2 rsp) throws IOException {
        CsvWriter csv = CsvWriter.open(rsp, "incidents.csv");
        csv.row("id", "runId", "jobFullName", "result", "status", "createdAt",
                "resolvedByRunId", "rerunRequestIds");
        Predicate<Incident> match = incidentFilter();
        for (YearMonth m : newestMonthFirst()) {
            List<Incident> month = new ArrayList<>();
            for (Incident i : IncidentService.get().list(m)) {
                if (match.test(i)) {
                    month.add(i);
                }
            }
            month.sort(INCIDENT_ORDER);
            for (Incident i : month) {
                csv.row(i.getId(), i.getRunId(), i.getJobFullName(), i.getResult(), i.getStatus(),
                        IncidentsSection.creationTime(i), i.getResolvedByRunId(),
                        String.join(" ", i.getRerunRequestIds()));
            }
        }
    }

    private void writeChangesCsv(StaplerResponse2 rsp) throws IOException {
        CsvWriter csv = CsvWriter.open(rsp, "changes.csv");
        // The diff column is deliberately omitted: diffs are large multi-line blobs that belong
        // in the change detail screen, not in a spreadsheet.
        csv.row("id", "type", "target", "user", "at", "grantId", "detail");
        Predicate<ChangeRecord> match = changeFilter();
        Comparator<ChangeRecord> order = Comparator.comparing(ChangeRecord::getAt)
                .thenComparing(ChangeRecord::getId).reversed();
        for (YearMonth m : newestMonthFirst()) {
            List<ChangeRecord> month = new ArrayList<>();
            for (ChangeRecord c : FileStore.get().listChangeRecords(m)) {
                if (match.test(c)) {
                    month.add(c);
                }
            }
            month.sort(order);
            for (ChangeRecord c : month) {
                csv.row(c.getId(), c.getType(), c.getTarget(), c.getUser(), c.getAt(),
                        c.getGrantId(), c.getDetail());
            }
        }
    }

    private void writeRequestsCsv(StaplerResponse2 rsp) throws IOException {
        CsvWriter csv = CsvWriter.open(rsp, "requests.csv");
        csv.row("id", "jobFullName", "parameters", "reason", "requester", "approver", "status",
                "createdAt", "decidedAt", "decisionComment", "selfApproved", "incidentId",
                "executedRunId", "decidedBy");
        // D-37: the existing approver column holds the designated set joined by ';', and
        // decidedBy is appended last so column positions of existing consumers do not move.
        // Every cell still goes through CsvWriter's formula escaping. Filtering runs on the
        // in-memory summaries (#13); each matching request's XML is loaded only to write its row.
        for (RequestSummary summary : matchingRequestSummaries()) {
            RunRequest q = RunRequestService.get().load(summary.id());
            if (q == null) {
                continue; // deleted by retention since the summary was read
            }
            csv.row(q.getId(), q.getJobFullName(), RunLinks.formatParameters(q.getParameters()),
                    q.getReason(), q.getRequester(), Approvers.csv(q.getApprovers()), q.getStatus(),
                    q.getCreatedAt(), q.getDecidedAt(), q.getDecisionComment(),
                    q.isSelfApproved(), q.getIncidentId(), q.getExecutedRunId(), q.getDecidedBy());
        }
    }

    /** The store's existing month buckets inside the filter range (no month cap, #13). */
    private List<YearMonth> storedMonths() {
        if (storedMonths == null) {
            storedMonths = getFilter().months(FileStore.get().listStoredMonths());
        }
        return storedMonths;
    }

    private List<YearMonth> newestMonthFirst() {
        List<YearMonth> months = new ArrayList<>(storedMonths());
        java.util.Collections.reverse(months);
        return months;
    }

    // ---------------------------------------------------------------- filter and kind

    /** The validated filter for the current request (parsed once). */
    public FilterParser.Filter getFilter() {
        if (filter == null) {
            filter = FilterParser.parse(Stapler.getCurrentRequest2());
        }
        return filter;
    }

    /** The selected table, from {@code ?kind=}; {@code runs} on absence or garbage. */
    public String getKind() {
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        if (req != null) {
            String raw = req.getParameter("kind");
            if (raw != null && KINDS.contains(raw.trim())) {
                return raw.trim();
            }
        }
        return "runs";
    }

    /** The filter as a query fragment (no kind, no page) for CSV links. */
    public String getBaseQuery() {
        return getFilter().toQueryString();
    }

    /** Full query fragment for a tab link of the given kind (filter preserved, page reset). */
    public String query(String kind) {
        String safe = KINDS.contains(kind) ? kind : "runs";
        return "kind=" + safe + "&" + getBaseQuery();
    }

    /**
     * The complete CSV export of what this screen lists (#13, S-03), relative to this section.
     * Pointed to by the truncation notice: the export is not bound by the per-screen record cap.
     * Only ISO dates and constant names go into it, so no encoding is needed.
     */
    public String getCsvUrl() {
        return getKind() + ".csv?" + getBaseQuery();
    }

    // ---------------------------------------------------------------- filters

    private static final Comparator<Incident> INCIDENT_ORDER = Comparator
            .comparing((Incident i) -> {
                Instant at = IncidentsSection.creationTime(i);
                return at == null ? Instant.EPOCH : at;
            })
            .thenComparing(Incident::getId)
            .reversed();

    private Predicate<RunRecord> runFilter() {
        FilterParser.Filter f = getFilter();
        return r -> f.inRange(r.getStartedAt()) && f.matchesJob(r.getJobFullName())
                && f.matchesUser(r.getUser()) && f.matchesResult(r.getResult());
    }

    private Predicate<Incident> incidentFilter() {
        FilterParser.Filter f = getFilter();
        return i -> f.inRange(IncidentsSection.creationTime(i))
                && f.matchesJob(i.getJobFullName())
                && f.matchesResult(i.getResult())
                && f.matchesStatus(i.getStatus());
    }

    private Predicate<ChangeRecord> changeFilter() {
        FilterParser.Filter f = getFilter();
        return c -> f.inRange(c.getAt()) && f.matchesJob(c.getTarget())
                && f.matchesUser(c.getUser());
    }

    /** Request summaries matching the filter (by creation time), newest first (#13). */
    private List<RequestSummary> matchingRequestSummaries() {
        FilterParser.Filter f = getFilter();
        List<RequestSummary> matched = new ArrayList<>();
        for (RequestSummary q : FileStore.get().listRunRequestSummaries()) {
            if (f.inRange(q.createdAt()) && f.matchesJob(q.jobFullName())
                    && f.matchesUser(q.requester()) && f.matchesStatus(q.status())) {
                matched.add(q);
            }
        }
        matched.sort(Comparator.comparing(RequestSummary::createdAt)
                .thenComparing(RequestSummary::id)
                .reversed());
        return matched;
    }

    /**
     * The blocked approval-marker re-use records matching the filter, newest first, at most
     * {@link #REUSE_ALERT_LIMIT} of them (D-30).
     *
     * <p>A blocked re-use produces no {@link RunRecord}, so on the default {@code runs} tab the
     * attempt would otherwise be invisible; the index view renders these rows as an alert above
     * the selected table on every tab. The period, job and user filters are the Changes tab's,
     * so the alert describes the same window as that tab (which is what makes {@code ?user=u2}
     * a query for "what did this account attempt"). The read is bounded like every page (#13).
     */
    public List<ChangeRecord> getMarkerReuseItems() {
        return markerReusePage().getItems();
    }

    private RecordPage<ChangeRecord> markerReusePage() {
        if (markerReuse == null) {
            Predicate<ChangeRecord> match = changeFilter();
            markerReuse = FileStore.get().pageChangeRecords(storedMonths(),
                    c -> c.getType() == ChangeType.MARKER_REUSE_BLOCKED && match.test(c),
                    0, REUSE_ALERT_LIMIT, Store.MAX_SCANNED_RECORDS);
        }
        return markerReuse;
    }

    /** How many blocked re-use attempts match the filter (a lower bound when truncated). */
    public int getMarkerReuseCount() {
        return markerReusePage().getMatched();
    }

    /**
     * The re-use rows the alert actually lists: the newest {@link #REUSE_ALERT_LIMIT}. The alert
     * must stay a signal rather than become a second unbounded table when an automation retries
     * in a loop; {@link #getMarkerReuseOverflow()} says how many rows were left out and the
     * Changes tab (and {@code changes.csv}) has all of them.
     */
    public List<ChangeRecord> getMarkerReuseAlertItems() {
        return getMarkerReuseItems();
    }

    /** How many matching re-use records the alert does not list; 0 when it lists them all. */
    public int getMarkerReuseOverflow() {
        return Math.max(0, getMarkerReuseCount() - REUSE_ALERT_LIMIT);
    }

    /** One page of the selected kind (#13): rows, match count, and whether the read was capped. */
    private record Listing(List<?> items, int total, boolean hasNext, boolean truncated) {
        static Listing of(RecordPage<?> page) {
            return new Listing(page.getItems(), page.getMatched(), page.isHasNext(), page.isTruncated());
        }
    }

    private Listing listing() {
        if (listing == null) {
            int offset = (getPage() - 1) * PAGE_SIZE;
            List<YearMonth> months = storedMonths();
            int cap = Store.MAX_SCANNED_RECORDS;
            switch (getKind()) {
                case "incidents" -> listing = Listing.of(FileStore.get().pageIncidents(
                        months, incidentFilter(), offset, PAGE_SIZE, cap));
                case "changes" -> listing = Listing.of(FileStore.get().pageChangeRecords(
                        months, changeFilter(), offset, PAGE_SIZE, cap));
                case "requests" -> {
                    List<RequestSummary> all = matchingRequestSummaries();
                    List<RunRequest> rows = new ArrayList<>();
                    for (int k = offset; k < Math.min(offset + PAGE_SIZE, all.size()); k++) {
                        RunRequest q = RunRequestService.get().load(all.get(k).id());
                        if (q != null) {
                            rows.add(q);
                        }
                    }
                    listing = new Listing(rows, all.size(), offset + PAGE_SIZE < all.size(), false);
                }
                default -> listing = Listing.of(FileStore.get().pageRunRecords(
                        months, runFilter(), offset, PAGE_SIZE, cap));
            }
        }
        return listing;
    }

    // ---------------------------------------------------------------- paging (used from Jelly)

    /** Current 1-based page, from the {@code page} query parameter. */
    public int getPage() {
        int page = 1;
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        if (req != null) {
            String raw = req.getParameter("page");
            if (raw != null) {
                try {
                    page = Integer.parseInt(raw.trim());
                } catch (NumberFormatException ignored) {
                    // Fall back to page 1 on garbage input.
                }
            }
        }
        return Math.max(1, page);
    }

    /** The rows of the selected kind shown on the current page, newest first. */
    public List<?> getPageItems() {
        return listing().items();
    }

    /** Matching records read (a lower bound when {@link #isTruncated()}). */
    public int getTotal() {
        return listing().total();
    }

    /** Whether the per-request record cap stopped the read (#13): ask to narrow the filter. */
    public boolean isTruncated() {
        return listing().truncated();
    }

    public boolean isHasPrevious() {
        return getPage() > 1;
    }

    public boolean isHasNext() {
        return listing().hasNext();
    }

    // ---------------------------------------------------------------- monthly summary

    /** The month shown in the on-page summary block and the JSON link: month of the filter end. */
    public String getSummaryMonth() {
        return YearMonth.from(java.time.LocalDate.parse(getFilter().getToValue())).toString();
    }

    /** The on-page monthly aggregate rows for {@link #getSummaryMonth()}. */
    public Map<String, Long> getSummary() {
        return summarize(YearMonth.parse(getSummaryMonth()));
    }

    /**
     * Computes the monthly aggregate (SPEC item 12) with exactly the eight contract keys, in a
     * deterministic order. Requests are attributed to the month of their decision; a request
     * counts as approved when its decision was an approval (status APPROVED or later EXECUTED).
     */
    private Map<String, Long> summarize(YearMonth month) {
        // Run counters are maintained incrementally by the store (#13).
        RunMonthStats stats = FileStore.get().runMonthStats(month);
        long incidentsOpen = 0;
        long incidentsResolved = 0;
        for (Incident i : IncidentService.get().list(month)) {
            if (i.getStatus() == IncidentStatus.OPEN) {
                incidentsOpen++;
            } else if (i.getStatus() == IncidentStatus.RESOLVED) {
                incidentsResolved++;
            }
        }
        long requestsApproved = 0;
        long requestsRejected = 0;
        for (RequestSummary q : FileStore.get().listRunRequestSummaries()) {
            Instant decided = q.decidedAt();
            if (decided == null
                    || !YearMonth.from(decided.atZone(BatchClock.clock().getZone())).equals(month)) {
                continue;
            }
            if (q.status() == RequestStatus.REJECTED) {
                requestsRejected++;
            } else if (q.status() == RequestStatus.APPROVED
                    || q.status() == RequestStatus.EXECUTED) {
                requestsApproved++;
            }
        }
        Map<String, Long> summary = new LinkedHashMap<>();
        summary.put("runs", stats.runs());
        summary.put("success", stats.success());
        summary.put("failure", stats.failure());
        summary.put("unstable", stats.unstable());
        summary.put("incidentsOpen", incidentsOpen);
        summary.put("incidentsResolved", incidentsResolved);
        summary.put("requestsApproved", requestsApproved);
        summary.put("requestsRejected", requestsRejected);
        return summary;
    }

    // ---------------------------------------------------------------- Jelly helpers

    /** An approver set for display ({@code a1, a2}). */
    public String join(@CheckForNull List<String> approvers) {
        return Approvers.display(approvers);
    }

    /** Human-readable timestamp. */
    public String format(Instant instant) {
        return Dates.format(instant);
    }

    /** Human-readable duration. */
    public String duration(long durationMs) {
        return RunLinks.formatDuration(durationMs);
    }

    /** Root-relative build URL for a run record. */
    public String runUrl(RunRecord record) {
        return RunLinks.runUrl(record.getJobFullName(), record.getNumber());
    }

    /** Root-relative build URL for an incident's originating run, or null. */
    @CheckForNull
    public String incidentRunUrl(Incident incident) {
        return RunLinks.runUrlFromRunId(incident.getRunId());
    }

    /** Creation timestamp of an incident. */
    @CheckForNull
    public Instant incidentCreatedAt(Incident incident) {
        return IncidentsSection.creationTime(incident);
    }

    /** One-line parameter rendering; values come from the record already masked. */
    public String parameters(Map<String, String> parameters) {
        return RunLinks.formatParameters(parameters);
    }

    // ---------------------------------------------------------------- helpers

    /** @return true when the request was refused (non-GET verb on a read-only URL). */
    private static boolean refuseNonGet(StaplerRequest2 req, StaplerResponse2 rsp)
            throws IOException {
        String method = req.getMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            rsp.setHeader("Allow", "GET, HEAD");
            rsp.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                    "The history section is read-only; only GET is allowed");
            return true;
        }
        return false;
    }
}
