package io.jenkins.plugins.batchcontrol.ops;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.model.AdministrativeMonitor;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.AuthorizationStrategy;
import hudson.security.Permission;
import hudson.security.SecurityRealm;
import io.jenkins.plugins.batchcontrol.Messages;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.security.GrantLayer;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Warns administrators when change control cannot actually control changes (SPEC item 8):
 * change control is on, but either the global authorization strategy is not a Batch Control
 * strategy (so grants can never apply, D-35a), or some known non-admin user holds
 * Item/Configure, Item/Create or Item/Delete directly from the strategy — a standing change
 * permission that bypasses the JIT grant process.
 *
 * <p>The scan uses the strategy's root ACL, which carries no grant scope (S-13), so what it finds
 * is always a native entry, never an open grant. It is also the scan's limit (S-07): per-item
 * native permissions (matrix-auth job, folder and agent properties, role-strategy item roles) are
 * not visible on the root ACL and are not detected. The monitor is best effort, not an inventory.
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

    /** Principal of the group probe: never a real account, so only group entries apply to it. */
    private static final String GROUP_PROBE_PRINCIPAL = "batch-control:group-probe";

    /** The group sid matrix-auth uses for every logged-in user. */
    private static final String AUTHENTICATED = "authenticated";

    /**
     * One user or group that holds Item/Configure, Item/Create or Item/Delete from the
     * authorization strategy itself, outside any grant (e2e-03 DEF-07). Immutable.
     */
    public static final class StandingHolder {
        private final String sid;
        private final boolean group;
        private final List<String> permissions;

        StandingHolder(String sid, boolean group, List<String> permissions) {
            this.sid = sid;
            this.group = group;
            this.permissions = Collections.unmodifiableList(new ArrayList<>(permissions));
        }

        /** The user id or group name as written in the authorization strategy. */
        public String getSid() {
            return sid;
        }

        /** {@code true} for a security-realm group (including {@code authenticated}), else a user. */
        public boolean isGroup() {
            return group;
        }

        /** The standing change permissions held, e.g. {@code "Job/Configure"}, in a fixed order. */
        public List<String> getPermissions() {
            return permissions;
        }

        /** The user's display name when Jenkins knows the user, otherwise the sid. */
        public String getDisplayName() {
            if (group) {
                return sid;
            }
            User user = User.getById(sid, false);
            return user == null ? sid : user.getDisplayName();
        }
    }

    /** The cached result of one expensive candidate scan (immutable snapshot). */
    private static final class CachedScan {
        final AuthorizationStrategy strategy; // identity key: a swapped strategy recomputes
        final List<StandingHolder> holders;
        final long computedAtNanos;

        CachedScan(AuthorizationStrategy strategy, List<StandingHolder> holders,
                   long computedAtNanos) {
            this.strategy = strategy;
            this.holders = Collections.unmodifiableList(new ArrayList<>(holders));
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
        if (isStrategyMissing()) {
            // Without a Batch Control strategy, grants can never apply: change control is a no-op.
            return true;
        }
        return !cachedScanResult(Jenkins.get().getAuthorizationStrategy()).isEmpty();
    }

    /**
     * Whether the installed authorization strategy is not a Batch Control strategy, so grants can
     * never apply (D-35a). When {@code true} the holder lists are empty: there is no grant layer
     * to bypass, and the message should name the strategy instead.
     */
    public boolean isStrategyMissing() {
        return !GrantLayer.isGrantLayered(Jenkins.get().getAuthorizationStrategy());
    }

    /**
     * Every user and group found holding a standing change permission (users first, then groups),
     * from the same TTL-cached scan as {@link #isActivated()}. Empty when change control is off or
     * the strategy is not a Batch Control strategy. Best effort: per-item entries are not seen.
     */
    public List<StandingHolder> getStandingHolders() {
        if (!BatchControlGlobalConfiguration.get().isChangeControlEnabled() || isStrategyMissing()) {
            return Collections.emptyList();
        }
        return cachedScanResult(Jenkins.get().getAuthorizationStrategy());
    }

    /** The users of {@link #getStandingHolders()}. */
    public List<StandingHolder> getStandingUsers() {
        return filter(false);
    }

    /** The groups of {@link #getStandingHolders()} (including {@code authenticated}). */
    public List<StandingHolder> getStandingGroups() {
        return filter(true);
    }

    private List<StandingHolder> filter(boolean groups) {
        List<StandingHolder> out = new ArrayList<>();
        for (StandingHolder holder : getStandingHolders()) {
            if (holder.isGroup() == groups) {
                out.add(holder);
            }
        }
        return out;
    }

    /**
     * The TTL-cached scan result for the given strategy: serves the cached value while it is
     * fresh and keyed to the same strategy instance, otherwise recomputes and stores. Static
     * because the cache is static — the monitor is an extension singleton either way.
     */
    private static List<StandingHolder> cachedScanResult(AuthorizationStrategy strategy) {
        CachedScan cached = cachedScan;
        long now = System.nanoTime();
        if (cached != null && cached.strategy == strategy
                && now - cached.computedAtNanos < CACHE_TTL_NANOS) {
            return cached.holders;
        }
        CachedScan fresh = new CachedScan(strategy, scanForStandingPermissions(strategy), now);
        cachedScan = fresh;
        return fresh.holders;
    }

    /**
     * The expensive part: impersonates candidate user sids against the strategy's root ACL, then
     * probes each granted group with an authentication that carries only that group. A user's
     * permissions are those granted to the account itself (DEF-29). A group's
     * permissions are those its probe holds beyond what every logged-in user holds, so a grant
     * to {@code authenticated} is reported once, on {@code authenticated}.
     */
    private static List<StandingHolder> scanForStandingPermissions(AuthorizationStrategy strategy) {
        ACL rootAcl = strategy.getRootACL();
        List<StandingHolder> holders = new ArrayList<>();
        for (String sid : candidateSids(strategy)) {
            Authentication auth = authenticate(sid);
            if (auth == null) {
                continue;
            }
            if (rootAcl.hasPermission2(auth, Jenkins.ADMINISTER)) {
                continue; // admin bypass is out of scope (SPEC section 1)
            }
            // e2e re-audit DEF-29: a user is listed for what the strategy grants the account itself.
            // The probe carries the account name without its group authorities (not even
            // authenticated), so a permission held only through `authenticated` or another group
            // is reported once, on that group's entry below, not repeated for every member.
            List<String> held = heldPermissions(rootAcl, principalOnly(auth), Collections.emptyList());
            if (!held.isEmpty()) {
                holders.add(new StandingHolder(sid, false, held));
            }
        }
        Set<String> groups = strategyGroupSids(strategy);
        Authentication everyLoggedIn = groupProbe(null);
        List<String> baseline = new ArrayList<>();
        if (!rootAcl.hasPermission2(everyLoggedIn, Jenkins.ADMINISTER)) {
            baseline = heldPermissions(rootAcl, everyLoggedIn, Collections.emptyList());
            // Reported whether or not the strategy's entries can be read: users no longer carry
            // what every logged-in user holds (DEF-29), so this entry is where it shows.
            if (!baseline.isEmpty()) {
                holders.add(new StandingHolder(AUTHENTICATED, true, baseline));
            }
        }
        for (String group : groups) {
            if (AUTHENTICATED.equals(group)) {
                continue;
            }
            Authentication probe = groupProbe(group);
            if (rootAcl.hasPermission2(probe, Jenkins.ADMINISTER)) {
                continue; // an administrators group: admin bypass is out of scope
            }
            List<String> held = heldPermissions(rootAcl, probe, baseline);
            if (!held.isEmpty()) {
                holders.add(new StandingHolder(group, true, held));
            }
        }
        return holders;
    }

    /** The change permissions {@code auth} holds on the root ACL, minus those in {@code except}. */
    private static List<String> heldPermissions(ACL rootAcl, Authentication auth, List<String> except) {
        List<String> held = new ArrayList<>();
        for (Permission permission : CHANGE_PERMISSIONS) {
            String name = permission.group.title + "/" + permission.name;
            if (!except.contains(name) && rootAcl.hasPermission2(auth, permission)) {
                held.add(name);
            }
        }
        return held;
    }

    /** {@code auth}'s account name with no granted authorities: matches user entries only. */
    private static Authentication principalOnly(Authentication auth) {
        return new UsernamePasswordAuthenticationToken(auth.getName(), "", Collections.emptyList());
    }

    /** A logged-in authentication that is no real account and carries only {@code group}, if any. */
    private static Authentication groupProbe(@CheckForNull String group) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(SecurityRealm.AUTHENTICATED_AUTHORITY2);
        if (group != null) {
            authorities.add(new SimpleGrantedAuthority(group));
        }
        return new UsernamePasswordAuthenticationToken(GROUP_PROBE_PRINCIPAL, "", authorities);
    }

    /** Known users plus (reflectively) the sids a matrix-family strategy grants anything to. */
    private static Set<String> candidateSids(AuthorizationStrategy strategy) {
        Set<String> sids = new LinkedHashSet<>();
        for (User user : User.getAll()) {
            if (sids.size() >= MAX_CANDIDATES) {
                return sids;
            }
            if (ACL.SYSTEM_USERNAME.equals(user.getId())) {
                continue; // the internal SYSTEM identity is not a person holding a standing permission
            }
            sids.add(user.getId());
        }
        for (String sid : strategyPermissionSids(strategy)) {
            if (sids.size() >= MAX_CANDIDATES) {
                return sids;
            }
            if (!sid.isEmpty() && !ACL.ANONYMOUS_USERNAME.equals(sid) && !ACL.SYSTEM_USERNAME.equals(sid)
                    && !AUTHENTICATED.equals(sid)) {
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
     * The group sids a matrix-family strategy grants anything to: {@code GROUP} and legacy
     * {@code EITHER} entries (an EITHER sid may name a group or an account, so it is probed both
     * ways), read reflectively like {@link #strategyPermissionSids}. Capped at
     * {@value #MAX_CANDIDATES}.
     */
    private static Set<String> strategyGroupSids(AuthorizationStrategy strategy) {
        Set<String> groups = new LinkedHashSet<>();
        try {
            Method method = strategy.getClass().getMethod("getAllPermissionEntries");
            Object result = method.invoke(strategy);
            if (result instanceof Collection) {
                for (Object entry : (Collection<?>) result) {
                    if (groups.size() >= MAX_CANDIDATES) {
                        break;
                    }
                    String type = typeOf(entry);
                    String sid = sidOf(entry);
                    if (sid != null && !sid.isEmpty() && ("GROUP".equals(type) || "EITHER".equals(type))) {
                        groups.add(sid);
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.log(Level.FINE,
                    "No getAllPermissionEntries() on " + strategy.getClass().getName(), e);
        }
        return groups;
    }

    /** A permission entry's {@code getType()} constant name (reflective), or {@code null}. */
    @CheckForNull
    private static String typeOf(Object entry) {
        try {
            Object type = entry.getClass().getMethod("getType").invoke(entry);
            return type instanceof Enum ? ((Enum<?>) type).name() : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Whether a (reflectively read) permission entry's {@code getType()} is matrix-auth's
     * {@code GROUP} constant; {@code false} for {@code USER}, {@code EITHER}, or anything
     * reflection cannot resolve.
     */
    private static boolean isGroupEntry(Object entry) {
        return "GROUP".equals(typeOf(entry));
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
