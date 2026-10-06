package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
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
 * Recording turned on by JCasC while Jenkins starts refreshes the configuration snapshots like any other
 * switch-on. Matrix row T-GAP-424 (note 282).
 *
 * <p>Basis: DECISIONS D-76 (1): "When recording turns on (neither switch was on before), the configuration
 * snapshot of every item is refreshed to its current configuration, so changes made while recording was off are
 * not attributed to the first person who saves afterwards"; D-42 (a JCasC apply of a switch applies the value);
 * SPEC 2 (the global configuration is applied through JCasC with the symbol {@code batchControl}, as in
 * {@link SwitchSaveFailureTest} T-01-12 and {@link StrategyCascTest}); SPEC 9 ("CONFIGURE 변경에 unified diff가
 * 저장된다"); LIMITATIONS 17 ({@code batch-control/snapshots/<job>.xml}); ARCHITECTURE 5.
 *
 * <p>The YAML is handed to JCasC through the system property {@code casc.jenkins.config}, set only between the
 * two sessions and restored afterwards. Session 2 waits at most {@link #REFRESH_WAIT_MS}, polling, for the
 * snapshot to hold the off-period value, because no document says whether the refresh at startup finishes before
 * or after Jenkins reports itself started (a bounded wait for background work, not for an expiry).
 *
 * <p>Written from docs/SPEC.md items 2 and 9, docs/DECISIONS.md D-42 and D-76, docs/LIMITATIONS.md item 17 and
 * docs/ARCHITECTURE.md section 5 only (no src/main knowledge).
 */
public class SnapshotRefreshCascStartupGapTest {

    static final String CASC_PROPERTY = "casc.jenkins.config";
    static final long REFRESH_WAIT_MS = 60_000;

    @RegisterExtension
    final JenkinsSessionExtension session = new JenkinsSessionExtension();

    @TempDir
    Path tmp;

    /**
     * T-GAP-424 (D-76 (1); D-42; SPEC 2, SPEC 9): session 1: change control on; job {@code casc-j} created and
     * given the description {@code before off} (premise: its snapshot is a regular file); change control off; the
     * description set to {@code off}. The system property {@code casc.jenkins.config} then names a YAML file with
     * {@code unclassified: batchControl: changeControlEnabled: true}. Session 2: change control is on (premise:
     * JCasC applied it at startup); within {@link #REFRESH_WAIT_MS} {@code snapshots/casc-j.xml} holds the
     * description {@code off}; the administrator changes the description to {@code after casc} over REST: exactly
     * one new CONFIGURE record, whose diff removes {@code off} and adds {@code after casc}, and does not mention
     * {@code before off}.
     */
    @Test
    public void t_gap_424_jcascTurningRecordingOnAtStartupRefreshesTheSnapshots() throws Throwable {
        Path yaml = tmp.resolve("batch-control-casc.yaml");
        Files.writeString(yaml, "unclassified:\n  batchControl:\n    changeControlEnabled: true\n", StandardCharsets.UTF_8);

        session.then(r -> {
            secure(r);
            BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
            assertFalse(cfg.isChangeControlEnabled() || cfg.isRunControlEnabled(), "premise (SPEC 1): both switches are off after installation");
            cfg.setChangeControlEnabled(true);
            cfg.save();
            FreeStyleProject job = r.createFreeStyleProject("casc-j");
            job.setDescription("before off");
            Path snapshot = snapshot(r);
            assertTrue(Files.isRegularFile(snapshot), "premise (ARCHITECTURE 5): the job's snapshot is stored at " + snapshot);
            assertTrue(read(snapshot).contains("<description>before off</description>"), "premise: the snapshot holds the pre-off value");

            cfg.setChangeControlEnabled(false);
            cfg.save();
            assertFalse(BatchControlGlobalConfiguration.get().isChangeControlEnabled(), "fixture: change control is off");
            job.setDescription("off");
            System.out.println("T-GAP-424 observation: with recording off the snapshot "
                    + (holdsOff(snapshot) ? "already holds" : "does not hold") + " the off-period value");
        });

        String previous = System.getProperty(CASC_PROPERTY);
        System.setProperty(CASC_PROPERTY, yaml.toString());
        try {
            session.then(r -> {
                secure(r);
                assertTrue(BatchControlGlobalConfiguration.get().isChangeControlEnabled(),
                        "premise (D-42, SPEC 2): JCasC turned change control on at startup");
                FreeStyleProject job = r.jenkins.getItemByFullName("casc-j", FreeStyleProject.class);
                assertNotNull(job, "premise: casc-j survived the restart");
                assertEquals("off", job.getDescription(), "premise: the off-period value survived the restart");
                Path snapshot = snapshot(r);
                long deadline = System.currentTimeMillis() + REFRESH_WAIT_MS;
                while (!holdsOff(snapshot) && System.currentTimeMillis() < deadline) {
                    Thread.sleep(100); // bounded wait for the refresh at startup, not for an expiry
                }
                assertTrue(holdsOff(snapshot), "D-76 (1): recording turned on by JCasC at startup refreshes the snapshot to the current"
                        + " configuration within " + REFRESH_WAIT_MS + " ms: " + (Files.isRegularFile(snapshot) ? read(snapshot) : "<no file>"));
                int before = records(ChangeType.CONFIGURE, "casc-j").size();

                assertEquals(200, postConfigXml(r, "admin", job, withDescription(job.getConfigFile().asString(), "after casc")),
                        "fixture: the administrator's config.xml POST succeeds");
                List<ChangeRecord> after = records(ChangeType.CONFIGURE, "casc-j");
                assertEquals(before + 1, after.size(), "SPEC 9: one change, one CONFIGURE record: " + describe(after.subList(before, after.size())));
                ChangeRecord record = after.get(after.size() - 1);
                assertDiffShows(record, "off", "after casc", "D-76 (1): the snapshot refreshed at startup is the baseline");
                assertFalse(record.getDiff().contains("<description>before off</description>"),
                        "D-76 (1): the change made while recording was off is not attributed to the administrator: " + record.getDiff());
            });
        } finally {
            if (previous == null) {
                System.clearProperty(CASC_PROPERTY);
            } else {
                System.setProperty(CASC_PROPERTY, previous);
            }
        }
    }

    private static boolean holdsOff(Path snapshot) throws IOException {
        return Files.isRegularFile(snapshot) && read(snapshot).contains("<description>off</description>");
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static void secure(JenkinsRule r) {
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy().grant(Jenkins.ADMINISTER).everywhere().to("admin"));
    }

    private static Path snapshot(JenkinsRule r) {
        return r.jenkins.getRootDir().toPath().resolve("batch-control/snapshots/casc-j.xml");
    }
}
