package io.jenkins.plugins.batchcontrol.security;

import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.security.Permission;
import hudson.security.PermissionGroup;
import hudson.security.PermissionScope;
import io.jenkins.plugins.batchcontrol.Messages;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * The five plugin permissions (SPEC item 2), exposed to Matrix/Role authorization strategies as
 * the "Batch Control" group.
 *
 * <p>Implications: {@link #MANAGE} is implied by {@code Overall/Administer}; every other
 * permission is implied by {@link #MANAGE}, so administrators pass all checks.
 *
 * <p>Restricted (S-10): other plugins interact with these permissions through the
 * authorization-strategy screens, not by referencing this class.
 */
@Restricted(NoExternalUse.class)
public class BatchControlPermissions {

    /** The "Batch Control" permission group shown on the authorization matrix screen. */
    public static final PermissionGroup GROUP =
            new PermissionGroup(BatchControlPermissions.class, Messages._BatchControlPermissions_Title());

    /** Manage the plugin: global configuration, approver list, revoking grants. */
    public static final Permission MANAGE = new Permission(GROUP, "Manage",
            Messages._BatchControlPermissions_ManageDescription(), Jenkins.ADMINISTER, PermissionScope.JENKINS);

    /** Create run requests. */
    public static final Permission REQUEST = new Permission(GROUP, "Request",
            Messages._BatchControlPermissions_RequestDescription(), MANAGE, PermissionScope.JENKINS);

    /** Approve or reject requests. */
    public static final Permission APPROVE = new Permission(GROUP, "Approve",
            Messages._BatchControlPermissions_ApproveDescription(), MANAGE, PermissionScope.JENKINS);

    /** Request temporary change permissions (JIT). */
    public static final Permission REQUEST_GRANT = new Permission(GROUP, "RequestGrant",
            Messages._BatchControlPermissions_RequestGrantDescription(), MANAGE, PermissionScope.JENKINS);

    /** View history screens and CSV exports. */
    public static final Permission VIEW_HISTORY = new Permission(GROUP, "ViewHistory",
            Messages._BatchControlPermissions_ViewHistoryDescription(), MANAGE, PermissionScope.JENKINS);

    /**
     * Forces this class to initialize during Jenkins startup so the permission group is
     * registered before any authorization strategy screen enumerates permission groups.
     */
    @Initializer(after = InitMilestone.PLUGINS_STARTED)
    public static void initialize() {
        // Referencing the constants above through class initialization is all that is needed.
    }

    protected BatchControlPermissions() {
    }
}
