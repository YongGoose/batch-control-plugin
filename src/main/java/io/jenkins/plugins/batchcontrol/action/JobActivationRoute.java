package io.jenkins.plugins.batchcontrol.action;

import hudson.model.Job;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * The URL space {@code /job/<name>/batch-control/activation[/...]} (SPEC item 6a), handed over by
 * {@link JobRequestAction#getTarget()}. Deliberately not a {@link hudson.model.ModelObject}: core's
 * breadcrumb bar skips it, so the activation form reads {@code <job> > Activation} and no crumb
 * opens the run request form (e2e re-audit DEF-06).
 *
 * <p>Only reachable through {@link JobRequestAction}, so it is absent (404) without
 * {@code BatchControl/Request} like the rest of that URL space; {@link JobActivationForm#doSubmit}
 * re-checks the permissions. It holds no state.
 */
@Restricted(NoExternalUse.class)
public final class JobActivationRoute {

    private final Job<?, ?> job;

    JobActivationRoute(Job<?, ?> job) {
        this.job = job;
    }

    public Job<?, ?> getJob() {
        return job;
    }

    /** Stapler: {@code activation/} and {@code activation/submit}. */
    public JobActivationForm getActivation() {
        return new JobActivationForm(job);
    }
}
