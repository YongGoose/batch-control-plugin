package io.jenkins.plugins.batchcontrol.action;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.ModelObject;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.model.IncidentStatus;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRecord;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.ops.IncidentService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import io.jenkins.plugins.batchcontrol.ui.CsvWriter;
import io.jenkins.plugins.batchcontrol.ui.Dates;
import io.jenkins.plugins.batchcontrol.ui.FilterParser;
import io.jenkins.plugins.batchcontrol.ui.RunLinks;
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
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
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
 * the whole subtree with 403 otherwise (T-12-01, T-12-05). All URLs are read-only: non-GET
 * verbs get 405. Query parsing and validation live in {@link FilterParser}.
 */
public class HistorySection implements ModelObject, StaplerProxy {

    /** Page size for every table. */
    public static final int PAGE_SIZE = 50;

    private static final List<String> KINDS = List.of("runs", "incidents", "changes", "requests");

    private FilterParser.Filter filter;
    private List<RunRecord> runs;
    private List<Incident> incidents;
    private List<ChangeRecord> changes;
    private List<RunRequest> requests;

    @Override
    public Object getTarget() {
        // Gate the entire /batch-control/history/** subtree, CSV and JSON included
        // (SPEC item 12: without ViewHistory every history screen and CSV is 403).
        Jenkins.get().checkPermission(BatchControlPermissions.VIEW_HISTORY);
        return this;
    }

    @Override
    public String getDisplayName() {
        return "History";
    }

    /**
     * Serves the index URL {@code /batch-control/history/}. The whole section is read-only, so
     * every verb except GET/HEAD is refused with 405.
     */
    public void doIndex(StaplerRequest2 req, StaplerResponse2 rsp)
            throws IOException, ServletException {
        if (refuseNonGet(req, rsp)) {
            return;
        }
        req.getView(this, "index.jelly").forward(req, rsp);
    }

    // ---------------------------------------------------------------- monthly aggregate JSON

