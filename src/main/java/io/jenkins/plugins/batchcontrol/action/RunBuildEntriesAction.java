package io.jenkins.plugins.batchcontrol.action;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Action;
import hudson.model.Run;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * e2e-03 DEF-25: on a build page of an approval-required job the "Rebuild" and "Retry" entries of
 * other plugins can never succeed (the queue gate refuses them for every user), so the page does
 * not offer them. The action has no sidebar entry and no URL; its {@code summary.jelly}, which
 * core's build page includes for every action, loads the stylesheet that hides those entries
 * while {@link #isHidden()} holds. It never changes state, and with run control off it renders
 * nothing.
 */
@Restricted(NoExternalUse.class)
public class RunBuildEntriesAction implements Action {

    private final Run<?, ?> run;

    public RunBuildEntriesAction(Run<?, ?> run) {
        this.run = run;
    }

    @Override
    @CheckForNull
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return "Build entries";
    }

    @Override
    @CheckForNull
    public String getUrlName() {
        return null;
    }

    /** Whether manual runs of the build's job need an approved request (run control on). */
    public boolean isHidden() {
        return new JobRequestAction(run.getParent()).isActive();
    }
}
