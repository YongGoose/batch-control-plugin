package io.jenkins.plugins.batchcontrol.action;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.ModelObject;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.ui.Dates;
import io.jenkins.plugins.batchcontrol.ui.Visibility;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerProxy;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

/**
 * The run request list at {@code /batch-control/requests/} (newest first, pages of
 * {@value #PAGE_SIZE} selected with {@code ?page=N}).
 *
 * <p>No state transition logic lives here; everything is delegated to
 * {@link RunRequestService}. Viewing requires one of the plugin permissions (requesters need to
 * see their own requests, approvers their inbox), enforced for the whole subtree by
 * {@link #getTarget()}.
 */
@Restricted(NoExternalUse.class)
public class RequestsSection implements ModelObject, StaplerProxy {

    /** Page size for the request list. */
    public static final int PAGE_SIZE = 50;

    /** Lazily computed, per-request cached sorted snapshot. */
    private List<RunRequest> sorted;

    @Override
    public Object getTarget() {
        // Gate the entire /batch-control/requests/** subtree (list, details, POST endpoints go
        // through their own additional checks in RequestItem).
        Jenkins.get().checkAnyPermission(
                BatchControlPermissions.REQUEST,
                BatchControlPermissions.APPROVE,
                BatchControlPermissions.MANAGE);
        return this;
    }

    @Override
    public String getDisplayName() {
        return "Run Requests";
    }

    /**
     * Serves the list URL {@code /batch-control/requests/}. The request history is append-only
     * (SPEC item 4): there is no modify/delete HTTP API, so every verb except GET/HEAD is
     * refused with 405.
     */
    public void doIndex(StaplerRequest2 req, StaplerResponse2 rsp)
            throws IOException, ServletException {
        String method = req.getMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            rsp.setHeader("Allow", "GET, HEAD");
            rsp.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                    "Run requests are append-only; only GET is allowed on this URL");
            return;
        }
        req.getView(this, "index.jelly").forward(req, rsp);
    }

    /**
     * Stapler: serves {@code /batch-control/requests/<id>/}; {@code null} renders a 404.
     * A request the caller may not see (P-09, S-01) renders exactly like a nonexistent one so
     * its existence is not disclosed.
     */
    @CheckForNull
    public RequestItem getDynamic(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        RunRequest request;
        try {
            request = RunRequestService.get().load(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (request == null || !Visibility.canSeeRunRequest(request)) {
            return null;
        }
        return new RequestItem(request);
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

    /** The requests shown on the current page, newest first. */
    public List<RunRequest> getPageItems() {
        List<RunRequest> all = allSorted();
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

    private List<RunRequest> allSorted() {
        if (sorted == null) {
            // P-09 visibility (S-01): rows the caller may not see are filtered out silently;
            // paging runs over the filtered list. Same predicate as the detail URL.
            List<RunRequest> all = new ArrayList<>();
            for (RunRequest request : RunRequestService.get().list()) {
                if (Visibility.canSeeRunRequest(request)) {
                    all.add(request);
                }
            }
            all.sort(Comparator.comparing(RunRequest::getCreatedAt)
                    .thenComparing(RunRequest::getId)
                    .reversed());
            sorted = all;
        }
        return sorted;
    }
}
