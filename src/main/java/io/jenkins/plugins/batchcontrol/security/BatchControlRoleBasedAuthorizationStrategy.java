package io.jenkins.plugins.batchcontrol.security;

import com.michelin.cio.hudson.plugins.rolestrategy.PermissionTemplate;
import com.michelin.cio.hudson.plugins.rolestrategy.RoleBasedAuthorizationStrategy;
import com.michelin.cio.hudson.plugins.rolestrategy.RoleMap;
import com.synopsys.arc.jenkins.plugins.rolestrategy.RoleType;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.AbstractItem;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Job;
import hudson.model.Node;
import hudson.security.ACL;
import hudson.security.AuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.Messages;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.StaplerRequest2;

/**
 * role-strategy's role-based strategy with Batch Control grants layered on top (D-35a,
 * ARCHITECTURE section 4). Being a {@link RoleBasedAuthorizationStrategy}, it passes every
 * {@code instanceof} check role-strategy makes: Manage and Assign Roles, the REST API and pipeline
 * steps ({@code getInstance()}), pattern-based Item/Create and the role naming strategy keep
 * working, and the role maps decide underneath the grant layer.
 *
 * <p>The item overloads are wrapped in a {@link GrantAwareACL} with the item's full name; the agent
 * overloads with no grant scope (grants never apply to agents), which passes every decision
 * through. The root ACL is not wrapped: the parent declares it as a {@code SidACL}, and the root
 * carries no grant scope anyway (S-13). With no active grant, or while change control is off, the
 * strategy behaves exactly like its parent.
 *
 * <p>Known limitation (D-35a, PoC-5 row 6): role-strategy's Manage Roles save always installs a
 * plain {@link RoleBasedAuthorizationStrategy}. Open grants then stop conferring (fail-safe), and
 * {@code ops.BatchControlStrategyMonitor} offers to reinstall this class.
 *
 * <p>role-strategy is an optional dependency; the descriptor is an optional extension.
 */
public class BatchControlRoleBasedAuthorizationStrategy extends RoleBasedAuthorizationStrategy
        implements GrantLayeredStrategy {

    public BatchControlRoleBasedAuthorizationStrategy(Map<String, RoleMap> grantedRoles,
                                                      @CheckForNull Set<PermissionTemplate> permissionTemplates) {
        super(grantedRoles, permissionTemplates);
    }

    @NonNull
    @Override
    public ACL getACL(@NonNull Job<?, ?> project) {
        // The parent forwards Job to AbstractItem; forwarding to the wrapped overload keeps a
        // single grant layer.
        return getACL((AbstractItem) project);
    }

    @NonNull
    @Override
    public ACL getACL(@NonNull AbstractItem item) {
        return new GrantAwareACL(super.getACL(item), item.getFullName());
    }

    @NonNull
    @Override
    public ACL getACL(@NonNull Computer computer) {
        return new GrantAwareACL(super.getACL(computer), null);
    }

    @NonNull
    @Override
    public ACL getACL(@NonNull Node node) {
        return new GrantAwareACL(super.getACL(node), null);
    }

    @NonNull
    @Override
    public AuthorizationStrategy toPlainStrategy() {
        return new RoleBasedAuthorizationStrategy(roleMaps(this), getPermissionTemplates());
    }

    /** Fresh copies of the three role maps, keyed as the parent's constructor expects. */
    private static Map<String, RoleMap> roleMaps(RoleBasedAuthorizationStrategy s) {
        Map<String, RoleMap> maps = new HashMap<>();
        maps.put(GLOBAL, new RoleMap(new TreeMap<>(s.getGrantedRolesEntries(RoleType.Global))));
        maps.put(PROJECT, new RoleMap(new TreeMap<>(s.getGrantedRolesEntries(RoleType.Project))));
        maps.put(SLAVE, new RoleMap(new TreeMap<>(s.getGrantedRolesEntries(RoleType.Slave))));
        return maps;
    }

    /**
     * The Batch Control strategy with every role, assignment and permission template of a
     * role-strategy strategy (migration, the withdrawn wrapper's load conversion, the converter
     * and JCasC). Declared with a core parameter type so {@link StrategyMigration} can call it
     * without loading role-strategy classes first.
     */
    @NonNull
    static BatchControlRoleBasedAuthorizationStrategy copyOf(@NonNull AuthorizationStrategy existing) {
        RoleBasedAuthorizationStrategy s = (RoleBasedAuthorizationStrategy) existing;
        return new BatchControlRoleBasedAuthorizationStrategy(roleMaps(s), s.getPermissionTemplates());
    }

    /**
     * Listed on the security page. The parent's descriptor is final, so this one is separate.
     * Selecting it builds the strategy the way role-strategy does (keeping the installed roles, or
     * an admin role for the current user when coming from another strategy) and copies the
     * result into this class.
     */
    @Extension(optional = true)
    @Symbol("batchControlRoleBased")
    @Restricted(NoExternalUse.class)
    public static final class DescriptorImpl extends Descriptor<AuthorizationStrategy> {

        public DescriptorImpl() {
            super(BatchControlRoleBasedAuthorizationStrategy.class);
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return Messages.BatchControlRoleBasedAuthorizationStrategy_DisplayName();
        }

        @Override
        public AuthorizationStrategy newInstance(StaplerRequest2 req, @NonNull JSONObject formData)
                throws FormException {
            AuthorizationStrategy current = Jenkins.get().getAuthorizationStrategy();
            if (current instanceof BatchControlRoleBasedAuthorizationStrategy) {
                return current;
            }
            RoleBasedAuthorizationStrategy.DescriptorImpl parent =
                    Jenkins.get().getDescriptorByType(RoleBasedAuthorizationStrategy.DescriptorImpl.class);
            if (parent == null) {
                throw new FormException("role-strategy is not available", "authorizationStrategy");
            }
            return copyOf(parent.newInstance(req, formData));
        }
    }

    /** role-strategy's converter is public: the on-disk format is the same, only the type differs. */
    @Restricted(NoExternalUse.class)
    public static final class ConverterImpl extends RoleBasedAuthorizationStrategy.ConverterImpl {

        @Override
        public boolean canConvert(Class type) {
            return type == BatchControlRoleBasedAuthorizationStrategy.class;
        }

        @Override
        public Object unmarshal(HierarchicalStreamReader reader, UnmarshallingContext context) {
            return copyOf((RoleBasedAuthorizationStrategy) super.unmarshal(reader, context));
        }
    }
}
