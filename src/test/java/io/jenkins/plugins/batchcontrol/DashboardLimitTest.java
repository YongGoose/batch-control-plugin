package io.jenkins.plugins.batchcontrol;

import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.DomText;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.UsabilityFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-67 (hosting review round 3, R4-13): "The run dashboard shows at most the 50 most recent runs
 * and links to History for the rest." Matrix rows T-UI-91, T-UI-92 (note 240).
 *
 * <p>Runs are generated as store lines (StoreDataFixtures: the line the store itself writes),
 * one job per run, named {@code dashrun-<i>} with i ascending in time, inside the dashboard's
 * default window at a fixed plugin clock. A run counts as shown when its job name appears in the
 * visible body text (outside the tab bar, form controls and scripts), so a job filter listing
 * every job does not count.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-67 and docs/TEST-MATRIX.md only (no src/main
 * knowledge).
 */
@WithJenkins
public class DashboardLimitTest {

    static final String DASHBOARD = "batch-control/dashboard/";
    static final int LIMIT = 50;
    static final Instant NOW = Instant.parse("2025-09-28T12:00:00Z");
    static final Instant FROM = Instant.parse("2025-09-27T12:00:00Z");
    static final Instant TO = Instant.parse("2025-09-28T11:00:00Z");
    private static final Instant FIXTURE_TIME = Instant.parse("2001-01-15T12:00:00Z");
    private static final Pattern RUN = Pattern.compile("dashrun-(\\d+)(?!\\d)");

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.VIEW_HISTORY).everywhere().to("viewer"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("admin"));
        cfg.save();
        BatchClock.setForTest(Clock.fixed(FIXTURE_TIME, ZoneOffset.UTC));
    }

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
    }

    /**
     * T-UI-91: with 60 runs in the window the dashboard shows exactly the 50 most recent
     * (dashrun-10..59) and none of the 10 oldest, and its body (outside the tab bar) links to
     * History; the 10 runs the dashboard left out are listed on the History page that link opens or
     * on the History pages reachable from it through its own links (History may page).
     */
    @Test
    public void t_ui_91_dashboardShowsTheFiftyMostRecentAndLinksToHistory() throws Exception {
        writeRuns(60);
        HtmlPage page = UsabilityFixtures.htmlPage(j, "viewer", DASHBOARD);
        assertEquals(200, page.getWebResponse().getStatusCode(), "premise: the viewer opens the dashboard");
        Set<Integer> shown = shownRuns(page);
        Set<Integer> expected = new TreeSet<>();
        for (int i = 60 - LIMIT; i < 60; i++) {
            expected.add(i);
        }
        assertEquals(expected, shown, "the dashboard must show exactly the 50 most recent runs (D-67): "
                + excerpt(bodyText(page)));

        String history = historyLink(page);
        assertTrue(history != null, "with more than 50 runs the dashboard body must link to History (D-67); body links: "
                + bodyLinks(page));
        Set<Integer> listed = reachableThroughHistory(history);
        for (int i = 0; i < 60 - LIMIT; i++) {
            assertTrue(listed.contains(i), "the runs the dashboard leaves out must be reachable on History from its link "
                    + history + " (its pages included), missing dashrun-" + i + ": " + listed);
        }
    }

    /**
     * T-UI-92 (guard): with 30 runs in the window every one is shown, so the limit is a bound and
     * not a fixed cut.
     */
    @Test
    public void t_ui_92_dashboardWithFewerRunsShowsThemAll() throws Exception {
        writeRuns(30);
        HtmlPage page = UsabilityFixtures.htmlPage(j, "viewer", DASHBOARD);
        assertEquals(200, page.getWebResponse().getStatusCode(), "premise: the viewer opens the dashboard");
        Set<Integer> expected = new TreeSet<>();
        for (int i = 0; i < 30; i++) {
            expected.add(i);
        }
        assertEquals(expected, shownRuns(page), "with 30 runs the dashboard must show all of them: " + excerpt(bodyText(page)));
    }

    // ---------------------------------------------------------------- helpers

    /** {@code count} jobs dashrun-0..count-1 (created in a month no row reads) with one run each, oldest first. */
    private void writeRuns(int count) throws Exception {
        StoreDataFixtures.RunLine line = StoreDataFixtures.runLineTemplate();
        for (int i = 0; i < count; i++) {
            j.createFreeStyleProject("dashrun-" + i);
        }
        BatchClock.setForTest(Clock.fixed(NOW, ZoneOffset.UTC));
        StoreDataFixtures.writeRunMonth(line, YearMonth.of(2025, 9), count, FROM, TO, "dashrun-", count);
    }

    /** Runs shown on the History page at {@code start} and on History pages linked from it (at most 10 pages). */
    private Set<Integer> reachableThroughHistory(String start) throws Exception {
        Set<Integer> out = new TreeSet<>();
        java.util.Deque<String> todo = new java.util.ArrayDeque<>(List.of(start));
        Set<String> seen = new java.util.HashSet<>();
        while (!todo.isEmpty() && seen.size() < 10) {
            String path = todo.poll();
            if (!seen.add(path)) {
                continue;
            }
            HtmlPage page = UsabilityFixtures.htmlPage(j, "viewer", path);
            assertEquals(200, page.getWebResponse().getStatusCode(), "History must open for the viewer: " + path);
            out.addAll(shownRuns(page));
            todo.addAll(historyLinks(page));
        }
        return out;
    }

    private static Set<Integer> shownRuns(HtmlPage page) {
        Set<Integer> out = new TreeSet<>();
        Matcher m = RUN.matcher(bodyText(page));
        while (m.find()) {
            out.add(Integer.parseInt(m.group(1)));
        }
        return out;
    }

    private static DomElement main(HtmlPage page) {
        DomElement main = page.getElementById("main-panel");
        assertTrue(main != null, "the page must have a main panel");
        return main;
    }

    static String bodyText(HtmlPage page) {
        StringBuilder out = new StringBuilder();
        for (DomNode n : main(page).getDescendants()) {
            if (n instanceof DomText t && !excluded(t)) {
                out.append(t.getWholeText()).append(' ');
            }
        }
        return out.toString().replaceAll("\\s+", " ");
    }

    private static boolean excluded(DomNode node) {
        for (DomNode n = node.getParentNode(); n != null; n = n.getParentNode()) {
            if (n instanceof DomElement e && e.hasAttribute("data-batch-control-tabs")) {
                return true;
            }
            String name = n.getNodeName();
            if ("script".equals(name) || "style".equals(name) || "select".equals(name) || "datalist".equals(name)
                    || "template".equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** Root-relative target of the first body link (outside the tab bar) to batch-control/history, or null. */
    private String historyLink(HtmlPage page) throws Exception {
        List<String> links = historyLinks(page);
        return links.isEmpty() ? null : links.get(0);
    }

    /** Root-relative targets of the body links (outside the tab bar) to batch-control/history. */
    private List<String> historyLinks(HtmlPage page) throws Exception {
        List<String> out = new ArrayList<>();
        String root = j.getURL().getPath();
        for (DomElement a : main(page).getElementsByTagName("a")) {
            if (excluded(a) || !a.hasAttribute("href")) {
                continue;
            }
            java.net.URL url = page.getFullyQualifiedUrl(a.getAttribute("href"));
            String path = url.getPath();
            if (path.endsWith("batch-control/history") || path.endsWith("batch-control/history/")) {
                String rel = path.startsWith(root) ? path.substring(root.length()) : path;
                out.add(url.getQuery() == null ? rel : rel + "?" + url.getQuery());
            }
        }
        return out;
    }

    private static List<String> bodyLinks(HtmlPage page) {
        List<String> out = new ArrayList<>();
        for (DomElement a : main(page).getElementsByTagName("a")) {
            if (!excluded(a)) {
                out.add(a.getAttribute("href"));
            }
        }
        return out;
    }
}
