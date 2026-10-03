package io.jenkins.plugins.batchcontrol.model;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
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

    /** #85 (P-15): the revocation reason of the windows closed by turning change control off. */
    public static final String REVOKED_CHANGE_CONTROL_OFF = "change control turned off";

    private final String id;
    private final String grantRequestId;
    private final String user;
    private final GrantScope scope;
    private final List<GrantAction> actions;
    private final long grantedAtMillis;
    private final long expiresAtMillis;
    private Long revokedAtMillis;
    private String revokedBy;
    /**
     * #85: why the grant was revoked when it was not an individual revocation, for example
     * {@link #REVOKED_CHANGE_CONTROL_OFF}; {@code null} for an individual revocation by a Manage
     * holder and in grant files written before #85 (an optional field, no new file format).
     */
    private String revokedReason;
    /**
     * D-35c: full names of the items this grant's holder created inside the scope during the
     * window, using the grant's Create. While the grant is active the holder also holds Item/Read
     * and Item/Configure on them, so matrix-auth's creator listener adds no permanent entry.
     * {@code null} in grant files written before D-35c (XStream skips the initializer).
     */
    private List<String> createdItems;
    /**
     * S-09: the identity of each created item (full name to an opaque marker of its directory on
     * disk, see {@code security.ItemIdentity}), so an item deleted and recreated under the same
     * name by someone else is not taken for the created one. An item without an entry (grant files
     * written before S-09, or no marker could be read) is matched by name alone.
     */
    private Map<String, String> createdItemIdentities;
    /**
     * D-40: the CREATE name restriction copied from the request ({@code null}: any name). Copied so
     * the permission-check hot path never has to load the request.
     */
    private String createNamePattern;
    /**
     * D-58a: full names of the items whose configuration was changed under this grant (saved or
     * created by its holder while the holder's permission came only from the grant). Such an item
     * stays guarded, also after the grant ended, until an administrator or a native Configure
     * holder saves it through the web (the review). {@code null} in grant files written before
     * D-58a and when empty (XStream skips the initializer).
     */
    private List<String> changedItems;
    /** D-36: the GRANT_EXPIRING notification was sent (persisted so a restart does not resend). */
    private boolean expiringNotified;
    /** Compiled {@link #createNamePattern}, built lazily. */
    private transient volatile CreateNamePattern compiledPattern;

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
        Grant grant = new Grant(request.getId(), request.getId(), request.getRequester(),
                request.getScope(), request.getActions(), grantedAt,
                grantedAt.plus(Duration.ofMinutes(request.getDurationMinutes())));
        grant.createNamePattern = request.getCreateNamePattern();
        return grant;
    }

    /** D-40: the CREATE name restriction ({@code /regex/} or exact name), or {@code null}. */
    public String getCreateNamePattern() {
        return createNamePattern;
    }

    /**
     * D-40: whether this grant's Create allows a new item called {@code itemName} (the item name,
     * not the full name). Always true without a restriction.
     */
    public boolean allowsCreateName(String itemName) {
        if (createNamePattern == null) {
            return true;
        }
        CreateNamePattern pattern = compiledPattern;
        if (pattern == null) {
            pattern = CreateNamePattern.forStored(createNamePattern);
            compiledPattern = pattern;
        }
        return pattern.matches(itemName);
    }

    /** Whether the D-36 GRANT_EXPIRING notification was already sent. */
    public boolean isExpiringNotified() {
        return expiringNotified;
    }

    /** Only {@code security.GrantService} marks the notification as sent. */
    public void setExpiringNotified(boolean expiringNotified) {
        this.expiringNotified = expiringNotified;
    }

    /** The id of the request this grant came from. */
    public String getGrantRequestId() {
        return grantRequestId;
    }

    public String getId() {
        return id;
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

    /** The revocation reason of a mass revocation (#85), or {@code null}; see {@link #REVOKED_CHANGE_CONTROL_OFF}. */
    public String getRevokedReason() {
        return revokedReason;
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

    /** D-35c: the items created through this grant's Create (a copy; never {@code null}). */
    public List<String> getCreatedItems() {
        return createdItems == null ? new ArrayList<>() : new ArrayList<>(createdItems);
    }

    /** Whether {@code itemFullName} was created through this grant's Create (D-35c). */
    public boolean hasCreated(String itemFullName) {
        return createdItems != null && itemFullName != null && createdItems.contains(itemFullName);
    }

    /**
     * Whether {@code itemFullName} was created through this grant's Create and is still the same
     * item (S-09): when an identity was recorded for it, {@code currentIdentity} (evaluated only
     * then) must return that identity.
     */
    public boolean hasCreated(String itemFullName, Supplier<String> currentIdentity) {
        if (!hasCreated(itemFullName)) {
            return false;
        }
        String recorded = createdItemIdentities == null ? null : createdItemIdentities.get(itemFullName);
        return recorded == null || recorded.equals(currentIdentity.get());
    }

    /** S-09: the recorded identities of the created items (a copy; never {@code null}). */
    public Map<String, String> getCreatedItemIdentities() {
        return createdItemIdentities == null ? new HashMap<>() : new HashMap<>(createdItemIdentities);
    }

    /**
     * Replaces the created-items list (D-35c). Only {@code security.GrantService} calls this, when
     * an item is created, relocated or deleted. Identities of items no longer listed are dropped.
     */
    public void setCreatedItems(List<String> items) {
        this.createdItems = items == null || items.isEmpty() ? null : new ArrayList<>(items);
        if (createdItemIdentities != null) {
            createdItemIdentities.keySet().removeIf(name -> createdItems == null || !createdItems.contains(name));
            if (createdItemIdentities.isEmpty()) {
                createdItemIdentities = null;
            }
        }
    }

    /**
     * Replaces the created-item identities (S-09); keys not in the created-items list are
     * ignored. Only {@code security.GrantService} calls this.
     */
    public void setCreatedItemIdentities(Map<String, String> identities) {
        Map<String, String> kept = new HashMap<>();
        if (identities != null && createdItems != null) {
            identities.forEach((name, identity) -> {
                if (identity != null && createdItems.contains(name)) {
                    kept.put(name, identity);
                }
            });
        }
        this.createdItemIdentities = kept.isEmpty() ? null : kept;
    }

    /** D-58a: the items changed under this grant and not reviewed since (a copy; never {@code null}). */
    public List<String> getChangedItems() {
        return changedItems == null ? new ArrayList<>() : new ArrayList<>(changedItems);
    }

    /** Whether {@code itemFullName} was changed under this grant and not reviewed since (D-58a). */
    public boolean hasChanged(String itemFullName) {
        return changedItems != null && itemFullName != null && changedItems.contains(itemFullName);
    }

    /** Replaces the changed-items list (D-58a). Only {@code security.GrantService} calls this. */
    public void setChangedItems(List<String> items) {
        this.changedItems = items == null || items.isEmpty() ? null : new ArrayList<>(items);
    }

    /** Only {@code security.GrantService} may revoke a grant (Manage holders, SPEC item 8). */
    public void markRevoked(Instant revokedAt, String revokedBy) {
        markRevoked(revokedAt, revokedBy, null);
    }

    /** As {@link #markRevoked(Instant, String)}, recording why (#85); only {@code security.GrantService} calls this. */
    public void markRevoked(Instant revokedAt, String revokedBy, String reason) {
        this.revokedAtMillis = Objects.requireNonNull(revokedAt, "revokedAt").toEpochMilli();
        this.revokedBy = revokedBy;
        this.revokedReason = reason;
    }
}
