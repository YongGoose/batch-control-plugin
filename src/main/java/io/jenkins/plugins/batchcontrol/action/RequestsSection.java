package io.jenkins.plugins.batchcontrol.action;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.ModelObject;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.model.Approvers;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.ui.Dates;
import io.jenkins.plugins.batchcontrol.ui.Visibility;
import io.jenkins.plugins.batchcontrol.ui.HttpVerbs;
import io.jenkins.plugins.batchcontrol.ui.Paging;
import io.jenkins.plugins.batchcontrol.ui.RecordLookup;
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
 * The run request lists at {@code /batch-control/requests/} (D-66): pending requests (oldest
 * first), active ones (approved, run not started) and ended ones (newest first), each in pages of
 * {@value #PAGE_SIZE} selected with {@code ?pendingPage=N}, {@code ?activePage=N} and
 * {@code ?endedPage=N}.
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

    /** Per-request cache of the pending, active and ended lists (D-66). */
    private List<List<RunRequest>> lists;

    @Override
    public Object getTarget() {
        // Gate the entire /batch-control/requests/** subtree (list, details, POST endpoints go
        // through their own additional checks in RequestItem). D-38b: a user with run requests
        // of their own is admitted too (Request held only on some jobs or folders); rows and
        // detail pages stay limited by Visibility.canSeeRunRequest.
        if (!SectionAccess.canOpenRequests()) {
            Jenkins.get().checkAnyPermission(SectionAccess.requests());
        }
        HttpVerbs.refuseUnsupported();
        return this;
    }

    @Override
    public String getDisplayName() {
        return "Run Requests";
    }


    /** Permissions for this screen's {@code l:layout} (the same set its section gate checks). */
    public Permission[] getViewPermissions() {
        return SectionAccess.viewPermissions(SectionAccess.requests(), SectionAccess.canOpenRequests());
    }

    /** Link predicates: a link to another screen is rendered only if the user may open it. */
    public SectionAccess getLinks() {
        return new SectionAccess();
    }

    /**
     * Stapler: serves {@code /batch-control/requests/<id>/}; {@code null} renders a 404.
     * A request the caller may not see (P-09, S-01) renders exactly like a nonexistent one so
     * its existence is not disclosed, and so does every failed lookup (S-39-01, {@link RecordLookup}):
     * a malformed id such as {@code <id>.VALUES} never reaches the store, and an unreadable record
     * answers 404, not 500.
     */
    @CheckForNull
    public RequestItem getDynamic(String id) {
        RunRequest request = RecordLookup.find(id, "run request", i -> RunRequestService.get().load(i),
                RunRequest::getId, Visibility::canSeeRunRequest);
        return request == null ? null : new RequestItem(request);
    }

    // ------------------------------------------- the three lists (D-66, used from Jelly)

    /** Pending requests on the current page, oldest first (closest to its timeout on top). */
    public List<RunRequest> getPendingItems() {
        return Paging.slice(lists().get(0), getPendingPage());
    }

    public int getPendingPage() {
        return Paging.currentPage("pendingPage");
    }

    public int getPendingTotal() {
        return lists().get(0).size();
    }

    public boolean isHasPendingPrevious() {
        return Paging.hasPrevious(getPendingPage());
    }

    public boolean isHasPendingNext() {
        return Paging.hasNext(getPendingPage(), getPendingTotal());
    }

    /** Approved requests whose run has not started yet, on the current page, newest first. */
    public List<RunRequest> getActiveItems() {
        return Paging.slice(lists().get(1), getActivePage());
    }

    public int getActivePage() {
        return Paging.currentPage("activePage");
    }

    public int getActiveTotal() {
        return lists().get(1).size();
    }

    public boolean isHasActivePrevious() {
        return Paging.hasPrevious(getActivePage());
    }

    public boolean isHasActiveNext() {
        return Paging.hasNext(getActivePage(), getActiveTotal());
    }

    /** Executed, rejected, cancelled, expired and invalidated requests, newest first. */
    public List<RunRequest> getEndedItems() {
        return Paging.slice(lists().get(2), getEndedPage());
    }

    public int getEndedPage() {
        return Paging.currentPage("endedPage");
    }

    public int getEndedTotal() {
        return lists().get(2).size();
    }

    public boolean isHasEndedPrevious() {
        return Paging.hasPrevious(getEndedPage());
    }

    public boolean isHasEndedNext() {
        return Paging.hasNext(getEndedPage(), getEndedTotal());
    }

    /** Every visible request, for the overall count. */
    public int getTotal() {
        return allSorted().size();
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

    /**
     * D-66: pending, active (approved, run not started) and ended, each request in one list.
     */
    private List<List<RunRequest>> lists() {
        if (lists == null) {
            List<RunRequest> pending = new ArrayList<>();
            List<RunRequest> active = new ArrayList<>();
            List<RunRequest> ended = new ArrayList<>();
            for (RunRequest request : allSorted()) {
                if (request.getStatus() == RequestStatus.PENDING) {
                    pending.add(request);
                } else if (request.getStatus() == RequestStatus.APPROVED) {
                    active.add(request);
                } else {
                    ended.add(request);
                }
            }
            java.util.Collections.reverse(pending);
            lists = List.of(pending, active, ended);
        }
        return lists;
    }
}
