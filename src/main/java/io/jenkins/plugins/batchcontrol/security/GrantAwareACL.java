package io.jenkins.plugins.batchcontrol.security;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.AbstractItem;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.security.ACL;
import hudson.security.AccessControlled;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.CreateNamePattern;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.model.ItemKind;
import io.jenkins.plugins.batchcontrol.store.BlockedAttemptAudit;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.springframework.security.core.Authentication;

/**
 * The grant layer of the Batch Control strategies (ARCHITECTURE section 4, D-35a): each of
 * {@link BatchControlMatrixAuthorizationStrategy} and {@link BatchControlRoleBasedAuthorizationStrategy}
 * wraps the parent strategy's ACL (the "delegate" below) in one of these. For the three grantable
 * item permissions (Item/Create, Item/Configure, Item/Delete) an active JIT grant is consulted
 * first; every other decision — and every miss — goes to the parent's ACL unchanged, so with no
 * active grant the behavior is exactly the parent strategy's.
 *
 * <p>S-15: a grant is consulted only while the change-control switch is on. With the switch off no
 * grant confers anything — every decision is the delegate's, exactly as before the plugin was
 * installed — which is what makes the switch a kill switch rather than a label. See
 * {@link #grantConfers} for why the check sits where it does, and
 * {@link GrantService#revokeAllActive} for the windows that are already open when the switch is
 * turned off.
 *
 * <p>A permission is not only conferred when the grant names it literally: Jenkins resolves a
 * permission against the {@link Permission#impliedBy} chain, so holding Item/Configure also
 * answers everything Item/Configure implies. The most visible case is {@code Item.EXTENDED_READ},
 * which core ships disabled and declares {@code impliedBy = Item.CONFIGURE}; it is what gates
 * <em>reading</em> a job's configuration ({@code Job/configure.jelly} renders under
 * {@code <l:layout permission="${it.EXTENDED_READ}">} and {@code AbstractItem#writeConfigDotXml}
 * calls {@code checkPermission(EXTENDED_READ)}). A grant that answered only the literal
 * permission therefore let the requester save a configuration they could not open. See
 * {@link #grantConfers} for the walk and the rule it mirrors.
 *
 * <p>D-35c: an item its holder created through the Create of an active grant also answers
 * Item/Read and Item/Configure (and what they imply) for the holder while the grant is active, so
 * matrix-auth's creator listener finds both already held and writes no permanent entry. Exactly
 * that item: its descendants the holder did not create get nothing from it (D-35d), which is why
 * the delegate is always evaluated with the grant layer switched off (see {@link #hasPermission2}).
 *
 * <p>D-71a (security-34 S-34-01, S-34-03): a window confers something only on the very item it
 * was approved for: it must name this item's full name exactly <em>and</em> be bound to this item
 * ({@link GrantService#isBoundTo}: the identity recorded at approval and the kind). A rename, move,
 * swap or re-creation therefore never re-points a window; a renamed item loses its window
 * (fail-closed). The identity is only looked at once an active window of the user names the item
 * (an in-memory match), and is cached per item object ({@link ItemIdentity}), so the hot path does
 * not touch the file system for the many items a page asks about. While change control is on, a
 * window's Configure also does not allow renaming an item group that is not a job: renaming it
 * renames everything inside it, which would re-point permissions matched by name elsewhere (a
 * role-strategy pattern). See {@link #refuseGroupRename}.
 *
 * <p>{@link #withoutGrants} evaluates a check with the grant layer switched off on the current
 * thread, which is how the listeners tell a permission that comes only from a grant from one the
 * installed strategy gives natively (D-35b, D-35c).
 *
 * <p>A {@code null} delegate ACL denies everything except SYSTEM (safe default for a strategy
 * that cannot be resolved).
 */
@Restricted(NoExternalUse.class)
final class GrantAwareACL extends ACL {

    private static final Logger LOGGER = Logger.getLogger(GrantAwareACL.class.getName());

    /** Depth of {@link #withoutGrants} calls on this thread; grants confer nothing while positive. */
    private static final ThreadLocal<Integer> SUSPENDED = new ThreadLocal<>();

