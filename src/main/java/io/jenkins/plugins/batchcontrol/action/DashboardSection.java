package io.jenkins.plugins.batchcontrol.action;

import hudson.model.ModelObject;
import io.jenkins.plugins.batchcontrol.model.RunRecord;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import io.jenkins.plugins.batchcontrol.ui.Dates;
import io.jenkins.plugins.batchcontrol.ui.RunLinks;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerProxy;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

/**
 * The run record dashboard at {@code /batch-control/dashboard/} (SPEC item 10): every build of
 * every job with cause, user, parameters, result, duration, who aborted it, and — for
 * APPROVED_REQUEST builds — a link to the originating run request.
 *
 * <p>Default view: the last {@value #DEFAULT_DAYS} days, newest first, pages of
 * {@value #PAGE_SIZE} ({@code ?page=N}). The window can be widened with {@code ?days=N}
 * (validated, capped at {@value #MAX_DAYS}). Requires {@code ViewHistory} for the whole subtree
 * ({@link #getTarget()}); records are read-only, so every verb except GET/HEAD is 405.
 */
public class DashboardSection implements ModelObject, StaplerProxy {

    /** Page size for the run record list. */
    public static final int PAGE_SIZE = 50;

    /** Default look-back window in days (SPEC item 10). */
    public static final int DEFAULT_DAYS = 7;

    /** Upper bound for {@code ?days=}; larger or invalid values fall back to the default. */
    public static final int MAX_DAYS = 365;

    /** Lazily computed, per-request cached sorted snapshot of the selected window. */
    private List<RunRecord> sorted;

    @Override
    public Object getTarget() {
        // Gate the entire /batch-control/dashboard/** subtree (SPEC item 12: 403 without it).
        Jenkins.get().checkPermission(BatchControlPermissions.VIEW_HISTORY);
        return this;
    }

    @Override
    public String getDisplayName() {
        return "Run Dashboard";
    }

    /**
     * Serves the list URL {@code /batch-control/dashboard/}. Run records are append-only audit
     * data (SPEC item 10): there is no modifying HTTP API, so every verb except GET/HEAD is
     * refused with 405.
     */
    public void doIndex(StaplerRequest2 req, StaplerResponse2 rsp)
            throws IOException, ServletException {
        String method = req.getMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            rsp.setHeader("Allow", "GET, HEAD");
            rsp.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                    "Run records are append-only; only GET is allowed on this URL");
            return;
        }
        req.getView(this, "index.jelly").forward(req, rsp);
    }

    // ---------------------------------------------------------------- window selection

    /** The look-back window from {@code ?days=}; default on absence, garbage or out-of-range. */
    public int getDays() {
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        if (req != null) {
            String raw = req.getParameter("days");
            if (raw != null && !raw.trim().isEmpty()) {
                try {
                    int days = Integer.parseInt(raw.trim());
                    if (days >= 1 && days <= MAX_DAYS) {
                        return days;
                    }
                } catch (NumberFormatException ignored) {
                    // Fall through to the default.
                }
            }
        }
        return DEFAULT_DAYS;
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

    /** The run records shown on the current page, newest first. */
    public List<RunRecord> getPageItems() {
        List<RunRecord> all = allSorted();
        int from = (getPage() - 1) * PAGE_SIZE;
        if (from >= all.size()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(all.subList(from, Math.min(from + PAGE_SIZE, all.size())));
    }

    public int getTotal() {
        return allSorted().size();
    }

    public boolean isHasPrevious() {
        return getPage() > 1;
    }

    public boolean isHasNext() {
        return getPage() * PAGE_SIZE < getTotal();
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

    /** Root-relative build URL ({@code job/a/job/b/12/}). */
    public String runUrl(RunRecord record) {
        return RunLinks.runUrl(record.getJobFullName(), record.getNumber());
    }

    /** One-line parameter rendering; values come from the record already masked. */
    public String parameters(Map<String, String> parameters) {
        return RunLinks.formatParameters(parameters);
    }

    // ---------------------------------------------------------------- data

    private List<RunRecord> allSorted() {
        if (sorted == null) {
            Instant now = BatchClock.now();
            Instant cutoff = now.minus(Duration.ofDays(getDays()));
            YearMonth last = YearMonth.now(BatchClock.clock());
            YearMonth first = YearMonth.from(cutoff.atZone(BatchClock.clock().getZone()));
            List<RunRecord> window = new ArrayList<>();
            for (YearMonth m = first; !m.isAfter(last); m = m.plusMonths(1)) {
                for (RunRecord record : FileStore.get().listRunRecords(m)) {
                    if (!record.getStartedAt().isBefore(cutoff)) {
                        window.add(record);
                    }
                }
            }
            window.sort(Comparator.comparing(RunRecord::getStartedAt)
                    .thenComparing(RunRecord::getRunId)
                    .reversed());
            sorted = window;
        }
        return sorted;
    }
}
