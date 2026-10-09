package io.jenkins.plugins.batchcontrol.action;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.ModelObject;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.model.IncidentTransition;
import io.jenkins.plugins.batchcontrol.ops.IncidentService;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.Period;
import io.jenkins.plugins.batchcontrol.store.RecordPage;
import io.jenkins.plugins.batchcontrol.store.Store;
import io.jenkins.plugins.batchcontrol.ui.Dates;
import io.jenkins.plugins.batchcontrol.ui.HttpVerbs;
import io.jenkins.plugins.batchcontrol.ui.Paging;
import io.jenkins.plugins.batchcontrol.ui.RecordLookup;
import io.jenkins.plugins.batchcontrol.ui.SectionAccess;
import io.jenkins.plugins.batchcontrol.ui.Visibility;
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
 * The incident list at {@code /batch-control/incidents/} (SPEC item 11). One month is shown at a
 * time, selected with {@code ?month=YYYY-MM} (default: current month), newest first, pages of
 * {@value #PAGE_SIZE} ({@code ?page=N}).
 *
 * <p>Viewing requires {@code ViewHistory}, enforced for the whole subtree by
 * {@link #getTarget()}. The list itself is read-only (PUT/DELETE/PATCH are 405); the state-changing
 * endpoints live on {@link IncidentItem} under {@code /batch-control/incidents/<id>/}.
 */
@Restricted(NoExternalUse.class)
public class IncidentsSection implements ModelObject, StaplerProxy {

    /** Page size for the incident list. */
    public static final int PAGE_SIZE = Paging.PAGE_SIZE;

    /** Lazily computed, per-request cached page of the selected month. */
    private RecordPage<Incident> page;

    @Override
    public Object getTarget() {
        // Gate the entire /batch-control/incidents/** subtree.
        Jenkins.get().checkAnyPermission(SectionAccess.history());
        HttpVerbs.refuseUnsupported();
        return this;
    }

    @Override
    public String getDisplayName() {
        return "Incidents";
    }


    /** Permissions for this screen's {@code l:layout} (the same set its section gate checks). */
    public Permission[] getViewPermissions() {
        return SectionAccess.history();
    }

    /**
     * Stapler: serves {@code /batch-control/incidents/<id>/}; {@code null} renders a 404, also for
     * every failed lookup (S-39-01, {@link RecordLookup}). Every ViewHistory holder, whom
     * {@link #getTarget()} has admitted, may see every incident.
     */
    @CheckForNull
    public IncidentItem getDynamic(String id) {
        Incident incident = RecordLookup.find(id, "incident", i -> IncidentService.get().load(i),
                Incident::getId, i -> true);
        return incident == null ? null : new IncidentItem(incident);
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

    /** Whether a next-month link makes sense (no incidents exist in the future). */
    public boolean isHasNextMonth() {
        return getMonth().isBefore(YearMonth.now(BatchClock.clock()));
    }

    // ---------------------------------------------------------------- paging (used from Jelly)

    /** Current 1-based page, from the {@code page} query parameter ({@link Paging}). */
    public int getPage() {
        return Paging.currentPage();
    }

    /** The incidents shown on the current page, newest first. */
    public List<Incident> getPageItems() {
        return page().getItems();
    }

    /** Matching incidents read (a lower bound when {@link #isTruncated()}). */
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
        return "../history/incidents.csv?from=" + month.atDay(1) + "&to=" + month.atEndOfMonth();
    }

    public boolean isHasPrevious() {
        return Paging.hasPrevious(getPage());
    }

    public boolean isHasNext() {
        return page().isHasNext();
    }

    // ---------------------------------------------------------------- Jelly helpers

    /** Human-readable timestamp. */
    public String format(Instant instant) {
        return Dates.format(instant);
    }

    /** Root-relative build URL for the incident's originating run, or null for plain text (D-44). */
    @CheckForNull
    public String runUrl(Incident incident) {
        return Visibility.runUrlFromRunId(incident.getRunId());
    }

    /** Creation timestamp of an incident (Jelly helper for the list column). */
    @CheckForNull
    public Instant createdAt(Incident incident) {
        return creationTime(incident);
    }

    /**
     * The incident creation time: the timestamp of the first transition (the automatic OPEN
     * entry written when the incident is registered). Null only for malformed records.
     */
    @CheckForNull
    static Instant creationTime(Incident incident) {
        List<IncidentTransition> transitions = incident.getTransitions();
        return transitions == null || transitions.isEmpty() ? null : transitions.get(0).getAt();
    }

    /** The selected month as a store period in the plugin clock's zone (S-03). */
    private Period monthPeriod() {
        java.time.ZoneId zone = BatchClock.clock().getZone();
        YearMonth month = getMonth();
        return new Period(month.atDay(1).atStartOfDay(zone).toInstant(),
                month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant());
    }

    private RecordPage<Incident> page() {
        if (page == null) {
            // Bounded read: the monthly index is walked newest first up to the record cap.
            page = Store.get().pageIncidents(List.of(getMonth()), monthPeriod(), s -> true, i -> true,
                    Paging.offset(getPage()), PAGE_SIZE, Store.MAX_SCANNED_RECORDS);
        }
        return page;
    }
}
