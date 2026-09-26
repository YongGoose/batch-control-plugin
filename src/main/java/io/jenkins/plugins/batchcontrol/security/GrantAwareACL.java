package io.jenkins.plugins.batchcontrol.security;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.security.ACL;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.springframework.security.core.Authentication;

/**
 * The ACL of the delegating strategy (ARCHITECTURE section 4). For the three grantable item
 * permissions (Item/Create, Item/Configure, Item/Delete) an active JIT grant is consulted
 * first; every other decision — and every miss — goes to the wrapped delegate ACL unchanged,
 * so with no active grant the behavior is exactly the delegate's.
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
 * <p>A {@code null} delegate ACL denies everything except SYSTEM (safe default while the
 * wrapper is misconfigured without a delegate strategy).
 */
@Restricted(NoExternalUse.class)
final class GrantAwareACL extends ACL {

    private static final GrantAwareACL DENY_ALL = new GrantAwareACL(null, null);

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

    GrantAwareACL(@CheckForNull ACL delegate, @CheckForNull String itemFullName) {
        this.delegate = delegate;
        this.itemFullName = itemFullName;
    }

    /** The deny-all-but-SYSTEM ACL used when the wrapper has no delegate. */
    static GrantAwareACL denyAll() {
        return DENY_ALL;
    }

    @Override
    public boolean hasPermission2(@NonNull Authentication a, @NonNull Permission permission) {
        if (a.equals(SYSTEM2)) {
            return true;
        }
        if (itemFullName != null && !ACL.isAnonymous2(a) && grantConfers(a.getName(), permission)) {
            return true;
        }
        return delegate != null && delegate.hasPermission2(a, permission);
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
    private boolean grantConfers(String user, Permission permission) {
        for (Permission p = permission; p != null; p = p.impliedBy) {
            if (!p.getEnabled()) {
                continue;
            }
            if (GrantAction.fromPermission(p) == null) {
                continue;
            }
            if (GrantService.get().hasActiveGrant(user, itemFullName, p)) {
                return true;
            }
        }
        return false;
    }
}
