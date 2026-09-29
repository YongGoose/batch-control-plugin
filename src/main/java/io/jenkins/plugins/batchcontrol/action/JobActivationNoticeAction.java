package io.jenkins.plugins.batchcontrol.action;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Action;
import hudson.model.Item;
import hudson.model.Job;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ActivationRequest;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.ui.ActivationView;
import io.jenkins.plugins.batchcontrol.ui.SectionAccess;
import io.jenkins.plugins.batchcontrol.ui.Visibility;
import java.util.ArrayList;
import java.util.List;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * The job-page Batch Control notice area: whether a run-controlled job is activated, not
 * activated or on hold, with a link to the activation request form (SPEC item 6a), and, on an
 * approval-required job, that manual runs need an approved run request, with a link to the
 * request form (SPEC item 6, e2e-03 DEF-01).
 *
 * <p>Like {@link JobTriggerLockAction} it has no sidebar entry and no URL; its
 * {@code jobMain.jelly} renders on the job page. It is shown to {@code Item/Read} holders while
 * run control is on, for every job whatever {@code approvalRequired} says (D-46a), and it never
 * changes state. The link to the form is rendered only for a {@code BatchControl/Request}
 * holder, the only user who may open it (SPEC item 2). A computed child (a branch job) has no
 * activation of its own: the notice names the computed folder that carries it (D-46c) and
 * links to that folder for a viewer who may read it.
 */
@Restricted(NoExternalUse.class)
public class JobActivationNoticeAction implements Action {

    private final Job<?, ?> job;

    public JobActivationNoticeAction(Job<?, ?> job) {
        this.job = job;
    }

    public Job<?, ?> getJob() {
        return job;
    }

    @Override
    @CheckForNull
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return "Activation";
    }

    @Override
    @CheckForNull
    public String getUrlName() {
        return null;
    }

    /** Run control on: the activation gate applies to every non-computed job (D-46a). */
    static boolean isRunControlEnabled() {
        return BatchControlGlobalConfiguration.get().isRunControlEnabled();
    }

    /** Whether the notice is rendered: run control on and the viewer holds {@code Item/Read}. */
    public boolean isShown() {
        return job.hasPermission(Item.READ) && isRunControlEnabled();
    }

    /**
     * e2e-03 DEF-01 (SPEC item 6): whether the "manual runs need an approved request" notice is
     * rendered. Shown with the activation notice ({@link #isShown()}) on every job whose manual
     * runs need approval ({@link JobRequestAction#isActive()}), because a build button of another
     * plugin (Rebuild, Retry, a customised build button) or core's scripted Build Now reports a
     * refusal only as its own generic failure message; the job page is where the user can still
     * read why and where to go. The link to the request form follows {@link #isCanRequest()}.
     */
    public boolean isApprovalRequired() {
        return isShown() && new JobRequestAction(job).isActive();
    }

    public boolean isActivated() {
        return ActivationView.isActivated(job);
    }

    /** Whether an approved hold took the job out of service (as opposed to never activated). */
    public boolean isHeld() {
        return ActivationView.isHeld(job);
    }

    /**
     * The computed folder that carries this job's activation when the job is a computed child
     * (D-46c), else {@code null}.
     */
    @CheckForNull
    public Item getCarrier() {
        return ActivationView.carrierOf(job);
    }

    /** "activated", "on hold" or "not activated" for the carrying folder. */
    public String carrierState(Item carrier) {
        return ActivationView.stateLabel(carrier);
    }

    /** The carrying folder's URL when the viewer may read it, else {@code null} (plain text). */
    @CheckForNull
    public String carrierUrl(Item carrier) {
        return carrier.hasPermission(Item.READ) ? carrier.getUrl() : null;
    }

    /** S-13-08: the request kind as the screens word it. */
    public String actionLabel(ActivationRequest.Action action) {
        return ActivationView.actionLabel(action);
    }

    /** Whether the viewer may open the activation request form ({@code BatchControl/Request}). */
    public boolean isCanRequest() {
        return Jenkins.get().hasPermission(BatchControlPermissions.REQUEST);
    }

    /**
     * Whether the viewer may submit a run request for this job, so the run request form is
     * linked (e2e-03 DEF-12): {@code BatchControl/Request} and {@code Item/Build} on the job
     * (D-38), the same predicate as the sidebar entry.
     */
    public boolean isCanRequestRun() {
        return new JobRequestAction(job).isCanRequestRun();
    }

    /** The PENDING requests of this job the viewer may see (P-09). */
    public List<ActivationRequest> getPendingRequests() {
        List<ActivationRequest> visible = new ArrayList<>();
        if (!new SectionAccess().isActivations()) {
            return visible;
        }
        for (ActivationRequest request : ActivationService.get().listPendingForJob(job.getFullName())) {
            if (Visibility.canSeeActivationRequest(request)) {
                visible.add(request);
            }
        }
        return visible;
    }
}
