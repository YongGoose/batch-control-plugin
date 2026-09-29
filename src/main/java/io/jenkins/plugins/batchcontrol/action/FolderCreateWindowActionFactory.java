package io.jenkins.plugins.batchcontrol.action;

import com.cloudbees.hudson.plugins.folder.AbstractFolder;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Action;
import java.util.Collection;
import java.util.List;
import jenkins.model.TransientActionFactory;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Attaches {@link FolderCreateWindowAction} to every folder (e2e-03 DEF-19). Attached
 * unconditionally, like {@link JobRequestActionFactory}; the action decides what it shows.
 */
@Extension
@Restricted(NoExternalUse.class)
@SuppressWarnings("rawtypes")
public class FolderCreateWindowActionFactory extends TransientActionFactory<AbstractFolder> {

    @Override
    public Class<AbstractFolder> type() {
        return AbstractFolder.class;
    }

    @NonNull
    @Override
    public Collection<? extends Action> createFor(@NonNull AbstractFolder target) {
        return List.of(new FolderCreateWindowAction((AbstractFolder<?>) target));
    }
}
