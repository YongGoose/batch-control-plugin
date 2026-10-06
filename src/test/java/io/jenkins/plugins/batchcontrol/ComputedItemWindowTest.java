package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.cloudbees.hudson.plugins.folder.relocate.RelocationAction;
import hudson.matrix.AxisList;
import hudson.matrix.MatrixProject;
import hudson.matrix.TextAxis;
import hudson.model.AbstractItem;
import hudson.model.Item;
import hudson.model.TopLevelItem;
import hudson.model.User;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import java.util.Arrays;
import java.util.List;
import jenkins.branch.OrganizationFolder;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.html.HtmlPage;
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
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 8 for the item kinds beyond Freestyle, Pipeline and Folder: a CONFIGURE window covers
 * "that item only; for a folder that is the folder's own configuration" (D-71) — a multibranch
 * project, an organization folder and a multi-configuration project included; "because a window's
 * DELETE never applies to an item group (D-71), moving a folder, multibranch project or
 * organization folder cannot be authorised through permission windows" (D-59), while a
 * multi-configuration project is a job; and "while change control is on, no window allows
 * renaming any item (job or folder of any kind) ... a refused rename ... is recorded as
 * GRANT_VIOLATION" (D-71c). Coverage inventory G-M7 and G-M14; matrix rows T-08-169 .. T-08-173
 * (note 269).
 *
 * <p>Batch Control matrix strategy (D-35a), change control on; u1 holds Overall/Read, Item/Read,
 * RequestGrant and native Item/Move (so the move rows measure the Delete/Create checks), a1
 * approves. Items: multibranch project {@code mb}, organization folder {@code org},
 * multi-configuration project {@code mx} (axis X = a, b), regular folder {@code dest}. Each item's
 * configuration carries the description {@code base}; saves go through {@code config.xml} POSTs.
 *
 * <p>Written from docs/SPEC.md item 8, docs/DECISIONS.md D-59, D-71, D-71c and D-73 and
 * docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class ComputedItemWindowTest {

    private JenkinsRule j;
    private WorkflowMultiBranchProject mb;
    private OrganizationFolder org;
    private MatrixProject mx;
    private Folder dest;

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
        strategy.add(RelocationAction.RELOCATE, PermissionEntry.user("u1"));
        j.jenkins.setAuthorizationStrategy(strategy);
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        mb = j.jenkins.createProject(WorkflowMultiBranchProject.class, "mb");
        mb.setDescription("base");
        org = j.jenkins.createProject(OrganizationFolder.class, "org");
        org.setDescription("base");
        mx = j.jenkins.createProject(MatrixProject.class, "mx");
        mx.setAxes(new AxisList(new TextAxis("X", "a", "b")));
        mx.setDescription("base");
        dest = j.jenkins.createProject(Folder.class, "dest");
        for (Item item : new Item[] {mb, org, mx, dest}) {
            assertFalse(can("u1", item, Item.CONFIGURE), "fixture: u1 holds no Configure of their own on " + item.getFullName());
        }
        assertNotNull(mx.getItem("X=a"), "fixture: mx has the configuration X=a");
    }

    /**
     * T-08-169 (G-M7): a CONFIGURE window on the multibranch project {@code mb} lets u1 open its
     * configure page and save its configuration ({@code config.xml}, 200, saved). Guard: before
     * the window the same save answers 403 and changes nothing.
     */
    @Test
    public void t_08_169_configureWindowConfersOnAMultibranchProject() throws Exception {
        assertConfigureWindowConfers(mb);
    }

    /** T-08-170 (G-M7): the same for the organization folder {@code org}. */
    @Test
    public void t_08_170_configureWindowConfersOnAnOrganizationFolder() throws Exception {
        assertConfigureWindowConfers(org);
    }

    /**
     * T-08-171 (G-M7): the same for the multi-configuration project {@code mx}; the window confers
     * no Configure on its configuration {@code mx/X=a}, which is another item.
     */
    @Test
    public void t_08_171_configureWindowConfersOnAMatrixProjectOnly() throws Exception {
        assertConfigureWindowConfers(mx);
        assertFalse(can("u1", mx.getItem("X=a"), Item.CONFIGURE), "the window on mx confers nothing on its configuration mx/X=a");
    }

    /**
     * T-08-172 (G-M14): u1 holds native Move, a CREATE window on {@code dest} and a CONFIGURE window
     * on {@code mb} and on {@code org} (a DELETE window on them cannot be requested, T-08-110).
     * Moving {@code mb} and {@code org} into {@code dest} is refused with 4xx: nothing moves and
     * each refusal is recorded as GRANT_VIOLATION naming u1 and the item. Guard: the
     * multi-configuration project {@code mx} is a job; with a DELETE window on it, moving it into
     * {@code dest} succeeds.
     */
    @Test
    public void t_08_172_computedFoldersCannotBeMovedThroughWindowsButAMatrixProjectCan() throws Exception {
        openWindow("dest", List.of("CREATE"));
        openWindow("mb", List.of("CONFIGURE"));
        openWindow("org", List.of("CONFIGURE"));

        for (TopLevelItem item : new TopLevelItem[] {mb, org}) {
            int before = violations(item.getFullName()).size();
            assertClientError(move("u1", item, dest), "u1 moving " + item.getFullName() + " into dest");
            assertNotNull(j.jenkins.getItemByFullName(item.getFullName()), item.getFullName() + " must stay at the root");
            assertNull(j.jenkins.getItemByFullName("dest/" + item.getName()), "nothing may arrive in dest");
            assertEquals(before + 1, violations(item.getFullName()).size(), "the refused move of " + item.getFullName()
                    + " must be recorded as GRANT_VIOLATION naming u1");
        }

        openWindow("mx", List.of("DELETE"));
        assertSuccess(move("u1", mx, dest), "guard: u1 moving the multi-configuration project mx into dest");
        assertNotNull(j.jenkins.getItemByFullName("dest/mx"), "mx must arrive in dest");
        assertNull(j.jenkins.getItemByFullName("mx"));
    }

    /**
     * T-08-173 (G-M14, D-71c): with a CONFIGURE window on each of {@code mb}, {@code org} and
     * {@code mx}, u1's {@code confirmRename} of each answers 400 with the D-71c refusal naming the
     * item and its kind and "Nothing was renamed."; every item keeps its name, nothing exists at
     * the new names, and each attempt is recorded as GRANT_VIOLATION naming u1 and the item. Guard:
     * the windows still confer Configure on each item after the refusals.
     */
    @Test
    public void t_08_173_renamingComputedFoldersAndAMatrixProjectThroughAWindowIsRefused() throws Exception {
        for (TopLevelItem item : new TopLevelItem[] {mb, org, mx}) {
            openWindow(item.getFullName(), List.of("CONFIGURE"));
            int before = violations(item.getFullName()).size();
            WebResponse response = ApproverFormFixtures.post(j, "u1", item.getUrl() + "confirmRename",
                    List.of(new NameValuePair("newName", item.getName() + "-renamed")));
            RenameRefusalFixtures.assertWindowRenameRefused(response, item.getFullName(), item.getDescriptor().getDisplayName(),
                    "u1 renaming " + item.getFullName());
            assertNotNull(j.jenkins.getItemByFullName(item.getFullName()), item.getFullName() + " must keep its name");
            assertNull(j.jenkins.getItemByFullName(item.getName() + "-renamed"), "nothing may exist at the new name");
            assertEquals(before + 1, violations(item.getFullName()).size(), "the refused rename of " + item.getFullName()
                    + " must be recorded once as GRANT_VIOLATION naming u1");
            assertTrue(can("u1", item, Item.CONFIGURE), "guard: the window still confers Configure on " + item.getFullName());
        }
    }

    // ---------------------------------------------------------------- helpers

    private void assertConfigureWindowConfers(AbstractItem item) throws Exception {
        assertEquals(403, postConfig("u1", item, "before-window"), "guard: without a window u1 must not save " + item.getFullName());
        assertEquals("base", reload(item).getDescription());

        openWindow(item.getFullName(), List.of("CONFIGURE"));
        HtmlPage configure = UsabilityFixtures.htmlPage(j, "u1", item.getUrl() + "configure");
        assertEquals(200, configure.getWebResponse().getStatusCode(), "u1 must open the configure page of " + item.getFullName());
        assertFalse(UsabilityFixtures.formsEndingWith(configure, item.getUrl() + "configSubmit").isEmpty(),
                "the configure page of " + item.getFullName() + " must carry its configuration form; forms: "
                        + UsabilityFixtures.formActions(configure));
        assertEquals(200, postConfig("u1", item, "changed-by-u1"), "u1 must save the configuration of " + item.getFullName());
        assertEquals("changed-by-u1", reload(item).getDescription(), "the save of " + item.getFullName() + " must be kept");
    }

    private void openWindow(String fullName, List<String> actions) throws Exception {
        long before = GrantService.get().listActive().stream().filter(g -> "u1".equals(g.getUser())).count();
        String id = submitGrantOk(j, "u1", fullName, actions, 30, "maintenance of " + fullName, null, "a1");
        assertSuccess(decideGrant(j, "a1", id, "approve", "ok"), "fixture: approval by a1");
        long after = GrantService.get().listActive().stream().filter(g -> "u1".equals(g.getUser())).count();
        assertEquals(before + 1, after, "fixture: u1 must hold one more active window");
    }

    private static boolean can(String user, Item item, hudson.security.Permission p) {
        return item.getACL().hasPermission2(User.getById(user, true).impersonate2(), p);
    }

    private int postConfig(String userId, AbstractItem target, String description) throws Exception {
        String xml = target.getConfigFile().asString();
        assertTrue(xml.contains("<description>" + target.getDescription() + "</description>"),
                "fixture: the configuration of " + target.getFullName() + " must carry its description");
        String changed = xml.replace("<description>" + target.getDescription() + "</description>",
                "<description>" + description + "</description>");
        JenkinsRule.WebClient wc = client(j, userId);
        WebRequest request = new WebRequest(wc.createCrumbedUrl(target.getUrl() + "config.xml"), HttpMethod.POST);
        request.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        request.setRequestBody(changed);
        return wc.getPage(request).getWebResponse().getStatusCode();
    }

    private AbstractItem reload(AbstractItem item) {
        return j.jenkins.getItemByFullName(item.getFullName(), AbstractItem.class);
    }

    private WebResponse move(String userId, Item item, Folder destination) throws Exception {
        return ApproverFormFixtures.post(j, userId, item.getUrl() + "move/move",
                List.of(new NameValuePair("destination", "/" + destination.getFullName())));
    }

    private static List<ChangeRecord> violations(String fullName) {
        return ApproverFormFixtures.records(ChangeType.GRANT_VIOLATION).stream()
                .filter(r -> "u1".equals(r.getUser()))
                .filter(r -> String.valueOf(r.getTarget()).contains(fullName) || String.valueOf(r.getDetail()).contains(fullName))
                .toList();
    }
}
