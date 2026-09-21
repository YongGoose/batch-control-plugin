package io.jenkins.plugins.batchcontrol.action;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.ModelObject;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.model.IncidentTransition;
import io.jenkins.plugins.batchcontrol.ops.IncidentService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.ui.Dates;
import io.jenkins.plugins.batchcontrol.ui.RunLinks;
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
 * The incident list at {@code /batch-control/incidents/} (SPEC item 11). One month is shown at a
 * time, selected with {@code ?month=YYYY-MM} (default: current month), newest first, pages of
 * {@value #PAGE_SIZE} ({@code ?page=N}).
 *
 * <p>Viewing requires {@code ViewHistory}, enforced for the whole subtree by
 * {@link #getTarget()}. The list itself is read-only (405 on non-GET); the state-changing
 * endpoints live on {@link IncidentItem} under {@code /batch-control/incidents/<id>/}.
 */
public class IncidentsSection implements ModelObject, StaplerProxy {

    /** Page size for the incident list. */
    public static final int PAGE_SIZE = 50;

    /** Lazily computed, per-request cached sorted snapshot of the selected month. */
    private List<Incident> sorted;

    @Override
    public Object getTarget() {
        // Gate the entire /batch-control/incidents/** subtree.
        Jenkins.get().checkPermission(BatchControlPermissions.VIEW_HISTORY);
        return this;
    }

    @Override
    public String getDisplayName() {
        return "Incidents";
    }

    /**
     * Serves the list URL {@code /batch-control/incidents/}. State transitions happen only on
     * the per-incident endpoints; the list URL itself never changes state, so every verb except
     * GET/HEAD is refused with 405.
     */
    public void doIndex(StaplerRequest2 req, StaplerResponse2 rsp)
            throws IOException, ServletException {
        String method = req.getMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            rsp.setHeader("Allow", "GET, HEAD");
            rsp.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                    "The incident list is read-only; only GET is allowed on this URL");
            return;
        }
        req.getView(this, "index.jelly").forward(req, rsp);
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
        List<Incident> all = allSorted();
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

    private List<Incident> allSorted() {
        if (sorted == null) {
            List<Incident> all = new ArrayList<>(IncidentService.get().list(getMonth()));
            all.sort(Comparator
                    .comparing((Incident i) -> {
                        Instant at = creationTime(i);
                        return at == null ? Instant.EPOCH : at;
                    })
                    .thenComparing(Incident::getId)
                    .reversed());
            sorted = all;
        }
        return sorted;
    }
}
