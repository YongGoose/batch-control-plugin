package io.jenkins.plugins.batchcontrol.ui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.batchcontrol.model.ActivationRequest;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import io.jenkins.plugins.batchcontrol.store.Store;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Backlog #88: totals per section for the overview page, shown to Batch Control/Manage holders
 * (who may see every request, P-09). Read from the open-request indexes and the in-memory grant
 * cache only, like the tab badges (D-61); no request file and no item is loaded.
 */
@Restricted(NoExternalUse.class)
public final class OverviewCounts {

    private OverviewCounts() {
    }

    /** One section's totals. */
    public static final class Row {
        private final String id;
        private final String urlName;
        private final String label;
        private final int pending;
        private final String otherLabel;
        private final int other;

        Row(String id, String urlName, String label, int pending, @CheckForNull String otherLabel, int other) {
            this.id = id;
            this.urlName = urlName;
            this.label = label;
            this.pending = pending;
            this.otherLabel = otherLabel;
            this.other = other;
        }

        public String getId() {
            return id;
        }

        /** Path below {@code /batch-control/}, ending in a slash. */
        public String getUrlName() {
            return urlName;
        }

        public String getLabel() {
            return label;
        }

        /** Requests awaiting a decision. */
        public int getPending() {
            return pending;
        }

        /** Label of the section's second figure, or {@code null} when it has none. */
        @CheckForNull
        public String getOtherLabel() {
            return otherLabel;
        }

        public int getOther() {
            return other;
        }
    }

    /**
     * The totals of the sections the current user may open, or an empty list unless they hold
     * Batch Control/Manage.
     */
    @NonNull
    public static List<Row> current() {
        if (!Jenkins.get().hasPermission(BatchControlPermissions.MANAGE)) {
            return Collections.emptyList();
        }
        SectionAccess links = new SectionAccess();
        Store store = Store.get();
        List<Row> rows = new ArrayList<>();
        if (links.isRequests()) {
            int pending = 0;
            int approved = 0;
            for (RunRequest r : store.listOpenRunRequests()) {
                if (r.getStatus() == RequestStatus.PENDING) {
                    pending++;
                } else if (r.getStatus() == RequestStatus.APPROVED) {
                    approved++;
                }
            }
            rows.add(new Row("requests", "requests/", "Run Requests", pending, "Approved, not yet run", approved));
        }
        if (links.isActivations()) {
            int pending = 0;
            for (ActivationRequest r : store.listOpenActivationRequests()) {
                if (r.getStatus() == RequestStatus.PENDING) {
                    pending++;
                }
            }
            rows.add(new Row("activations", "activations/", "Activations", pending, null, 0));
        }
        if (links.isGrants()) {
            int pending = 0;
            for (GrantRequest r : store.listOpenGrantRequests()) {
                if (r.getStatus() == RequestStatus.PENDING) {
                    pending++;
                }
            }
            rows.add(new Row("grants", "grants/", "Grants", pending, "Active permission windows",
                    GrantService.get().listActive().size()));
        }
        return Collections.unmodifiableList(rows);
    }
}
