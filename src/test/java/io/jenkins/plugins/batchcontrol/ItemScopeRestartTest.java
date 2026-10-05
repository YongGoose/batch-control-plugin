package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.model.ItemKind;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.htmlunit.WebResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 4 (restart durability) and item 8 (D-71), matrix rows T-08-106 and T-08-126
 * (note 260) and T-08-145 (note 262): an ITEM window and the item kind it records survive a
 * restart with their reach unchanged, and a stored window or request with one of the earlier scope
 * types is not converted (D-69), so it confers nothing after the restart while an intact one next
 * to it still loads. T-08-151 (note 264, converted for D-74 in note 270): a window whose item
 * vanished while Jenkins was down ends at startup. T-08-189 (note 270, D-74): a window that followed
 * a rename of its item (or of its item's folder) stays with the item across a restart. T-08-190 (note
 * 270): a window whose end could not be written when its item was deleted does not come back after a
 * restart on an item re-created at its name.
 *
 * <p>The grant file is located as ARCHITECTURE section 5 describes ({@code batch-control/grants/<id>.xml})
 * and is read only for premises; the earlier-type file is made from a file the current plugin
 * wrote, by replacing its scope type value.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-71/D-69/D-74, docs/ARCHITECTURE.md sections 4 and 5 and
 * docs/TEST-MATRIX.md only (no src/main knowledge).
 */
public class ItemScopeRestartTest {

    @RegisterExtension
    final JenkinsSessionExtension session = new JenkinsSessionExtension();

    private String jobWindowId;
    private String jobRequestId;
    private String folderWindowId;
    private String folderRequestId;
    private String staleWindowId;
    private final Map<String, String> staleRequestIds = new LinkedHashMap<>();
    private String intactRequestId;
    private final Map<String, String> startupWindowIds = new LinkedHashMap<>();
    private Path home;
    private String followedJobWindowId;
    private String followedFolderWindowId;
    private String followedChildWindowId;

    /**
     * T-08-106 (rewritten for D-71; was the D-65 FOLDER/FOLDER_ONLY restart row): u1's CONFIGURE
     * window on the job {@code ops/a} and u2's CREATE window on the folder {@code ops} survive a
     * restart: both are active with scope type ITEM, their full names and their kinds (window and
     * request); u1 still configures {@code ops/a} and nothing else, u2 still creates in {@code ops}
     * and not in {@code ops/sub}.
     */
    @Test
    public void t_08_106_itemWindowAndItsKindSurviveARestart() throws Throwable {
        session.then(r -> {
            prepare(r);
            GrantRequest jobRequest = request("u1", "ops/a", GrantAction.CONFIGURE);
            jobRequestId = jobRequest.getId();
            jobWindowId = approve(jobRequest).getId();
            GrantRequest folderRequest = request("u2", "ops", GrantAction.CREATE);
            folderRequestId = folderRequest.getId();
            folderWindowId = approve(folderRequest).getId();

            String jobXml = grantFile(r, jobWindowId);
            assertTrue(jobXml.contains("ITEM") && jobXml.contains("hudson.model.FreeStyleProject"),
                    "premise (ARCHITECTURE 5): the window file records the ITEM scope and the kind: " + ApproverFormFixtures.excerpt(jobXml));
            String folderXml = grantFile(r, folderWindowId);
            assertTrue(folderXml.contains("com.cloudbees.hudson.plugins.folder.Folder"),
                    "premise: the folder window's file records the Folder kind: " + ApproverFormFixtures.excerpt(folderXml));
        });
        session.then(r -> {
            Item ops = r.jenkins.getItemByFullName("ops");
            Item a = r.jenkins.getItemByFullName("ops/a");
            Item c = r.jenkins.getItemByFullName("ops/c");
            Item sub = r.jenkins.getItemByFullName("ops/sub");
            Item b = r.jenkins.getItemByFullName("ops/sub/b");
            assertNotNull(b);

            Grant jobWindow = active(jobWindowId);
            assertEquals(GrantScope.Type.ITEM, jobWindow.getScope().getType());
            assertEquals("ops/a", jobWindow.getScope().getFullName());
            assertKind(jobWindow.getItemKind(), "hudson.model.FreeStyleProject", "Freestyle project", "the job window");
            assertKind(GrantRequestService.get().load(jobRequestId).getItemKind(), "hudson.model.FreeStyleProject",
                    "Freestyle project", "the job window's request");
            assertTrue(can("u1", a, Item.CONFIGURE), "after the restart u1 still configures ops/a");
            assertFalse(can("u1", c, Item.CONFIGURE), "after the restart the window still excludes the sibling ops/c");
            assertFalse(can("u1", ops, Item.CONFIGURE), "after the restart the window still excludes the folder ops");
            assertFalse(can("u1", b, Item.CONFIGURE), "after the restart the window still excludes ops/sub/b");

            Grant folderWindow = active(folderWindowId);
            assertEquals(GrantScope.Type.ITEM, folderWindow.getScope().getType());
            assertEquals("ops", folderWindow.getScope().getFullName());
            assertKind(folderWindow.getItemKind(), "com.cloudbees.hudson.plugins.folder.Folder", "Folder", "the folder window");
            assertKind(GrantRequestService.get().load(folderRequestId).getItemKind(), "com.cloudbees.hudson.plugins.folder.Folder",
                    "Folder", "the folder window's request");
            assertTrue(can("u2", ops, Item.CREATE), "after the restart u2 still creates in ops");
            assertFalse(can("u2", sub, Item.CREATE), "after the restart the window still excludes the nested folder");
            assertFalse(can("u2", a, Item.CONFIGURE), "a CREATE window confers no Configure on an item u2 did not create");
        });
    }

