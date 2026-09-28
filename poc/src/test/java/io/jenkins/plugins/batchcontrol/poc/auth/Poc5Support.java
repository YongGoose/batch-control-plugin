package io.jenkins.plugins.batchcontrol.poc.auth;

import com.michelin.cio.hudson.plugins.rolestrategy.PermissionEntry;
import com.michelin.cio.hudson.plugins.rolestrategy.Role;
import com.michelin.cio.hudson.plugins.rolestrategy.RoleBasedAuthorizationStrategy;
import com.michelin.cio.hudson.plugins.rolestrategy.RoleMap;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.GlobalMatrixAuthorizationStrategy;
import hudson.security.Permission;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;

/** Shared fixtures for the PoC-5 tests. */
final class Poc5Support {

    static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private Poc5Support() {
    }

    /** A clock whose instant the test sets; the system time is never changed. */
    static final class SettableClock extends Clock {
        private volatile Instant instant;

        SettableClock(Instant i) {
            instant = i;
        }

        void set(Instant i) {
            instant = i;
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }

    /** Global matrix: admin=ADMINISTER, alice and bob = Overall/Read only. */
    static <T extends GlobalMatrixAuthorizationStrategy> T matrix(T s) {
        s.add(Jenkins.ADMINISTER, org.jenkinsci.plugins.matrixauth.PermissionEntry.user("admin"));
        s.add(Jenkins.READ, org.jenkinsci.plugins.matrixauth.PermissionEntry.user("alice"));
        s.add(Jenkins.READ, org.jenkinsci.plugins.matrixauth.PermissionEntry.user("bob"));
        s.add(Item.READ, org.jenkinsci.plugins.matrixauth.PermissionEntry.user("bob"));
        return s;
    }

    /**
     * Roles: global admin (admin), global reader (bob, carol); item role "team" pattern
     * {@code team-.*} with Read/Configure/Create for bob.
     */
    static Map<String, RoleMap> roles() {
        TreeMap<Role, Set<PermissionEntry>> global = new TreeMap<>();
        global.put(new Role("admin", Pattern.compile(".*"), Set.of(Jenkins.ADMINISTER), ""), set(PermissionEntry.user("admin")));
        global.put(new Role("reader", Pattern.compile(".*"), Set.of(Jenkins.READ), ""), set(PermissionEntry.user("bob"), PermissionEntry.user("carol")));
        TreeMap<Role, Set<PermissionEntry>> items = new TreeMap<>();
        items.put(new Role("team", Pattern.compile("team-.*"), Set.of(Item.READ, Item.CONFIGURE, Item.CREATE), ""),
                set(PermissionEntry.user("bob")));
        Map<String, RoleMap> m = new HashMap<>();
        m.put(RoleBasedAuthorizationStrategy.GLOBAL, new RoleMap(global));
        m.put(RoleBasedAuthorizationStrategy.PROJECT, new RoleMap(items));
        TreeMap<Role, Set<PermissionEntry>> agents = new TreeMap<>();
        agents.put(new Role("agents", Pattern.compile("agent-.*"), Set.of(hudson.model.Computer.CONFIGURE), ""),
                set(PermissionEntry.user("bob")));
        m.put(RoleBasedAuthorizationStrategy.SLAVE, new RoleMap(agents));
        return m;
    }

    @SafeVarargs
    private static <E> Set<E> set(E... e) {
        return new HashSet<>(Set.of(e));
    }

    static boolean has(hudson.security.AccessControlled o, String user, Permission p) {
        return o.getACL().hasPermission2(User.getById(user, true).impersonate2(), p);
    }

    static <V> V as(String user, Callable<V> c) throws Exception {
        try (ACLContext ignored = ACL.as2(User.getById(user, true).impersonate2())) {
            return c.call();
        }
    }
}