    /** The delegate's ACL for the same object; {@code null} denies all but SYSTEM. */
    @CheckForNull
    private final ACL delegate;

    /**
     * Full name of the item this ACL guards; {@code null} for everything grants never apply to —
     * views, nodes, users, clouds, computers, and the Jenkins root itself (S-13: there is no
     * root-scope grant, so root-level Item/Create is the delegate's decision alone).
     */
    @CheckForNull
    private final String itemFullName;

    /**
     * The item this ACL guards; {@code null} with no item. D-71a: a window confers something only
     * when it names this item's full name and is bound to this item ({@link GrantService#isBoundTo});
     * for Item/Create this is the folder the new item is created in.
     */
    @CheckForNull
    private final Item item;

    /**
     * D-71a: whether the item is an item group that is not a job ({@link GrantScope#isNonJobGroup}):
     * while change control is on, a window's Configure does not allow renaming it.
     */
    private final boolean nonJobGroup;

    /**
     * D-71: whether a window's DELETE can apply to the item, i.e. it is a job
     * ({@link GrantScope#deleteAppliesTo}). A folder, multibranch project or organization folder
     * never gets Item/Delete from a window, whatever the stored grant says; {@code false} when the
     * item is unknown (fail-safe).
     */
    private final boolean deleteApplies;

    /**
     * D-71: whether a window's CREATE can apply to the item, i.e. it is a regular folder
     * ({@link GrantScope#createAppliesTo}); {@code false} when the item is unknown (fail-safe).
     */
    private final boolean createApplies;

    /**
     * An ACL for an object grants never apply to (the root, a node, a computer). The full name is
     * kept for the callers' symmetry; without an item no window can be bound, so nothing is
     * conferred whatever it says.
     */
    GrantAwareACL(@CheckForNull ACL delegate, @CheckForNull String itemFullName) {
        this(delegate, itemFullName, null);
    }

    GrantAwareACL(@CheckForNull ACL delegate, @CheckForNull AbstractItem item) {
        this(delegate, item == null ? null : item.getFullName(), item);
    }

    private GrantAwareACL(@CheckForNull ACL delegate, @CheckForNull String itemFullName, @CheckForNull Item item) {
        this.delegate = delegate;
        this.itemFullName = itemFullName;
        this.item = item;
        this.deleteApplies = GrantScope.deleteAppliesTo(item);
        this.createApplies = GrantScope.createAppliesTo(item);
        this.nonJobGroup = GrantScope.isNonJobGroup(item);
    }

    /**
     * Runs {@code check} with every grant layer switched off on this thread, so the answer is the
     * installed strategy's own (D-35b, D-35c). Nestable.
     */
    static boolean withoutGrants(java.util.function.BooleanSupplier check) {
        Integer previous = SUSPENDED.get();
        SUSPENDED.set(previous == null ? 1 : previous + 1);
        try {
            return check.getAsBoolean();
        } finally {
            if (previous == null) {
                SUSPENDED.remove(); // pooled threads keep no entry behind
            } else {
                SUSPENDED.set(previous);
            }
        }
    }

    private static boolean suspended() {
        return SUSPENDED.get() != null;
    }

