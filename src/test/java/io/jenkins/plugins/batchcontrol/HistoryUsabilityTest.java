package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.assertApprovedRunQueuedExactlyOnce;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.requestAndApprove;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.secureWithRunControl;
import static io.jenkins.plugins.batchcontrol.UsabilityFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC 10 (the run record carries a user) and 12 (history filters by user and date), with the
 * SPEC 6 usability line (invalid input is refused with a message next to the field): e2e-04
 * FD-09 and FD-11. Matrix rows T-12-13 and T-12-14 (note 170). The administrator reads the
 * history (u1 and a1 hold no ViewHistory).
 *
 * <p>Written from docs/SPEC.md, docs/reports/e2e-04.md and docs/TEST-MATRIX.md only (no src/main
 * knowledge).
 */
@WithJenkins
public class HistoryUsabilityTest {

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        secureWithRunControl(j);
    }

    /**
     * T-12-13 (FD-09): a run started by u1's approved request lists u1 in the History runs row
     * and in runs.csv, and the {@code user=u1} filter finds it on the screen and in the CSV.
     */
    @Test
    public void t_12_13_approvedRunListsTheRequesterAsItsUser() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("hist-approved");
        setBatchControl(job, new BatchControlJobProperty(true));
        requestAndApprove(job);
        assertApprovedRunQueuedExactlyOnce(j, job);

        String row = rowNaming(UsabilityFixtures.htmlPage(j, "admin", "batch-control/history/?kind=runs"), "hist-approved");
        assertNotNull(row, "fixture: the History runs list must show the approved run");
        assertTrue(row.matches("(?s).*\\bu1\\b.*"), "the run started by u1's approved request must list u1 as its user: " + row);

        String csv = ApproverFormFixtures.get(j, "admin", "batch-control/history/runs.csv").getContentAsString();
        String line = lineNaming(csv, "hist-approved");
        assertNotNull(line, "fixture: runs.csv must export the approved run: " + excerpt(csv));
        assertTrue(line.matches("(?s).*\\bu1\\b.*"), "runs.csv must list u1 as the run's user: " + line);

        String filtered = ApproverFormFixtures.get(j, "admin", "batch-control/history/runs.csv?user=u1").getContentAsString();
        assertNotNull(lineNaming(filtered, "hist-approved"), "the user=u1 filter must find the run: " + excerpt(filtered));
        assertNotNull(rowNaming(UsabilityFixtures.htmlPage(j, "admin", "batch-control/history/?kind=runs&user=u1"),
                "hist-approved"), "the user=u1 filter on the screen must find the run");
    }

    /**
     * T-12-14 (FD-11): an invalid date and a reversed range in the History filter are answered
     * with a message next to the date fields (in the filter form), not silently replaced by the
     * current month.
     */
    @Test
    public void t_12_14_invalidOrReversedDatesShowAMessageAtTheFilter() throws Exception {
        String[][] cases = {
            {"batch-control/history/?kind=runs&from=2026-13-45&to=2026-09-30", "(?i)invalid|not a (valid )?date"},
            {"batch-control/history/?kind=runs&from=2026-09-30&to=2026-09-01", "(?i)before|after|earlier|later|reversed"},
        };
        for (String[] c : cases) {
            HtmlPage page = UsabilityFixtures.htmlPage(j, "admin", c[0]);
            String near = filterText(page);
            assertTrue(Pattern.compile(c[1]).matcher(near).find(), c[0] + ": the date filter must show a message next to"
                    + " the fields: " + excerpt(near));
        }
    }

    /**
     * T-10-11 (security-25 S-25-01): on a job without approvalRequired (run control on, job
     * activated), u1's approved run #1 completes; u2 then rebuilds #1 (Rebuild passes the gate: a
     * person acting, D-47). Run #2 is u2's: its History row and runs.csv line name u2 and not u1,
     * it is not classed APPROVED_REQUEST and carries no link to u1's request id. Run #1 still names
     * u1. Note 173.
     */
    @Test
    public void t_10_11_rebuildOfAnApprovedRunIsTheRebuildersRun() throws Exception {
        j.jenkins.setAuthorizationStrategy(new org.jvnet.hudson.test.MockAuthorizationStrategy()
                .grant(jenkins.model.Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(jenkins.model.Jenkins.READ, hudson.model.Item.READ, hudson.model.Item.BUILD,
                        io.jenkins.plugins.batchcontrol.security.BatchControlPermissions.REQUEST).everywhere().to("u1", "u2")
                .grant(jenkins.model.Jenkins.READ, hudson.model.Item.READ,
                        io.jenkins.plugins.batchcontrol.security.BatchControlPermissions.APPROVE).everywhere().to("a1"));
        FreeStyleProject job = BatchControlFixtures.uncontrolled(j.createFreeStyleProject("hist-rebuild"));
        BatchControlFixtures.activate(job);
        String requestId = requestAndApprove(job).getId();
        j.waitUntilNoActivity();
        assertNotNull(job.getBuildByNumber(1), "fixture: the approved request must have run as #1");

        PluginInteractionFixtures.post(j, "u2", job.getBuildByNumber(1).getUrl() + "rebuild/");
        j.waitUntilNoActivity();
        assertNotNull(job.getBuildByNumber(2), "fixture: u2's Rebuild must run as #2 on a job without approvalRequired");

        HtmlPage runs = UsabilityFixtures.htmlPage(j, "admin", "batch-control/history/?kind=runs&job=hist-rebuild");
        String first = rowStarting(runs, "#1");
        String second = rowStarting(runs, "#2");
        assertNotNull(first, "fixture: the History must list #1");
        assertNotNull(second, "fixture: the History must list #2");
        assertTrue(first.matches("(?s).*\\bu1\\b.*"), "u1's approved run must still name u1: " + first);
        assertTrue(second.matches("(?s).*\\bu2\\b.*") && !second.matches("(?s).*\\bu1\\b.*"),
                "u2's Rebuild must be recorded as u2's run, not u1's: " + second);
        assertTrue(!second.contains("APPROVED_REQUEST"), "the Rebuild must not be classed as an approved-request run: " + second);
        assertTrue(!second.contains(requestId), "the Rebuild must carry no link to u1's request " + requestId + ": " + second);

        String csv = ApproverFormFixtures.get(j, "admin", "batch-control/history/runs.csv").getContentAsString();
        List<String> withRequest = new ArrayList<>();
        boolean u2Line = false;
        for (String line : csv.split("\\R")) {
            if (!line.contains("hist-rebuild")) {
                continue;
            }
            if (line.contains(requestId)) {
                withRequest.add(line);
            }
            u2Line |= line.matches("(?s).*\\bu2\\b.*") && !line.matches("(?s).*\\bu1\\b.*");
        }
        assertEquals(1, withRequest.size(), "only u1's own run may carry the request id in runs.csv: " + withRequest);
        assertTrue(u2Line, "runs.csv must list the Rebuild as u2's run: " + excerpt(csv));
    }

    // ---------------------------------------------------------------- helpers

    private static String rowStarting(HtmlPage page, String prefix) {
        for (DomElement tr : page.getElementsByTagName("tr")) {
            String text = tr.asNormalizedText().trim();
            if (text.startsWith(prefix + "\t") || text.startsWith(prefix + " ")) {
                return text;
            }
        }
        return null;
    }

    /** The text of the element holding the {@code from} date field: its form, else its parent three levels up. */
    private static String filterText(HtmlPage page) {
        DomElement from = null;
        for (DomElement input : page.getElementsByTagName("input")) {
            if ("from".equals(input.getAttribute("name"))) {
                from = input;
                break;
            }
        }
        assertNotNull(from, "fixture: the History screen must offer the from date field");
        DomNode scope = from;
        while (scope != null && !(scope instanceof HtmlForm)) {
            scope = scope.getParentNode();
        }
        if (scope == null) {
            scope = from;
            for (int i = 0; i < 3 && scope.getParentNode() != null; i++) {
                scope = scope.getParentNode();
            }
        }
        return scope.asNormalizedText();
    }

    private static String rowNaming(HtmlPage page, String needle) {
        List<String> rows = new ArrayList<>();
        for (DomElement tr : page.getElementsByTagName("tr")) {
            String text = tr.asNormalizedText();
            if (text.contains(needle)) {
                rows.add(text);
            }
        }
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String lineNaming(String csv, String needle) {
        for (String line : csv.split("\\R")) {
            if (line.contains(needle)) {
                return line;
            }
        }
        return null;
    }
}
