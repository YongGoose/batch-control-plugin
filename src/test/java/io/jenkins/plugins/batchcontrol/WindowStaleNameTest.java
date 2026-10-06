package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import jenkins.model.Jenkins;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * security-39 S-39-02: a window must never be left on a name its item no longer has, where an item
 * created, renamed or moved to that name would pick it up. SPEC item 8 line 170: "a window applies
 * to its item, not to a name ... so renaming, moving, swapping or re-creating items never makes a
 * window reach an item nobody approved"; DECISIONS D-71c (3): "approval re-verifies the item's
 * identity after registering the grant"; D-74 (3). Matrix rows T-SEC-92 (the approval race),
 * T-SEC-93 (a follow whose write failed) and T-SEC-94 (an item renamed or moved into a name a
 * window was left on) (note 274).
 *
 * <p>The approval race cannot be timed from a test: approval checks the item, writes the request,
 * then registers the window under the checked object's full name, and no public extension point
 * runs in between. The rows therefore play the last step themselves, as security-39's probe did:
 * {@code GrantService#register(Grant, Item)} (public) is called, as the approver, with the item
 * object the approval checked, after that item was deleted (and, for T-SEC-92, after a new item was
 * created at its name). A guard in each row shows that the same call makes a working window when
 * the checked item is still in place, so a green row cannot come from a call that never works.
 *
 * <p>Fixture: change control on, Batch Control matrix strategy; u1 (Overall/Read, Item/Read,
 * RequestGrant), a1 approver, admin. What a window confers is read from the item's own ACL.
 *
 * <p>Written from docs/SPEC.md item 8, docs/DECISIONS.md D-71c and D-74, docs/ARCHITECTURE.md
 * sections 4 and 5 and the Given/When/Then of docs/reports/security-39.md only (no src/main
 * knowledge; the signature of {@code GrantService#register} was taken from the compiler's answer to
 * a call).
 */
@WithJenkins
public class WindowStaleNameTest {

    /** The job of the template window whose stored file {@link #registerAsApproval} copies. */
    private static final String TEMPLATE_JOB = "template-job-q7z";

    private JenkinsRule j;
    private GrantRequest templateRequest;
    private Grant template;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "a1"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        j.jenkins.setAuthorizationStrategy(strategy);
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-SEC-92 (S-39-02 (a), D-71c (3)): u1 requests a CONFIGURE window on the job {@code r}. The
     * approval checks {@code r}; before the window is registered the administrator deletes
     * {@code r} and creates a new job {@code r}; then the window is registered for the checked
     * (deleted) object. u1 holds no Configure on the new {@code r}. Guard: the same registration
     * for an item still in place ({@code h}) confers Configure on it.
     */
    @Test
    public void t_sec_92_windowRegisteredAfterItsItemWasReplacedDoesNotReachTheNewItem() throws Exception {
        FreeStyleProject h = j.createFreeStyleProject("h");
        registerAsApproval(request("u1", "h"), h);
        assertTrue(can("u1", h, Item.CONFIGURE), "guard: registering for an item still in place confers Configure on it");

        FreeStyleProject r = j.createFreeStyleProject("r");
        GrantRequest pending = request("u1", "r");
        Item replacement;
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // fixture: the administrator replaces r between the check and the registration
            r.delete();
            replacement = j.jenkins.createProject(FreeStyleProject.class, "r");
        }
        assertFalse(can("u1", replacement, Item.CONFIGURE), "premise: before the registration the new r confers nothing");
        registerAsApproval(pending, r);

        assertFalse(can("u1", replacement, Item.CONFIGURE),
                "S-39-02 (a): a window approved for the deleted r must not reach the job created at its name (D-71c (3))");
    }

    /**
     * T-SEC-93 (S-39-02 (b)): u1's approved CONFIGURE window on the job {@code a}. The grants
     * directory and the window's file refuse writes while the administrator renames {@code a} to
     * {@code b} (HTTP {@code confirmRename}, 3xx); write access is restored; the administrator
     * renames {@code x} to {@code a}. u1 holds no Configure on the item now named {@code a}, and the
     * window either names {@code b} or has ended with a GRANT_REVOKE record. Skipped where this
     * process can write despite the read-only bits (root, Windows).
     */
    @Test
    public void t_sec_93_followWhoseWriteFailedLeavesNoWindowForAnItemRenamedIntoTheOldName() throws Exception {
        FreeStyleProject a = j.createFreeStyleProject("a");
        FreeStyleProject x = j.createFreeStyleProject("x");
        Grant window = approve(request("u1", "a"));
        assertTrue(can("u1", a, Item.CONFIGURE), "premise: the window confers Configure on a");
        Set<String> revokesBefore = WindowStateFixtures.revokeRecordIds();

        Path dir = j.jenkins.getRootDir().toPath().resolve("batch-control/grants");
        File dirFile = dir.toFile();
        File storedFile = dir.resolve(window.getId() + ".xml").toFile();
        assertTrue(storedFile.isFile(), "premise (ARCHITECTURE 5): the window is stored at " + storedFile);
        try {
            assertTrue(storedFile.setWritable(false, false), "fixture: the stored window made read-only");
            assertTrue(dirFile.setWritable(false, false), "fixture: the grants directory made read-only");
            Assumptions.assumeTrue(writesRefused(dir) && !Files.isWritable(storedFile.toPath()),
                    "the file system does not refuse writes to read-only files for this process");
            assertRedirect(rename("admin", a, "b"), "the administrator renames a to b while the grant store refuses writes");
        } finally {
            dirFile.setWritable(true);
            storedFile.setWritable(true);
        }
        assertNotNull(j.jenkins.getItemByFullName("b"), "premise: a is now b");
        assertRedirect(rename("admin", x, "a"), "the administrator renames x to a after write access was restored");
        Item nowA = j.jenkins.getItemByFullName("a");
        assertNotNull(nowA, "premise: x is now a");

        assertFalse(can("u1", nowA, Item.CONFIGURE),
                "S-39-02 (b): the item renamed into the name a failed follow left behind must get nothing from u1's window");
        Grant now = WindowStateFixtures.active(window.getId());
        if (now != null) {
            assertEquals("b", now.getScope().getFullName(), "S-39-02 (b): a window still active must name its item's name b");
        } else {
            List<ChangeRecord> revokes = WindowStateFixtures.revokeRecordsSince(revokesBefore);
            assertTrue(revokes.stream().anyMatch(rec -> WindowStateFixtures.identifies(rec, window.getId(), "a")
                    || WindowStateFixtures.identifies(rec, window.getId(), "b")),
                    "S-39-02 (b): a window that is no longer active must have ended with a GRANT_REVOKE record, got "
                            + WindowStateFixtures.describe(revokes));
        }
    }

    /**
     * T-SEC-94 (S-39-02 (a) second form and fix direction (2)): (1) u1 requests a window on the job
     * {@code s}; the administrator deletes {@code s} before the window is registered for it, so the
     * window is left on the free name {@code s}; the administrator then renames the job
     * {@code other} to {@code s} (HTTP): u1 holds no Configure on it. (2) The same with the job
     * {@code t}, and the administrator moves {@code f/t} into the Jenkins root (HTTP
     * {@code move/move}, destination {@code /}): u1 holds no Configure on it. Guard: u1's approved
     * window on {@code p} follows the administrator's rename of {@code p} to {@code p2}; after the
     * administrator renames {@code q} to {@code p}, u1 holds no Configure on the new {@code p} and
     * still holds it on {@code p2}.
     */
    @Test
    public void t_sec_94_itemRenamedOrMovedIntoANameAWindowWasLeftOnGetsNothing() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("p");
        FreeStyleProject q = j.createFreeStyleProject("q");
        Grant followed = approve(request("u1", "p"));
        assertRedirect(rename("admin", p, "p2"), "guard: the administrator renames p to p2");
        WindowStateFixtures.assertActiveOn(j, "u1", followed.getId(), "p2", "guard: the window follows p to p2");
        assertRedirect(rename("admin", q, "p"), "guard: the administrator renames q to p");
        assertFalse(can("u1", j.jenkins.getItemByFullName("p"), Item.CONFIGURE), "guard: the job renamed into p gets nothing");
        assertTrue(can("u1", j.jenkins.getItemByFullName("p2"), Item.CONFIGURE), "guard: the window still confers on p2");

        FreeStyleProject s = j.createFreeStyleProject("s");
        FreeStyleProject other = j.createFreeStyleProject("other");
        GrantRequest onS = request("u1", "s");
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // fixture: the administrator deletes s between the check and the registration
            s.delete();
        }
        registerAsApproval(onS, s);
        assertRedirect(rename("admin", other, "s"), "the administrator renames other to s");
        Item renamedIn = j.jenkins.getItemByFullName("s");
        assertNotNull(renamedIn, "premise: other is now s");
        assertFalse(can("u1", renamedIn, Item.CONFIGURE),
                "S-39-02: the job renamed into the name s, which a window was left on, must get nothing from it");

        FreeStyleProject t = j.createFreeStyleProject("t");
        Folder f = j.jenkins.createProject(Folder.class, "f");
        FreeStyleProject ft = f.createProject(FreeStyleProject.class, "t");
        GrantRequest onT = request("u1", "t");
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // fixture: the administrator deletes t between the check and the registration
            t.delete();
        }
        registerAsApproval(onT, t);
        assertRedirect(ApproverFormFixtures.post(j, "admin", ft.getUrl() + "move/move", List.of(new NameValuePair("destination", "/"))),
                "the administrator moves f/t into the root");
        Item movedIn = j.jenkins.getItemByFullName("t");
        assertNotNull(movedIn, "premise: f/t is now t");
        assertNull(f.getItem("t"), "premise: f/t left f");
        assertFalse(can("u1", movedIn, Item.CONFIGURE),
                "S-39-02: the job moved into the name t, which a window was left on, must get nothing from it");
    }

    // ---------------------------------------------------------------- helpers

    /** u1's PENDING CONFIGURE request on {@code fullName}, designating a1. */
    private static GrantRequest request(String user, String fullName) {
        try (ACLContext ignored = ACL.as2(User.getById(user, true).impersonate2())) {
            return GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, fullName),
                    List.of(GrantAction.CONFIGURE), 30, "maintenance of " + fullName, "a1");
        }
    }

    private static Grant approve(GrantRequest request) {
        try (ACLContext ignored = ACL.as2(User.getById("a1", true).impersonate2())) {
            Grant grant = GrantRequestService.get().approve(request.getId(), "ok");
            assertNotNull(grant, "fixture: the approval must open a window");
            return grant;
        }
    }

    /**
     * The approval's last step played by the test: as a1, a window for {@code request} that was never
     * registered is registered for {@code checked}, the item object the approval checked. The
     * {@code Grant} constructor is not public, so the window is read with core's XStream from a copy
     * of a window file the plugin wrote (ARCHITECTURE section 5, {@code grants/<id>.xml}): the
     * template window on {@link #TEMPLATE_JOB} with its id, request id and item name replaced.
     */
    private void registerAsApproval(GrantRequest request, Item checked) throws Exception {
        if (template == null) {
            j.createFreeStyleProject(TEMPLATE_JOB);
            templateRequest = request("u1", TEMPLATE_JOB);
            template = approve(templateRequest);
        }
        Path file = j.jenkins.getRootDir().toPath().resolve("batch-control/grants/" + template.getId() + ".xml");
        assertTrue(Files.isRegularFile(file), "premise (ARCHITECTURE 5): the template window is stored at " + file);
        String xml = Files.readString(file, StandardCharsets.UTF_8);
        String id = UUID.randomUUID().toString();
        String fullName = request.getScope().getFullName();
        assertTrue(xml.contains(template.getId()) && xml.contains(templateRequest.getId()) && xml.contains(">" + TEMPLATE_JOB + "<"),
                "premise: the template window file names its id, its request and its item: " + ApproverFormFixtures.excerpt(xml));
        xml = xml.replace(template.getId(), id).replace(templateRequest.getId(), request.getId())
                .replace(">" + TEMPLATE_JOB + "<", ">" + fullName + "<");
        Grant grant = (Grant) Jenkins.XSTREAM2.fromXML(xml);
        assertEquals(id, grant.getId(), "premise: the copy is a new window");
        assertEquals("u1", grant.getUser(), "premise: the copy is u1's window");
        assertEquals(fullName, grant.getScope().getFullName(), "premise: the copy names " + fullName);
        try (ACLContext ignored = ACL.as2(User.getById("a1", true).impersonate2())) {
            GrantService.get().register(grant, checked);
        }
    }

    private WebResponse rename(String user, Item item, String newName) throws Exception {
        return ApproverFormFixtures.post(j, user, item.getUrl() + "confirmRename", List.of(new NameValuePair("newName", newName)));
    }

    private static void assertRedirect(WebResponse response, String what) {
        int code = response.getStatusCode();
        assertTrue(code >= 300 && code < 400, what + " must go through (redirect), got HTTP " + code + ": "
                + ApproverFormFixtures.excerpt(response.getContentAsString()));
    }

    private static boolean writesRefused(Path dir) {
        Path probe = dir.resolve("probe-" + System.nanoTime() + ".tmp");
        try {
            Files.createFile(probe);
            Files.delete(probe);
            return false;
        } catch (java.io.IOException expected) {
            return true;
        }
    }

    private static boolean can(String user, Item item, hudson.security.Permission permission) {
        return item.getACL().hasPermission2(User.getById(user, true).impersonate2(), permission);
    }
}
