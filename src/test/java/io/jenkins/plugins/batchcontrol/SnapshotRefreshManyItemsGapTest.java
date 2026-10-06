package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

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
 * The refresh of the configuration snapshots at switch-on with more than 1,000 items: an item edited right after
 * the switch is never charged with the change made while recording was off. Matrix row T-GAP-425 (note 282).
 *
 * <p>Basis: DECISIONS D-76 (1): "When recording turns on (neither switch was on before), the configuration
 * snapshot of every item is refreshed to its current configuration, so changes made while recording was off are
 * not attributed to the first person who saves afterwards"; SPEC 9 ("CONFIGURE 변경에 unified diff가 저장된다");
 * LIMITATIONS 17 and 53 (the snapshot is the baseline; a CONFIGURE record whose previous configuration is not
 * available has no diff and a note instead, and the next change has a diff again); ARCHITECTURE 5. The note of a
 * record made before the refresh reached the item is the wording relayed by the coordinator: "No diff: change
 * recording had just been turned on, and the configuration of this item before this change was not recorded
 * yet."
 *
 * <p>Fixture: {@value #ITEMS} freestyle jobs {@code j0000} .. {@code j1099} are created while both switches are
 * off; change control is turned on once so that every job has a snapshot (premise, polled with a bound), then off
 * again. The waits are bounded polls for background work ({@link #REFRESH_WAIT_MS}), never for an expiry. The row
 * is P2 for its runtime, which is printed.
 *
 * <p>Written from docs/SPEC.md items 1 and 9, docs/DECISIONS.md D-76, docs/LIMITATIONS.md items 17 and 53 and
 * docs/ARCHITECTURE.md section 5 only (no src/main knowledge).
 */
@WithJenkins
public class SnapshotRefreshManyItemsGapTest {

    static final int ITEMS = 1_100;
    static final long REFRESH_WAIT_MS = 180_000;
    static final String JUST_TURNED_ON_NOTE = "No diff: change recording had just been turned on, and the configuration of this item"
            + " before this change was not recorded yet.";

    private JenkinsRule j;
    private BatchControlGlobalConfiguration cfg;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin"));
        cfg = BatchControlGlobalConfiguration.get();
        assertFalse(cfg.isRunControlEnabled() || cfg.isChangeControlEnabled(), "premise (SPEC 1): both switches are off after installation");
    }

    /**
     * T-GAP-425 (D-76 (1); SPEC 9; LIMITATIONS 53): {@value #ITEMS} jobs {@code j0000} .. {@code j1099};
     * {@code j1099} has the description {@code before off}; change control on until every job has a snapshot
     * (premise), then off. While off, {@code j1099}'s description is set to {@code off value} and
     * {@code j0000}'s to {@code off zero}. Change control on, and the administrator immediately changes
     * {@code j1099}'s description to {@code edited at switch-on} over REST: exactly one new CONFIGURE record,
     * which either has no diff and the note "No diff: change recording had just been turned on, ..." or has a
     * diff that removes {@code off value} and adds {@code edited at switch-on}; never a diff that mentions
     * {@code before off} or adds {@code off value}. After polling (bounded) until the snapshots of {@code j0000}
     * and {@code j1099} hold their current configuration, a further change to {@code edited after refresh} writes
     * one CONFIGURE record with a normal diff from {@code edited at switch-on}.
     */
    @Test
    public void t_gap_425_editRightAfterSwitchOnWithMoreThanAThousandItemsIsNotChargedWithTheOffPeriod() throws Exception {
        long started = System.nanoTime();
        // createProjectFromXML with recording off: j.createFreeStyleProject took about one second per job
        byte[] blank = ("<?xml version='1.1' encoding='UTF-8'?><project><builders/><publishers/><buildWrappers/></project>")
                .getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < ITEMS; i++) {
            j.jenkins.createProjectFromXML(name(i), new java.io.ByteArrayInputStream(blank));
        }
        long created = System.nanoTime();
        System.out.println("T-GAP-425 timing: " + ITEMS + " jobs created in " + millis(started, created) + " ms");
        String last = name(ITEMS - 1);
        String first = name(0);
        job(last).setDescription("before off");

        recording(true);
        long seeded = waitFor(() -> allSnapshotsPresent() && holds(last, "before off"));
        assertTrue(allSnapshotsPresent(), "premise (D-76 (1)): after switch-on every one of the " + ITEMS + " jobs has a snapshot within "
                + REFRESH_WAIT_MS + " ms");
        assertTrue(holds(last, "before off"), "premise: " + last + "'s snapshot holds its configuration");
        System.out.println("T-GAP-425 timing: first switch-on seeded all snapshots after " + seeded + " ms of polling");

        recording(false);
        job(last).setDescription("off value");
        job(first).setDescription("off zero");
        int before = records(ChangeType.CONFIGURE, last).size();
        assertEquals(0, before, "premise (SPEC 9, D-76 (2)): no CONFIGURE record of " + last + " yet");

        long switchOn = System.nanoTime();
        recording(true);
        long switched = System.nanoTime();
        assertEquals(200, postConfigXml(j, "admin", job(last), withDescription(job(last).getConfigFile().asString(), "edited at switch-on")),
                "fixture: the administrator's config.xml POST succeeds right after switch-on");
        long edited = System.nanoTime();
        System.out.println("T-GAP-425 timing: switch-on save took " + millis(switchOn, switched) + " ms, the immediate edit "
                + millis(switched, edited) + " ms");
        List<ChangeRecord> afterFirst = records(ChangeType.CONFIGURE, last);
        assertEquals(before + 1, afterFirst.size(), "SPEC 9: one change, one CONFIGURE record: " + describe(afterFirst.subList(before, afterFirst.size())));
        ChangeRecord immediate = afterFirst.get(afterFirst.size() - 1);
        assertEquals("admin", immediate.getUser(), "SPEC 9: the record names who changed it");
        String diff = immediate.getDiff();
        if (diff == null || diff.isBlank()) {
            System.out.println("T-GAP-425 observation: the immediate edit has no diff; detail: " + immediate.getDetail());
            assertTrue(String.valueOf(immediate.getDetail()).contains(JUST_TURNED_ON_NOTE),
                    "D-76 (1), LIMITATIONS 53: a record without a diff right after switch-on says why: " + immediate.getDetail());
        } else {
            System.out.println("T-GAP-425 observation: the immediate edit has a diff");
            assertFalse(diff.contains("<description>before off</description>"),
                    "D-76 (1): the snapshot from before recording was off is never the baseline: " + diff);
            assertFalse(Arrays.stream(diff.split("\\R")).anyMatch(l -> l.startsWith("+") && !l.startsWith("+++")
                            && l.contains("<description>off value</description>")),
                    "D-76 (1): the off-period value is never shown as added by the edit: " + diff);
            assertDiffShows(immediate, "off value", "edited at switch-on",
                    "D-76 (1): a diff right after switch-on goes from the off-period value");
        }

        long refreshed = waitFor(() -> holds(first, "off zero") && holds(last, "edited at switch-on"));
        assertTrue(holds(first, "off zero"), "D-76 (1): within " + REFRESH_WAIT_MS + " ms the snapshot of " + first
                + " holds the value set while recording was off");
        assertTrue(holds(last, "edited at switch-on"), "SPEC 9: the snapshot of " + last + " holds its saved configuration");
        System.out.println("T-GAP-425 timing: snapshots current after " + refreshed + " ms of polling");

        assertEquals(200, postConfigXml(j, "admin", job(last), withDescription(job(last).getConfigFile().asString(), "edited after refresh")),
                "fixture: the further config.xml POST succeeds");
        List<ChangeRecord> afterSecond = records(ChangeType.CONFIGURE, last);
        assertEquals(before + 2, afterSecond.size(), "SPEC 9: the further change writes one more CONFIGURE record: "
                + describe(afterSecond.subList(before, afterSecond.size())));
        assertDiffShows(afterSecond.get(afterSecond.size() - 1), "edited at switch-on", "edited after refresh",
                "SPEC 9, LIMITATIONS 53: once the snapshot is current, the next change has a normal diff");
        System.out.println("T-GAP-425 timing: whole row " + millis(started, System.nanoTime()) + " ms");
    }

    // ------------------------------------------------------------------ helpers

    private interface Condition {
        boolean holds() throws IOException;
    }

    /** Polls {@code condition} every 100 ms for at most {@link #REFRESH_WAIT_MS}; returns the milliseconds waited. */
    private static long waitFor(Condition condition) throws IOException, InterruptedException {
        long start = System.nanoTime();
        long deadline = System.currentTimeMillis() + REFRESH_WAIT_MS;
        while (!condition.holds() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100); // bounded wait for background work, not for an expiry
        }
        return millis(start, System.nanoTime());
    }

    private boolean allSnapshotsPresent() {
        for (int i = 0; i < ITEMS; i++) {
            if (!Files.isRegularFile(snapshot(name(i)))) {
                return false;
            }
        }
        return true;
    }

    private boolean holds(String name, String description) throws IOException {
        Path file = snapshot(name);
        return Files.isRegularFile(file)
                && Files.readString(file, StandardCharsets.UTF_8).contains("<description>" + description + "</description>");
    }

    private Path snapshot(String name) {
        return j.jenkins.getRootDir().toPath().resolve("batch-control/snapshots/" + name + ".xml");
    }

    private void recording(boolean on) throws Exception {
        cfg.setChangeControlEnabled(on);
        cfg.save();
        assertEquals(on, BatchControlGlobalConfiguration.get().isChangeControlEnabled(), "fixture: change control switched");
    }

    private FreeStyleProject job(String name) {
        FreeStyleProject job = j.jenkins.getItemByFullName(name, FreeStyleProject.class);
        assertNotNull(job, "fixture: " + name + " exists");
        return job;
    }

    private static String name(int i) {
        return String.format("j%04d", i);
    }

    private static long millis(long fromNanos, long toNanos) {
        return (toNanos - fromNanos) / 1_000_000;
    }
}
