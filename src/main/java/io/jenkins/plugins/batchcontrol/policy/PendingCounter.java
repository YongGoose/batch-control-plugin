package io.jenkins.plugins.batchcontrol.policy;

import hudson.security.ACL;
import io.jenkins.plugins.batchcontrol.model.Approvers;
import io.jenkins.plugins.batchcontrol.model.PendingCount;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.function.BiPredicate;
import java.util.function.Function;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.springframework.security.core.Authentication;

/**
 * The single D-61 counting rule behind the {@code countPendingFor(Authentication)} methods of
 * {@link RunRequestService}, {@link GrantRequestService} and {@link ActivationService}.
 * Callers pass the open-request index of the store only, never the history.
 *
 * <p>A PENDING request counts as awaiting the viewer's decision when the viewer holds
 * Jenkins-level {@code BatchControl/Approve} and is a designated approver; otherwise it counts as
 * the viewer's own when they are its requester. Both sets are visible to the viewer by the P-09
 * rules (requester or designated approver), so a count never includes a request the viewer cannot
 * open. Anonymous viewers count nothing.
 */
@Restricted(NoExternalUse.class)
final class PendingCounter {

    private PendingCounter() {
    }

    /** Whether {@code auth} holds Jenkins-level {@code BatchControl/Approve}. */
    static boolean isApprover(Authentication auth) {
        return Jenkins.get().getACL().hasPermission2(auth, BatchControlPermissions.APPROVE);
    }

    static boolean isCountable(Authentication auth) {
        return auth != null && !ACL.isAnonymous2(auth);
    }

    static <T> PendingCount count(
            Iterable<T> openRequests,
            Authentication auth,
            Function<T, RequestStatus> status,
            BiPredicate<T, String> designatedApprover,
            Function<T, String> requester) {
        if (!isCountable(auth)) {
            return PendingCount.NONE;
        }
        String me = auth.getName();
        boolean approver = isApprover(auth);
        int decide = 0;
        int mine = 0;
        for (T request : openRequests) {
            if (status.apply(request) != RequestStatus.PENDING) {
                continue; // the open indexes also hold APPROVED requests (a run waiting to start)
            }
            if (approver && designatedApprover.test(request, me)) {
                decide++;
            } else if (Approvers.sameUser(me, requester.apply(request))) {
                mine++;
            }
        }
        return decide == 0 && mine == 0 ? PendingCount.NONE : new PendingCount(decide, mine);
    }
}
