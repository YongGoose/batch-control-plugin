package io.jenkins.plugins.batchcontrol.action;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.ModelObject;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.model.RunRecord;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.Period;
import io.jenkins.plugins.batchcontrol.store.RecordPage;
import io.jenkins.plugins.batchcontrol.store.Store;
import io.jenkins.plugins.batchcontrol.ui.Dates;
import io.jenkins.plugins.batchcontrol.ui.RunLinks;
import io.jenkins.plugins.batchcontrol.ui.HttpVerbs;
import io.jenkins.plugins.batchcontrol.ui.Paging;
import io.jenkins.plugins.batchcontrol.ui.SectionAccess;
import io.jenkins.plugins.batchcontrol.ui.Visibility;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
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
 * ({@link #getTarget()}); records are read-only, so PUT/DELETE/PATCH are 405 ({@code HttpVerbs}).
 */
@Restricted(NoExternalUse.class)
public class DashboardSection implements ModelObject, StaplerProxy {

    /** Page size for the run record list. */
    public static final int PAGE_SIZE = Paging.PAGE_SIZE;

    /** Default look-back window in days (SPEC item 10). */
    public static final int DEFAULT_DAYS = 7;

    /** Upper bound for {@code ?days=}; larger or invalid values fall back to the default. */
    public static final int MAX_DAYS = 365;

    /** Lazily computed, per-request cached page of the selected window. */
    private RecordPage<RunRecord> page;

    @Override
    public Object getTarget() {
        // Gate the entire /batch-control/dashboard/** subtree (SPEC item 12: 403 without it).
        Jenkins.get().checkAnyPermission(SectionAccess.history());
        HttpVerbs.refuseUnsupported();
        return this;
    }

    @Override
    public String getDisplayName() {
        return "Run Dashboard";
    }


    /** Permissions for this screen's {@code l:layout} (the same set its section gate checks). */
    public Permission[] getViewPermissions() {
        return SectionAccess.history();
    }

    /** Link predicates: a link to another screen is rendered only if the user may open it. */
    public SectionAccess getLinks() {
        return new SectionAccess();
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

    /** Current 1-based page, from the {@code page} query parameter ({@link Paging}). */
    public int getPage() {
        return Paging.currentPage();
    }

    /** The run records shown on the current page, newest first. */
    public List<RunRecord> getPageItems() {
        return page().getItems();
    }

    /** Matching records read (a lower bound when {@link #isTruncated()}). */
    public int getTotal() {
        return page().getMatched();
    }

    /** Whether the per-request record cap stopped the read (#13): ask to narrow the window. */
    public boolean isTruncated() {
        return page().isTruncated();
    }

    /**
     * Over-long lines (over 1 MiB) skipped while reading this page (security-11 N-02); the CSV
     * export skips the same lines, so the screen says so rather than look complete.
     */
    public int getOversized() {
        return page().getOversized();
    }

    public boolean isHasPrevious() {
        return Paging.hasPrevious(getPage());
    }

    public boolean isHasNext() {
        return page().isHasNext();
    }

    /**
     * The complete CSV export of what this screen lists (#13, S-03), relative to this section.
     * Pointed to by the truncation notice: the export is not bound by the per-screen record cap.
     * Only ISO dates and constant names go into it, so no encoding is needed.
     */
    public String getCsvUrl() {
        java.time.ZoneId zone = BatchClock.clock().getZone();
        java.time.LocalDate to = java.time.LocalDate.now(BatchClock.clock());
        java.time.LocalDate from = BatchClock.now().minus(Duration.ofDays(getDays()))
                .atZone(zone).toLocalDate();
        return "../history/runs.csv?from=" + from + "&to=" + to;
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

    /** Root-relative build URL ({@code job/a/job/b/12/}), or null for plain text (D-44). */
    @CheckForNull
    public String runUrl(RunRecord record) {
        return Visibility.runUrl(record.getJobFullName(), record.getNumber());
    }

    /** One-line parameter rendering; values come from the record already masked. */
    public String parameters(Map<String, String> parameters) {
        return RunLinks.formatParameters(parameters);
    }

    // ---------------------------------------------------------------- data

    private RecordPage<RunRecord> page() {
        if (page == null) {
            Instant cutoff = BatchClock.now().minus(Duration.ofDays(getDays()));
            YearMonth last = YearMonth.now(BatchClock.clock());
            YearMonth first = YearMonth.from(cutoff.atZone(BatchClock.clock().getZone()));
            List<YearMonth> months = new ArrayList<>();
            for (YearMonth m = first; !m.isAfter(last); m = m.plusMonths(1)) {
                months.add(m);
            }
            // Bounded read (#13): newest first, stops at the record cap.
            // Only records inside the window count toward the cap (S-03).
            page = Store.get().pageRunRecords(months, new Period(cutoff, null),
                    r -> !r.getStartedAt().isBefore(cutoff),
                    Paging.offset(getPage()), PAGE_SIZE, Store.MAX_SCANNED_RECORDS);
        }
        return page;
    }
}
