package io.jenkins.plugins.batchcontrol.action;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Item;
import hudson.model.ModelObject;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.model.ActivationRequest;
import io.jenkins.plugins.batchcontrol.model.ActivationState;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.Approvers;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import io.jenkins.plugins.batchcontrol.ui.ActivationView;
import io.jenkins.plugins.batchcontrol.ui.Dates;
import io.jenkins.plugins.batchcontrol.ui.HttpVerbs;
import io.jenkins.plugins.batchcontrol.ui.Paging;
import io.jenkins.plugins.batchcontrol.ui.RecordLookup;
import io.jenkins.plugins.batchcontrol.ui.SectionAccess;
import io.jenkins.plugins.batchcontrol.ui.Visibility;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.StaplerProxy;

/**
 * The activation request screen at {@code /batch-control/activations/} (SPEC item 6a, D-66):
 * the activation and hold requests the viewer may see as pending (those awaiting the viewer's
 * decision marked), active (the request in effect for its job) and ended, each in pages of
 * {@value #PAGE_SIZE} ({@code ?pendingPage=N}, {@code ?activePage=N}, {@code ?endedPage=N}).
 *
 * <p>The subtree is gated by {@link #getTarget()} with {@link SectionAccess#activations()}; rows
 * and detail pages follow {@link Visibility#canSeeActivationRequest} (P-09). No state logic lives
 * here; decisions go through {@link ActivationItem} to {@link ActivationService}.
 */
@Restricted(NoExternalUse.class)
public class ActivationsSection implements ModelObject, StaplerProxy {

    /** Page size for the request list. */
    public static final int PAGE_SIZE = Paging.PAGE_SIZE;

    /** Per-request cache of the visible, sorted list. */
    private List<ActivationRequest> sorted;

    /** Per-request cache of the pending, active and ended lists (D-66). */
    private List<List<ActivationRequest>> lists;

    /** Per-request cache of the ids awaiting the viewer's decision. */
    private java.util.Set<String> awaiting;

    @Override
    public Object getTarget() {
        // D-38c: every user admitted to the root is admitted here; rows and detail
        // pages stay limited by Visibility.canSeeActivationRequest.
        if (!SectionAccess.canOpenActivations()) {
            Jenkins.get().checkAnyPermission(SectionAccess.activations());
        }
        HttpVerbs.refuseUnsupported();
        return this;
    }

    @Override
    public String getDisplayName() {
        return "Activations";
    }

    /** Permissions for this screen's {@code l:layout} (the same set its section gate checks). */
    public Permission[] getViewPermissions() {
        return SectionAccess.viewPermissions(SectionAccess.activations(), SectionAccess.canOpenActivations());
    }

    /**
     * Stapler: serves {@code /batch-control/activations/<id>/}; {@code null} renders a 404. A
     * request the caller may not see renders exactly like a nonexistent one (P-09), and so does
     * every failed lookup (S-39-01, {@link RecordLookup}).
     */
    @CheckForNull
    public ActivationItem getDynamic(String id) {
        ActivationRequest request = RecordLookup.find(id, "activation request",
                i -> ActivationService.get().load(i), ActivationRequest::getId,
                Visibility::canSeeActivationRequest);
        return request == null ? null : new ActivationItem(request);
    }

    // ---------------------------------------------------------------- view model (Jelly)

    /**
     * Whether the viewer is a designated approver of this pending request (the Activations tab
     * badge's predicate), so its row says "awaiting your decision". D-66: the former inbox
     * table repeated these rows; they are now marked in the Pending list instead.
     */
    public boolean isAwaitingMyDecision(ActivationRequest request) {
        if (awaiting == null) {
            awaiting = new java.util.HashSet<>();
            for (ActivationRequest r : ActivationService.get().listAwaitingDecision(Jenkins.getAuthentication2())) {
                awaiting.add(r.getId());
            }
        }
        return awaiting.contains(request.getId());
    }

    /** Pending requests on the current page, oldest first (closest to its timeout on top). */
    public List<ActivationRequest> getPendingItems() {
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

    /**
     * Approved requests still in effect: the request that set its job's current activation state
     * ({@link ActivationState#getRequestId()}), newest first.
     */
    public List<ActivationRequest> getActiveItems() {
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

    /** Superseded, rejected, cancelled, expired and invalidated requests, newest first. */
    public List<ActivationRequest> getEndedItems() {
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

    /** Every visible request, for the empty state. */
    public int getTotal() {
        return allSorted().size();
    }

    /** How an ended request ended, in words: a superseded approval says so. */
    public String endedLabel(ActivationRequest request) {
        return request.getStatus() == RequestStatus.APPROVED ? "APPROVED (superseded)" : String.valueOf(request.getStatus());
    }

    /**
     * Root-relative URL of the request's job or computed folder (D-46c) when the viewer may read
     * it, else {@code null} (plain text).
     */
    @CheckForNull
    public String jobUrl(ActivationRequest request) {
        Item item = Visibility.findVisibleItem(request.getJobFullName());
        return item != null && item.hasPermission(Item.READ) ? item.getUrl() : null;
    }

    /** S-13-08: ACTIVATE and HOLD worded so they cannot be mistaken for each other. */
    public String actionLabel(ActivationRequest.Action action) {
        return ActivationView.actionLabel(action);
    }

    /** Jelly helper: an approver set for display ({@code a1, a2}). */
    public String join(@CheckForNull List<String> approvers) {
        return Approvers.display(approvers);
    }

    /** Jelly helper: human-readable timestamp. */
    public String format(@CheckForNull Instant instant) {
        return Dates.format(instant);
    }

    private List<ActivationRequest> allSorted() {
        if (sorted == null) {
            List<ActivationRequest> all = new ArrayList<>();
            for (ActivationRequest request : ActivationService.get().list()) {
                if (Visibility.canSeeActivationRequest(request)) {
                    all.add(request);
                }
            }
            all.sort(Comparator.comparing(ActivationRequest::getCreatedAt)
                    .thenComparing(ActivationRequest::getId)
                    .reversed());
            sorted = all;
        }
        return sorted;
    }

    /** D-66: pending, active (in effect) and ended, each request in one list. */
    private List<List<ActivationRequest>> lists() {
        if (lists == null) {
            List<ActivationRequest> pending = new ArrayList<>();
            List<ActivationRequest> active = new ArrayList<>();
            List<ActivationRequest> ended = new ArrayList<>();
            java.util.Map<String, java.util.Optional<String>> inEffect = new java.util.HashMap<>();
            for (ActivationRequest request : allSorted()) {
                if (request.getStatus() == RequestStatus.PENDING) {
                    pending.add(request);
                    continue;
                }
                if (request.getStatus() == RequestStatus.APPROVED) {
                    String current = inEffect.computeIfAbsent(request.getJobFullName(), name -> {
                        ActivationState state = ActivationService.get().getState(name);
                        return java.util.Optional.ofNullable(state == null ? null : state.getRequestId());
                    }).orElse(null);
                    if (request.getId().equals(current)) {
                        active.add(request);
                        continue;
                    }
                }
                ended.add(request);
            }
            java.util.Collections.reverse(pending);
            lists = List.of(pending, active, ended);
        }
        return lists;
    }
}
