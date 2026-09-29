package io.jenkins.plugins.batchcontrol.action;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.ModelObject;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.model.IncidentTransition;
import io.jenkins.plugins.batchcontrol.ops.IncidentService;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import io.jenkins.plugins.batchcontrol.store.RecordPage;
import io.jenkins.plugins.batchcontrol.store.Store;
import io.jenkins.plugins.batchcontrol.ui.Dates;
import io.jenkins.plugins.batchcontrol.ui.RunLinks;
import io.jenkins.plugins.batchcontrol.ui.HttpVerbs;
import io.jenkins.plugins.batchcontrol.ui.SectionAccess;
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
    public static final int PAGE_SIZE = 50;

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

    /** Link predicates: a link to another screen is rendered only if the user may open it. */
    public SectionAccess getLinks() {
        return new SectionAccess();
    }

    /** Stapler: serves {@code /batch-control/incidents/<id>/}; {@code null} renders a 404. */
    @CheckForNull
    public IncidentItem getDynamic(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        Incident incident;
        try {
            incident = IncidentService.get().load(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
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

    /** The incidents shown on the current page, newest first. */
    public List<Incident> getPageItems() {
        return page().getItems();
    }

    /** Matching incidents read (a lower bound when {@link #isTruncated()}). */
    public int getTotal() {
        return page().getMatched();
    }

    /** Whether the per-request record cap stopped the read (#13): ask to narrow the filter. */
    public boolean isTruncated() {
        return page().isTruncated();
    }

    public boolean isHasPrevious() {
        return getPage() > 1;
    }

    public boolean isHasNext() {
        return page().isHasNext();
    }

    // ---------------------------------------------------------------- Jelly helpers

    /** Human-readable timestamp. */
    public String format(Instant instant) {
        return Dates.format(instant);
    }

    /** Root-relative build URL for the incident's originating run, or null. */
    @CheckForNull
    public String runUrl(Incident incident) {
        return RunLinks.runUrlFromRunId(incident.getRunId());
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

    private RecordPage<Incident> page() {
        if (page == null) {
            // Bounded read (#13): the monthly index is walked newest first up to the record cap.
            page = FileStore.get().pageIncidents(List.of(getMonth()), i -> true,
                    (getPage() - 1) * PAGE_SIZE, PAGE_SIZE, Store.MAX_SCANNED_RECORDS);
        }
        return page;
    }
}
