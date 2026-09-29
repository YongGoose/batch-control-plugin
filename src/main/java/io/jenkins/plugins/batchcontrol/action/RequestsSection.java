package io.jenkins.plugins.batchcontrol.action;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.ModelObject;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.model.Approvers;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.ui.Dates;
import io.jenkins.plugins.batchcontrol.ui.Visibility;
import io.jenkins.plugins.batchcontrol.ui.HttpVerbs;
import io.jenkins.plugins.batchcontrol.ui.Paging;
import io.jenkins.plugins.batchcontrol.ui.SectionAccess;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.StaplerProxy;
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
    public static final int PAGE_SIZE = Paging.PAGE_SIZE;

    /** Lazily computed, per-request cached sorted snapshot. */
    private List<RunRequest> sorted;

    @Override
    public Object getTarget() {
        // Gate the entire /batch-control/requests/** subtree (list, details, POST endpoints go
        // through their own additional checks in RequestItem).
        Jenkins.get().checkAnyPermission(SectionAccess.requests());
        HttpVerbs.refuseUnsupported();
        return this;
    }

    @Override
    public String getDisplayName() {
        return "Run Requests";
    }


    /** Permissions for this screen's {@code l:layout} (the same set its section gate checks). */
    public Permission[] getViewPermissions() {
        return SectionAccess.requests();
    }

    /** Link predicates: a link to another screen is rendered only if the user may open it. */
    public SectionAccess getLinks() {
        return new SectionAccess();
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

    /** Current 1-based page, from the {@code page} query parameter ({@link Paging}). */
    public int getPage() {
        return Paging.currentPage();
    }

    /** The requests shown on the current page, newest first. */
    public List<RunRequest> getPageItems() {
        return Paging.slice(allSorted(), getPage());
    }

    public int getTotal() {
        return allSorted().size();
    }

    public boolean isHasPrevious() {
        return Paging.hasPrevious(getPage());
    }

    public boolean isHasNext() {
        return Paging.hasNext(getPage(), getTotal());
    }

    /** Jelly helper: an approver set for display ({@code a1, a2}). */
    public String join(@CheckForNull List<String> approvers) {
        return Approvers.display(approvers);
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
