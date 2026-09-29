package io.jenkins.plugins.batchcontrol;

import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.CauseType;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.RunRecord;
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
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
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
 * SPEC item 4, "a history, dashboard or change-list page load reads a bounded amount of data
 * regardless of how many records a month holds ... and a query span is capped by records, not by
 * files" (#13), and the SPEC section 6 measurement. Matrix rows T-10-08, T-10-09, T-12-06,
 * T-12-07 and T-12-08 (notes 64, 65, 66).
 *
 * <p>"Bounded regardless of how many records a month holds" is measured as a comparison: the same
 * page over a month of 1,500 records and over a month of 35,000 records (5,000 runs/day for the 7
 * days before "now", the SPEC section 6 volume) must cost about the same heap allocation, and the
 * large one must render within the SPEC section 6 bound of 2 s. A page that materialises the
 * whole month allocates in proportion to it and fails the comparison even on a machine fast
 * enough to beat the time bound. (The volume is capped at 35,000 generated lines to keep the
 * suite's memory use modest, note 64.)
 *
 * <p>Each page is loaded once unmeasured after the data is written (Jelly compilation, and any
 * per-month index an implementation keeps and rebuilds because the files were written behind its
 * back, note 64), then measured.
 *
 * Written from docs/SPEC.md, docs/ARCHITECTURE.md section 5, issue #13 and docs/TEST-MATRIX.md
 * only (no src/main knowledge).
 */
@WithJenkins
public class BoundedReadTest {

    static final int SMALL = 1_500;
    static final int LARGE = 35_000;
    static final int JOBS = 10;
    static final long TIME_BOUND_MS = 2_000;
    /** Allowed extra allocation of the 35,000-record page over the 1,500-record page. */
    static final long ALLOC_BOUND_BYTES = 24L * 1024 * 1024;

    static final YearMonth SMALL_MONTH = YearMonth.of(2025, 8);
    static final YearMonth LARGE_MONTH = YearMonth.of(2025, 9);
    static final Instant SMALL_NOW = Instant.parse("2025-08-28T12:00:00Z");
    static final Instant LARGE_NOW = Instant.parse("2025-09-28T12:00:00Z");
    /** 5,000 runs a day for the 7 days before LARGE_NOW (SPEC section 6). */
    static final Instant LARGE_FROM = Instant.parse("2025-09-21T12:00:00Z");
    private static final Instant FIXTURE_TIME = Instant.parse("2001-01-15T12:00:00Z");

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
        cfg.setRunControlEnabled(true); // recording on (SPEC 9), so CREATE records exist
        cfg.setApprovers(Arrays.asList("admin"));
        cfg.save();

        // Fixture jobs are created in a month no row reads, so the months under test are first
        // touched by the generated files.
        BatchClock.setForTest(Clock.fixed(FIXTURE_TIME, ZoneOffset.UTC));
        for (int k = 0; k < JOBS; k++) {
            j.createFreeStyleProject("perf-job-" + k);
        }
    }

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
    }

    /**
     * T-12-06 (#13): the history screen over a 7-day window of a 35,000-record month renders
     * within 2 s and allocates no more than {@value #ALLOC_BOUND_BYTES} bytes beyond the same
     * screen over a 1,500-record month.
     */
    @Test
    public void t_12_06_historyPageOverA35kMonthIsBounded() throws Exception {
        writeRunMonths();
        Measured small = measure(SMALL_NOW, "batch-control/history/?from=2025-08-22&to=2025-08-28");
        Measured large = measure(LARGE_NOW, "batch-control/history/?from=2025-09-22&to=2025-09-28");
        assertBounded("history", small, large);
        assertStoreHoldsTheGeneratedRuns();
    }

    /**
     * T-10-08 (#13): the dashboard's default 7-day view (SPEC 10) over a 35,000-record month is
     * bounded the same way.
     */
    @Test
    public void t_10_08_dashboardDefaultViewOverA35kMonthIsBounded() throws Exception {
        writeRunMonths();
        Measured small = measure(SMALL_NOW, "batch-control/dashboard/");
        Measured large = measure(LARGE_NOW, "batch-control/dashboard/");
        assertBounded("dashboard", small, large);
        assertStoreHoldsTheGeneratedRuns();
    }

    /**
     * T-10-09 (SPEC 6, #13): the measurement SPEC section 6 asks for, once, with a generated
     * dataset: 5,000 runs a day for 7 days, the dashboard's and the history screen's 7-day view
     * each under 2 s locally. The median of three loads after one warm-up is asserted and printed
     * ({@code [spec6-measurement]}) so the figure can be recorded in docs/HOSTING-READINESS.md.
     */
    @Test
    public void t_10_09_spec6SevenDayViewsAt5000RunsADayUnderTwoSeconds() throws Exception {
        writeRunMonths();
        BatchClock.setForTest(Clock.fixed(LARGE_NOW, ZoneOffset.UTC));
        JenkinsRule.WebClient wc = client();
        for (String path : new String[] {"batch-control/dashboard/",
                "batch-control/history/?from=2025-09-21&to=2025-09-28"}) {
            assertEquals(200, get(wc, path).getStatusCode(), "warm-up GET " + path);
            long[] times = new long[3];
            for (int i = 0; i < times.length; i++) {
                long start = System.nanoTime();
                WebResponse response = get(wc, path);
                times[i] = (System.nanoTime() - start) / 1_000_000;
                assertEquals(200, response.getStatusCode(), "GET " + path);
                assertTrue(response.getContentAsString().contains("perf-job-"), "fixture: " + path + " lists the generated runs");
            }
            java.util.Arrays.sort(times);
            long median = times[1];
            System.out.println("[spec6-measurement] " + path + " over " + LARGE + " runs in 7 days: median "
                    + median + " ms (runs " + java.util.Arrays.toString(times) + ", "
                    + Runtime.version() + ", " + Runtime.getRuntime().availableProcessors() + " cpus)");
            assertTrue(median < TIME_BOUND_MS, path + " 7-day view at 5,000 runs/day must load within " + TIME_BOUND_MS
                    + " ms (SPEC 6), median " + median + " ms");
        }
        assertStoreHoldsTheGeneratedRuns();
    }

    /**
     * T-12-08 (#13): the change-list screen over a 35,000-record change month is bounded the same
     * way.
     */
    @Test
    public void t_12_08_changeListOverA35kMonthIsBounded() throws Exception {
        ChangeTemplate template = changeTemplate();
        writeChangeMonth(template, SMALL_MONTH, SMALL, Instant.parse("2025-08-01T00:00:00Z"), SMALL_NOW);
        writeChangeMonth(template, LARGE_MONTH, LARGE, LARGE_FROM, LARGE_NOW);
        Measured small = measure(SMALL_NOW, "batch-control/history/?kind=changes&from=2025-08-22&to=2025-08-28");
        Measured large = measure(LARGE_NOW, "batch-control/history/?kind=changes&from=2025-09-22&to=2025-09-28");
        assertBounded("change list", small, large);
        assertEquals(LARGE, FileStore.get().listChangeRecords(LARGE_MONTH).stream()
                .filter(rec -> rec.getTarget() != null && rec.getTarget().startsWith("perf-job-")).count(), "fixture: the store must read every generated change line");
    }

    /**
     * T-12-07 (#13): a query span is capped by records, not by files. A hand-typed span of 41
     * months holding two records returns both, including the one 40 months back that a 36-file
     * cap would drop; and an absurd span (from 1990) still answers 200 within the time bound.
     */
    @Test
    public void t_12_07_querySpanIsCappedByRecordsNotByFiles() throws Exception {
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRetentionMonths(60);
        cfg.save();
        assertEquals(60, cfg.getRetentionMonths(), "fixture: retention keeps a record 40 months old");

        j.createFreeStyleProject("span-old-job");
        j.createFreeStyleProject("span-new-job");
        Instant old = Instant.parse("2022-05-15T12:00:00Z");
        BatchClock.setForTest(Clock.fixed(old, ZoneOffset.UTC));
        FileStore.get().appendRunRecord(new RunRecord("span-old-job#1", "span-old-job", 1,
                CauseType.USER, "SUCCESS", old, 10L));
        Instant recent = Instant.parse("2025-09-27T12:00:00Z");
        BatchClock.setForTest(Clock.fixed(recent, ZoneOffset.UTC));
        FileStore.get().appendRunRecord(new RunRecord("span-new-job#1", "span-new-job", 1,
                CauseType.USER, "SUCCESS", recent, 10L));
        BatchClock.setForTest(Clock.fixed(LARGE_NOW, ZoneOffset.UTC));
        assertEquals(1, FileStore.get().listRunRecords(YearMonth.of(2022, 5)).size(), "fixture: the old record is stored in its month");

        JenkinsRule.WebClient wc = client();
        WebResponse wide = get(wc, "batch-control/history/?from=2022-05-01&to=2025-09-28");
        assertEquals(200, wide.getStatusCode(), "a 41-month span must be answered");
        String body = wide.getContentAsString();
        assertTrue(body.contains("span-new-job"), "guard: the recent record is listed");
        assertTrue(body.contains("span-old-job"), "a span of 41 months holding two records must list both: the cap is on records,"
                + " not on month files (#13)");

        long start = System.nanoTime();
        WebResponse absurd = get(wc, "batch-control/history/?from=1990-01-01&to=2025-09-28");
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertEquals(200, absurd.getStatusCode(), "an absurd span must still be answered");
        assertTrue(absurd.getContentAsString().contains("span-old-job"), "an absurd span over two records lists both");
        assertTrue(ms < TIME_BOUND_MS, "an absurd span must render within " + TIME_BOUND_MS + " ms, took " + ms);
    }

    // ---------------------------------------------------------------------------------------

    private void writeRunMonths() throws Exception {
        StoreDataFixtures.RunLine line = StoreDataFixtures.runLineTemplate();
        StoreDataFixtures.writeRunMonth(line, SMALL_MONTH, SMALL,
                Instant.parse("2025-08-01T00:00:00Z"), SMALL_NOW, "perf-job-", JOBS);
        StoreDataFixtures.writeRunMonth(line, LARGE_MONTH, LARGE, LARGE_FROM, LARGE_NOW, "perf-job-", JOBS);
        assertEquals(LARGE, StoreDataFixtures.lineCount(StoreDataFixtures.runsFile(LARGE_MONTH)), "fixture: the large month holds 35,000 lines");
    }

    private static void assertStoreHoldsTheGeneratedRuns() {
        // Premise, checked last because it materialises the month: the store parses every
        // generated line, so the pages above were really rendered over 35,000 records.
        assertEquals(LARGE, FileStore.get().listRunRecords(LARGE_MONTH).size(), "fixture: the store must read every generated run line");
    }

    private record Measured(long ms, long allocated, String body) {
    }

    private Measured measure(Instant now, String path) throws Exception {
        BatchClock.setForTest(Clock.fixed(now, ZoneOffset.UTC));
        JenkinsRule.WebClient wc = client();
        WebResponse warm = get(wc, path);
        assertEquals(200, warm.getStatusCode(), "GET " + path);
        System.gc();
        long allocBefore = StoreDataFixtures.allocatedBytes();
        long start = System.nanoTime();
        WebResponse response = get(wc, path);
        long ms = (System.nanoTime() - start) / 1_000_000;
        long allocated = StoreDataFixtures.allocatedBytes() - allocBefore;
        assertEquals(200, response.getStatusCode(), "GET " + path);
        String body = response.getContentAsString();
        assertTrue(body.contains("perf-job-"), "fixture: " + path + " must list generated records");
        System.out.println("[bounded-read] " + path + " @" + now + ": " + ms + " ms, "
                + (allocated / (1024 * 1024)) + " MiB allocated");
        return new Measured(ms, allocated, body);
    }

    private static void assertBounded(String screen, Measured small, Measured large) {
        assertTrue(large.ms() < TIME_BOUND_MS, "the " + screen + " page over a 35,000-record month must render within "
                + TIME_BOUND_MS + " ms (SPEC 6), took " + large.ms() + " ms");
        long extra = large.allocated() - small.allocated();
        assertTrue(extra < ALLOC_BOUND_BYTES, "the " + screen + " page must read a bounded amount regardless of the month's size (#13):"
                + " 35,000 records allocated " + (large.allocated() >> 20) + " MiB against "
                + (small.allocated() >> 20) + " MiB for 1,500");
    }

    private JenkinsRule.WebClient client() throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setJavaScriptEnabled(false);
        wc.getOptions().setCssEnabled(false);
        return wc.login("viewer");
    }

    private WebResponse get(JenkinsRule.WebClient wc, String path) throws Exception {
        return wc.getPage(new WebRequest(new URL(j.getURL(), path), HttpMethod.GET)).getWebResponse();
    }

    // ---- change-record lines, templated from what the store wrote for a fixture CREATE ----

    private record ChangeTemplate(String line, String id, String target, String at) {
    }

    private ChangeTemplate changeTemplate() throws Exception {
        YearMonth fixtureMonth = YearMonth.from(FIXTURE_TIME.atZone(ZoneOffset.UTC));
        List<ChangeRecord> creates = FileStore.get().listChangeRecords(fixtureMonth).stream()
                .filter(rec -> rec.getType() == ChangeType.CREATE && "perf-job-0".equals(rec.getTarget()))
                .collect(Collectors.toList());
        assertEquals(1, creates.size(), "fixture: one CREATE record for perf-job-0");
        String id = creates.get(0).getId();
        Path file = StoreDataFixtures.storeDir().resolve("changes")
                .resolve(StoreDataFixtures.monthName(fixtureMonth) + ".jsonl");
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .filter(l -> l.contains(id)).collect(Collectors.toList());
        assertEquals(1, lines.size(), "fixture: the CREATE line is found by its id");
        String line = lines.get(0);
        String at = line.contains(FIXTURE_TIME.toString()) ? FIXTURE_TIME.toString()
                : String.valueOf(FIXTURE_TIME.toEpochMilli());
        assertTrue(line.contains(at), "fixture: the record time appears in the line: " + line);
        assertTrue(id.matches("[0-9]{8}-[0-9]{6}-[A-Za-z0-9]{6}"), "fixture: ids have the documented form yyyyMMdd-HHmmss-<6>: " + id);
        return new ChangeTemplate(line, id, "perf-job-0", at);
    }

    private static void writeChangeMonth(ChangeTemplate t, YearMonth month, int count, Instant from,
            Instant to) throws Exception {
        Path file = StoreDataFixtures.storeDir().resolve("changes")
                .resolve(StoreDataFixtures.monthName(month) + ".jsonl");
        Files.createDirectories(file.getParent());
        DateTimeFormatter idTime = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);
        long span = to.toEpochMilli() - from.toEpochMilli();
        boolean iso = t.at().contains("T");
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            for (int i = 0; i < count; i++) {
                Instant at = Instant.ofEpochMilli(from.toEpochMilli() + span * i / count);
                String id = LocalDateTime.ofInstant(at, ZoneOffset.UTC).format(idTime) + "-"
                        + String.format(Locale.ROOT, "%06d", i % 1_000_000);
                String line = t.line().replace(t.id(), id)
                        .replace("\"" + t.target() + "\"", "\"perf-job-" + (i % JOBS) + "\"")
                        .replace(t.at(), iso ? at.toString() : String.valueOf(at.toEpochMilli()));
                out.write(line);
                out.write('\n');
            }
        }
        assertFalse(Files.size(file) == 0, "fixture: change month written");
    }
}
