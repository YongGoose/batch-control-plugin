package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.FreeStyleProject;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.store.PathCodecUniquenessTest.FOLDER_A;
import static io.jenkins.plugins.batchcontrol.store.PathCodecUniquenessTest.FOLDER_B;
import static io.jenkins.plugins.batchcontrol.store.PathCodecUniquenessTest.VICTIM;
import static io.jenkins.plugins.batchcontrol.store.PathCodecUniquenessTest.VICTIM_LEAF;
import static io.jenkins.plugins.batchcontrol.store.PathCodecUniquenessTest.attackerFor;
import static io.jenkins.plugins.batchcontrol.store.PathCodecUniquenessTest.legacyShortForm;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 4, "the file name derived from an item's full name is unique ... A file written by an
 * earlier version under the old shortened form is still found" (#25). Matrix rows T-04-06,
 * T-04-07 and T-04-15 (the unit half is T-04-05 in {@code store.PathCodecUniquenessTest}).
 *
 * <p>Observed through the CONFIGURE records only (SPEC 9: a CONFIGURE diff is computed against
 * the item's previous snapshot), never through which file the store chose. Recording is active
 * because run control is on (SPEC 9: either switch activates recording); change control stays
 * off so deleting the attacker's job is not vetoed.
 *
 * Written from docs/SPEC.md, docs/ARCHITECTURE.md section 5, issue #25 and docs/TEST-MATRIX.md
 * only (no src/main knowledge).
 */
@WithJenkins
public class StoreFileNameUniquenessTest {

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-04-06 (#25): the attacker's job (name = decoded 180-char prefix of the victim's encoding +
     * '-' + sha256(victim)) and the long-named victim job keep separate baselines. The attacker's
     * CONFIGURE diff never shows the victim's configuration, the victim's diffs are always against
     * its own previous configuration, and deleting the attacker's job leaves the victim's
     * baseline in place.
     */
    @Test
    public void t_04_06_craftedLongNameDoesNotShareTheVictimsSnapshot() throws Exception {
        Folder a = j.jenkins.createProject(Folder.class, FOLDER_A);
        Folder b = a.createProject(Folder.class, FOLDER_B);
        FreeStyleProject victim = b.createProject(FreeStyleProject.class, VICTIM_LEAF);
        assertEquals(VICTIM, victim.getFullName(), "fixture: the victim's full name");
        victim.setDescription("victim-secret-1");

        String attackerFullName = attackerFor(VICTIM);
        String attackerLeaf = attackerFullName.substring(attackerFullName.lastIndexOf('/') + 1);
        FreeStyleProject attacker = b.createProject(FreeStyleProject.class, attackerLeaf);
        assertEquals(attackerFullName, attacker.getFullName(), "fixture: the attacker's job sits in the victim's folder under the crafted name");

        // victim saves while the attacker's job exists: its diff must be against its own config
        victim.setDescription("victim-secret-2");
        String victimDiff = lastDiff(VICTIM);
        assertRemoved(victimDiff, "victim-secret-1", "the victim's diff must be against the victim's own previous configuration");
        assertFalse(victimDiff.contains(attackerLeaf) || victimDiff.contains("attacker-"), "the victim's diff must not be computed against the attacker's configuration:\n" + victimDiff);

        // attacker saves: its diff must not reveal the victim's configuration
        // (two saves, so at least one CONFIGURE record exists whatever the first save after
        // creation records; every one of them is checked)
        attacker.setDescription("attacker-1");
        attacker.setDescription("attacker-2");
        List<ChangeRecord> attackerRecords = configures(attackerFullName);
        assertFalse(attackerRecords.isEmpty(), "a CONFIGURE record must exist for the attacker's job");
        for (ChangeRecord rec : attackerRecords) {
            String attackerDiff = String.valueOf(rec.getDiff());
            assertFalse(attackerDiff.contains("victim-secret"), "the attacker's CONFIGURE diff must not reveal the victim's configuration (#25):\n"
                    + attackerDiff);
        }

        // deleting the attacker's job must not delete the victim's baseline
        attacker.delete();
        victim.setDescription("victim-secret-3");
        String afterDelete = lastDiff(VICTIM);
        assertRemoved(afterDelete, "victim-secret-2", "after the attacker's job is deleted the victim's diff must still be against its own"
                        + " previous configuration (baseline kept)");
        assertFalse(afterDelete.contains("attacker-2"), "the victim's diff must not mention the attacker's configuration:\n" + afterDelete);
    }

    /**
     * T-04-07 (#25, contract reversed by D-43, note 80): a file in the pre-release shortened form
     * (180-char encoded prefix + '-' + sha256(full name), issue #25) is <em>not</em> read. The
     * next CONFIGURE of the long-named job is not diffed against it, and the file is left where
     * and as it is (not moved into the job's current name, not rewritten).
     */
    @Test
    public void t_04_07_snapshotInThePreReleaseShortenedFormIsIgnored() throws Exception {
        Folder a = j.jenkins.createProject(Folder.class, FOLDER_A);
        Folder b = a.createProject(Folder.class, FOLDER_B);
        FreeStyleProject victim = b.createProject(FreeStyleProject.class, VICTIM_LEAF);
        victim.setDescription("current-baseline");

        // Replace whatever the current version stored by the file an earlier version would have
        // left: snapshots/<old shortened form>.xml holding a distinguishable configuration.
        Path snapshots = j.jenkins.getRootDir().toPath().resolve("batch-control").resolve("snapshots");
        assertTrue(Files.isDirectory(snapshots), "fixture: the store keeps a snapshots/ directory");
        try (Stream<Path> files = Files.list(snapshots)) {
            for (Path file : files.collect(Collectors.toList())) {
                Files.delete(file);
            }
        }
        String legacyConfig = victim.getConfigFile().asString()
                .replace("<description>current-baseline</description>",
                        "<description>legacy-baseline-marker</description>");
        assertTrue(legacyConfig.contains("legacy-baseline-marker"), "fixture: the legacy configuration differs");
        Path legacy = snapshots.resolve(legacyShortForm(VICTIM) + ".xml");
        assertTrue(legacy.getFileName().toString().length() <= 255, "fixture: the legacy name is a valid file name");
        Files.writeString(legacy, legacyConfig, StandardCharsets.UTF_8);

        victim.setDescription("after-upgrade");
        for (ChangeRecord rec : configures(VICTIM)) {
            assertFalse(String.valueOf(rec.getDiff()).contains("legacy-baseline-marker"), "a file in the pre-release shortened form must not be used as the job's baseline"
                    + " (D-43):\n" + rec.getDiff());
        }
        assertTrue(Files.exists(legacy), "the pre-release file must not be moved or deleted (D-43)");
        assertEquals(legacyConfig, Files.readString(legacy, StandardCharsets.UTF_8), "the pre-release file must be left unchanged (D-43)");

        // guard: the job keeps its own baseline under the current form, so the next change is
        // diffed against the configuration it just saved
        victim.setDescription("after-upgrade-2");
        assertRemoved(lastDiff(VICTIM), "after-upgrade", "the job's next change must be diffed against its own current-form baseline");
    }

    /**
     * T-04-15 (#25 migration, commit 08f342c): a real defect, distinct from T-04-06/07. Saving the
     * snapshot of the long-named job V deleted the file at V's <em>legacy</em> shortened path (the
     * old {@code -} scheme) even though a live, unrelated job W whose <em>plain</em> full name
     * equals that exact string keeps its real, current baseline snapshot there. That destroyed W's
     * baseline, so W's next configuration change wrote no CONFIGURE record at all, breaking SPEC
     * item 9's guarantee that every configuration change is recorded.
     *
     * <p>W is given a baseline and one ordinary change before V exists at all (a control proving
     * normal recording). V is then created and saved twice (the trigger). W's configuration is
     * changed again: exactly one new CONFIGURE record must exist, carrying a non-empty diff against
     * W's own previous value, not an empty or missing baseline. A closing guard deletes V and
     * repeats the same check once more.
     */
    @Test
    public void t_04_15_savingTheLongNamedJobDoesNotDestroyTheCollidingJobsBaseline() throws Exception {
        Folder a = j.jenkins.createProject(Folder.class, FOLDER_A);
        Folder b = a.createProject(Folder.class, FOLDER_B);

        String wFullName = attackerFor(VICTIM);
        String wLeaf = wFullName.substring(wFullName.lastIndexOf('/') + 1);
        FreeStyleProject w = b.createProject(FreeStyleProject.class, wLeaf);
        assertEquals(wFullName, w.getFullName(), "fixture: W's full name");
        assertEquals(legacyShortForm(VICTIM), wFullName.replace("/", "%2F"),
                "fixture: W's plain encoding equals V's legacy shortened form (#25)");

        // Give W a baseline, with no V in the picture yet.
        w.setDescription("w-baseline");

        // Control: an ordinary change to W, still with no V present, is recorded exactly once
        // against its own baseline. This proves the harness records normally on its own, so a
        // later failure to record is attributable to V, not to the fixture.
        int beforeControl = configures(wFullName).size();
        w.setDescription("w-changed-0");
        List<ChangeRecord> controlRecords = configures(wFullName);
        assertEquals(beforeControl + 1, controlRecords.size(),
                "control: with no V present, W's change must be recorded exactly once");
        String controlDiff = controlRecords.get(controlRecords.size() - 1).getDiff();
        assertNotNull(controlDiff, "control: the recorded change must carry a diff");
        assertRemoved(controlDiff, "w-baseline", "control: the diff must be against W's own baseline");
        assertTrue(controlDiff.contains("w-changed-0"), "control: the diff carries the new value:\n" + controlDiff);

        // Create the long-named job V and save it. Its own legacy shortened path (the old '-'
        // scheme) is exactly W's plain full name. Before commit 08f342c, saving V deleted the file
        // at that shared path -- W's own live snapshot -- destroying W's baseline.
        FreeStyleProject v = b.createProject(FreeStyleProject.class, VICTIM_LEAF);
        assertEquals(VICTIM, v.getFullName(), "fixture: V's full name");
        v.setDescription("v-1");
        v.setDescription("v-2");

        // Regression: W's next configuration change must still be recorded exactly once, with a
        // non-empty diff against the configuration it had before V was ever saved.
        int beforeRegression = configures(wFullName).size();
        w.setDescription("w-changed-1");
        List<ChangeRecord> regressionRecords = configures(wFullName);
        assertEquals(beforeRegression + 1, regressionRecords.size(),
                "saving V must not destroy W's baseline: W's own configuration change must still be"
                        + " recorded exactly once (#25, commit 08f342c)");
        String regressionDiff = regressionRecords.get(regressionRecords.size() - 1).getDiff();
        assertNotNull(regressionDiff, "the CONFIGURE record must carry a diff");
        assertFalse(regressionDiff.isBlank(), "the diff must not be empty (the baseline must not have been lost)");
        assertRemoved(regressionDiff, "w-changed-0",
                "the diff must be against W's real previous configuration, not an empty or missing baseline");
        assertTrue(regressionDiff.contains("w-changed-1"), "the diff carries the new value:\n" + regressionDiff);

        // Guard: deleting V must not disturb W's baseline either.
        v.delete();
        int beforeAfterDelete = configures(wFullName).size();
        w.setDescription("w-changed-2");
        List<ChangeRecord> afterDeleteRecords = configures(wFullName);
        assertEquals(beforeAfterDelete + 1, afterDeleteRecords.size(),
                "deleting V must not affect W's baseline or recording");
        String afterDeleteDiff = afterDeleteRecords.get(afterDeleteRecords.size() - 1).getDiff();
        assertNotNull(afterDeleteDiff, "the CONFIGURE record must carry a diff");
        assertRemoved(afterDeleteDiff, "w-changed-1",
                "after deleting V, W's diff is still against its own previous configuration");
    }

    /** The diff of the newest CONFIGURE record of {@code target} in the current month. */
    private static List<ChangeRecord> configures(String target) {
        return FileStore.get().listChangeRecords(YearMonth.now()).stream()
                .filter(rec -> rec.getType() == ChangeType.CONFIGURE)
                .filter(rec -> target.equals(rec.getTarget()))
                .collect(Collectors.toList());
    }

    private static String lastDiff(String target) {
        List<ChangeRecord> records = configures(target);
        assertFalse(records.isEmpty(), "a CONFIGURE record must exist for " + target);
        String diff = records.get(records.size() - 1).getDiff();
        assertNotNull(diff, "a CONFIGURE record must carry a diff");
        return diff;
    }

    private static void assertRemoved(String diff, String oldValue, String message) {
        boolean removed = Arrays.stream(diff.split("\n"))
                .anyMatch(line -> line.startsWith("-") && !line.startsWith("---") && line.contains(oldValue));
        assertTrue(removed, message + " (expected a removed line with \"" + oldValue + "\"):\n" + diff);
    }
}
