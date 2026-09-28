package io.jenkins.plugins.batchcontrol.security;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.security.AuthorizationStrategy;
import hudson.security.ProjectMatrixAuthorizationStrategy;
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
 * JCasC support for {@link BatchControlMatrixAuthorizationStrategy} under the symbol
 * {@code batchControlProjectMatrix}, with the same schema as matrix-auth's
 * {@code projectMatrix} (D-35a, PoC-5 row 8).
 *
 * <p>matrix-auth's configurator matches its exact class and is restricted, so this one looks it up
 * by type at runtime and converts to and from a plain {@link ProjectMatrixAuthorizationStrategy}.
 * Optional extension: without JCasC or matrix-auth it is never loaded.
 */
@Extension(optional = true)
@Restricted(NoExternalUse.class)
public final class BatchControlMatrixCascConfigurator
        implements Configurator<BatchControlMatrixAuthorizationStrategy> {

    @NonNull
    @Override
    public String getName() {
        return "batchControlProjectMatrix";
    }

    @Override
    public Class<BatchControlMatrixAuthorizationStrategy> getTarget() {
        return BatchControlMatrixAuthorizationStrategy.class;
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
    public Set<Attribute<BatchControlMatrixAuthorizationStrategy, ?>> describe() {
        return Collections.emptySet();
    }

    private static Configurator<ProjectMatrixAuthorizationStrategy> base(ConfigurationContext context)
            throws ConfiguratorException {
        return context.lookupOrFail(ProjectMatrixAuthorizationStrategy.class);
    }

    @NonNull
    @Override
    public BatchControlMatrixAuthorizationStrategy configure(CNode config, ConfigurationContext context)
            throws ConfiguratorException {
        return BatchControlMatrixAuthorizationStrategy.copyOf(base(context).configure(config, context));
    }

    @Override
    public BatchControlMatrixAuthorizationStrategy check(CNode config, ConfigurationContext context)
            throws ConfiguratorException {
        return BatchControlMatrixAuthorizationStrategy.copyOf(base(context).check(config, context));
    }

    @CheckForNull
    @Override
    public CNode describe(BatchControlMatrixAuthorizationStrategy instance, ConfigurationContext context)
            throws Exception {
        return base(context).describe((ProjectMatrixAuthorizationStrategy) instance.toPlainStrategy(), context);
    }
}
