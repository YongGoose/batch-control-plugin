package io.jenkins.plugins.batchcontrol.action;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Action;
import hudson.model.Item;
import hudson.model.Job;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ActivationRequest;
import io.jenkins.plugins.batchcontrol.model.ActivationState;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
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
 * run control is on and the job requires approval, and it never changes state. The link to the
 * form is rendered only for a {@code BatchControl/Request} holder, the only user who may open it
 * (SPEC item 2).
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

    /** Run control on and the job requires approval: the gate applies to it. */
    static boolean isRunControlled(Job<?, ?> job) {
        if (!BatchControlGlobalConfiguration.get().isRunControlEnabled()) {
            return false;
        }
        BatchControlJobProperty property = job.getProperty(BatchControlJobProperty.class);
        return property != null && property.isApprovalRequired();
    }

    /**
     * Whether the notice is rendered: run control on, the job requires approval, it is not a
     * computed child (D-32, not controlled), and the viewer holds {@code Item/Read}.
     */
    public boolean isShown() {
        return job.hasPermission(Item.READ) && isRunControlled(job) && !ActivationService.isComputedChild(job);
    }

    public boolean isActivated() {
        return ActivationService.get().isActivated(job);
    }

    /** Whether an approved hold took the job out of service (as opposed to never activated). */
    public boolean isHeld() {
        if (isActivated()) {
            return false;
        }
        ActivationState state = ActivationService.get().getState(job);
        return state != null && state.getDeactivatedBy() != null;
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
