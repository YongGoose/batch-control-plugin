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
import hudson.security.Permission;
import hudson.security.PermissionGroup;
import hudson.util.FormValidation;
import io.jenkins.plugins.batchcontrol.Messages;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.accmod.restrictions.suppressions.SuppressRestrictedWarnings;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.interceptor.RequirePOST;

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
 * <p>D-35f, D-35g: role-strategy 927 or later is required (pinned in the pom). Since 918, the
 * Manage Roles, Assign Roles and permission template pages edit the installed strategy in place,
 * so every save keeps this class; the reset to a plain {@link RoleBasedAuthorizationStrategy} that
 * older versions' Manage Roles save performed (D-35a, PoC-5 row 6) no longer happens. Installing a
 * plain strategy on the global security page still stops grants from conferring (fail-safe), and
 * {@code ops.BatchControlStrategyMonitor} then offers to reinstall this class.
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
        return new GrantAwareACL(super.getACL(item), item);
    }

    @NonNull
    @Override
    public ACL getACL(@NonNull Computer computer) {
        return new GrantAwareACL(super.getACL(computer), (String) null);
    }

    @NonNull
    @Override
    public ACL getACL(@NonNull Node node) {
        return new GrantAwareACL(super.getACL(node), (String) null);
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
     * role-strategy strategy (migration, the converter and JCasC). Declared with a core parameter type so {@link RoleStrategies} can call it
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
     *
     * <p>e2e-03 DEF-20, D-35g: role-strategy's Manage Roles and Assign Roles pages address the
     * installed strategy's descriptor ({@code /descriptor/<class>/checkPattern} and
     * {@code /checkSidName}). Those endpoints and the parent descriptor's role-page members are
     * forwarded to role-strategy's own descriptor, so behaviour is identical to the plain
     * strategy. The parent restricts most of them; {@code access-modifier-suppressions} allows
     * the forwarding calls. The web methods keep {@code @RequirePOST} and an inline permission
     * check; the parent performs its own checks as well.
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

        /** The plain strategy's descriptor every role-page method delegates to. */
        @NonNull
        private static RoleBasedAuthorizationStrategy.DescriptorImpl parent() {
            RoleBasedAuthorizationStrategy.DescriptorImpl parent =
                    Jenkins.get().getDescriptorByType(RoleBasedAuthorizationStrategy.DescriptorImpl.class);
            return parent != null ? parent : RoleBasedAuthorizationStrategy.DESCRIPTOR;
        }

        /*
         * Jenkins Security Scan alerts 31-36: the role-page checks below serve role-strategy's
         * Manage Roles and Assign Roles pages, so they need what role-strategy requires to open those
         * pages (Overall/SystemRead or one of its role-administration permissions; Overall/Administer
         * implies them). The check is inline in each method so the scanner can see it; the parent's
         * method then applies its own checks.
         */

        /**
         * Assign Roles (role-strategy 927 {@code index.jelly}): resolves the sid typed in the add
         * dialog as a user or group ({@code type} {@code USER} or {@code GROUP}).
         */
        // The check is the inline checkAnyPermission below; the scanner rule has a known bug and misses it.
        @SuppressWarnings("lgtm[jenkins/no-permission-check]")
        @SuppressRestrictedWarnings(RoleBasedAuthorizationStrategy.DescriptorImpl.class)
        @RequirePOST
        public FormValidation doCheckSidName(@QueryParameter String value, @QueryParameter String type) {
            Jenkins.get().checkAnyPermission(Jenkins.SYSTEM_READ, RoleBasedAuthorizationStrategy.ITEM_ROLES_ADMIN,
                    RoleBasedAuthorizationStrategy.AGENT_ROLES_ADMIN);
            return parent().doCheckSidName(value, type);
        }

        /** Manage Roles (role-strategy 927 {@code manage-roles.jelly}): validates a role pattern. */
        // The check is the inline checkAnyPermission below; the scanner rule has a known bug and misses it.
        @SuppressWarnings("lgtm[jenkins/no-permission-check]")
        @SuppressRestrictedWarnings(RoleBasedAuthorizationStrategy.DescriptorImpl.class)
        @RequirePOST
        public FormValidation doCheckPattern(@QueryParameter String value) {
            Jenkins.get().checkAnyPermission(Jenkins.SYSTEM_READ, RoleBasedAuthorizationStrategy.ITEM_ROLES_ADMIN,
                    RoleBasedAuthorizationStrategy.AGENT_ROLES_ADMIN);
            return parent().doCheckPattern(value);
        }

        /** The permission groups shown for a role type. */
        public List<PermissionGroup> getGroups(@NonNull String type) {
            return parent().getGroups(type);
        }

        /** Whether a permission is shown for a role type. */
        @SuppressRestrictedWarnings(RoleBasedAuthorizationStrategy.DescriptorImpl.class)
        public boolean showPermission(String type, Permission p) {
            return parent().showPermission(type, p);
        }

        /** The space-separated ids of the permissions implying {@code p}. */
        @SuppressRestrictedWarnings(RoleBasedAuthorizationStrategy.DescriptorImpl.class)
        public String impliedByList(Permission p) {
            return parent().impliedByList(p);
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
