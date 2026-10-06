package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.cloudbees.hudson.plugins.folder.relocate.RelocationAction;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.security.Permission;
import hudson.triggers.TimerTrigger;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ActivationFixtures.assertBlocked;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.decideActivation;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.isActivated;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.recordsFor;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.submitActivationOk;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.activate;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage lane 1, scenario L1-04 (F), the cases that need no restart: activation state fails
 * closed after a disk fault. Matrix rows T-GAP-115 .. T-GAP-118 (note 276); the restart cases are
 * {@link ActivationRestartGapTest}.
 *
 * <p>Basis: SPEC 6a "activation state is truthful and fails closed: a job re-created under a
 * deleted job's name, a job whose state file could not be deleted ... starts not activated; an
 * approved HOLD or a deletion is never undone by a stale cached value", "An approved hold marks
 * the job not activated and writes a {@code HELD} change record", and the D-59a exception "a job
 * moved by a user without Overall/Administer starts over like a newly created job — it is no
 * longer activated and gets the D-34 lock ..., recorded as a {@code HELD} change record naming the
 * move"; LIMITATIONS 39 "The state fails closed"; ARCHITECTURE 5 (activation store
 * {@code activations/<encoded job full name>.xml}; for a top-level item the encoded name is the
 * plain name). Fault injection: files deleted or written by hand in the store directory.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-59a, docs/LIMITATIONS.md and
 * docs/ARCHITECTURE.md section 5 only (no src/main knowledge).
 */
@WithJenkins
public class ActivationFaultGapTest {

    private JenkinsRule j;
    private BatchControlMatrixAuthorizationStrategy strategy;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "a1", "a2"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        strategy.add(RelocationAction.RELOCATE, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.REQUEST, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a2"));
        j.jenkins.setAuthorizationStrategy(strategy);

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1", "a2"));
        cfg.save();
    }

