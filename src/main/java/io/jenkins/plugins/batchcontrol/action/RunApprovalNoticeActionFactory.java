package io.jenkins.plugins.batchcontrol.action;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Action;
import hudson.model.Job;
import hudson.model.Run;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import java.util.Collection;
import java.util.List;
import jenkins.model.TransientActionFactory;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Attaches {@link RunApprovalNoticeAction} to the builds of a job whose manual runs need an
 * approved request (e2e re-audit DEF-31), and to no build while run control is off or the job
 * needs no approval (S-18-06). The action still checks the viewer's permissions at render time.
 */
@Extension
@Restricted(NoExternalUse.class)
public class RunApprovalNoticeActionFactory extends TransientActionFactory<Run> {

    @Override
    public Class<Run> type() {
        return Run.class;
    }

    @NonNull
    @Override
    public Collection<? extends Action> createFor(@NonNull Run target) {
        // S-18-06: nothing is attached while run control is off or the job needs no approval, so
        // a build's actions (and its api/json) are unchanged then.
        Job<?, ?> job = target.getParent();
        if (!RunRequestService.requiresApprovalToRun(job)) {
            return List.of();
        }
        return List.of(new RunApprovalNoticeAction(target));
    }
}
