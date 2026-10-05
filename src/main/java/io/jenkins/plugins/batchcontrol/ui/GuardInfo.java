package io.jenkins.plugins.batchcontrol.ui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Item;
import hudson.model.ItemGroup;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import java.util.HashSet;
import java.util.Set;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Backlog #86: what the strategy monitor says about an item in the "changed under a grant" state
 * (D-58a/b): which permission window recorded the change, and after "Mark as reviewed" what still
 * guards the item. Read-only; for the Administer-only monitor pages, reached through
 * {@code BatchControlRootAction} ({@code j:invokeStatic} cannot load plugin classes).
 */
@Restricted(NoExternalUse.class)
public final class GuardInfo {

    private GuardInfo() {
    }

    /** The permission window that recorded a change to an item (or to a folder above it). */
    public static final class Changed {
        private final String grantId;
        private final String user;
        private final String scopeFullName;

        Changed(String grantId, String user, String scopeFullName) {
            this.grantId = grantId;
            this.user = user;
            this.scopeFullName = scopeFullName;
        }

        public String getGrantId() {
            return grantId;
        }

        /** Holder of the window, the user whose save or creation was recorded. */
        public String getUser() {
            return user;
        }

        /** Full name of the job or folder the window was requested for. */
        public String getScopeFullName() {
            return scopeFullName;
        }
    }

    /** What still guards an item after it was marked as reviewed. */
    public static final class Outcome {
        private final String itemFullName;
        private final String itemUrl;
        private final String changedFolder;
        private final Changed activeWindow;

        Outcome(String itemFullName, String itemUrl, @CheckForNull String changedFolder,
                @CheckForNull Changed activeWindow) {
            this.itemFullName = itemFullName;
            this.itemUrl = itemUrl;
            this.changedFolder = changedFolder;
            this.activeWindow = activeWindow;
        }

        public String getItemFullName() {
            return itemFullName;
        }

        /** The item's URL relative to the Jenkins root. */
        public String getItemUrl() {
            return itemUrl;
        }

        /** A folder above the item that is still listed as changed under a window, or {@code null}. */
        @CheckForNull
        public String getChangedFolder() {
            return changedFolder;
        }

        /** An active window that covers the item, or {@code null}. */
        @CheckForNull
        public Changed getActiveWindow() {
            return activeWindow;
        }
    }

    /**
     * The window that recorded a change to the item or a folder above it, or {@code null} when
     * the item is not in the "changed under a grant" state or the grant cannot be read.
     */
    @CheckForNull
    public static Changed changedUnder(@CheckForNull String itemFullName) {
        String id = GrantService.get().guardingGrantId(itemFullName);
        return id == null ? null : load(id);
    }

    /**
     * After "Mark as reviewed" of {@code itemFullName}: the outcome, or {@code null} when the name
     * does not resolve (as the viewer) or the item is still listed as changed itself, so that a
     * hand-edited query string never produces a confirmation that is not true now.
     */
    @CheckForNull
    public static Outcome reviewed(@CheckForNull String itemFullName) {
        Item item = Visibility.findVisibleItem(itemFullName);
        if (item == null) {
            return null;
        }
        GrantService grants = GrantService.get();
        Set<String> listed = new HashSet<>(grants.itemsChangedUnderGrant(Integer.MAX_VALUE));
        if (listed.contains(item.getFullName())) {
            return null;
        }
        String folder = null;
        ItemGroup<?> parent = item.getParent();
        while (parent instanceof Item) {
            String name = ((Item) parent).getFullName();
            if (listed.contains(name)) {
                folder = name;
                break;
            }
            parent = ((Item) parent).getParent();
        }
        Changed active = null;
        if (folder == null) {
            // No changed folder above: a guarding grant can only be an active window covering it.
            String id = grants.guardingGrantId(item.getFullName());
            active = id == null ? null : load(id);
        }
        return new Outcome(item.getFullName(), item.getUrl(), folder, active);
    }

    @CheckForNull
    private static Changed load(String grantId) {
        if (Jenkins.getInstanceOrNull() == null) {
            return null;
        }
        try {
            Grant grant = GrantService.get().find(grantId);
            if (grant == null) {
                return null;
            }
            String scope = grant.getScope() == null ? "" : grant.getScope().getFullName();
            return new Changed(grant.getId(), grant.getUser(), scope);
        } catch (RuntimeException e) {
            return null; // display only; the monitor still lists the item
        }
    }
}
