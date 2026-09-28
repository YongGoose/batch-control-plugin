package io.jenkins.plugins.batchcontrol.security;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.security.AccessControlled;
import hudson.security.AuthorizationStrategy;
import hudson.security.Permission;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.springframework.security.core.Authentication;

/**
 * Entry points to the grant layer for code outside this package (D-35b, D-35c).
 */
@Restricted(NoExternalUse.class)
public final class GrantLayer {

    private GrantLayer() {
    }

    /** Whether {@code strategy} is a Batch Control strategy (layers grants over its parent). */
    public static boolean isGrantLayered(@CheckForNull AuthorizationStrategy strategy) {
        return strategy instanceof GrantLayeredStrategy;
    }

    /**
     * Whether {@code a} holds {@code permission} on {@code object} without any grant: the answer
     * of the installed strategy alone, with every grant layer switched off on this thread for the
     * duration of the check (inherited ACLs included).
     */
    public static boolean hasPermissionWithoutGrants(@NonNull AccessControlled object,
                                                     @NonNull Authentication a,
                                                     @NonNull Permission permission) {
        return GrantAwareACL.withoutGrants(() -> object.getACL().hasPermission2(a, permission));
    }
}
