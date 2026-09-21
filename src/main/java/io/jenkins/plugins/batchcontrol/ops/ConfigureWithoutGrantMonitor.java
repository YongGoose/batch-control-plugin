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
import io.jenkins.plugins.batchcontrol.security.BatchControlAuthorizationStrategy;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
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
 * change control is on, but either the global authorization strategy is not the plugin's
 * delegating wrapper (so grants can never apply), or some known non-admin user holds
 * Item/Configure, Item/Create or Item/Delete directly from the delegate — a standing change
 * permission that bypasses the JIT grant process.
 *
 * <p>Candidate users come from {@link User#getAll()} plus, for matrix-family delegates, the
 * strategy's own granted sids (read reflectively — matrix-auth is an optional dependency).
 * The scan is capped at {@value #MAX_CANDIDATES} candidates and every impersonation failure is
 * swallowed: this is a best-effort warning, never an enforcement point.
 *
 * <p>S-05: {@code isActivated()} is evaluated by Jenkins on (almost) every admin page render,
 * and the candidate scan performs up to {@value #MAX_CANDIDATES} synchronous security-realm
 * lookups (remote round-trips on LDAP/AD). The scan result is therefore cached per delegate
 * instance with a {@value #CACHE_TTL_MINUTES}-minute TTL (monotonic {@link System#nanoTime}):
 * a strategy swap recomputes immediately (identity key), a change-control toggle invalidates
 * explicitly, and permission edits inside the same delegate show up within the TTL. The cheap
 * pre-checks (switch off, wrong strategy, missing delegate) are never cached.
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
        final AuthorizationStrategy delegate; // identity key: a swapped delegate recomputes
        final boolean standingPermissionFound;
        final long computedAtNanos;

        CachedScan(AuthorizationStrategy delegate, boolean standingPermissionFound,
                   long computedAtNanos) {
            this.delegate = delegate;
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
        if (!(strategy instanceof BatchControlAuthorizationStrategy)) {
            // Without the delegating wrapper, grants can never apply: change control is a no-op.
            return true;
        }
        AuthorizationStrategy delegate = ((BatchControlAuthorizationStrategy) strategy).getDelegate();
        if (delegate == null) {
            // Deny-all safe default: nobody holds any change permission directly.
            return false;
        }
        return cachedScanResult(delegate);
    }

    /**
     * The TTL-cached scan result for the given delegate: serves the cached value while it is
     * fresh and keyed to the same delegate instance, otherwise recomputes and stores. Static
     * because the cache is static — the monitor is an extension singleton either way.
     */
    private static boolean cachedScanResult(AuthorizationStrategy delegate) {
        CachedScan cached = cachedScan;
        long now = System.nanoTime();
        if (cached != null && cached.delegate == delegate
                && now - cached.computedAtNanos < CACHE_TTL_NANOS) {
            return cached.standingPermissionFound;
        }
        boolean found = scanForStandingPermissions(delegate);
        cachedScan = new CachedScan(delegate, found, now);
        return found;
    }

    /** The expensive part: impersonates candidate sids against the delegate's root ACL. */
    private static boolean scanForStandingPermissions(AuthorizationStrategy delegate) {
        ACL delegateRootAcl = delegate.getRootACL();
        for (String sid : candidateSids(delegate)) {
            Authentication auth = authenticate(sid);
            if (auth == null) {
                continue;
            }
            if (delegateRootAcl.hasPermission2(auth, Jenkins.ADMINISTER)) {
                continue; // admin bypass is out of scope (SPEC section 1)
            }
            for (Permission permission : CHANGE_PERMISSIONS) {
                if (delegateRootAcl.hasPermission2(auth, permission)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Known users plus (reflectively) the sids a matrix-family delegate grants anything to. */
    private static Set<String> candidateSids(AuthorizationStrategy delegate) {
        Set<String> sids = new LinkedHashSet<>();
        for (User user : User.getAll()) {
            if (sids.size() >= MAX_CANDIDATES) {
                return sids;
            }
            sids.add(user.getId());
        }
        for (Object raw : enumerateStrategySids(delegate)) {
            if (sids.size() >= MAX_CANDIDATES) {
                return sids;
            }
            String sid = sidOf(raw);
            if (sid != null && !sid.isEmpty()
                    && !ACL.ANONYMOUS_USERNAME.equals(sid) && !"authenticated".equals(sid)) {
                sids.add(sid);
            }
        }
        return sids;
    }

    /**
     * Reads the delegate's granted sids without a compile-time matrix-auth dependency: tries
     * {@code getAllSIDs()} (collection of strings) and {@code getGrantedPermissionEntries()}
     * (map of permission to entries carrying {@code getSid()}). Anything that fails just
     * contributes nothing.
     */
    private static Collection<?> enumerateStrategySids(AuthorizationStrategy delegate) {
        Set<Object> raw = new LinkedHashSet<>();
        try {
            Method method = delegate.getClass().getMethod("getAllSIDs");
            Object result = method.invoke(delegate);
            if (result instanceof Collection) {
                raw.addAll((Collection<?>) result);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.log(Level.FINE, "No getAllSIDs() on " + delegate.getClass().getName(), e);
        }
        try {
            Method method = delegate.getClass().getMethod("getGrantedPermissionEntries");
            Object result = method.invoke(delegate);
            if (result instanceof Map) {
                for (Object value : ((Map<?, ?>) result).values()) {
                    if (value instanceof Collection) {
                        raw.addAll((Collection<?>) value);
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.log(Level.FINE,
                    "No getGrantedPermissionEntries() on " + delegate.getClass().getName(), e);
        }
        return raw;
    }

    /** A sid string as-is, or a permission entry's {@code getSid()} (reflective), else null. */
    @CheckForNull
    private static String sidOf(Object raw) {
        if (raw instanceof String) {
            return (String) raw;
        }
        try {
            Object sid = raw.getClass().getMethod("getSid").invoke(raw);
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
