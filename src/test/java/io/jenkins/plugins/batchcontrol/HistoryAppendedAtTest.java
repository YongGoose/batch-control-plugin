package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.CauseType;
import io.jenkins.plugins.batchcontrol.model.RunRecord;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import org.htmlunit.WebResponse;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bug hunt A R1-02 (D-81, TEST-MATRIX note 304): run records carry the time they were appended. Each new
 * line of {@code runs/YYYY-MM.jsonl} has {@code appendedAt} (epoch millis of the append, on the plugin
 * clock); a date-filtered listing stops on {@code appendedAt} when present, so a run whose record was
 * appended late (its finalization lagged) does not hide runs appended before it: the listing for the day
 * shows them, matching {@code runs.csv}. Matrix rows T-12-16 and T-10-14.
 *
 * <p>Basis: DECISIONS D-81; SPEC 12 (period filter and CSV export for run records), SPEC 4 (a page load
 * reads a bounded amount; screens and exports never silently disagree; dates in the plugin clock's zone),
 * SPEC 10 (every build is recorded); ARCHITECTURE 2 (run records are written in
 * {@code RunListener#onFinalized}) and 5 ({@code runs/YYYY-MM.jsonl}, newest-first paging). Time moves only
 * through {@link BatchClock}; a run's start time and duration are Jenkins' own.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-81 and docs/ARCHITECTURE.md sections 2 and 5 only (no
 * src/main knowledge).
 */
@WithJenkins
public class HistoryAppendedAtTest {

    private static final Pattern APPENDED_AT = Pattern.compile("\"appendedAt\"\\s*:\\s*(\\d+)");

    /** A day in the middle of a month, so the day before it is in the same month bucket. */
    private static final LocalDate DAY = LocalDate.of(2026, 3, 10);
    private static final Instant MIDNIGHT = DAY.atStartOfDay(ZoneOffset.UTC).toInstant();

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.VIEW_HISTORY).everywhere().to("viewer"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true); // the plugin records nothing while both switches are off (SPEC 1)
        cfg.setApprovers(List.of("admin"));
        cfg.save();
    }

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
        SlowFinalizer.reset();
    }

    /**
     * T-12-16 (P0, D-81): three run records are appended through the store with the plugin clock (UTC) set
     * to each append instant: {@code r102-earlier} (started D-1 23:49:00, appended D-1 23:50:00),
     * {@code r102-fast} (started D 00:00:05, 1 s, appended D 00:00:06) and {@code r102-slow} (started D-1
     * 23:58:00, 5 s, appended late at D 00:03:05, as when its finalization lagged). The History runs listing
     * for {@code from=D&to=D} lists {@code r102-fast} and neither of the others, and its job set equals the one
     * of {@code runs.csv} for the same day. Guards: the listing for D-1 lists {@code r102-slow} and
     * {@code r102-earlier} and not {@code r102-fast}; the stored lines carry {@code appendedAt} equal to the
     * plugin clock at each append.
     */
    @Test
    public void t_12_16_lateAppendedRunDoesNotHideEarlierAppendedRunsOfTheDay() throws Exception {
        for (String name : new String[] {"r102-earlier", "r102-fast", "r102-slow"}) {
            j.createFreeStyleProject(name);
        }
        Instant earlierAppend = MIDNIGHT.minus(Duration.ofMinutes(10));
        Instant fastAppend = MIDNIGHT.plusSeconds(6);
        Instant slowAppend = MIDNIGHT.plus(Duration.ofMinutes(3)).plusSeconds(5);
        append(earlierAppend, "r102-earlier", MIDNIGHT.minus(Duration.ofMinutes(11)), 30_000L);
        append(fastAppend, "r102-fast", MIDNIGHT.plusSeconds(5), 1_000L);
        append(slowAppend, "r102-slow", MIDNIGHT.minus(Duration.ofMinutes(2)), 5_000L);
        BatchClock.setForTest(Clock.fixed(slowAppend.plus(Duration.ofHours(1)), ZoneOffset.UTC));

        String previous = DAY.minusDays(1).toString();
        TreeSet<String> listedBefore = listedJobs("batch-control/history/?kind=runs&from=" + previous + "&to=" + previous);
        assertEquals(new TreeSet<>(List.of("r102-earlier", "r102-slow")), listedBefore,
                "guard: the listing for " + previous + " lists the two runs started that day (the period is by start time)");

        String day = DAY.toString();
        TreeSet<String> listed = listedJobs("batch-control/history/?kind=runs&from=" + day + "&to=" + day);
        TreeSet<String> exported = csvJobs("batch-control/history/runs.csv?from=" + day + "&to=" + day);
        assertTrue(exported.contains("r102-fast") && !exported.contains("r102-slow") && !exported.contains("r102-earlier"),
                "guard (SPEC 12): runs.csv for " + day + " lists exactly the run started that day: " + exported);
        assertTrue(listed.contains("r102-fast"), "R1-02, D-81: the History runs listing for " + day + " must list r102-fast, appended"
                + " before the late-appended r102-slow; listed: " + listed + ", runs.csv: " + exported);
        assertEquals(exported, listed, "R1-02, SPEC 4: the listing for " + day + " must match runs.csv for the same day");

        assertEquals(fastAppend.toEpochMilli(), appendedAt("r102-fast"), "D-81: the line of r102-fast carries appendedAt = its append instant");
        assertEquals(slowAppend.toEpochMilli(), appendedAt("r102-slow"), "D-81: the line of r102-slow carries appendedAt = its append instant");
    }

    /**
     * T-10-14 (P0, D-81): two real Freestyle runs, the plugin clock fixed at T. A run listener of the test
     * holds {@code r102-real-slow} in {@code onCompleted} (after its duration is fixed, before it is
     * finalized); meanwhile {@code r102-real-fast} runs and is recorded. The plugin clock moves to T + 5 min
     * and the slow run is released. Both lines carry {@code appendedAt}: the fast one T, the slow one
     * T + 5 min, which is more than 60 s after the slow run's start + duration (premise: appended late); the
     * fast line precedes the slow one in the month file.
     */
    @Test
    public void t_10_14_runLineCarriesTheInstantItWasAppended() throws Exception {
        j.jenkins.setNumExecutors(4);
        FreeStyleProject slow = BatchControlFixtures.uncontrolled(j.createFreeStyleProject("r102-real-slow"));
        FreeStyleProject fast = BatchControlFixtures.uncontrolled(j.createFreeStyleProject("r102-real-fast"));
        BatchControlFixtures.activateAsAdmin(slow); // D-46: a cause-less submission needs an activation
        BatchControlFixtures.activateAsAdmin(fast);
        Instant t = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        BatchClock.setForTest(Clock.fixed(t, ZoneOffset.UTC));

        SlowFinalizer.arm("r102-real-slow");
        var slowFuture = slow.scheduleBuild2(0);
        assertNotNull(slowFuture, "fixture: the slow run is scheduled");
        assertTrue(SlowFinalizer.entered.await(60, TimeUnit.SECONDS), "fixture: the slow run reaches onCompleted");
        FreeStyleBuild slowBuild = slowFuture.waitForStart();

        j.buildAndAssertSuccess(fast);
        String fastLine = awaitLine("r102-real-fast");

        Instant late = t.plus(Duration.ofMinutes(5));
        BatchClock.setForTest(Clock.fixed(late, ZoneOffset.UTC));
        SlowFinalizer.release.countDown();
        j.assertBuildStatusSuccess(slowFuture);
        String slowLine = awaitLine("r102-real-slow");

        assertTrue(late.toEpochMilli() > slowBuild.getStartTimeInMillis() + slowBuild.getDuration() + 60_000L,
                "premise: the slow run's record is appended more than 60 s after its start + duration");
        assertEquals(Long.valueOf(t.toEpochMilli()), appendedAtOf(fastLine),
                "D-81: a new run line carries appendedAt, the plugin clock's instant of the append: " + fastLine);
        assertEquals(Long.valueOf(late.toEpochMilli()), appendedAtOf(slowLine),
                "D-81: the late run's line carries the instant it was appended, not its start + duration: " + slowLine);
        Path fastFile = fileHolding("r102-real-fast");
        if (fastFile.equals(fileHolding("r102-real-slow"))) {
            List<String> lines = Files.readAllLines(fastFile, StandardCharsets.UTF_8);
            assertTrue(indexOf(lines, "r102-real-fast") < indexOf(lines, "r102-real-slow"),
                    "premise: the fast run's line was appended before the slow run's line");
        }
    }

    // ---------------------------------------------------------------- the slow finalizer

    /** Holds the armed job's run in {@code onCompleted} until released (the slow publisher of the defect). */
    @TestExtension("t_10_14_runLineCarriesTheInstantItWasAppended")
    @SuppressWarnings("rawtypes")
    public static class SlowFinalizer extends RunListener<Run> {
        static volatile String target;
        static volatile CountDownLatch entered = new CountDownLatch(1);
        static volatile CountDownLatch release = new CountDownLatch(1);

        public SlowFinalizer() {
            super(Run.class);
        }

        static void arm(String fullName) {
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
            target = fullName;
        }

        static void reset() {
            target = null;
            release.countDown();
        }

        @Override
        public void onCompleted(Run run, TaskListener listener) {
            if (run.getParent().getFullName().equals(target)) {
                entered.countDown();
                try {
                    release.await(120, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void append(Instant at, String job, Instant startedAt, long durationMs) throws IOException {
        BatchClock.setForTest(Clock.fixed(at, ZoneOffset.UTC));
        FileStore.get().appendRunRecord(new RunRecord(job + "#1", job, 1, CauseType.USER, "SUCCESS", startedAt, durationMs));
    }

    /** The fixture jobs (names starting {@code r102-}) named in the rows of the listing, as the viewer sees it. */
    private TreeSet<String> listedJobs(String path) throws Exception {
        HtmlPage page = UsabilityFixtures.htmlPage(j, "viewer", path);
        assertEquals(200, page.getWebResponse().getStatusCode(), "the listing " + path + " opens");
        TreeSet<String> out = new TreeSet<>();
        for (DomElement tr : page.getElementsByTagName("tr")) {
            out.addAll(fixtureJobs(tr.asNormalizedText()));
        }
        return out;
    }

    private TreeSet<String> csvJobs(String path) throws Exception {
        WebResponse csv = ApproverFormFixtures.get(j, "viewer", path);
        assertEquals(200, csv.getStatusCode(), "the export " + path + " answers 200");
        TreeSet<String> out = new TreeSet<>();
        for (String line : csv.getContentAsString().split("\\R")) {
            out.addAll(fixtureJobs(line));
        }
        return out;
    }

    private static List<String> fixtureJobs(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("r102-(earlier|fast|slow)\\b").matcher(text);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    private long appendedAt(String job) throws IOException {
        Long value = appendedAtOf(lineOf(job));
        assertNotNull(value, "D-81: the stored line of " + job + " must carry appendedAt: " + lineOf(job));
        return value;
    }

    private static Long appendedAtOf(String line) {
        Matcher m = APPENDED_AT.matcher(line);
        return m.find() ? Long.valueOf(m.group(1)) : null;
    }

    /** Waits (bounded, for the background finalization, not for a time-out) until a line naming {@code job} is stored. */
    private String awaitLine(String job) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            String line = findLine(job);
            if (line != null) {
                return line;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("fixture: no run line naming " + job + " was stored within 30 s; stored: " + storedRunLines());
    }

    private String lineOf(String job) throws IOException {
        String line = findLine(job);
        assertNotNull(line, "fixture: a run line naming " + job + " is stored");
        return line;
    }

    private String findLine(String job) throws IOException {
        Path file = fileHoldingOrNull(job);
        if (file == null) {
            return null;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.contains("\"" + job + "\"")) {
                return line;
            }
        }
        return null;
    }

    private Path fileHolding(String job) throws IOException {
        Path file = fileHoldingOrNull(job);
        assertNotNull(file, "fixture: a month file holds the line of " + job);
        return file;
    }

    private Path fileHoldingOrNull(String job) throws IOException {
        Path runs = StoreDataFixtures.storeDir().resolve("runs");
        if (!Files.isDirectory(runs)) {
            return null;
        }
        List<Path> files;
        try (Stream<Path> list = Files.list(runs)) {
            files = list.filter(p -> p.getFileName().toString().endsWith(".jsonl")).sorted().collect(Collectors.toList());
        }
        for (Path file : files) {
            if (Files.readString(file, StandardCharsets.UTF_8).contains("\"" + job + "\"")) {
                return file;
            }
        }
        return null;
    }

    /** Every stored run line, by file, for failure messages. */
    private static String storedRunLines() throws IOException {
        Path runs = StoreDataFixtures.storeDir().resolve("runs");
        if (!Files.isDirectory(runs)) {
            return "<no runs directory>";
        }
        StringBuilder out = new StringBuilder();
        try (Stream<Path> list = Files.list(runs)) {
            for (Path file : list.sorted().collect(Collectors.toList())) {
                out.append(file.getFileName()).append(": ");
                if (Files.isRegularFile(file)) {
                    out.append(Files.readString(file, StandardCharsets.UTF_8).replace('\n', '|'));
                }
                out.append(' ');
            }
        }
        return out.toString();
    }

    private static int indexOf(List<String> lines, String job) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("\"" + job + "\"")) {
                return i;
            }
        }
        return -1;
    }
}
