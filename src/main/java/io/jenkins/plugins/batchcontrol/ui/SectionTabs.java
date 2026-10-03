package io.jenkins.plugins.batchcontrol.ui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.batchcontrol.model.PendingCount;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import jenkins.management.Badge;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.springframework.security.core.Authentication;

/**
 * The tabs of the Batch Control page (hosting review 2026-10-02, PR6): one entry per section the
 * current user may open, in a fixed order. The same list feeds the tab bar of every Batch Control
 * screen ({@code bc:tabs}) and the breadcrumb context menu of the root action, so the two can
 * never disagree. Visibility is exactly {@link SectionAccess}'s link predicates, which are the
 * permission sets of the section gates.
 *
 * <p>Badges count the open items the viewer can act on, from each service's
 * {@code countPendingFor} (#76: the one count the sections also use, read from the open-request
 * indexes, never from the full history):
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
        Authentication me = Jenkins.getAuthentication2();
        if (links.isRequests()) {
            tabs.add(new Tab("requests", "requests/", "Run Requests", "symbol-list-outline plugin-ionicons-api",
                    badge(RunRequestService.get().countPendingFor(me), "run request")));
        }
        if (links.isActivations()) {
            tabs.add(new Tab("activations", "activations/", "Activations", "symbol-power-outline plugin-ionicons-api",
                    badge(ActivationService.get().countPendingFor(me), "activation or hold request")));
        }
        if (links.isGrants()) {
            tabs.add(new Tab("grants", "grants/", "Grants", "symbol-key-outline plugin-ionicons-api",
                    badge(GrantRequestService.get().countPendingFor(me), "grant request")));
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
    private static Badge badge(PendingCount count, String noun) {
        return badge(count.getAwaitingDecision(), count.getOwn(), noun);
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