    /**
     * T-08-126 (D-71, D-69): u2's CONFIGURE window on the folder {@code ops} is rewritten on disk
     * to the earlier scope type FOLDER (made from the file the plugin wrote: its single
     * {@code ITEM} value replaced, premise asserted). After the restart it is not converted: u2
     * holds Configure on neither {@code ops}, {@code ops/a} nor {@code ops/sub/b}. Guard: u1's intact
     * window next to it is still active and confers Configure on {@code ops/a}.
     */
    @Test
    public void t_08_126_windowWithAnEarlierScopeTypeIsNotConverted() throws Throwable {
        session.then(r -> {
            prepare(r);
            jobWindowId = approve(request("u1", "ops/a", GrantAction.CONFIGURE)).getId();
            staleWindowId = approve(request("u2", "ops", GrantAction.CONFIGURE)).getId();
            assertTrue(can("u2", r.jenkins.getItemByFullName("ops"), Item.CONFIGURE), "premise: u2's window confers before the rewrite");

            Path file = grantPath(r, staleWindowId);
            String xml = Files.readString(file, StandardCharsets.UTF_8);
            // the scope type is an element value or an attribute value; either way it is the only "ITEM" token
            int asElement = xml.split(">ITEM<", -1).length - 1;
            int asAttribute = xml.split("\"ITEM\"", -1).length - 1;
            assertEquals(1, asElement + asAttribute,
                    "premise: the window file holds the scope type value ITEM exactly once: " + ApproverFormFixtures.excerpt(xml));
            Files.writeString(file, xml.replace(">ITEM<", ">FOLDER<").replace("\"ITEM\"", "\"FOLDER\""), StandardCharsets.UTF_8);
        });
        session.then(r -> {
            Item ops = r.jenkins.getItemByFullName("ops");
            Item a = r.jenkins.getItemByFullName("ops/a");
            Item b = r.jenkins.getItemByFullName("ops/sub/b");
            for (Item item : new Item[] {ops, a, b}) {
                assertFalse(can("u2", item, Item.CONFIGURE),
                        "D-69/D-71: a window stored with the earlier type FOLDER must not be converted and must confer nothing on "
                                + item.getFullName());
            }
            Grant intact = active(jobWindowId);
            assertEquals("ops/a", intact.getScope().getFullName());
            assertTrue(can("u1", a, Item.CONFIGURE), "guard: the intact ITEM window still confers after the restart");
        });
    }