    @Override
    public boolean hasPermission2(@NonNull Authentication a, @NonNull Permission permission) {
        if (a.equals(SYSTEM2)) {
            return true;
        }
        Decision decision = Decision.NONE;
        if (itemFullName != null && item != null && !ACL.isAnonymous2(a) && !suspended()) {
            decision = grantConfers(a, permission);
            if (decision.confers) {
                return true;
            }
        }
        // D-35d (1): the parent's decision is taken with every grant layer switched off. matrix-auth
        // resolves an item without its own property through the parent folder's ACL, which is a
        // grant-aware ACL too; evaluated with grants on, a D-35c grant on a folder the holder
        // created would reach every descendant through that inheritance, and a window on a folder
        // would widen to its children. Only this, the outermost layer, consults grants: a window
        // names exactly one item (D-71), and D-35c answers for exactly the items the holder created.
        ACL parent = delegate;
        boolean allowed = parent != null && withoutGrants(() -> parent.hasPermission2(a, permission));
        if (allowed && MoveGuard.isMovePermission(permission) && itemFullName != null && !suspended()
                && a.getName().equals(Jenkins.getAuthentication2().getName())) {
            // D-59: the folders plugin checks Item/Move on the item first in move/move, and then
            // drops the request without a word when the destination is not offered to the user (a
            // name-restricted Create grant, or no Create at all). The whole move is decided here,
            // as the same user, so a refusal is recorded and explained; ChangeControlledRelocation-
            // Handler takes the same decision again right before the move.
            MoveRefusal refusal = MoveGuard.checkCurrentRequest(itemFullName);
            if (refusal != null) {
                throw refusal;
            }
        }
        if (!allowed && decision.groupRename) {
            // D-71a: only core's own rename check (Item/Configure) is answered with the refusal; any
            // other check this request makes on the item (EXTENDED_READ, say) is just not conferred.
            if (permission == Item.CONFIGURE) {
                refuseGroupRename(a, decision);
            }
            return false;
        }
        if (!allowed && decision.refusedName != null) {
            if (decision.recordable) {
                decision.recordRefusal(a.getName());
            }
            // e2e-03 DEF-19: core checks Item/Create before it looks at the name, so a plain refusal
            // is an "Access Denied ... missing the Job/Create permission" that does not say why (and,
            // while the holder types, a server log line per keystroke). On the web endpoints that
            // create or rename, and on the checks their pages run while a name is typed, the refusal
            // is instead answered with the explanation. Only the Create check of the current user's
            // own request is answered this way; it is refused either way, and nothing has changed.
            if (permission == Item.CREATE && a.getName().equals(Jenkins.getAuthentication2().getName())) {
                if (!decision.recordable && NewItemName.isValidationOperation(decision.operation)) {
                    throw NameRestrictionValidation.validation(decision.explain());
                }
                if (decision.recordable && NewItemName.isWebChangeOperation(decision.operation)) {
                    throw NameRestrictionValidation.refusal(decision.explain());
                }
                if (decision.recordable && NewItemName.isCliOperation(decision.operation)) {
                    // e2e-03 DEF-36: the CLI prints an IllegalStateException as one "ERROR: <message>"
                    // line (exit 4) instead of "missing the Job/Create permission".
                    throw new IllegalStateException(decision.explain());
                }
            }
        }
        return allowed;
    }

    /**
     * D-40: a Create refused only because the new item's name is outside the restriction of every
     * active Create grant covering the group. Recorded as {@code GRANT_VIOLATION} when the
     * installed strategy refuses too, i.e. the attempt is actually denied. Repeats of the same
     * attempt within a minute are merged ({@link BlockedAttemptAudit}), since core and the UI may
     * evaluate the same check more than once per request.
     */
    private static final class Decision {
        /** The grant layer confers the permission. */
        static final Decision CONFERS = new Decision(true, null, null, null, false, null, false);
        /** The grant layer does not confer it; the delegate decides. */
        static final Decision NONE = new Decision(false, null, null, null, false, null, false);

        final boolean confers;
        /** D-40: the refused new name, or {@code null} when no named creation/rename was refused. */
        @CheckForNull
        final String refusedName;
        /** The group the item would have been created or renamed in. */
        @CheckForNull
        final String groupFullName;
        @CheckForNull
        final Grant grant;
        /** S-05: whether the refusal is an actual attempt (not a validation or read-only request). */
        final boolean recordable;
        /** The operation that was refused; part of the merge key, so each operation is recorded. */
        @CheckForNull
        final String operation;
        /**
         * D-71a: a window would confer Configure on this item group, but the current request renames
         * it, so the window answers nothing ({@link #refusedName} is the new name, possibly
         * {@code null} when unknown; {@link #grant} is the window).
         */
        final boolean groupRename;

        private Decision(boolean confers, @CheckForNull String refusedName, @CheckForNull String groupFullName,
                         @CheckForNull Grant grant, boolean recordable, @CheckForNull String operation,
                         boolean groupRename) {
            this.confers = confers;
            this.refusedName = refusedName;
            this.groupFullName = groupFullName;
            this.grant = grant;
            this.recordable = recordable;
            this.operation = operation;
            this.groupRename = groupRename;
        }

