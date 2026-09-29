package io.jenkins.plugins.batchcontrol.action;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ModelObject;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.model.ActivationRequest;
import io.jenkins.plugins.batchcontrol.model.Approvers;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.ui.Dates;
import io.jenkins.plugins.batchcontrol.ui.HttpVerbs;
import io.jenkins.plugins.batchcontrol.ui.Paging;
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
 * The activation request screen at {@code /batch-control/activations/} (SPEC item 6a): the
 * viewer's approval inbox (PENDING activation and hold requests on which they are a designated
 * approver) and every activation request they may see, newest first, in pages of
 * {@value #PAGE_SIZE} ({@code ?page=N}).
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

    @Override
    public Object getTarget() {
        Jenkins.get().checkAnyPermission(SectionAccess.activations());
        HttpVerbs.refuseUnsupported();
        return this;
    }

    @Override
    public String getDisplayName() {
        return "Activations";
    }

    /** Permissions for this screen's {@code l:layout} (the same set its section gate checks). */
    public Permission[] getViewPermissions() {
        return SectionAccess.activations();
    }

    /**
     * Stapler: serves {@code /batch-control/activations/<id>/}; {@code null} renders a 404. A
     * request the caller may not see renders exactly like a nonexistent one (P-09).
     */
    @CheckForNull
    public ActivationItem getDynamic(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        ActivationRequest request;
        try {
            request = ActivationService.get().load(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (request == null || !Visibility.canSeeActivationRequest(request)) {
            return null;
        }
        return new ActivationItem(request);
    }

    // ---------------------------------------------------------------- view model (Jelly)

    /**
     * The approval inbox: PENDING requests on which the viewer is a designated approver, oldest
     * first (the one closest to its timeout on top). Empty without {@code BatchControl/Approve}.
     * Bounded by the pending timeout, so it is not paged.
     */
    public List<ActivationRequest> getAwaitingDecision() {
        if (!Jenkins.get().hasPermission(BatchControlPermissions.APPROVE)) {
            return List.of();
        }
        return ActivationService.get().listPendingFor(Jenkins.getAuthentication2().getName());
    }

    public int getPage() {
        return Paging.currentPage();
    }

    /** The visible requests on the current page, newest first. */
    public List<ActivationRequest> getPageItems() {
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

    /** Root-relative job URL when the viewer may read the job, else {@code null} (plain text). */
    @CheckForNull
    public String jobUrl(ActivationRequest request) {
        Job<?, ?> job = Visibility.findVisibleJob(request.getJobFullName());
        return job != null && job.hasPermission(Item.READ) ? job.getUrl() : null;
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
}
