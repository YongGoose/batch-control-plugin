package io.jenkins.plugins.batchcontrol.queue;

import hudson.model.InvisibleAction;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * D-58c: the hidden marker of a run started by a Replay, Pipeline Rebuild or Restart from Stage
 * that a user whose permission for it came only from a grant submitted. It travels with the queue
 * item onto the run and is saved in the run's {@code build.xml}. A later re-run whose source is a
 * marked run is refused for everyone but an administrator, before and after the review, because
 * the replayed script is not part of the job's configuration a reviewer looks at.
 */
@Restricted(NoExternalUse.class)
public final class ReplayUnderGrantAction extends InvisibleAction {

    private final String user;
    private final String grantId;

    public ReplayUnderGrantAction(String user, String grantId) {
        this.user = user;
        this.grantId = grantId;
    }

    public String getUser() {
        return user;
    }

    public String getGrantId() {
        return grantId;
    }
}
