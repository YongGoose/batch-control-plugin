package io.jenkins.plugins.batchcontrol.ui;

import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * The permissions each Batch Control screen requires, in one place (SPEC item 2, hosting review
 * #31).
 *
 * <p>The same arrays feed three things, so they cannot drift apart:
 * <ul>
 *   <li>the section gates ({@code getTarget()} of each section), which answer 403;</li>
 *   <li>the {@code permissions} attribute of each screen's {@code l:layout};</li>
 *   <li>the link predicates of an instance ({@link #isRequests()} and friends), which decide
 *       whether a sidebar entry or an in-page link to a screen is rendered at all.</li>
 * </ul>
 *
 * <p>Every method returns a fresh array: callers (and Jelly) may not mutate the shared
 * definition.
 */
@Restricted(NoExternalUse.class)
public final class SectionAccess {

    /** Any Batch Control permission: without one of these the whole root action is absent. */
    public static Permission[] anyPermission() {
        return new Permission[] {
            BatchControlPermissions.REQUEST,
            BatchControlPermissions.APPROVE,
            BatchControlPermissions.REQUEST_GRANT,
            BatchControlPermissions.VIEW_HISTORY,
            BatchControlPermissions.MANAGE,
        };
    }

    /** {@code /batch-control/requests/**}: requesters, approvers and managers. */
    public static Permission[] requests() {
        return new Permission[] {
            BatchControlPermissions.REQUEST,
            BatchControlPermissions.APPROVE,
            BatchControlPermissions.MANAGE,
        };
    }

    /**
     * {@code /batch-control/activations/**}: requesters, approvers and managers. An activation
     * request needs {@code BatchControl/Request} (D-39) and is decided with
     * {@code BatchControl/Approve}, like a run request.
     */
    public static Permission[] activations() {
        return new Permission[] {
            BatchControlPermissions.REQUEST,
            BatchControlPermissions.APPROVE,
            BatchControlPermissions.MANAGE,
        };
    }

    /** {@code /batch-control/grants/**}: grant requesters, approvers and managers. */
    public static Permission[] grants() {
        return new Permission[] {
            BatchControlPermissions.REQUEST_GRANT,
            BatchControlPermissions.APPROVE,
            BatchControlPermissions.MANAGE,
        };
    }

    /** Changes, dashboard, incidents and history: {@code ViewHistory}. */
    public static Permission[] history() {
        return new Permission[] {BatchControlPermissions.VIEW_HISTORY};
    }

    /** Whether the current user holds at least one of {@code permissions} on Jenkins. */
    public static boolean hasAny(Permission[] permissions) {
        Jenkins jenkins = Jenkins.get();
        for (Permission permission : permissions) {
            if (jenkins.hasPermission(permission)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- link predicates (Jelly)

    /** Whether the Run Requests screen (and its detail pages) may be linked for this user. */
    public boolean isRequests() {
        return hasAny(requests());
    }

    /** Whether the Activations screen (and its detail pages) may be linked for this user. */
    public boolean isActivations() {
        return hasAny(activations());
    }

    /**
     * Whether the Grants screen may be linked: the permission gate, and change control on
     * (the subtree refuses every request while it is off, P-15).
     */
    public boolean isGrants() {
        return hasAny(grants()) && BatchControlGlobalConfiguration.get().isChangeControlEnabled();
    }

    /** Whether the ViewHistory screens (changes, dashboard, incidents, history) may be linked. */
    public boolean isHistory() {
        return hasAny(history());
    }
}
