package io.jenkins.plugins.batchcontrol.ops;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.model.AdministrativeMonitor;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.AuthorizationStrategy;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.Messages;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.security.GrantLayer;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Warns administrators when change control cannot actually control changes (SPEC item 8):
 * change control is on, but either the global authorization strategy is not a Batch Control
 * strategy (so grants can never apply, D-35a), or some known non-admin user holds
 * Item/Configure, Item/Create or Item/Delete directly from the strategy — a standing change
 * permission that bypasses the JIT grant process.
 *
 * <p>The scan uses the strategy's root ACL, which carries no grant scope (S-13), so what it finds
 * is always a native entry, never an open grant.
 *
 * <p>Candidate users come from {@link User#getAll()} plus, for matrix-family strategies, the
 * strategy's own granted sids (read reflectively — matrix-auth is an optional dependency).
 * The scan is capped at {@value #MAX_CANDIDATES} candidates and every impersonation failure is
 * swallowed: this is a best-effort warning, never an enforcement point.
 *
 * <p>S-05: {@code isActivated()} is evaluated by Jenkins on (almost) every admin page render,
 * and the candidate scan performs up to {@value #MAX_CANDIDATES} synchronous security-realm
 * lookups (remote round-trips on LDAP/AD). The scan result is therefore cached per strategy
 * strategy instance with a {@value #CACHE_TTL_MINUTES}-minute TTL (monotonic {@link System#nanoTime}):
 * a strategy swap recomputes immediately (identity key), a change-control toggle invalidates
 * explicitly, and permission edits inside the same strategy show up within the TTL. The cheap
 * pre-checks (switch off, wrong strategy) are never cached.
 */
@Extension
@Restricted(NoExternalUse.class)
public class ConfigureWithoutGrantMonitor extends AdministrativeMonitor {

    private static final Logger LOGGER =
            Logger.getLogger(ConfigureWithoutGrantMonitor.class.getName());

    private static final int MAX_CANDIDATES = 100;

    private static final long CACHE_TTL_MINUTES = 5;
    private static final long CACHE_TTL_NANOS =
            java.util.concurrent.TimeUnit.MINUTES.toNanos(CACHE_TTL_MINUTES);

    private static final Permission[] CHANGE_PERMISSIONS =
            {Item.CONFIGURE, Item.CREATE, Item.DELETE};

    /** The cached result of one expensive candidate scan (immutable snapshot). */
    private static final class CachedScan {
        final AuthorizationStrategy strategy; // identity key: a swapped strategy recomputes
        final boolean standingPermissionFound;
        final long computedAtNanos;

        CachedScan(AuthorizationStrategy strategy, boolean standingPermissionFound,
                   long computedAtNanos) {
            this.strategy = strategy;
            this.standingPermissionFound = standingPermissionFound;
            this.computedAtNanos = computedAtNanos;
        }
    }

    private static volatile CachedScan cachedScan;

    /** Drops the cached scan (called on a change-control toggle; next render recomputes). */
    public static void invalidateCache() {
        cachedScan = null;
    }

    @Override
    public String getDisplayName() {
        return Messages.ConfigureWithoutGrantMonitor_DisplayName();
    }

    @Override
    public boolean isActivated() {
        if (!BatchControlGlobalConfiguration.get().isChangeControlEnabled()) {
            return false;
        }
        AuthorizationStrategy strategy = Jenkins.get().getAuthorizationStrategy();
        if (!GrantLayer.isGrantLayered(strategy)) {
            // Without a Batch Control strategy, grants can never apply: change control is a no-op.
            return true;
        }
        return cachedScanResult(strategy);
    }

    /**
     * The TTL-cached scan result for the given strategy: serves the cached value while it is
     * fresh and keyed to the same strategy instance, otherwise recomputes and stores. Static
     * because the cache is static — the monitor is an extension singleton either way.
     */
    private static boolean cachedScanResult(AuthorizationStrategy strategy) {
        CachedScan cached = cachedScan;
        long now = System.nanoTime();
        if (cached != null && cached.strategy == strategy
                && now - cached.computedAtNanos < CACHE_TTL_NANOS) {
            return cached.standingPermissionFound;
        }
        boolean found = scanForStandingPermissions(strategy);
        cachedScan = new CachedScan(strategy, found, now);
        return found;
    }

    /** The expensive part: impersonates candidate sids against the strategy's root ACL. */
    private static boolean scanForStandingPermissions(AuthorizationStrategy strategy) {
        ACL rootAcl = strategy.getRootACL();
        for (String sid : candidateSids(strategy)) {
            Authentication auth = authenticate(sid);
            if (auth == null) {
                continue;
            }
            if (rootAcl.hasPermission2(auth, Jenkins.ADMINISTER)) {
                continue; // admin bypass is out of scope (SPEC section 1)
            }
            for (Permission permission : CHANGE_PERMISSIONS) {
                if (rootAcl.hasPermission2(auth, permission)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Known users plus (reflectively) the sids a matrix-family strategy grants anything to. */
    private static Set<String> candidateSids(AuthorizationStrategy strategy) {
        Set<String> sids = new LinkedHashSet<>();
        for (User user : User.getAll()) {
            if (sids.size() >= MAX_CANDIDATES) {
                return sids;
            }
            sids.add(user.getId());
        }
        for (String sid : strategyPermissionSids(strategy)) {
            if (sids.size() >= MAX_CANDIDATES) {
                return sids;
            }
            if (!sid.isEmpty() && !ACL.ANONYMOUS_USERNAME.equals(sid) && !"authenticated".equals(sid)) {
                sids.add(sid);
            }
        }
        return sids;
    }

    /**
     * Reads the strategy's granted permission entries without a compile-time matrix-auth
     * dependency: tries {@code getAllPermissionEntries()}, a {@code List} of entries each
     * carrying a {@code getSid()} and a {@code getType()} of {@code USER}/{@code GROUP}/
     * {@code EITHER} (matrix-auth 3.0+; supersedes the deprecated {@code getAllSIDs()}, which
     * collapsed both kinds into one list of plain strings and is what this method replaces).
     *
     * <p>Entries of type {@code GROUP} are skipped: a group's sid names a security-realm group,
     * not an account, and {@link #authenticate(String)} impersonates by username — trying to
     * impersonate a group as if it were a user could either fail harmlessly or, worse, collide
     * with an unrelated user of the same name and read that user's rights as the group's.
     * {@code USER} and legacy {@code EITHER} entries are both kept, since either may name a real
     * account. A strategy without the method, or any failure resolving it, contributes nothing:
     * this is a best-effort warning, never an enforcement point.
     */
    private static Collection<String> strategyPermissionSids(AuthorizationStrategy strategy) {
        List<String> sids = new ArrayList<>();
        try {
            Method method = strategy.getClass().getMethod("getAllPermissionEntries");
            Object result = method.invoke(strategy);
            if (result instanceof Collection) {
                for (Object entry : (Collection<?>) result) {
                    if (isGroupEntry(entry)) {
                        continue;
                    }
                    String sid = sidOf(entry);
                    if (sid != null) {
                        sids.add(sid);
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.log(Level.FINE,
                    "No getAllPermissionEntries() on " + strategy.getClass().getName(), e);
        }
        return sids;
    }

    /**
     * Whether a (reflectively read) permission entry's {@code getType()} is matrix-auth's
     * {@code GROUP} constant; {@code false} for {@code USER}, {@code EITHER}, or anything
     * reflection cannot resolve.
     */
    private static boolean isGroupEntry(Object entry) {
        try {
            Object type = entry.getClass().getMethod("getType").invoke(entry);
            return type instanceof Enum && "GROUP".equals(((Enum<?>) type).name());
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** A permission entry's {@code getSid()} (reflective), or {@code null} if it cannot be read. */
    @CheckForNull
    private static String sidOf(Object entry) {
        try {
            Object sid = entry.getClass().getMethod("getSid").invoke(entry);
            return sid == null ? null : sid.toString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Best-effort authentication for a candidate sid via the security realm; {@code null} when
     * the realm does not know the sid (group names, deleted users) — such candidates are
     * skipped, never guessed.
     */
    @CheckForNull
    private static Authentication authenticate(String sid) {
        try {
            UserDetails details = Jenkins.get().getSecurityRealm().loadUserByUsername2(sid);
            return new UsernamePasswordAuthenticationToken(
                    details.getUsername(), "", details.getAuthorities());
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "Cannot impersonate candidate sid '" + sid + "'", e);
            return null;
        }
    }
}
