package io.jenkins.plugins.batchcontrol.model;

import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.Ids;
import java.time.Instant;
import java.util.Objects;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Audit record of a change event (SPEC section 3). Appended to the monthly JSONL file
 * {@code changes/YYYY-MM.jsonl}; the month bucket is derived from {@link #getAt()}.
 * Append-only: records are never modified after creation.
 */
@Restricted(NoExternalUse.class)
public final class ChangeRecord {

    private final String id;
    private final ChangeType type;
    private final String target;
    private final String user;
    private final Instant at;
    private String grantId;
    private String diff;
    private final String detail;

    private ChangeRecord(String id, ChangeType type, String target, String user, Instant at, String detail) {
        this.id = id;
        this.type = type;
        this.target = target;
        this.user = user;
        this.at = at;
        this.detail = detail;
    }

    /** Creates a record with a fresh id and the current {@link BatchClock} time. */
    public static ChangeRecord create(ChangeType type, String target, String user, String detail) {
        Objects.requireNonNull(type, "type");
        return new ChangeRecord(Ids.newId(), type, target, user, BatchClock.now(), detail);
    }

    /** Rebuilds a record from stored fields (used by the store when reading JSONL). */
    public static ChangeRecord restore(String id, ChangeType type, String target, String user,
                                       Instant at, String grantId, String diff, String detail) {
        ChangeRecord record = new ChangeRecord(id, type, target, user, at, detail);
        record.grantId = grantId;
        record.diff = diff;
        return record;
    }

    public String getId() {
        return id;
    }

    public ChangeType getType() {
        return type;
    }

    public String getTarget() {
        return target;
    }

    public String getUser() {
        return user;
    }

    public Instant getAt() {
        return at;
    }

    public String getGrantId() {
        return grantId;
    }

    public void setGrantId(String grantId) {
        this.grantId = grantId;
    }

    public String getDiff() {
        return diff;
    }

    public void setDiff(String diff) {
        this.diff = diff;
    }

    public String getDetail() {
        return detail;
    }
}
