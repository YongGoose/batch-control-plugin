package io.jenkins.plugins.batchcontrol.listener;

import hudson.Extension;
import hudson.model.Run;
import hudson.model.listeners.RunListener;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.queue.ReplayUnderGrantAction;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import io.jenkins.plugins.batchcontrol.store.Store;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * D-58c: when a run marked {@link ReplayUnderGrantAction} starts, a {@code REPLAY_UNDER_GRANT}
 * change record names the user, the run and the grant, and the run is added to the marked runs
 * the review surfaces list. Never throws.
 */
@Extension
@Restricted(NoExternalUse.class)
public class ReplayUnderGrantListener extends RunListener<Run<?, ?>> {

    private static final Logger LOGGER = Logger.getLogger(ReplayUnderGrantListener.class.getName());

    @Override
    public void onStarted(Run<?, ?> run, hudson.model.TaskListener listener) {
        ReplayUnderGrantAction marker = run.getAction(ReplayUnderGrantAction.class);
        if (marker == null) {
            return;
        }
        String job = run.getParent().getFullName();
        String runId = job + "#" + run.getNumber();
        try {
            ChangeRecord record = ChangeRecord.create(ChangeType.REPLAY_UNDER_GRANT, job, marker.getUser(),
                    "Run #" + run.getNumber() + " was started by a Replay, Pipeline Rebuild or Restart from Stage"
                            + " by '" + marker.getUser() + "' whose permission came only from a permission window;"
                            + " it cannot be re-run except by an administrator.");
            record.setGrantId(marker.getGrantId());
            Store.get().appendChangeRecord(record);
        } catch (RuntimeException e) {
            LOGGER.log(Level.SEVERE, "Could not record the replay under a grant of " + runId, e);
        }
        GrantService.get().noteMarkedRun(job, runId);
    }
}
