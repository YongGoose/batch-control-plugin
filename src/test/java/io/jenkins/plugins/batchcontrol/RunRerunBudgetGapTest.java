package io.jenkins.plugins.batchcontrol;

import hudson.model.Cause;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Result;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.BlockedAttemptAudit;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import io.jenkins.plugins.batchcontrol.store.Store;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LogRecorder;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.token;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.uncontrolled;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.post;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage lane 1, scenario L1-20: timing of the refused re-run budget. Matrix rows T-GAP-175 ..
 * T-GAP-178 (note 276).
 *
 * <p>Basis: SPEC 6 D-51/D-51a "A refused re-run submitted by a person ... writes one record per
 * attempt ... Per user at most 20 such records per rolling 10 minutes; the next refusal in that
 * window writes one summary record, later ones are only counted, and one closing record gives the
 * number of all refusals beyond the 20 per-attempt records, the one that opened the summary
 * included, when the window ends (records are never rewritten)". The seam is the one
 * RerunSummaryFlushTest uses ({@code BlockedAttemptAudit.swapStoreForTesting},
 * {@code flushAtShutdown()}, {@code get().flushPersonSummaries()}); time moves through
 * {@link BatchClock}. Refused re-runs are naginator Retry clicks on failed builds of an
 * approval-required job. A summary record is recognised by saying that further refusals are
 * counted (D-51a wording), a closing record by naming the counted builds.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-51/D-51a and the existing D-51a rows only (no
 * src/main knowledge).
 */
@WithJenkins
public class RunRerunBudgetGapTest {

    private static final Instant T = Instant.parse("2026-09-25T09:00:00Z");

