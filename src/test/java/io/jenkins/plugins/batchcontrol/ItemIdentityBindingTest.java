package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.AbstractItem;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import java.util.Arrays;
import java.util.List;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertClientError;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.client;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.records;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 8 (D-71a, security-34 S-34-01/03/04, spec-review-S6 m-1): "a window confers something
 * only on the very item it was approved for: the grant records the item's identity at approval
 * and matches only when both the full name and the identity match, so renaming, moving, swapping
 * or re-creating items never makes a window (or several windows combined) reach a different item;
 * while change control is on, renaming an item group (folder, multibranch project, organization
 * folder) whose Configure comes only from a window is refused and recorded as GRANT_VIOLATION (the
 * same as a refused move, D-73 coalescing applies). The stored scope is the item's canonical full
 * name, whatever spelling was typed." And line 168: "Renaming follows core's rule: Configure on the
 * item (one CONFIGURE window on it), or else both Delete on it and Create in its parent." Matrix
 * rows T-08-130, T-08-131, T-08-133 .. T-08-137, T-08-146, T-08-147 (note 262); the role-strategy
 * variant of S-34-01 is T-08-132 ({@link ItemIdentityRoleStrategyTest}).
 *
 * <p>Layout (security-34 Probe A): folder {@code ops} with the job {@code ops/prod}, folder
 * {@code sandbox} with the job {@code sandbox/prod}, folder {@code dest}; every description is
 * "base". Batch Control matrix strategy, change control on. u1 and u2 hold Overall/Read, Item/Read
 * and RequestGrant only; a1 approves; c1 holds a standing Item/Configure (no window); admin holds
 * Overall/Administer. Windows are requested through the form contract and approved by a1. What a
 * window confers is measured on the item's own ACL and through HTTP saves, never through a
 * name-based service query, because a name alone is what D-71a stops trusting.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-71/D-71a/D-73, docs/reports/security-34.md,
 * docs/reports/spec-review-S6.md and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class ItemIdentityBindingTest {

    private JenkinsRule j;
    private Folder ops;
    private FreeStyleProject opsProd;
    private Folder sandbox;
    private FreeStyleProject sandboxProd;
    private Folder dest;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "u2", "a1", "c1"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u2"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        strategy.add(Item.CONFIGURE, PermissionEntry.user("c1")); // standing Configure, no window
        j.jenkins.setAuthorizationStrategy(strategy);

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        ops = j.jenkins.createProject(Folder.class, "ops");
        ops.setDescription("base");
        opsProd = ops.createProject(FreeStyleProject.class, "prod");
        opsProd.setDescription("base");
        sandbox = j.jenkins.createProject(Folder.class, "sandbox");
        sandbox.setDescription("base");
        sandboxProd = sandbox.createProject(FreeStyleProject.class, "prod");
        sandboxProd.setDescription("base");
        dest = j.jenkins.createProject(Folder.class, "dest");

        for (String userId : new String[] {"u1", "u2"}) {
            for (Item item : new Item[] {ops, opsProd, sandbox, sandboxProd, dest}) {
                assertFalse(can(userId, item, Item.CONFIGURE), "fixture: " + userId + " holds no Configure of their own on " + item.getFullName());
                assertFalse(can(userId, item, Item.DELETE), "fixture: " + userId + " holds no Delete of their own on " + item.getFullName());
            }
            assertFalse(can(userId, j.jenkins, Item.CREATE), "fixture: " + userId + " holds no Create in the root");
        }
    }

    /**
     * T-08-130 (S-34-01 Probe A, D-71a ruling 2): u1 holds approved CONFIGURE windows on the
     * folders {@code ops} and {@code sandbox} and on the job {@code sandbox/prod}, none of them on
     * {@code ops/prod}. u1's rename of the folder {@code sandbox} (the probe's first step, which
     * core allows on Configure) is refused with 4xx: no item is renamed, the job inside keeps its
     * name, and exactly one GRANT_VIOLATION naming u1 and {@code sandbox} is recorded. u1 never
     * gains Configure on {@code ops/prod} (its save is 403 and it keeps "base"). Guards, measured
     * after the refusal so that they cannot influence it: the three windows still confer what they
     * name (u1 saves {@code sandbox}, {@code sandbox/prod} and {@code ops}), so the refusal is
     * about the rename, not about windows that never confer.
     */
    @Test
    public void t_08_130_windowOnlyFolderRenameIsRefusedAndRecorded() throws Exception {
        openWindow("u1", "ops", "CONFIGURE");
        openWindow("u1", "sandbox", "CONFIGURE");
        openWindow("u1", "sandbox/prod", "CONFIGURE");
        assertTrue(can("u1", sandbox, Item.CONFIGURE), "premise: u1's Configure on the folder sandbox comes from the window");
        assertFalse(can("u1", opsProd, Item.CONFIGURE), "premise: no window names ops/prod");
        int violationsBefore = records(ChangeType.GRANT_VIOLATION).size();

        assertClientError(rename("u1", sandbox, "sandbox-old"), "u1 renaming the folder sandbox with Configure only from a window");
        assertNotNull(j.jenkins.getItemByFullName("sandbox"), "the refused rename must leave the folder sandbox in place");
        assertNull(j.jenkins.getItemByFullName("sandbox-old"), "no item may carry the new name");
        assertNotNull(j.jenkins.getItemByFullName("sandbox/prod"), "the job inside keeps its full name");
        assertEquals("sandbox", sandbox.getFullName(), "the folder keeps its full name");
        List<ChangeRecord> violations = records(ChangeType.GRANT_VIOLATION);
        assertEquals(violationsBefore + 1, violations.size(), "the refused rename is recorded once as GRANT_VIOLATION, got " + violations);
        ChangeRecord rec = violations.get(violations.size() - 1);
        assertEquals("u1", rec.getUser(), "the GRANT_VIOLATION names u1");
        assertTrue(mentions(rec, "sandbox"), "the GRANT_VIOLATION names the folder sandbox: " + describe(rec));

        assertFalse(can("u1", opsProd, Item.CONFIGURE), "u1 must never hold Configure on ops/prod");
        assertEquals(403, postConfigXml("u1", opsProd, "planted"), "u1's save of ops/prod must be refused");
        assertEquals("base", reload(opsProd).getDescription(), "ops/prod keeps its description");

        assertEquals(200, postConfigXml("u1", sandbox, "changed-sandbox"), "guard: the window on sandbox still confers Configure on it");
        assertEquals(200, postConfigXml("u1", sandboxProd, "changed-sandbox-prod"), "guard: the window on sandbox/prod still confers");
        assertEquals(200, postConfigXml("u1", ops, "changed-ops"), "guard: the window on ops still confers");
    }

    /**
     * T-08-131 (S-34-01, D-71a ruling 1, identity binding): the same three windows; the
     * administrator performs the probe's renames ({@code sandbox} to {@code sandbox-old}, then
     * {@code ops} to {@code sandbox}), so the job formerly {@code ops/prod} is now named
     * {@code sandbox/prod}, the name one of u1's windows carries. u1 holds no Configure on it, its
     * save is 403 and it keeps "base"; nor on the folder now named {@code sandbox} (formerly
     * {@code ops}, which u1's {@code sandbox} window names and u1's {@code ops} window was approved
     * for); nor on the renamed originals {@code sandbox-old} and {@code sandbox-old/prod} (a window
     * does not follow its item). Guard: before the renames u1 configured {@code sandbox/prod},
     * {@code sandbox} and {@code ops}, and not {@code ops/prod}.
     */
    @Test
    public void t_08_131_swappedNamesDoNotRepointWindows() throws Exception {
        openWindow("u1", "ops", "CONFIGURE");
        openWindow("u1", "sandbox", "CONFIGURE");
        openWindow("u1", "sandbox/prod", "CONFIGURE");
        assertTrue(can("u1", sandboxProd, Item.CONFIGURE), "guard: before the renames u1 configures sandbox/prod");
        assertTrue(can("u1", sandbox, Item.CONFIGURE), "guard: before the renames u1 configures sandbox");
        assertTrue(can("u1", ops, Item.CONFIGURE), "guard: before the renames u1 configures ops");
        assertFalse(can("u1", opsProd, Item.CONFIGURE), "guard: before the renames u1 does not configure ops/prod");

        assertSuccess(rename("admin", sandbox, "sandbox-old"), "fixture: the administrator renames sandbox to sandbox-old");
        assertSuccess(rename("admin", ops, "sandbox"), "fixture: the administrator renames ops to sandbox");
        assertEquals("sandbox/prod", opsProd.getFullName(), "premise: the job formerly ops/prod is now sandbox/prod");
        assertEquals("sandbox", ops.getFullName(), "premise: the folder formerly ops is now sandbox");
        assertEquals("sandbox-old/prod", sandboxProd.getFullName(), "premise: the original sandbox/prod is now sandbox-old/prod");

        assertFalse(can("u1", opsProd, Item.CONFIGURE), "D-71a: the window on the name sandbox/prod must not reach the job formerly ops/prod");
        assertFalse(can("u1", opsProd, Item.EXTENDED_READ), "nor its EXTENDED_READ");
        assertEquals(403, postConfigXml("u1", opsProd, "planted"), "u1's save of the job formerly ops/prod must be refused");
        assertEquals("base", reload(opsProd).getDescription(), "the job formerly ops/prod keeps its description");
        assertFalse(can("u1", ops, Item.CONFIGURE), "D-71a: no window reaches the folder now named sandbox (formerly ops)");
        assertFalse(can("u1", sandbox, Item.CONFIGURE), "D-71a: the window does not follow the folder renamed to sandbox-old");
        assertFalse(can("u1", sandboxProd, Item.CONFIGURE), "D-71a: the window does not follow the job now at sandbox-old/prod");
    }

    /**
     * T-08-133 (guards of T-08-130 and T-08-132): renaming a folder is not refused for someone whose
     * Configure is standing. The administrator renames {@code ops} to {@code ops-renamed} and c1
     * (standing Item/Configure, no window) renames {@code sandbox} to {@code sandbox-renamed}, both
     * through the same rename endpoint; both succeed, the jobs inside follow, and no GRANT_VIOLATION
     * is recorded.
     */
    @Test
    public void t_08_133_administratorAndStandingConfigureRenameAFolder() throws Exception {
        assertTrue(can("c1", sandbox, Item.CONFIGURE), "premise: c1's Configure on sandbox is standing");
        int violationsBefore = records(ChangeType.GRANT_VIOLATION).size();

        assertSuccess(rename("admin", ops, "ops-renamed"), "the administrator renaming the folder ops");
        assertNotNull(j.jenkins.getItemByFullName("ops-renamed/prod"), "the job inside follows the administrator's rename");
        assertNull(j.jenkins.getItemByFullName("ops"));

        assertSuccess(rename("c1", sandbox, "sandbox-renamed"), "c1 (standing Configure) renaming the folder sandbox");
        assertNotNull(j.jenkins.getItemByFullName("sandbox-renamed/prod"), "the job inside follows c1's rename");
        assertNull(j.jenkins.getItemByFullName("sandbox"));

        assertEquals(violationsBefore, records(ChangeType.GRANT_VIOLATION).size(), "a permitted folder rename records no violation");
    }

    /**
     * T-08-134 (SPEC 8 line 168 rename sentence, spec-review-S6 m-1, D-71a ruling 2 "Renaming a job
     * with a CONFIGURE window stays allowed"): u1's CONFIGURE window on the pre-existing job
     * {@code ops/prod} lets u1 rename it (core's Configure branch) to {@code ops/prod2}; no
     * GRANT_VIOLATION is recorded. Afterwards the window no longer applies to the renamed job (a
     * renamed item loses its window, D-71a): u1 holds no Configure on {@code ops/prod2} and its save
     * is 403. Guard: before the rename u1 configured the job. The folder half of m-1 is T-08-130.
     */
    @Test
    public void t_08_134_configureWindowOnAJobAllowsRenamingIt() throws Exception {
        openWindow("u1", "ops/prod", "CONFIGURE");
        assertTrue(can("u1", opsProd, Item.CONFIGURE), "guard: before the rename the window confers Configure on ops/prod");
        int violationsBefore = records(ChangeType.GRANT_VIOLATION).size();

        assertSuccess(rename("u1", opsProd, "prod2"), "u1 renaming the job ops/prod through its CONFIGURE window");
        assertNotNull(j.jenkins.getItemByFullName("ops/prod2"), "the job must carry the new name");
        assertNull(j.jenkins.getItemByFullName("ops/prod"), "the old name must be free");
        assertEquals(violationsBefore, records(ChangeType.GRANT_VIOLATION).size(), "the permitted job rename records no violation");

        assertFalse(can("u1", opsProd, Item.CONFIGURE), "D-71a: the window does not follow the job to ops/prod2");
        assertEquals(403, postConfigXml("u1", opsProd, "after-rename"), "u1's save of ops/prod2 must be refused");
        assertEquals("base", reload(opsProd).getDescription());
    }

    /**
     * T-08-135 (S-34-03, Probe B): u1's approved CONFIGURE window on the Freestyle job
     * {@code ops/x}. The administrator deletes it and creates a Folder {@code ops/x}. u1 holds no
     * Configure on the folder, its save is 403 and it keeps "base". Guard: before the deletion the
     * window conferred Configure on the job.
     */
    @Test
    public void t_08_135_windowDoesNotReachAFolderThatReplacedItsJob() throws Exception {
        FreeStyleProject x = ops.createProject(FreeStyleProject.class, "x");
        openWindow("u1", "ops/x", "CONFIGURE");
        assertTrue(can("u1", x, Item.CONFIGURE), "guard: before the deletion the window confers Configure on ops/x");

        Folder replacement;
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // fixture: the administrator replaces the job
            x.delete();
            replacement = ops.createProject(Folder.class, "x");
            replacement.setDescription("base");
        }
        assertEquals("ops/x", replacement.getFullName(), "premise: a folder now carries the window's name");

        assertFalse(can("u1", replacement, Item.CONFIGURE), "S-34-03: the window must not reach the folder that replaced its job");
        assertEquals(403, postConfigXml("u1", replacement, "planted"), "u1's save of the folder ops/x must be refused");
        assertEquals("base", reload(replacement).getDescription());
    }

    /**
     * T-08-136 (S-34-03, same-kind re-creation; D-71a "deletion and re-creation"): u1's approved
     * CONFIGURE window on the Freestyle job {@code ops/x}. The administrator deletes it and creates
     * a new Freestyle job {@code ops/x} (same name, same kind). u1 holds no Configure on the new
     * job, its save is 403 and it keeps "base". Guard: before the deletion the window conferred
     * Configure on the old job.
     */
    @Test
    public void t_08_136_windowDoesNotReachAJobRecreatedUnderItsName() throws Exception {
        FreeStyleProject x = ops.createProject(FreeStyleProject.class, "x");
        openWindow("u1", "ops/x", "CONFIGURE");
        assertTrue(can("u1", x, Item.CONFIGURE), "guard: before the deletion the window confers Configure on ops/x");

        FreeStyleProject recreated;
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // fixture: the administrator re-creates the job
            x.delete();
            recreated = ops.createProject(FreeStyleProject.class, "x");
            recreated.setDescription("base");
        }
        assertEquals("ops/x", recreated.getFullName(), "premise: a new job of the same kind carries the window's name");

        assertFalse(can("u1", recreated, Item.CONFIGURE), "D-71a: the window must not reach a job re-created under its name");
        assertEquals(403, postConfigXml("u1", recreated, "planted"), "u1's save of the re-created ops/x must be refused");
        assertEquals("base", reload(recreated).getDescription());
    }

    /**
     * T-08-137 (S-34-04, D-71a ruling 3): the stored scope is the canonical full name. u1 submits
     * the form with {@code scopeFullName=ops/} and u2 with {@code scopeFullName=OPS}, both for the
     * folder {@code ops}; each stored request names {@code ops}, a1 approves, each window names
     * {@code ops} and confers Configure on the folder (u1's and u2's saves 200). Guard: before the
     * approvals neither held Configure on {@code ops}, and the windows reach nothing else
     * ({@code ops/prod}).
     */
    @Test
    public void t_08_137_typedSpellingIsStoredAsTheCanonicalFullName() throws Exception {
        String[][] cases = {{"u1", "ops/"}, {"u2", "OPS"}};
        for (String[] c : cases) {
            String user = c[0];
            String typed = c[1];
            String id = submitGrantOk(j, user, typed, List.of("CONFIGURE"), 30, "maintenance of " + typed, null, "a1");
            GrantRequest stored = GrantRequestService.get().load(id);
            assertEquals("ops", stored.getScope().getFullName(), "S-34-04: the request typed as '" + typed + "' must store the canonical name");
            assertFalse(can(user, ops, Item.CONFIGURE), "guard: before the approval " + user + " holds no Configure on ops");

            assertSuccess(decideGrant(j, "a1", id, "approve", "ok"), "approval of the request typed as '" + typed + "'");
            Grant window = GrantService.get().listActive().stream().filter(g -> user.equals(g.getUser())).findFirst().orElse(null);
            assertNotNull(window, user + "'s approval must open a window");
            assertEquals("ops", window.getScope().getFullName(), "the window must name the canonical full name ops");
            assertTrue(can(user, ops, Item.CONFIGURE), "S-34-04: the approved window must confer Configure on ops (typed '" + typed + "')");
            assertEquals(200, postConfigXml(user, ops, "saved-by-" + user), user + " must be able to save the folder ops");
            assertFalse(can(user, opsProd, Item.CONFIGURE), "guard: the window reaches nothing inside ops");
        }
    }

    /**
     * T-08-146 (coverage of D-71a "a renamed item loses its window"; SPEC 8 line 169 "renaming,
     * moving ... never makes a window reach a different item"): u1 holds CONFIGURE windows on the
     * jobs {@code ops/prod} and {@code sandbox/prod}. The administrator renames {@code ops/prod} to
     * {@code ops/prod-renamed} and moves {@code sandbox/prod} into {@code dest}. u1 then holds no
     * Configure on either job under its new name, and both saves are 403. Guard: before the rename
     * and the move both windows conferred Configure.
     */
    @Test
    public void t_08_146_windowDoesNotFollowARenameOrMoveOfItsItem() throws Exception {
        openWindow("u1", "ops/prod", "CONFIGURE");
        openWindow("u1", "sandbox/prod", "CONFIGURE");
        assertTrue(can("u1", opsProd, Item.CONFIGURE), "guard: before the rename the window confers Configure on ops/prod");
        assertTrue(can("u1", sandboxProd, Item.CONFIGURE), "guard: before the move the window confers Configure on sandbox/prod");

        assertSuccess(rename("admin", opsProd, "prod-renamed"), "fixture: the administrator renames ops/prod");
        assertSuccess(ApproverFormFixtures.post(j, "admin", sandboxProd.getUrl() + "move/move",
                List.of(new NameValuePair("destination", "/dest"))), "fixture: the administrator moves sandbox/prod into dest");
        assertEquals("ops/prod-renamed", opsProd.getFullName(), "premise: the job was renamed");
        assertEquals("dest/prod", sandboxProd.getFullName(), "premise: the job was moved");

        assertFalse(can("u1", opsProd, Item.CONFIGURE), "the window must not follow the renamed job");
        assertEquals(403, postConfigXml("u1", opsProd, "after-rename"), "u1's save of the renamed job must be refused");
        assertFalse(can("u1", sandboxProd, Item.CONFIGURE), "the window must not follow the moved job");
        assertEquals(403, postConfigXml("u1", sandboxProd, "after-move"), "u1's save of the moved job must be refused");
    }

    /**
     * T-08-147 (SPEC 8 line 169 "the same as a refused move, D-73 coalescing applies"): u1 holds
     * CONFIGURE windows on the folders {@code sandbox} and {@code ops}. u1's refused rename of
     * {@code sandbox} to {@code sandbox-old} adds one GRANT_VIOLATION; the same rename repeated at
     * once is refused too and adds none (same user, item and new name within a minute); the refused
     * rename of the other folder {@code ops} adds its own record naming {@code ops}. Nothing is
     * renamed.
     */
    @Test
    public void t_08_147_repeatedRefusedFolderRenameIsRecordedOncePerMinute() throws Exception {
        openWindow("u1", "sandbox", "CONFIGURE");
        openWindow("u1", "ops", "CONFIGURE");
        int before = records(ChangeType.GRANT_VIOLATION).size();

        assertClientError(rename("u1", sandbox, "sandbox-old"), "u1's first rename of sandbox");
        assertEquals(before + 1, records(ChangeType.GRANT_VIOLATION).size(), "the first refusal is recorded");
        assertClientError(rename("u1", sandbox, "sandbox-old"), "u1's immediate repeat of the same rename");
        assertEquals(before + 1, records(ChangeType.GRANT_VIOLATION).size(), "D-73: the repeat within the minute adds no record");

        assertClientError(rename("u1", ops, "ops-old"), "u1's rename of the other folder ops");
        List<ChangeRecord> violations = records(ChangeType.GRANT_VIOLATION);
        assertEquals(before + 2, violations.size(), "D-73: a different item writes its own record, got " + violations);
        ChangeRecord last = violations.get(violations.size() - 1);
        assertEquals("u1", last.getUser(), "the record names u1");
        assertTrue(mentions(last, "ops"), "the second record names the folder ops: " + describe(last));

        assertNotNull(j.jenkins.getItemByFullName("sandbox"));
        assertNotNull(j.jenkins.getItemByFullName("ops"));
        assertNull(j.jenkins.getItemByFullName("sandbox-old"));
        assertNull(j.jenkins.getItemByFullName("ops-old"));
    }

    // ---------------------------------------------------------------- helpers

    /** Files a window on {@code fullName} through the form as {@code user}; a1 approves it. */
    private void openWindow(String user, String fullName, String... actions) throws Exception {
        long before = GrantService.get().listActive().stream().filter(g -> user.equals(g.getUser())).count();
        String id = submitGrantOk(j, user, fullName, Arrays.asList(actions), 30, "maintenance of " + fullName, null, "a1");
        assertSuccess(decideGrant(j, "a1", id, "approve", "ok"), "fixture: approval by a1");
        long after = GrantService.get().listActive().stream().filter(g -> user.equals(g.getUser())).count();
        assertEquals(before + 1, after, "fixture: " + user + " must hold one more active window");
    }

    /** {@code POST <item>/confirmRename} with {@code newName} (core's rename endpoint), redirects not followed. */
    private WebResponse rename(String user, Item item, String newName) throws Exception {
        return ApproverFormFixtures.post(j, user, item.getUrl() + "confirmRename", List.of(new NameValuePair("newName", newName)));
    }

    static boolean can(String user, hudson.security.AccessControlled target, hudson.security.Permission p) {
        return target.getACL().hasPermission2(User.getById(user, true).impersonate2(), p);
    }

    private AbstractItem reload(AbstractItem item) {
        AbstractItem now = j.jenkins.getItemByFullName(item.getFullName(), AbstractItem.class);
        assertNotNull(now, "fixture: " + item.getFullName() + " must exist");
        return now;
    }

    private static boolean mentions(ChangeRecord rec, String text) {
        return String.valueOf(rec.getTarget()).contains(text) || String.valueOf(rec.getDetail()).contains(text);
    }

    private static String describe(ChangeRecord rec) {
        return "user=" + rec.getUser() + " target=" + rec.getTarget() + " detail=" + rec.getDetail();
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
}
