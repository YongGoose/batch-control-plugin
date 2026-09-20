package io.jenkins.plugins.batchcontrol.action;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Failure;
import hudson.model.ModelObject;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import io.jenkins.plugins.batchcontrol.ui.ApproverOptions;
import io.jenkins.plugins.batchcontrol.ui.Dates;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerProxy;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 * The grant screens at {@code /batch-control/grants/} (SPEC item 8, JIT permission requests).
 *
 * <p>URL space (fixed contract, asserted by tests):
 * <ul>
 *   <li>{@code /batch-control/grants/} — grant request list + active grant list + new request
 *       form (GET only; other verbs get 405)</li>
 *   <li>{@code POST /batch-control/grants/create} — submit a new grant request</li>
 *   <li>{@code /batch-control/grants/<id>/} — request detail; POST {@code approve} /
 *       {@code reject} / {@code cancel} (see {@link GrantRequestItem})</li>
 *   <li>{@code POST /batch-control/grants/active/<grantId>/revoke} — revoke an active grant
 *       (see {@link ActiveGrantsSection})</li>
 * </ul>
 *
 * <p>No state transition logic lives here; everything is delegated to
 * {@link GrantRequestService} and {@link GrantService}. Viewing requires one of the plugin
 * permissions (requesters see their requests, approvers their inbox, managers the active
 * grants), enforced for the whole subtree by {@link #getTarget()}.
 */
public class GrantsSection implements ModelObject, StaplerProxy {

    /** Page size for both the request list and the active grant list. */
    public static final int PAGE_SIZE = 50;

    /** Lazily computed, per-request cached sorted snapshot of grant requests. */
    private List<GrantRequest> sortedRequests;

    /** Lazily computed, per-request cached sorted snapshot of active grants. */
    private List<Grant> sortedActive;

    @Override
    public Object getTarget() {
        // Gate the entire /batch-control/grants/** subtree (list, details, POST endpoints go
        // through their own additional checks below and in GrantRequestItem/ActiveGrantsSection).
        Jenkins.get().checkAnyPermission(
                BatchControlPermissions.REQUEST_GRANT,
                BatchControlPermissions.APPROVE,
                BatchControlPermissions.MANAGE);
        return this;
    }

    @Override
    public String getDisplayName() {
        return "Grants";
    }

    /**
     * Serves the list URL {@code /batch-control/grants/}. Reads never change state, so every
     * verb except GET/HEAD is refused with 405 (the state-changing endpoints are separate URLs).
     */
    public void doIndex(StaplerRequest2 req, StaplerResponse2 rsp)
            throws IOException, ServletException {
        String method = req.getMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            rsp.setHeader("Allow", "GET, HEAD");
            rsp.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                    "Only GET is allowed on this URL");
            return;
        }
        req.getView(this, "index.jelly").forward(req, rsp);
    }

    // ---------------------------------------------------------------- routing

    /** Stapler: serves {@code /batch-control/grants/<id>/}; {@code null} renders a 404. */
    @CheckForNull
    public GrantRequestItem getDynamic(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        GrantRequest request;
        try {
            request = GrantRequestService.get().load(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return request == null ? null : new GrantRequestItem(request);
    }

    /** Stapler: serves {@code /batch-control/grants/active/...} (revoke endpoints). */
    public ActiveGrantsSection getActive() {
        return new ActiveGrantsSection();
    }

    // ---------------------------------------------------------------- create endpoint

    /**
     * POST {@code /batch-control/grants/create} — submits a new grant request. This method only
     * parses HTTP input; all business validation (action set non-empty, duration cap, approver
     * eligibility, reason required) is enforced by {@link GrantRequestService#create}.
     *
     * <p>Form fields: {@code scopeType} (JOB/FOLDER), {@code scopeFullName}, {@code actions}
     * (multi-valued checkboxes), {@code durationMinutes} (preset select),
     * {@code customDurationMinutes} (optional free number overriding the preset, capped
     * client-side at {@code maxGrantMinutes} and re-checked by the service), {@code reason},
     * {@code approver}.
     */
    @RequirePOST
    public void doCreate(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        Jenkins.get().checkPermission(BatchControlPermissions.REQUEST_GRANT);

        GrantScope scope = parseScope(req.getParameter("scopeType"), req.getParameter("scopeFullName"));
        List<GrantAction> actions = parseActions(req.getParameterValues("actions"));
        int durationMinutes = parseDuration(
                req.getParameter("durationMinutes"), req.getParameter("customDurationMinutes"));
        String reason = req.getParameter("reason");
        String approver = req.getParameter("approver");

        try {
            GrantRequestService.get().create(scope, actions, durationMinutes, reason, approver);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new Failure(e.getMessage() == null ? "The grant request was rejected" : e.getMessage());
        }
        rsp.sendRedirect2(".");
    }

    // ---------------------------------------------------------------- form input parsing

    private static GrantScope parseScope(@CheckForNull String rawType, @CheckForNull String rawFullName) {
        if (rawType == null) {
            throw new Failure("A scope type (JOB or FOLDER) is required");
        }
        GrantScope.Type type;
        try {
            type = GrantScope.Type.valueOf(rawType.trim());
        } catch (IllegalArgumentException e) {
            throw new Failure("Unknown scope type: must be JOB or FOLDER");
        }
        String fullName = rawFullName == null ? "" : rawFullName.trim();
        if (fullName.isEmpty()) {
            throw new Failure("A scope full name (job or folder path) is required");
        }
        return new GrantScope(type, fullName);
    }

    private static List<GrantAction> parseActions(@CheckForNull String[] raw) {
        List<GrantAction> actions = new ArrayList<>();
        if (raw != null) {
            for (String value : raw) {
                try {
                    actions.add(GrantAction.valueOf(value.trim()));
                } catch (IllegalArgumentException e) {
                    throw new Failure("Unknown action: " + value);
                }
            }
        }
        return actions;
    }

    private static int parseDuration(@CheckForNull String preset, @CheckForNull String custom) {
        String raw = custom != null && !custom.trim().isEmpty() ? custom : preset;
        if (raw == null || raw.trim().isEmpty()) {
            throw new Failure("A duration in minutes is required");
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new Failure("The duration must be a whole number of minutes");
        }
    }

    // ---------------------------------------------------------------- view model (used from Jelly)

    /** View gating for the new-request form; {@link #doCreate} re-checks for real. */
    public boolean isCanRequest() {
        return Jenkins.get().hasPermission(BatchControlPermissions.REQUEST_GRANT);
    }

    /** View gating for the Revoke buttons; the revoke endpoint re-checks for real. */
    public boolean isCanRevoke() {
        return Jenkins.get().hasPermission(BatchControlPermissions.MANAGE);
    }

    /** Preset duration choices from the global configuration. */
    public List<Integer> getDurationOptions() {
        return BatchControlGlobalConfiguration.get().getGrantDurationOptions();
    }

    /** Upper bound in minutes for a grant request (client-side cap; the service re-checks). */
    public int getMaxGrantMinutes() {
        return BatchControlGlobalConfiguration.get().getMaxGrantMinutes();
    }

    /** Approver candidates (global list minus the current user, per the self-approval policy). */
    public List<String> getApproverOptions() {
        return ApproverOptions.forJob(null);
    }

    /** Jelly helper: human-readable timestamp. */
    public String format(Instant instant) {
        return Dates.format(instant);
    }

    /** Jelly helper: comma-joined action list ("CREATE, CONFIGURE"). */
    public String join(Collection<?> items) {
        return items == null
                ? ""
                : items.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }

    // ---------------------------------------------------------------- paging: grant requests

    /** Current 1-based page of the request table, from the {@code page} query parameter. */
    public int getPage() {
        return pageParameter("page");
    }

    /** The grant requests shown on the current page, newest first. */
    public List<GrantRequest> getPageItems() {
        return slice(allRequestsSorted(), getPage());
    }

    public int getTotal() {
        return allRequestsSorted().size();
    }

    public boolean isHasPrevious() {
        return getPage() > 1;
    }

    public boolean isHasNext() {
        return getPage() * PAGE_SIZE < getTotal();
    }

    // ---------------------------------------------------------------- paging: active grants

    /** Current 1-based page of the active grant table, from {@code activePage}. */
    public int getActivePage() {
        return pageParameter("activePage");
    }

    /** The active grants shown on the current page, newest first. */
    public List<Grant> getActivePageItems() {
        return slice(allActiveSorted(), getActivePage());
    }

    public int getActiveTotal() {
        return allActiveSorted().size();
    }

    public boolean isHasActivePrevious() {
        return getActivePage() > 1;
    }

    public boolean isHasActiveNext() {
        return getActivePage() * PAGE_SIZE < getActiveTotal();
    }

    // ---------------------------------------------------------------- helpers

    private static int pageParameter(String name) {
        int page = 1;
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        if (req != null) {
            String raw = req.getParameter(name);
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

    private static <T> List<T> slice(List<T> all, int page) {
        int from = (page - 1) * PAGE_SIZE;
        if (from >= all.size()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(all.subList(from, Math.min(from + PAGE_SIZE, all.size())));
    }

    private List<GrantRequest> allRequestsSorted() {
        if (sortedRequests == null) {
            List<GrantRequest> all = new ArrayList<>(GrantRequestService.get().list());
            all.sort(Comparator.comparing(GrantRequest::getCreatedAt)
                    .thenComparing(GrantRequest::getId)
                    .reversed());
            sortedRequests = all;
        }
        return sortedRequests;
    }

    private List<Grant> allActiveSorted() {
        if (sortedActive == null) {
            List<Grant> all = new ArrayList<>(GrantService.get().listActive());
            all.sort(Comparator.comparing(Grant::getGrantedAt)
                    .thenComparing(Grant::getId)
                    .reversed());
            sortedActive = all;
        }
        return sortedActive;
    }
}
