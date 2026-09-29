package io.jenkins.plugins.batchcontrol.action;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Action;
import hudson.model.Run;
import java.util.Collection;
import java.util.List;
import jenkins.model.TransientActionFactory;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Attaches {@link RunApprovalNoticeAction} to every build (e2e re-audit DEF-31). Attached
 * unconditionally and cheaply (it only keeps the run); the action decides at render time whether
 * anything is shown, like {@link JobRequestActionFactory}'s actions.
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
        return List.of(new RunApprovalNoticeAction(target));
    }
}
