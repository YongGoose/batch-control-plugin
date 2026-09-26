package io.jenkins.plugins.batchcontrol.model;

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Kind of event captured by a {@link ChangeRecord}.
 */
@Restricted(NoExternalUse.class)
public enum ChangeType {
    CREATE,
    CONFIGURE,
    DELETE,
    RENAME,
    MOVE,
    CONFIG_TOGGLE,
    RETENTION,
    GRANT_REVOKE,
    /**
     * A blocked re-use of an approved-run marker (D-23 refusal, D-30 audit trail): the marker
     * authorizes exactly one queue submission, and a further submission of the same marker is
     * refused and recorded under this type so an operator can see the attempt. {@code user} is
     * the account that attempted the re-use, not the requester of the original approval.
     */
    MARKER_REUSE_BLOCKED
}