    /**
     * T-08-145 (D-71, D-69, spec-review-S6 m-4: "Stored windows and requests with the earlier scope
     * types (JOB, FOLDER, FOLDER_ONLY) are not converted"): three PENDING CONFIGURE requests have
     * their files ({@code batch-control/requests/grant/<id>.xml}, ARCHITECTURE 5) rewritten to an
     * earlier scope type, each made from the file the plugin wrote by replacing its single
     * {@code ITEM} value (premise asserted): u2's on {@code ops} to FOLDER, u3's on {@code ops} to
     * FOLDER_ONLY, u4's on {@code ops/a} to JOB. After the restart none of them can be approved
     * (a1's approval POST answers 4xx), its detail URL does not crash (below 500), none is listed as
     * APPROVED, no window is open for u2, u3 or u4, and none of them holds Configure on {@code ops},
     * {@code ops/a} or {@code ops/sub/b}. Guards: u1's intact request on {@code ops/c} next to them
     * is still PENDING, the grants page opens for a1 (200) and links it, and its approval confers
     * Configure on {@code ops/c}.
     */
    @Test
    public void t_08_145_grantRequestWithAnEarlierScopeTypeIsNotConverted() throws Throwable {
        session.then(r -> {
            prepare(r);
            staleRequestIds.put("u2", request("u2", "ops", GrantAction.CONFIGURE).getId());
            staleRequestIds.put("u3", request("u3", "ops", GrantAction.CONFIGURE).getId());
            staleRequestIds.put("u4", request("u4", "ops/a", GrantAction.CONFIGURE).getId());
            intactRequestId = request("u1", "ops/c", GrantAction.CONFIGURE).getId();
            rewriteScopeType(r, staleRequestIds.get("u2"), "FOLDER");
            rewriteScopeType(r, staleRequestIds.get("u3"), "FOLDER_ONLY");
            rewriteScopeType(r, staleRequestIds.get("u4"), "JOB");
        });
        session.then(r -> {
            Item ops = r.jenkins.getItemByFullName("ops");
            Item a = r.jenkins.getItemByFullName("ops/a");
            Item b = r.jenkins.getItemByFullName("ops/sub/b");
            Item c = r.jenkins.getItemByFullName("ops/c");
            for (Map.Entry<String, String> stale : staleRequestIds.entrySet()) {
                String user = stale.getKey();
                String id = stale.getValue();
                WebResponse approval = ApproverFormFixtures.decideGrant(r, "a1", id, "approve", "ok");
                assertTrue(approval.getStatusCode() >= 400 && approval.getStatusCode() < 500,
                        "D-69/D-71: " + user + "'s request stored with an earlier scope type must not be approved, got HTTP "
                                + approval.getStatusCode() + ": " + ApproverFormFixtures.excerpt(approval.getContentAsString()));
                int detail = ApproverFormFixtures.get(r, "a1", "batch-control/grants/" + id + "/").getStatusCode();
                assertTrue(detail < 500, "the detail URL of " + user + "'s earlier-type request must not crash, got HTTP " + detail);
                assertTrue(GrantRequestService.get().list().stream()
                                .noneMatch(g -> id.equals(g.getId()) && g.getStatus() == RequestStatus.APPROVED),
                        user + "'s earlier-type request must not be APPROVED");
                assertTrue(GrantService.get().listActive().stream().noneMatch(g -> user.equals(g.getUser())),
                        "no window may be open for " + user);
                for (Item item : new Item[] {ops, a, b}) {
                    assertFalse(can(user, item, Item.CONFIGURE), user + "'s earlier-type request must confer nothing on " + item.getFullName());
                }
            }

            GrantRequest intact = GrantRequestService.get().load(intactRequestId);
            assertNotNull(intact, "guard: the intact request loads after the restart");
            assertEquals(RequestStatus.PENDING, intact.getStatus(), "guard: the intact request is still PENDING");
            WebResponse list = ApproverFormFixtures.get(r, "a1", "batch-control/grants/");
            assertEquals(200, list.getStatusCode(), "guard: the grants page opens next to the earlier-type files");
            assertTrue(list.getContentAsString().contains(intactRequestId), "guard: the grants page lists the intact request");
            ApproverFormFixtures.assertSuccess(ApproverFormFixtures.decideGrant(r, "a1", intactRequestId, "approve", "ok"),
                    "guard: approval of the intact request");
            assertTrue(can("u1", c, Item.CONFIGURE), "guard: the intact request's window confers Configure on ops/c");
        });
    }

