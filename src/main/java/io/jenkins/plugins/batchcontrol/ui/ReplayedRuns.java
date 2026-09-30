package io.jenkins.plugins.batchcontrol.ui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import java.util.ArrayList;
import java.util.List;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * D-58c: the runs of an item that were replayed under a permission window, for the two review
 * surfaces (the batch-control-strategy monitor and the job's Batch Control page). Read-only; the
 * ids come from {@link GrantService#markedRuns} (at most 50) and each is rendered escaped, linked
 * to its build page when the id has the {@code job#number} form.
 */
@Restricted(NoExternalUse.class)
public final class ReplayedRuns {

    private ReplayedRuns() {
    }

    /** The marked runs of {@code item}; empty for {@code null}. */
    public static List<Row> of(@CheckForNull Item item) {
        List<Row> rows = new ArrayList<>();
        if (item == null) {
            return rows;
        }
        for (String id : GrantService.get().markedRuns(item)) {
            rows.add(new Row(id, RunLinks.runUrlFromRunId(id)));
        }
        return rows;
    }

    /** One marked run: its id and its root-relative build URL, or {@code null} for plain text. */
    public static final class Row {
        private final String id;
        private final String url;

        Row(String id, @CheckForNull String url) {
            this.id = id;
            this.url = url;
        }

        public String getId() {
            return id;
        }

        @CheckForNull
        public String getUrl() {
            return url;
        }
    }
}
