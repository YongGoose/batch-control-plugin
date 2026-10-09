package io.jenkins.plugins.batchcontrol.action;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Action;
import hudson.model.Item;
import hudson.model.Job;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.ui.SectionAccess;
import java.util.List;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * The job-page notice for blocked unattended triggers (SPEC item 6). A job whose
 * {@code blockTimer} or {@code blockUpstream} switch is on refuses those runs with nobody to tell,
 * so its main page ({@code jobMain.jelly}, included for every action by both the Freestyle and the
 * Pipeline job page) names the switch and says it is cleared in the job configuration, which is
 * change-controlled.
 *
 * <p>The action has no sidebar entry and no URL ({@link #getIconFileName()} and
 * {@link #getUrlName()} are {@code null}); it is attached to every job by
 * {@link JobRequestActionFactory} and decides from {@link #getBlockingSwitches()} whether there
 * is anything to show. It never changes state.
 */
@Restricted(NoExternalUse.class)
public class JobTriggerLockAction implements Action {

    /** The {@code blockTimer} switch, as named by {@link BatchControlJobProperty#getBlockingSwitches()}. */
    static final String BLOCK_TIMER = "blockTimer";

    /** The {@code blockUpstream} switch, as named by {@link BatchControlJobProperty#getBlockingSwitches()}. */
    static final String BLOCK_UPSTREAM = "blockUpstream";

    private final Job<?, ?> job;

    public JobTriggerLockAction(Job<?, ?> job) {
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
        return "Trigger Lock";
    }

    @Override
    @CheckForNull
    public String getUrlName() {
        return null;
    }

    /**
     * The switches that refuse unattended runs of this job right now, for a viewer holding
     * {@code Item/Read}, whether or not the job requires approval for manual runs; empty when run
     * control is off, both switches are off, or the viewer may not read the job (the notice is
     * then not rendered).
     */
    public List<String> getBlockingSwitches() {
        if (!job.hasPermission(Item.READ)) {
            return List.of();
        }
        // e2e-03 DEF-15 (SPEC item 6, D-46a): the switches refuse unattended runs whatever
        // approvalRequired says, and getBlockingSwitches() follows the switches alone.
        BatchControlJobProperty property = job.getProperty(BatchControlJobProperty.class);
        return property == null ? List.of() : property.getBlockingSwitches();
    }

    /** Whether {@code blockTimer} is refusing cron runs (Jelly). */
    public boolean isTimerBlocked() {
        return getBlockingSwitches().contains(BLOCK_TIMER);
    }

    /** Whether {@code blockUpstream} is refusing upstream-triggered runs (Jelly). */
    public boolean isUpstreamBlocked() {
        return getBlockingSwitches().contains(BLOCK_UPSTREAM);
    }

    /** Whether the viewer may open the job configuration, so the notice links to it. */
    public boolean isCanConfigure() {
        return job.hasPermission(Item.CONFIGURE);
    }

    /** Whether change control is on, so clearing a switch needs a permission window. */
    public boolean isChangeControlEnabled() {
        return BatchControlGlobalConfiguration.get().isChangeControlEnabled();
    }

    /** Link predicates: the Grants screen is linked only for a user who may open it. */
    public SectionAccess getLinks() {
        return new SectionAccess();
    }
}
