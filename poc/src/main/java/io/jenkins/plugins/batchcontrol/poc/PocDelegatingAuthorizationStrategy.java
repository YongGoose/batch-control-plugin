package io.jenkins.plugins.batchcontrol.poc;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.AbstractItem;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.model.Job;
import hudson.security.ACL;
import hudson.security.AuthorizationStrategy;
import hudson.security.Permission;
import java.util.Collection;
import org.springframework.security.core.Authentication;

/**
 * PoC delegating authorization strategy for design assumption C.
 *
 * <p>Wraps an arbitrary {@link AuthorizationStrategy} (Matrix in the PoC tests) and layers
 * temporary Item/CREATE, Item/CONFIGURE, Item/DELETE grants from {@link PocGrantStore} on top.
 * With no active grants it behaves exactly like the delegate.
 */
public class PocDelegatingAuthorizationStrategy extends AuthorizationStrategy {

    private final AuthorizationStrategy delegate;

    public PocDelegatingAuthorizationStrategy(AuthorizationStrategy delegate) {
        this.delegate = delegate;
    }

    public AuthorizationStrategy getDelegate() {
        return delegate;
    }

    @NonNull
    @Override
    public ACL getRootACL() {
        return new GrantAwareACL(delegate.getRootACL(), null);
    }

    @NonNull
    @Override
    public ACL getACL(@NonNull AbstractItem item) {
        return new GrantAwareACL(delegate.getACL(item), item.getFullName());
    }

    @NonNull
    @Override
    public ACL getACL(@NonNull Job<?, ?> project) {
        return getACL((AbstractItem) project);
    }

    @NonNull
    @Override
    public Collection<String> getGroups() {
        return delegate.getGroups();
    }

    private static final class GrantAwareACL extends ACL {
        private final ACL delegateAcl;
        private final String itemFullName; // null for root

        GrantAwareACL(ACL delegateAcl, String itemFullName) {
            this.delegateAcl = delegateAcl;
            this.itemFullName = itemFullName;
        }

        @Override
        public boolean hasPermission2(@NonNull Authentication a, @NonNull Permission permission) {
            if (itemFullName != null && isGrantable(permission)
                    && PocGrantStore.hasActiveGrant(a.getName(), itemFullName, permission)) {
                return true;
            }
            return delegateAcl.hasPermission2(a, permission);
        }

        private static boolean isGrantable(Permission p) {
            return p == Item.CREATE || p == Item.CONFIGURE || p == Item.DELETE;
        }
    }

    /** Minimal descriptor so the strategy is a well-formed Describable inside JenkinsRule. */
    @Extension
    public static final class DescriptorImpl extends Descriptor<AuthorizationStrategy> {
        @NonNull
        @Override
        public String getDisplayName() {
            return "Batch Control PoC (delegating)";
        }
    }
}