        static Decision refused(String itemName, String groupFullName, Grant grant, NewItemName context) {
            return new Decision(false, itemName, groupFullName, grant, context.isRecordable(),
                    context.getOperation(), false);
        }

        /** D-71a: the rename of an item group whose Configure would come from {@code grant}. */
        static Decision groupRenameRefused(NewItemName context, Grant grant) {
            return new Decision(false, context.getName(), null, grant, context.isRecordable(),
                    context.getOperation(), true);
        }

        /** Plain-text explanation of the refusal for the holder (e2e-03 DEF-19). */
        String explain() {
            Grant grant = this.grant;
            String group = groupFullName == null || groupFullName.isEmpty() ? "Jenkins" : "'" + groupFullName + "'";
            String allowed = grant == null || grant.getCreateNamePattern() == null
                    ? "a restricted set of names"
                    : CreateNamePattern.describe(grant.getCreateNamePattern());
            return "'" + refusedName + "' is not allowed here: your permission window for " + group
                    + (grant == null ? "" : " (grant " + grant.getId() + ")")
                    + " only allows " + allowed + ". Choose a name within that restriction, or request a new"
                    + " permission window for this name.";
        }

        void recordRefusal(String user) {
            String itemName = refusedName;
            Grant grant = this.grant;
            String group = groupFullName;
            if (itemName == null || grant == null || group == null) {
                return;
            }
            String target = group.isEmpty() ? itemName : group + "/" + itemName;
            try {
                String attemptKey = (operation == null ? "" : operation + " ") + target;
                BlockedAttemptAudit.get().record(ChangeType.GRANT_VIOLATION, attemptKey, target, user,
                        "Refused to create or rename to '" + itemName + "' in '" + group + "': the name is "
                                + "outside the name restriction '" + grant.getCreateNamePattern() + "' of grant "
                                + grant.getId(), grant.getId());
            } catch (RuntimeException e) {
                // The refusal stands whatever happens to the record.
                LOGGER.log(Level.WARNING, "Could not record the refused name '" + target + "'", e);
            }
            LOGGER.info(() -> "Refused the name '" + itemName + "' in '" + group + "' for '" + user
                    + "': outside the name restriction of grant " + grant.getId());
        }
    }

