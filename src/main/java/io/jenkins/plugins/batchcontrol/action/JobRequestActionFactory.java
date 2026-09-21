package io.jenkins.plugins.batchcontrol.action;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Action;
import hudson.model.Job;
import java.util.Collection;
import java.util.Collections;
import jenkins.model.TransientActionFactory;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Attaches {@link JobRequestAction} to every job so {@code /job/<name>/batch-control/} is always
 * routable; the action itself hides its sidebar icon unless run control is on, the job requires
 * approval and the user holds {@code BatchControl/Request}.
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
        return Collections.singleton(new JobRequestAction(target));
    }
}
