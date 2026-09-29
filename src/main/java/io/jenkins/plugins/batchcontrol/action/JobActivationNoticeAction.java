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
 * The job-page activation notice (SPEC item 6a): whether a run-controlled job is activated, not
 * activated or on hold, with a link to the activation request form.
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

    /** Whether the viewer may open the request form ({@code BatchControl/Request}). */
    public boolean isCanRequest() {
        return Jenkins.get().hasPermission(BatchControlPermissions.REQUEST);
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
