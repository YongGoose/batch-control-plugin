package io.jenkins.plugins.batchcontrol;

import hudson.model.Cause;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.BlockedAttemptAudit;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import io.jenkins.plugins.batchcontrol.store.Store;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.token;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.uncontrolled;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.assertBlocked;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.post;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.secureWithRunControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-51a (per-user budget of per-attempt re-run records, summary and closing record) under a
 * failing store and after the shutdown flush: security-23 S-23-03 and S-23-06, security-24
 * S-24-01/04. Matrix rows T-06-86 and T-06-87 (note 172).
 *
 * <p>Test seam (core-dev, security-24 S-24-04): {@code BlockedAttemptAudit.swapStoreForTesting},
 * {@code BlockedAttemptAudit.flushAtShutdown()} and {@code BlockedAttemptAudit.get()
 * .flushPersonSummaries()}. The failing store is a dynamic proxy over the real {@link Store} that
 * throws once from {@code appendChangeRecord} (the method security-23 names), so this class
 * depends on no other part of the interface. Time moves through {@link BatchClock}.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-51a, docs/ARCHITECTURE.md, the security-23/24
 * reports and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class RerunSummaryFlushTest {

    private JenkinsRule j;
    private Store original;
    private FreeStyleProject job;
    private Set<String> baseline;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        secureWithRunControl(j);
        job = uncontrolled(j.createFreeStyleProject("rr-flush"));
        job.getBuildersList().add(new FailureBuilder());
        BatchControlFixtures.activate(job);
        for (int i = 0; i < 24; i++) {
            try (ACLContext ignored = ACL.as2(token("u1"))) {
                j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0, new Cause.UserIdCause()));
            }
        }
        setBatchControl(job, new BatchControlJobProperty(true));
        j.waitUntilNoActivity();
        baseline = byU1(Set.of()).stream().map(ChangeRecord::getId).collect(Collectors.toSet());
    }

    @AfterEach
    public void tearDown() {
        if (original != null) {
            BlockedAttemptAudit.swapStoreForTesting(original);
        }
        BatchClock.reset();
    }

    /**
     * T-06-86 (S-23-03): u1 retries #1..#24 (20 per-attempt records, the summary, 3 counted). The
     * window ends (the clock moves 11 minutes) and the per-minute flush runs against a store whose
     * append fails once. The closing record is not lost: a second flush with the store working
     * again writes it exactly once, giving the count 4 (D-51a ruling, T-06-84).
     */
    @Test
    public void t_06_86_closingRecordSurvivesOneFailedAppend() throws Exception {
        Instant now = Instant.now();
        BatchClock.setForTest(Clock.fixed(now, ZoneOffset.UTC));
        retry(1, 24);
        assertEquals(21, byU1(baseline).size(), "fixture: 20 per-attempt records and one summary");

        AtomicInteger failures = new AtomicInteger();
        original = BlockedAttemptAudit.swapStoreForTesting(failingOnce(FileStore.get(), failures));
        BatchClock.setForTest(Clock.fixed(now.plus(Duration.ofMinutes(11)), ZoneOffset.UTC));
        try {
            BlockedAttemptAudit.get().flushPersonSummaries();
        } catch (RuntimeException expected) {
            // a failing append may surface; the count must not be lost either way
        }
        assertEquals(1, failures.get(), "fixture: the first flush must have tried to append the closing record and"
                + " met the failure");
        assertEquals(21, byU1(baseline).size(), "fixture: the failed append wrote nothing");

        BlockedAttemptAudit.get().flushPersonSummaries();
        List<ChangeRecord> after = byU1(baseline);
        assertEquals(22, after.size(), "the next flush must write the closing record exactly once: " + describe(after));
        String closing = after.stream().map(ChangeRecord::getDetail).filter(d -> d != null && d.matches("(?s).*#24\\b.*")
                && d.matches("(?s).*#21\\b.*")).findFirst().orElse("");
        assertTrue(closing.matches("(?s).*\\b4\\b.*"), "the closing record must give the count 4 and name #21..#24: "
                + describe(after));

        BlockedAttemptAudit.get().flushPersonSummaries();
        assertEquals(22, byU1(baseline).size(), "a further flush must not write the closing record again");
    }

    /**
     * T-06-87 (S-23-06, S-24-01): after the shutdown flush, u1's further refusals do not open a
     * summary. u1 retries #1..#18, the shutdown flush runs, then u1 retries #19..#22: #19 and #20
     * are within the budget and each gets a per-attempt record; #21 and #22 are beyond it and
     * nothing more is recorded (no summary record).
     */
    @Test
    public void t_06_87_afterTheShutdownFlushNoSummaryOpens() throws Exception {
        retry(1, 18);
        assertEquals(18, byU1(baseline).size(), "fixture: 18 per-attempt records");
        BlockedAttemptAudit.flushAtShutdown();
        Set<String> beforeLate = byU1(Set.of()).stream().map(ChangeRecord::getId).collect(Collectors.toSet());

        retry(19, 20);
        List<ChangeRecord> within = byU1(beforeLate);
        assertEquals(2, within.size(), "refusals within the budget after the shutdown flush are written per attempt: "
                + describe(within));
        assertTrue(within.stream().allMatch(r -> String.valueOf(r.getDetail()).matches("(?s).*#(19|20)\\b.*")),
                "each record names its build: " + describe(within));

        retry(21, 22);
        List<ChangeRecord> beyond = byU1(beforeLate);
        assertEquals(2, beyond.size(), "beyond the budget after the shutdown flush nothing more is recorded (no summary"
                + " opens): " + describe(beyond));
    }

    // ---------------------------------------------------------------- helpers

    private void retry(int from, int to) throws Exception {
        for (int n = from; n <= to; n++) {
            post(j, "u1", job.getBuildByNumber(n).getUrl() + "retry/");
        }
        assertBlocked(j, job, 25, 24);
    }

    /** A {@link Store} that forwards every call to {@code real}, except that the first appendChangeRecord throws. */
    private static Store failingOnce(Store real, AtomicInteger failures) {
        return (Store) Proxy.newProxyInstance(Store.class.getClassLoader(), new Class<?>[] {Store.class},
                (proxy, method, args) -> {
                    if ("appendChangeRecord".equals(method.getName()) && failures.compareAndSet(0, 1)) {
                        throw failure(method);
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /** An IOException when the method declares it, otherwise its unchecked wrapper. */
    private static Throwable failure(Method method) {
        IOException io = new IOException("test: the store write failed once");
        return Arrays.asList(method.getExceptionTypes()).stream().anyMatch(t -> t.isAssignableFrom(IOException.class))
                ? io : new UncheckedIOException(io);
    }

    private static List<ChangeRecord> byU1(Set<String> skip) {
        return FileStore.get().listChangeRecords(YearMonth.now()).stream()
                .filter(r -> "u1".equals(r.getUser()) && !skip.contains(r.getId()))
                .collect(Collectors.toList());
    }

    private static String describe(List<ChangeRecord> records) {
        return records.stream().map(r -> r.getType() + " " + r.getDetail()).collect(Collectors.joining("; ", "[", "]"));
    }
}
