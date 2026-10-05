package io.jenkins.plugins.batchcontrol.security;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.model.ItemKind;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.Store;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    /**
     * D-71c (security-36 S-36-03): ids of grants unbound in memory whose file could not be written
     * yet; guarded by {@code this}. Every copy of such a grant that enters the cache is unbound
     * again, and the write is retried on the next unbinding, the next registration, the next write
     * of that grant and by the expiry periodic work, until it succeeds.
     */
    private final Set<String> unsavedUnbindings = new LinkedHashSet<>();

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
        markedRuns.clear();
        markedRunsLoaded = false;
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

    /*
     * D-71a (security-34 S-34-01, S-34-03): a window confers something only on the very item it was
     * approved for. Every lookup below therefore matches in two steps:
     *   1. in memory, under this monitor: active grants of the user whose scope is exactly the full
     *      name (a copy is returned, so nothing below runs while the monitor is held);
     *   2. outside the monitor, and only when step 1 found a candidate: the grant is bound to the
     *      item (isBoundTo): the identity recorded at approval equals the item's current identity
     *      (ItemIdentity, cached per item object) and, when recorded, the kind is the same.
     * A grant without a recorded identity is bound to nothing and confers nothing.
     *
     * The lookups that take only a full name resolve the item currently at that name as the caller
     * sees it (no SYSTEM switch; an item the caller cannot read gets nothing) and then match as
     * above. They are for callers outside a permission check (tests, screens); the grant layer
     * itself only uses the item forms, so no lookup is ever made from inside a permission check.
     */

    /**
     * D-71a: whether {@code grant} is bound to {@code item}: an identity was recorded at approval
     * and equals the item's current identity, and the item has the recorded kind (when one was
     * recorded; S-34-03). The full name is not compared here; callers match it first.
     */
    public static boolean isBoundTo(Grant grant, @CheckForNull Item item) {
        if (grant == null || item == null || grant.getScope() == null
                || grant.getScope().getType() != GrantScope.Type.ITEM) {
            // a stored scope of an earlier type (JOB, FOLDER, FOLDER_ONLY) loads without a type and
            // is not converted (D-69, D-71): it is bound to nothing
            return false;
        }
        String recorded = grant.getItemIdentity();
        if (recorded == null) {
            return false;
        }
        ItemKind kind = grant.getItemKind();
        if (kind != null && !kind.matches(item)) {
            return false;
        }
        return recorded.equals(ItemIdentity.of(item));
    }

    /**
     * Whether {@code user} currently holds {@code permission} on the item named
     * {@code itemFullName} through an active grant. Only the three grantable item permissions
     * can ever match; any other permission returns {@code false} immediately.
     *
     * <p>D-71a: the item currently at that name is resolved as the caller sees it, and the grant
     * must be bound to it ({@link #hasActiveGrant(String, Item, Permission)}). Whether the action
     * can apply to the item's kind is decided when the request is submitted and approved, and again
     * by the grant layer ({@code GrantAwareACL}) and {@link #findActiveDeleteGrant}.
     */
    public boolean hasActiveGrant(String user, String itemFullName, Permission permission) {
        GrantAction action = GrantAction.fromPermission(permission);
        return action != null && findActiveGrant(user, itemFullName, action) != null;
    }

    /**
     * D-71a: whether {@code user} currently holds {@code permission} on {@code item} through an
     * active grant naming exactly its full name and bound to it. For Item/Create, {@code item} is
     * the group the new item is created in (core checks Create on the group's ACL), so the identity
     * compared is the folder's.
     */
    public boolean hasActiveGrant(String user, @CheckForNull Item item, Permission permission) {
        GrantAction action = GrantAction.fromPermission(permission);
        return action != null && findActiveGrant(user, item, action) != null;
    }

    /**
     * The first active grant of {@code user} whose scope is the item {@code itemFullName}, bound to
     * the item currently at that name (D-71a), and that includes {@code action}, or {@code null}.
     * A {@code null} action matches any action.
     *
     * <p>For DELETE this does not look at the item's kind; callers holding the item use
     * {@link #findActiveDeleteGrant}, which also requires the item to be a job (D-71).
     */
    @CheckForNull
    public Grant findActiveGrant(String user, String itemFullName, @CheckForNull GrantAction action) {
        return findActiveGrant(user, itemFullName, resolve(itemFullName), action);
    }

    /**
     * D-71a: the first active grant of {@code user} naming exactly {@code item}'s full name, bound
     * to {@code item}, and including {@code action} ({@code null}: any action), or {@code null}.
     */
    @CheckForNull
    public Grant findActiveGrant(String user, @CheckForNull Item item, @CheckForNull GrantAction action) {
        return item == null ? null : findActiveGrant(user, item.getFullName(), item, action);
    }

    /**
     * D-71a: as {@link #findActiveGrant(String, Item, GrantAction)} for a window naming
     * {@code itemFullName} rather than the item's current full name: its name before a rename or
     * move, whose window was the one in use (change records). The identity is still {@code item}'s.
     */
    @CheckForNull
    public Grant findActiveGrant(String user, @CheckForNull String itemFullName, @CheckForNull Item item,
                                 @CheckForNull GrantAction action) {
        if (item == null) {
            return null;
        }
        for (Grant grant : named(user, itemFullName, action)) {
            if (isBoundTo(grant, item)) {
                return grant;
            }
        }
        return null;
    }

    /**
     * D-71: the active DELETE window of {@code user} on {@code item}, or {@code null}. A window's
     * DELETE applies only to a job ({@link GrantScope#deleteAppliesTo}), so for any other item —
     * a folder, a multibranch project, an organization folder — this is {@code null} whatever
     * windows exist: deleting such a group would delete its children as SYSTEM. D-71a: the window
     * must be bound to the item.
     */
    @CheckForNull
    public Grant findActiveDeleteGrant(String user, @CheckForNull Item item) {
        return findActiveDeleteGrant(user, item, item == null ? null : item.getFullName());
    }

    /**
     * As {@link #findActiveDeleteGrant(String, Item)} for the item under the name
     * {@code itemFullName} (its name before a move, for example); the identity is the item's.
     */
    @CheckForNull
    public Grant findActiveDeleteGrant(String user, @CheckForNull Item item, @CheckForNull String itemFullName) {
        if (!GrantScope.deleteAppliesTo(item)) {
            return null;
        }
        return findActiveGrant(user, itemFullName, item, GrantAction.DELETE);
    }

    /**
     * Every active grant of {@code user} whose scope is the item {@code itemFullName}, bound to
     * the item currently at that name (D-71a), and that includes {@code action} (D-40: the CREATE
     * check has to see all of them, since each may carry a different name restriction).
     */
    public List<Grant> findActiveGrants(String user, String itemFullName, GrantAction action) {
        return findActiveGrants(user, resolve(itemFullName), itemFullName, action);
    }

    /**
     * D-71a: every active grant of {@code user} naming exactly {@code item}'s full name, bound to
     * {@code item}, and including {@code action}.
     *
     * <p>For {@link GrantAction#CREATE}, {@code item} is the item group the new item is created in
     * (Item/Create is checked on the group's ACL), so a CREATE window confers Create in its own
     * folder only, never in a nested folder or at the root (D-71), and only while that folder is
     * the one it was approved for (D-71a).
     */
    public List<Grant> findActiveGrants(String user, @CheckForNull Item item, GrantAction action) {
        return findActiveGrants(user, item, item == null ? null : item.getFullName(), action);
    }

    private List<Grant> findActiveGrants(String user, @CheckForNull Item item, @CheckForNull String itemFullName,
                                         GrantAction action) {
        List<Grant> found = new ArrayList<>();
        if (item == null || action == null) {
            return found;
        }
        for (Grant grant : named(user, itemFullName, action)) {
            if (isBoundTo(grant, item)) {
                found.add(grant);
            }
        }
        return found;
    }

    /**
     * D-40: the first active Create grant of {@code user} conferring Create in the item group
     * {@code itemFullName} (bound to the group currently at that name, D-71a) whose name
     * restriction (if any) allows {@code itemName}, or {@code null}.
     */
    @CheckForNull
    public Grant findActiveCreateGrant(String user, String itemFullName, String itemName) {
        return firstAllowing(findActiveGrants(user, itemFullName, GrantAction.CREATE), itemName);
    }

    /**
     * D-40, D-71a: the first active Create grant of {@code user} on the item group {@code group}
     * (named exactly and bound to it) whose name restriction (if any) allows {@code itemName}, or
     * {@code null}. {@code null} for the Jenkins root, where no window exists (S-13).
     */
    @CheckForNull
    public Grant findActiveCreateGrant(String user, @CheckForNull ItemGroup<?> group, String itemName) {
        if (!(group instanceof Item)) {
            return null;
        }
        return firstAllowing(findActiveGrants(user, (Item) group, GrantAction.CREATE), itemName);
    }

    /** The first grant whose name restriction allows {@code itemName}; never under this monitor (S-03). */
    @CheckForNull
    private static Grant firstAllowing(List<Grant> grants, String itemName) {
        // Not synchronized: the list is a copy, and a user-supplied pattern is never matched while
        // this monitor is held, since every permission check passes through it (security-08 S-03).
        for (Grant grant : grants) {
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
     * D-35c: the active grant of {@code user} through whose Create {@code item} was created, or
     * {@code null}. While such a grant is active its holder also holds Item/Read and
     * Item/Configure on that item (see {@code GrantAwareACL}), so matrix-auth's creator listener
     * finds the permissions already held and writes no permanent entry; the permissions end with
     * the window.
     *
     * <p>The item must still lie directly inside the grant's scope folder, matched by parent, not by
     * name prefix (D-71), and that folder must be the one the window was approved for (D-71a: named
     * exactly and bound to it). S-09, D-71a: the identity recorded for the created item must be the
     * item's current one; an item recorded without an identity is matched by nothing.
     */
    @CheckForNull
    public Grant findCreatingGrant(String user, @CheckForNull Item item) {
        return item == null ? null : findCreatingGrant(user, item.getFullName(), item);
    }

    /**
     * As {@link #findCreatingGrant(String, Item)} for the record under the name
     * {@code itemFullName} (its name before a rename, whose record was the one in use); the parent
     * and the identities are {@code item}'s.
     */
    @CheckForNull
    public Grant findCreatingGrant(String user, @CheckForNull String itemFullName, @CheckForNull Item item) {
        if (item == null) {
            return null;
        }
        List<Grant> candidates = creatingCandidates(user, itemFullName);
        if (candidates.isEmpty()) {
            return null;
        }
        ItemGroup<?> parentGroup = item.getParent();
        Item parent = parentGroup instanceof Item ? (Item) parentGroup : null;
        if (parent == null) {
            return null; // no root-scope grant exists (S-13)
        }
        Supplier<String> current = () -> ItemIdentity.of(item);
        for (Grant grant : candidates) {
            if (grant.getScope().includes(parent.getFullName())
                    && isBoundTo(grant, parent)
                    && grant.hasCreated(itemFullName, current)) {
                return grant;
            }
        }
        return null;
    }

    /** Active grants of {@code user} recording {@code itemFullName} as created directly inside their scope (a copy). */
    private synchronized List<Grant> creatingCandidates(String user, @CheckForNull String itemFullName) {
        List<Grant> found = new ArrayList<>();
        if (user == null || itemFullName == null) {
            return found;
        }
        Instant now = BatchClock.now();
        for (Grant grant : grants()) {
            if (grant.isActiveAt(now)
                    && user.equals(grant.getUser())
                    && grant.getScope() != null
                    && grant.getScope().isParentOf(itemFullName)
                    && grant.hasCreated(itemFullName)) {
                found.add(grant);
            }
        }
        return found;
    }

    /**
     * The active grant that gives {@code user} Item/Configure on {@code item}: a grant with the
     * CONFIGURE action naming and bound to the item, or a Create grant through which the user
     * created it (D-35c). {@code null} if neither exists. Used to name the grant a change or a
     * violation came from (D-35b, SPEC item 9).
     */
    @CheckForNull
    public Grant findConfigureGrant(String user, @CheckForNull Item item) {
        Grant grant = findActiveGrant(user, item, GrantAction.CONFIGURE);
        return grant != null ? grant : findCreatingGrant(user, item);
    }

    /**
     * Active grants of {@code user} whose scope is exactly {@code itemFullName} and that include
     * {@code action} ({@code null}: any), not yet matched against an item (a copy).
     */
    private synchronized List<Grant> named(String user, @CheckForNull String itemFullName,
                                           @CheckForNull GrantAction action) {
        List<Grant> found = new ArrayList<>();
        if (user == null || itemFullName == null) {
            return found;
        }
        Instant now = BatchClock.now();
        for (Grant grant : grants()) {
            if (grant.isActiveAt(now)
                    && user.equals(grant.getUser())
                    && grant.getScope() != null
                    && grant.getScope().includes(itemFullName)
                    && (action == null || grant.getActions().contains(action))) {
                found.add(grant);
            }
        }
        return found;
    }

    /**
     * The item at {@code fullName} as the caller sees it, or {@code null} (none, not readable, or
     * Item/Discover only). Never called from inside a permission check (see above).
     */
    @CheckForNull
    private static Item resolve(@CheckForNull String fullName) {
        if (fullName == null || fullName.isEmpty() || Jenkins.getInstanceOrNull() == null) {
            return null;
        }
        try {
            return Jenkins.get().getItemByFullName(fullName);
        } catch (org.springframework.security.access.AccessDeniedException e) {
            return null;
        }
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

    /** D-58c: at most this many marked runs are listed per item. */
    static final int MAX_MARKED_RUNS = 50;

    /** At most this many REPLAY_UNDER_GRANT records are read to rebuild the index after a restart. */
    static final int MARKED_RUN_RECORDS = 5_000;

    /**
     * D-58c (S-30-05): job full name to the ids of its marked runs, newest first. Filled when a
     * marked run starts, and once per session from the REPLAY_UNDER_GRANT change records (bounded,
     * no build is loaded); never computed while a map lock is held.
     */
    private final java.util.Map<String, List<String>> markedRuns = new java.util.concurrent.ConcurrentHashMap<>();

    private volatile boolean markedRunsLoaded;

    /** The marked-run index follows a rename or move, and forgets a deleted item ({@code null}). */
    private void relocateMarkedRuns(String oldFullName, @CheckForNull String newFullName) {
        for (String job : new ArrayList<>(markedRuns.keySet())) {
            if (!job.equals(oldFullName) && !job.startsWith(oldFullName + "/")) {
                continue;
            }
            List<String> runs = markedRuns.remove(job);
            if (runs == null || newFullName == null) {
                continue;
            }
            String moved = newFullName + job.substring(oldFullName.length());
            List<String> renamed = new ArrayList<>();
            for (String runId : runs) {
                renamed.add(moved + runId.substring(job.length()));
            }
            markedRuns.put(moved, renamed);
        }
    }

    /** D-58c: a marked run started (called by the run listener). */
    public void noteMarkedRun(String jobFullName, String runId) {
        markedRuns.compute(jobFullName, (job, runs) -> prepend(runs, runId));
    }

    private static List<String> prepend(List<String> runs, String runId) {
        List<String> updated = new ArrayList<>();
        updated.add(runId);
        if (runs != null) {
            for (String existing : runs) {
                if (updated.size() >= MAX_MARKED_RUNS) {
                    break;
                }
                if (!existing.equals(runId)) {
                    updated.add(existing);
                }
            }
        }
        return updated;
    }

    /**
     * D-58c: the ids ({@code job#number}) of the runs of the item (a job, or the jobs below a
     * folder) that were replayed under a grant, at most {@value #MAX_MARKED_RUNS}. Served from the
     * in-memory index; no build is loaded.
     */
    public List<String> markedRuns(hudson.model.Item item) {
        loadMarkedRunsOnce();
        List<String> out = new ArrayList<>();
        String fullName = item.getFullName();
        List<String> own = markedRuns.get(fullName);
        if (own != null) {
            out.addAll(own);
        }
        if (item instanceof hudson.model.ItemGroup) {
            for (java.util.Map.Entry<String, List<String>> e : markedRuns.entrySet()) {
                if (out.size() >= MAX_MARKED_RUNS) {
                    break;
                }
                if (e.getKey().startsWith(fullName + "/")) {
                    out.addAll(e.getValue());
                }
            }
        }
        return out.size() > MAX_MARKED_RUNS ? new ArrayList<>(out.subList(0, MAX_MARKED_RUNS)) : out;
    }

    /**
     * Rebuilds the index once per session from the REPLAY_UNDER_GRANT records of the retained months
     * (the record's target is the job, its detail starts with "Run #n"). Bounded by
     * {@value #MARKED_RUN_RECORDS} records; a failure leaves the index as it is.
     */
    private void loadMarkedRunsOnce() {
        if (markedRunsLoaded) {
            return;
        }
        synchronized (markedRuns) {
            if (markedRunsLoaded) {
                return;
            }
            markedRunsLoaded = true;
            try {
                io.jenkins.plugins.batchcontrol.store.RecordPage<ChangeRecord> page = store.pageChangeRecords(
                        store.listStoredMonths(), r -> r.getType() == ChangeType.REPLAY_UNDER_GRANT,
                        0, MARKED_RUN_RECORDS, MARKED_RUN_RECORDS * 20);
                List<ChangeRecord> records = new ArrayList<>(page.getItems());
                java.util.Collections.reverse(records); // oldest first, so prepend leaves the newest first
                for (ChangeRecord record : records) {
                    String detail = record.getDetail();
                    if (record.getTarget() == null || detail == null || !detail.startsWith("Run #")) {
                        continue;
                    }
                    int end = 5;
                    while (end < detail.length() && Character.isDigit(detail.charAt(end))) {
                        end++;
                    }
                    if (end > 5) {
                        String runId = record.getTarget() + "#" + detail.substring(5, end);
                        markedRuns.compute(record.getTarget(), (job, runs) -> runs != null && runs.contains(runId)
                                ? runs : prepend(runs, runId));
                    }
                }
            } catch (RuntimeException e) {
                LOGGER.log(java.util.logging.Level.WARNING, "Could not rebuild the index of runs replayed under a grant", e);
            }
        }
    }

    /**
     * D-58b: whether the item, or an item above it, is in the "changed under a grant" state (any
     * grant, active or ended). Served from the in-memory grant cache, for page rendering.
     */
    public synchronized boolean isChangedUnderGrant(hudson.model.Item item) {
        if (item == null) {
            return false;
        }
        String fullName = item.getFullName();
        for (Grant grant : grants()) {
            if (changedAtOrAbove(grant, fullName)) {
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

    /**
     * Whether a grant's coverage (scope, a scope below a folder, or D-35c created) includes the item.
     *
     * <p>D-58b (1) guards every item below a scope item, unchanged by D-71: a window on a folder
     * confers nothing on its children, but the holder may change the folder's configuration (and
     * its authorization property, inherited below it), so what lies below it stays guarded.
     */
    private static boolean covers(Grant grant, String itemFullName) {
        String scope = grant.getScope() == null ? null : grant.getScope().getFullName();
        if (grant.getScope() != null && grant.getScope().includes(itemFullName)) {
            return true;
        }
        if (scope != null && !scope.isEmpty()
                // a folder's property is inherited below it, so a scope below the folder guards it
                && (scope.startsWith(itemFullName + "/")
                        // D-58b (1): everything below a scope item (branch jobs of a multibranch
                        // project, jobs in a folder)
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
        if (!mayReview(item, auth)) {
            throw new hudson.security.AccessDeniedException3(auth, hudson.model.Item.CONFIGURE);
        }
        String fullName = item.getFullName();
        // S-29-02: an entry below the item is cleared only if the reviewer may review that item too.
        java.util.Set<String> clear = new java.util.LinkedHashSet<>();
        java.util.Set<String> kept = new java.util.LinkedHashSet<>();
        for (String name : changedAtOrBelow(fullName)) {
            // S-30-03: found as SYSTEM (read-only lookup, ApprovalPolicy#itemForPolicy), so an item the
            // reviewer cannot read is not taken for a deleted one; the reviewer's own permission
            // on it is what decides below.
            hudson.model.Item below = name.equals(fullName) ? item
                    : io.jenkins.plugins.batchcontrol.policy.ApprovalPolicy.itemForPolicy(name);
            if (below == null || below == item || mayReview(below, auth)) {
                clear.add(name);
            } else {
                kept.add(name);
            }
        }
        int cleared = removeChanged(clear);
        // S-29-03: recorded only when something was cleared, and says what is still guarded.
        if (cleared > 0) {
            String still = isGuardedItem(fullName)
                    ? " The item is still guarded through an active permission window or a changed folder above it."
                    : "";
            String skipped = kept.isEmpty() ? "" : " Not cleared, because the reviewer may not review them: "
                    + String.join(", ", kept) + ".";
            store.appendChangeRecord(ChangeRecord.create(ChangeType.GUARD_REVIEWED, fullName, auth.getName(),
                    "Marked as reviewed by '" + auth.getName() + "': " + cleared + " changed-under-a-permission-window"
                            + " entr" + (cleared == 1 ? "y" : "ies") + " at or below this item cleared." + skipped + still));
            LOGGER.info(() -> "'" + fullName + "' marked as reviewed by '" + auth.getName() + "'");
        }
    }

    /**
     * S-29-04: an administrator clears a listed entry whose item no longer resolves (a stale entry).
     * Checked here too: Overall/Administer.
     */
    public void clearStaleEntry(String fullName) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        int cleared = removeChanged(java.util.Set.of(fullName));
        if (cleared > 0) {
            String user = Jenkins.getAuthentication2().getName();
            store.appendChangeRecord(ChangeRecord.create(ChangeType.GUARD_REVIEWED, fullName, user,
                    "Marked as reviewed by '" + user + "': the entry named an item that no longer exists."));
        }
    }

    private static boolean mayReview(hudson.model.Item item, org.springframework.security.core.Authentication auth) {
        return GrantLayer.hasPermissionWithoutGrants(item, auth, hudson.model.Item.CONFIGURE)
                || GrantLayer.hasPermissionWithoutGrants(Jenkins.get(), auth, Jenkins.ADMINISTER);
    }

    /** The changed entries (any grant) equal to or below {@code fullName}. */
    private synchronized java.util.Set<String> changedAtOrBelow(String fullName) {
        java.util.Set<String> found = new java.util.TreeSet<>();
        for (Grant grant : grants()) {
            for (String name : grant.getChangedItems()) {
                if (name.equals(fullName) || name.startsWith(fullName + "/")) {
                    found.add(name);
                }
            }
        }
        return found;
    }

    /** Removes the exact entries {@code names} from every grant; the number of entries removed. */
    private synchronized int removeChanged(java.util.Set<String> names) {
        int removed = 0;
        if (names.isEmpty()) {
            return 0;
        }
        for (Grant cached : new ArrayList<>(grants())) {
            boolean affected = false;
            for (String name : cached.getChangedItems()) {
                if (names.contains(name)) {
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
                List<String> kept = new ArrayList<>();
                for (String name : grant.getChangedItems()) {
                    if (names.contains(name)) {
                        removed++;
                    } else {
                        kept.add(name);
                    }
                }
                grant.setChangedItems(kept);
                store.saveGrant(grant);
                replaceInCache(grant);
            } catch (RuntimeException e) {
                LOGGER.log(java.util.logging.Level.SEVERE, "Could not clear the changed-under-grant entries of grant "
                        + cached.getId(), e);
            }
        }
        return removed;
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
            // S-30-04: guarded through a changed folder above it, and moved out of that folder.
            for (String changed : grant.getChangedItems()) {
                if (oldFullName.startsWith(changed + "/") && !newFullName.startsWith(changed + "/")) {
                    carriers.add(grant.getId());
                }
            }
        }
        rewriteChanged(oldFullName, newFullName, true);
        relocateMarkedRuns(oldFullName, newFullName);
        for (String grantId : carriers) {
            markChanged(grantId, newFullName);
        }
    }

    /** D-58a: a deleted item (and what was below it) leaves the "changed under a grant" state. */
    public synchronized void forgetChanged(String fullName) {
        rewriteChanged(fullName, null, true);
        relocateMarkedRuns(fullName, null);
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
        retryUnsavedUnbindings();
    }

    /**
     * D-71c (security-36 S-36-03 (i)): unbinds the grant {@code grantId} from its item for good,
     * because the item it was approved for is no longer at its name (called by
     * {@code policy.GrantRequestService} right after registering it, when the item was deleted and
     * another one created at the name while the approval ran). No-op for an unknown or already
     * unbound grant.
     */
    public synchronized void unbindGrant(String grantId, String why) {
        unbindWhere(grant -> grantId.equals(grant.getId()), why);
    }

    /**
     * D-35c: notes that {@code user} created {@code item} through the Create of an active grant,
     * persisting the grant. Called by {@code listener.CreatedItemGrantListener} only when the
     * creation was not possible without the grant. The Create window is the one on the item's
     * parent folder whose name restriction admits the item's name (D-40, chosen outside the
     * monitor, S-03), named exactly and bound to that folder (D-71a); the identity recorded is that
     * of the item's directory (S-09).
     *
     * @return the grant that now records the item, or {@code null} when no active Create grant of
     *         {@code user} covers the item
     */
    @CheckForNull
    public Grant recordCreatedItem(String user, Item item) {
        Grant active = findActiveCreateGrant(user, item.getParent(), item.getName());
        if (active == null) {
            return null;
        }
        return recordCreatedItemIn(active.getId(), user, item.getFullName(), ItemIdentity.of(item.getRootDir()));
    }

    @CheckForNull
    private synchronized Grant recordCreatedItemIn(String grantId, String user, String itemFullName,
                                                   @CheckForNull String identity) {
        Grant grant = store.loadGrant(grantId);
        if (grant == null || !grant.isActiveAt(BatchClock.now()) || !user.equals(grant.getUser())
                || !grant.getScope().isParentOf(itemFullName) || !grant.getActions().contains(GrantAction.CREATE)) {
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
     * follows it. Whether it still confers anything is decided by the parent check at query time,
     * so moving the item out of the scope folder ends the permission.
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
     * D-71a: the item {@code fullName} was deleted. Every active window naming it, or an item below
     * it, is unbound from its item ({@link Grant#clearItemIdentity()}) for good, so it confers
     * nothing on any item that comes to have that name later, whatever identity that item's
     * directory gets: a file system may hand the deleted directory's inode number to the next
     * directory it creates (Linux ext4, xfs), and NTFS keeps a re-created name's creation time for a
     * while. Core fires {@code ItemListener.onDeleted} before it frees the name in the parent's item
     * map ({@code Jenkins#onDeleted}, {@code AbstractFolder#onDeleted}), so no item can be created
     * under that name before this has run.
     *
     * <p>The cached copy is unbound first, so the window stops conferring even if writing its file
     * fails. The window stays listed as active until it ends or is revoked.
     */
    public synchronized void forgetDeletedItem(String fullName) {
        unbindAt(fullName, "was deleted");
    }

    /**
     * D-71a: the item {@code oldFullName} was renamed or moved to {@code newFullName} (also called
     * for each item below a renamed or moved folder). Windows naming the old name, or an item below
     * it, are unbound for good: a renamed item loses its window (fail-closed), also if it comes back
     * to that name. Windows naming the new name are unbound too: their own item cannot be there (it
     * left that name, or disappeared without Jenkins seeing it), so they must not apply to the item
     * that arrived, whatever its identity.
     */
    public synchronized void forgetRelocatedItem(String oldFullName, String newFullName) {
        unbindAt(oldFullName, "was renamed or moved");
        unbindAt(newFullName, "is now the name of another item");
    }

    /**
     * D-71a: an item was created (or copied) under {@code fullName}. A window still bound under that
     * name belongs to an item that disappeared without Jenkins seeing it (deleted on disk and
     * reloaded); it is unbound, so it never applies to the new item, whatever identity the new
     * item's directory gets.
     */
    public synchronized void forgetNewItemName(String fullName) {
        unbindAt(fullName, "is now the name of a new item");
    }

    /**
     * D-71a: after all items are loaded at startup, unbinds every active window whose item no
     * longer exists ({@code exists} is false for its full name): it was deleted while Jenkins was
     * down, or a crash came between deleting it and handling the deletion.
     */
    public synchronized void unbindMissingItems(Predicate<String> exists) {
        unbindWhere(grant -> !exists.test(grant.getScope().getFullName()), "no longer exists");
    }

    /** Unbinds the windows naming {@code fullName} or an item below it. */
    private synchronized void unbindAt(@CheckForNull String fullName, String why) {
        if (fullName == null || fullName.isEmpty()) {
            return;
        }
        unbindWhere(grant -> {
            String scope = grant.getScope().getFullName();
            return scope.equals(fullName) || scope.startsWith(fullName + "/");
        }, why);
    }

    /**
     * Unbinds the active, still bound windows (with a non-empty scope full name) that
     * {@code affected} accepts. The cached copy is unbound first, so the window stops conferring
     * at once; a file that cannot be written is retried ({@link #unsavedUnbindings}).
     */
    private synchronized void unbindWhere(Predicate<Grant> affected, String why) {
        retryUnsavedUnbindings();
        Instant now = BatchClock.now();
        for (Grant cached : new ArrayList<>(grants())) {
            String scope = cached.getScope() == null ? null : cached.getScope().getFullName();
            if (!cached.isActiveAt(now) || cached.getItemIdentity() == null || scope == null || scope.isEmpty()
                    || !affected.test(cached)) {
                continue;
            }
            cached.clearItemIdentity(); // effective at once, whatever happens to the file
            unsavedUnbindings.add(cached.getId());
            saveUnbinding(cached.getId());
            LOGGER.info(() -> "Grant " + cached.getId() + " no longer applies: its item '" + scope + "' " + why);
        }
    }

    /**
     * D-71c (security-36 S-36-03 (ii)): retries writing every unbinding whose file could not be
     * written. Called by the expiry periodic work and on every unbinding and registration.
     */
    public synchronized void retryUnsavedUnbindings() {
        for (String id : new ArrayList<>(unsavedUnbindings)) {
            saveUnbinding(id);
        }
    }

    /**
     * Writes the unbinding of grant {@code id} to its file; on success (or when there is nothing
     * left to write) the id leaves {@link #unsavedUnbindings}, otherwise it stays for a retry.
     */
    private synchronized void saveUnbinding(String id) {
        try {
            Grant grant = store.loadGrant(id);
            if (grant != null && grant.getItemIdentity() != null) {
                grant.clearItemIdentity();
                store.saveGrant(grant);
            }
            unsavedUnbindings.remove(id);
            if (grant != null) {
                replaceInCache(grant);
            }
        } catch (RuntimeException e) {
            LOGGER.log(java.util.logging.Level.SEVERE, "Could not save grant " + id + " as no longer bound to its"
                    + " item; it confers nothing, and the write is retried on the next item event and by the"
                    + " periodic expiry work", e);
        }
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
     * D-71a: a new item was created under {@code fullName}; a created-item record under exactly
     * that name belongs to an item that disappeared without a deletion event (deleted on disk and
     * reloaded) and is dropped, so it never applies to the new item, whatever identity the new
     * item's directory gets. Exact name only: a copied folder's children are created (and possibly
     * recorded) before the folder's own creation event.
     */
    public synchronized void forgetStaleCreatedItem(String fullName) {
        if (fullName == null || fullName.isEmpty()) {
            return;
        }
        Instant now = BatchClock.now();
        for (Grant cached : new ArrayList<>(grants())) {
            if (!cached.isActiveAt(now) || !cached.hasCreated(fullName)) {
                continue;
            }
            Grant grant = store.loadGrant(cached.getId());
            if (grant == null) {
                continue;
            }
            List<String> kept = grant.getCreatedItems();
            kept.remove(fullName);
            grant.setCreatedItems(kept); // drops the identity recorded for it too
            store.saveGrant(grant);
            replaceInCache(grant);
            LOGGER.info(() -> "Grant " + grant.getId() + ": dropped the stale created-item record of '" + fullName
                    + "', now the name of a new item");
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
        revokeOne(grant, caller, null, "revoked");
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
            // #85: the record and the grant say why, not just who.
            revokeOne(grant, caller, Grant.REVOKED_CHANGE_CONTROL_OFF, "revoked: "
                    + Grant.REVOKED_CHANGE_CONTROL_OFF + " by '" + caller + "' ("
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
    private void revokeOne(Grant grant, String caller, @CheckForNull String reason, String detail) {
        grant.markRevoked(BatchClock.now(), caller, reason);
        store.saveGrant(grant);
        replaceInCache(grant);
        ChangeRecord record = ChangeRecord.create(ChangeType.GRANT_REVOKE,
                grant.getScope() == null ? null : grant.getScope().getFullName(), caller,
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
            for (Grant grant : cache) {
                if (unsavedUnbindings.contains(grant.getId())) {
                    grant.clearItemIdentity(); // S-36-03: the file still carries the old binding
                }
            }
        }
        return cache;
    }

    /**
     * Puts {@code grant} in the cache in place of the copy with the same id. A copy of a grant
     * whose unbinding is not written yet is unbound before it enters the cache (S-36-03): any write
     * of that grant loaded from its file would otherwise bring the old binding back.
     */
    private synchronized void replaceInCache(Grant grant) {
        if (unsavedUnbindings.contains(grant.getId()) && grant.getItemIdentity() != null) {
            grant.clearItemIdentity();
            try {
                store.saveGrant(grant);
                unsavedUnbindings.remove(grant.getId());
            } catch (RuntimeException e) {
                LOGGER.log(java.util.logging.Level.WARNING, "Could not save grant " + grant.getId()
                        + " as no longer bound to its item; retried later", e);
            }
        }
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
