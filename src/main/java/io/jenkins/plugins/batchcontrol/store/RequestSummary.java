package io.jenkins.plugins.batchcontrol.store;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import java.time.Instant;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * The few fields of a run request that listings filter and sort on (#13), kept in memory by the
 * store so a listing reads only the XML files of the rows it renders. Derived data: rebuilt from
 * {@code requests/run/*.xml} once per Jenkins session and updated on every save.
 *
 * @param id          request id (sortable by creation time)
 * @param jobFullName target job
 * @param requester   requesting user id
 * @param status      current status
 * @param createdAt   creation instant
 * @param decidedAt   decision instant, or {@code null} while undecided
 */
@Restricted(NoExternalUse.class)
public record RequestSummary(String id, String jobFullName, String requester, RequestStatus status,
                             Instant createdAt, @CheckForNull Instant decidedAt) {

    static RequestSummary of(RunRequest request) {
        return new RequestSummary(request.getId(), request.getJobFullName(), request.getRequester(),
                request.getStatus(), request.getCreatedAt(), request.getDecidedAt());
    }

    /** PENDING or APPROVED: the only statuses the expiry, recovery and invalidation work acts on. */
    public boolean isOpen() {
        return status == RequestStatus.PENDING || status == RequestStatus.APPROVED;
    }
}
