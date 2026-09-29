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
 * Attaches {@link RunBuildEntriesAction} to every build (e2e-03 DEF-25). Attached
 * unconditionally, like {@link JobRequestActionFactory}; the action decides whether it renders
 * anything.
 */
@Extension
@Restricted(NoExternalUse.class)
@SuppressWarnings("rawtypes")
public class RunBuildEntriesActionFactory extends TransientActionFactory<Run> {

    @Override
    public Class<Run> type() {
        return Run.class;
    }

    @NonNull
    @Override
    public Collection<? extends Action> createFor(@NonNull Run target) {
        return List.of(new RunBuildEntriesAction((Run<?, ?>) target));
    }
}
