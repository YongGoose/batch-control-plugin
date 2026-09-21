package io.jenkins.plugins.batchcontrol.model;

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Handling state of an {@link Incident} (SPEC section 4):
 * {@code OPEN -> ACKNOWLEDGED -> RESOLVED}, forward-only.
 */
@Restricted(NoExternalUse.class)
public enum IncidentStatus {
    OPEN,
    ACKNOWLEDGED,
    RESOLVED
}
