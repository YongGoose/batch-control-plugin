package io.jenkins.plugins.batchcontrol.model;

import java.time.Instant;
import java.util.Objects;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Whether a job has been approved to run unattended (SPEC item 6a, D-39). Stored by the plugin at
 * {@code activations/<PathCodec-encoded job full name>.xml}, outside the job's configuration, so
 * no configuration write path can change it. Only {@code policy.ActivationService} writes it: on
 * an approved ACTIVATE or HOLD request, once at upgrade ({@link #UPGRADE}), and when a job is
 * created while run control is off ({@link #UNCONTROLLED}, D-45).
 *
 * <p>The job full name is stored inside the file as well, because a shortened encoded file name
 * cannot be decoded back (store/PathCodec).
 */
@Restricted(NoExternalUse.class)
public final class ActivationState {

    /** {@link #getActivatedBy()} of a job that existed when the plugin version was first installed. */
    public static final String UPGRADE = "upgrade";

    /** {@link #getActivatedBy()} of a job created while run control was off (D-45). */
    public static final String UNCONTROLLED = "uncontrolled";

    private final String jobFullName;
    private boolean activated;
    private String activatedBy;
    private Long activatedAtMillis;
    private String requestId;
    private String deactivatedBy;
    private Long deactivatedAtMillis;

    private ActivationState(String jobFullName) {
        this.jobFullName = Objects.requireNonNull(jobFullName, "jobFullName");
    }

    /** An activated state, granted by {@code by} through {@code requestId} ({@code null} for the upgrade seeding). */
    public static ActivationState activated(String jobFullName, String by, Instant at, String requestId) {
        ActivationState state = new ActivationState(jobFullName);
        state.activated = true;
        state.activatedBy = by;
        state.activatedAtMillis = at == null ? null : at.toEpochMilli();
        state.requestId = requestId;
        return state;
    }

    /**
     * This state put on hold by {@code by} through {@code requestId}: not activated, keeping who
     * activated it last for the record.
     */
    public ActivationState heldBy(String by, Instant at, String holdRequestId) {
        ActivationState state = copyFor(jobFullName);
        state.activated = false;
        state.deactivatedBy = by;
        state.deactivatedAtMillis = at == null ? null : at.toEpochMilli();
        state.requestId = holdRequestId;
        return state;
    }

    /** A never-activated job put on hold (a HOLD approved for a job without a stored state). */
    public static ActivationState held(String jobFullName, String by, Instant at, String holdRequestId) {
        return new ActivationState(jobFullName).heldBy(by, at, holdRequestId);
    }

    /** The same state under another full name (rename or move keeps the activation). */
    public ActivationState copyFor(String newFullName) {
        ActivationState copy = new ActivationState(newFullName);
        copy.activated = activated;
        copy.activatedBy = activatedBy;
        copy.activatedAtMillis = activatedAtMillis;
        copy.requestId = requestId;
        copy.deactivatedBy = deactivatedBy;
        copy.deactivatedAtMillis = deactivatedAtMillis;
        return copy;
    }

    public String getJobFullName() {
        return jobFullName;
    }

    public boolean isActivated() {
        return activated;
    }

    /** The approver who activated the job, or {@link #UPGRADE}; {@code null} if it never was. */
    public String getActivatedBy() {
        return activatedBy;
    }

    public Instant getActivatedAt() {
        return activatedAtMillis == null ? null : Instant.ofEpochMilli(activatedAtMillis);
    }

    /** The request that set this state last, or {@code null} for the upgrade seeding. */
    public String getRequestId() {
        return requestId;
    }

    /** The approver of the hold, or {@code null} if the job is not on hold. */
    public String getDeactivatedBy() {
        return deactivatedBy;
    }

    public Instant getDeactivatedAt() {
        return deactivatedAtMillis == null ? null : Instant.ofEpochMilli(deactivatedAtMillis);
    }

    /** Whether this state came from the one-time upgrade seeding. */
    public boolean isSeededByUpgrade() {
        return activated && UPGRADE.equals(activatedBy) && requestId == null;
    }
}
