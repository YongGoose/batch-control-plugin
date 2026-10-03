package io.jenkins.plugins.batchcontrol.action;

import com.cloudbees.hudson.plugins.folder.computed.ComputedFolder;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Action;
import java.util.Collection;
import java.util.List;
import jenkins.model.TransientActionFactory;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Attaches {@link ComputedFolderActivationAction} to every computed folder (D-46c). Attached
 * unconditionally, like {@link JobRequestActionFactory}; the action decides its own visibility.
 */
@Extension
@Restricted(NoExternalUse.class)
@SuppressWarnings("rawtypes")
public class ComputedFolderActivationActionFactory extends TransientActionFactory<ComputedFolder> {

    @Override
    public Class<ComputedFolder> type() {
        return ComputedFolder.class;
    }

    @NonNull
    @Override
    public Collection<? extends Action> createFor(@NonNull ComputedFolder target) {
        ComputedFolder<?> folder = (ComputedFolder<?>) target;
        return List.of(new ComputedFolderActivationAction(folder), new JobActivationForm(folder));
    }
}
