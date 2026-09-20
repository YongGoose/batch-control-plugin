package io.jenkins.plugins.batchcontrol.queue;

import hudson.model.Cause;
import java.util.Objects;

/**
 * Cause carried by every build that runs on behalf of an approved run request (SPEC item 5):
 * links the build to the request id, the requester and the approver.
 */
public class ApprovedCause extends Cause {

    private final String requestId;
    private final String requester;
    private final String approver;

    public ApprovedCause(String requestId, String requester, String approver) {
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.requester = requester;
        this.approver = approver;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getRequester() {
        return requester;
    }

    public String getApprover() {
        return approver;
    }

    @Override
    public String getShortDescription() {
        return "Approved batch run request " + requestId
                + " (requested by " + requester + ", approved by " + approver + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ApprovedCause)) {
            return false;
        }
        ApprovedCause that = (ApprovedCause) o;
        return requestId.equals(that.requestId)
                && Objects.equals(requester, that.requester)
                && Objects.equals(approver, that.approver);
    }

    @Override
    public int hashCode() {
        return Objects.hash(requestId, requester, approver);
    }
}