    private JenkinsRule j;
    private FreeStyleProject job;
    private Store original;
    private Set<String> baseline;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST).everywhere().to("u1", "u2")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        job = uncontrolled(j.createFreeStyleProject("gap-budget"));
        job.getBuildersList().add(new FailureBuilder());
        BatchControlFixtures.activate(job);
        for (int i = 0; i < 24; i++) {
            try (ACLContext ignored = ACL.as2(token("u1"))) {
                j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0, new Cause.UserIdCause()));
            }
        }
        setBatchControl(job, new BatchControlJobProperty(true));
        j.waitUntilNoActivity();
        BatchClock.setForTest(Clock.fixed(T, ZoneOffset.UTC));
        baseline = all().stream().map(ChangeRecord::getId).collect(Collectors.toSet());
    }

    @AfterEach
    public void tearDown() {
        if (original != null) {
            BlockedAttemptAudit.swapStoreForTesting(original);
        }
        BatchClock.reset();
    }

    /**
     * T-GAP-175 (L1-20 case 1): u1 is refused once at T (one per-attempt record). At T+11 min the
     * 10-minute window has rolled, so u1's next 21 refusals start a fresh budget: 20 per-attempt
     * records and one summary record. (Without the roll the first refusal would still count, and
     * only 19 per-attempt records would precede the summary.)
     */
    @Test
    public void t_gap_175_theWindowRollsAfterTenMinutes() throws Exception {
        retry("u1", 1, 1);
        List<ChangeRecord> first = since(baseline, "u1");
        assertEquals(1, first.size(), "one per-attempt record at T: " + describe(first));
        Set<String> afterFirst = ids();

        at(T.plus(Duration.ofMinutes(11)));
        retry("u1", 2, 22);
        List<ChangeRecord> fresh = since(afterFirst, "u1");
        long summaries = fresh.stream().filter(RunRerunBudgetGapTest::isSummary).count();
        long perAttempt = fresh.size() - summaries;
        assertEquals(20, perAttempt, "a rolled window gives a fresh budget of 20 per-attempt records: " + describe(fresh));
        assertEquals(1, summaries, "the 21st refusal in the new window opens one summary: " + describe(fresh));
    }

    /**
     * T-GAP-176 (L1-20 case 2): u1 is refused 22 times at T (20 per-attempt, a summary opened by
     * #21, #22 counted). At T+11 min, without any per-minute flush, u1 is refused again on #23: the
     * closing record (count 2, naming #21 and #22) is written before the per-attempt record of #23.
     */
    @Test
    public void t_gap_176_closingRecordPrecedesTheNextWindowsFirstRecord() throws Exception {
        retry("u1", 1, 22);
        assertEquals(21, since(baseline, "u1").size(), "fixture: 20 per-attempt records and one summary");
        Set<String> beforeLate = ids();

        at(T.plus(Duration.ofMinutes(11)));
        retry("u1", 23, 23);
        List<ChangeRecord> late = since(beforeLate, "u1");
        ChangeRecord closing = late.stream().filter(r -> names(r, 21) && names(r, 22)).findFirst().orElse(null);
        ChangeRecord next = late.stream().filter(r -> names(r, 23) && !names(r, 21)).findFirst().orElse(null);
        assertTrue(closing != null, "the closing record of the ended window is written: " + describe(late));
        assertTrue(next != null, "the new refusal gets its per-attempt record: " + describe(late));
        assertTrue(String.valueOf(closing.getDetail()).matches("(?s).*\\b2\\b.*"), "the closing record gives the count 2: " + closing.getDetail());
        assertTrue(lineOf(closing.getId()) < lineOf(next.getId()), "the closing record is written before the new per-attempt record");
        assertEquals(2, late.size(), "nothing else is written: " + describe(late));
    }

    /**
     * T-GAP-177 (L1-20 case 3 (F)): u1 (22 refusals) and u2 (21 refusals) both have an open summary
     * at T. At T+11 min one per-minute flush runs against a store whose appends fail: both closing
     * records fail. The next flush with a working store writes both (u1 count 2, u2 count 1), and a
     * further flush writes nothing more.
     */
    @Test
    public void t_gap_177_twoFailedClosingRecordsAreBothKept() throws Exception {
        retry("u1", 1, 22);
        retry("u2", 1, 21);
        Set<String> beforeFlush = ids();
        AtomicBoolean failing = new AtomicBoolean(true);
        AtomicInteger failures = new AtomicInteger();
        original = BlockedAttemptAudit.swapStoreForTesting(failingWhile(FileStore.get(), failing, failures));
        at(T.plus(Duration.ofMinutes(11)));
        try {
            BlockedAttemptAudit.get().flushPersonSummaries();
        } catch (RuntimeException expected) {
            // a failing append may surface; the counts must not be lost either way
        }
        assertTrue(failures.get() >= 2, "fixture: the flush tried to append both closing records, failures=" + failures.get());
        assertEquals(beforeFlush, ids(), "fixture: the failed flush wrote nothing");

        failing.set(false);
        BlockedAttemptAudit.get().flushPersonSummaries();
        List<ChangeRecord> u1 = since(beforeFlush, "u1");
        List<ChangeRecord> u2 = since(beforeFlush, "u2");
        assertEquals(1, u1.size(), "u1's closing record is written by the next good flush: " + describe(u1));
        assertEquals(1, u2.size(), "u2's closing record is written by the next good flush: " + describe(u2));
        assertTrue(String.valueOf(u1.get(0).getDetail()).matches("(?s).*\\b2\\b.*") && names(u1.get(0), 21) && names(u1.get(0), 22),
                "u1's closing record gives the count 2 and names #21, #22: " + describe(u1));
        assertTrue(String.valueOf(u2.get(0).getDetail()).matches("(?s).*\\b1\\b.*") && names(u2.get(0), 21),
                "u2's closing record gives the count 1 and names #21: " + describe(u2));

        Set<String> afterGood = ids();
        BlockedAttemptAudit.get().flushPersonSummaries();
        assertEquals(afterGood, ids(), "a further flush writes nothing more");
    }

    /**
     * T-GAP-178 (L1-20 case 3 (F)): u1 has an open summary (22 refusals at T); the shutdown flush
     * runs against a failing store: the count is logged (a log record naming u1 and the count 2),
     * and after the store works again later flushes write u1's closing record at most once.
     */
    @Test
    public void t_gap_178_failedShutdownFlushLogsTheCountAndDoesNotDuplicate() throws Exception {
        retry("u1", 1, 22);
        Set<String> beforeFlush = ids();
        AtomicBoolean failing = new AtomicBoolean(true);
        AtomicInteger failures = new AtomicInteger();
        original = BlockedAttemptAudit.swapStoreForTesting(failingWhile(FileStore.get(), failing, failures));
        try (LogRecorder log = new LogRecorder().record("io.jenkins.plugins.batchcontrol", Level.ALL).capture(10000).quiet()) {
            try {
                BlockedAttemptAudit.flushAtShutdown();
            } catch (RuntimeException expected) {
                // the failure may surface; what counts is the log and that nothing is written twice
            }
            assertTrue(failures.get() >= 1, "fixture: the shutdown flush tried to append u1's closing record");
            SimpleFormatter formatter = new SimpleFormatter();
            boolean logged = false;
            for (LogRecord record : log.getRecords()) {
                String text = formatter.formatMessage(record);
                if (text.contains("u1") && text.matches("(?s).*\\b2\\b.*")) {
                    logged = true;
                }
            }
            assertTrue(logged, "the failed shutdown flush must log u1's count 2: "
                    + log.getRecords().stream().map(formatter::formatMessage).collect(Collectors.toList()));
        }
        assertEquals(beforeFlush, ids(), "the failed shutdown flush wrote nothing");

        failing.set(false);
        at(T.plus(Duration.ofMinutes(11)));
        BlockedAttemptAudit.get().flushPersonSummaries();
        BlockedAttemptAudit.flushAtShutdown();
        long closings = since(beforeFlush, "u1").stream().filter(r -> names(r, 21) && names(r, 22)).count();
        assertTrue(closings <= 1, "u1's closing record is never written twice: " + describe(since(beforeFlush, "u1")));
    }

    // ------------------------------------------------------------------ helpers

    private void retry(String user, int from, int to) throws Exception {
        int next = job.getNextBuildNumber();
        int builds = job.getBuilds().size();
        for (int n = from; n <= to; n++) {
            post(j, user, job.getBuildByNumber(n).getUrl() + "retry/");
        }
        PluginInteractionFixtures.assertBlocked(j, job, next, builds);
    }

    private static void at(Instant instant) {
        BatchClock.setForTest(Clock.fixed(instant, ZoneOffset.UTC));
    }

    private static List<ChangeRecord> all() {
        return FileStore.get().listChangeRecords(YearMonth.from(T.atZone(ZoneOffset.UTC)));
    }

    private static Set<String> ids() {
        return all().stream().map(ChangeRecord::getId).collect(Collectors.toSet());
    }

    private static List<ChangeRecord> since(Set<String> skip, String user) {
        return all().stream().filter(r -> user.equals(r.getUser()) && !skip.contains(r.getId())).collect(Collectors.toList());
    }

    private static boolean isSummary(ChangeRecord r) {
        return String.valueOf(r.getDetail()).toLowerCase(java.util.Locale.ROOT).contains("counted");
    }

    private static boolean names(ChangeRecord r, int build) {
        return String.valueOf(r.getDetail()).matches("(?s).*#" + build + "\\b.*");
    }

    /** The line number of the record {@code id} in this month's {@code changes/} file (append order). */
    private int lineOf(String id) throws IOException {
        Path file = j.jenkins.getRootDir().toPath().resolve("batch-control").resolve("changes")
                .resolve(StoreDataFixtures.monthName(YearMonth.from(T.atZone(ZoneOffset.UTC))) + ".jsonl");
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(id)) {
                return i;
            }
        }
        throw new AssertionError("record " + id + " is not in " + file);
    }

    private static String describe(List<ChangeRecord> records) {
        return records.stream().map(r -> r.getType() + " " + r.getUser() + " " + r.getDetail()).collect(Collectors.joining("; ", "[", "]"));
    }

    /** A {@link Store} forwarding every call to {@code real}, except that appendChangeRecord fails while {@code failing} is set. */
    private static Store failingWhile(Store real, AtomicBoolean failing, AtomicInteger failures) {
        return (Store) Proxy.newProxyInstance(Store.class.getClassLoader(), new Class<?>[] {Store.class},
                (proxy, method, args) -> {
                    if ("appendChangeRecord".equals(method.getName()) && failing.get()) {
                        failures.incrementAndGet();
                        throw failure(method);
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static Throwable failure(Method method) {
        IOException io = new IOException("test: the store write failed");
        return Arrays.stream(method.getExceptionTypes()).anyMatch(t -> t.isAssignableFrom(IOException.class))
                ? io : new UncheckedIOException(io);
    }
}
