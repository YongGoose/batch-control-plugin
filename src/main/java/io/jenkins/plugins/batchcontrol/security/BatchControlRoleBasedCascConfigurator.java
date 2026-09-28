package io.jenkins.plugins.batchcontrol.security;

import com.michelin.cio.hudson.plugins.rolestrategy.RoleBasedAuthorizationStrategy;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.security.AuthorizationStrategy;
import io.jenkins.plugins.casc.Attribute;
import io.jenkins.plugins.casc.ConfigurationContext;
import io.jenkins.plugins.casc.Configurator;
import io.jenkins.plugins.casc.ConfiguratorException;
import io.jenkins.plugins.casc.model.CNode;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * JCasC support for {@link BatchControlRoleBasedAuthorizationStrategy} under the symbol
 * {@code batchControlRoleBased}, with the same schema as role-strategy's {@code roleBased}
 * (D-35a, PoC-5 row 8). role-strategy's configurator is looked up by type at runtime and the
 * result converted. Optional extension: without JCasC or role-strategy it is never loaded.
 */
@Extension(optional = true)
@Restricted(NoExternalUse.class)
public final class BatchControlRoleBasedCascConfigurator
        implements Configurator<BatchControlRoleBasedAuthorizationStrategy> {

    @NonNull
    @Override
    public String getName() {
        return "batchControlRoleBased";
    }

    @Override
    public Class<BatchControlRoleBasedAuthorizationStrategy> getTarget() {
        return BatchControlRoleBasedAuthorizationStrategy.class;
    }

    @Override
    public Class<?> getImplementedAPI() {
        return AuthorizationStrategy.class;
    }

    @NonNull
    @Override
    public List<String> getNames() {
        return Collections.singletonList(getName());
    }

    @NonNull
    @Override
    public Set<Attribute<BatchControlRoleBasedAuthorizationStrategy, ?>> describe() {
        return Collections.emptySet();
    }

    private static Configurator<RoleBasedAuthorizationStrategy> base(ConfigurationContext context)
            throws ConfiguratorException {
        return context.lookupOrFail(RoleBasedAuthorizationStrategy.class);
    }

    @NonNull
    @Override
    public BatchControlRoleBasedAuthorizationStrategy configure(CNode config, ConfigurationContext context)
            throws ConfiguratorException {
        return BatchControlRoleBasedAuthorizationStrategy.copyOf(base(context).configure(config, context));
    }

    @Override
    public BatchControlRoleBasedAuthorizationStrategy check(CNode config, ConfigurationContext context)
            throws ConfiguratorException {
        return BatchControlRoleBasedAuthorizationStrategy.copyOf(base(context).check(config, context));
    }

    @CheckForNull
    @Override
    public CNode describe(BatchControlRoleBasedAuthorizationStrategy instance, ConfigurationContext context)
            throws Exception {
        return base(context).describe((RoleBasedAuthorizationStrategy) instance.toPlainStrategy(), context);
    }
}
