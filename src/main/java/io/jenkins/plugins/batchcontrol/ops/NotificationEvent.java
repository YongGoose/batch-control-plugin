package io.jenkins.plugins.batchcontrol.ops;

/**
 * The request events a {@link BatchControlNotifier} receives (SPEC item 13, D-36).
 *
 * <p>Recipients: the designated approvers for {@link #REQUEST_CREATED} and
 * {@link #APPROVERS_CHANGED}; the requester for every other event.
 */
public enum NotificationEvent {
    /** A run or change request was created and waits for a decision. */
    REQUEST_CREATED,
    /** The requester changed the designated approver set of a pending request. */
    APPROVERS_CHANGED,
    /** A designated approver approved the request. */
    APPROVED,
    /** A designated approver rejected the request. */
    REJECTED,
    /** A pending run or change request reaches its pending timeout soon. */
    EXPIRING,
    /** An active change window (grant) ends soon. */
    GRANT_EXPIRING;

    /** Short human-readable title used in plain-text messages. */
    public String getTitle() {
        switch (this) {
            case REQUEST_CREATED:
                return "New request awaiting your decision";
            case APPROVERS_CHANGED:
                return "Request routed to you";
            case APPROVED:
                return "Request approved";
            case REJECTED:
                return "Request rejected";
            case EXPIRING:
                return "Pending request expires soon";
            case GRANT_EXPIRING:
                return "Change window ends soon";
            default:
                return name();
        }
    }
}
