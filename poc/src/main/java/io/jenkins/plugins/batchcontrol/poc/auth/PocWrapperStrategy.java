package io.jenkins.plugins.batchcontrol.poc.auth;

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
import java.util.Collection;

/**
 * PoC-5 baseline: same shape as the current {@code BatchControlAuthorizationStrategy} (every
 * getACL overload goes to the delegate; items get the grant layer). Any wrapper that is not a
 * subclass of the delegate's type fails the same {@code instanceof} checks.
 */
public class PocWrapperStrategy extends AuthorizationStrategy {
    private final AuthorizationStrategy delegate;

    public PocWrapperStrategy(AuthorizationStrategy delegate) {
        this.delegate = delegate;
    }

    public AuthorizationStrategy getDelegate() {
        return delegate;
    }

    @NonNull @Override public ACL getRootACL() { return delegate.getRootACL(); }
    @NonNull @Override public ACL getACL(@NonNull AbstractItem i) { return new GrantLayerACL(delegate.getACL(i), i.getFullName()); }
    @NonNull @Override public ACL getACL(@NonNull Job<?, ?> j) { return new GrantLayerACL(delegate.getACL(j), j.getFullName()); }
    @NonNull @Override public ACL getACL(@NonNull View v) { return delegate.getACL(v); }
    @NonNull @Override public ACL getACL(@NonNull User u) { return delegate.getACL(u); }
    @NonNull @Override public ACL getACL(@NonNull Computer c) { return delegate.getACL(c); }
    @NonNull @Override public ACL getACL(@NonNull Cloud c) { return delegate.getACL(c); }
    @NonNull @Override public ACL getACL(@NonNull Node n) { return delegate.getACL(n); }
    @NonNull @Override public Collection<String> getGroups() { return delegate.getGroups(); }

    @Extension
    public static final class DescriptorImpl extends Descriptor<AuthorizationStrategy> {
        @NonNull @Override public String getDisplayName() { return "PoC-5 wrapper"; }
    }
}
