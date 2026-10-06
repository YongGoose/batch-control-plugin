package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.michelin.cio.hudson.plugins.rolestrategy.PermissionEntry;
import com.michelin.cio.hudson.plugins.rolestrategy.Role;
import com.michelin.cio.hudson.plugins.rolestrategy.RoleBasedAuthorizationStrategy;
import com.michelin.cio.hudson.plugins.rolestrategy.RoleMap;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.BatchControlRoleBasedAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertClientError;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.records;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 8 (D-71a ruling 2, D-71c), the role-strategy variants of security-34 S-34-01 and
 * security-36 S-36-01/S-36-02: role-strategy item roles are regular expressions on full names, so
 * renaming an item can move it (or a folder's contents) under a pattern its holder already has and
 * turn a time-limited window into standing permissions. "While change control is on, no window
 * allows renaming any item (job or folder of any kind) ...; a refused rename (whatever URL form
 * reaches core's rename endpoints) is recorded as GRANT_VIOLATION (D-71c)." Matrix rows T-08-132
 * (note 262), T-08-157 and T-08-158 (note 266), T-08-185 and T-08-186 (note 270, security-38
 * S-38-01: a trailing path segment after core's rename method).
 *
 * <p>Batch Control role-strategy strategy (D-35a); global roles give Overall/Read and Item/Read to
 * u1 and a1, RequestGrant to u1, Approve to a1, Administer to admin; the item role
 * {@code sandbox} with pattern {@code sandbox.*} gives u1 Item/Read, Item/Configure, Item/Build and
 * Item/Workspace (security-36 Probe ROLE2). Items: folder {@code ops} (job {@code ops/prod}), folder
 * {@code sandbox} (job {@code sandbox/prod}) and the top-level job {@code deploy}. Windows are
 * requested through the form contract and approved by a1.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-71a/D-71c/D-73, docs/reports/security-34.md,
 * docs/reports/security-36.md, docs/reports/security-38.md and docs/TEST-MATRIX.md only (no src/main
 * knowledge).
 */
@WithJenkins
public class ItemIdentityRoleStrategyTest {

    private JenkinsRule j;
    private Folder ops;
    private FreeStyleProject opsProd;
    private Folder sandbox;
    private FreeStyleProject deploy;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new BatchControlRoleBasedAuthorizationStrategy(roles(), Collections.emptySet()));
        assertSame(BatchControlRoleBasedAuthorizationStrategy.class, j.jenkins.getAuthorizationStrategy().getClass(),
                "fixture: the Batch Control role strategy must be installed");

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        ops = j.jenkins.createProject(Folder.class, "ops");
        opsProd = ops.createProject(FreeStyleProject.class, "prod");
        sandbox = j.jenkins.createProject(Folder.class, "sandbox");
        sandbox.createProject(FreeStyleProject.class, "prod");
        deploy = j.jenkins.createProject(FreeStyleProject.class, "deploy");
    }

    /**
     * T-08-132 (S-34-01, role-strategy variant): u1's standing item role {@code sandbox.*}
     * (Configure) plus one CONFIGURE window on the folder {@code ops}. u1's rename of {@code ops}
     * to {@code sandbox2}, which would put {@code ops/prod} under the role's pattern, is refused
     * with 4xx: nothing is renamed, {@code ops/prod} keeps its name, nothing exists at
     * {@code sandbox2}, u1 holds no Configure on {@code ops/prod}, and one GRANT_VIOLATION names u1.
     * Guards: the role confers Configure on the matching folder {@code sandbox} and not on
     * {@code ops/prod}; the window confers Configure on {@code ops} (before and after); and u1
     * renames {@code sandbox} to {@code sandbox-old}, whose Configure is standing (the role), so the
     * refusal is about the window-only Configure, not about folder renames as such.
     */
    @Test
    public void t_08_132_roleStrategyFolderRenameWithWindowOnlyConfigureIsRefused() throws Exception {
        assertTrue(can("u1", sandbox, Item.CONFIGURE), "premise: the item role sandbox.* confers Configure on the folder sandbox");
        assertFalse(can("u1", opsProd, Item.CONFIGURE), "premise: the role does not reach ops/prod");
        assertFalse(can("u1", ops, Item.CONFIGURE), "premise: u1 holds no Configure of their own on ops");

        String id = submitGrantOk(j, "u1", "ops", List.of("CONFIGURE"), 30, "maintenance of ops", null, "a1");
        assertSuccess(decideGrant(j, "a1", id, "approve", "ok"), "fixture: approval by a1");
        assertEquals(1, GrantService.get().listActive().stream().filter(g -> "u1".equals(g.getUser())).count(),
                "fixture: u1 holds one active window");
        assertTrue(can("u1", ops, Item.CONFIGURE), "premise: the window confers Configure on ops");
        int violationsBefore = records(ChangeType.GRANT_VIOLATION).size();

        assertClientError(rename("u1", ops, "sandbox2"), "u1 renaming the folder ops (Configure only from the window) to sandbox2");
        assertNotNull(j.jenkins.getItemByFullName("ops"), "the refused rename must leave ops in place");
        assertNotNull(j.jenkins.getItemByFullName("ops/prod"), "ops/prod keeps its full name");
        assertNull(j.jenkins.getItemByFullName("sandbox2"), "nothing may exist at sandbox2");
        assertEquals("ops/prod", opsProd.getFullName());
        assertFalse(can("u1", opsProd, Item.CONFIGURE), "u1 must not gain Configure on ops/prod");
        List<ChangeRecord> violations = records(ChangeType.GRANT_VIOLATION);
        assertEquals(violationsBefore + 1, violations.size(), "the refused rename is recorded once as GRANT_VIOLATION, got " + violations);
        ChangeRecord rec = violations.get(violations.size() - 1);
        assertEquals("u1", rec.getUser(), "the GRANT_VIOLATION names u1");
        assertTrue(String.valueOf(rec.getTarget()).contains("ops") || String.valueOf(rec.getDetail()).contains("ops"),
                "the GRANT_VIOLATION names the folder ops: target=" + rec.getTarget() + " detail=" + rec.getDetail());
        assertTrue(can("u1", ops, Item.CONFIGURE), "guard: the window still confers Configure on ops after the refusal");

        assertSuccess(rename("u1", sandbox, "sandbox-old"), "guard: u1 renaming the folder sandbox, whose Configure comes from the role");
        assertNotNull(j.jenkins.getItemByFullName("sandbox-old/prod"), "guard: the role-authorised rename goes through");
        assertEquals(violationsBefore + 1, records(ChangeType.GRANT_VIOLATION).size(), "guard: the permitted rename adds no violation");
    }

    /**
     * T-08-157 (security-36 S-36-01, ROLE probe; D-71c ruling 2): the T-08-132 fixture (item role
     * {@code sandbox.*} with Configure for u1, one CONFIGURE window on the folder {@code ops}). u1
     * POSTs with a crumb {@code job/ops/confirm%52ename?newName=sandbox2},
     * {@code job/ops/%63onfirmRename?newName=sandbox3} and
     * {@code job/ops/confirm%52ename/?newName=sandbox4}. Each answers 400 with the D-71c refusal
     * for 'ops' (Folder) and "Nothing was renamed.", {@code ops} and {@code ops/prod} keep their
     * names, nothing exists at the new names, u1 holds no Configure on {@code ops/prod}, and each
     * attempt adds one GRANT_VIOLATION naming u1 and {@code ops}. Guard: the window still confers
     * Configure on {@code ops}.
     */
    @Test
    public void t_08_157_roleStrategyEncodedFolderRenamesAreRefused() throws Exception {
        openWindow("ops");
        assertTrue(can("u1", ops, Item.CONFIGURE), "premise: the window confers Configure on ops");
        assertFalse(can("u1", opsProd, Item.CONFIGURE), "premise: the role does not reach ops/prod");
        int expected = records(ChangeType.GRANT_VIOLATION).size();
        String[][] attempts = {
            {"confirm%52ename", "sandbox2"}, {"%63onfirmRename", "sandbox3"}, {"confirm%52ename/", "sandbox4"},
        };
        for (String[] attempt : attempts) {
            String path = ops.getUrl() + attempt[0] + "?newName=" + attempt[1];
            RenameRefusalFixtures.assertWindowRenameRefused(RenameRefusalFixtures.postPath(j, "u1", path), "ops", "Folder", "POST " + path);
            assertNotNull(j.jenkins.getItemByFullName("ops"), path + " must leave ops in place");
            assertNotNull(j.jenkins.getItemByFullName("ops/prod"), path + " must leave ops/prod under its full name");
            assertNull(j.jenkins.getItemByFullName(attempt[1]), "nothing may exist at " + attempt[1]);
            assertEquals("ops/prod", opsProd.getFullName());
            assertFalse(can("u1", opsProd, Item.CONFIGURE), "u1 must not gain Configure on ops/prod through " + path);
            expected++;
            List<ChangeRecord> violations = records(ChangeType.GRANT_VIOLATION);
            assertEquals(expected, violations.size(), path + " must be recorded once as GRANT_VIOLATION, got " + violations);
            ChangeRecord rec = violations.get(violations.size() - 1);
            assertEquals("u1", rec.getUser(), "the GRANT_VIOLATION of " + path + " names u1");
            assertTrue(mentions(rec, "ops"), "the GRANT_VIOLATION names the folder ops: " + describe(rec));
        }
        assertTrue(can("u1", ops, Item.CONFIGURE), "guard: the window still confers Configure on ops after the refusals");
    }

    /**
     * T-08-158 (security-36 S-36-02, Probe ROLE2; D-71c ruling 1): u1's item role
     * {@code sandbox.*} (Read, Configure, Build, Workspace) and u1's approved CONFIGURE window on the
     * top-level job {@code deploy}. u1's {@code confirmRename} of {@code deploy} to
     * {@code sandbox-deploy} (which would put the job under the role's pattern) answers 400 with the
     * D-71c refusal for 'deploy' (Freestyle project) and "Nothing was renamed."; so do
     * {@code confirm%52ename?newName=sandbox-deploy2} and core's {@code doRename?newName=sandbox-deploy3}.
     * After each, {@code deploy} keeps its name, nothing exists at the new name, u1 has gained neither
     * Build nor Workspace on the job, and one GRANT_VIOLATION names u1 and {@code deploy}. Once the
     * administrator revokes the window, u1 holds neither Configure nor Build on {@code deploy}.
     * Premises: before the rename the window confers Configure, the role confers Build on the
     * folder {@code sandbox} and nothing on {@code deploy}.
     */
    @Test
    public void t_08_158_roleStrategyJobRenameIntoAPatternIsRefused() throws Exception {
        assertTrue(can("u1", sandbox, Item.BUILD), "premise: the item role sandbox.* confers Build on a matching name");
        assertFalse(can("u1", deploy, Item.BUILD), "premise: the role does not reach deploy");
        String windowId = openWindow("deploy");
        assertTrue(can("u1", deploy, Item.CONFIGURE), "premise: the window confers Configure on deploy");
        assertFalse(can("u1", deploy, Item.BUILD), "premise: the window confers no Build on deploy");
        assertFalse(can("u1", deploy, Item.WORKSPACE), "premise: the window confers no Workspace on deploy");
        int expected = records(ChangeType.GRANT_VIOLATION).size();

        String[][] attempts = {
            {"confirmRename", "sandbox-deploy"}, {"confirm%52ename", "sandbox-deploy2"}, {"doRename", "sandbox-deploy3"},
        };
        for (String[] attempt : attempts) {
            String path = deploy.getUrl() + attempt[0] + "?newName=" + attempt[1];
            RenameRefusalFixtures.assertWindowRenameRefused(RenameRefusalFixtures.postPath(j, "u1", path), "deploy", "Freestyle project",
                    "POST " + path);
            assertNotNull(j.jenkins.getItemByFullName("deploy"), path + " must leave deploy under its name");
            assertNull(j.jenkins.getItemByFullName(attempt[1]), "nothing may exist at " + attempt[1]);
            assertEquals("deploy", deploy.getFullName());
            assertFalse(can("u1", deploy, Item.BUILD), "S-36-02: u1 must not gain Build on deploy through " + path);
            assertFalse(can("u1", deploy, Item.WORKSPACE), "S-36-02: u1 must not gain Workspace on deploy through " + path);
            expected++;
            List<ChangeRecord> violations = records(ChangeType.GRANT_VIOLATION);
            assertEquals(expected, violations.size(), path + " must be recorded once as GRANT_VIOLATION, got " + violations);
            ChangeRecord rec = violations.get(violations.size() - 1);
            assertEquals("u1", rec.getUser(), "the GRANT_VIOLATION of " + path + " names u1");
            assertTrue(mentions(rec, "deploy"), "the GRANT_VIOLATION names the job deploy: " + describe(rec));
        }

        assertSuccess(ApproverFormFixtures.post(j, "admin", "batch-control/grants/" + windowId + "/revoke", List.of()),
                "fixture: the administrator revokes u1's window");
        assertFalse(can("u1", deploy, Item.CONFIGURE), "after the revocation u1 holds no Configure on deploy");
        assertFalse(can("u1", deploy, Item.BUILD), "after the revocation u1 holds no Build on deploy");
    }

    /**
     * T-08-185 (security-38 S-38-01, role-strategy folder fixture; D-71c ruling 2): the T-08-132
     * fixture (item role {@code sandbox.*} with Configure for u1, one CONFIGURE window on the folder
     * {@code ops}). u1 POSTs {@code job/ops/confirmRename/extra?newName=sandbox5},
     * {@code job/ops/confirm%52ename/x?newName=sandbox6} and {@code job/ops/confirmRename//x?newName=sandbox7}
     * (a rename would put {@code ops/prod} under the role's pattern). Each answers 400 with the D-71c
     * refusal for 'ops' (Folder) and "Nothing was renamed.", {@code ops} and {@code ops/prod} keep
     * their names, nothing exists at the new name, u1 holds no Configure on {@code ops/prod}, and each
     * adds one GRANT_VIOLATION naming u1 and {@code ops}. After the administrator revokes the window
     * u1 holds no Configure on {@code ops} or {@code ops/prod} (no standing permission was gained).
     * Guard: before the revocation the window still confers Configure on {@code ops}.
     */
    @Test
    public void t_08_185_roleStrategyTrailingSegmentFolderRenamesAreRefused() throws Exception {
        String windowId = openWindow("ops");
        assertTrue(can("u1", ops, Item.CONFIGURE), "premise: the window confers Configure on ops");
        assertFalse(can("u1", opsProd, Item.CONFIGURE), "premise: the role does not reach ops/prod");
        String[][] attempts = {{"confirmRename/extra", "sandbox5"}, {"confirm%52ename/x", "sandbox6"}, {"confirmRename//x", "sandbox7"}};
        int expected = records(ChangeType.GRANT_VIOLATION).size();
        for (String[] attempt : attempts) {
            String path = ops.getUrl() + attempt[0] + "?newName=" + attempt[1];
            RenameRefusalFixtures.assertWindowRenameRefused(RenameRefusalFixtures.postPath(j, "u1", path), "ops", "Folder", "POST " + path);
            assertNotNull(j.jenkins.getItemByFullName("ops"), path + " must leave ops in place");
            assertNotNull(j.jenkins.getItemByFullName("ops/prod"), path + " must leave ops/prod under its full name");
            assertNull(j.jenkins.getItemByFullName(attempt[1]), "nothing may exist at " + attempt[1]);
            assertFalse(can("u1", opsProd, Item.CONFIGURE), "S-38-01: u1 must not gain Configure on ops/prod through " + path);
            expected++;
            List<ChangeRecord> violations = records(ChangeType.GRANT_VIOLATION);
            assertEquals(expected, violations.size(), path + " must be recorded once as GRANT_VIOLATION, got " + violations);
            ChangeRecord rec = violations.get(violations.size() - 1);
            assertEquals("u1", rec.getUser(), "the GRANT_VIOLATION of " + path + " names u1");
            assertTrue(mentions(rec, "ops"), "the GRANT_VIOLATION names the folder ops: " + describe(rec));
        }
        assertTrue(can("u1", ops, Item.CONFIGURE), "guard: the window still confers Configure on ops after the refusals");

        assertSuccess(ApproverFormFixtures.post(j, "admin", "batch-control/grants/" + windowId + "/revoke", List.of()),
                "fixture: the administrator revokes u1's window");
        assertFalse(can("u1", ops, Item.CONFIGURE), "after the revocation u1 holds no Configure on ops");
        assertFalse(can("u1", opsProd, Item.CONFIGURE), "after the revocation u1 holds no Configure on ops/prod");
    }

    /**
     * T-08-186 (security-38 S-38-01, role-strategy job fixture, the S-36-02 escalation; D-71c rulings
     * 1 and 2): the T-08-158 fixture (item role {@code sandbox.*} with Read, Configure, Build and
     * Workspace for u1; u1's CONFIGURE window on the top-level job {@code deploy}). u1 POSTs
     * {@code job/deploy/confirmRename/extra?newName=sandbox-deploy5},
     * {@code job/deploy/doRename/x?newName=sandbox-deploy6} and
     * {@code job/deploy/do%52ename/x?newName=sandbox-deploy7}. Each answers 400 with the D-71c refusal
     * for 'deploy' (Freestyle project) and "Nothing was renamed.", {@code deploy} keeps its name,
     * nothing exists at the new name, u1 gains neither Build nor Workspace on the job, and each adds
     * one GRANT_VIOLATION naming u1 and {@code deploy}. Once the administrator revokes the window, u1
     * holds neither Configure, Build nor Workspace on {@code deploy}. Premises: the window confers
     * Configure, not Build or Workspace; the role confers Build on a matching name.
     */
    @Test
    public void t_08_186_roleStrategyTrailingSegmentJobRenamesAreRefused() throws Exception {
        assertTrue(can("u1", sandbox, Item.BUILD), "premise: the item role sandbox.* confers Build on a matching name");
        String windowId = openWindow("deploy");
        assertTrue(can("u1", deploy, Item.CONFIGURE), "premise: the window confers Configure on deploy");
        assertFalse(can("u1", deploy, Item.BUILD), "premise: the window confers no Build on deploy");
        assertFalse(can("u1", deploy, Item.WORKSPACE), "premise: the window confers no Workspace on deploy");
        String[][] attempts = {
            {"confirmRename/extra", "sandbox-deploy5"}, {"doRename/x", "sandbox-deploy6"}, {"do%52ename/x", "sandbox-deploy7"},
        };
        int expected = records(ChangeType.GRANT_VIOLATION).size();
        for (String[] attempt : attempts) {
            String path = deploy.getUrl() + attempt[0] + "?newName=" + attempt[1];
            RenameRefusalFixtures.assertWindowRenameRefused(RenameRefusalFixtures.postPath(j, "u1", path), "deploy", "Freestyle project",
                    "POST " + path);
            assertEquals("deploy", deploy.getFullName(), path + " must leave deploy under its name");
            assertNull(j.jenkins.getItemByFullName(attempt[1]), "nothing may exist at " + attempt[1]);
            assertFalse(can("u1", deploy, Item.BUILD), "S-38-01: u1 must not gain Build on deploy through " + path);
            assertFalse(can("u1", deploy, Item.WORKSPACE), "S-38-01: u1 must not gain Workspace on deploy through " + path);
            expected++;
            List<ChangeRecord> violations = records(ChangeType.GRANT_VIOLATION);
            assertEquals(expected, violations.size(), path + " must be recorded once as GRANT_VIOLATION, got " + violations);
            ChangeRecord rec = violations.get(violations.size() - 1);
            assertEquals("u1", rec.getUser(), "the GRANT_VIOLATION of " + path + " names u1");
            assertTrue(mentions(rec, "deploy"), "the GRANT_VIOLATION names the job deploy: " + describe(rec));
        }

        assertSuccess(ApproverFormFixtures.post(j, "admin", "batch-control/grants/" + windowId + "/revoke", List.of()),
                "fixture: the administrator revokes u1's window");
        assertFalse(can("u1", deploy, Item.CONFIGURE), "after the revocation u1 holds no Configure on deploy");
        assertFalse(can("u1", deploy, Item.BUILD), "after the revocation u1 holds no Build on deploy");
        assertFalse(can("u1", deploy, Item.WORKSPACE), "after the revocation u1 holds no Workspace on deploy");
    }

    // ---------------------------------------------------------------- helpers

    /** Files u1's CONFIGURE window on {@code fullName} through the form; a1 approves it. Returns the window's id. */
    private String openWindow(String fullName) throws Exception {
        String id = submitGrantOk(j, "u1", fullName, List.of("CONFIGURE"), 30, "maintenance of " + fullName, null, "a1");
        assertSuccess(decideGrant(j, "a1", id, "approve", "ok"), "fixture: approval by a1");
        return WindowStateFixtures.windowId("u1", fullName);
    }

    private static boolean mentions(ChangeRecord rec, String text) {
        return String.valueOf(rec.getTarget()).contains(text) || String.valueOf(rec.getDetail()).contains(text);
    }

    private static String describe(ChangeRecord rec) {
        return "user=" + rec.getUser() + " target=" + rec.getTarget() + " detail=" + rec.getDetail();
    }


    private WebResponse rename(String user, Item item, String newName) throws Exception {
        return ApproverFormFixtures.post(j, user, item.getUrl() + "confirmRename", List.of(new NameValuePair("newName", newName)));
    }

    private static boolean can(String user, Item item, hudson.security.Permission p) {
        return item.getACL().hasPermission2(User.getById(user, true).impersonate2(), p);
    }

    private static Map<String, RoleMap> roles() {
        TreeMap<Role, Set<PermissionEntry>> global = new TreeMap<>();
        global.put(new Role("admin", Pattern.compile(".*"), Set.of(Jenkins.ADMINISTER), ""), set(PermissionEntry.user("admin")));
        global.put(new Role("reader", Pattern.compile(".*"), Set.of(Jenkins.READ, Item.READ), ""),
                set(PermissionEntry.user("u1"), PermissionEntry.user("a1")));
        global.put(new Role("requester", Pattern.compile(".*"), Set.of(BatchControlPermissions.REQUEST_GRANT), ""),
                set(PermissionEntry.user("u1")));
        global.put(new Role("approver", Pattern.compile(".*"), Set.of(BatchControlPermissions.APPROVE), ""), set(PermissionEntry.user("a1")));
        TreeMap<Role, Set<PermissionEntry>> items = new TreeMap<>();
        items.put(new Role("sandbox", Pattern.compile("sandbox.*"), Set.of(Item.READ, Item.CONFIGURE, Item.BUILD, Item.WORKSPACE), ""),
                set(PermissionEntry.user("u1")));
        Map<String, RoleMap> m = new HashMap<>();
        m.put(RoleBasedAuthorizationStrategy.GLOBAL, new RoleMap(global));
        m.put(RoleBasedAuthorizationStrategy.PROJECT, new RoleMap(items));
        m.put(RoleBasedAuthorizationStrategy.SLAVE, new RoleMap(new TreeMap<>()));
        return m;
    }

    @SafeVarargs
    private static <E> Set<E> set(E... e) {
        return new HashSet<>(Arrays.asList(e));
    }
}
