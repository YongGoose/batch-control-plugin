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
    GRANT_REVOKE
}
