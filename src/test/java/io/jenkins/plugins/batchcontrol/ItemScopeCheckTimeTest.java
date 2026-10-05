package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Items;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import java.util.Arrays;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.client;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 8 (D-71), spec-review-S6 M-2: "DELETE applies only to a job ...; it never applies to
 * an item group that is not a job, so no window lets its holder delete a folder" and "CREATE
 * applies only to a modifiable item group (a regular folder; not a job, not a computed folder)".
 * The submission rows (T-08-104/108/110/113) check the kind when a request is made; these rows
 * check what an approved window confers once its item has been replaced by an item of another
 * kind, so a DELETE window never comes to delete a folder (whose children core deletes as SYSTEM)
 * and a CREATE window never comes to create inside a job or a computed folder. D-71a's identity
 * binding gives the same answer; the rows assert the result only. Matrix rows T-08-141, T-08-142
 * (note 262).
 *
 * <p>Batch Control matrix strategy, change control on; u1 holds Overall/Read, Item/Read and
 * RequestGrant only, a1 approves. The replacements are made by the administrator (SYSTEM) in the
 * fixture.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-71/D-71a, docs/reports/spec-review-S6.md and
 * docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class ItemScopeCheckTimeTest {

    private JenkinsRule j;
    private Folder team;

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

        team = j.jenkins.createProject(Folder.class, "team");
    }

    /**
     * T-08-141 (M-2, DELETE): u1's DELETE windows on the jobs {@code team/x} and {@code team/y}.
     * The administrator deletes {@code team/x} and creates a folder {@code team/x} holding
     * {@code team/x/child}; deletes {@code team/y} and moves the folder {@code other/y} (holding
     * {@code other/y/child}) into {@code team}. u1 holds no Delete on either folder, the
     * {@code doDelete} of each is refused with 4xx, and both folders and their children survive.
     * Guard: before the replacements both windows conferred Delete on their jobs.
     */
    @Test
    public void t_08_141_deleteWindowDoesNotApplyToAFolderThatReplacedItsJob() throws Exception {
        FreeStyleProject jobX = team.createProject(FreeStyleProject.class, "x");
        FreeStyleProject jobY = team.createProject(FreeStyleProject.class, "y");
        Folder other = j.jenkins.createProject(Folder.class, "other");
        Folder otherY = other.createProject(Folder.class, "y");
        otherY.createProject(FreeStyleProject.class, "child");
        openWindow("team/x", "DELETE");
        openWindow("team/y", "DELETE");
        assertTrue(can("u1", jobX, Item.DELETE), "guard: before the replacement the window confers Delete on the job team/x");
        assertTrue(can("u1", jobY, Item.DELETE), "guard: before the replacement the window confers Delete on the job team/y");

        Folder createdX;
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // fixture: the administrator replaces the jobs by folders
            jobX.delete();
            createdX = team.createProject(Folder.class, "x");
            createdX.createProject(FreeStyleProject.class, "child");
            jobY.delete();
            Items.move(otherY, team);
        }
        Folder movedY = j.jenkins.getItemByFullName("team/y", Folder.class);
        assertNotNull(movedY, "premise: the folder other/y is now team/y");
        assertNotNull(j.jenkins.getItemByFullName("team/y/child"), "premise: its child came along");

        for (Folder folder : new Folder[] {createdX, movedY}) {
            assertFalse(can("u1", folder, Item.DELETE), "D-71: a DELETE window must not confer Delete on the folder " + folder.getFullName());
            int code = postDelete("u1", folder);
            assertTrue(code >= 400 && code < 500, "deleting the folder " + folder.getFullName() + " must be refused, got HTTP " + code);
        }
        for (String fullName : new String[] {"team/x", "team/x/child", "team/y", "team/y/child"}) {
            assertNotNull(j.jenkins.getItemByFullName(fullName), fullName + " must survive");
        }
    }

    /**
     * T-08-142 (M-2, CREATE): u1's CREATE windows on the folders {@code team/f} and {@code team/g}.
     * The administrator replaces {@code team/f} by a Freestyle job and {@code team/g} by a
     * multibranch project. u1 holds no Create on either (measured on the item's ACL: neither kind
     * offers a creation endpoint, branch-api's {@code createItem} answers 500 to everyone). Guards:
     * before the replacements both windows conferred Create in their folders; the administrator
     * holds Create on the multibranch project's ACL.
     */
    @Test
    public void t_08_142_createWindowDoesNotApplyToAJobOrComputedFolderThatReplacedItsFolder() throws Exception {
        Folder f = team.createProject(Folder.class, "f");
        Folder g = team.createProject(Folder.class, "g");
        openWindow("team/f", "CREATE");
        openWindow("team/g", "CREATE");
        assertTrue(can("u1", f, Item.CREATE), "guard: before the replacement the window confers Create in team/f");
        assertTrue(can("u1", g, Item.CREATE), "guard: before the replacement the window confers Create in team/g");

        FreeStyleProject jobF;
        WorkflowMultiBranchProject mbG;
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // fixture: the administrator replaces the folders
            f.delete();
            jobF = team.createProject(FreeStyleProject.class, "f");
            g.delete();
            mbG = team.createProject(WorkflowMultiBranchProject.class, "g");
        }
        assertEquals("team/f", jobF.getFullName(), "premise: a job now carries the window's name");
        assertEquals("team/g", mbG.getFullName(), "premise: a multibranch project now carries the window's name");

        assertFalse(can("u1", jobF, Item.CREATE), "D-71: a CREATE window must not answer Create on a job");
        assertFalse(can("u1", mbG, Item.CREATE), "D-71: a CREATE window must not answer Create on a computed folder");
        // No HTTP creation is attempted: neither a job nor a multibranch project offers one (branch-api's
        // createItem answers 500 UnsupportedOperationException to everyone, the administrator included),
        // so only the ACL that core and plugins consult measures the window here (note 262).
        assertTrue(can("admin", mbG, Item.CREATE), "guard: the ACL itself answers Create on the multibranch project for the administrator");
    }

    // ---------------------------------------------------------------- helpers

    private void openWindow(String fullName, String... actions) throws Exception {
        long before = GrantService.get().listActive().stream().filter(g -> "u1".equals(g.getUser())).count();
        String id = submitGrantOk(j, "u1", fullName, Arrays.asList(actions), 30, "maintenance of " + fullName, null, "a1");
        assertSuccess(decideGrant(j, "a1", id, "approve", "ok"), "fixture: approval by a1");
        long after = GrantService.get().listActive().stream().filter(g -> "u1".equals(g.getUser())).count();
        assertEquals(before + 1, after, "fixture: u1 must hold one more active window");
    }

    private static boolean can(String user, Item item, hudson.security.Permission p) {
        return item.getACL().hasPermission2(User.getById(user, true).impersonate2(), p);
    }

    private int postDelete(String userId, Item item) throws Exception {
        JenkinsRule.WebClient wc = client(j, userId);
        WebRequest request = new WebRequest(wc.createCrumbedUrl(item.getUrl() + "doDelete"), HttpMethod.POST);
        return wc.getPage(request).getWebResponse().getStatusCode();
    }
}
