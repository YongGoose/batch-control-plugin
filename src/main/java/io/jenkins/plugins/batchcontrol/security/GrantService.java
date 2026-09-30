package io.jenkins.plugins.batchcontrol.security;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.Store;
import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Runtime authority on active grants (SPEC item 8). Keeps an in-memory cache of all grants,
 * backed by the file store ({@code grants/<id>.xml}), so the permission-check hot path
 * ({@link GrantAwareACL}) never touches disk.
 *
 * <p>Restart safety: {@link Jenkins} is a singleton, so there is exactly one live cache to keep
 * fresh; {@link #resetCacheOnStartup()} clears it unconditionally on every Jenkins startup, so
 * the very first query after a restart reloads straight from the files instead of serving
 * whatever happened to be in memory before. A grant that is still inside its window therefore
 * survives a restart and one whose window ended during the downtime is gone from the very first
 * check (SPEC item 8). Activity is always judged by {@link Grant#isActiveAt} against
 * {@link BatchClock} at check time — no timers.
 *
 * <p>S-15: the change-control switch gates whether a grant <em>confers</em> anything (that check is
 * in {@link GrantAwareACL}), and turning the switch off also closes the windows that are open at
 * that moment — see {@link #revokeAllActive()}.
 */
@Restricted(NoExternalUse.class)
public final class GrantService {

    private static final Logger LOGGER = Logger.getLogger(GrantService.class.getName());

    private static final GrantService INSTANCE = new GrantService();

    private final Store store = Store.get();

    /** All known grants (active or not); guarded by {@code this}. */
    private List<Grant> cache;

    private GrantService() {
    }

    public static GrantService get() {
        return INSTANCE;
    }

    /**
     * Clears the in-memory cache. Jenkins is a singleton, so this is not about telling one
     * running instance apart from another — it simply makes sure the first query after a fresh
     * Jenkins startup (a real restart, or a test harness booting a new session) reloads from the
     * files instead of serving a cache built for whatever was on disk before.
     */
    @Initializer(after = InitMilestone.PLUGINS_STARTED)
    public static void resetCacheOnStartup() {
        INSTANCE.clearCache();
    }

    private synchronized void clearCache() {
        cache = null;
    }

    /**
     * Drops grants that retention has deleted from the store (#13). They ended before the
     * retention cut-off, so none of them can be active; this only keeps the cache from holding
     * them until the next restart.
     */
    public synchronized void forgetDeleted(java.util.Collection<String> grantIds) {
        if (cache != null && !grantIds.isEmpty()) {
            cache.removeIf(grant -> grantIds.contains(grant.getId()));
        }
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
     * {@code action}, or {@code null}. A {@code null} action matches any action (used by
     * {@code ItemChangeListener} to link RENAME/MOVE change records to the grant in use).
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

    /**
     * Every active grant of {@code user} that covers {@code itemFullName} and includes
     * {@code action} (D-40: the CREATE check has to see all of them, since each may carry a
     * different name restriction).
     */
    public synchronized List<Grant> findActiveGrants(String user, String itemFullName, GrantAction action) {
        List<Grant> found = new ArrayList<>();
        if (user == null || itemFullName == null || action == null) {
            return found;
        }
        Instant now = BatchClock.now();
        for (Grant grant : grants()) {
            if (grant.isActiveAt(now)
                    && user.equals(grant.getUser())
                    && grant.getScope().includes(itemFullName)
                    && grant.getActions().contains(action)) {
                found.add(grant);
            }
        }
        return found;
    }

    /**
     * D-40: the first active Create grant of {@code user} covering {@code itemFullName} whose name
     * restriction (if any) allows {@code itemName}, or {@code null}.
     */
    @CheckForNull
    public Grant findActiveCreateGrant(String user, String itemFullName, String itemName) {
        // Not synchronized: the list is a copy, and a user-supplied pattern is never matched while
        // this monitor is held, since every permission check passes through it (security-08 S-03).
        for (Grant grant : findActiveGrants(user, itemFullName, GrantAction.CREATE)) {
            if (grant.allowsCreateName(itemName)) {
                return grant;
            }
        }
        return null;
    }

    /**
     * D-36: marks and returns every active grant whose window ends within {@code lead} and whose
     * GRANT_EXPIRING notification was not sent yet. The flag is persisted before the caller
     * dispatches, so a restart never resends.
     */
    public synchronized List<Grant> claimExpiringNotifications(java.time.Duration lead) {
        Instant now = BatchClock.now();
        List<Grant> claimed = new ArrayList<>();
        for (Grant cached : new ArrayList<>(grants())) {
            if (!cached.isActiveAt(now) || cached.isExpiringNotified()
                    || now.isBefore(cached.getExpiresAt().minus(lead))) {
                continue;
            }
            Grant grant = store.loadGrant(cached.getId());
            if (grant == null || !grant.isActiveAt(now) || grant.isExpiringNotified()) {
                continue;
            }
            grant.setExpiringNotified(true);
            store.saveGrant(grant);
            replaceInCache(grant);
            claimed.add(grant);
        }
        return claimed;
    }

    /**
     * D-35c: the active grant of {@code user} through whose Create the item {@code itemFullName}
     * was created, or {@code null}. The item must still lie inside the grant's scope. While such a
     * grant is active its holder also holds Item/Read and Item/Configure on that item (see
     * {@code GrantAwareACL}), so matrix-auth's creator listener finds the permissions already
     * held and writes no permanent entry; the permissions end with the window. S-09: when the
     * record carries the item's identity, the item at {@code itemRootDir} must still have it.
     */
    @CheckForNull
    public synchronized Grant findCreatingGrant(String user, String itemFullName, @CheckForNull File itemRootDir) {
        if (user == null || itemFullName == null) {
            return null;
        }
        Instant now = BatchClock.now();
        String[] identity = new String[1];
        boolean[] computed = new boolean[1];
        Supplier<String> current = () -> {
            if (!computed[0]) {
                identity[0] = ItemIdentity.of(itemRootDir);
                computed[0] = true;
            }
            return identity[0];
        };
        for (Grant grant : grants()) {
            if (grant.isActiveAt(now)
                    && user.equals(grant.getUser())
                    && grant.getScope().includes(itemFullName)
                    && grant.hasCreated(itemFullName, current)) {
                return grant;
            }
        }
        return null;
    }

    /**
     * The active grant that gives {@code user} Item/Configure on {@code itemFullName}: a grant
     * with the CONFIGURE action covering the item, or a Create grant through which the user
     * created it (D-35c). {@code null} if neither exists. Used by the D-35b guard to name the
     * grant a violation came from.
     */
    @CheckForNull
    public synchronized Grant findConfigureGrant(String user, String itemFullName, @CheckForNull File itemRootDir) {
        Grant grant = findActiveGrant(user, itemFullName, GrantAction.CONFIGURE);
        return grant != null ? grant : findCreatingGrant(user, itemFullName, itemRootDir);
    }

    // ---------------------------------------------------------------- D-58a guarded items

    /**
     * D-58a: whether the item is guarded: covered by an active grant (its scope includes the item,
     * the scope lies below the item when the item is a folder, or the item was created through
     * it), or changed under a grant (active or ended) and not reviewed since. Answered from the
     * in-memory grant cache.
     */
    public synchronized boolean isGuardedItem(String itemFullName) {
        if (itemFullName == null || itemFullName.isEmpty()) {
            return false;
        }
        Instant now = BatchClock.now();
        for (Grant grant : grants()) {
            if (changedAtOrAbove(grant, itemFullName)) {
                return true;
            }
            if (grant.isActiveAt(now) && covers(grant, itemFullName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * D-58b (1): whether the grant lists the item, or an item above it, as changed under it (a
     * changed folder shapes what its children run: branch jobs of a multibranch project, jobs in a
     * folder).
     */
    private static boolean changedAtOrAbove(Grant grant, String itemFullName) {
        for (String changed : grant.getChangedItems()) {
            if (itemFullName.equals(changed) || itemFullName.startsWith(changed + "/")) {
                return true;
            }
        }
        return false;
    }

    /** Whether a grant's coverage (scope, a scope below a folder, or D-35c created) includes the item. */
    private static boolean covers(Grant grant, String itemFullName) {
        String scope = grant.getScope() == null ? null : grant.getScope().getFullName();
        if (grant.getScope() != null && grant.getScope().includes(itemFullName)) {
            return true;
        }
        if (scope != null && !scope.isEmpty()
                // a folder's property is inherited below it, so a scope below the folder guards it
                && (scope.startsWith(itemFullName + "/")
                        // D-58b (1): everything below a scope item (branch jobs of a multibranch
                        // project in a JOB scope, for example)
                        || itemFullName.startsWith(scope + "/"))) {
            return true;
        }
        for (String created : grant.getCreatedItems()) {
            if (itemFullName.equals(created) || itemFullName.startsWith(created + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * D-58a: the id of a grant that makes the item guarded (one that lists it as changed, else an
     * active one covering it), or {@code null} when it is not guarded.
     */
    @CheckForNull
    public synchronized String guardingGrantId(String itemFullName) {
        if (itemFullName == null || itemFullName.isEmpty()) {
            return null;
        }
        Instant now = BatchClock.now();
        String covering = null;
        for (Grant grant : grants()) {
            if (changedAtOrAbove(grant, itemFullName)) {
                return grant.getId();
            }
            if (covering == null && grant.isActiveAt(now) && covers(grant, itemFullName)) {
                covering = grant.getId();
            }
        }
        return covering;
    }

    /** D-58a: the items in the "changed under a grant" state, sorted, at most {@code limit}. */
    public synchronized List<String> itemsChangedUnderGrant(int limit) {
        java.util.TreeSet<String> items = new java.util.TreeSet<>();
        for (Grant grant : grants()) {
            items.addAll(grant.getChangedItems());
        }
        List<String> out = new ArrayList<>();
        for (String item : items) {
            if (out.size() >= limit) {
                break;
            }
            out.add(item);
        }
        return out;
    }

    /**
     * D-58a: notes that {@code itemFullName} was changed under {@code grantId} (a save or creation
     * by the holder while their permission came only from the grant), persisting the grant. A
     * store failure is logged; it never fails the save.
     */
    public synchronized void markChanged(String grantId, String itemFullName) {
        if (grantId == null || itemFullName == null || itemFullName.isEmpty()) {
            return;
        }
        for (Grant cached : grants()) {
            if (cached.getId().equals(grantId) && cached.hasChanged(itemFullName)) {
                return; // already marked
            }
        }
        try {
            Grant grant = store.loadGrant(grantId);
            if (grant == null) {
                return;
            }
            List<String> items = grant.getChangedItems();
            if (!items.contains(itemFullName)) {
                items.add(itemFullName);
            }
            grant.setChangedItems(items);
            store.saveGrant(grant);
            replaceInCache(grant);
        } catch (RuntimeException e) {
            // S-28-06: a lost mark would let the item go unguarded once the window ends.
            LOGGER.log(java.util.logging.Level.SEVERE, "Could not mark '" + itemFullName
                    + "' as changed under grant " + grantId + "; an administrator must check it", e);
        }
    }

    /**
     * D-58b (3): the deliberate review. The item and everything below it leave the "changed under a
     * grant" state, and a {@code GUARD_REVIEWED} change record names the reviewer. The caller must
     * hold Item/Configure on the item natively (asked with every grant layer off) or
     * Overall/Administer; a user whose permission on the item comes from a grant cannot review it.
     *
     * @throws org.springframework.security.access.AccessDeniedException (AccessDeniedException3)
     *         when the caller may not review the item
     */
    public void markReviewed(hudson.model.Item item) {
        org.springframework.security.core.Authentication auth = Jenkins.getAuthentication2();
        if (!GrantLayer.hasPermissionWithoutGrants(item, auth, hudson.model.Item.CONFIGURE)
                && !GrantLayer.hasPermissionWithoutGrants(Jenkins.get(), auth, Jenkins.ADMINISTER)) {
            throw new hudson.security.AccessDeniedException3(auth, hudson.model.Item.CONFIGURE);
        }
        String fullName = item.getFullName();
        synchronized (this) {
            rewriteChanged(fullName, null, true);
        }
        store.appendChangeRecord(ChangeRecord.create(ChangeType.GUARD_REVIEWED, fullName, auth.getName(),
                "Marked as reviewed by '" + auth.getName() + "': the item and everything below it are no longer"
                        + " guarded as changed under a permission window."));
        LOGGER.info(() -> "'" + fullName + "' marked as reviewed by '" + auth.getName() + "'");
    }

    /**
     * D-58a (S-27-03): an item was renamed or moved. The "changed under a grant" state follows it
     * and what is below it; and an item that was covered by an active grant under its old name,
     * but is not under its new name, is marked as changed under that grant, so it stays guarded.
     */
    public synchronized void relocateChanged(String oldFullName, String newFullName) {
        Instant now = BatchClock.now();
        List<String> carriers = new ArrayList<>();
        for (Grant grant : grants()) {
            if (grant.isActiveAt(now) && covers(grant, oldFullName) && !covers(grant, newFullName)) {
                carriers.add(grant.getId());
            }
        }
        rewriteChanged(oldFullName, newFullName, true);
        for (String grantId : carriers) {
            markChanged(grantId, newFullName);
        }
    }

    /** D-58a: a deleted item (and what was below it) leaves the "changed under a grant" state. */
    public synchronized void forgetChanged(String fullName) {
        rewriteChanged(fullName, null, true);
    }

    /**
     * Replaces (or removes, with a {@code null} replacement) {@code fullName}, and with
     * {@code descendants} what is below it, in the changed-items list of every grant.
     */
    private void rewriteChanged(String fullName, @CheckForNull String replacement, boolean descendants) {
        if (fullName == null || fullName.isEmpty()) {
            return;
        }
        for (Grant cached : new ArrayList<>(grants())) {
            boolean affected = false;
            for (String item : cached.getChangedItems()) {
                if (item.equals(fullName) || (descendants && item.startsWith(fullName + "/"))) {
                    affected = true;
                    break;
                }
            }
            if (!affected) {
                continue;
            }
            try {
                Grant grant = store.loadGrant(cached.getId());
                if (grant == null) {
                    continue;
                }
                java.util.LinkedHashSet<String> updated = new java.util.LinkedHashSet<>();
                for (String item : grant.getChangedItems()) {
                    boolean match = item.equals(fullName) || (descendants && item.startsWith(fullName + "/"));
                    if (!match) {
                        updated.add(item);
                    } else if (replacement != null) {
                        updated.add(replacement + item.substring(fullName.length()));
                    }
                }
                grant.setChangedItems(new ArrayList<>(updated));
                store.saveGrant(grant);
                replaceInCache(grant);
            } catch (RuntimeException e) {
                LOGGER.log(java.util.logging.Level.WARNING, "Could not update the changed-under-grant state of '"
                        + fullName + "' in grant " + cached.getId(), e);
            }
        }
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
     * D-35c: notes that {@code user} created {@code itemFullName} through the Create of an active
     * grant, persisting the grant. Called by {@code listener.CreatedItemGrantListener} only when the
     * creation was not possible without the grant.
     *
     * @return the grant that now records the item, or {@code null} when no active Create grant of
     *         {@code user} covers the item
     */
    @CheckForNull
    public Grant recordCreatedItem(String user, String itemFullName, @CheckForNull String identity) {
        // D-40: the grant whose name restriction admits the item (its name, not the full name),
        // chosen outside the monitor (S-03), then recorded under it.
        String itemName = itemFullName.substring(itemFullName.lastIndexOf('/') + 1);
        Grant active = findActiveCreateGrant(user, itemFullName, itemName);
        if (active == null) {
            return null;
        }
        return recordCreatedItemIn(active.getId(), user, itemFullName, identity);
    }

    @CheckForNull
    private synchronized Grant recordCreatedItemIn(String grantId, String user, String itemFullName,
                                                   @CheckForNull String identity) {
        Grant grant = store.loadGrant(grantId);
        if (grant == null || !grant.isActiveAt(BatchClock.now()) || !user.equals(grant.getUser())
                || !grant.getScope().includes(itemFullName) || !grant.getActions().contains(GrantAction.CREATE)) {
            return null;
        }
        List<String> items = grant.getCreatedItems();
        if (!items.contains(itemFullName)) {
            items.add(itemFullName);
        }
        Map<String, String> identities = grant.getCreatedItemIdentities();
        identities.remove(itemFullName);
        if (identity != null) {
            identities.put(itemFullName, identity);
        }
        grant.setCreatedItems(items);
        grant.setCreatedItemIdentities(identities);
        // D-58a: an item created through the grant alone is changed under it.
        List<String> changed = grant.getChangedItems();
        if (!changed.contains(itemFullName)) {
            changed.add(itemFullName);
        }
        grant.setChangedItems(changed);
        store.saveGrant(grant);
        replaceInCache(grant);
        return grant;
    }

    /**
     * D-35c: an item recorded as created through an active grant was renamed or moved; the record
     * follows it. Whether it still confers anything is decided by the scope check at query time,
     * so moving the item out of the scope ends the permission.
     */
    public synchronized void relocateCreatedItem(String oldFullName, String newFullName) {
        updateCreatedItems(oldFullName, newFullName);
    }

    /**
     * D-35c: an item recorded as created through an active grant was deleted. The record is
     * dropped, so an item created later under the same name by someone else confers nothing.
     */
    public synchronized void forgetCreatedItem(String fullName) {
        updateCreatedItems(fullName, null);
    }

    /**
     * D-35c, S-09: drops from the created-items lists of active grants every item for which
     * {@code exists} is false. Called after all items are loaded (startup and reload), so a record
     * of an item deleted on disk while no listener saw it cannot confer anything on an item
     * created later under the same name by someone else. An item that failed to load loses its
     * record too, which only takes permissions away (fail-safe).
     */
    public synchronized void pruneCreatedItems(Predicate<String> exists) {
        Instant now = BatchClock.now();
        for (Grant cached : new ArrayList<>(grants())) {
            if (!cached.isActiveAt(now) || cached.getCreatedItems().isEmpty()) {
                continue;
            }
            List<String> kept = new ArrayList<>();
            for (String item : cached.getCreatedItems()) {
                if (exists.test(item)) {
                    kept.add(item);
                }
            }
            if (kept.size() == cached.getCreatedItems().size()) {
                continue;
            }
            Grant grant = store.loadGrant(cached.getId());
            if (grant == null) {
                continue;
            }
            List<String> stored = new ArrayList<>();
            for (String item : grant.getCreatedItems()) {
                if (exists.test(item)) {
                    stored.add(item);
                }
            }
            grant.setCreatedItems(stored);
            store.saveGrant(grant);
            replaceInCache(grant);
            LOGGER.info(() -> "Grant " + grant.getId() + ": dropped created-item records of items "
                    + "that no longer exist");
        }
    }

    /**
     * Replaces (or, with a {@code null} replacement, removes) {@code fullName} and every
     * descendant of it in the created-items lists of active grants.
     */
    private void updateCreatedItems(String fullName, @CheckForNull String replacement) {
        if (fullName == null) {
            return;
        }
        Instant now = BatchClock.now();
        for (Grant cached : new ArrayList<>(grants())) {
            if (!cached.isActiveAt(now) || cached.getCreatedItems().isEmpty()) {
                continue;
            }
            boolean changed = false;
            for (String item : cached.getCreatedItems()) {
                if (item.equals(fullName) || item.startsWith(fullName + "/")) {
                    changed = true;
                    break;
                }
            }
            if (!changed) {
                continue;
            }
            Grant grant = store.loadGrant(cached.getId());
            if (grant == null) {
                continue;
            }
            List<String> updated = new ArrayList<>();
            Map<String, String> identities = grant.getCreatedItemIdentities();
            Map<String, String> updatedIdentities = new HashMap<>();
            for (String item : grant.getCreatedItems()) {
                String target = item;
                if (item.equals(fullName) || item.startsWith(fullName + "/")) {
                    target = replacement == null ? null : replacement + item.substring(fullName.length());
                }
                if (target != null) {
                    updated.add(target);
                    String identity = identities.get(item);
                    if (identity != null) {
                        updatedIdentities.put(target, identity);
                    }
                }
            }
            grant.setCreatedItems(updated);
            grant.setCreatedItemIdentities(updatedIdentities);
            store.saveGrant(grant);
            replaceInCache(grant);
        }
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
        revokeOne(grant, caller, "revoked");
        return grant;
    }

    /**
     * S-15, the kill switch: revokes every grant that is active right now and returns how many were
     * closed. Called when the change-control switch is turned off (see
     * {@code config.BatchControlGlobalConfiguration#setChangeControlEnabled}) — turning the switch
     * off has to close the windows that are already open, not merely stop new ones from conferring.
     *
     * <p>Two things this does that {@link GrantAwareACL}'s switch check alone does not:
     * <ul>
     *   <li>it is <b>durable</b>. The ACL check makes an open window stop conferring while the
     *       switch is off; without the revocation, switching change control back on would bring
     *       every one of those windows back to life for the remainder of its duration (up to
     *       {@code maxGrantMinutes}, default 240). A revoked grant never returns —
     *       {@link Grant#isActiveAt} is false for it for good.</li>
     *   <li>it is <b>visible</b>. Each revocation appends a {@code ChangeRecord(GRANT_REVOKE)}
     *       naming the account that flipped the switch, the grantee whose window was closed and the
     *       position in the batch, so the audit history says who cut the work off and how much of it
     *       there was. A silent mass revocation would be worse than none: the users whose in-flight
     *       configuration changes suddenly stop working would have nothing to read.</li>
     * </ul>
     *
     * <p><b>This is destructive to other people's in-flight work, by design.</b> Whoever turns the
     * switch off cuts off every change currently underway with no warning; that is the trade the
     * owner chose over a switch that leaves windows quietly open. The caller writes its own
     * CONFIG_TOGGLE record before calling this, so the history reads "the switch went off" and then
     * "these windows were closed", in that order.
     *
     * <p>No permission check of its own, deliberately, and unlike {@link #revoke(String)}: the
     * caller is a plain configuration setter whose only web entry point is core's
     * {@code /manage/configure} submission, already gated on {@code Overall/Administer} — which
     * implies {@code BatchControl/Manage} in any case. Putting a check here would instead impose a
     * permission gate on a setter that JCasC and startup also drive.
     *
     * @return the number of active windows that were closed
     */
    public synchronized int revokeAllActive() {
        List<Grant> active = listActive();
        if (active.isEmpty()) {
            return 0;
        }
        String caller = Jenkins.getAuthentication2().getName();
        int total = active.size();
        int closed = 0;
        for (Grant cached : active) {
            // Revoke the store's copy — the same one revoke(String) mutates — so the persisted
            // grant and the cache cannot end up disagreeing about who revoked it and when.
            Grant grant = store.loadGrant(cached.getId());
            if (grant == null) {
                LOGGER.warning(() -> "Grant " + cached.getId() + " is active in memory but has no "
                        + "file in the store, so it cannot be revoked as part of switching change "
                        + "control off; dropping it from the cache instead.");
                grants().removeIf(existing -> existing.getId().equals(cached.getId()));
                continue;
            }
            if (grant.getRevokedAt() != null) {
                // Already revoked behind the cache's back: nothing to write, no record to duplicate.
                replaceInCache(grant);
                continue;
            }
            closed++;
            revokeOne(grant, caller, "revoked because change control was switched off ("
                    + closed + " of " + total + " active permission windows closed)");
        }
        int closedCount = closed;
        LOGGER.info(() -> "Change control was switched off by '" + caller + "': " + closedCount
                + " of " + total + " active permission windows were revoked");
        return closed;
    }

    /**
     * Marks {@code grant} revoked by {@code caller}, persists it, refreshes the cache and appends
     * the {@code GRANT_REVOKE} record. The single revocation write path, so a per-grant revocation
     * and a switch-off mass revocation cannot drift apart in what they persist or record.
     */
    private void revokeOne(Grant grant, String caller, String detail) {
        grant.markRevoked(BatchClock.now(), caller);
        store.saveGrant(grant);
        replaceInCache(grant);
        ChangeRecord record = ChangeRecord.create(ChangeType.GRANT_REVOKE,
                grant.getScope().getFullName(), caller,
                "Grant " + grant.getId() + " for user '" + grant.getUser() + "' ("
                        + grant.getScope() + ") " + detail);
        record.setGrantId(grant.getId());
        store.appendChangeRecord(record);
        LOGGER.info(() -> "Grant " + grant.getId() + " revoked by '" + caller + "'");
    }

    // ---------------------------------------------------------------- cache

    /** The live cache list, lazily loaded from the store and cleared at every startup. */
    private synchronized List<Grant> grants() {
        if (Jenkins.getInstanceOrNull() == null) {
            // No Jenkins (shutdown window): answer from whatever is cached, never load.
            return cache != null ? cache : new ArrayList<>();
        }
        if (cache == null) {
            cache = new ArrayList<>(store.listGrants());
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
