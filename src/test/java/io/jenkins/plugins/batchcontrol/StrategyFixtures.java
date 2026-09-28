package io.jenkins.plugins.batchcontrol;

import com.michelin.cio.hudson.plugins.rolestrategy.PermissionEntry;
import com.michelin.cio.hudson.plugins.rolestrategy.Role;
import com.michelin.cio.hudson.plugins.rolestrategy.RoleBasedAuthorizationStrategy;
import com.michelin.cio.hudson.plugins.rolestrategy.RoleMap;
import com.synopsys.arc.jenkins.plugins.rolestrategy.RoleType;
import hudson.model.AdministrativeMonitor;
import hudson.model.Computer;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.AccessControlled;
import hudson.security.GlobalMatrixAuthorizationStrategy;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared fixtures for the D-35a/b/c strategy rows (T-02-10..33). Ported from the PoC-5 support
 * class (poc/.../Poc5Support.java); the users and roles are the same so the PoC evidence and
 * these rows can be read side by side.
 *
 * <p>Users: {@code admin} (Overall/Administer), {@code alice}, {@code bob}, {@code carol}
 * (Overall/Read + Item/Read), {@code a1} (the designated approver), {@code m1}
 * (BatchControl/Manage but not Administer), {@code c1} (a native Item/Configure — the control
 * for "Configure that does not come from a grant"). bob and carol hold BatchControl/RequestGrant.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-35a..c and docs/POC-RESULTS.md PoC-5 only
 * (no src/main knowledge).
 */
final class StrategyFixtures {

    static final Instant T0 = Instant.parse("2026-09-20T00:00:00Z");
    static final int WINDOW_MINUTES = 30;
    static final String MONITOR_ID = "batch-control-strategy";
    static final String LEGACY_WRAPPER = "io.jenkins.plugins.batchcontrol.security.BatchControlAuthorizationStrategy";

    private StrategyFixtures() {
    }

    /** Fills a matrix (plain parent or Batch Control subclass) with the standard users. */
    static <T extends GlobalMatrixAuthorizationStrategy> T matrix(T s) {
        s.add(Jenkins.ADMINISTER, org.jenkinsci.plugins.matrixauth.PermissionEntry.user("admin"));
        for (String u : new String[] {"alice", "bob", "carol", "a1", "m1", "c1"}) {
            s.add(Jenkins.READ, org.jenkinsci.plugins.matrixauth.PermissionEntry.user(u));
            s.add(Item.READ, org.jenkinsci.plugins.matrixauth.PermissionEntry.user(u));
        }
        s.add(BatchControlPermissions.REQUEST_GRANT, org.jenkinsci.plugins.matrixauth.PermissionEntry.user("bob"));
        s.add(BatchControlPermissions.REQUEST_GRANT, org.jenkinsci.plugins.matrixauth.PermissionEntry.user("carol"));
        s.add(BatchControlPermissions.APPROVE, org.jenkinsci.plugins.matrixauth.PermissionEntry.user("a1"));
        s.add(BatchControlPermissions.MANAGE, org.jenkinsci.plugins.matrixauth.PermissionEntry.user("m1"));
        s.add(Item.CONFIGURE, org.jenkinsci.plugins.matrixauth.PermissionEntry.user("c1"));
        return s;
    }

    /**
     * Roles (PoC-5): global {@code admin} (admin); global {@code reader} with Overall/Read and
     * Item/Read (bob, carol, a1, m1); global {@code requester} with RequestGrant (bob, carol);
     * global {@code approver} with Approve (a1); global {@code manager} with Manage (m1); item
     * role {@code team} on {@code team-.*} with Read/Configure/Create (bob); agent role
     * {@code agents} on {@code agent-.*} with Computer/Configure (bob).
     */
    static Map<String, RoleMap> roles() {
        TreeMap<Role, Set<PermissionEntry>> global = new TreeMap<>();
        global.put(new Role("admin", Pattern.compile(".*"), Set.of(Jenkins.ADMINISTER), ""), set(PermissionEntry.user("admin")));
        global.put(new Role("reader", Pattern.compile(".*"), Set.of(Jenkins.READ, Item.READ), ""),
                set(PermissionEntry.user("bob"), PermissionEntry.user("carol"), PermissionEntry.user("a1"), PermissionEntry.user("m1")));
        global.put(new Role("requester", Pattern.compile(".*"), Set.of(BatchControlPermissions.REQUEST_GRANT), ""),
                set(PermissionEntry.user("bob"), PermissionEntry.user("carol")));
        global.put(new Role("approver", Pattern.compile(".*"), Set.of(BatchControlPermissions.APPROVE), ""), set(PermissionEntry.user("a1")));
        global.put(new Role("manager", Pattern.compile(".*"), Set.of(BatchControlPermissions.MANAGE), ""), set(PermissionEntry.user("m1")));
        TreeMap<Role, Set<PermissionEntry>> items = new TreeMap<>();
        items.put(new Role("team", Pattern.compile("team-.*"), Set.of(Item.READ, Item.CONFIGURE, Item.CREATE), ""),
                set(PermissionEntry.user("bob")));
        TreeMap<Role, Set<PermissionEntry>> agents = new TreeMap<>();
        agents.put(new Role("agents", Pattern.compile("agent-.*"), Set.of(Computer.CONFIGURE), ""), set(PermissionEntry.user("bob")));
        Map<String, RoleMap> m = new HashMap<>();
        m.put(RoleBasedAuthorizationStrategy.GLOBAL, new RoleMap(global));
        m.put(RoleBasedAuthorizationStrategy.PROJECT, new RoleMap(items));
        m.put(RoleBasedAuthorizationStrategy.SLAVE, new RoleMap(agents));
        return m;
    }

