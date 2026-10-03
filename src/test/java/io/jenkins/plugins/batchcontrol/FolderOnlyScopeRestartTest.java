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
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
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
 * SPEC item 8 (D-65), matrix row T-08-106 (note 233): a stored window without the new scope
 * type (a FOLDER window, whose file does not mention FOLDER_ONLY) loads unchanged after a
 * restart and still covers nested items; a FOLDER_ONLY window keeps its type and its smaller
 * reach across the restart.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-65, docs/ARCHITECTURE.md section 5 and
 * docs/TEST-MATRIX.md only (no src/main knowledge).
 */
public class FolderOnlyScopeRestartTest {

    @RegisterExtension
    final JenkinsSessionExtension session = new JenkinsSessionExtension();

    private String folderGrantId;
    private String folderOnlyGrantId;

    /** T-08-106: FOLDER and FOLDER_ONLY windows survive a restart with their type and reach. */
    @Test
    public void t_08_106_windowsWithoutTheNewTypeLoadUnchanged() throws Throwable {
        session.then(r -> {
            prepare(r);
            folderGrantId = open("u1", GrantScope.Type.FOLDER).getId();
            folderOnlyGrantId = open("u2", GrantScope.Type.FOLDER_ONLY).getId();

            Path file = r.jenkins.getRootDir().toPath().resolve("batch-control/grants/" + folderGrantId + ".xml");
            assertTrue(Files.isRegularFile(file), "premise: the FOLDER window is stored at " + file);
            String xml = Files.readString(file, StandardCharsets.UTF_8);
            assertTrue(xml.contains("FOLDER") && !xml.contains("FOLDER_ONLY"),
                    "premise: the FOLDER window's file does not name the new type: " + ApproverFormFixtures.excerpt(xml));
        });
        session.then(r -> {
            Item a = r.jenkins.getItemByFullName("ops/a");
            Item b = r.jenkins.getItemByFullName("ops/sub/b");
            assertNotNull(a);
            assertNotNull(b);

            Grant folder = active(folderGrantId);
            assertEquals(GrantScope.Type.FOLDER, folder.getScope().getType(), "the FOLDER window keeps its type");
            assertEquals("ops", folder.getScope().getFullName());
            assertTrue(can("u1", a, Item.CONFIGURE));
            assertTrue(can("u1", b, Item.CONFIGURE), "the FOLDER window still covers nested items after the restart");

            Grant folderOnly = active(folderOnlyGrantId);
            assertEquals(GrantScope.Type.FOLDER_ONLY, folderOnly.getScope().getType(), "the FOLDER_ONLY window keeps its type");
            assertTrue(can("u2", a, Item.CONFIGURE));
            assertFalse(can("u2", b, Item.CONFIGURE), "the FOLDER_ONLY window still excludes nested items after the restart");
        });
    }

    private static Grant active(String id) {
        Grant grant = GrantService.get().listActive().stream().filter(g -> id.equals(g.getId())).findFirst().orElse(null);
        assertNotNull(grant, "the window " + id + " must be active after the restart");
        return grant;
    }

    private static boolean can(String user, Item item, hudson.security.Permission p) {
        return item.getACL().hasPermission2(User.getById(user, true).impersonate2(), p);
    }

    private static Grant open(String user, GrantScope.Type type) {
        GrantRequest request;
        try (ACLContext ignored = ACL.as2(User.getById(user, true).impersonate2())) {
            request = GrantRequestService.get().create(new GrantScope(type, "ops"),
                    Arrays.asList(GrantAction.CONFIGURE), 120, "restart window", "a1");
        }
        try (ACLContext ignored = ACL.as2(User.getById("a1", true).impersonate2())) {
            return GrantRequestService.get().approve(request.getId(), "ok");
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
        Folder sub = ops.createProject(Folder.class, "sub");
        sub.createProject(FreeStyleProject.class, "b");
    }
}
