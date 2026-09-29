package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParameterDefinition;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.RunRecord;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.net.URL;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.uncontrolled;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression rows for security-10 S-02 and S-03 against SPEC item 4 ("Only records inside the
 * requested period count toward the cap; when the cap is reached the screen says so and points
 * to the CSV export, which is complete. A record the fast reader cannot scan is read with the
 * reference parser before it is ever skipped, so no valid record disappears from a screen").
 * Matrix rows T-10-10, T-12-09 and T-12-10 (notes 82, 83).
 *
 * Written from docs/SPEC.md items 4, 10 and 12, docs/reports/security-10.md and
 * docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class StoreReadRegressionTest {

    private static final int PARAMETERS = 70;
    private static final YearMonth MONTH = YearMonth.of(2025, 9);
    private static final Instant NOW = Instant.parse("2025-09-28T12:00:00Z");
    private static final Instant A_AT = Instant.parse("2025-09-01T12:00:00Z");
    private static final int OTHERS = 59_999;

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.VIEW_HISTORY)
                        .everywhere().to("viewer"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("admin"));
        cfg.save();
    }

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
    }

    /**
     * T-10-10 (security-10 S-02): a job with 70 string parameters and one completed build is
     * listed on the dashboard and on the history runs screen with every one of its 70 parameter
     * values (a fast reader that gave up past 64 members made the run vanish from both).
     */
    @Test
    public void t_10_10_runWithSeventyParametersIsListedWithAllOfThem() throws Exception {
        FreeStyleProject job = uncontrolled(j.createFreeStyleProject("many-params"));
        List<ParameterDefinition> definitions = new ArrayList<>();
        for (int i = 1; i <= PARAMETERS; i++) {
            definitions.add(new StringParameterDefinition(name(i), value(i)));
        }
        job.addProperty(new ParametersDefinitionProperty(definitions));
        BatchControlFixtures.activateAsAdmin(job); // D-46: a cause-less submission needs an activation (note 109)
        j.buildAndAssertSuccess(job);
        j.waitUntilNoActivity();

        YearMonth month = YearMonth.now(BatchClock.clock());
        RunRecord record = FileStore.get().listRunRecords(month).stream()
                .filter(rec -> "many-params".equals(rec.getJobFullName())).findFirst().orElse(null);
        assertTrue(record != null, "fixture: the build's run record is stored");

        for (String path : new String[] {"batch-control/dashboard/", "batch-control/history/"}) {
            String body = page(path).getWebResponse().getContentAsString();
            assertTrue(body.contains("many-params"), path + " must list the run with 70 parameters (S-02)");
            for (int i = 1; i <= PARAMETERS; i++) {
                assertTrue(body.contains(value(i)), path + " must show parameter " + name(i) + "=" + value(i) + " (S-02)");
            }
        }
    }

    /**
     * T-12-09 (security-10 S-03): in one month of 60,000 run records, job A's single record is
     * older than the newest 50,000 lines. The history screen for a period that holds only A's
     * record lists it, with the job filter and without: only records inside the period count
     * toward the cap.
     */
    @Test
    public void t_12_09_capCountsOnlyRecordsInsideThePeriod() throws Exception {
        writeMonth();
        for (String path : new String[] {
                "batch-control/history/?job=cap-job-a0&from=2025-09-01&to=2025-09-02",
                "batch-control/history/?from=2025-09-01&to=2025-09-02"}) {
            String body = page(path).getWebResponse().getContentAsString();
            assertTrue(body.contains("cap-job-a0"), path + " must list job A's record: the period holds one record, so no cap"
                    + " applies (S-03)");
        }
    }

    /**
     * T-12-10 (security-10 S-03): when the cap is really reached (59,999 records in the period),
     * the screen says so and points to the CSV export - its text mentions CSV more often than the
     * same screen for an uncapped period does - and the CSV export of that period is complete.
     */
    @Test
    public void t_12_10_reachedCapPointsToTheCompleteCsvExport() throws Exception {
        writeMonth();
        String capped = text(page("batch-control/history/?from=2025-09-10&to=2025-09-28"));
        String uncapped = text(page("batch-control/history/?from=2025-09-01&to=2025-09-02"));
        assertTrue(uncapped.contains("cap-job-a0"), "guard: the uncapped screen renders records");
        assertTrue(count(capped, "csv") > count(uncapped, "csv"), "a screen whose cap is reached must say so and point to the CSV export (SPEC 4, S-03);"
                + " capped text mentions CSV " + count(capped, "csv") + " times, uncapped "
                + count(uncapped, "csv"));

        String csv = page("batch-control/history/runs.csv?from=2025-09-10&to=2025-09-28")
                .getWebResponse().getContentAsString();
        long rows = csv.lines().skip(1).filter(line -> !line.isBlank()).count();
        assertEquals(OTHERS, rows, "the CSV export of the capped period must be complete");
    }

    // ---------------------------------------------------------------------------------------

    private void writeMonth() throws Exception {
        StoreDataFixtures.RunLine line = StoreDataFixtures.runLineTemplate();
        StoreDataFixtures.writeRunMonth(line, MONTH, 1, A_AT, A_AT.plusMillis(1), "cap-job-a", 1);
        StoreDataFixtures.writeRunMonth(line, MONTH, OTHERS, Instant.parse("2025-09-10T00:00:00Z"),
                Instant.parse("2025-09-28T00:00:00Z"), "perf-job-", 10);
        assertEquals(OTHERS + 1, StoreDataFixtures.lineCount(StoreDataFixtures.runsFile(MONTH)), "fixture: 60,000 lines");
        j.createFreeStyleProject("cap-job-a0");
        for (int k = 0; k < 10; k++) {
            j.createFreeStyleProject("perf-job-" + k);
        }
        BatchClock.setForTest(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Page page(String path) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setJavaScriptEnabled(false);
        wc.getOptions().setCssEnabled(false);
        Page page = wc.login("viewer").getPage(new WebRequest(new URL(j.getURL(), path), HttpMethod.GET));
        assertEquals(200, page.getWebResponse().getStatusCode(), "GET " + path);
        return page;
    }

    private static String text(Page page) {
        assertTrue(page instanceof HtmlPage, "fixture: an HTML screen");
        return ((HtmlPage) page).asNormalizedText();
    }

    private static int count(String haystack, String needle) {
        String h = haystack.toLowerCase(Locale.ROOT);
        int n = 0;
        for (int i = h.indexOf(needle); i >= 0; i = h.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    private static String name(int i) {
        return String.format(Locale.ROOT, "P%02d", i);
    }

    private static String value(int i) {
        return String.format(Locale.ROOT, "pv%02d-q7", i);
    }
}
