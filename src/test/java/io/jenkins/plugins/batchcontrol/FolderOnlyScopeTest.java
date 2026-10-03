package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.cloudbees.hudson.plugins.folder.relocate.RelocationAction;
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
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertClientError;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.client;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.grantRequestIds;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 8 (D-65): a FOLDER_ONLY permission window covers the folder itself and the items
 * whose parent is that folder, not items in nested folders; a FOLDER window still covers
 * everything below. Matrix rows T-08-100 .. T-08-105, T-08-107 .. T-08-111 (note 233); the restart row T-08-106 is
 * in {@link FolderOnlyScopeRestartTest}.
 *
 * <p>Layout: folder {@code ops} with job {@code ops/a} and nested folder {@code ops/sub} holding
 * job {@code ops/sub/b}. u1 holds Overall/Read, Item/Read and RequestGrant only, under the
 * Batch Control matrix strategy (D-35a) so a window actually confers. Windows are requested
 * through the frozen form contract ({@code POST batch-control/grants/create} with
 * {@code scopeType=FOLDER_ONLY}) and approved by a1.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-65, docs/ARCHITECTURE.md section 2 and
 * docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class FolderOnlyScopeTest {

    private static final String MINIMAL_JOB_XML = "<?xml version='1.1' encoding='UTF-8'?>"
            + "<project><builders/><publishers/><buildWrappers/></project>";

    private JenkinsRule j;
    private Folder ops;
    private Folder sub;
    private FreeStyleProject a;
    private FreeStyleProject b;

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
        // native Item/Move (folders plugin) so that T-08-111 measures the Delete/Create checks of a move
        strategy.add(RelocationAction.RELOCATE, PermissionEntry.user("u1"));
        j.jenkins.setAuthorizationStrategy(strategy);

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        ops = j.jenkins.createProject(Folder.class, "ops");
        a = ops.createProject(FreeStyleProject.class, "a");
        a.setDescription("base");
        sub = ops.createProject(Folder.class, "sub");
        b = sub.createProject(FreeStyleProject.class, "b");
        b.setDescription("base");

        assertFalse(can("u1", ops, Item.CONFIGURE), "fixture: u1 holds no Configure of their own");
        assertFalse(can("u1", a, Item.CONFIGURE), "fixture: u1 holds no Configure of their own");
    }

    /**
     * T-08-100: a FOLDER_ONLY CONFIGURE window on {@code ops} confers Configure on {@code ops}
     * itself, on its job {@code ops/a} and on its direct item {@code ops/sub}, but not on
     * {@code ops/sub/b} (an item of a nested folder).
     */
    @Test
    public void t_08_100_folderOnlyConfigureCoversFolderAndDirectItemsOnly() throws Exception {
        openWindow("FOLDER_ONLY", "ops", List.of("CONFIGURE"), null);

        assertTrue(can("u1", ops, Item.CONFIGURE), "the folder itself is in a FOLDER_ONLY scope");
        assertTrue(can("u1", a, Item.CONFIGURE), "an item whose parent is the folder is in scope");
        assertTrue(can("u1", sub, Item.CONFIGURE), "the nested folder is itself an item whose parent is ops");
        assertEquals(200, postConfigXml("u1", a, "changed-a"), "u1 must be able to save ops/a");
        assertEquals("changed-a", a.getDescription());

        assertFalse(can("u1", b, Item.CONFIGURE), "an item of a nested folder is outside a FOLDER_ONLY scope");
        assertEquals(403, postConfigXml("u1", b, "changed-b"), "saving ops/sub/b must be refused");
        assertEquals("base", b.getDescription(), "ops/sub/b must be unchanged");
    }

    /** T-08-101 (guard for T-08-100): a FOLDER window on {@code ops} covers {@code ops/sub/b}. */
    @Test
    public void t_08_101_folderWindowStillCoversNestedItems() throws Exception {
        openWindow("FOLDER", "ops", List.of("CONFIGURE"), null);

        assertEquals(200, postConfigXml("u1", b, "changed-b"), "a FOLDER window covers everything below the folder");
        assertEquals("changed-b", b.getDescription());
        assertEquals(200, postConfigXml("u1", a, "changed-a"));
    }

    /**
     * T-08-102: a FOLDER_ONLY CREATE window on {@code ops} lets u1 create directly in {@code ops}
     * but not in {@code ops/sub} nor at the root.
     */
    @Test
    public void t_08_102_folderOnlyCreateWorksDirectlyInTheFolderOnly() throws Exception {
        openWindow("FOLDER_ONLY", "ops", List.of("CREATE"), null);

        int inside = createItem("u1", ops.getUrl(), "made-here");
        assertTrue(inside < 400, "creating directly in ops must succeed, got HTTP " + inside);
        assertNotNull(j.jenkins.getItemByFullName("ops/made-here"));

        int nested = createItem("u1", sub.getUrl(), "made-deeper");
        assertTrue(nested >= 400 && nested < 500, "creating in ops/sub must be refused, got HTTP " + nested);
        assertNull(j.jenkins.getItemByFullName("ops/sub/made-deeper"));

        int root = createItem("u1", "", "made-at-root");
        assertTrue(root >= 400 && root < 500, "creating at the root must be refused, got HTTP " + root);
        assertNull(j.jenkins.getItemByFullName("made-at-root"));
    }

    /** T-08-103: a CREATE name restriction applies to a FOLDER_ONLY window as to FOLDER (D-40). */
    @Test
    public void t_08_103_nameRestrictionAppliesToFolderOnly() throws Exception {
        openWindow("FOLDER_ONLY", "ops", List.of("CREATE"), "/nightly-[a-z]+/");

        int ok = createItem("u1", ops.getUrl(), "nightly-sales");
        assertTrue(ok < 400, "a matching name directly in ops must be created, got HTTP " + ok);
        assertNotNull(j.jenkins.getItemByFullName("ops/nightly-sales"));

        int other = createItem("u1", ops.getUrl(), "daily-sales");
        assertTrue(other >= 400 && other < 500, "a name outside the restriction must be refused, got HTTP " + other);
        assertNull(j.jenkins.getItemByFullName("ops/daily-sales"));

        int nested = createItem("u1", sub.getUrl(), "nightly-deep");
        assertTrue(nested >= 400 && nested < 500, "a matching name in a nested folder must still be refused, got HTTP " + nested);
        assertNull(j.jenkins.getItemByFullName("ops/sub/nightly-deep"));
    }

    /**
     * T-08-104: a FOLDER_ONLY request on something that is not a folder is refused, through the
     * form and the service, and nothing is stored. Guard: the same request on {@code ops} is accepted.
     */
    @Test
    public void t_08_104_folderOnlyOnNonFolderIsRefused() throws Exception {
        int before = grantRequestIds().size();
        assertClientError(submitGrant(j, "u1", "FOLDER_ONLY", "ops/a", List.of("CONFIGURE"), 30,
                "not a folder", null, "a1"), "a FOLDER_ONLY request on the job ops/a");
        assertEquals(before, grantRequestIds().size(), "no request may be stored for a job");

        try (ACLContext ignored = ACL.as2(User.getById("u1", true).impersonate2())) {
            assertThrows(RuntimeException.class, () -> GrantRequestService.get().create(
                    new GrantScope(GrantScope.Type.FOLDER_ONLY, "ops/a"),
                    Arrays.asList(GrantAction.CONFIGURE), 30, "not a folder", "a1"),
                    "the service must refuse FOLDER_ONLY on a job");
        }
        assertEquals(before, grantRequestIds().size(), "no request may be stored for a job");

        String id = submitGrantOk(j, "u1", "FOLDER_ONLY", "ops", List.of("CONFIGURE"), 30, "a folder", null, "a1");
        assertNotNull(GrantRequestService.get().load(id), "the guard request on the folder ops must be stored");
    }

    /**
     * T-08-105: the form binds {@code scopeType=FOLDER_ONLY}: the stored request and the window
     * its approval opens carry the FOLDER_ONLY type and the folder name.
     */
    @Test
    public void t_08_105_formBindsFolderOnlyScopeType() throws Exception {
        String id = submitGrantOk(j, "u1", "FOLDER_ONLY", "ops", List.of("CONFIGURE"), 30, "form binding", null, "a1");
        GrantRequest request = GrantRequestService.get().load(id);
        assertEquals(GrantScope.Type.FOLDER_ONLY, request.getScope().getType());
        assertEquals("ops", request.getScope().getFullName());

        assertSuccess(decideGrant(j, "a1", id, "approve", "ok"), "approval by a1");
        Grant grant = GrantService.get().listActive().stream()
                .filter(g -> "u1".equals(g.getUser())).findFirst().orElse(null);
        assertNotNull(grant, "the approval must open a window");
        assertEquals(GrantScope.Type.FOLDER_ONLY, grant.getScope().getType());
        assertEquals("ops", grant.getScope().getFullName());
    }

    /**
     * T-08-107 (D-65 owner ruling): a FOLDER_ONLY DELETE window on {@code ops} lets u1 delete the
     * direct job {@code ops/a}, but neither the nested folder {@code ops/sub} (that would delete
     * items outside the scope) nor {@code ops/sub/b}.
     */
    @Test
    public void t_08_107_folderOnlyDeleteCoversDirectJobsButNotNestedFolders() throws Exception {
        openWindow("FOLDER_ONLY", "ops", List.of("DELETE"), null);

        assertFalse(can("u1", sub, Item.DELETE), "Delete must not apply to a nested folder under FOLDER_ONLY");
        int nestedFolder = postDelete("u1", sub);
        assertTrue(nestedFolder >= 400 && nestedFolder < 500, "deleting ops/sub must be refused, got HTTP " + nestedFolder);
        assertNotNull(j.jenkins.getItemByFullName("ops/sub"), "ops/sub must still exist");
        assertNotNull(j.jenkins.getItemByFullName("ops/sub/b"), "ops/sub/b must still exist");

        int nestedJob = postDelete("u1", b);
        assertTrue(nestedJob >= 400 && nestedJob < 500, "deleting ops/sub/b must be refused, got HTTP " + nestedJob);
        assertNotNull(j.jenkins.getItemByFullName("ops/sub/b"));

        assertTrue(can("u1", a, Item.DELETE), "Delete applies to a direct job of the folder");
        int direct = postDelete("u1", a);
        assertTrue(direct < 400, "deleting ops/a must succeed, got HTTP " + direct);
        assertNull(j.jenkins.getItemByFullName("ops/a"), "ops/a must be deleted");
    }

    /** T-08-108 (guard for T-08-107): a FOLDER DELETE window on {@code ops} does cover the nested folder. */
    @Test
    public void t_08_108_folderDeleteWindowCoversNestedFolder() throws Exception {
        openWindow("FOLDER", "ops", List.of("DELETE"), null);

        int nestedFolder = postDelete("u1", sub);
        assertTrue(nestedFolder < 400, "a FOLDER window covers deleting ops/sub, got HTTP " + nestedFolder);
        assertNull(j.jenkins.getItemByFullName("ops/sub"), "ops/sub must be deleted");
    }

    /**
     * T-08-109 (D-65 ruling): a FOLDER_ONLY DELETE window on {@code ops} does not let u1 delete
     * {@code ops} itself. Guard: the direct job {@code ops/a} is deletable under the same window.
     */
    @Test
    public void t_08_109_folderOnlyDeleteRefusesTheFolderItself() throws Exception {
        openWindow("FOLDER_ONLY", "ops", List.of("DELETE"), null);

        assertFalse(can("u1", ops, Item.DELETE), "Delete must not apply to the window's folder itself");
        int self = postDelete("u1", ops);
        assertTrue(self >= 400 && self < 500, "deleting ops must be refused, got HTTP " + self);
        assertNotNull(j.jenkins.getItemByFullName("ops"), "ops must still exist");
        assertNotNull(j.jenkins.getItemByFullName("ops/a"), "ops/a must still exist");

        int direct = postDelete("u1", a);
        assertTrue(direct < 400, "guard: deleting ops/a must succeed, got HTTP " + direct);
        assertNull(j.jenkins.getItemByFullName("ops/a"));
    }

    /**
     * T-08-110 (D-65 ruling): a multibranch project directly in {@code ops} is an item group, so a
     * FOLDER_ONLY DELETE window does not delete it. Guard: a FOLDER DELETE window does.
     */
    @Test
    public void t_08_110_folderOnlyDeleteRefusesADirectMultibranchProject() throws Exception {
        WorkflowMultiBranchProject mb = ops.createProject(WorkflowMultiBranchProject.class, "mb");
        openWindow("FOLDER_ONLY", "ops", List.of("DELETE"), null);

        assertFalse(can("u1", mb, Item.DELETE), "Delete must not apply to a multibranch project under FOLDER_ONLY");
        int refused = postDelete("u1", mb);
        assertTrue(refused >= 400 && refused < 500, "deleting ops/mb must be refused, got HTTP " + refused);
        assertNotNull(j.jenkins.getItemByFullName("ops/mb"), "ops/mb must still exist");

        openWindow("FOLDER", "ops", List.of("DELETE"), null);
        int allowed = postDelete("u1", mb);
        assertTrue(allowed < 400, "guard: a FOLDER window covers deleting ops/mb, got HTTP " + allowed);
        assertNull(j.jenkins.getItemByFullName("ops/mb"));
    }

    /**
     * T-08-111 (D-65 ruling, D-59): a move needs Delete, so with a FOLDER_ONLY DELETE window on
     * {@code ops} and a FOLDER CREATE window on {@code dest}, moving the nested folder
     * {@code ops/sub} to {@code dest} is refused. Guard: moving the job {@code ops/a} there succeeds.
     */
    @Test
    public void t_08_111_folderOnlyDeleteRefusesMovingANestedFolderOut() throws Exception {
        Folder dest = j.jenkins.createProject(Folder.class, "dest");
        openWindow("FOLDER_ONLY", "ops", List.of("DELETE"), null);
        openWindow("FOLDER", "dest", List.of("CREATE"), null);

        assertClientError(move("u1", sub, dest), "u1 moving the nested folder ops/sub out");
        assertNotNull(j.jenkins.getItemByFullName("ops/sub"), "ops/sub must stay in place");
        assertNotNull(j.jenkins.getItemByFullName("ops/sub/b"), "ops/sub/b must stay in place");
        assertNull(j.jenkins.getItemByFullName("dest/sub"), "nothing may arrive in dest");

        assertSuccess(move("u1", a, dest), "guard: u1 moving the job ops/a to dest");
        assertNotNull(j.jenkins.getItemByFullName("dest/a"), "ops/a must arrive in dest");
        assertNull(j.jenkins.getItemByFullName("ops/a"));
    }

    // ---------------------------------------------------------------- helpers

    /** {@code POST <item url>move/move} with {@code destination=/<folder>} (folders plugin). */
    private WebResponse move(String userId, Item item, Folder destination) throws Exception {
        return ApproverFormFixtures.post(j, userId, item.getUrl() + "move/move",
                List.of(new NameValuePair("destination", "/" + destination.getFullName())));
    }

    private int postDelete(String userId, Item item) throws Exception {
        JenkinsRule.WebClient wc = client(j, userId);
        WebRequest request = new WebRequest(wc.createCrumbedUrl(item.getUrl() + "doDelete"), HttpMethod.POST);
        return wc.getPage(request).getWebResponse().getStatusCode();
    }

    private void openWindow(String scopeType, String folder, List<String> actions, String pattern) throws Exception {
        String id = submitGrantOk(j, "u1", scopeType, folder, actions, 30, "folder-only maintenance", pattern, "a1");
        assertSuccess(decideGrant(j, "a1", id, "approve", "ok"), "fixture: approval by a1");
        assertTrue(GrantService.get().listActive().stream().anyMatch(g -> "u1".equals(g.getUser())),
                "fixture: u1 must hold an active window");
    }

    private static boolean can(String user, Item item, hudson.security.Permission p) {
        return item.getACL().hasPermission2(User.getById(user, true).impersonate2(), p);
    }

    private int postConfigXml(String userId, FreeStyleProject target, String newDescription) throws Exception {
        JenkinsRule.WebClient wc = client(j, userId);
        String xml = target.getConfigFile().asString()
                .replace("<description>" + target.getDescription() + "</description>",
                        "<description>" + newDescription + "</description>");
        WebRequest request = new WebRequest(wc.createCrumbedUrl(target.getUrl() + "config.xml"), HttpMethod.POST);
        request.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        request.setRequestBody(xml);
        return wc.getPage(request).getWebResponse().getStatusCode();
    }

    private int createItem(String userId, String containerUrl, String name) throws Exception {
        JenkinsRule.WebClient wc = client(j, userId);
        URL url = new URL(wc.createCrumbedUrl(containerUrl + "createItem").toExternalForm()
                + "&name=" + URLEncoder.encode(name, StandardCharsets.UTF_8));
        WebRequest request = new WebRequest(url, HttpMethod.POST);
        request.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        request.setRequestBody(MINIMAL_JOB_XML);
        WebResponse response = wc.getPage(request).getWebResponse();
        return response.getStatusCode();
    }
}
