package io.jenkins.plugins.batchcontrol.ui;

import hudson.model.Item;
import hudson.model.Job;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.springframework.security.core.Authentication;

/**
 * D-38a: whether the <em>requester</em> of a run request (not the viewer) lacks {@code Item/Build}
 * on the request's job, for the request detail page.
 *
 * <p>Interim: the same predicate is needed by the approver notification ({@code ops}), so it
 * belongs in {@code RunRequestService}; once that method exists this class delegates to it
 * (requested from core-dev). Read only, never changes state.
 */
@Restricted(NoExternalUse.class)
public final class RequesterPermission {

    private static final Logger LOGGER = Logger.getLogger(RequesterPermission.class.getName());

    private RequesterPermission() {
    }

    /**
     * {@code true} when the requester is known not to hold {@code Item/Build} on the job, or when
     * that cannot be confirmed (the account no longer resolves); {@code false} when the requester
     * holds it or the job no longer exists.
     */
    public static boolean lacksBuild(RunRequest request) {
        // SYSTEM2 only to look the job up and to impersonate the requester: the viewer's own
        // permission to open the detail page was checked by the page (l:layout permissions)
        // before this runs, and the answer is about the requester, not the viewer.
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            Job<?, ?> job = Jenkins.get().getItemByFullName(request.getJobFullName(), Job.class);
            if (job == null) {
                return false;
            }
            Authentication requester;
            try {
                User user = User.getById(request.getRequester(), false);
                if (user == null) {
                    return true;
                }
                requester = user.impersonate2();
            } catch (RuntimeException e) {
                LOGGER.log(Level.FINE, "Cannot impersonate requester " + request.getRequester(), e);
                return true;
            }
            return !job.getACL().hasPermission2(requester, Item.BUILD);
        }
    }
}
