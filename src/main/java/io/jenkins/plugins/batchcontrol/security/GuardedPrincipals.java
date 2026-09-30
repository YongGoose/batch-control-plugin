package io.jenkins.plugins.batchcontrol.security;

import hudson.model.User;
import hudson.security.SecurityRealm;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.springframework.security.core.GrantedAuthority;

/**
 * D-58 (e2e-03 DEF-38): the guarded principals of an item. These are the users who hold, or held
 * within {@link #WINDOW}, a Batch Control grant that covers the item (its scope includes the item,
 * lies below a folder item, or the item was created through it), and the groups those users belong
 * to as the security realm reports them now, always including {@code authenticated}.
 *
 * <p>The realm lookups are cached per user for {@link #GROUP_TTL_MINUTES} minutes, and the cache is
 * bounded at {@link #MAX_CACHED_USERS} users. A lookup that fails leaves the user with
 * {@code authenticated} only; this class never throws. Membership at the time the grant was issued
 * is not stored with the grant, so a group the user has left since is not guarded (documented).
 */
@Restricted(NoExternalUse.class)
public final class GuardedPrincipals {

    private static final Logger LOGGER = Logger.getLogger(GuardedPrincipals.class.getName());

    /** How long after a grant ended its holder stays guarded (D-58). */
    public static final Duration WINDOW = Duration.ofDays(30);

    static final long GROUP_TTL_MINUTES = 5;

    static final int MAX_CACHED_USERS = 1_000;

    private static final class CachedGroups {
        final Set<String> groups;
        final long atNanos;

        CachedGroups(Set<String> groups, long atNanos) {
            this.groups = groups;
            this.atNanos = atNanos;
        }
    }

    private static final Map<String, CachedGroups> GROUPS = new LinkedHashMap<>();

    private final Set<String> users;
    private final Set<String> groups;

    private GuardedPrincipals(Set<String> users, Set<String> groups) {
        this.users = users;
        this.groups = groups;
    }

    /** The guarded principals of the item {@code itemFullName}; empty when no grant covers it. */
    public static GuardedPrincipals of(String itemFullName) {
        Set<String> users;
        try {
            users = GrantService.get().recentHolders(itemFullName, WINDOW);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not list the grant holders of '" + itemFullName + "'", e);
            users = Collections.emptySet();
        }
        Set<String> groups = new LinkedHashSet<>();
        if (!users.isEmpty()) {
            groups.add(SecurityRealm.AUTHENTICATED_AUTHORITY2.getAuthority());
            for (String user : users) {
                groups.addAll(groupsOf(user));
            }
        }
        return new GuardedPrincipals(users, groups);
    }

    public boolean isEmpty() {
        return users.isEmpty();
    }

    public Set<String> getUsers() {
        return Collections.unmodifiableSet(users);
    }

    /** Whether {@code sid} names a guarded user (under Jenkins' user id strategy). */
    public boolean isUser(String sid) {
        for (String user : users) {
            if (sameUser(user, sid)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code sid} names a guarded group (under the realm's group id strategy). */
    public boolean isGroup(String sid) {
        for (String group : groups) {
            if (sameGroup(group, sid)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameUser(String a, String b) {
        return Jenkins.getInstanceOrNull() == null ? a.equals(b) : User.idStrategy().equals(a, b);
    }

    private static boolean sameGroup(String a, String b) {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        return jenkins == null ? a.equals(b) : jenkins.getSecurityRealm().getGroupIdStrategy().equals(a, b);
    }

    private static Set<String> groupsOf(String user) {
        long now = System.nanoTime();
        synchronized (GROUPS) {
            CachedGroups cached = GROUPS.get(user);
            if (cached != null && now - cached.atNanos < TimeUnit.MINUTES.toNanos(GROUP_TTL_MINUTES)) {
                return cached.groups;
            }
        }
        Set<String> groups = new LinkedHashSet<>();
        try {
            Jenkins jenkins = Jenkins.getInstanceOrNull();
            if (jenkins != null && jenkins.getSecurityRealm() != SecurityRealm.NO_AUTHENTICATION) {
                for (GrantedAuthority authority : jenkins.getSecurityRealm().loadUserByUsername2(user).getAuthorities()) {
                    if (authority.getAuthority() != null && !authority.getAuthority().isEmpty()) {
                        groups.add(authority.getAuthority());
                    }
                }
            }
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "Could not read the groups of '" + user + "'", e);
        }
        synchronized (GROUPS) {
            if (GROUPS.size() >= MAX_CACHED_USERS) {
                GROUPS.clear();
            }
            GROUPS.put(user, new CachedGroups(Collections.unmodifiableSet(groups), now));
        }
        return groups;
    }
}
