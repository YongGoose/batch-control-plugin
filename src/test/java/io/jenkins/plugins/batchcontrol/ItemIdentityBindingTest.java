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
 * SPEC item 8 (D-71a, D-71c, security-34 S-34-01/03/04, security-36 S-36-01/02, spec-review-S6
 * m-1): "a window confers something only on the very item it was approved for: the grant records
 * the item's identity at approval and matches only when both the full name and the identity match,
 * so renaming, moving, swapping or re-creating items never makes a window (or several windows
 * combined) reach a different item; while change control is on, no window allows renaming any item
 * (job or folder of any kind) -- neither a CONFIGURE window nor DELETE and CREATE windows combined;
 * a rename needs an administrator or the user's own permissions, and a refused rename (whatever URL
 * form reaches core's rename endpoints) is recorded as GRANT_VIOLATION (D-71c) (the same as a
 * refused move, D-73 coalescing applies). The stored scope is the item's canonical full name,
 * whatever spelling was typed." Matrix rows T-08-130, T-08-131, T-08-133 .. T-08-137, T-08-146,
 * T-08-147 (note 262), T-08-154 (note 264, the rename refusal's field check and page) and
 * T-08-134 (rewritten), T-08-156, T-08-159 .. T-08-163 (note 266, D-71c); the role-strategy rows
 * T-08-132, T-08-157 and T-08-158 are in {@link ItemIdentityRoleStrategyTest}, the D-71b item
 * events in {@link ItemBindingEventsTest}.
 *
 * <p>Layout (security-34 Probe A): folder {@code ops} with the job {@code ops/prod}, folder
 * {@code sandbox} with the job {@code sandbox/prod}, folder {@code dest}; every description is
 * "base". Batch Control matrix strategy, change control on. u1 and u2 hold Overall/Read, Item/Read
 * and RequestGrant only; a1 approves; c1 holds a standing Item/Configure (no window); admin holds
 * Overall/Administer. For the D-71c rows on core's second rename path (Delete on the item plus
 * Create in its parent), d1 holds a standing Item/Delete, k1 a standing Item/Create and dc1 both,
 * each with RequestGrant and without Configure. Windows are requested through the form contract
 * and approved by a1. What a window confers is measured on the item's own ACL and through HTTP
 * saves, never through a name-based service query, because a name alone is what D-71a stops
 * trusting.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-71/D-71a/D-71c/D-73, docs/reports/security-34.md,
 * docs/reports/security-36.md, docs/reports/spec-review-S6.md and docs/TEST-MATRIX.md only (no
 * src/main knowledge).
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
        for (String userId : new String[] {"d1", "k1", "dc1"}) { // core's second rename path, D-71c rows
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
            strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user(userId));
        }
        strategy.add(Item.DELETE, PermissionEntry.user("d1")); // standing Delete only
        strategy.add(Item.CREATE, PermissionEntry.user("k1")); // standing Create only
        strategy.add(Item.DELETE, PermissionEntry.user("dc1")); // standing Delete and Create
        strategy.add(Item.CREATE, PermissionEntry.user("dc1"));
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
     * T-08-134 (SPEC 8 line 169, D-71c ruling 1, which supersedes D-71a ruling 2 "Renaming a job
     * with a CONFIGURE window stays allowed"; security-36 S-36-02; spec-review-S6 m-1): u1's
     * CONFIGURE window on the pre-existing job {@code ops/prod} does not let u1 rename it. The
     * rename to {@code prod2} ({@code confirmRename}) answers 400 with the D-71c refusal
     * ("Renaming 'ops/prod' (Freestyle project) is not allowed: while change control is on, a
     * permission window does not allow renaming a job or folder" and "Nothing was renamed."), the
     * job keeps its name, nothing carries {@code ops/prod2}, and exactly one GRANT_VIOLATION names u1
     * and {@code ops/prod}. Guards: before the rename the window conferred Configure on the job, and
     * after the refusal it still does (u1's save 200), so the refusal is about the rename, not about
     * a window that confers nothing. The folder half of m-1 is T-08-130.
     */
    @Test
    public void t_08_134_configureWindowOnAJobDoesNotAllowRenamingIt() throws Exception {
        openWindow("u1", "ops/prod", "CONFIGURE");
        assertTrue(can("u1", opsProd, Item.CONFIGURE), "guard: before the rename the window confers Configure on ops/prod");
        int violationsBefore = records(ChangeType.GRANT_VIOLATION).size();

        RenameRefusalFixtures.assertWindowRenameRefused(rename("u1", opsProd, "prod2"), "ops/prod", "Freestyle project",
                "u1 renaming the job ops/prod with Configure only from its CONFIGURE window");
        assertNotNull(j.jenkins.getItemByFullName("ops/prod"), "the refused rename must leave the job under its name");
        assertNull(j.jenkins.getItemByFullName("ops/prod2"), "no item may carry the new name");
        assertEquals("ops/prod", opsProd.getFullName());
        List<ChangeRecord> violations = records(ChangeType.GRANT_VIOLATION);
        assertEquals(violationsBefore + 1, violations.size(), "the refused rename is recorded once as GRANT_VIOLATION, got " + violations);
        ChangeRecord rec = violations.get(violations.size() - 1);
        assertEquals("u1", rec.getUser(), "the GRANT_VIOLATION names u1");
        assertTrue(mentions(rec, "ops/prod"), "the GRANT_VIOLATION names the job ops/prod: " + describe(rec));

        assertTrue(can("u1", opsProd, Item.CONFIGURE), "guard: the window still confers Configure on ops/prod after the refusal");
        assertEquals(200, postConfigXml("u1", opsProd, "after-refusal"), "guard: u1 still saves ops/prod through the window");
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

    /**
     * T-08-154 (D-71a ruling 2, D-71c ruling 1, SPEC 8 line 169; the refusal's screens, ui-dev
     * contract; usability line: a refusal names what is refused in plain words): u1 holds CONFIGURE
     * windows on the folder {@code sandbox} and on the job {@code sandbox/prod}. The rename page's
     * field check ({@code GET job/sandbox/checkNewName?newName=sandbox-old}) answers 200 with an
     * error that reads "Renaming 'sandbox' (Folder) is not allowed: while change control is on, a
     * permission window does not allow renaming a job or folder"; the rename itself ({@code POST
     * confirmRename} from a browser, Accept text/html) answers 400 with a page whose text reads the
     * same and "Nothing was renamed."; nothing is renamed. The one GRANT_VIOLATION of that refused
     * POST is T-08-130's (same fixture and request), not repeated here. Since D-71c u1's field check
     * of a rename of the job {@code sandbox/prod} is the same refusal for the job ("Renaming
     * 'sandbox/prod' (Freestyle project) is not allowed: ..."; before D-71c it was the guard "no
     * error", note 266). Guard: the same field check by c1 (standing Item/Configure) is no error and
     * says nothing is not allowed.
     */
    @Test
    public void t_08_154_windowRenameRefusalIsExplainedOnTheFieldAndThePage() throws Exception {
        openWindow("u1", "sandbox", "CONFIGURE");
        openWindow("u1", "sandbox/prod", "CONFIGURE");
        String refused = RenameRefusalFixtures.refusal("sandbox", "Folder");

        WebResponse check = ApproverFormFixtures.get(j, "u1", sandbox.getUrl() + "checkNewName?newName=sandbox-old");
        assertEquals(200, check.getStatusCode(), "the field check answers a validation result: " + ApproverFormFixtures.excerpt(check.getContentAsString()));
        assertEquals("error", validationKind(check), "the field check must be an error for u1: " + ApproverFormFixtures.excerpt(check.getContentAsString()));
        assertTrue(visible(check.getContentAsString()).contains(refused), "the field check must say '" + refused + "': "
                + visible(check.getContentAsString()));

        JenkinsRule.WebClient browser = UsabilityFixtures.clientNoJs(j, "u1");
        browser.getOptions().setRedirectEnabled(false);
        WebRequest post = new WebRequest(browser.createCrumbedUrl(sandbox.getUrl() + "confirmRename"), HttpMethod.POST);
        post.setRequestParameters(List.of(new NameValuePair("newName", "sandbox-old")));
        post.setAdditionalHeader("Accept", "text/html,application/xhtml+xml");
        org.htmlunit.Page answer = browser.getPage(post);
        String text = UsabilityFixtures.text(answer);
        assertEquals(400, answer.getWebResponse().getStatusCode(), "the refused rename answers 400: " + ApproverFormFixtures.excerpt(text));
        assertTrue(text.contains(refused), "the refusal page must say '" + refused + "': " + ApproverFormFixtures.excerpt(text));
        assertTrue(text.contains("Nothing was renamed."), "the refusal page must say 'Nothing was renamed.': " + ApproverFormFixtures.excerpt(text));
        assertNotNull(j.jenkins.getItemByFullName("sandbox"), "the folder keeps its name");
        assertNotNull(j.jenkins.getItemByFullName("sandbox/prod"), "the job inside keeps its full name");
        assertNull(j.jenkins.getItemByFullName("sandbox-old"), "nothing carries the new name");

        WebResponse standing = ApproverFormFixtures.get(j, "c1", sandbox.getUrl() + "checkNewName?newName=sandbox-old");
        assertEquals(200, standing.getStatusCode());
        assertFalse("error".equals(validationKind(standing)), "guard: c1's field check (standing Configure) is no error: "
                + ApproverFormFixtures.excerpt(standing.getContentAsString()));
        assertFalse(visible(standing.getContentAsString()).contains("not allowed"), "guard: c1 is not told the rename is not allowed");
        WebResponse jobCheck = ApproverFormFixtures.get(j, "u1", sandboxProd.getUrl() + "checkNewName?newName=prod2");
        String jobRefused = RenameRefusalFixtures.refusal("sandbox/prod", "Freestyle project");
        assertEquals(200, jobCheck.getStatusCode(), "the job's field check answers a validation result");
        assertEquals("error", validationKind(jobCheck), "D-71c: renaming the job through its CONFIGURE window is an error: "
                + ApproverFormFixtures.excerpt(jobCheck.getContentAsString()));
        assertTrue(visible(jobCheck.getContentAsString()).contains(jobRefused), "the job's field check must say '" + jobRefused + "': "
                + visible(jobCheck.getContentAsString()));
        assertNotNull(j.jenkins.getItemByFullName("sandbox/prod"), "the field check renames nothing");
    }

    /**
     * T-08-156 (security-36 S-36-01, D-71c ruling 2, D-73): rename detection works on the decoded
     * request path. u1 holds one approved CONFIGURE window on the folder {@code ops} (the T-08-130
     * fixture). u1 POSTs, with a crumb, {@code job/ops/confirm%52ename?newName=sandbox2},
     * {@code job/ops/%63onfirmRename?newName=sandbox3} and
     * {@code job/ops/confirm%52ename/?newName=sandbox4} (Stapler decodes each to core's
     * {@code confirmRename}). Each answers 400 with the D-71c refusal for 'ops' (Folder) and
     * "Nothing was renamed.", {@code ops} and {@code ops/prod} keep their names, nothing exists at the
     * new names, u1 holds no Configure on {@code ops/prod}, and each attempt adds one GRANT_VIOLATION
     * naming u1 and {@code ops} (three new names, three records). The first form repeated at once is
     * refused again and adds none (D-73: same user, item and new name within the minute). Guard,
     * after the refusals: the window still confers Configure on {@code ops} (save 200).
     */
    @Test
    public void t_08_156_encodedRenameEndpointsOnAFolderWindowAreRefused() throws Exception {
        openWindow("u1", "ops", "CONFIGURE");
        assertTrue(can("u1", ops, Item.CONFIGURE), "premise: the window confers Configure on ops");
        int before = records(ChangeType.GRANT_VIOLATION).size();
        String[][] attempts = {
            {"confirm%52ename", "sandbox2"}, {"%63onfirmRename", "sandbox3"}, {"confirm%52ename/", "sandbox4"},
        };
        int expected = before;
        for (String[] attempt : attempts) {
            String path = ops.getUrl() + attempt[0] + "?newName=" + attempt[1];
            RenameRefusalFixtures.assertWindowRenameRefused(RenameRefusalFixtures.postPath(j, "u1", path), "ops", "Folder", "POST " + path);
            assertNotNull(j.jenkins.getItemByFullName("ops"), path + " must leave the folder ops in place");
            assertNotNull(j.jenkins.getItemByFullName("ops/prod"), path + " must leave ops/prod under its full name");
            assertNull(j.jenkins.getItemByFullName(attempt[1]), "nothing may exist at " + attempt[1] + " after " + path);
            assertFalse(can("u1", opsProd, Item.CONFIGURE), "u1 must hold no Configure on ops/prod after " + path);
            expected++;
            List<ChangeRecord> violations = records(ChangeType.GRANT_VIOLATION);
            assertEquals(expected, violations.size(), path + " must be recorded once as GRANT_VIOLATION, got " + violations);
            ChangeRecord rec = violations.get(violations.size() - 1);
            assertEquals("u1", rec.getUser(), "the GRANT_VIOLATION of " + path + " names u1");
            assertTrue(mentions(rec, "ops"), "the GRANT_VIOLATION of " + path + " names the folder ops: " + describe(rec));
        }

        String repeat = ops.getUrl() + "confirm%52ename?newName=sandbox2";
        RenameRefusalFixtures.assertWindowRenameRefused(RenameRefusalFixtures.postPath(j, "u1", repeat), "ops", "Folder", "the repeated POST " + repeat);
        assertEquals(expected, records(ChangeType.GRANT_VIOLATION).size(), "D-73: the same refused rename repeated within the minute adds no record");
        assertNull(j.jenkins.getItemByFullName("sandbox2"));

        assertEquals(200, postConfigXml("u1", ops, "changed-ops"), "guard: the window on ops still confers Configure on it");
    }

    /**
     * T-08-159 (D-71c ruling 1, core's second rename path; security-36 S-36-02 fix direction (a)):
     * u1 holds a CREATE window on the folder {@code ops} and a DELETE window on the job
     * {@code ops/prod}, so u1 holds Item/Create in the parent and Item/Delete on the job, both only
     * from windows, and no Configure. u1's {@code confirmRename} to {@code prod2} and core's
     * {@code doRename} to {@code prod3} each answer 400 with the D-71c refusal for 'ops/prod'
     * (Freestyle project) and "Nothing was renamed."; the job keeps its name, nothing carries either
     * new name, and each attempt adds one GRANT_VIOLATION naming u1 and {@code ops/prod}. Guard,
     * after the refusals: both windows still confer what they name.
     */
    @Test
    public void t_08_159_deleteAndCreateWindowsDoNotRenameAJob() throws Exception {
        openWindow("u1", "ops", "CREATE");
        openWindow("u1", "ops/prod", "DELETE");
        assertTrue(can("u1", ops, Item.CREATE), "premise: the CREATE window confers Item/Create in ops");
        assertTrue(can("u1", opsProd, Item.DELETE), "premise: the DELETE window confers Item/Delete on ops/prod");
        assertFalse(can("u1", opsProd, Item.CONFIGURE), "premise: u1 holds no Configure on ops/prod");

        assertSecondPathRenameRefused("u1");

        assertTrue(can("u1", ops, Item.CREATE), "guard: the CREATE window still confers Item/Create in ops");
        assertTrue(can("u1", opsProd, Item.DELETE), "guard: the DELETE window still confers Item/Delete on ops/prod");
    }

    /**
     * T-08-160 (D-71c ruling 1): d1 holds a standing Item/Delete (no Configure, no Create) and a
     * CREATE window on the folder {@code ops}; the window supplies the Create half of core's second
     * rename path. d1's {@code confirmRename} of {@code ops/prod} to {@code prod2} and
     * {@code doRename} to {@code prod3} are each refused with 400 and the D-71c refusal, nothing is
     * renamed, and each attempt adds one GRANT_VIOLATION naming d1 and {@code ops/prod}.
     */
    @Test
    public void t_08_160_ownDeleteAndACreateWindowDoNotRenameAJob() throws Exception {
        assertTrue(can("d1", opsProd, Item.DELETE), "premise: d1's Item/Delete on ops/prod is standing");
        assertFalse(can("d1", ops, Item.CREATE), "premise: d1 holds no Create of their own in ops");
        openWindow("d1", "ops", "CREATE");
        assertTrue(can("d1", ops, Item.CREATE), "premise: the CREATE window confers Item/Create in ops");
        assertFalse(can("d1", opsProd, Item.CONFIGURE), "premise: d1 holds no Configure on ops/prod");

        assertSecondPathRenameRefused("d1");
    }

    /**
     * T-08-161 (D-71c ruling 1): k1 holds a standing Item/Create (no Configure, no Delete) and a
     * DELETE window on the job {@code ops/prod}; the window supplies the Delete half of core's second
     * rename path. k1's {@code confirmRename} to {@code prod2} and {@code doRename} to {@code prod3}
     * are each refused with 400 and the D-71c refusal, nothing is renamed, and each attempt adds one
     * GRANT_VIOLATION naming k1 and {@code ops/prod}.
     */
    @Test
    public void t_08_161_aDeleteWindowAndOwnCreateDoNotRenameAJob() throws Exception {
        assertTrue(can("k1", ops, Item.CREATE), "premise: k1's Item/Create in ops is standing");
        assertFalse(can("k1", opsProd, Item.DELETE), "premise: k1 holds no Delete of their own on ops/prod");
        openWindow("k1", "ops/prod", "DELETE");
        assertTrue(can("k1", opsProd, Item.DELETE), "premise: the DELETE window confers Item/Delete on ops/prod");
        assertFalse(can("k1", opsProd, Item.CONFIGURE), "premise: k1 holds no Configure on ops/prod");

        assertSecondPathRenameRefused("k1");
    }

    /**
     * T-08-162 (guards of T-08-134 and T-08-156 .. T-08-161; D-71c "a rename needs an administrator
     * or the user's own permissions"): c1 (standing Item/Configure, no window) renames the job
     * {@code ops/prod} to {@code prod-c1} ({@code confirmRename}); dc1 (standing Item/Delete and
     * Item/Create, no Configure of their own) renames {@code sandbox/prod} to {@code prod-dc1} while
     * also holding a CONFIGURE window on it (a window held next to sufficient standing permissions
     * does not turn the rename into a refusal); the administrator renames the job (now
     * {@code ops/prod-c1}) to {@code prod-admin} through core's {@code doRename} and the folder
     * {@code dest} to {@code dest-admin} through {@code confirm%52ename}. Each answers a redirect
     * (3xx), the items carry the new names, and no GRANT_VIOLATION is recorded.
     */
    @Test
    public void t_08_162_standingPermissionsAndTheAdministratorStillRename() throws Exception {
        int before = records(ChangeType.GRANT_VIOLATION).size();

        assertRedirect(rename("c1", opsProd, "prod-c1"), "c1 (standing Configure) renaming the job ops/prod");
        assertEquals("ops/prod-c1", opsProd.getFullName(), "c1's rename goes through");

        openWindow("dc1", "sandbox/prod", "CONFIGURE");
        assertTrue(can("dc1", sandboxProd, Item.DELETE), "premise: dc1's Item/Delete on sandbox/prod is standing");
        assertTrue(can("dc1", sandbox, Item.CREATE), "premise: dc1's Item/Create in sandbox is standing");
        assertRedirect(rename("dc1", sandboxProd, "prod-dc1"), "dc1 (standing Delete and Create, plus a window) renaming sandbox/prod");
        assertEquals("sandbox/prod-dc1", sandboxProd.getFullName(), "dc1's rename goes through");

        assertRedirect(RenameRefusalFixtures.postPath(j, "admin", opsProd.getUrl() + "doRename?newName=prod-admin"),
                "the administrator renaming ops/prod-c1 through doRename");
        assertEquals("ops/prod-admin", opsProd.getFullName(), "the administrator's doRename goes through");
        assertRedirect(RenameRefusalFixtures.postPath(j, "admin", dest.getUrl() + "confirm%52ename?newName=dest-admin"),
                "the administrator renaming the folder dest through confirm%52ename");
        assertEquals("dest-admin", dest.getFullName(), "the administrator's encoded rename goes through");

        assertEquals(before, records(ChangeType.GRANT_VIOLATION).size(), "permitted renames record no violation");
    }

    /**
     * T-08-163 (D-71c; CLAUDE.md "a new feature does not change existing Jenkins behaviour while the
     * global switch is off"): with change control off, c1 (standing Item/Configure) renames the job
     * {@code ops/prod} to {@code prod-off} through core's {@code doRename} and the folder
     * {@code sandbox} to {@code sandbox-off} through {@code confirm%52ename}; both redirect and go
     * through, and c1's field check is no error. u2 (no Configure, Delete or Create) renaming
     * {@code ops} gets core's refusal (4xx) without the D-71c text, and nothing is renamed. No
     * GRANT_VIOLATION is recorded.
     */
    @Test
    public void t_08_163_switchOffRenamesAsInJenkins() throws Exception {
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(false);
        cfg.save();
        int before = records(ChangeType.GRANT_VIOLATION).size();

        WebResponse check = ApproverFormFixtures.get(j, "c1", opsProd.getUrl() + "checkNewName?newName=prod-off");
        assertEquals(200, check.getStatusCode());
        assertFalse("error".equals(validationKind(check)), "c1's field check is no error with the switch off: "
                + ApproverFormFixtures.excerpt(check.getContentAsString()));
        assertRedirect(RenameRefusalFixtures.postPath(j, "c1", opsProd.getUrl() + "doRename?newName=prod-off"), "c1's doRename of ops/prod");
        assertEquals("ops/prod-off", opsProd.getFullName(), "c1's doRename goes through with the switch off");
        assertRedirect(RenameRefusalFixtures.postPath(j, "c1", sandbox.getUrl() + "confirm%52ename?newName=sandbox-off"),
                "c1's encoded rename of the folder sandbox");
        assertEquals("sandbox-off", sandbox.getFullName(), "c1's encoded rename goes through with the switch off");
        assertNotNull(j.jenkins.getItemByFullName("sandbox-off/prod"), "the job inside follows");

        WebResponse refused = rename("u2", ops, "ops-u2");
        assertClientError(refused, "u2 (no permission) renaming ops with the switch off");
        assertFalse(visible(refused.getContentAsString()).contains(RenameRefusalFixtures.REASON),
                "core's refusal, not the D-71c text, with the switch off: " + ApproverFormFixtures.excerpt(refused.getContentAsString()));
        assertNotNull(j.jenkins.getItemByFullName("ops"));
        assertNull(j.jenkins.getItemByFullName("ops-u2"));

        assertEquals(before, records(ChangeType.GRANT_VIOLATION).size(), "nothing is recorded with the switch off");
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Core's second rename path by {@code user} on {@code ops/prod}: {@code confirmRename} to
     * {@code prod2}, then {@code doRename} to {@code prod3}; each is the D-71c refusal, renames
     * nothing and adds one GRANT_VIOLATION naming the user and {@code ops/prod}.
     */
    private void assertSecondPathRenameRefused(String user) throws Exception {
        int expected = records(ChangeType.GRANT_VIOLATION).size();
        String[][] attempts = {{"confirmRename", "prod2"}, {"doRename", "prod3"}};
        for (String[] attempt : attempts) {
            String path = opsProd.getUrl() + attempt[0] + "?newName=" + attempt[1];
            RenameRefusalFixtures.assertWindowRenameRefused(RenameRefusalFixtures.postPath(j, user, path), "ops/prod", "Freestyle project",
                    user + " POST " + path);
            assertNotNull(j.jenkins.getItemByFullName("ops/prod"), path + " must leave ops/prod under its name");
            assertNull(j.jenkins.getItemByFullName("ops/" + attempt[1]), "nothing may carry ops/" + attempt[1]);
            expected++;
            List<ChangeRecord> violations = records(ChangeType.GRANT_VIOLATION);
            assertEquals(expected, violations.size(), user + "'s " + path + " must be recorded once as GRANT_VIOLATION, got " + violations);
            ChangeRecord rec = violations.get(violations.size() - 1);
            assertEquals(user, rec.getUser(), "the GRANT_VIOLATION names " + user);
            assertTrue(mentions(rec, "ops/prod"), "the GRANT_VIOLATION names the job ops/prod: " + describe(rec));
        }
        assertEquals("ops/prod", opsProd.getFullName());
    }

    private static void assertRedirect(WebResponse r, String what) {
        int code = r.getStatusCode();
        assertTrue(code >= 300 && code < 400, what + " must answer a redirect (the rename went through), got HTTP " + code + ": "
                + ApproverFormFixtures.excerpt(r.getContentAsString()));
    }


    /** The FormValidation kind of a validation answer ({@code <div class=ok|warning|error>}), or "". */
    private static String validationKind(WebResponse r) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("class=[\"']?(ok|warning|error)\\b").matcher(r.getContentAsString());
        return m.find() ? m.group(1) : "";
    }

    /** Visible text of a markup fragment: tags removed, entities decoded, whitespace collapsed. */
    private static String visible(String html) {
        String s = html.replaceAll("<[^>]*>", " ")
                .replace("&#039;", "'").replace("&#39;", "'").replace("&apos;", "'").replace("&quot;", "\"").replace("&#34;", "\"")
                .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
        return s.replaceAll("\\s+", " ").trim();
    }

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
