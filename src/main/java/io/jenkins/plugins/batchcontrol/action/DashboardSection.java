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
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.StaplerProxy;

/**
 * The run record dashboard at {@code /batch-control/dashboard/} (SPEC item 10): the most recent
 * builds of every job with cause, user, parameters, result, duration, who aborted it, and — for
 * APPROVED_REQUEST builds — a link to the originating run request.
 *
 * <p>D-67 (hosting review round 3): at most the {@value #LIMIT} most recent runs, newest first,
 * with a link to History for the rest; no look-back window and no paging. The read is bounded by
 * {@value #SCAN} records whatever the store holds, newest bucket first. Requires
 * {@code ViewHistory} for the whole subtree ({@link #getTarget()}); records are read-only, so
 * PUT/DELETE/PATCH are 405 ({@code HttpVerbs}).
 */
@Restricted(NoExternalUse.class)
public class DashboardSection implements ModelObject, StaplerProxy {

    /** Most runs the dashboard lists (D-67). */
    public static final int LIMIT = Paging.PAGE_SIZE;

    /**
     * Records read at most, newest appended first. A bucket is ordered by completion, the list by
     * start time, so a little more than {@link #LIMIT} is read to pick the most recently started.
     */
    public static final int SCAN = 10 * LIMIT;

    /** Lazily computed, per-request cached read. */
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

    // ---------------------------------------------------------------- data (used from Jelly)

    /** The most recent runs, newest first, at most {@link #LIMIT}. */
    public List<RunRecord> getItems() {
        return page().getItems();
    }

    /** Most runs listed, for the page text. */
    public int getLimit() {
        return LIMIT;
    }

    /**
     * Over-long lines (over 1 MiB) skipped while reading (security-11 N-02); the CSV export
     * skips the same lines, so the screen says so rather than look complete.
     */
    public int getOversized() {
        return page().getOversized();
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
            Instant now = BatchClock.now();
            YearMonth current = YearMonth.now(BatchClock.clock());
            List<YearMonth> months = new ArrayList<>();
            for (YearMonth m : Store.get().listStoredMonths()) {
                if (!m.isAfter(current)) {
                    months.add(m);
                }
            }
            // Bounded read (D-67): newest bucket first, stops after SCAN records.
            page = Store.get().pageRunRecords(months, new Period(null, now), r -> true, 0, LIMIT, SCAN);
        }
        return page;
    }
}
