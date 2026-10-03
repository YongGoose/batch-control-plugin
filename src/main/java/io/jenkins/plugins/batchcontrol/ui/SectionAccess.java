package io.jenkins.plugins.batchcontrol.ui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
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
     * {@code /batch-control/activations/**}: requesters, approvers and managers hold it on
     * Jenkins; since D-38c the section also opens for every other user admitted to the root
     * ({@link #canOpenActivations()}). An activation
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

    // ---------------------------------------------------------------- D-38b: own requests

    /**
     * D-38b: whether the current user may open the Run Requests screen: one of
     * {@link #requests()} on Jenkins, or run requests of their own to see (Request held only on
     * some jobs or folders). The own-requests test is the service's index lookup, never a scan of
     * all items.
     */
    public static boolean canOpenRequests() {
        return hasAny(requests())
                || RunRequestService.get().hasOwnRequests(Jenkins.getAuthentication2());
    }

    /**
     * D-38c (amends D-38b): whether the current user may open the Activations screen: every user
     * admitted to the Batch Control root page. The rows stay limited by the visibility rules
     * (possibly none), and requesting an activation is still checked on the job. No scan of all
     * items.
     */
    public static boolean canOpenActivations() {
        return canOpenRoot();
    }

    /** D-38b: whether the Batch Control root action exists for the current user. */
    public static boolean canOpenRoot() {
        return hasAny(anyPermission()) || canOpenRequests()
                || ActivationService.get().hasOwnRequests(Jenkins.getAuthentication2());
    }

    /**
     * The {@code l:layout} permissions of a screen whose gate admits a user through
     * {@code hasOwn} as well as through {@code permissions}: {@code permissions} for a holder of
     * one of them, and none (the gate has already admitted the user) for a user who is there
     * only for requests of their own, whom the Jenkins-level check would refuse.
     */
    public static Permission[] viewPermissions(Permission[] permissions, boolean admitted) {
        return admitted && !hasAny(permissions) ? new Permission[0] : permissions;
    }

    // ---------------------------------------------------------------- link predicates (Jelly)

    /** Whether the Run Requests screen (and its detail pages) may be linked for this user. */
    public boolean isRequests() {
        return canOpenRequests();
    }

    /** Whether the Activations screen (and its detail pages) may be linked for this user. */
    public boolean isActivations() {
        return canOpenActivations();
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

    /**
     * Whether the run request with this id may be linked (e2e-03 DEF-11): the Run Requests
     * screen is open to the user AND the request itself is visible to them (P-09,
     * {@link Visibility#canSeeRunRequest}); the detail page answers 404 otherwise. Rows of the
     * history screens are visible to every ViewHistory holder, so the id is then plain text.
     */
    public boolean request(@CheckForNull String requestId) {
        if (requestId == null || requestId.isEmpty() || !isRequests()) {
            return false;
        }
        RunRequest request;
        try {
            request = RunRequestService.get().load(requestId);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return request != null && Visibility.canSeeRunRequest(request);
    }

    /**
     * Whether the grant request (or the grant, which has the same id) with this id may be linked
     * (DEF-11): the Grants screen is open to the user AND the request is visible to them (P-09).
     */
    public boolean grant(@CheckForNull String grantRequestId) {
        if (grantRequestId == null || grantRequestId.isEmpty() || !isGrants()) {
            return false;
        }
        GrantRequest request;
        try {
            request = GrantRequestService.get().load(grantRequestId);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return request != null && Visibility.canSeeGrantRequest(request);
    }
}