    /**
     * T-08-151 (D-74 "a window ... ends ... starting Jenkins after the item vanished"; ARCHITECTURE 4
     * "at startup, windows whose item no longer exists end"; converted from the D-71b startup
     * unbinding, note 270): u1 holds CONFIGURE windows on the job {@code ops/a}, the folder
     * {@code ops/sub}, the job {@code ops/sub/b} inside it, and the job {@code ops/c}. While Jenkins
     * is down the directories of {@code ops/a} and {@code ops/sub} are deleted (core fires no item
     * event). After the start the three windows whose item is gone have ended (not active, no Active
     * list row); after the administrator creates {@code ops/a} and {@code ops/sub/b} again, u1 holds
     * no Configure on them and the windows are still ended. Guard: the window on {@code ops/c}, whose
     * item survived, is active on {@code ops/c} and confers Configure on it.
     */
    @Test
    public void t_08_151_windowWhoseItemVanishedWhileDownEndsAtStartup() throws Throwable {
        session.then(r -> {
            prepare(r);
            for (String name : new String[] {"ops/a", "ops/sub", "ops/sub/b", "ops/c"}) {
                Grant window = approve(request("u1", name, GrantAction.CONFIGURE));
                startupWindowIds.put(name, window.getId());
                assertTrue(can("u1", r.jenkins.getItemByFullName(name), Item.CONFIGURE), "premise: the window on " + name + " confers");
            }
            home = r.jenkins.getRootDir().toPath();
        });
        for (String dir : new String[] {"jobs/ops/jobs/a", "jobs/ops/jobs/sub"}) {
            Path path = home.resolve(dir);
            assertTrue(Files.isDirectory(path), "premise: the item directory " + path + " exists while Jenkins is down");
            try (Stream<Path> walk = Files.walk(path)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }
        session.then(r -> {
            assertNull(r.jenkins.getItemByFullName("ops/a"), "premise: ops/a is gone after the start");
            assertNull(r.jenkins.getItemByFullName("ops/sub"), "premise: ops/sub is gone after the start");
            for (String name : new String[] {"ops/a", "ops/sub", "ops/sub/b"}) {
                WindowStateFixtures.assertEnded(r, "u1", startupWindowIds.get(name),
                        "D-74: the window on " + name + " whose item vanished while Jenkins was down");
            }
            String onC = startupWindowIds.get("ops/c");
            WindowStateFixtures.assertActiveOn(r, "u1", onC, "ops/c", "guard: the window on ops/c, whose item survived");
            assertTrue(can("u1", r.jenkins.getItemByFullName("ops/c"), Item.CONFIGURE), "guard: the window on ops/c still confers after the start");

            Item a;
            Item b;
            try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // fixture: the administrator creates the names again
                Folder ops = (Folder) r.jenkins.getItemByFullName("ops");
                a = ops.createProject(FreeStyleProject.class, "a");
                Folder sub = ops.createProject(Folder.class, "sub");
                b = sub.createProject(FreeStyleProject.class, "b");
            }
            assertFalse(can("u1", a, Item.CONFIGURE), "D-74: no window may reach the re-created ops/a");
            assertFalse(can("u1", b, Item.CONFIGURE), "D-74: no window may reach the re-created ops/sub/b");
            WindowStateFixtures.assertEnded(r, "u1", startupWindowIds.get("ops/a"), "D-74: the window on ops/a stays ended after the name was re-created");
            WindowStateFixtures.assertEnded(r, "u1", startupWindowIds.get("ops/sub/b"),
                    "D-74: the window on ops/sub/b stays ended after the name was re-created");
        });
    }

