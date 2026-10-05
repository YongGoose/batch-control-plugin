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

    /** #85 (P-15): the revocation reason of the windows closed by turning change control off. */
    public static final String REVOKED_CHANGE_CONTROL_OFF = "change control turned off";

    /** D-74: the revocation reason of a window whose item was deleted (or no longer exists). */
    public static final String REVOKED_ITEM_DELETED = "its item was deleted";

    private final String id;
    private final String grantRequestId;
    private final String user;
    /**
     * The item the window is on. D-74: it follows the item when an administrator or a user with
     * their own permissions renames or moves it ({@link #followItem(String)}), as matrix-auth's item
     * permissions do.
     */
    private GrantScope scope;
    /** D-71: the kind of the scope item, copied from the request at approval ({@code null} if it had none). */
    private ItemKind itemKind;
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
     * and Item/Configure on them, so matrix-auth's creator listener adds no permanent entry. Item
     * events keep the names in step (D-74): a rename or move updates them, a deletion drops them.
     * {@code null} when empty.
     */
    private List<String> createdItems;
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
        grant.itemKind = request.getItemKind();
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

    /** D-71: the kind of the scope item, copied from the request ({@code null} if it recorded none). */
    public ItemKind getItemKind() {
        return itemKind;
    }

    /**
     * D-74: the window follows its item, renamed or moved to {@code newFullName}. Only
     * {@code security.GrantService} calls this.
     */
    public void followItem(String newFullName) {
        this.scope = GrantScope.item(Objects.requireNonNull(newFullName, "newFullName"));
    }

    /**
     * Transitional, for the screens only (ui/WindowBinding) until they stop showing windows as
     * "no longer applies": windows follow their item and are never unbound (D-74), so this is never
     * {@code null} for a window on an item.
     *
     * @deprecated nothing is bound to a directory any more (D-74); to be removed with its last caller
     */
    @Deprecated
    public String getItemIdentity() {
        return scope == null ? null : scope.getFullName();
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
     * Replaces the created-items list (D-35c). Only {@code security.GrantService} calls this, when
     * an item is created, relocated or deleted.
     */
    public void setCreatedItems(List<String> items) {
        this.createdItems = items == null || items.isEmpty() ? null : new ArrayList<>(items);
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
