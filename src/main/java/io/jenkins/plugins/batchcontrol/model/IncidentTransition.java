package io.jenkins.plugins.batchcontrol.model;

import java.time.Instant;
import java.util.Objects;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * One entry of an {@link Incident}'s handling history (SPEC item 11): a status transition
 * ({@code {status, by, at, comment}}) or a plain comment (recorded against the incident's
 * status at the time, without changing it).
 *
 * <p>The timestamp is persisted as epoch milliseconds because the Jenkins XStream class filter
 * does not allow {@code java.time.Instant}; the accessor exposes {@link Instant}.
 */
@Restricted(NoExternalUse.class)
public final class IncidentTransition {

    private final IncidentStatus status;
    private final String by;
    private final long atMillis;
    private final String comment;

    public IncidentTransition(IncidentStatus status, String by, Instant at, String comment) {
        this.status = Objects.requireNonNull(status, "status");
        this.by = by;
        this.atMillis = Objects.requireNonNull(at, "at").toEpochMilli();
        this.comment = comment;
    }

    public IncidentStatus getStatus() {
        return status;
    }

    public String getBy() {
        return by;
    }

    public Instant getAt() {
        return Instant.ofEpochMilli(atMillis);
    }

    public String getComment() {
        return comment;
    }
}
