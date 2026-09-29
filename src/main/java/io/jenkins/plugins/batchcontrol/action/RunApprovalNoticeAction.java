package io.jenkins.plugins.batchcontrol.action;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Action;
import hudson.model.Job;
import hudson.model.Run;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * The build-page counterpart of the job-page approval notice (SPEC item 6, e2e re-audit DEF-31):
 * on every build of a job whose manual runs need an approved run request, the build page says so
 * and links the run request form for a viewer who may submit it. Rebuild and Retry are clicked on
 * the build page and a refusal there only produces core's generic "Failed." message, so the
 * explanation has to be where the button is.
 *
 * <p>Attached by {@link RunApprovalNoticeActionFactory}. No sidebar entry and no URL; its
 * {@code summary.jelly} is included by the build page. Every decision is delegated to the job's
 * {@link JobActivationNoticeAction}, so the two notices cannot disagree: shown only to
 * {@code Item/Read} holders while run control is on and the job requires approval; the form is
 * linked only for a viewer holding {@code BatchControl/Request} and {@code Item/Build} (D-38).
 * It never changes state.
 */
@Restricted(NoExternalUse.class)
public class RunApprovalNoticeAction implements Action {

    private final Run<?, ?> run;

    public RunApprovalNoticeAction(Run<?, ?> run) {
        this.run = run;
    }

    public Job<?, ?> getJob() {
        return run.getParent();
    }

    private JobActivationNoticeAction notice() {
        return new JobActivationNoticeAction(run.getParent());
    }

    /** Whether the notice is rendered (see {@link JobActivationNoticeAction#isApprovalRequired()}). */
    public boolean isShown() {
        return notice().isApprovalRequired();
    }

    /** Whether the run request form is linked ({@link JobActivationNoticeAction#isCanRequestRun()}). */
    public boolean isCanRequestRun() {
        return notice().isCanRequestRun();
    }

    /** Whether the viewer holds {@code BatchControl/Request} (but perhaps not {@code Item/Build}). */
    public boolean isCanRequest() {
        return notice().isCanRequest();
    }

    @Override
    @CheckForNull
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return "Run approval";
    }

    @Override
    @CheckForNull
    public String getUrlName() {
        return null;
    }
}
