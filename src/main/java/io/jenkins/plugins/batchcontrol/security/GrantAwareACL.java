package io.jenkins.plugins.batchcontrol.security;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.AbstractItem;
import hudson.model.Item;
import hudson.security.ACL;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.CreateNamePattern;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.store.BlockedAttemptAudit;
import java.io.File;
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

    private static final GrantAwareACL DENY_ALL = new GrantAwareACL(null, (String) null);

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

    /** The directory of the item this ACL guards (S-09 identity check); {@code null} with no item. */
    @CheckForNull
    private final File itemRootDir;

    GrantAwareACL(@CheckForNull ACL delegate, @CheckForNull String itemFullName) {
        this(delegate, itemFullName, null);
    }

    GrantAwareACL(@CheckForNull ACL delegate, @CheckForNull AbstractItem item) {
        this(delegate, item == null ? null : item.getFullName(), item == null ? null : item.getRootDir());
    }

    private GrantAwareACL(@CheckForNull ACL delegate, @CheckForNull String itemFullName,
                          @CheckForNull File itemRootDir) {
        this.delegate = delegate;
        this.itemFullName = itemFullName;
        this.itemRootDir = itemRootDir;
    }

    /** The deny-all-but-SYSTEM ACL used when no parent ACL is available. */
    static GrantAwareACL denyAll() {
        return DENY_ALL;
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
        if (itemFullName != null && !ACL.isAnonymous2(a) && !suspended()) {
            decision = grantConfers(a.getName(), permission);
            if (decision.confers) {
                return true;
            }
        }
        // D-35d (1): the parent's decision is taken with every grant layer switched off. matrix-auth
        // resolves an item without its own property through the parent folder's ACL, which is a
        // grant-aware ACL too; evaluated with grants on, a D-35c grant on a folder the holder
        // created would reach every descendant through that inheritance, and a JOB-scope grant on
        // a folder would widen to its children. Only this, the outermost layer, consults grants:
        // FOLDER scope already matches descendants by path above, and D-35c answers for exactly
        // the items the holder created.
        ACL parent = delegate;
        boolean allowed = parent != null && withoutGrants(() -> parent.hasPermission2(a, permission));
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
        static final Decision CONFERS = new Decision(true, null, null, null, false, null);
        /** The grant layer does not confer it; the delegate decides. */
        static final Decision NONE = new Decision(false, null, null, null, false, null);

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

        private Decision(boolean confers, @CheckForNull String refusedName, @CheckForNull String groupFullName,
                         @CheckForNull Grant grant, boolean recordable, @CheckForNull String operation) {
            this.confers = confers;
            this.refusedName = refusedName;
            this.groupFullName = groupFullName;
            this.grant = grant;
            this.recordable = recordable;
            this.operation = operation;
        }

        static Decision refused(String itemName, String groupFullName, Grant grant, NewItemName context) {
            return new Decision(false, itemName, groupFullName, grant, context.isRecordable(),
                    context.getOperation());
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
    private Decision grantConfers(String user, Permission permission) {
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
                Grant creating = GrantService.get().findCreatingGrant(user, itemFullName, itemRootDir);
                if (creating != null) {
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
            if (GrantService.get().hasActiveGrant(user, itemFullName, p)) {
                return Decision.CONFERS;
            }
        }
        return result;
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
        List<Grant> grants = GrantService.get().findActiveGrants(user, itemFullName, GrantAction.CREATE);
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
