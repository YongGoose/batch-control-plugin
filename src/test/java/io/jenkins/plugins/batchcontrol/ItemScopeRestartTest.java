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
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 4 (restart durability) and item 8 (D-71), matrix rows T-08-106 and T-08-126
 * (note 260): an ITEM window and the item kind it records survive a restart with their reach
 * unchanged, and a stored window with one of the earlier scope types is not converted (D-69), so
 * it confers nothing after the restart while an intact window next to it still loads.
 *
 * <p>The grant file is located as ARCHITECTURE section 5 describes ({@code batch-control/grants/<id>.xml})
 * and is read only for premises; the earlier-type file is made from a file the current plugin
 * wrote, by replacing its scope type value.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-71/D-69, docs/ARCHITECTURE.md section 5 and
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

    // ---------------------------------------------------------------- helpers

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
        for (String userId : new String[] {"u1", "u2", "a1"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u2"));
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
