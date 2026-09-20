package io.jenkins.plugins.batchcontrol.security;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.AbstractItem;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Job;
import hudson.model.Node;
import hudson.model.User;
import hudson.model.View;
import hudson.security.ACL;
import hudson.security.AuthorizationStrategy;
import hudson.slaves.Cloud;
import io.jenkins.plugins.batchcontrol.Messages;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import jenkins.model.IComputer;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * The delegating authorization strategy that makes JIT change control work (SPEC item 8,
 * ARCHITECTURE section 4). It wraps the strategy the administrator actually uses (Matrix etc.):
 * every ACL is the delegate's ACL wrapped in a {@link GrantAwareACL}, so with no active grant
 * the instance behaves exactly like the delegate — installable and selectable regardless of the
 * change-control switch. Only Item/Create, Item/Configure and Item/Delete can ever be added by
 * a grant.
 *
 * <p><b>All</b> {@code getACL} overloads delegate (not just {@code getRootACL}): other
 * strategies override them individually, so missing one would silently fall back to the
 * wrapper's root ACL instead of the delegate's specific one (Phase 1 PoC finding).
 *
 * <p>A missing delegate (broken form submission, manual config edit) fails safe: every ACL
 * denies everything except SYSTEM.
 */
public class BatchControlAuthorizationStrategy extends AuthorizationStrategy {

    /** The wrapped strategy; its own configuration (matrix entries etc.) stays untouched. */
    @CheckForNull
    private final AuthorizationStrategy delegate;

    @DataBoundConstructor
    public BatchControlAuthorizationStrategy(@CheckForNull AuthorizationStrategy delegate) {
        this.delegate = delegate;
    }

    @CheckForNull
    public AuthorizationStrategy getDelegate() {
        return delegate;
    }

    @NonNull
    @Override
    public ACL getRootACL() {
        if (delegate == null) {
            return GrantAwareACL.denyAll();
        }
        // "" is the root item-group: a FOLDER-scope grant on "" covers root-level Item/Create.
        return new GrantAwareACL(delegate.getRootACL(), "");
    }

    @NonNull
    @Override
    public ACL getACL(@NonNull AbstractItem item) {
        if (delegate == null) {
            return GrantAwareACL.denyAll();
        }
        // Folders are AbstractItems: Item/Create inside a folder is checked on this ACL.
        return new GrantAwareACL(delegate.getACL(item), item.getFullName());
    }

    @NonNull
    @Override
    public ACL getACL(@NonNull Job<?, ?> project) {
        if (delegate == null) {
            return GrantAwareACL.denyAll();
        }
        return new GrantAwareACL(delegate.getACL(project), project.getFullName());
    }

    @NonNull
    @Override
    public ACL getACL(@NonNull View item) {
        return noScope(delegate == null ? null : delegate.getACL(item));
    }

    @NonNull
    @Override
    public ACL getACL(@NonNull User user) {
        return noScope(delegate == null ? null : delegate.getACL(user));
    }

    @NonNull
    @Override
    public ACL getACL(@NonNull Computer computer) {
        return noScope(delegate == null ? null : delegate.getACL(computer));
    }

    @NonNull
    @Override
    public ACL getACL(@NonNull IComputer computer) {
        // Without this override, core's default for a non-Computer IComputer would fall back
        // to THIS strategy's getRootACL() instead of the delegate's own IComputer override
        // (ARCHITECTURE section 4: ALL getACL overloads must delegate).
        return noScope(delegate == null ? null : delegate.getACL(computer));
    }

    @NonNull
    @Override
    public ACL getACL(@NonNull Cloud cloud) {
        return noScope(delegate == null ? null : delegate.getACL(cloud));
    }

    @NonNull
    @Override
    public ACL getACL(@NonNull Node node) {
        return noScope(delegate == null ? null : delegate.getACL(node));
    }

    @NonNull
    @Override
    public Collection<String> getGroups() {
        return delegate == null ? Collections.emptySet() : delegate.getGroups();
    }

    /** Grants never apply to non-item objects; wrap only for the null-delegate safe default. */
    @NonNull
    private static ACL noScope(@CheckForNull ACL delegateAcl) {
        return delegateAcl == null ? GrantAwareACL.denyAll() : new GrantAwareACL(delegateAcl, null);
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<AuthorizationStrategy> {

        @NonNull
        @Override
        public String getDisplayName() {
            return Messages.BatchControlAuthorizationStrategy_DisplayName();
        }

        /**
         * The strategies selectable as the delegate (for the config form's dropdown): every
         * registered authorization strategy except this wrapper itself, so the wrapper can
         * never be nested in itself.
         */
        public List<Descriptor<AuthorizationStrategy>> getDelegateDescriptors() {
            List<Descriptor<AuthorizationStrategy>> descriptors = new ArrayList<>();
            for (Descriptor<AuthorizationStrategy> descriptor : AuthorizationStrategy.all()) {
                if (!(descriptor instanceof DescriptorImpl)) {
                    descriptors.add(descriptor);
                }
            }
            return descriptors;
        }
    }
}
