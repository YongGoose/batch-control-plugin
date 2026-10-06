package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

import static io.jenkins.plugins.batchcontrol.SnapshotBaselineGapTest.assertDiffShows;
import static io.jenkins.plugins.batchcontrol.SnapshotBaselineGapTest.describe;
import static io.jenkins.plugins.batchcontrol.SnapshotBaselineGapTest.postConfigXml;
import static io.jenkins.plugins.batchcontrol.SnapshotBaselineGapTest.records;
import static io.jenkins.plugins.batchcontrol.SnapshotBaselineGapTest.withDescription;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An item without a configuration snapshot gets one again when Jenkins starts while recording is active, so its
 * next change has a diff. Matrix row T-GAP-408 (note 281).
 *
 * <p>Basis: SPEC 9 ("CONFIGURE 변경에 unified diff가 저장된다"); SPEC 4 (the store survives a restart); DECISIONS
 * D-76 (1) (the snapshot is the baseline of the next change; an item saved with no snapshot at all is recorded
 * without a diff); LIMITATIONS 17 and 53 ({@code batch-control/snapshots/<job>.xml}); ARCHITECTURE 5. The
 * startup seeding itself is the scenario relayed by the coordinator ("restart; wait (bounded) until
 * {@code snapshots/<J>.xml} reappears"): the row waits at most {@link #SEEDING_WAIT_MS} for the file, polling,
 * because no document says whether the seeding runs before or after Jenkins reports itself started.
 *
 * <p>Written from docs/SPEC.md items 4 and 9, docs/DECISIONS.md D-76, docs/LIMITATIONS.md items 17 and 53 and
 * docs/ARCHITECTURE.md section 5 only (no src/main knowledge).
 */
public class SnapshotSeedingRestartGapTest {

    /** The bound on the wait for the startup seeding to write the snapshot again. */
    static final long SEEDING_WAIT_MS = 60_000;

    @RegisterExtension
    final JenkinsSessionExtension session = new JenkinsSessionExtension();

    /**
     * T-GAP-408 (D-76 (1); SPEC 9, SPEC 4): session 1: change control on; job {@code seed-j} created with the
     * description {@code before restart} (premise: {@code snapshots/seed-j.xml} is a regular file); the snapshot
     * file is deleted. Session 2 (after a restart): within {@link #SEEDING_WAIT_MS} the file
     * {@code snapshots/seed-j.xml} is there again; the administrator changes the description to
     * {@code after restart} over REST: exactly one new CONFIGURE record, whose diff goes from
     * {@code before restart} to {@code after restart}.
     */
    @Test
    public void t_gap_408_startupSeedsTheMissingSnapshotSoTheNextChangeHasADiff() throws Throwable {
        session.then(r -> {
            secure(r);
            BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
            cfg.setChangeControlEnabled(true);
            cfg.save();
            FreeStyleProject job = r.createFreeStyleProject("seed-j");
            job.setDescription("before restart");
            Path snapshot = snapshot(r);
            assertTrue(Files.isRegularFile(snapshot), "premise (ARCHITECTURE 5): the job's snapshot is stored at " + snapshot);
            Files.delete(snapshot);
            assertFalse(Files.exists(snapshot), "fixture: the snapshot is deleted before the restart");
        });
        session.then(r -> {
            secure(r);
            assertTrue(BatchControlGlobalConfiguration.get().isChangeControlEnabled(), "premise (SPEC 4): change control is still on");
            FreeStyleProject job = r.jenkins.getItemByFullName("seed-j", FreeStyleProject.class);
            assertNotNull(job, "premise: seed-j survived the restart");
            Path snapshot = snapshot(r);
            long deadline = System.currentTimeMillis() + SEEDING_WAIT_MS;
            while (!Files.isRegularFile(snapshot) && System.currentTimeMillis() < deadline) {
                Thread.sleep(100); // bounded wait for the startup seeding, not for an expiry
            }
            assertTrue(Files.isRegularFile(snapshot), "D-76 (1): the startup seeding writes the missing snapshot again within "
                    + SEEDING_WAIT_MS + " ms: " + snapshot);
            int before = records(ChangeType.CONFIGURE, "seed-j").size();

            assertEquals(200, postConfigXml(r, "admin", job, withDescription(job.getConfigFile().asString(), "after restart")),
                    "fixture: the administrator's config.xml POST succeeds");
            List<ChangeRecord> after = records(ChangeType.CONFIGURE, "seed-j");
            assertEquals(before + 1, after.size(), "SPEC 9: one change, one CONFIGURE record: " + describe(after));
            assertDiffShows(after.get(after.size() - 1), "before restart", "after restart",
                    "D-76 (1): the seeded snapshot is the baseline of the next change");
        });
    }

    private static void secure(JenkinsRule r) {
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy().grant(Jenkins.ADMINISTER).everywhere().to("admin"));
    }

    private static Path snapshot(JenkinsRule r) {
        return r.jenkins.getRootDir().toPath().resolve("batch-control/snapshots/seed-j.xml");
    }
}
