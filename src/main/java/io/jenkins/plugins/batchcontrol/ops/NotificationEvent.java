package io.jenkins.plugins.batchcontrol.ops;

/**
 * The request events a {@link BatchControlNotifier} receives (SPEC item 13, D-36).
 *
 * <p>Recipients: the designated approvers for {@link #REQUEST_CREATED} and
 * {@link #APPROVERS_CHANGED}; for the end events of D-54 see {@link #CANCELLED},
 * {@link #EXPIRED} and {@link #INVALIDATED}; the requester for every other event.
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
    GRANT_EXPIRING,
    /**
     * A pending request was cancelled (D-54). Sent to its designated approvers, and to the
     * requester only when someone else (a Manage holder) cancelled it; never to the canceller.
     */
    CANCELLED,
    /**
     * A request expired (D-54): pending past its timeout, or approved but not started in time.
     * Sent to the requester, with the reason, and to the designated approvers when it was pending.
     */
    EXPIRED,
    /**
     * A request was invalidated (D-54), for example because its job was renamed or deleted. Sent to
     * the requester, with the reason, and to the designated approvers when it was pending.
     */
    INVALIDATED;

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
            case CANCELLED:
                return "Request cancelled";
            case EXPIRED:
                return "Request expired";
            case INVALIDATED:
                return "Request invalidated";
            default:
                return name();
        }
    }
}