    @SafeVarargs
    private static <E> Set<E> set(E... e) {
        return new HashSet<>(Arrays.asList(e));
    }

    /** Change control on, a1 the only approver (SPEC 3). */
    static BatchControlGlobalConfiguration changeControlOn() {
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        return cfg;
    }

    static boolean has(AccessControlled o, String user, Permission p) {
        return o.getACL().hasPermission2(User.getById(user, true).impersonate2(), p);
    }

    static <V> V as(String user, Callable<V> c) throws Exception {
        try (ACLContext ignored = ACL.as2(User.getById(user, true).impersonate2())) {
            return c.call();
        }
    }

    /**
     * {@code user} requests a grant, a1 approves it. Each row asserts the grant's effect itself
     * (through {@link #has}) as its premise.
     */
    static Grant grant(String user, GrantScope.Type type, String scopeName, List<GrantAction> actions)
            throws Exception {
        GrantScope scope = new GrantScope(type, scopeName);
        GrantRequest request = as(user, () -> GrantRequestService.get().create(scope, actions, WINDOW_MINUTES,
                "maintenance for " + scopeName, "a1"));
        Grant grant = as("a1", () -> GrantRequestService.get().approve(request.getId(), "ok"));
        assertNotNull(grant, "fixture: the approval must produce a grant");
        return grant;
    }

    /** Change records of {@code type} in the month of T0 and the current month (deduplicated by identity). */
    static List<ChangeRecord> records(ChangeType type) {
        Set<YearMonth> months = new LinkedHashSet<>(Arrays.asList(YearMonth.from(T0.atZone(ZoneOffset.UTC)), YearMonth.now()));
        List<ChangeRecord> out = new ArrayList<>();
        for (YearMonth month : months) {
            for (ChangeRecord rec : FileStore.get().listChangeRecords(month)) {
                if (rec.getType() == type && !out.contains(rec)) {
                    out.add(rec);
                }
            }
        }
        return out;
    }

    static AdministrativeMonitor strategyMonitor() {
        AdministrativeMonitor monitor = Jenkins.get().getAdministrativeMonitor(MONITOR_ID);
        assertNotNull(monitor, "the administrative monitor '" + MONITOR_ID + "' must be registered (D-35a)");
        return monitor;
    }

    /** Stable rendering of a matrix for equality checks: "permissionId=TYPE:sid,..." sorted. */
    static Set<String> describeMatrix(Map<Permission, Set<org.jenkinsci.plugins.matrixauth.PermissionEntry>> entries) {
        Set<String> out = new TreeSet<>();
        entries.forEach((p, sids) -> sids.forEach(e -> out.add(p.getId() + "=" + e.getType() + ":" + e.getSid())));
        return out;
    }

    /** Stable rendering of every role map: "Type/name/pattern/[perm ids]/[entries]" sorted. */
    static Set<String> describeRoles(RoleBasedAuthorizationStrategy s) {
        Set<String> out = new TreeSet<>();
        for (RoleType type : RoleType.values()) {
            s.getGrantedRolesEntries(type).forEach((role, entries) -> out.add(type + "/" + role.getName() + "/"
                    + role.getPattern().pattern() + "/"
                    + role.getPermissions().stream().map(Permission::getId).sorted().collect(Collectors.toList()) + "/"
                    + entries.stream().map(e -> e.getType() + ":" + e.getSid()).sorted().collect(Collectors.toList())));
        }
        return out;
    }
}
