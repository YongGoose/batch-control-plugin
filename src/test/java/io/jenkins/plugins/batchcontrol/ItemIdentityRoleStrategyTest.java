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
 * SPEC item 8 (D-71a ruling 2), the role-strategy variant of security-34 S-34-01: role-strategy
 * item roles are regular expressions on full names, so renaming a folder can move its contents
 * under a pattern its holder already has. "While change control is on, renaming an item group ...
 * whose Configure comes only from a window is refused and recorded as GRANT_VIOLATION." Matrix
 * row T-08-132 (note 262).
 *
 * <p>Batch Control role-strategy strategy (D-35a); global roles give Overall/Read and Item/Read to
 * u1 and a1, RequestGrant to u1, Approve to a1, Administer to admin; the item role
 * {@code sandbox} with pattern {@code sandbox.*} gives u1 Item/Read and Item/Configure. u1 holds
 * one approved CONFIGURE window on the folder {@code ops} (job {@code ops/prod}).
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-71a, docs/reports/security-34.md and
 * docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class ItemIdentityRoleStrategyTest {

    private JenkinsRule j;
    private Folder ops;
    private FreeStyleProject opsProd;
    private Folder sandbox;

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

    // ---------------------------------------------------------------- helpers

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
        items.put(new Role("sandbox", Pattern.compile("sandbox.*"), Set.of(Item.READ, Item.CONFIGURE), ""), set(PermissionEntry.user("u1")));
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
