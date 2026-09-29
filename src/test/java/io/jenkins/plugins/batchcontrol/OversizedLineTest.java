package io.jenkins.plugins.batchcontrol;

import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.io.BufferedWriter;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 4, "A line longer than 1 MiB is skipped on every read path, including CSV exports,
 * and a screen that skipped one says so, so screens and exports never silently disagree.
 * (security-11)". Regression rows for security-11 N-01 (the CSV path had no line cap) and N-02
 * (a skipped line was only logged): T-04-18, T-04-19, T-04-20 (note 89).
 *
 * <p>Lines are written into the month files directly, templated from what the store itself
 * wrote (as in {@link StoreDataFixtures}). The oversized line is one well-formed record whose
 * job name alone is 1.1 MiB, placed between two ordinary records. The twin month (August) has
 * the same two ordinary records and no oversized line, so the notice is measured against a
 * screen of the same shape.
 *
 * <p>The notice is recognised by wording: it must contain one of "skipped", "oversized",
 * "too large" or "1 MiB" (case-insensitive). The screen contract pinned here is only that the
 * notice uses one of those words.
 *
 * Written from docs/SPEC.md item 4, docs/reports/security-11.md and docs/TEST-MATRIX.md only
 * (no src/main knowledge).
 */
@WithJenkins
public class OversizedLineTest {