    /**
     * Whether an active grant of {@code user} on this item answers {@code permission}, following
     * the same implication rule Jenkins authorization strategies use.
     *
     * <p>The rule is taken from matrix-auth's {@code AuthorizationContainer#hasPermission(String,
     * Permission, boolean)} (matrix-auth 3.3), which is what resolves a permission against the
     * stored entries for the delegate strategy of this plugin, and from core's
     * {@code SparseACL#hasPermission(Sid, Permission)}. Both walk the chain the same way:
     *
     * <pre>for (; p != null; p = p.impliedBy) { if (!p.getEnabled()) continue; ...check p... }</pre>
     *
     * <p>Two properties of that loop matter here and are reproduced exactly:
     * <ul>
     *   <li>The walk is <b>unconditional</b> — every link up to the root is visited. It is not
     *       "step up only while the permission is disabled". The {@code while (!p.enabled &amp;&amp;
     *       p.impliedBy != null)} loop in core's {@code ACL#checkPermission} is not the decision
     *       rule: it runs only after the decision came back false, to pick the name put in the
     *       {@code AccessDeniedException3} message. That is why a denied {@code Item.EXTENDED_READ}
     *       is reported as a missing "Job/Configure" permission.</li>
     *   <li>A <b>disabled</b> link is skipped as a candidate but does not stop the walk, so a
     *       grant is never matched against a permission the administrator turned off — the same
     *       treatment matrix-auth gives a stored matrix entry.</li>
     * </ul>
     *
     * <p>Only links that are themselves grantable are looked up ({@link GrantAction#fromPermission}
     * is identity-based), so the walk cannot widen a grant: the generic root permissions the chain
     * passes through ({@code Permission.CONFIGURE}, {@code UPDATE}, {@code WRITE},
     * {@code ADMINISTER}) are not grantable, and in core only {@code Item.EXTENDED_READ} reaches
     * {@code Item.CONFIGURE} this way. Scope and action stay the grant's own:
     * {@link GrantService#hasActiveGrant} is still asked about this item only, and about the
     * grantable permission actually found on the chain.
     */
    private Decision grantConfers(Authentication a, Permission permission) {
        // S-15: the change-control switch is a kill switch. While it is off a grant confers
        // nothing, so the answer is the delegate's alone and the instance behaves exactly like the
        // strategy the administrator actually configured (CLAUDE.md: "a new feature does not change
        // existing Jenkins behaviour while the global switch is off"; SPEC item 1).
        //
        // The guard sits ahead of the implication walk rather than inside it, and that placement is
        // the point: every "return true" below is inside the loop, so one check before the loop is
        // entered cannot be walked around by an impliedBy chain (Item.EXTENDED_READ reaching
        // Item.CONFIGURE, say). It is also the cheapest step in the method — an extension-list
        // singleton lookup in front of the synchronized grant scan it now skips — so on an instance
        // that does not use change control this makes the permission hot path faster, not slower.
        if (!BatchControlGlobalConfiguration.get().isChangeControlEnabled()) {
            return Decision.NONE;
        }
        String user = a.getName();
        Decision result = Decision.NONE;
        boolean createdChecked = false;
        for (Permission p = permission; p != null; p = p.impliedBy) {
            if (!p.getEnabled()) {
                continue;
            }
            if (!createdChecked && (p == Item.READ || p == Item.CONFIGURE)) {
                // D-35c: Read and Configure on an item the holder created through an active Create
                // grant. Looked up once per walk; the same enabled-link rule applies.
                createdChecked = true;
                Grant creating = GrantService.get().findCreatingGrant(user, item);
                if (creating != null) {
                    NewItemName groupRename = p == Item.CONFIGURE ? renameOfGroup(a) : null;
                    if (groupRename != null) {
                        // D-71a: no window's Configure renames an item group, the D-35c one included.
                        // A CONFIGURE window on the same item answers nothing either (below).
                        result = Decision.groupRenameRefused(groupRename, creating);
                        continue;
                    }
                    Decision rename = p == Item.CONFIGURE ? renameUnderRestriction(creating) : null;
                    if (rename == null) {
                        return Decision.CONFERS;
                    }
                    // SPEC item 8 (D-40, D-40a): renaming an item created through a restricted
                    // Create grant must match the restriction, so no grant confers this rename,
                    // not even a CONFIGURE action of the same or another window (e2e-03 DEF-19).
                    // The installed strategy's own Configure still decides underneath.
                    return rename;
                }
            }
            if (GrantAction.fromPermission(p) == null) {
                continue;
            }
            if (p == Item.CREATE) {
                Decision create = createConfers(user);
                if (create.confers) {
                    return create;
                }
                if (create.refusedName != null && result.refusedName == null) {
                    result = create;
                }
                continue;
            }
            if (p == Item.DELETE && !deleteApplies) {
                // D-71: no window confers Delete on an item group that is not a job; core would
                // delete its children as SYSTEM without checking them.
                continue;
            }
            // D-71a: the window must name this item and be bound to it (identity and kind).
            Grant window = GrantService.get().findActiveGrant(user, item, GrantAction.fromPermission(p));
            if (window == null) {
                continue;
            }
            if (p == Item.CONFIGURE) {
                NewItemName groupRename = renameOfGroup(a);
                if (groupRename != null) {
                    // D-71a (security-34 S-34-01): renaming an item group renames everything inside
                    // it, which re-points name-matched permissions (role-strategy patterns, other
                    // windows' names); a window's Configure does not allow it. The installed
                    // strategy's own Configure still decides underneath.
                    if (!result.groupRename) {
                        result = Decision.groupRenameRefused(groupRename, window);
                    }
                    continue;
                }
            }
            return Decision.CONFERS;
        }
        return result;
    }

