package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.cloudbees.hudson.plugins.folder.relocate.RelocationAction;
import hudson.model.AbstractItem;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
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
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 8 (D-71, replaces D-65): a permission window names exactly one item, a job or a
 * folder of any kind (scope type ITEM), and confers nothing on any other item, including the
 * items inside a folder. CONFIGURE (with the EXTENDED_READ that comes with it, P-11) covers that
 * item only; CREATE applies to a regular folder and creates directly inside it only; the D-35c
 * Configure covers what the holder created there; DELETE applies only to a job; a folder cannot
 * be moved through windows (D-59). Matrix rows T-08-100 .. T-08-103, T-08-107, T-08-109,
 * T-08-111, T-08-112 (note 260); submission rows are in {@link ItemScopeSubmissionTest}, the
 * restart rows in {@link ItemScopeRestartTest}.
 *
 * <p>Layout: folder {@code ops} with the jobs {@code ops/a} and {@code ops/c} and the nested
 * folder {@code ops/sub} holding the job {@code ops/sub/b}. u1 holds Overall/Read, Item/Read,
 * RequestGrant and native Item/Move only, under the Batch Control matrix strategy (D-35a) so a
 * window actually confers. Windows are requested through the frozen form contract
 * ({@code POST batch-control/grants/create} with {@code scopeFullName}, no {@code scopeType}) and
 * approved by a1. Every positive assertion has a refusal next to it and vice versa, so no row can
 * pass because windows never confer or always confer.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-71, docs/ARCHITECTURE.md section 2 and
 * docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class ItemScopeTest {

    static final String MINIMAL_JOB_XML = "<?xml version='1.1' encoding='UTF-8'?>"
            + "<project><builders/><publishers/><buildWrappers/></project>";

    private JenkinsRule j;
    private Folder ops;
    private Folder sub;
    private FreeStyleProject a;
    private FreeStyleProject b;
    private FreeStyleProject c;

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
        ops.setDescription("base");
        a = ops.createProject(FreeStyleProject.class, "a");
        a.setDescription("base");
        c = ops.createProject(FreeStyleProject.class, "c");
        c.setDescription("base");
        sub = ops.createProject(Folder.class, "sub");
        sub.setDescription("base");
        b = sub.createProject(FreeStyleProject.class, "b");
        b.setDescription("base");

        for (Item item : new Item[] {ops, a, c, sub, b}) {
            assertFalse(can("u1", item, Item.CONFIGURE), "fixture: u1 holds no Configure of their own on " + item.getFullName());
            assertFalse(can("u1", item, Item.DELETE), "fixture: u1 holds no Delete of their own on " + item.getFullName());
        }
        assertFalse(can("u1", ops, Item.CREATE), "fixture: u1 holds no Create of their own");
    }

    /**
     * T-08-100 (rewritten for D-71; was the D-65 FOLDER_ONLY CONFIGURE row): a CONFIGURE window on
     * the folder {@code ops} lets u1 open the folder's configure page and save the folder, and
     * confers nothing on any item inside it: not the job {@code ops/a}, not the nested folder
     * {@code ops/sub} (both covered under D-65) and not {@code ops/sub/b}. The EXTENDED_READ that
     * comes with CONFIGURE (P-11) stays on the folder too.
     */
    @Test
    @Tag("core")
    public void t_08_100_configureWindowOnAFolderCoversTheFolderOnly() throws Exception {
        openWindow("ops", List.of("CONFIGURE"), null);

        HtmlPage configure = UsabilityFixtures.htmlPage(j, "u1", ops.getUrl() + "configure");
        assertEquals(200, configure.getWebResponse().getStatusCode(), "u1 must open the folder's own configure page");
        assertFalse(UsabilityFixtures.formsEndingWith(configure, ops.getUrl() + "configSubmit").isEmpty(),
                "the folder's configure page must carry its configuration form; forms: " + UsabilityFixtures.formActions(configure));
        assertEquals(200, postConfigXml("u1", ops, "changed-ops"), "u1 must be able to save the folder itself");
        assertEquals("changed-ops", reload(ops).getDescription());
        assertTrue(can("u1", ops, Item.EXTENDED_READ), "P-11: the window's EXTENDED_READ covers the folder");

        for (AbstractItem inside : new AbstractItem[] {a, sub, b}) {
            assertFalse(can("u1", inside, Item.CONFIGURE), "D-71: a window on ops must not confer Configure on " + inside.getFullName());
            assertFalse(can("u1", inside, Item.EXTENDED_READ), "D-71: nor EXTENDED_READ on " + inside.getFullName());
            assertEquals(403, postConfigXml("u1", inside, "changed-inside"), "saving " + inside.getFullName() + " must be refused");
            assertEquals("base", reload(inside).getDescription(), inside.getFullName() + " must be unchanged");
        }
    }

    /**
     * T-08-101 (rewritten for D-71; was the D-65 guard "a FOLDER window covers nested items"): a
     * CONFIGURE window on the job {@code ops/a} lets u1 save that job, and confers nothing on the
     * sibling job {@code ops/c} or on the folder {@code ops}.
     */
    @Test
    public void t_08_101_configureWindowOnAJobCoversThatJobOnly() throws Exception {
        openWindow("ops/a", List.of("CONFIGURE"), null);

        assertTrue(can("u1", a, Item.CONFIGURE), "the window's job must be configurable");
        assertEquals(200, postConfigXml("u1", a, "changed-a"), "u1 must be able to save ops/a");
        assertEquals("changed-a", reload(a).getDescription());

        assertFalse(can("u1", c, Item.CONFIGURE), "a sibling job must not be configurable");
        assertEquals(403, postConfigXml("u1", c, "changed-c"), "saving the sibling ops/c must be refused");
        assertEquals("base", reload(c).getDescription());
        assertFalse(can("u1", ops, Item.CONFIGURE), "a window on a job confers nothing on its folder");
        assertEquals(403, postConfigXml("u1", ops, "changed-ops"), "saving the folder ops must be refused");
        assertEquals("base", reload(ops).getDescription());
    }

    /**
     * T-08-102 (rewritten for D-71; same rule as the D-65 FOLDER_ONLY row): a CREATE window on the
     * folder {@code ops} lets u1 create directly in {@code ops}, but neither in the nested folder
     * {@code ops/sub} nor at the root.
     */
    @Test
    @Tag("core")
    public void t_08_102_createWindowCreatesDirectlyInTheFolderOnly() throws Exception {
        openWindow("ops", List.of("CREATE"), null);

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

    /**
     * T-08-103 (rewritten for D-71): a CREATE window on {@code ops} with the name restriction
     * {@code /nightly-[a-z]+/} (D-40) creates a matching name directly in {@code ops}; another name
     * in {@code ops} and a matching name in the nested folder {@code ops/sub} are refused.
     */
    @Test
    public void t_08_103_nameRestrictionAppliesToTheCreateWindow() throws Exception {
        openWindow("ops", List.of("CREATE"), "/nightly-[a-z]+/");

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
     * T-08-107 (rewritten for D-71; was the D-65 FOLDER_ONLY DELETE row): the holder of a
     * [CREATE, CONFIGURE] window on the folder {@code ops} cannot delete the folder or anything in
     * it (the delete veto): {@code ops}, {@code ops/a}, {@code ops/sub} and {@code ops/sub/b} are
     * refused and all still exist. Guard: a DELETE window on the job {@code ops/a} deletes it.
     */
    @Test
    public void t_08_107_folderWindowHolderCannotDeleteTheFolderOrAChild() throws Exception {
        openWindow("ops", List.of("CREATE", "CONFIGURE"), null);

        for (Item target : new Item[] {b, sub, a, ops}) {
            assertFalse(can("u1", target, Item.DELETE), "a CREATE/CONFIGURE window must not confer Delete on " + target.getFullName());
            int code = postDelete("u1", target);
            assertTrue(code >= 400 && code < 500, "deleting " + target.getFullName() + " must be refused, got HTTP " + code);
        }
        for (String fullName : new String[] {"ops", "ops/a", "ops/sub", "ops/sub/b"}) {
            assertNotNull(j.jenkins.getItemByFullName(fullName), fullName + " must still exist");
        }

        openWindow("ops/a", List.of("DELETE"), null);
        int allowed = postDelete("u1", a);
        assertTrue(allowed < 400, "guard: a DELETE window on ops/a deletes it, got HTTP " + allowed);
        assertNull(j.jenkins.getItemByFullName("ops/a"));
    }

    /**
     * T-08-109 (rewritten for D-71; was the D-65 "not the folder itself" row): a DELETE window on
     * the job {@code ops/a} deletes that job; the sibling job {@code ops/c} and the folder
     * {@code ops} are refused and stay.
     */
    @Test
    public void t_08_109_deleteWindowOnAJobDeletesThatJobOnly() throws Exception {
        openWindow("ops/a", List.of("DELETE"), null);

        assertFalse(can("u1", c, Item.DELETE), "a sibling job must not be deletable");
        int sibling = postDelete("u1", c);
        assertTrue(sibling >= 400 && sibling < 500, "deleting ops/c must be refused, got HTTP " + sibling);
        assertNotNull(j.jenkins.getItemByFullName("ops/c"));
        assertFalse(can("u1", ops, Item.DELETE), "the folder must not be deletable");
        int folder = postDelete("u1", ops);
        assertTrue(folder >= 400 && folder < 500, "deleting ops must be refused, got HTTP " + folder);
        assertNotNull(j.jenkins.getItemByFullName("ops"));

        assertTrue(can("u1", a, Item.DELETE), "the window's job must be deletable");
        int direct = postDelete("u1", a);
        assertTrue(direct < 400, "deleting ops/a must succeed, got HTTP " + direct);
        assertNull(j.jenkins.getItemByFullName("ops/a"), "ops/a must be deleted");
    }

    /**
     * T-08-111 (rewritten for D-71, D-59; was the D-65 nested-folder move row): u1 holds native
     * Move and a CREATE window on {@code dest}. A DELETE window on the folder {@code ops/sub}
     * cannot be requested, so moving {@code ops/sub} to {@code dest} is refused and nothing moves.
     * Guard: with a DELETE window on the job {@code ops/a}, moving {@code ops/a} to {@code dest}
     * succeeds (Delete at the source, Create at the destination, two windows).
     */
    @Test
    public void t_08_111_folderCannotBeMovedThroughWindowsButAJobCan() throws Exception {
        Folder dest = j.jenkins.createProject(Folder.class, "dest");
        openWindow("dest", List.of("CREATE"), null);

        int before = grantRequestIds().size();
        assertClientError(submitGrant(j, "u1", "ops/sub", List.of("DELETE"), 30, "move the folder", null, "a1"),
                "a DELETE window on the folder ops/sub");
        assertEquals(before, grantRequestIds().size(), "the refused request must not be stored");

        assertClientError(move("u1", sub, dest), "u1 moving the folder ops/sub to dest");
        assertNotNull(j.jenkins.getItemByFullName("ops/sub"), "ops/sub must stay in place");
        assertNotNull(j.jenkins.getItemByFullName("ops/sub/b"), "ops/sub/b must stay in place");
        assertNull(j.jenkins.getItemByFullName("dest/sub"), "nothing may arrive in dest");

        openWindow("ops/a", List.of("DELETE"), null);
        assertSuccess(move("u1", a, dest), "guard: u1 moving the job ops/a to dest");
        assertNotNull(j.jenkins.getItemByFullName("dest/a"), "ops/a must arrive in dest");
        assertNull(j.jenkins.getItemByFullName("ops/a"));
    }

    /**
     * T-08-112 (D-71, amends D-35c): a CREATE window on {@code ops}. u1 creates the job
     * {@code ops/made-here} and the folder {@code ops/made-folder}; during the window u1 configures
     * both (D-35c: the created item's parent is the window's folder), and nothing else: no Delete
     * on either, no Create inside the folder u1 created (never in a nested folder), no Configure on
     * {@code ops} itself, on the pre-existing {@code ops/a} or on {@code ops/by-admin}, created in
     * {@code ops} by the administrator. (u1's Item/Read is native in this fixture, so Read is not
     * measured here.)
     */
    @Test
    public void t_08_112_createdItemGetsConfigureAndNothingElse() throws Exception {
        openWindow("ops", List.of("CREATE"), null);

        assertTrue(createItem("u1", ops.getUrl(), "made-here") < 400, "fixture: u1 creates ops/made-here");
        assertSuccess(ApproverFormFixtures.post(j, "u1", ops.getUrl() + "createItem", List.of(
                new NameValuePair("name", "made-folder"),
                new NameValuePair("mode", "com.cloudbees.hudson.plugins.folder.Folder"))), "fixture: u1 creates the folder ops/made-folder");
        FreeStyleProject madeHere = j.jenkins.getItemByFullName("ops/made-here", FreeStyleProject.class);
        Folder madeFolder = j.jenkins.getItemByFullName("ops/made-folder", Folder.class);
        assertNotNull(madeHere);
        assertNotNull(madeFolder);
        FreeStyleProject byAdmin = ops.createProject(FreeStyleProject.class, "by-admin");

        assertTrue(can("u1", madeHere, Item.CONFIGURE), "D-35c: u1 configures the job they created");
        assertTrue(can("u1", madeFolder, Item.CONFIGURE), "D-35c: u1 configures the folder they created");

        assertFalse(can("u1", madeHere, Item.DELETE), "D-35c confers Read/Configure only, not Delete");
        assertFalse(can("u1", madeFolder, Item.DELETE), "D-35c confers Read/Configure only, not Delete");
        assertFalse(can("u1", madeFolder, Item.CREATE), "D-71: CREATE never applies inside a nested folder, even one u1 created");
        int nested = createItem("u1", madeFolder.getUrl(), "deeper");
        assertTrue(nested >= 400 && nested < 500, "creating inside ops/made-folder must be refused, got HTTP " + nested);
        assertNull(j.jenkins.getItemByFullName("ops/made-folder/deeper"));

        assertFalse(can("u1", ops, Item.CONFIGURE), "a CREATE window confers no Configure on its folder");
        assertFalse(can("u1", a, Item.CONFIGURE), "nor on an item u1 did not create");
        assertFalse(can("u1", byAdmin, Item.CONFIGURE), "nor on an item someone else created in the folder");
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

    /** Files a window on the one item {@code fullName} through the form as u1; a1 approves it. */
    private void openWindow(String fullName, List<String> actions, String pattern) throws Exception {
        long before = GrantService.get().listActive().stream().filter(g -> "u1".equals(g.getUser())).count();
        String id = submitGrantOk(j, "u1", fullName, actions, 30, "maintenance of " + fullName, pattern, "a1");
        assertSuccess(decideGrant(j, "a1", id, "approve", "ok"), "fixture: approval by a1");
        long after = GrantService.get().listActive().stream().filter(g -> "u1".equals(g.getUser())).count();
        assertEquals(before + 1, after, "fixture: u1 must hold one more active window");
    }

    static boolean can(String user, Item item, hudson.security.Permission p) {
        return item.getACL().hasPermission2(User.getById(user, true).impersonate2(), p);
    }

    private AbstractItem reload(AbstractItem item) {
        return j.jenkins.getItemByFullName(item.getFullName(), AbstractItem.class);
    }

    /** POSTs the item's config.xml (job or folder) with its description swapped; returns the HTTP status. */
    private int postConfigXml(String userId, AbstractItem target, String newDescription) throws Exception {
        JenkinsRule.WebClient wc = client(j, userId);
        String xml = target.getConfigFile().asString()
                .replace("<description>" + target.getDescription() + "</description>",
                        "<description>" + newDescription + "</description>");
        assertTrue(xml.contains(newDescription), "fixture: " + target.getFullName() + "'s config.xml must carry its description");
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
        return wc.getPage(request).getWebResponse().getStatusCode();
    }
}
