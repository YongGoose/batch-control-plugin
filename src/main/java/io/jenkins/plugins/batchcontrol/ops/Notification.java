package io.jenkins.plugins.batchcontrol.ops;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable description of one notification (SPEC item 13, D-36), handed to every
 * {@link BatchControlNotifier}. All values are plain text; a notifier that renders HTML must
 * escape them.
 */
public final class Notification {

    /** {@link #getKind()} of a run request. */
    public static final String KIND_RUN = "RUN";
    /** {@link #getKind()} of a change (grant) request or window. */
    public static final String KIND_GRANT = "GRANT";
    /** {@link #getKind()} of an activation or hold request for a job (SPEC item 6a). */
    public static final String KIND_ACTIVATION = "ACTIVATION";

    private final String kind;
    private final String requestId;
    private final String subject;
    private final String requester;
    private final String reason;
    private final List<String> recipients;
    private final String url;
    private final String action;

    public Notification(String kind, String requestId, String subject, String requester,
                        String reason, List<String> recipients, String url) {
        this(kind, requestId, subject, requester, reason, recipients, url, null);
    }

    /**
     * @param action for an activation request the requested action ({@code ACTIVATE} or
     *               {@code HOLD}, security-13 S-13-08); {@code null} for other kinds
     */
    public Notification(String kind, String requestId, String subject, String requester,
                        String reason, List<String> recipients, String url, String action) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.subject = subject;
        this.requester = requester;
        this.reason = reason;
        this.recipients = recipients == null
                ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(recipients));
        this.url = url;
        this.action = action;
    }

    /** {@code ACTIVATE} or {@code HOLD} for an activation request; {@code null} for other kinds. */
    public String getAction() {
        return action;
    }

    /**
     * {@code "RUN"} for a run request, {@code "GRANT"} for a change request or window,
     * {@code "ACTIVATION"} for an activation or hold request.
     */
    public String getKind() {
        return kind;
    }

    /** The request id (for a window, the id of the change request it came from). */
    public String getRequestId() {
        return requestId;
    }

    /** The job full name (run, activation) or the scope full name (grant). */
    public String getSubject() {
        return subject;
    }

    public String getRequester() {
        return requester;
    }

    public String getReason() {
        return reason;
    }

    /** Jenkins user ids to notify; never {@code null}. */
    public List<String> getRecipients() {
        return recipients;
    }

    /** Absolute link to the request page from the configured Jenkins URL, or {@code null} when none is configured. */
    public String getUrl() {
        return url;
    }

    @Override
    public String toString() {
        return kind + (action == null ? "" : " " + action) + " request " + requestId + " (" + subject + ") -> " + recipients;
    }
}