    /**
     * D-71a: the context of the current request when it renames this item and this item is an
     * item group that is not a job ({@link GrantScope#isNonJobGroup}), else {@code null}. Only for
     * the current user's own check: a check about someone else during this request is not their
     * rename. Read from the endpoint performing the rename (D-40a, {@link NewItemName#forRename}):
     * core's {@code confirmRename} and its {@code checkNewName} validation; no other web, CLI or
     * REST path renames an item for a non-administrator.
     */
    @CheckForNull
    private NewItemName renameOfGroup(Authentication a) {
        if (!nonJobGroup || itemFullName == null
                || !a.getName().equals(Jenkins.getAuthentication2().getName())) {
            return null;
        }
        return NewItemName.forRename(itemFullName);
    }

    /**
     * D-71a: the current user's rename of this item group, whose Configure would come only from a
     * window, was refused by the window layer and the installed strategy gives no Configure. Core
     * then still allows the rename for a user with Item/Delete on the item and Item/Create in its
     * parent ({@code AbstractItem#doCheckNewName}); no window confers Delete on an item group
     * (D-71), so that takes the user's own Delete. When that path is open nothing is refused here
     * and core decides. Otherwise the refusal is recorded as {@code GRANT_VIOLATION} (a real attempt
     * only; repeats within a minute merged, D-73) and answered with the explanation: a field message
     * for {@code checkNewName}, a plain HTTP 400 page for {@code confirmRename}.
     */
    private void refuseGroupRename(Authentication a, Decision decision) {
        ACL parentAcl = delegate;
        Item renamed = item;
        if (renamed == null) {
            return;
        }
        boolean nativeDelete = parentAcl != null && withoutGrants(() -> parentAcl.hasPermission2(a, Item.DELETE));
        if (nativeDelete) {
            ItemGroup<?> parent = renamed.getParent();
            if (!(parent instanceof AccessControlled)
                    || ((AccessControlled) parent).getACL().hasPermission2(a, Item.CREATE)) {
                return; // core's Delete-and-Create path is open: core decides
            }
        }
        String user = a.getName();
        String kind = describeKind(renamed);
        String explanation = groupRenameExplanation(itemFullName, kind);
        if (decision.recordable) {
            recordGroupRename(user, kind, decision);
        }
        if (NewItemName.isValidationOperation(decision.operation)) {
            throw NameRestrictionValidation.validation(explanation);
        }
        if (NewItemName.isWebChangeOperation(decision.operation)) {
            throw NameRestrictionValidation.refusal(explanation + " Nothing was renamed.");
        }
    }

    /**
     * D-71a: what the user is told when a window's Configure does not allow renaming an item group
     * (plain text; escaped where it is rendered).
     */
    static String groupRenameExplanation(String fullName, String kind) {
        return "Renaming '" + fullName + "' (" + kind + ") is not allowed: while change control is on, a permission"
                + " window does not allow renaming a folder, multibranch project or organization folder, because that"
                + " also renames every item inside it. Renaming it needs your own Item/Configure on it, or an"
                + " administrator.";
    }

    /** D-71a, D-73: the refused rename of an item group, merged with its repeats for a minute. */
    private void recordGroupRename(String user, String kind, Decision decision) {
        String target = itemFullName;
        Grant window = decision.grant;
        if (target == null) {
            return;
        }
        String newName = decision.refusedName == null ? "?" : decision.refusedName;
        boolean written;
        try {
            written = BlockedAttemptAudit.get().record(ChangeType.GRANT_VIOLATION,
                    "rename-group " + target + " " + newName, target, user,
                    "Refused to rename '" + target + "' (" + kind + ") to '" + newName + "' for '" + user + "': while"
                            + " change control is on, a permission window does not allow renaming a folder,"
                            + " multibranch project or organization folder; Item/Configure on it comes only from "
                            + (window == null ? "a permission window" : "grant " + window.getId()),
                    window == null ? null : window.getId());
        } catch (RuntimeException e) {
            // The refusal stands whatever happens to the record.
            LOGGER.log(Level.WARNING, "Could not record the refused rename of '" + target + "'", e);
            written = true; // no record: the refusal must at least reach the log
        }
        LOGGER.log(written ? Level.INFO : Level.FINE, () -> "Refused to rename '" + target + "' to '" + newName
                + "' for '" + user + "': a permission window does not allow renaming an item group");
    }