    private static final int BIG = 1_100_000 + 1024 * 64;
    private static final String BIG_JOB = "ovrbig" + "x".repeat(BIG);
    private static final YearMonth MONTH = YearMonth.of(2025, 9);
    private static final YearMonth TWIN = YearMonth.of(2025, 8);
    private static final Instant FIXTURE_TIME = Instant.parse("2001-01-15T12:00:00Z");
    private static final Pattern NOTICE = Pattern.compile("(?i)(skipped|oversized|too large|1\\s*MiB)");

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
        assertTrue(BIG_JOB.length() > 1024 * 1024, "fixture: the oversized value exceeds 1 MiB");
    }

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
    }

    /**
     * T-04-18 (security-11 N-01): runs.csv over a month holding a run line longer than 1 MiB
     * completes with 200, exports the two ordinary rows and nothing of the oversized one.
     */
    @Test
    public void t_04_18_runsCsvSkipsALineOver1MiB() throws Exception {
        writeRuns();
        BatchClock.setForTest(Clock.fixed(Instant.parse("2025-09-28T12:00:00Z"), ZoneOffset.UTC));

        Page page = get("batch-control/history/runs.csv?from=2025-09-01&to=2025-09-30");
        assertEquals(200, page.getWebResponse().getStatusCode(), "the export must complete");
        String csv = page.getWebResponse().getContentAsString();
        assertTrue(csv.contains("ovr-a") && csv.contains("ovr-b"), "the ordinary rows must be exported");
        assertFalse(csv.contains("ovrbig"), "the line over 1 MiB must be skipped on the CSV path too");
        List<String> rows = csv.lines().skip(1).filter(line -> !line.isBlank()).collect(Collectors.toList());
        assertEquals(2, rows.size(), "exactly the two ordinary rows: " + rows.stream()
                .map(r -> r.length() > 200 ? r.substring(0, 200) + "..." : r).collect(Collectors.toList()));
    }

    /**
     * T-04-19 (security-11 N-01): changes.csv over a month holding a change line longer than
     * 1 MiB completes, exports the ordinary rows, and the history change list over it says it
     * skipped one while the same list over the twin month does not.
     */
    @Test
    public void t_04_19_changesCsvSkipsALineOver1MiBAndTheChangeListSaysSo() throws Exception {
        writeChanges();
        BatchClock.setForTest(Clock.fixed(Instant.parse("2025-09-28T12:00:00Z"), ZoneOffset.UTC));

        Page page = get("batch-control/history/changes.csv?from=2025-09-01&to=2025-09-30");
        assertEquals(200, page.getWebResponse().getStatusCode(), "the export must complete");
        String csv = page.getWebResponse().getContentAsString();
        assertTrue(csv.contains("ovr-ca") && csv.contains("ovr-cb"), "the ordinary rows must be exported");
        assertFalse(csv.contains("ovrbig"), "the line over 1 MiB must be skipped on the CSV path too");

        String withLine = text(get("batch-control/history/?kind=changes&from=2025-09-01&to=2025-09-30"));
        String twin = text(get("batch-control/history/?kind=changes&from=2025-08-01&to=2025-08-31"));
        assertTrue(withLine.contains("ovr-ca") && withLine.contains("ovr-cb"), "the ordinary records are listed");
        assertTrue(twin.contains("ovr-ca") && twin.contains("ovr-cb"), "guard: the twin month lists the same records");
        assertTrue(NOTICE.matcher(withLine).find(), "the change list that skipped a line must say so: " + excerpt(withLine));
        assertFalse(NOTICE.matcher(twin).find(), "the change list that skipped nothing must not: " + excerpt(twin));
    }

    /**
     * T-04-20 (security-11 N-02): the history runs screen and the dashboard over a month holding
     * a run line longer than 1 MiB list the ordinary runs and say a record was skipped; the same
     * screens over the twin month do not.
     */
    @Test
    public void t_04_20_runScreensThatSkippedALineSaySo() throws Exception {
        writeRuns();
        BatchClock.setForTest(Clock.fixed(Instant.parse("2025-09-28T12:00:00Z"), ZoneOffset.UTC));
        String history = text(get("batch-control/history/?from=2025-09-01&to=2025-09-30"));
        String historyTwin = text(get("batch-control/history/?from=2025-08-01&to=2025-08-31"));
        assertTrue(history.contains("ovr-a") && history.contains("ovr-b"), "the ordinary runs are listed");
        assertTrue(historyTwin.contains("ovr-a") && historyTwin.contains("ovr-b"), "guard: the twin month lists them too");
        assertFalse(history.contains("ovrbig"), "the oversized record is not listed");
        assertTrue(NOTICE.matcher(history).find(), "the history screen that skipped a line must say so: " + excerpt(history));
        assertFalse(NOTICE.matcher(historyTwin).find(), "the history screen that skipped nothing must not: " + excerpt(historyTwin));

        // Dashboard default view: last 7 days of the plugin clock, which covers 09-10 and 09-15.
        BatchClock.setForTest(Clock.fixed(Instant.parse("2025-09-16T12:00:00Z"), ZoneOffset.UTC));
        String dashboard = text(get("batch-control/dashboard/"));
        BatchClock.setForTest(Clock.fixed(Instant.parse("2025-08-16T12:00:00Z"), ZoneOffset.UTC));
        String dashboardTwin = text(get("batch-control/dashboard/"));
        assertTrue(dashboard.contains("ovr-b"), "the dashboard lists the ordinary run in its window");
        assertTrue(dashboardTwin.contains("ovr-b"), "guard: the twin dashboard lists it too");
        assertTrue(NOTICE.matcher(dashboard).find(), "the dashboard that skipped a line must say so: " + excerpt(dashboard));
        assertFalse(NOTICE.matcher(dashboardTwin).find(), "the dashboard that skipped nothing must not: " + excerpt(dashboardTwin));
    }

    // ---------------------------------------------------------------- fixtures

    private void writeRuns() throws Exception {
        StoreDataFixtures.RunLine line = StoreDataFixtures.runLineTemplate();
        for (YearMonth month : new YearMonth[] {TWIN, MONTH}) {
            Path file = StoreDataFixtures.runsFile(month);
            Files.createDirectories(file.getParent());
            String prefix = StoreDataFixtures.monthName(month);
            try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                out.write(line.render("ovr-a#1", "ovr-a", 1, Instant.parse(prefix + "-05T12:00:00Z")));
                out.write('\n');
                if (month.equals(MONTH)) {
                    out.write(line.render(BIG_JOB + "#1", BIG_JOB, 1, Instant.parse(prefix + "-10T12:00:00Z")));
                    out.write('\n');
                }
                out.write(line.render("ovr-b#1", "ovr-b", 1, Instant.parse(prefix + "-15T12:00:00Z")));
                out.write('\n');
            }
        }
        assertEquals(3, StoreDataFixtures.lineCount(StoreDataFixtures.runsFile(MONTH)), "fixture: three lines in September");
        assertTrue(Files.size(StoreDataFixtures.runsFile(MONTH)) > 1024 * 1024, "fixture: the September file holds a line over 1 MiB");
        j.createFreeStyleProject("ovr-a");
        j.createFreeStyleProject("ovr-b");
    }

    private void writeChanges() throws Exception {
        BatchClock.setForTest(Clock.fixed(FIXTURE_TIME, ZoneOffset.UTC));
        j.createFreeStyleProject("ovr-tpl");
        YearMonth fixtureMonth = YearMonth.from(FIXTURE_TIME.atZone(ZoneOffset.UTC));
        List<ChangeRecord> creates = FileStore.get().listChangeRecords(fixtureMonth).stream()
                .filter(r -> r.getType() == ChangeType.CREATE && "ovr-tpl".equals(r.getTarget()))
                .collect(Collectors.toList());
        assertEquals(1, creates.size(), "fixture: one CREATE record for the template job");
        String id = creates.get(0).getId();
        Path tplFile = changesFile(fixtureMonth);
        List<String> tpl = Files.readAllLines(tplFile, StandardCharsets.UTF_8).stream()
                .filter(l -> l.contains(id)).collect(Collectors.toList());
        assertEquals(1, tpl.size(), "fixture: the CREATE line is found by its id");
        String template = tpl.get(0);
        String at = template.contains(FIXTURE_TIME.toString()) ? FIXTURE_TIME.toString()
                : String.valueOf(FIXTURE_TIME.toEpochMilli());
        boolean iso = at.contains("T");
        assertTrue(template.contains("\"ovr-tpl\""), "fixture: the target appears as a JSON string: " + template);

        int serial = 0;
        for (YearMonth month : new YearMonth[] {TWIN, MONTH}) {
            Path file = changesFile(month);
            Files.createDirectories(file.getParent());
            String prefix = StoreDataFixtures.monthName(month);
            String compact = prefix.replace("-", "");
            try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                out.write(changeLine(template, id, at, iso, compact + "05-120000-ovr" + (serial++) + "aa",
                        "ovr-ca", Instant.parse(prefix + "-05T12:00:00Z")));
                out.write('\n');
                if (month.equals(MONTH)) {
                    out.write(changeLine(template, id, at, iso, compact + "10-120000-ovrbig",
                            BIG_JOB, Instant.parse(prefix + "-10T12:00:00Z")));
                    out.write('\n');
                }
                out.write(changeLine(template, id, at, iso, compact + "15-120000-ovr" + (serial++) + "bb",
                        "ovr-cb", Instant.parse(prefix + "-15T12:00:00Z")));
                out.write('\n');
            }
        }
        assertTrue(Files.size(changesFile(MONTH)) > 1024 * 1024, "fixture: the September change file holds a line over 1 MiB");
    }

    private static String changeLine(String template, String templateId, String templateAt, boolean iso,
            String newId, String target, Instant at) {
        return template.replace(templateId, newId)
                .replace("\"ovr-tpl\"", "\"" + target + "\"")
                .replace(templateAt, iso ? at.toString() : String.valueOf(at.toEpochMilli()));
    }

    private static Path changesFile(YearMonth month) {
        return StoreDataFixtures.storeDir().resolve("changes").resolve(StoreDataFixtures.monthName(month) + ".jsonl");
    }

    private Page get(String path) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setJavaScriptEnabled(false);
        wc.getOptions().setCssEnabled(false);
        return wc.login("viewer").getPage(new WebRequest(new URL(j.getURL(), path), HttpMethod.GET));
    }

    private static String text(Page page) {
        assertEquals(200, page.getWebResponse().getStatusCode(), "the screen must render");
        assertTrue(page instanceof HtmlPage, "fixture: an HTML screen");
        return ((HtmlPage) page).asNormalizedText();
    }

    private static String excerpt(String text) {
        return text.length() > 1500 ? text.substring(0, 1500) + "..." : text;
    }
}
