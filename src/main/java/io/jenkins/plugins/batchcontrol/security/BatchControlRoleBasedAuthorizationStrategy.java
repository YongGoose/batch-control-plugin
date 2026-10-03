package io.jenkins.plugins.batchcontrol.security;

import com.michelin.cio.hudson.plugins.rolestrategy.AuthorizationType;
import com.michelin.cio.hudson.plugins.rolestrategy.PermissionEntry;
import com.michelin.cio.hudson.plugins.rolestrategy.PermissionTemplate;
import com.michelin.cio.hudson.plugins.rolestrategy.Role;
import com.michelin.cio.hudson.plugins.rolestrategy.RoleBasedAuthorizationStrategy;
import com.michelin.cio.hudson.plugins.rolestrategy.RoleMap;
import com.synopsys.arc.jenkins.plugins.rolestrategy.RoleType;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.PluginManager;
import hudson.model.AbstractItem;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Job;
import hudson.model.Node;
import hudson.security.ACL;
import hudson.security.AuthorizationStrategy;
import hudson.security.Permission;
import hudson.security.PermissionGroup;
import hudson.security.PermissionScope;
import hudson.util.FormValidation;
import io.jenkins.plugins.batchcontrol.Messages;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
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
 * <p>D-35f: role-strategy 918 or later is required (pinned in the pom). From that version on, the
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
     * <p>e2e-03 DEF-20: role-strategy's Manage Roles, Assign Roles and permission template pages
     * address the installed strategy's descriptor ({@code it.strategy.descriptor} in Jelly and
     * {@code /descriptor/<class>/check*} from JavaScript). Every such method of the parent's
     * descriptor is therefore exposed here, so the pages behave exactly as under the plain
     * strategy. Methods role-strategy restricts, or removes in its UI rework (PR #766:
     * {@code checkName} replaced by {@code checkSidName}), are re-implemented
     * ({@link RoleSidChecks}) instead of delegated. The web methods keep the parent's {@code @RequirePOST}; they
     * only validate or render and the parent performs its own permission checks.
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
         * implies them). The check is inline in each method so the scanner can see it.
         */
        /** Assign Roles: renders each user or group row's name (role-strategy tableAssign.js). */
        // The check is the inline checkAnyPermission below; the scanner rule has a known bug and misses it.
        @SuppressWarnings("lgtm[jenkins/no-permission-check]")
        @RequirePOST
        public FormValidation doCheckName(@QueryParameter String value) {
            Jenkins.get().checkAnyPermission(Jenkins.SYSTEM_READ, RoleBasedAuthorizationStrategy.ITEM_ROLES_ADMIN,
                    RoleBasedAuthorizationStrategy.AGENT_ROLES_ADMIN);
            // Re-implemented (RoleSidChecks): role-strategy PR #766 removes the parent's method.
            return RoleSidChecks.checkName(value);
        }

        /**
         * Assign Roles (redesigned page, role-strategy PR #766): resolves the sid typed in the add
         * dialog as a user or group ({@code type} {@code USER} or {@code GROUP}). Re-implemented
         * because the method does not exist in the parent's descriptor of role-strategy 918.
         */
        // The check is the inline checkAnyPermission below; the scanner rule has a known bug and misses it.
        @SuppressWarnings("lgtm[jenkins/no-permission-check]")
        @RequirePOST
        public FormValidation doCheckSidName(@QueryParameter String value, @QueryParameter String type) {
            Jenkins.get().checkAnyPermission(Jenkins.SYSTEM_READ, RoleBasedAuthorizationStrategy.ITEM_ROLES_ADMIN,
                    RoleBasedAuthorizationStrategy.AGENT_ROLES_ADMIN);
            return RoleSidChecks.checkSidName(value, type);
        }

        /**
         * Manage Roles: validates an item or agent role pattern. Re-implemented rather than
         * delegated because the parent's method is restricted to role-strategy itself.
         */
        // The check is the inline checkAnyPermission below; the scanner rule has a known bug and misses it.
        @SuppressWarnings("lgtm[jenkins/no-permission-check]")
        @RequirePOST
        public FormValidation doCheckPattern(@QueryParameter String value) {
            Jenkins.get().checkAnyPermission(Jenkins.SYSTEM_READ, RoleBasedAuthorizationStrategy.ITEM_ROLES_ADMIN,
                    RoleBasedAuthorizationStrategy.AGENT_ROLES_ADMIN);
            try {
                Pattern.compile(value == null ? "" : value);
            } catch (PatternSyntaxException e) {
                return FormValidation.error(e.getMessage());
            }
            return FormValidation.ok();
        }

        /** Role and template names: warns about leading or trailing whitespace. */
        // The check is the inline checkAnyPermission below; the scanner rule has a known bug and misses it.
        @SuppressWarnings("lgtm[jenkins/no-permission-check]")
        @RequirePOST
        public FormValidation doCheckForWhitespace(@QueryParameter String value) {
            Jenkins.get().checkAnyPermission(Jenkins.SYSTEM_READ, RoleBasedAuthorizationStrategy.ITEM_ROLES_ADMIN,
                    RoleBasedAuthorizationStrategy.AGENT_ROLES_ADMIN);
            return parent().doCheckForWhitespace(value);
        }

        /** Jelly: the permission groups shown for a role type. */
        public List<PermissionGroup> getGroups(@NonNull String type) {
            return parent().getGroups(type);
        }

        /**
         * Jelly: whether a permission is shown for a role type (the parent's rule: no dangerous
         * permission among the global ones, the scope must fit item and agent roles).
         */
        @SuppressWarnings("deprecation") // Jenkins.RUN_SCRIPTS is one of the suppressed permissions
        public boolean showPermission(String type, Permission p) {
            if (p == null || type == null) {
                return false;
            }
            switch (type) {
                case GLOBAL:
                    return !(p == Jenkins.RUN_SCRIPTS || p == PluginManager.CONFIGURE_UPDATECENTER
                            || p == PluginManager.UPLOAD_PLUGINS) && p.getEnabled();
                case PROJECT:
                    return p.isContainedBy(PermissionScope.ITEM_GROUP) && p.getEnabled();
                case SLAVE:
                    return p.isContainedBy(PermissionScope.COMPUTER) && p.getEnabled();
                default:
                    return false;
            }
        }

        /** Jelly: the space-separated ids of the permissions implying {@code p}. */
        public String impliedByList(Permission p) {
            List<String> ids = new ArrayList<>();
            for (Permission q = p == null ? null : p.impliedBy; q != null; q = q.impliedBy) {
                ids.add(q.getId());
            }
            return String.join(" ", ids);
        }

        /** Jelly: the entry of an assignment row; {@code null} for the template row. */
        @CheckForNull
        public PermissionEntry entryFor(String type, String sid) {
            return type == null ? null : new PermissionEntry(AuthorizationType.valueOf(type), sid);
        }

        /** Jelly: whether an assignment table holds ambiguous (user-or-group) entries. */
        public boolean hasAmbiguousEntries(SortedMap<Role, Set<PermissionEntry>> grantedRoles) {
            if (grantedRoles == null) {
                return false;
            }
            for (Set<PermissionEntry> entries : grantedRoles.values()) {
                for (PermissionEntry entry : entries) {
                    if (entry.getType() == AuthorizationType.EITHER) {
                        return true;
                    }
                }
            }
            return false;
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
