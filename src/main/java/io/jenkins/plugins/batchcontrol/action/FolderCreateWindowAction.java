package io.jenkins.plugins.batchcontrol.action;

import com.cloudbees.hudson.plugins.folder.AbstractFolder;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Action;
import hudson.model.Item;
import hudson.security.ACL;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import io.jenkins.plugins.batchcontrol.ui.Dates;
import java.time.Instant;
import java.util.List;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.springframework.security.core.Authentication;

/**
 * e2e-03 DEF-19 (SPEC item 8, D-40): the folder page tells the holder of a Create window which new
 * job names the window allows. A name-restricted window refuses New Item and Rename for any other
 * name, and core's own pages only say "missing the Job/Create permission"; this notice, on the
 * folder where the user creates the job, states the exact name or pattern and when the window
 * ends.
 *
 * <p>No sidebar entry and no URL; its {@code summary.jelly} is included by the folder page for
 * every action. Only the viewer's own active windows are listed, only while change control is on
 * and only to a viewer with {@code Item/Read} on the folder. It never changes state.
 */
@Restricted(NoExternalUse.class)
public class FolderCreateWindowAction implements Action {

    private final AbstractFolder<?> folder;

    public FolderCreateWindowAction(AbstractFolder<?> folder) {
        this.folder = folder;
    }

    @Override
    @CheckForNull
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return "Create window";
    }

    @Override
    @CheckForNull
    public String getUrlName() {
        return null;
    }

    /**
     * The viewer's active Create windows that cover new items in this folder: those naming its
     * current full name (a window follows its folder through a rename or move, D-74 (3)).
     */
    public List<Grant> getWindows() {
        if (!BatchControlGlobalConfiguration.get().isChangeControlEnabled()
                || !folder.hasPermission(Item.READ)) {
            return List.of();
        }
        Authentication me = Jenkins.getAuthentication2();
        if (ACL.isAnonymous2(me)) {
            return List.of();
        }
        // CREATE is checked on the folder's own ACL, so the folder itself is the lookup key: the
        // windows naming its current full name. D-74 (3): when the folder (or a folder above it) is
        // renamed or moved, its windows follow it to the new name and are still listed here;
        // deleting the folder ends them.
        return GrantService.get().findActiveGrants(me.getName(), folder, GrantAction.CREATE);
    }

    /** The allowed names of a window in words: the exact name or pattern, or {@code null} for any. */
    @CheckForNull
    public String restriction(Grant grant) {
        return grant.getCreateNamePattern();
    }

    /** Whether the restriction is a {@code /regex/} (worded "matching") rather than an exact name. */
    public boolean isPattern(Grant grant) {
        String pattern = grant.getCreateNamePattern();
        return pattern != null && pattern.length() > 1 && pattern.startsWith("/") && pattern.endsWith("/");
    }

    public String format(Instant instant) {
        return Dates.format(instant);
    }

    public String remaining(Instant expiresAt) {
        return Dates.until(expiresAt);
    }
}
