package io.jenkins.plugins.batchcontrol.action;

import hudson.model.ModelObject;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import io.jenkins.plugins.batchcontrol.ui.Dates;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerProxy;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

/**
 * The change record list at {@code /batch-control/changes/} (SPEC item 9). One month is shown at
 * a time, selected with {@code ?month=YYYY-MM} (default: current month), newest first, pages of
 * {@value #PAGE_SIZE} selected with {@code ?page=N}.
 *
 * <p>Change records are append-only audit data: this section is strictly read-only (no POST
 * endpoints at all) and requires {@code ViewHistory}, enforced for the whole subtree by
 * {@link #getTarget()}.
 */
public class ChangesSection implements ModelObject, StaplerProxy {

    /** Page size for the change record list. */
    public static final int PAGE_SIZE = 50;

    /** Lazily computed, per-request cached sorted snapshot of the selected month. */
    private List<ChangeRecord> sorted;

    @Override
    public Object getTarget() {
        // Gate the entire /batch-control/changes/** subtree.
        Jenkins.get().checkPermission(BatchControlPermissions.VIEW_HISTORY);
        return this;
    }

    @Override
    public String getDisplayName() {
        return "Change Records";
    }

    /**
     * Serves the list URL {@code /batch-control/changes/}. Change records are append-only
     * (SPEC item 9): there is no HTTP API that modifies them, so every verb except GET/HEAD is
     * refused with 405.
     */
    public void doIndex(StaplerRequest2 req, StaplerResponse2 rsp)
            throws IOException, ServletException {
        String method = req.getMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            rsp.setHeader("Allow", "GET, HEAD");
            rsp.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                    "Change records are append-only; only GET is allowed on this URL");
            return;
        }
        req.getView(this, "index.jelly").forward(req, rsp);
    }

    // ---------------------------------------------------------------- month selection

    /** The selected month, from {@code ?month=YYYY-MM}; current month on absence or garbage. */
    public YearMonth getMonth() {
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        if (req != null) {
            String raw = req.getParameter("month");
            if (raw != null && !raw.trim().isEmpty()) {
                try {
                    return YearMonth.parse(raw.trim());
                } catch (DateTimeParseException ignored) {
                    // Fall back to the current month on garbage input.
                }
            }
        }
        return YearMonth.now();
    }

    /** Selected month as {@code YYYY-MM} (value of the month input and the query parameter). */
    public String getMonthValue() {
        return getMonth().toString();
    }

    /** Previous month as {@code YYYY-MM} (for the navigation link). */
    public String getPreviousMonth() {
        return getMonth().minusMonths(1).toString();
    }

    /** Next month as {@code YYYY-MM} (for the navigation link). */
    public String getNextMonth() {
        return getMonth().plusMonths(1).toString();
    }

    /** Whether a next-month link makes sense (no records exist in the future). */
    public boolean isHasNextMonth() {
        return getMonth().isBefore(YearMonth.now());
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

    /** The change records shown on the current page, newest first. */
    public List<ChangeRecord> getPageItems() {
        List<ChangeRecord> all = allSorted();
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

    /** Jelly helper: human-readable timestamp. */
    public String format(Instant instant) {
        return Dates.format(instant);
    }

    private List<ChangeRecord> allSorted() {
        if (sorted == null) {
            List<ChangeRecord> all = new ArrayList<>(FileStore.get().listChangeRecords(getMonth()));
            all.sort(Comparator.comparing(ChangeRecord::getAt)
                    .thenComparing(ChangeRecord::getId)
                    .reversed());
            sorted = all;
        }
        return sorted;
    }
}
