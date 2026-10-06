package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.Failure;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.model.listeners.ItemListener;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.htmlunit.WebResponse;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DELETE change records of the items that are deleted together with their folder name the user
 * who deleted the folder. SPEC 6 (usability): "recorded history names who did what"; SPEC 9
 * (paraphrased from the Korean): creations, changes, deletions, renames and moves are recorded
 * automatically whatever the path, with who changed what and when, and recording is active while
 * either switch is on; SPEC 3 data model: ChangeRecord {@code target, user, at}. Matrix rows
 * T-09-25 .. T-09-29 (note 272) and T-09-30 (security-39 S-39-05: a SYSTEM deletion after an aborted
 * user deletion on the same thread; note 274).
 *
 * <p>Why the children are the interesting case (DECISIONS D-71): core's
 * {@code AbstractItem.delete()} deletes every child of a folder as SYSTEM, without checking it. A
 * record that took the user from the authentication current when the child's deletion fires
 * would therefore say {@code SYSTEM} for every item below the folder, while the folder's own
 * record says who deleted it. These rows require every record of one deletion to name the one
 * user who made it, and the twins require a deletion that SYSTEM really made to keep saying
 * {@code SYSTEM}, also after a user's deletion on the same thread.
 *
 * <p>Records are read through the public history API the other ChangeRecord tests use
 * ({@code FileStore#listChangeRecords} of the current month, via
 * {@link ApproverFormFixtures#records}), and for the HTTP rows also through the history export
 * {@code batch-control/history/changes.csv}.
 *
 * <p>Users: admin (Overall/Administer), d2 (Overall/Read; Item/Read on {@code f2} and below;
 * Item/Delete on the folder {@code f2} only), a1 (the configured approver, never acts).
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md (D-71), the Given/When/Then of
 * docs/reports/security-39.md and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class ChangeRecordFolderDeleteAttributionTest {

    private static final String SYSTEM = "SYSTEM";

    private JenkinsRule j;
    private BatchControlGlobalConfiguration cfg;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ).everywhere().to("d2", "a1")
                .grant(Item.READ).onPaths("f2(/.*)?").to("d2")
                // Delete on the folder itself only: core deletes the children as SYSTEM (D-71).
                .grant(Item.DELETE).onPaths("f2").to("d2"));

        cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(false);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-09-25 (P0): run control on, change control off. The administrator deletes the folder
     * {@code fold} over HTTP ({@code doDelete} with a crumb); it holds the job {@code fold/child}
     * and the folder {@code fold/sub} with the job {@code fold/sub/deep}. Each of the four items
     * has exactly one DELETE record and it names admin, never SYSTEM; the history export
     * {@code changes.csv} carries the same four rows with admin.
     */
    @Test
    public void t_09_25_adminFolderDeleteOverHttpNamesAdminOnEveryRecord() throws Exception {
        assertTrue(cfg.isRunControlEnabled() && !cfg.isChangeControlEnabled(),
                "premise: run control on, change control off");
        Instant before = Instant.now(BatchClock.clock());
        Folder fold = nestedFolder("fold");

        assertSuccess(ApproverFormFixtures.post(j, "admin", fold.getUrl() + "doDelete", List.of()),
                "fixture: the administrator deletes the folder fold over HTTP");

        String[] targets = {"fold", "fold/child", "fold/sub", "fold/sub/deep"};
        assertGone(targets);
        for (String target : targets) {
            assertOneDeleteRecordBy("admin", target, before,
                    "the administrator's HTTP deletion of fold (run control only)");
        }
        assertCsvDeleteRowsBy("admin", targets);
    }

    /**
     * T-09-26 (P0): as T-09-25 with change control on as well (Batch Control matrix strategy, the
     * administrator the only principal). The administrator deletes {@code cfold} (job
     * {@code cfold/child}, folder {@code cfold/sub} with job {@code cfold/sub/deep}) over HTTP:
     * exactly one DELETE record per item, each naming admin; {@code changes.csv} agrees.
     */
    @Test
    public void t_09_26_adminFolderDeleteOverHttpNamesAdminWithChangeControlOn() throws Exception {
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        j.jenkins.setAuthorizationStrategy(strategy);
        cfg.setChangeControlEnabled(true);
        cfg.save();
        assertTrue(cfg.isRunControlEnabled() && cfg.isChangeControlEnabled(),
                "premise: run control and change control on");
        Instant before = Instant.now(BatchClock.clock());
        Folder fold = nestedFolder("cfold");

        assertSuccess(ApproverFormFixtures.post(j, "admin", fold.getUrl() + "doDelete", List.of()),
                "fixture: the administrator deletes the folder cfold over HTTP");

        String[] targets = {"cfold", "cfold/child", "cfold/sub", "cfold/sub/deep"};
        assertGone(targets);
        for (String target : targets) {
            assertOneDeleteRecordBy("admin", target, before,
                    "the administrator's HTTP deletion of cfold (change control on)");
        }
        assertCsvDeleteRowsBy("admin", targets);
    }

    /**
     * T-09-27 (P0): run control on, change control off. d2, who is not an administrator and
     * holds Item/Delete on the folder {@code f2} only, deletes {@code f2} (job {@code f2/c2})
     * over HTTP. Both DELETE records name d2, neither SYSTEM nor admin; {@code changes.csv}
     * agrees.
     */
    @Test
    public void t_09_27_nonAdminFolderDeleteOverHttpNamesThatUser() throws Exception {
        Instant before = Instant.now(BatchClock.clock());
        Folder f2;
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // fixture: the items exist before the row
            f2 = j.jenkins.createProject(Folder.class, "f2");
            f2.createProject(FreeStyleProject.class, "c2");
        }
        try (ACLContext ignored = as("d2")) {
            assertTrue(f2.hasPermission(Item.DELETE), "premise: d2 holds Item/Delete on f2");
            assertFalse(Jenkins.get().hasPermission(Jenkins.ADMINISTER), "premise: d2 is not an administrator");
        }

        assertSuccess(ApproverFormFixtures.post(j, "d2", f2.getUrl() + "doDelete", List.of()),
                "fixture: d2 deletes the folder f2 over HTTP");

        String[] targets = {"f2", "f2/c2"};
        assertGone(targets);
        for (String target : targets) {
            assertOneDeleteRecordBy("d2", target, before, "d2's HTTP deletion of f2");
        }
        assertCsvDeleteRowsBy("d2", targets);
    }

    /**
     * T-09-28 (P1, twin of T-09-25): a deletion that SYSTEM really makes stays SYSTEM. The folder
     * {@code sysf} (job {@code sysf/child}, folder {@code sysf/sub} with job
     * {@code sysf/sub/deep}) is deleted in code under {@code ACL.as2(ACL.SYSTEM2)}: every DELETE
     * record, the child's included, names SYSTEM.
     */
    @Test
    public void t_09_28_folderDeletedAsSystemIsRecordedAsSystem() throws Exception {
        Instant before = Instant.now(BatchClock.clock());
        Folder sysf = nestedFolder("sysf");

        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            sysf.delete();
        }

        String[] targets = {"sysf", "sysf/child", "sysf/sub", "sysf/sub/deep"};
        assertGone(targets);
        for (String target : targets) {
            assertOneDeleteRecordBy(SYSTEM, target, before, "the SYSTEM deletion of sysf");
        }
    }

    /**
     * T-09-29 (P0): no leaking of an earlier user. On the test thread the administrator first
     * deletes the folder {@code lk1} (job {@code lk1/child}, folder {@code lk1/sub} with job
     * {@code lk1/sub/deep}) in code under {@code ACL.as2(admin)}: all four records name admin.
     * Afterwards, on the same thread, the folder {@code lk2} (same layout) and the top-level job
     * {@code lk3} are deleted under {@code ACL.as2(ACL.SYSTEM2)}: all their records name SYSTEM,
     * none admin.
     */
    @Test
    public void t_09_29_systemDeletionAfterAUserDeletionOnTheSameThreadStaysSystem() throws Exception {
        Instant before = Instant.now(BatchClock.clock());
        Folder lk1 = nestedFolder("lk1");
        Folder lk2 = nestedFolder("lk2");
        FreeStyleProject lk3;
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            lk3 = j.jenkins.createProject(FreeStyleProject.class, "lk3");
        }

        try (ACLContext ignored = as("admin")) {
            lk1.delete();
        }
        String[] byAdmin = {"lk1", "lk1/child", "lk1/sub", "lk1/sub/deep"};
        assertGone(byAdmin);
        for (String target : byAdmin) {
            assertOneDeleteRecordBy("admin", target, before,
                    "premise: the administrator's deletion of lk1 in code");
        }

        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            lk2.delete();
            lk3.delete();
        }
        String[] bySystem = {"lk2", "lk2/child", "lk2/sub", "lk2/sub/deep", "lk3"};
        assertGone(bySystem);
        for (String target : bySystem) {
            assertOneDeleteRecordBy(SYSTEM, target, before,
                    "the SYSTEM deletion after admin's deletion on the same thread (no leak of admin)");
        }
    }

    /** Vetoes the deletion of the item named {@link #vetoed} (T-09-30); null vetoes nothing. */
    @TestExtension("t_09_30_systemDeletionAfterAnAbortedUserDeletionStaysSystem")
    public static class ChildDeletionVeto extends ItemListener {
        static volatile String vetoed;

        @Override
        public void onCheckDelete(Item item) throws Failure {
            if (item.getFullName().equals(vetoed)) {
                throw new Failure("test veto: " + item.getFullName() + " may not be deleted now");
            }
        }
    }

    /**
     * T-09-30 (security-39 S-39-05; SPEC 6 usability "recorded history names who did what", SPEC 9;
     * note 274): the folder {@code gf} holds the jobs {@code gf/c1} and {@code gf/c2}; a test item
     * listener vetoes the deletion of {@code gf/c2}. On the test thread the administrator deletes
     * {@code gf} in code under {@code ACL.as2(admin)}: {@code gf/c1} is deleted (one DELETE record
     * naming admin) and the deletion aborts ({@code gf} and {@code gf/c2} remain). The veto is lifted
     * and, on the same thread, {@code gf} is deleted under {@code ACL.as2(ACL.SYSTEM2)}: the DELETE
     * records of {@code gf/c2} and {@code gf} each name SYSTEM, not admin.
     */
    @Test
    public void t_09_30_systemDeletionAfterAnAbortedUserDeletionStaysSystem() throws Exception {
        Instant before = Instant.now(BatchClock.clock());
        Folder gf;
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // fixture: the items exist before the row
            gf = j.jenkins.createProject(Folder.class, "gf");
            gf.createProject(FreeStyleProject.class, "c1");
            gf.createProject(FreeStyleProject.class, "c2");
        }
        ChildDeletionVeto.vetoed = "gf/c2";
        try {
            boolean aborted = false;
            try (ACLContext ignored = as("admin")) {
                gf.delete();
            } catch (RuntimeException | java.io.IOException expected) {
                aborted = true;
            }
            assertTrue(aborted, "premise: the administrator's deletion of gf aborts at the vetoed gf/c2");
        } finally {
            ChildDeletionVeto.vetoed = null;
        }
        assertGone("gf/c1");
        assertTrue(j.jenkins.getItemByFullName("gf") != null && j.jenkins.getItemByFullName("gf/c2") != null,
                "premise: gf and gf/c2 remain after the aborted deletion");
        assertOneDeleteRecordBy("admin", "gf/c1", before, "premise: the administrator's aborted deletion of gf deleted gf/c1");

        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            gf.delete();
        }
        assertGone("gf", "gf/c2");
        assertOneDeleteRecordBy(SYSTEM, "gf/c2", before,
                "S-39-05: the SYSTEM deletion of gf after the administrator's aborted deletion on the same thread");
        assertOneDeleteRecordBy(SYSTEM, "gf", before,
                "S-39-05: the SYSTEM deletion of gf after the administrator's aborted deletion on the same thread");
    }

    // ---------------------------------------------------------------- helpers

    private ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }

    /** {@code <name>} holding the job {@code child} and the folder {@code sub} with the job {@code deep}. */
    private Folder nestedFolder(String name) throws Exception {
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // fixture: the items exist before the row
            Folder folder = j.jenkins.createProject(Folder.class, name);
            folder.createProject(FreeStyleProject.class, "child");
            Folder sub = folder.createProject(Folder.class, "sub");
            sub.createProject(FreeStyleProject.class, "deep");
            return folder;
        }
    }

    private void assertGone(String... fullNames) {
        for (String fullName : fullNames) {
            assertNull(j.jenkins.getItemByFullName(fullName), "premise: " + fullName + " is deleted");
        }
    }

    /** Exactly one DELETE record for {@code target} since {@code since}, and it names {@code user}. */
    private void assertOneDeleteRecordBy(String user, String target, Instant since, String what) {
        List<ChangeRecord> deletes = ApproverFormFixtures.records(ChangeType.DELETE, since).stream()
                .filter(rec -> target.equals(rec.getTarget()))
                .collect(Collectors.toList());
        assertEquals(1, deletes.size(), what + ": exactly one DELETE record must name " + target
                + ", found " + describe(deletes));
        assertEquals(user, deletes.get(0).getUser(), what + ": the DELETE record of " + target
                + " must name " + user + " (SPEC 6: recorded history names who did what), found "
                + describe(deletes));
    }

    /** {@code changes.csv} (current month) has one DELETE row per target, each naming {@code user}. */
    private void assertCsvDeleteRowsBy(String user, String... targets) throws Exception {
        LocalDate today = LocalDate.now(BatchClock.clock());
        String period = "from=" + today.withDayOfMonth(1) + "&to=" + today.withDayOfMonth(today.lengthOfMonth());
        WebResponse csv = ApproverFormFixtures.get(j, "admin", "batch-control/history/changes.csv?" + period);
        assertEquals(200, csv.getStatusCode(), "the administrator must download changes.csv");
        List<List<String>> rows = parseCsv(csv.getContentAsString());
        for (String target : targets) {
            List<List<String>> deleteRows = rows.stream()
                    .filter(row -> row.contains("DELETE") && row.contains(target))
                    .collect(Collectors.toList());
            assertEquals(1, deleteRows.size(), "changes.csv must export exactly one DELETE row for "
                    + target + ", found " + deleteRows);
            List<String> row = deleteRows.get(0);
            assertTrue(row.contains(user), "the exported DELETE row of " + target + " must name "
                    + user + ": " + row);
            if (!SYSTEM.equals(user)) {
                assertFalse(row.contains(SYSTEM), "the exported DELETE row of " + target
                        + " must not name SYSTEM: " + row);
            }
        }
    }

    /** RFC 4180 rows (quoted cells, doubled quotes); each cell trimmed of surrounding whitespace. */
    private static List<List<String>> parseCsv(String body) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < body.length() && body.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(cell.toString().trim());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < body.length() && body.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString().trim());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString().trim());
            rows.add(row);
        }
        return rows;
    }

    private static String describe(List<ChangeRecord> records) {
        return records.stream()
                .map(rec -> rec.getType() + "/" + rec.getTarget() + "/" + rec.getUser())
                .collect(Collectors.toList())
                .toString();
    }
}
