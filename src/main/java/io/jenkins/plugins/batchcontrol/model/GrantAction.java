package io.jenkins.plugins.batchcontrol.model;

import hudson.model.Item;
import hudson.security.Permission;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * The change actions a JIT grant can cover (SPEC item 8). Each action maps 1:1 to the Jenkins
 * item permission it temporarily confers; no other permission is ever grantable.
 */
@Restricted(NoExternalUse.class)
public enum GrantAction {
    CREATE,
    CONFIGURE,
    DELETE;

    /** The Jenkins permission this action confers while a grant is active. */
    public Permission toPermission() {
        switch (this) {
            case CREATE:
                return Item.CREATE;
            case CONFIGURE:
                return Item.CONFIGURE;
            case DELETE:
                return Item.DELETE;
            default:
                throw new AssertionError("Unknown grant action: " + this);
        }
    }

    /**
     * The action corresponding to a Jenkins permission, or {@code null} when the permission is
     * not one of the three grantable item permissions (the grant machinery must never touch any
     * other permission).
     */
    public static GrantAction fromPermission(Permission permission) {
        if (permission == Item.CREATE) {
            return CREATE;
        }
        if (permission == Item.CONFIGURE) {
            return CONFIGURE;
        }
        if (permission == Item.DELETE) {
            return DELETE;
        }
        return null;
    }
}
