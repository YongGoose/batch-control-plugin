package io.jenkins.plugins.batchcontrol.security;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import io.jenkins.plugins.batchcontrol.store.Store;
import java.lang.ref.WeakReference;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Runtime authority on active grants (SPEC item 8). Keeps an in-memory cache of all grants,
 * backed by the file store ({@code grants/<id>.xml}), so the permission-check hot path
 * ({@link GrantAwareACL}) never touches disk.
 *
 * <p>Restart safety: the cache is keyed to the current Jenkins instance and reloaded from the
 * files whenever a new instance appears, so a grant that is still inside its window survives a
 * restart and one whose window ended during the downtime is gone from the very first check
 * (SPEC item 8). Activity is always judged by {@link Grant#isActiveAt} against
 * {@link BatchClock} at check time — no timers.
 */
@Restricted(NoExternalUse.class)
public final class GrantService {

    private static final Logger LOGGER = Logger.getLogger(GrantService.class.getName());

    private static final GrantService INSTANCE = new GrantService();

    private final Store store = FileStore.get();

    /** All known grants (active or not); guarded by {@code this}. */
    private List<Grant> cache;

    /** The Jenkins instance the cache was loaded for (weak: test harnesses boot several). */
    private WeakReference<Jenkins> cacheFor = new WeakReference<>(null);

    private GrantService() {
    }

    public static GrantService get() {
        return INSTANCE;
    }

    // ---------------------------------------------------------------- queries

    /**
     * Whether {@code user} currently holds {@code permission} on the item named
     * {@code itemFullName} through an active grant. Only the three grantable item permissions
     * can ever match; any other permission returns {@code false} immediately.
     */
    public boolean hasActiveGrant(String user, String itemFullName, Permission permission) {
        GrantAction action = GrantAction.fromPermission(permission);
        return action != null && findActiveGrant(user, itemFullName, action) != null;
    }

    /**
     * The first active grant of {@code user} that covers {@code itemFullName} and includes
     * {@code action}, or {@code null}. A {@code null} action matches any action (used by the
     * D-17 "created inside a grant window" check).
     */
    @CheckForNull
    public synchronized Grant findActiveGrant(String user, String itemFullName,
                                              @CheckForNull GrantAction action) {
        if (user == null || itemFullName == null) {
            return null;
        }
        Instant now = BatchClock.now();
        for (Grant grant : grants()) {
            if (grant.isActiveAt(now)
                    && user.equals(grant.getUser())
                    && grant.getScope().includes(itemFullName)
                    && (action == null || grant.getActions().contains(action))) {
                return grant;
            }
        }
        return null;
    }

    /** Every grant that is active right now (not expired, not revoked). */
    public synchronized List<Grant> listActive() {
        Instant now = BatchClock.now();
        List<Grant> active = new ArrayList<>();
        for (Grant grant : grants()) {
            if (grant.isActiveAt(now)) {
                active.add(grant);
            }
        }
        return active;
    }

    // ---------------------------------------------------------------- mutations

    /**
     * Persists a freshly created grant and makes it effective immediately. Called only by
     * {@code policy.GrantRequestService} on approval.
     */
    public synchronized void register(Grant grant) {
        Objects.requireNonNull(grant, "grant");
        store.saveGrant(grant);
        List<Grant> grants = grants();
        grants.removeIf(existing -> existing.getId().equals(grant.getId()));
        grants.add(grant);
    }

    /**
     * Immediately revokes a grant (SPEC item 8: Manage holders only — the web action checks;
     * the service double-checks) and appends a {@code ChangeRecord(GRANT_REVOKE)}.
     *
     * @return the revoked grant
     * @throws IllegalArgumentException if no such grant exists
     * @throws IllegalStateException if the grant was already revoked
     */
    public synchronized Grant revoke(String grantId) {
        Objects.requireNonNull(grantId, "grantId");
        Jenkins.get().checkPermission(BatchControlPermissions.MANAGE);
        Grant grant = store.loadGrant(grantId);
        if (grant == null) {
            throw new IllegalArgumentException("No such grant: " + grantId);
        }
        if (grant.getRevokedAt() != null) {
            throw new IllegalStateException("Grant " + grantId + " is already revoked.");
        }
        String caller = Jenkins.getAuthentication2().getName();
        grant.markRevoked(BatchClock.now(), caller);
        store.saveGrant(grant);
        replaceInCache(grant);
        ChangeRecord record = ChangeRecord.create(ChangeType.GRANT_REVOKE,
                grant.getScope().getFullName(), caller,
                "Grant " + grant.getId() + " for user '" + grant.getUser() + "' ("
                        + grant.getScope() + ") revoked");
        record.setGrantId(grant.getId());
        store.appendChangeRecord(record);
        LOGGER.info(() -> "Grant " + grantId + " revoked by '" + caller + "'");
        return grant;
    }

    // ---------------------------------------------------------------- cache

    /** The live cache list, (re)loaded from the store for the current Jenkins instance. */
    private synchronized List<Grant> grants() {
        Jenkins current = Jenkins.getInstanceOrNull();
        if (current == null) {
            // No Jenkins (shutdown window): answer from whatever is cached, never load.
            return cache != null ? cache : new ArrayList<>();
        }
        if (cache == null || cacheFor.get() != current) {
            cache = new ArrayList<>(store.listGrants());
            cacheFor = new WeakReference<>(current);
        }
        return cache;
    }

    private synchronized void replaceInCache(Grant grant) {
        List<Grant> grants = grants();
        ListIterator<Grant> it = grants.listIterator();
        boolean replaced = false;
        while (it.hasNext()) {
            if (it.next().getId().equals(grant.getId())) {
                it.set(grant);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            grants.add(grant);
        }
    }
}