    /**
     * GET {@code summary?month=YYYY-MM} — the monthly aggregate as JSON (T-12-04). Absent month
     * defaults to the current month; a malformed month is a 400.
     */
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
        switch (rest) {
            case "/runs.csv" -> writeRunsCsv(rsp);
            case "/incidents.csv" -> writeIncidentsCsv(rsp);
            case "/changes.csv" -> writeChangesCsv(rsp);
            default -> writeRequestsCsv(rsp);
        }
    }

    private void writeRunsCsv(StaplerResponse2 rsp) throws IOException {
        CsvWriter csv = CsvWriter.open(rsp, "runs.csv");
        csv.row("runId", "jobFullName", "number", "causeType", "user", "parameters", "result",
                "startedAt", "durationMs", "abortedBy", "runRequestId");
        for (RunRecord r : getRunItems()) {
            csv.row(r.getRunId(), r.getJobFullName(), r.getNumber(), r.getCauseType(),
                    r.getUser(), RunLinks.formatParameters(r.getParameters()), r.getResult(),
                    r.getStartedAt(), r.getDurationMs(), r.getAbortedBy(), r.getRunRequestId());
        }
    }

    private void writeIncidentsCsv(StaplerResponse2 rsp) throws IOException {
        CsvWriter csv = CsvWriter.open(rsp, "incidents.csv");
        csv.row("id", "runId", "jobFullName", "result", "status", "createdAt",
                "resolvedByRunId", "rerunRequestIds");
        for (Incident i : getIncidentItems()) {
            csv.row(i.getId(), i.getRunId(), i.getJobFullName(), i.getResult(), i.getStatus(),
                    IncidentsSection.creationTime(i), i.getResolvedByRunId(),
                    String.join(" ", i.getRerunRequestIds()));
        }
    }

    private void writeChangesCsv(StaplerResponse2 rsp) throws IOException {
        CsvWriter csv = CsvWriter.open(rsp, "changes.csv");
        // The diff column is deliberately omitted: diffs are large multi-line blobs that belong
        // in the change detail screen, not in a spreadsheet.
        csv.row("id", "type", "target", "user", "at", "grantId", "detail");
        for (ChangeRecord c : getChangeItems()) {
            csv.row(c.getId(), c.getType(), c.getTarget(), c.getUser(), c.getAt(), c.getGrantId(),
                    c.getDetail());
        }
    }

    private void writeRequestsCsv(StaplerResponse2 rsp) throws IOException {
        CsvWriter csv = CsvWriter.open(rsp, "requests.csv");
        csv.row("id", "jobFullName", "parameters", "reason", "requester", "approver", "status",
                "createdAt", "decidedAt", "decisionComment", "selfApproved", "incidentId",
                "executedRunId");
        for (RunRequest q : getRequestItems()) {
            csv.row(q.getId(), q.getJobFullName(), RunLinks.formatParameters(q.getParameters()),
                    q.getReason(), q.getRequester(), q.getApprover(), q.getStatus(),
                    q.getCreatedAt(), q.getDecidedAt(), q.getDecisionComment(),
                    q.isSelfApproved(), q.getIncidentId(), q.getExecutedRunId());
        }
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

    // ---------------------------------------------------------------- filtered listings

    /** All run records matching the filter, newest first. */
    public List<RunRecord> getRunItems() {
        if (runs == null) {
            FilterParser.Filter f = getFilter();
            List<RunRecord> matched = new ArrayList<>();
            for (YearMonth m : f.months()) {
                for (RunRecord r : FileStore.get().listRunRecords(m)) {
                    if (f.inRange(r.getStartedAt()) && f.matchesJob(r.getJobFullName())
                            && f.matchesUser(r.getUser()) && f.matchesResult(r.getResult())) {
                        matched.add(r);
                    }
                }
            }
            matched.sort(Comparator.comparing(RunRecord::getStartedAt)
                    .thenComparing(RunRecord::getRunId)
                    .reversed());
            runs = matched;
        }
        return runs;
    }

    /** All incidents matching the filter, newest first. */
    public List<Incident> getIncidentItems() {
        if (incidents == null) {
            FilterParser.Filter f = getFilter();
            List<Incident> matched = new ArrayList<>();
            for (YearMonth m : f.months()) {
                for (Incident i : IncidentService.get().list(m)) {
                    if (f.inRange(IncidentsSection.creationTime(i))
                            && f.matchesJob(i.getJobFullName())
                            && f.matchesResult(i.getResult())
                            && f.matchesStatus(i.getStatus())) {
                        matched.add(i);
                    }
                }
            }
            matched.sort(Comparator
                    .comparing((Incident i) -> {
                        Instant at = IncidentsSection.creationTime(i);
                        return at == null ? Instant.EPOCH : at;
                    })
                    .thenComparing(Incident::getId)
                    .reversed());
            incidents = matched;
        }
        return incidents;
    }

    /** All change records matching the filter, newest first. */
    public List<ChangeRecord> getChangeItems() {
        if (changes == null) {
            FilterParser.Filter f = getFilter();
            List<ChangeRecord> matched = new ArrayList<>();
            for (YearMonth m : f.months()) {
                for (ChangeRecord c : FileStore.get().listChangeRecords(m)) {
                    if (f.inRange(c.getAt()) && f.matchesJob(c.getTarget())
                            && f.matchesUser(c.getUser())) {
                        matched.add(c);
                    }
                }
            }
            matched.sort(Comparator.comparing(ChangeRecord::getAt)
                    .thenComparing(ChangeRecord::getId)
                    .reversed());
            changes = matched;
        }
        return changes;
    }

    /** All run requests matching the filter (by creation time), newest first. */
    public List<RunRequest> getRequestItems() {
        if (requests == null) {
            FilterParser.Filter f = getFilter();
            List<RunRequest> matched = new ArrayList<>();
            for (RunRequest q : RunRequestService.get().list()) {
                if (f.inRange(q.getCreatedAt()) && f.matchesJob(q.getJobFullName())
                        && f.matchesUser(q.getRequester()) && f.matchesStatus(q.getStatus())) {
                    matched.add(q);
                }
            }
            matched.sort(Comparator.comparing(RunRequest::getCreatedAt)
                    .thenComparing(RunRequest::getId)
                    .reversed());
            requests = matched;
        }
        return requests;
    }

    private List<?> currentKindItems() {
        switch (getKind()) {
            case "incidents":
                return getIncidentItems();
            case "changes":
                return getChangeItems();
            case "requests":
                return getRequestItems();
            default:
                return getRunItems();
        }
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
        List<?> all = currentKindItems();
        int from = (getPage() - 1) * PAGE_SIZE;
        if (from >= all.size()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(all.subList(from, Math.min(from + PAGE_SIZE, all.size())));
    }

    public int getTotal() {
        return currentKindItems().size();
    }

    public boolean isHasPrevious() {
        return getPage() > 1;
    }

    public boolean isHasNext() {
        return getPage() * PAGE_SIZE < getTotal();
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
        long runCount = 0;
        long success = 0;
        long failure = 0;
        long unstable = 0;
        for (RunRecord r : FileStore.get().listRunRecords(month)) {
            runCount++;
            String result = r.getResult();
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
        for (Incident i : IncidentService.get().list(month)) {
            if (i.getStatus() == IncidentStatus.OPEN) {
                incidentsOpen++;
            } else if (i.getStatus() == IncidentStatus.RESOLVED) {
                incidentsResolved++;
            }
        }
        long requestsApproved = 0;
        long requestsRejected = 0;
        for (RunRequest q : RunRequestService.get().list()) {
            Instant decided = q.getDecidedAt();
            if (decided == null
                    || !YearMonth.from(decided.atZone(BatchClock.clock().getZone())).equals(month)) {
                continue;
            }
            if (q.getStatus() == RequestStatus.REJECTED) {
                requestsRejected++;
            } else if (q.getStatus() == RequestStatus.APPROVED
                    || q.getStatus() == RequestStatus.EXECUTED) {
                requestsApproved++;
            }
        }
        Map<String, Long> summary = new LinkedHashMap<>();
        summary.put("runs", runCount);
        summary.put("success", success);
        summary.put("failure", failure);
        summary.put("unstable", unstable);
        summary.put("incidentsOpen", incidentsOpen);
        summary.put("incidentsResolved", incidentsResolved);
        summary.put("requestsApproved", requestsApproved);
        summary.put("requestsRejected", requestsRejected);
        return summary;
    }

    // ---------------------------------------------------------------- Jelly helpers

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
