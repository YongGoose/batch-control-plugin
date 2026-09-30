package io.jenkins.plugins.batchcontrol;

import io.jenkins.plugins.batchcontrol.store.BatchClock;
import hudson.model.Cause;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.time.YearMonth;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.token;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.uncontrolled;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.assertBlocked;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.post;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.secureWithRunControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 6 (D-51a) and docs/LIMITATIONS.md (the per-attempt records item): "On a clean
 * shutdown, the closing record of every open summary is written before Jenkins stops and says
 * that the window ended early because Jenkins was shutting down." Matrix row T-06-84 (note 166,
 * security-23 S-23-08).
 *
 * <p>The clean shutdown is the one {@link JenkinsSessionExtension} performs between sessions.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-51a, docs/LIMITATIONS.md,
 * docs/reports/security-23.md and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
public class RerunSummaryShutdownTest {

    @RegisterExtension
    final JenkinsSessionExtension session = new JenkinsSessionExtension();

    /**
     * T-06-84: u1 retries 24 different failed builds within seconds: 20 per-attempt records, one
     * summary opened by #21, and #22..#24 only counted, so the summary is still open. After a clean
     * restart the history holds exactly one further record by u1: the closing record, giving the
     * count 4 (every refusal beyond the 20, including #21 that opened the summary, D-51a), naming
     * #21..#24 and none of #1..#20, and saying that the window ended early because Jenkins was
     * shutting down.
     */
    @Test
    public void t_06_84_cleanShutdownWritesTheClosingRecordOfAnOpenSummary() throws Throwable {
        AtomicReference<Set<String>> before = new AtomicReference<>();
        session.then(r -> {
            secureWithRunControl(r);
            FreeStyleProject job = uncontrolled(r.createFreeStyleProject("rr-shutdown"));
            job.getBuildersList().add(new FailureBuilder());
            BatchControlFixtures.activate(job);
            for (int i = 0; i < 24; i++) {
                try (ACLContext ignored = ACL.as2(token("u1"))) {
                    r.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0, new Cause.UserIdCause()));
                }
            }
            setBatchControl(job, new BatchControlJobProperty(true));
            r.waitUntilNoActivity();
            Set<String> baseline = byU1(Set.of()).stream().map(ChangeRecord::getId).collect(Collectors.toSet());

            for (int n = 1; n <= 24; n++) {
                post(r, "u1", job.getBuildByNumber(n).getUrl() + "retry/");
            }
            assertBlocked(r, job, 25, 24);
            List<ChangeRecord> open = byU1(baseline);
            assertEquals(21, open.size(), "fixture: 20 per-attempt records and one summary before the restart: "
                    + describe(open));
            before.set(open.stream().map(ChangeRecord::getId).collect(Collectors.toSet()));
            before.get().addAll(baseline);
        });
        session.then(r -> {
            List<ChangeRecord> added = byU1(before.get());
            assertEquals(1, added.size(), "a clean shutdown must write exactly one closing record for u1's open"
                    + " summary: " + describe(added));
            String text = String.valueOf(added.get(0).getDetail());
            String lower = text.toLowerCase(Locale.ROOT);
            // D-51a (1a80eff): the count covers every refusal beyond the 20 per-attempt records,
            // including the one that opened the summary: #21..#24
            assertTrue(text.matches("(?s).*\\b4\\b.*"), "the closing record must give the count 4 (#21..#24): " + text);
            for (int n = 21; n <= 24; n++) {
                assertTrue(text.matches("(?s).*#" + n + "\\b.*"), "the closing record must name build #" + n + ": " + text);
            }
            for (int n = 1; n <= 20; n++) {
                assertTrue(!text.matches("(?s).*#" + n + "\\b.*"), "the closing record must not name build #" + n
                        + ", which has its own record: " + text);
            }
            assertTrue(lower.contains("shut"), "the closing record must say the window ended early because Jenkins was"
                    + " shutting down: " + text);
        });
    }

    private static List<ChangeRecord> byU1(Set<String> skip) {
        return FileStore.get().listChangeRecords(YearMonth.now(BatchClock.clock())).stream()
                .filter(r -> "u1".equals(r.getUser()) && !skip.contains(r.getId()))
                .collect(Collectors.toList());
    }

    private static String describe(List<ChangeRecord> records) {
        return records.stream().map(r -> r.getType() + " " + r.getDetail()).collect(Collectors.joining("; ", "[", "]"));
    }
}
