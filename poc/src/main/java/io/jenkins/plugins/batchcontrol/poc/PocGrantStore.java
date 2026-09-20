package io.jenkins.plugins.batchcontrol.poc;

import hudson.security.Permission;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory grant store for design assumption C.
 * Expiry is decided by comparing the injected {@link Clock} at check time — no timers.
 */
public final class PocGrantStore {

    /** A temporary permission grant. */
    public static final class Grant {
        final String user;
        final String scopeFullName;
        final boolean folderScope;
        final Set<Permission> permissions;
        final Instant expiresAt;

        Grant(String user, String scopeFullName, boolean folderScope,
              Set<Permission> permissions, Instant expiresAt) {
            this.user = user;
            this.scopeFullName = scopeFullName;
            this.folderScope = folderScope;
            this.permissions = new HashSet<>(permissions);
            this.expiresAt = expiresAt;
        }
    }

    private static final List<Grant> GRANTS = new CopyOnWriteArrayList<>();

    private static volatile Clock clock = Clock.systemUTC();

    private PocGrantStore() {
    }

    public static void setClock(Clock c) {
        clock = c;
    }

    public static void addGrant(String user, String scopeFullName, boolean folderScope,
                                Set<Permission> permissions, Instant expiresAt) {
        GRANTS.add(new Grant(user, scopeFullName, folderScope, permissions, expiresAt));
    }

    public static void reset() {
        GRANTS.clear();
        clock = Clock.systemUTC();
    }

    /**
     * True when {@code user} holds an unexpired grant covering {@code itemFullName} and
     * {@code permission}. A grant is dead the instant {@code now >= expiresAt}.
     */
    public static boolean hasActiveGrant(String user, String itemFullName, Permission permission) {
        Instant now = clock.instant();
        for (Grant g : GRANTS) {
            if (!g.user.equals(user)) {
                continue;
            }
            if (!g.permissions.contains(permission)) {
                continue;
            }
            if (!now.isBefore(g.expiresAt)) {
                continue; // expired: denied from the first check at/after expiresAt
            }
            if (matches(g, itemFullName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Scope matching. Folder scope matches the folder itself (for CREATE checked on the
     * folder ACL) and any item strictly below it, using a {@code /} boundary so that a
     * grant on {@code team/batch} never matches {@code team/batch-other}.
     */
    private static boolean matches(Grant g, String itemFullName) {
        if (g.folderScope) {
            return itemFullName.equals(g.scopeFullName)
                    || itemFullName.startsWith(g.scopeFullName + "/");
        }
        return itemFullName.equals(g.scopeFullName);
    }
}
