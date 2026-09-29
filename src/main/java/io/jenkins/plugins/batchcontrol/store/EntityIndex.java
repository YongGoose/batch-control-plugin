package io.jenkins.plugins.batchcontrol.store;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import io.jenkins.plugins.batchcontrol.model.ActivationRequest;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * In-memory index of the XML entities (#13): which run and grant requests are still open, what
 * listings filter on, and when each request or grant was last active (for retention). Derived
 * data, never persisted: {@link FileStore} rebuilds it from the entity files once per Jenkins
 * session and updates it on every save and deletion, so the per-minute expiry work loads only
 * open requests and retention decides without parsing every file.
 */
@Restricted(NoExternalUse.class)
final class EntityIndex {

    /** A run request's listing fields plus its last activity instant. */
    record RunEntry(RequestSummary summary, Instant lastActivity) {
    }

    /** A grant request's status and last activity instant. */
    record GrantRequestEntry(String id, RequestStatus status, Instant lastActivity) {
    }

    /** A grant, the grant request it came from, and the instant it stopped conferring anything. */
    record GrantEntry(String id, String requestId, Instant endedAt) {
    }

    /** The store root this index was built from; a different root means a different session. */
    final Path root;
    final Map<String, RunEntry> runRequests = new ConcurrentHashMap<>();
    final Map<String, GrantRequestEntry> grantRequests = new ConcurrentHashMap<>();
    final Map<String, GrantEntry> grants = new ConcurrentHashMap<>();
    /** Activation requests (#15) share the grant request entry shape: status and last activity. */
    final Map<String, GrantRequestEntry> activationRequests = new ConcurrentHashMap<>();

    EntityIndex(Path root) {
        this.root = root;
    }

    void put(RunRequest request) {
        runRequests.put(request.getId(), new RunEntry(RequestSummary.of(request), lastActivity(request)));
    }

    void put(GrantRequest request) {
        grantRequests.put(request.getId(),
                new GrantRequestEntry(request.getId(), request.getStatus(), lastActivity(request)));
    }

    void put(ActivationRequest request) {
        activationRequests.put(request.getId(),
                new GrantRequestEntry(request.getId(), request.getStatus(), lastActivity(request)));
    }

    void put(Grant grant) {
        Instant ended = grant.getExpiresAt();
        Instant revoked = grant.getRevokedAt();
        if (revoked != null && revoked.isBefore(ended)) {
            ended = revoked;
        }
        // Grants created before grantRequestId existed share the request's id (GrantRequestService).
        String requestId = grant.getGrantRequestId() != null ? grant.getGrantRequestId() : grant.getId();
        grants.put(grant.getId(), new GrantEntry(grant.getId(), requestId, ended));
    }

    static boolean isOpen(GrantRequestEntry entry) {
        return entry.status() == RequestStatus.PENDING;
    }

    private static Instant lastActivity(RunRequest request) {
        Instant last = request.getCreatedAt();
        last = later(last, request.getDecidedAt());
        last = later(last, request.getQueuedAt());
        last = later(last, request.getExpiryBase());
        last = later(last, request.getExecutedAt());
        for (RunRequest.ApproverChange change : request.getApproverChanges()) {
            last = later(last, change.getAt());
        }
        return last;
    }

    private static Instant lastActivity(GrantRequest request) {
        Instant last = request.getCreatedAt();
        last = later(last, request.getDecidedAt());
        for (GrantRequest.ApproverChange change : request.getApproverChanges()) {
            last = later(last, change.getAt());
        }
        return last;
    }

    private static Instant lastActivity(ActivationRequest request) {
        Instant last = request.getCreatedAt();
        last = later(last, request.getDecidedAt());
        for (ActivationRequest.ApproverChange change : request.getApproverChanges()) {
            last = later(last, change.getAt());
        }
        return last;
    }

    private static Instant later(Instant a, @CheckForNull Instant b) {
        if (a == null) {
            return b;
        }
        return b != null && b.isAfter(a) ? b : a;
    }
}
