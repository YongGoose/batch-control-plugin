package io.jenkins.plugins.batchcontrol.model;

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Lifecycle status of a {@link RunRequest} (and, from slice S2 on, a grant request).
 * State transitions are performed only by the policy services.
 */
@Restricted(NoExternalUse.class)
public enum RequestStatus {
    PENDING,
    APPROVED,
    REJECTED,
    CANCELLED,
    EXPIRED,
    EXECUTED,
    INVALIDATED
}
