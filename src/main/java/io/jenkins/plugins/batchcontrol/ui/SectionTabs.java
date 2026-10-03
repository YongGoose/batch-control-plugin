package io.jenkins.plugins.batchcontrol.ui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.batchcontrol.model.ActivationRequest;
import io.jenkins.plugins.batchcontrol.model.Approvers;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.Store;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import jenkins.management.Badge;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * The tabs of the Batch Control page (hosting review 2026-10-02, PR6): one entry per section the
 * current user may open, in a fixed order. The same list feeds the tab bar of every Batch Control
 * screen ({@code bc:tabs}) and the breadcrumb context menu of the root action, so the two can
 * never disagree. Visibility is exactly {@link SectionAccess}'s link predicates, which are the
 * permission sets of the section gates.
 *
 * <p>Badges count the open items the viewer can act on, read from the open-request indexes of
 * the store ({@link Store#listOpenRunRequests()}, {@link Store#listOpenGrantRequests()},
 * {@link Store#listOpenActivationRequests()}), never from the full history:
 * <ul>
 *   <li>a holder of {@code BatchControl/Approve}: PENDING requests on which they are a designated
 *       approver ("awaiting your decision", warning colour);</li>
 *   <li>otherwise: their own PENDING requests ("pending", info colour).</li>
 * </ul>
 * Both sets are visible to the viewer by the P-09 rules ({@link Visibility}: requester or
 * designated approver), so a badge never counts a request the viewer cannot open.
 */
@Restricted(NoExternalUse.class)
public final class SectionTabs {

    /** One tab: a section of {@code /batch-control/}. */
    public static final class Tab {
        private final String id;
        private final String urlName;
        private final String displayName;
        private final String iconFileName;
        private final Badge badge;

        Tab(String id, String urlName, String displayName, String iconFileName, @CheckForNull Badge badge) {
            this.id = id;
            this.urlName = urlName;
            this.displayName = displayName;
            this.iconFileName = iconFileName;
            this.badge = badge;
        }

        /** Key a page names as its active tab ({@code bc:tabs active="..."}). */
        public String getId() {
            return id;
        }

        /** Path below {@code /batch-control/}, ending in a slash; empty for the overview. */
        public String getUrlName() {
            return urlName;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getIconFileName() {
            return iconFileName;
        }

        /** Count of open items the viewer can act on, or {@code null} when there are none. */
        @CheckForNull
        public Badge getBadge() {
            return badge;
        }
    }

    private SectionTabs() {
    }

    /** The tabs the current user may open (Jelly reaches it through {@code BatchControlRootAction#getTabs}). */
    @NonNull
    public static List<Tab> current() {
        SectionAccess links = new SectionAccess();
        List<Tab> tabs = new ArrayList<>();
        tabs.add(new Tab("overview", "", "Overview", "symbol-home-outline plugin-ionicons-api", null));
        String me = Jenkins.getAuthentication2().getName();
        boolean approver = Jenkins.get().hasPermission(BatchControlPermissions.APPROVE);
        if (links.isRequests()) {
            tabs.add(new Tab("requests", "requests/", "Run Requests", "symbol-list-outline plugin-ionicons-api",
                    runRequestBadge(me, approver)));
        }
        if (links.isActivations()) {
            tabs.add(new Tab("activations", "activations/", "Activations", "symbol-power-outline plugin-ionicons-api",
                    activationBadge(me, approver)));
        }
        if (links.isGrants()) {
            tabs.add(new Tab("grants", "grants/", "Grants", "symbol-key-outline plugin-ionicons-api",
                    grantBadge(me, approver)));
        }
        if (links.isHistory()) {
            tabs.add(new Tab("changes", "changes/", "Changes", "symbol-document-text-outline plugin-ionicons-api", null));
            tabs.add(new Tab("dashboard", "dashboard/", "Dashboard", "symbol-speedometer-outline plugin-ionicons-api", null));
            tabs.add(new Tab("incidents", "incidents/", "Incidents", "symbol-warning-outline plugin-ionicons-api", null));
            tabs.add(new Tab("history", "history/", "History", "symbol-search-outline plugin-ionicons-api", null));
        }
        return Collections.unmodifiableList(tabs);
    }

    @CheckForNull
    private static Badge runRequestBadge(String me, boolean approver) {
        int decide = 0;
        int mine = 0;
        for (RunRequest r : Store.get().listOpenRunRequests()) {
            if (r.getStatus() != RequestStatus.PENDING) {
                continue; // the index also holds APPROVED requests waiting to run
            }
            if (approver && r.isDesignatedApprover(me)) {
                decide++;
            } else if (Approvers.sameUser(me, r.getRequester())) {
                mine++;
            }
        }
        return badge(decide, mine, "run request");
    }

    @CheckForNull
    private static Badge grantBadge(String me, boolean approver) {
        int decide = 0;
        int mine = 0;
        for (GrantRequest r : Store.get().listOpenGrantRequests()) {
            if (r.getStatus() != RequestStatus.PENDING) {
                continue;
            }
            if (approver && r.isDesignatedApprover(me)) {
                decide++;
            } else if (Approvers.sameUser(me, r.getRequester())) {
                mine++;
            }
        }
        return badge(decide, mine, "grant request");
    }

    @CheckForNull
    private static Badge activationBadge(String me, boolean approver) {
        int decide = 0;
        int mine = 0;
        for (ActivationRequest r : Store.get().listOpenActivationRequests()) {
            if (r.getStatus() != RequestStatus.PENDING) {
                continue;
            }
            if (approver && r.isDesignatedApprover(me)) {
                decide++;
            } else if (Approvers.sameUser(me, r.getRequester())) {
                mine++;
            }
        }
        return badge(decide, mine, "activation or hold request");
    }

    /**
     * Decisions first: an approver who also has requests of their own sees the decisions; a user
     * with nothing to decide sees their own pending requests.
     */
    @CheckForNull
    static Badge badge(int decide, int mine, String noun) {
        if (decide > 0) {
            return new Badge(Integer.toString(decide),
                    decide + " " + noun + (decide == 1 ? " is" : "s are") + " awaiting your decision",
                    Badge.Severity.WARNING);
        }
        if (mine > 0) {
            return new Badge(Integer.toString(mine),
                    mine + " of your " + noun + (mine == 1 ? "s is" : "s are") + " pending",
                    Badge.Severity.INFO);
        }
        return null;
    }
}
