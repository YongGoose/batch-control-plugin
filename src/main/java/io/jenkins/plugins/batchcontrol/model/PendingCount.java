package io.jenkins.plugins.batchcontrol.model;

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * How many PENDING requests of one kind concern a viewer (D-61): the requests awaiting
 * their decision as a designated approver holding {@code BatchControl/Approve}, and their own
 * requests on which they are not a designated approver. Produced only by the
 * {@code countPendingFor(Authentication)} methods of the policy services, so the tab badges and
 * the sections share one rule.
 *
 * <p>Decisions come first: as soon as one request awaits the viewer's decision,
 * {@link #getCount()} is that number; otherwise it is the number
 * of the viewer's own pending requests.
 */
@Restricted(NoExternalUse.class)
public final class PendingCount {

    /** Nothing pending for the viewer. */
    public static final PendingCount NONE = new PendingCount(0, 0);

    private final int awaitingDecision;
    private final int own;

    public PendingCount(int awaitingDecision, int own) {
        if (awaitingDecision < 0 || own < 0) {
            throw new IllegalArgumentException("counts must not be negative");
        }
        this.awaitingDecision = awaitingDecision;
        this.own = own;
    }

    /** PENDING requests on which the viewer is a designated approver holding {@code BatchControl/Approve}. */
    public int getAwaitingDecision() {
        return awaitingDecision;
    }

    /** The viewer's own PENDING requests that are not already counted as awaiting their decision. */
    public int getOwn() {
        return own;
    }

    /** The number to display: decisions when there are any, else own requests; 0 when nothing is pending. */
    public int getCount() {
        return awaitingDecision > 0 ? awaitingDecision : own;
    }

    public boolean isEmpty() {
        return awaitingDecision == 0 && own == 0;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PendingCount other && awaitingDecision == other.awaitingDecision && own == other.own;
    }

    @Override
    public int hashCode() {
        return 31 * awaitingDecision + own;
    }

    @Override
    public String toString() {
        return "PendingCount[awaitingDecision=" + awaitingDecision + ", own=" + own + "]";
    }
}
