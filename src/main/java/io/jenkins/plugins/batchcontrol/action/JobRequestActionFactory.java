package io.jenkins.plugins.batchcontrol.action;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Action;
import hudson.model.Job;
import java.util.Collection;
import java.util.List;
import jenkins.model.TransientActionFactory;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Attaches the plugin's two per-job sidebar entries to every job.
 *
 * <ul>
 *   <li>{@link JobRequestAction} — so {@code /job/<name>/batch-control/} is always routable; the
 *       action itself hides its sidebar icon unless run control is on, the job requires approval
 *       and the user holds {@code BatchControl/Request}.</li>
 *   <li>{@link JobGrantRequestAction} — a link to the grant screen for this job (U-01); it holds
 *       no URL space and hides itself unless change control is on, the user holds
 *       {@code BatchControl/RequestGrant} and the user does not already hold
 *       {@code Item/Configure} here.</li>
 * </ul>
 *
 * <p>Both are attached unconditionally and decide their own visibility, because a
 * {@link TransientActionFactory} runs for reasons other than rendering a sidebar (URL routing,
 * API listings) and must not make visibility depend on which of those is happening.
 */
@Extension
@Restricted(NoExternalUse.class)
public class JobRequestActionFactory extends TransientActionFactory<Job> {

    @Override
    public Class<Job> type() {
        return Job.class;
    }

    @NonNull
    @Override
    public Collection<? extends Action> createFor(@NonNull Job target) {
        return List.of(new JobRequestAction(target), new JobGrantRequestAction(target));
    }
}
