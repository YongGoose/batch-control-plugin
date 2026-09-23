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
        if (itemFullName != null
                && !ACL.isAnonymous2(a)
                && GrantAction.fromPermission(permission) != null
                && GrantService.get().hasActiveGrant(a.getName(), itemFullName, permission)) {
            return true;
        }
        return delegate != null && delegate.hasPermission2(a, permission);
    }
}
