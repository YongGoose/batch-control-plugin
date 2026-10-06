package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.TopLevelItem;
import hudson.model.User;
import hudson.model.listeners.ItemListener;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage lane 3, scenario L3-20 (the rows that need no restart): a created-item record whose item
 * vanished on disk before a reload, and interleaved renames. Matrix rows T-GAP-375 and T-GAP-376 (note
 * 279); the restart rows of L3-20 are T-GAP-374 in {@link WindowRestartGapTest}.
 *
 * <p>Basis: D-35c; LIMITATIONS 11 (the created-item record "follows its item ... and is dropped when the
 * item is deleted or another item takes its name"; "A window that cannot follow its item for certain ends
 * ... when another item already has the old name again by the time the rename or move is handled, or when
 * another item has just taken the name the window gives (renames whose events interleave lead to the last
 * two). It is revoked ... with the reason 'it could not follow its item' and a GRANT_REVOKE record";
 * "Creating a new item at a window's name ends that window the same way"; the remaining reload gap); SPEC
 * 8 line 170; D-75 (2) (interleaved renames end the window); ARCHITECTURE 4.
 *
 * <p>The interleaving is produced by a test item listener (default ordinal, armed only in its row) that,
 * when job {@code ia} is renamed to {@code ib}, puts another item at the name {@code ia} with core's
 * {@code Jenkins#putItem} (which fires its own creation event inside the rename's event). A test listener
 * of equal ordinal is consulted before Batch Control's (class name order, note 265 of QueueRefusalFixtures),
 * so Batch Control sees the new {@code ia} before it handles the rename.
 *
 * <p>Batch Control matrix strategy, change control on; u1 and u3 hold RequestGrant; a1 approves.
 *
 * <p>Written from docs/SPEC.md item 8, docs/DECISIONS.md D-35c, D-74 and D-75, docs/LIMITATIONS.md item 11
 * and docs/ARCHITECTURE.md section 4 only (no src/main knowledge).
 */
@WithJenkins
public class WindowFollowGapTest {

    static final String FOLLOW_REASON = "it could not follow its item";

    private JenkinsRule j;

    /** When armed, puts another item at the old name {@code ia} while {@code ia} is renamed (once). */
    @TestExtension("t_gap_376_interleavedRenamesEndTheWindowOnBothItems")
    public static final class Interleaver extends ItemListener {
        static volatile boolean armed;
        static volatile boolean fired;

        @Override
        public void onRenamed(Item item, String oldName, String newName) {
            if (armed && "ia".equals(oldName)) {
                armed = false;
                fired = true;
                try {
                    Jenkins.get().putItem(new FreeStyleProject(Jenkins.get(), "ia"));
                } catch (IOException | InterruptedException e) {
                    throw new IllegalStateException("fixture: putItem failed", e);
                }
            }
        }
    }

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        Interleaver.armed = false;
        Interleaver.fired = false;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "u3", "a1"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u3"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        j.jenkins.setAuthorizationStrategy(strategy);
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    @AfterEach
    public void tearDown() {
        Interleaver.armed = false;
    }

    /**
     * T-GAP-375 (L3-20; LIMITATIONS 11 "dropped when ... another item takes its name", SPEC 8 line 170):
     * u3's CREATE window on folder {@code fs}; u3 creates {@code fs/s} and {@code fs/s2} through it
     * (premise: u3 configures both). {@code fs/s}'s directory is deleted on disk and Jenkins reloads its
     * configuration (no deletion event; premise: {@code fs/s} is gone). The administrator renames
     * {@code fs/t} to {@code fs/s}: u3 holds no Configure on it. Guard: u3 still configures {@code fs/s2}.
     */
    @Test
    public void t_gap_375_itemRenamedOntoAVanishedCreatedItemsNameGetsNothing() throws Exception {
        Folder fs = j.jenkins.createProject(Folder.class, "fs");
        fs.createProject(FreeStyleProject.class, "t");
        openWindow("u3", "fs", "CREATE");
        createAs("u3", "fs", "s");
        createAs("u3", "fs", "s2");
        assertTrue(can("u3", j.jenkins.getItemByFullName("fs/s"), Item.CONFIGURE), "premise (D-35c): u3 configures fs/s");
        assertTrue(can("u3", j.jenkins.getItemByFullName("fs/s2"), Item.CONFIGURE), "premise (D-35c): u3 configures fs/s2");
        j.jenkins.save(); // fixture: the security configuration must survive the reload from disk

        deleteTree(j.jenkins.getRootDir().toPath().resolve("jobs/fs/jobs/s"));
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // fixture: the administrator reloads the configuration from disk
            j.jenkins.reload();
        }
        assertNull(j.jenkins.getItemByFullName("fs/s"), "premise: fs/s vanished without a deletion event");
        try (ACLContext ignored = ACL.as2(User.getById("admin", true).impersonate2())) {
            ((FreeStyleProject) j.jenkins.getItemByFullName("fs/t")).renameTo("s");
        }
        Item renamed = j.jenkins.getItemByFullName("fs/s");
        assertNotNull(renamed, "premise: fs/t is now fs/s");
        assertFalse(can("u3", renamed, Item.CONFIGURE), "LIMITATIONS 11, SPEC 8 line 170: the item renamed onto the name gives u3 nothing");
        assertTrue(can("u3", j.jenkins.getItemByFullName("fs/s2"), Item.CONFIGURE), "guard: u3 still configures fs/s2");
    }

    /**
     * T-GAP-376 (L3-20; LIMITATIONS 11, D-75 (2) interleaved renames): u1's CONFIGURE windows on the jobs
     * {@code ia} and {@code ja}. Guard: the administrator renames {@code ja} to {@code jb} with the test
     * listener unarmed: the window follows ({@code jb}, confers). Armed, the administrator renames
     * {@code ia} to {@code ib} while the listener puts another item at {@code ia}: the window on {@code ia}
     * has ended (not active, stored as revoked), exactly one GRANT_REVOKE record identifies it, it confers
     * on neither {@code ib} nor the new {@code ia}, and its stored reason is one LIMITATIONS 11 gives for
     * this case ("it could not follow its item", or that of a creation at the window's name, "its item was
     * deleted"; which of the two is not settled by the documents, note 279; printed).
     */
    @Test
    public void t_gap_376_interleavedRenamesEndTheWindowOnBothItems() throws Exception {
        FreeStyleProject ia = j.createFreeStyleProject("ia");
        FreeStyleProject ja = j.createFreeStyleProject("ja");
        String onIa = openWindow("u1", "ia", "CONFIGURE");
        String onJa = openWindow("u1", "ja", "CONFIGURE");
        try (ACLContext ignored = ACL.as2(User.getById("admin", true).impersonate2())) {
            ja.renameTo("jb");
        }
        WindowStateFixtures.assertActiveOn(j, "u1", onJa, "jb", "guard (D-74): unarmed, the window follows ja to jb");
        assertTrue(can("u1", j.jenkins.getItemByFullName("jb"), Item.CONFIGURE), "guard: the followed window confers on jb");

        Set<String> before = WindowStateFixtures.revokeRecordIds();
        Interleaver.armed = true;
        try (ACLContext ignored = ACL.as2(User.getById("admin", true).impersonate2())) {
            ia.renameTo("ib");
        }
        assertTrue(Interleaver.fired, "premise: the test listener put another item at ia during the rename");
        TopLevelItem newIa = j.jenkins.getItem("ia");
        assertNotNull(newIa, "premise: another item is at ia");
        assertNotNull(j.jenkins.getItemByFullName("ib"), "premise: the job is now ib");

        assertNull(WindowStateFixtures.active(onIa), "D-75 (2): the window that could not follow for certain is not active");
        List<ChangeRecord> revokes = WindowStateFixtures.revokeRecordsSince(before);
        assertEquals(1, revokes.stream().filter(r -> onIa.equals(r.getGrantId()) || String.valueOf(r.getDetail()).contains(onIa)
                || String.valueOf(r.getTarget()).contains(onIa) || "ia".equals(r.getTarget()) || "ib".equals(r.getTarget())).count(),
                "LIMITATIONS 11: exactly one GRANT_REVOKE record for the window: " + WindowStateFixtures.describe(revokes));
        assertFalse(can("u1", j.jenkins.getItemByFullName("ib"), Item.CONFIGURE), "SPEC 8 line 170: the window confers nothing on ib");
        assertFalse(can("u1", newIa, Item.CONFIGURE), "SPEC 8 line 170: the window confers nothing on the new ia");
        Grant stored = (Grant) Jenkins.XSTREAM2.fromXML(Files.readString(
                j.jenkins.getRootDir().toPath().resolve("batch-control/grants/" + onIa + ".xml"), StandardCharsets.UTF_8));
        assertNotNull(stored.getRevokedAt(), "the stored window is revoked");
        String reason = String.valueOf(stored.getRevokedReason()).toLowerCase(Locale.ROOT);
        System.out.println("T-GAP-376 observation: stored reason '" + stored.getRevokedReason() + "', revoked by " + stored.getRevokedBy());
        assertTrue(reason.contains(FOLLOW_REASON) || reason.contains(WindowStateFixtures.DELETED_REASON),
                "LIMITATIONS 11: the stored reason is '" + FOLLOW_REASON + "' or '" + WindowStateFixtures.DELETED_REASON + "', was: "
                        + stored.getRevokedReason());
    }

    // ------------------------------------------------------------------ helpers

    private String openWindow(String userId, String fullName, String action) throws Exception {
        String id = submitGrantOk(j, userId, fullName, List.of(action), 30, "work on " + fullName, null, "a1");
        assertSuccess(decideGrant(j, "a1", id, "approve", "ok"), "fixture: approval by a1");
        return WindowStateFixtures.windowId(userId, fullName);
    }

    private void createAs(String user, String folder, String name) throws Exception {
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, user);
        WebRequest req = new WebRequest(new URL(wc.createCrumbedUrl(((TopLevelItem) j.jenkins.getItemByFullName(folder)).getUrl() + "createItem")
                .toExternalForm() + "&name=" + name), HttpMethod.POST);
        req.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        req.setRequestBody("<?xml version='1.1' encoding='UTF-8'?><project><builders/><publishers/><buildWrappers/></project>");
        int code = wc.getPage(req).getWebResponse().getStatusCode();
        assertTrue(code < 400, "fixture: " + user + " creates " + folder + "/" + name + ", got " + code);
    }

    private static boolean can(String userId, Item item, hudson.security.Permission permission) {
        return item.getACL().hasPermission2(User.getById(userId, true).impersonate2(), permission);
    }

    private static void deleteTree(Path root) throws IOException {
        assertTrue(Files.isDirectory(root), "premise: the item directory " + root + " exists");
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
