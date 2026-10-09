package io.jenkins.plugins.batchcontrol.action;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Job;
import hudson.widgets.Widget;
import java.util.Collection;
import java.util.List;
import jenkins.model.experimentalflags.UserExperimentalFlag;
import jenkins.widgets.WidgetFactory;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * The job page notices (trigger lock, approval, activation) as a card of core's new job
 * page. That page's Overview renders the job's widgets ({@link Job#getWidgets()}) as cards and
 * puts everything a classic page contributes ({@code jobMain.jelly}) into a card titled
 * "Legacy"; this widget lets the notices sit next to core's own cards instead.
 *
 * <p>Only contributed while the viewer has the new job page on, so the classic page (where job
 * widgets render in the side panel) is unchanged and keeps showing the notices through
 * {@code jobMain.jelly}, which renders nothing on the new page. The notices are the views of
 * {@link JobTriggerLockAction} and {@link JobActivationNoticeAction} ({@code notices.jelly}), so
 * both pages show the same text under the same permission rules. Read-only: no state changes.
 */
@Restricted(NoExternalUse.class)
public final class JobNoticesWidget extends Widget {

    /** Core's flag class, read through its public lookup (the class itself is restricted). */
    static final String NEW_JOB_PAGE_FLAG = "jenkins.model.experimentalflags.NewJobPageUserExperimentalFlag";

    private final Job<?, ?> job;

    JobNoticesWidget(Job<?, ?> job) {
        this.job = job;
    }

    public Job<?, ?> getJob() {
        return job;
    }

    /**
     * Core routes {@code <job>/<urlName>} to a widget before the job's actions, so the name must
     * not be {@code batch-control} (the run request form).
     */
    @Override
    public String getUrlName() {
        return "batch-control-notices";
    }

    public JobTriggerLockAction getTriggerLock() {
        return new JobTriggerLockAction(job);
    }

    public JobActivationNoticeAction getActivationNotice() {
        return new JobActivationNoticeAction(job);
    }

    /** Whether either notice has anything to say to this viewer (no empty card otherwise). */
    public boolean isShown() {
        return !getTriggerLock().getBlockingSwitches().isEmpty() || getActivationNotice().isShown();
    }

    /** Whether the current viewer sees core's new job page (the same lookup as {@code l:userExperimentalFlag}). */
    static boolean isNewJobPage() {
        return Boolean.TRUE.equals(UserExperimentalFlag.getFlagValueForCurrentUser(NEW_JOB_PAGE_FLAG));
    }

    /** Contributes the card to a job's widgets on the new job page only. */
    @Extension
    @Restricted(NoExternalUse.class)
    public static final class FactoryImpl extends WidgetFactory<Job, JobNoticesWidget> {

        @Override
        public Class<Job> type() {
            return Job.class;
        }

        @Override
        public Class<JobNoticesWidget> widgetType() {
            return JobNoticesWidget.class;
        }

        @NonNull
        @Override
        public Collection<JobNoticesWidget> createFor(@NonNull Job target) {
            if (!JobActivationNoticeAction.isRunControlEnabled() || !isNewJobPage()) {
                return List.of();
            }
            return List.of(new JobNoticesWidget(target));
        }
    }
}
