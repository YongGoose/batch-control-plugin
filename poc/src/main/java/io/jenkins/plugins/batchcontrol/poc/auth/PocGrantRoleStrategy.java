package io.jenkins.plugins.batchcontrol.poc.auth;

import com.michelin.cio.hudson.plugins.rolestrategy.PermissionTemplate;
import com.michelin.cio.hudson.plugins.rolestrategy.RoleBasedAuthorizationStrategy;
import com.michelin.cio.hudson.plugins.rolestrategy.RoleMap;
import com.synopsys.arc.jenkins.plugins.rolestrategy.RoleType;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.AbstractItem;
import hudson.model.Descriptor;
import hudson.security.ACL;
import hudson.security.AuthorizationStrategy;
import io.jenkins.plugins.casc.Attribute;
import io.jenkins.plugins.casc.ConfigurationContext;
import io.jenkins.plugins.casc.Configurator;
import io.jenkins.plugins.casc.ConfiguratorException;
import io.jenkins.plugins.casc.model.CNode;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jenkinsci.Symbol;

/**
 * PoC-5 option A (role-strategy): RoleBasedAuthorizationStrategy is not final, so it can be
 * subclassed. The parent's getACL(Job) forwards to getACL(AbstractItem), so one override covers
 * both. Persistence reuses role-strategy's public ConverterImpl; JCasC reuses its configurator
 * looked up by type.
 */
public class PocGrantRoleStrategy extends RoleBasedAuthorizationStrategy {

    public PocGrantRoleStrategy(Map<String, RoleMap> grantedRoles) {
        super(grantedRoles);
    }

    public PocGrantRoleStrategy(Map<String, RoleMap> grantedRoles, Set<PermissionTemplate> templates) {
        super(grantedRoles, templates);
    }

    @NonNull
    @Override
    public ACL getACL(@NonNull AbstractItem item) {
        return new GrantLayerACL(super.getACL(item), item.getFullName());
    }

    /** Migration helper / copy: the same roles and templates, with the grant layer. */
    public static PocGrantRoleStrategy from(RoleBasedAuthorizationStrategy s) {
        Map<String, RoleMap> m = new HashMap<>();
        m.put(GLOBAL, new RoleMap(new TreeMap<>(s.getGrantedRolesEntries(RoleType.Global))));
        m.put(PROJECT, new RoleMap(new TreeMap<>(s.getGrantedRolesEntries(RoleType.Project))));
        m.put(SLAVE, new RoleMap(new TreeMap<>(s.getGrantedRolesEntries(RoleType.Slave))));
        return new PocGrantRoleStrategy(m, s.getPermissionTemplates());
    }

    /** The parent's DescriptorImpl is final, so the subclass needs a descriptor of its own. */
    @Extension
    @Symbol("batchControlRoleBased")
    public static final class DescriptorImpl extends Descriptor<AuthorizationStrategy> {
        public DescriptorImpl() {
            super(PocGrantRoleStrategy.class);
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "PoC-5 role-based with Batch Control grants";
        }
    }

    /** role-strategy's ConverterImpl is public; only canConvert and the returned type change. */
    public static final class ConverterImpl extends RoleBasedAuthorizationStrategy.ConverterImpl {
        @Override
        public boolean canConvert(Class type) {
            return type == PocGrantRoleStrategy.class;
        }

        @Override
        public Object unmarshal(HierarchicalStreamReader reader, UnmarshallingContext context) {
            return from((RoleBasedAuthorizationStrategy) super.unmarshal(reader, context));
        }
    }

    @Extension(optional = true)
    public static final class CascConfigurator implements Configurator<PocGrantRoleStrategy> {
        @NonNull @Override public String getName() { return "batchControlRoleBased"; }
        @Override public Class<PocGrantRoleStrategy> getTarget() { return PocGrantRoleStrategy.class; }
        @Override public Class getImplementedAPI() { return AuthorizationStrategy.class; }
        @NonNull @Override public Set<Attribute<PocGrantRoleStrategy, ?>> describe() { return Collections.emptySet(); }

        private static Configurator<RoleBasedAuthorizationStrategy> base(ConfigurationContext c) throws ConfiguratorException {
            return c.lookupOrFail(RoleBasedAuthorizationStrategy.class);
        }

        @NonNull
        @Override
        public PocGrantRoleStrategy configure(CNode node, ConfigurationContext c) throws ConfiguratorException {
            return from(base(c).configure(node, c));
        }

        @Override
        public PocGrantRoleStrategy check(CNode node, ConfigurationContext c) throws ConfiguratorException {
            return from(base(c).check(node, c));
        }

        @Override
        public CNode describe(PocGrantRoleStrategy instance, ConfigurationContext c) throws Exception {
            Map<String, RoleMap> m = new HashMap<>();
            m.put(GLOBAL, new RoleMap(new TreeMap<>(instance.getGrantedRolesEntries(RoleType.Global))));
            m.put(PROJECT, new RoleMap(new TreeMap<>(instance.getGrantedRolesEntries(RoleType.Project))));
            m.put(SLAVE, new RoleMap(new TreeMap<>(instance.getGrantedRolesEntries(RoleType.Slave))));
            return base(c).describe(new RoleBasedAuthorizationStrategy(m, instance.getPermissionTemplates()), c);
        }
    }
}