    /**
     * T-GAP-115 (L1-04 case 4; SPEC 6a "An approved hold marks the job not activated and writes a
     * HELD change record", "never undone by a stale cached value"): H is activated and has a
     * pending HOLD request; H's activation state file is deleted on disk before the approval. The
     * approval still records H as HELD (one HELD record), writes a state file for H again, and H
     * is not activated: its timer is refused.
     */
    @Test
    public void t_gap_115_holdApprovedAfterTheStateFileVanished() throws Exception {
        FreeStyleProject jobH = cleared(j.createFreeStyleProject("gap-hold-h"));
        activate(jobH, "u1", "a1");
        Path stateH = stateFileOf("gap-hold-h");
        String hold = submitActivationOk(j, "u1", jobH, "HOLD", "vendor outage", "a2");

        Files.delete(stateH);
        assertFalse(Files.exists(stateH), "premise: H's state file is gone from disk");

        assertSuccess(decideActivation(j, "a2", hold, "approve", "hold it"), "a2's approval of the hold");
        assertEquals(RequestStatus.APPROVED, ActivationService.get().load(hold).getStatus());
        assertEquals(1, recordsFor(ChangeType.HELD, "gap-hold-h").size(), "the approval records H as HELD");
        assertTrue(Files.isRegularFile(stateH), "the approval writes H's state file again: " + stateFiles());
        assertFalse(isActivated(jobH), "an approved hold marks the job not activated");
        assertNull(jobH.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()), "a held job's timer is refused");
        assertBlocked(j, jobH, 1, 0);
    }

    /**
     * T-GAP-116 (L1-04 case 5; SPEC 6a exception D-59a; SPEC 8 move rule D-59): P is activated in
     * folder {@code gap-src} and its state file is deleted on disk. u1 (no Overall/Administer,
     * native Move, Delete on {@code gap-src} and Create on {@code gap-dst}) moves P with both
     * controls on. P is then HELD: not activated, the D-34 lock set, and one HELD record naming
     * the move and u1.
     */
    @Test
    public void t_gap_116_nonAdminMoveAfterTheStateFileVanishedHoldsTheJob() throws Exception {
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.save();
        Folder src = j.jenkins.createProject(Folder.class, "gap-src");
        Folder dst = j.jenkins.createProject(Folder.class, "gap-dst");
        folderPermission(src, "u1", Item.DELETE);
        folderPermission(dst, "u1", Item.CREATE);
        FreeStyleProject jobP = src.createProject(FreeStyleProject.class, "p");
        BatchControlJobProperty open = new BatchControlJobProperty(false);
        open.setBlockTimer(false);
        open.setBlockUpstream(false);
        setBatchControl(jobP, open);
        activate(jobP, "u1", "a1");
        Path stateP = stateFileOf("gap-src/p");
        Files.delete(stateP);
        assertFalse(Files.exists(stateP), "premise: P's state file is gone from disk");
        assertTrue(recordsFor(ChangeType.HELD, "gap-dst/p").isEmpty(), "premise: no HELD record yet");

        assertSuccess(ApproverFormFixtures.post(j, "u1", jobP.getUrl() + "move/move",
                List.of(new NameValuePair("destination", "/gap-dst"))), "u1's move (allowed by D-59)");
        Item moved = j.jenkins.getItemByFullName("gap-dst/p");
        assertTrue(moved instanceof FreeStyleProject, "premise: the job moved to gap-dst");
        FreeStyleProject movedJob = (FreeStyleProject) moved;
        assertFalse(isActivated(movedJob), "D-59a: the moved job is no longer activated");
        BatchControlJobProperty lock = movedJob.getProperty(BatchControlJobProperty.class);
        assertNotNull(lock, "D-59a: the moved job carries the Batch Control property");
        assertTrue(lock.isApprovalRequired() && lock.isBlockTimer() && lock.isBlockUpstream(), "D-59a: the D-34 lock is set");
        List<ChangeRecord> held = ApproverFormFixtures.records(ChangeType.HELD);
        List<ChangeRecord> forMove = held.stream().filter(rec -> {
            String text = (rec.getTarget() + " " + rec.getDetail()).toLowerCase(Locale.ROOT);
            return (text.contains("gap-dst/p") || text.contains("gap-src/p")) && text.contains("mov");
        }).collect(Collectors.toList());
        assertEquals(1, forMove.size(), "one HELD record naming the move: " + describe(held));
        assertEquals("u1", forMove.get(0).getUser(), "the HELD record names the mover");
    }

    /**
     * T-GAP-117 (L1-04 case 6; SPEC 6a "a job re-created under a deleted job's name ... starts not
     * activated"; ARCHITECTURE 5 activation store): a state file for the name {@code gap-q} is
     * written by hand into {@code activations/} (a copy of an activated job's file, with that
     * job's name replaced by {@code gap-q} wherever it appears). Creating a folder named
     * {@code gap-q} removes that file.
     */
    @Test
    public void t_gap_117_folderCreatedOverAStaleStateFileRemovesIt() throws Exception {
        Path stale = plantStateFile("gap-q");
        Folder folder = j.jenkins.createProject(Folder.class, "gap-q");
        assertNotNull(folder);
        assertFalse(Files.exists(stale), "creating an item at gap-q must remove the stale state file " + stale
                + "; activations/ holds " + stateFiles());
    }

    /**
     * T-GAP-118 (L1-04 case 6, twin for a job; SPEC 6a fails closed): the same hand-written state
     * file for {@code gap-q-job}, then a job created under that name with its switches cleared:
     * the job is not activated and its timer is refused.
     */
    @Test
    public void t_gap_118_jobCreatedOverAStaleStateFileStartsNotActivated() throws Exception {
        plantStateFile("gap-q-job");
        FreeStyleProject job = cleared(j.createFreeStyleProject("gap-q-job"));
        assertFalse(isActivated(job), "a job created where a stale state file lay starts not activated");
        assertNull(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()), "its timer is refused");
        assertBlocked(j, job, 1, 0);
    }

    // ------------------------------------------------------------------ helpers

    /** Copies an activated job's state file to {@code activations/<name>.xml}; returns the copy. */
    private Path plantStateFile(String name) throws Exception {
        String sourceName = "gap-source-" + name;
        FreeStyleProject source = cleared(j.createFreeStyleProject(sourceName));
        activate(source, "u1", "a1");
        Path original = stateFileOf(sourceName);
        String xml = Files.readString(original, StandardCharsets.UTF_8).replace(sourceName, name);
        assertTrue(xml.toLowerCase(Locale.ROOT).contains("activated"), "fixture: the copied state names its activation: " + xml);
        Path copy = activationsDir().resolve(name + ".xml");
        Files.writeString(copy, xml, StandardCharsets.UTF_8);
        assertTrue(Files.isRegularFile(copy), "fixture: the stale state file is planted at " + copy);
        return copy;
    }

    /** {@code activations/<encoded full name>.xml} (ARCHITECTURE 5: {@code /} is {@code %2F}), which must exist. */
    private Path stateFileOf(String fullName) throws Exception {
        for (String name : stateFiles()) {
            if (java.net.URLDecoder.decode(name, StandardCharsets.UTF_8).equals(fullName + ".xml")) {
                return activationsDir().resolve(name);
            }
        }
        throw new AssertionError("fixture: the activation of " + fullName + " must be stored as activations/<encoded name>.xml"
                + " (ARCHITECTURE 5); activations/ holds " + stateFiles());
    }

    private static FreeStyleProject cleared(FreeStyleProject job) throws Exception {
        BatchControlJobProperty property = new BatchControlJobProperty(true);
        property.setBlockTimer(false);
        property.setBlockUpstream(false);
        setBatchControl(job, property);
        return job;
    }

    private Path activationsDir() {
        return j.jenkins.getRootDir().toPath().resolve("batch-control").resolve("activations");
    }

    private Set<String> stateFiles() throws Exception {
        Set<String> out = new TreeSet<>();
        if (Files.isDirectory(activationsDir())) {
            try (Stream<Path> files = Files.list(activationsDir())) {
                files.filter(Files::isRegularFile).map(p -> p.getFileName().toString())
                        .filter(n -> n.endsWith(".xml")).forEach(out::add);
            }
        }
        return out;
    }

    private static Set<String> added(Set<String> before, Set<String> now) {
        Set<String> out = new TreeSet<>(now);
        out.removeAll(before);
        return out;
    }

    private static String describe(List<ChangeRecord> recs) {
        return recs.stream().map(r -> r.getType() + " user=" + r.getUser() + " target=" + r.getTarget() + " detail=" + r.getDetail())
                .collect(Collectors.joining("; "));
    }

    private void folderPermission(Folder folder, String userId, Permission permission) throws Exception {
        com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty property =
                folder.getProperties().get(com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty.class);
        if (property == null) {
            property = new com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty(
                    new HashMap<Permission, Set<String>>());
            property.add(permission, PermissionEntry.user(userId));
            folder.addProperty(property);
        } else {
            property.add(permission, PermissionEntry.user(userId));
            folder.save();
        }
    }
}