    /**
     * T-08-189 (D-74 "the window follows it -- windows on the items below a renamed or moved folder
     * follow too"; SPEC item 4, restart durability; note 270): u1's CONFIGURE window on the job
     * {@code ops/a}, u2's CONFIGURE windows on the folder {@code ops/sub} and the job
     * {@code ops/sub/b}. The administrator renames {@code ops/a} to {@code ops/a-renamed} and the
     * folder {@code ops/sub} to {@code ops/sub-renamed}; the windows follow (premise). After a
     * restart the three windows are still active on {@code ops/a-renamed}, {@code ops/sub-renamed}
     * and {@code ops/sub-renamed/b} and confer Configure there; items the administrator then creates
     * at the old names ({@code ops/a}, a folder {@code ops/sub} with a job {@code ops/sub/b}) get
     * nothing, and the windows stay on their items.
     */
    @Test
    public void t_08_189_followedWindowStaysWithItsItemAcrossARestart() throws Throwable {
        session.then(r -> {
            prepare(r);
            followedJobWindowId = approve(request("u1", "ops/a", GrantAction.CONFIGURE)).getId();
            followedFolderWindowId = approve(request("u2", "ops/sub", GrantAction.CONFIGURE)).getId();
            followedChildWindowId = approve(request("u2", "ops/sub/b", GrantAction.CONFIGURE)).getId();
            try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // fixture: the administrator renames the job and the folder
                ((FreeStyleProject) r.jenkins.getItemByFullName("ops/a")).renameTo("a-renamed");
                ((Folder) r.jenkins.getItemByFullName("ops/sub")).renameTo("sub-renamed");
            }
            assertEquals("ops/a-renamed", active(followedJobWindowId).getScope().getFullName(), "premise: the job window followed the rename");
            assertEquals("ops/sub-renamed", active(followedFolderWindowId).getScope().getFullName(),
                    "premise: the folder window followed the rename");
            assertEquals("ops/sub-renamed/b", active(followedChildWindowId).getScope().getFullName(),
                    "premise: the window below the renamed folder followed too");
        });
        session.then(r -> {
            Item renamedJob = r.jenkins.getItemByFullName("ops/a-renamed");
            Item renamedFolder = r.jenkins.getItemByFullName("ops/sub-renamed");
            Item child = r.jenkins.getItemByFullName("ops/sub-renamed/b");
            assertNotNull(child, "premise: the renamed items exist after the restart");
            WindowStateFixtures.assertActiveOn(r, "u1", followedJobWindowId, "ops/a-renamed", "D-74: after the restart the job window stays on its job");
            WindowStateFixtures.assertActiveOn(r, "u2", followedFolderWindowId, "ops/sub-renamed",
                    "D-74: after the restart the folder window stays on its folder");
            WindowStateFixtures.assertActiveOn(r, "u2", followedChildWindowId, "ops/sub-renamed/b",
                    "D-74: after the restart the window below the folder stays on its job");
            assertTrue(can("u1", renamedJob, Item.CONFIGURE), "D-74: after the restart u1 configures ops/a-renamed");
            assertTrue(can("u2", renamedFolder, Item.CONFIGURE), "D-74: after the restart u2 configures ops/sub-renamed");
            assertTrue(can("u2", child, Item.CONFIGURE), "D-74: after the restart u2 configures ops/sub-renamed/b");

            Item a;
            Item sub;
            Item b;
            try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // fixture: the administrator creates items at the old names
                Folder ops = (Folder) r.jenkins.getItemByFullName("ops");
                a = ops.createProject(FreeStyleProject.class, "a");
                Folder newSub = ops.createProject(Folder.class, "sub");
                sub = newSub;
                b = newSub.createProject(FreeStyleProject.class, "b");
            }
            assertFalse(can("u1", a, Item.CONFIGURE), "D-74: the new ops/a at the window's old name gets nothing");
            assertFalse(can("u2", sub, Item.CONFIGURE), "D-74: the new ops/sub at the window's old name gets nothing");
            assertFalse(can("u2", b, Item.CONFIGURE), "D-74: the new ops/sub/b at the window's old name gets nothing");
            assertEquals("ops/a-renamed", active(followedJobWindowId).getScope().getFullName(), "guard: the job window stays on its job");
            assertEquals("ops/sub-renamed/b", active(followedChildWindowId).getScope().getFullName(), "guard: the child window stays on its job");
        });
    }

    /**
     * T-08-190 (the restart half of T-08-164; SPEC 8 line 170 "deleting the item ends the window ...
     * so ... re-creating items never makes a window reach an item nobody approved"; security-36
     * S-36-03 (ii) intent; note 270): u1's CONFIGURE windows on the jobs {@code ops/a} and
     * {@code ops/c}. The grants directory and the {@code ops/a} window's file are made read-only and
     * the administrator deletes {@code ops/a}, so the window's end cannot be written at that moment
     * (premise: the window is not active). Write access is restored, the expiry periodic work runs
     * once, and the administrator creates a new job {@code ops/a}. After a restart u1 holds no
     * Configure on the new {@code ops/a} and the window is not active. Guard: the window on
     * {@code ops/c} is active after the restart and confers. Skipped where this process can write
     * despite the read-only bits (root, Windows).
     */
    @Test
    public void t_08_190_windowWhoseEndCouldNotBeWrittenDoesNotReturnAfterARestart() throws Throwable {
        session.then(r -> {
            prepare(r);
            startupWindowIds.put("ops/a", approve(request("u1", "ops/a", GrantAction.CONFIGURE)).getId());
            startupWindowIds.put("ops/c", approve(request("u1", "ops/c", GrantAction.CONFIGURE)).getId());
            String onA = startupWindowIds.get("ops/a");
            Path dir = r.jenkins.getRootDir().toPath().resolve("batch-control/grants");
            Path stored = grantPath(r, onA);
            java.io.File dirFile = dir.toFile();
            java.io.File storedFile = stored.toFile();
            try {
                assertTrue(storedFile.setWritable(false, false), "fixture: the stored grant made read-only");
                assertTrue(dirFile.setWritable(false, false), "fixture: the grants directory made read-only");
                boolean enforced;
                Path probe = dir.resolve("probe-" + System.nanoTime() + ".tmp");
                try {
                    Files.createFile(probe);
                    Files.delete(probe);
                    enforced = false;
                } catch (java.io.IOException expected) {
                    enforced = true;
                }
                org.junit.jupiter.api.Assumptions.assumeTrue(enforced && !Files.isWritable(stored),
                        "the file system does not refuse writes to read-only files for this process");
                try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // fixture: the administrator deletes ops/a
                    r.jenkins.getItemByFullName("ops/a").delete();
                }
                assertTrue(GrantService.get().listActive().stream().noneMatch(g -> onA.equals(g.getId())),
                        "premise: the window on ops/a is not active after its job was deleted");
            } finally {
                dirFile.setWritable(true);
                storedFile.setWritable(true);
            }
            hudson.ExtensionList.lookupSingleton(io.jenkins.plugins.batchcontrol.ops.ExpiryPeriodicWork.class).doRun();
            try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // fixture: the administrator creates a new job at the name
                ((Folder) r.jenkins.getItemByFullName("ops")).createProject(FreeStyleProject.class, "a");
            }
            assertFalse(can("u1", r.jenkins.getItemByFullName("ops/a"), Item.CONFIGURE), "premise: before the restart the new ops/a gets nothing");
        });
        session.then(r -> {
            Item recreated = r.jenkins.getItemByFullName("ops/a");
            assertNotNull(recreated, "premise: the new ops/a exists after the restart");
            String onA = startupWindowIds.get("ops/a");
            assertFalse(can("u1", recreated, Item.CONFIGURE), "D-74: after the restart no window may reach the job re-created at ops/a");
            assertTrue(GrantService.get().listActive().stream().noneMatch(g -> onA.equals(g.getId())),
                    "the window on the deleted ops/a must not be active after the restart");
            WindowStateFixtures.assertActiveOn(r, "u1", startupWindowIds.get("ops/c"), "ops/c", "guard: the window on ops/c after the restart");
            assertTrue(can("u1", r.jenkins.getItemByFullName("ops/c"), Item.CONFIGURE), "guard: the window on ops/c still confers");
        });
    }

    // ---------------------------------------------------------------- helpers

    /** Replaces the single scope type value ITEM of the grant request file {@code id} by {@code earlierType}. */
    private static void rewriteScopeType(JenkinsRule r, String id, String earlierType) throws Exception {
        Path file = r.jenkins.getRootDir().toPath().resolve("batch-control/requests/grant/" + id + ".xml");
        assertTrue(Files.isRegularFile(file), "premise (ARCHITECTURE 5): the grant request is stored at " + file);
        String xml = Files.readString(file, StandardCharsets.UTF_8);
        int asElement = xml.split(">ITEM<", -1).length - 1;
        int asAttribute = xml.split("\"ITEM\"", -1).length - 1;
        assertEquals(1, asElement + asAttribute,
                "premise: the grant request file holds the scope type value ITEM exactly once: " + ApproverFormFixtures.excerpt(xml));
        Files.writeString(file, xml.replace(">ITEM<", ">" + earlierType + "<").replace("\"ITEM\"", "\"" + earlierType + "\""),
                StandardCharsets.UTF_8);
    }

    private static void assertKind(ItemKind kind, String descriptorId, String displayName, String what) {
        assertNotNull(kind, what + " must keep its item kind across the restart");
        assertEquals(descriptorId, kind.getDescriptorId(), what + ": descriptor id");
        assertEquals(displayName, kind.getDisplayName(), what + ": display name");
    }

    private static Grant active(String id) {
        List<Grant> active = GrantService.get().listActive();
        Grant grant = active.stream().filter(g -> id.equals(g.getId())).findFirst().orElse(null);
        assertNotNull(grant, "the window " + id + " must be active after the restart");
        return grant;
    }

    private static boolean can(String user, Item item, hudson.security.Permission p) {
        return item.getACL().hasPermission2(User.getById(user, true).impersonate2(), p);
    }

    private static Path grantPath(JenkinsRule r, String id) {
        Path file = r.jenkins.getRootDir().toPath().resolve("batch-control/grants/" + id + ".xml");
        assertTrue(Files.isRegularFile(file), "premise (ARCHITECTURE 5): the window is stored at " + file);
        return file;
    }

    private static String grantFile(JenkinsRule r, String id) throws Exception {
        return Files.readString(grantPath(r, id), StandardCharsets.UTF_8);
    }

    private static GrantRequest request(String user, String fullName, GrantAction... actions) {
        try (ACLContext ignored = ACL.as2(User.getById(user, true).impersonate2())) {
            return GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, fullName),
                    Arrays.asList(actions), 120, "restart window on " + fullName, "a1");
        }
    }

    private static Grant approve(GrantRequest request) {
        try (ACLContext ignored = ACL.as2(User.getById("a1", true).impersonate2())) {
            Grant grant = GrantRequestService.get().approve(request.getId(), "ok");
            assertNotNull(grant, "fixture: the approval must open a window");
            return grant;
        }
    }

    private static void prepare(JenkinsRule r) throws Exception {
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "u2", "u3", "u4", "a1"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        for (String userId : new String[] {"u1", "u2", "u3", "u4"}) {
            strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user(userId));
        }
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        r.jenkins.setAuthorizationStrategy(strategy);
        r.jenkins.save();

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        Folder ops = r.jenkins.createProject(Folder.class, "ops");
        ops.createProject(FreeStyleProject.class, "a");
        ops.createProject(FreeStyleProject.class, "c");
        Folder sub = ops.createProject(Folder.class, "sub");
        sub.createProject(FreeStyleProject.class, "b");
    }
}
