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
 * earlier version under the old shortened form is still found" (#25). Matrix rows T-04-06 and
 * T-04-07 (the unit half is T-04-05 in {@code store.PathCodecUniquenessTest}).
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
        attacker.setDescription("attacker-2");
        String attackerDiff = lastDiff(attackerFullName);
        assertFalse(attackerDiff.contains("victim-secret"), "the attacker's CONFIGURE diff must not reveal the victim's configuration (#25):\n"
                + attackerDiff);

        // deleting the attacker's job must not delete the victim's baseline
        attacker.delete();
        victim.setDescription("victim-secret-3");
        String afterDelete = lastDiff(VICTIM);
        assertRemoved(afterDelete, "victim-secret-2", "after the attacker's job is deleted the victim's diff must still be against its own"
                        + " previous configuration (baseline kept)");
        assertFalse(afterDelete.contains("attacker-2"), "the victim's diff must not mention the attacker's configuration:\n" + afterDelete);
    }

    /**
     * T-04-07 (#25): a snapshot written by an earlier version under the old shortened form
     * (180-char encoded prefix + '-' + sha256(full name), issue #25) is still found: the next
     * CONFIGURE of that job is diffed against it.
     */
    @Test
    public void t_04_07_snapshotUnderTheOldShortenedFormIsStillFound() throws Exception {
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
        String diff = lastDiff(VICTIM);
        assertRemoved(diff, "legacy-baseline-marker", "the CONFIGURE diff must be computed against the snapshot stored under the old"
                        + " shortened form (#25)");
        assertTrue(diff.contains("after-upgrade"), "guard: the diff carries the new value:\n" + diff);
    }

    /** The diff of the newest CONFIGURE record of {@code target} in the current month. */
    private static String lastDiff(String target) {
        List<ChangeRecord> records = FileStore.get().listChangeRecords(YearMonth.now()).stream()
                .filter(rec -> rec.getType() == ChangeType.CONFIGURE)
                .filter(rec -> target.equals(rec.getTarget()))
                .collect(Collectors.toList());
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
