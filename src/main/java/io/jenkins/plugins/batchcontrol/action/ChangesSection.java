package io.jenkins.plugins.batchcontrol.action;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.ModelObject;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.Period;
import io.jenkins.plugins.batchcontrol.store.RecordPage;
import io.jenkins.plugins.batchcontrol.store.Store;
import io.jenkins.plugins.batchcontrol.ui.Dates;
import io.jenkins.plugins.batchcontrol.ui.DiffSummary;
import io.jenkins.plugins.batchcontrol.ui.HttpVerbs;
import io.jenkins.plugins.batchcontrol.ui.Paging;
import io.jenkins.plugins.batchcontrol.ui.SectionAccess;
import io.jenkins.plugins.batchcontrol.ui.SectionTabs;
import java.time.Instant;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
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
@Restricted(NoExternalUse.class)
public class ChangesSection implements ModelObject, StaplerProxy {

    /** Page size for the change record list. */
    public static final int PAGE_SIZE = Paging.PAGE_SIZE;

    /** Lazily computed, per-request cached page of the selected month. */
    private RecordPage<ChangeRecord> page;

    @Override
    public Object getTarget() {
        // Gate the entire /batch-control/changes/** subtree.
        Jenkins.get().checkAnyPermission(SectionAccess.history());
        HttpVerbs.refuseUnsupported();
        return this;
    }

    @Override
    public String getDisplayName() {
        return SectionTabs.CHANGES_TITLE;
    }


    /** Permissions for this screen's {@code l:layout} (the same set its section gate checks). */
    public Permission[] getViewPermissions() {
        return SectionAccess.history();
    }

    /** Link predicates: a link to another screen is rendered only if the user may open it. */
    public SectionAccess getLinks() {
        return new SectionAccess();
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
        return YearMonth.now(BatchClock.clock());
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
        return getMonth().isBefore(YearMonth.now(BatchClock.clock()));
    }

    // ---------------------------------------------------------------- paging (used from Jelly)

    /** Current 1-based page, from the {@code page} query parameter ({@link Paging}). */
    public int getPage() {
        return Paging.currentPage();
    }

    /** The change records shown on the current page, newest first. */
    public List<ChangeRecord> getPageItems() {
        return page().getItems();
    }

    /** Matching records read (a lower bound when {@link #isTruncated()}). */
    public int getTotal() {
        return page().getMatched();
    }

    /** Whether the per-request record cap stopped the read: ask to narrow the filter. */
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

    /**
     * The complete CSV export of what this screen lists (S-03), relative to this section.
     * Pointed to by the truncation notice: the export is not bound by the per-screen record cap.
     * Only ISO dates and constant names go into it, so no encoding is needed.
     */
    public String getCsvUrl() {
        YearMonth month = getMonth();
        return "../history/changes.csv?from=" + month.atDay(1) + "&to=" + month.atEndOfMonth();
    }

    public boolean isHasPrevious() {
        return Paging.hasPrevious(getPage());
    }

    public boolean isHasNext() {
        return page().isHasNext();
    }

    /** Jelly helper: human-readable timestamp. */
    public String format(Instant instant) {
        return Dates.format(instant);
    }

    /**
     * Jelly helper: {@code +12 / -3 lines} for a record's diff, shown next to the disclosure
     * control so the size of a configuration change is readable without opening it (U-07).
     *
     * @return an empty string when there is no countable diff (no diff, or the "too large to
     *         store" placeholder), in which case the view shows no summary
     */
    public String diffSummary(@CheckForNull String diff) {
        return DiffSummary.of(diff);
    }

    /** The selected month as a store period in the plugin clock's zone (S-03). */
    private Period monthPeriod() {
        java.time.ZoneId zone = BatchClock.clock().getZone();
        YearMonth month = getMonth();
        return new Period(month.atDay(1).atStartOfDay(zone).toInstant(),
                month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant());
    }

    private RecordPage<ChangeRecord> page() {
        if (page == null) {
            // Bounded read: only this page's window is held and only its diffs are read.
            page = Store.get().pageChangeRecords(List.of(getMonth()), monthPeriod(), c -> true,
                    Paging.offset(getPage()), PAGE_SIZE, Store.MAX_SCANNED_RECORDS);
        }
        return page;
    }
}