    /** The item's kind as shown to the user, for example "Folder" or "Multibranch Pipeline". */
    private static String describeKind(Item renamed) {
        ItemKind kind = ItemKind.of(renamed);
        return kind == null ? "item group" : kind.getDisplayName();
    }

    /**
     * D-40: whether an active Create grant confers Item/Create on this group for the item being
     * created. A grant without a name restriction always does. A restricted grant does only when
     * the new item's name ({@link NewItemName}) matches in full, or when the check is a read-only
     * page view that cannot create anything. Where the name cannot be determined, a restricted
     * grant confers nothing (fail-safe), so no item with another name can come into existence.
     *
     * @return {@link Decision#CONFERS}, {@link Decision#NONE}, or a refusal naming the item when a
     *         named creation was refused only because of the restriction
     */
    private Decision createConfers(String user) {
        if (!createApplies) {
            // D-71: a CREATE window exists only on a regular folder; on anything else (a job, a
            // computed folder) it confers nothing, whatever the stored grant says.
            return Decision.NONE;
        }
        // D-71a: the windows must name this folder and be bound to it (the folder's identity).
        List<Grant> grants = GrantService.get().findActiveGrants(user, item, GrantAction.CREATE);
        if (grants.isEmpty()) {
            return Decision.NONE;
        }
        for (Grant grant : grants) {
            if (grant.getCreateNamePattern() == null) {
                return Decision.CONFERS;
            }
        }
        NewItemName context = NewItemName.forCreate(itemFullName);
        switch (context.getKind()) {
            case UNNAMED:
                return Decision.CONFERS;
            case NAMED:
                String name = context.getName();
                // Matched here, outside every lock: the grants are a copy (security-08 S-03).
                for (Grant grant : grants) {
                    if (grant.allowsCreateName(name)) {
                        return Decision.CONFERS;
                    }
                }
                return name == null ? Decision.NONE
                        : Decision.refused(name, itemFullName, grants.get(0), context);
            default:
                return Decision.NONE;
        }
    }

    /**
     * D-40a (security-08 S-01): the D-35c Configure on an item created through a name-restricted
     * Create grant would let its holder rename the item to any name. When the current request
     * renames this item, the new name must satisfy the restriction.
     *
     * @return {@code null} when the D-35c Configure may be conferred (no restriction, not a rename,
     *         or a matching new name); otherwise the refusal
     */
    @CheckForNull
    private Decision renameUnderRestriction(Grant creating) {
        if (creating.getCreateNamePattern() == null) {
            return null;
        }
        NewItemName context = NewItemName.forRename(itemFullName);
        if (context == null) {
            return null;
        }
        String name = context.getName();
        String current = itemFullName.substring(itemFullName.lastIndexOf('/') + 1);
        if (context.getKind() == NewItemName.Kind.NAMED && name != null && name.trim().equals(current)
                && "checkNewName".equals(context.getOperation())
                && creating.getUser() != null
                && creating.getUser().equals(Jenkins.getAuthentication2().getName())) {
            // e2e-03 DEF-36: the Rename page checks its field once on load, with the current name;
            // core would answer "the same as the current name". The holder is told the restriction
            // that governs the rename instead. Only the holder's own check; nothing is refused.
            throw NameRestrictionValidation.notice("Renaming '" + current + "' is limited by your permission"
                    + " window: it only allows " + CreateNamePattern.describe(creating.getCreateNamePattern())
                    + ". A name outside that restriction is refused.");
        }
        if (context.getKind() == NewItemName.Kind.NAMED && creating.allowsCreateName(name)) {
            return null;
        }
        String parent = itemFullName.contains("/") ? itemFullName.substring(0, itemFullName.lastIndexOf('/')) : "";
        return name == null ? Decision.NONE
                : Decision.refused(name, parent, creating, context);
    }
}
