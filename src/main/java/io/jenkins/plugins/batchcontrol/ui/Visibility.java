package io.jenkins.plugins.batchcontrol.ui;

import hudson.model.Item;
import hudson.model.Job;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Per-object visibility predicates for the request and grant screens (P-09, security review
 * S-01). The section gates stay coarse; these predicates decide which rows a caller may see,
 * and the SAME predicate gates the detail URL, so list and detail can never diverge. A
 * non-visible object renders exactly like a nonexistent one (404) to hide its existence.
 *
 * <p>Read-only: only public model getters, {@code Jenkins.getItemByFullName} and
 * {@code hasPermission} are used. State-changing endpoints keep their own permission checks —
 * nothing here replaces them.
 */
@Restricted(NoExternalUse.class)
public final class Visibility {

    private Visibility() {
    }

    /**
     * A run request is visible iff the caller has Manage (or Overall/Administer), OR is the
     * requester, OR is the designated approver, OR holds Item/Read on the target job. When the
     * job no longer exists (or is invisible to the caller — {@code getItemByFullName} is
     * permission-aware), only requester/approver/Manage still see the request.
     */
    public static boolean canSeeRunRequest(RunRequest request) {
        if (isManager()) {
            return true;
        }
        String me = Jenkins.getAuthentication2().getName();
        if (me.equals(request.getRequester()) || me.equals(request.getApprover())) {
            return true;
        }
        Job<?, ?> job = Jenkins.get().getItemByFullName(request.getJobFullName(), Job.class);
        return job != null && job.hasPermission(Item.READ);
    }

    /** A grant request is visible iff the caller has Manage, or is requester or approver. */
    public static boolean canSeeGrantRequest(GrantRequest request) {
        if (isManager()) {
            return true;
        }
        String me = Jenkins.getAuthentication2().getName();
        return me.equals(request.getRequester()) || me.equals(request.getApprover());
    }

    /** An active grant is visible iff the caller has Manage, or the grant is their own. */
    public static boolean canSeeGrant(Grant grant) {
        return isManager() || Jenkins.getAuthentication2().getName().equals(grant.getUser());
    }

    /** Manage (Overall/Administer implies it, but check both to match P-09 verbatim). */
    private static boolean isManager() {
        Jenkins jenkins = Jenkins.get();
        return jenkins.hasPermission(BatchControlPermissions.MANAGE)
                || jenkins.hasPermission(Jenkins.ADMINISTER);
    }
}
