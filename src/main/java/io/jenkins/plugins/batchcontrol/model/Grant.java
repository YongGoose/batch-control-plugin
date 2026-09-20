package io.jenkins.plugins.batchcontrol.model;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * An approved temporary permission (SPEC item 8, section 3): the result of an approved
 * {@link GrantRequest}. Persisted as XStream XML at {@code grants/<id>.xml}.
 *
 * <p>The grant id equals the id of the grant request it came from (they are 1:1), so change
 * records that carry a {@code grantId} resolve directly to the request detail screen.
 *
 * <p>Expiry is never timer-driven: activity is always judged by {@link #isActiveAt(Instant)}
 * at check time (SPEC item 8, D-08). Timestamps are persisted as epoch milliseconds because the
 * Jenkins XStream class filter does not allow {@code java.time.Instant}.
 *
 * <p>Only {@code security.GrantService} may set the revocation fields.
 */
@Restricted(NoExternalUse.class)
public final class Grant {

    private final String id;
    private final String grantRequestId;
    private final String user;
    private final GrantScope scope;
    private final List<GrantAction> actions;
    private final long grantedAtMillis;
    private final long expiresAtMillis;
    private Long revokedAtMillis;
    private String revokedBy;

    private Grant(String id, String grantRequestId, String user, GrantScope scope,
                  List<GrantAction> actions, Instant grantedAt, Instant expiresAt) {
        this.id = id;
        this.grantRequestId = grantRequestId;
        this.user = user;
        this.scope = scope;
        this.actions = new ArrayList<>(actions);
        this.grantedAtMillis = grantedAt.toEpochMilli();
        this.expiresAtMillis = expiresAt.toEpochMilli();
    }

    /**
     * Creates the grant for an approved request: the grantee is the requester, the window is
     * {@code [grantedAt, grantedAt + durationMinutes)}, and the id equals the request id.
     */
    public static Grant createFor(GrantRequest request, Instant grantedAt) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(grantedAt, "grantedAt");
        return new Grant(request.getId(), request.getId(), request.getRequester(),
                request.getScope(), request.getActions(), grantedAt,
                grantedAt.plus(Duration.ofMinutes(request.getDurationMinutes())));
    }

    public String getId() {
        return id;
    }

    public String getGrantRequestId() {
        return grantRequestId;
    }

    public String getUser() {
        return user;
    }

    public GrantScope getScope() {
        return scope;
    }

    /** A defensive copy; the granted actions never change after creation. */
    public List<GrantAction> getActions() {
        return actions == null ? new ArrayList<>() : new ArrayList<>(actions);
    }

    public Instant getGrantedAt() {
        return Instant.ofEpochMilli(grantedAtMillis);
    }

    public Instant getExpiresAt() {
        return Instant.ofEpochMilli(expiresAtMillis);
    }

    public Instant getRevokedAt() {
        return revokedAtMillis == null ? null : Instant.ofEpochMilli(revokedAtMillis);
    }

    public String getRevokedBy() {
        return revokedBy;
    }

    /**
     * Whether the grant confers its permissions at the given instant: not yet expired
     * (strictly before {@code expiresAt} — the first check at or past the expiry instant is
     * already denied) and not revoked.
     */
    public boolean isActiveAt(Instant at) {
        Objects.requireNonNull(at, "at");
        return revokedAtMillis == null
                && at.toEpochMilli() >= grantedAtMillis
                && at.toEpochMilli() < expiresAtMillis;
    }

    /** Only {@code security.GrantService} may revoke a grant (Manage holders, SPEC item 8). */
    public void markRevoked(Instant revokedAt, String revokedBy) {
        this.revokedAtMillis = Objects.requireNonNull(revokedAt, "revokedAt").toEpochMilli();
        this.revokedBy = revokedBy;
    }
}
