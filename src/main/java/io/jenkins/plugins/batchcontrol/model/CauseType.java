package io.jenkins.plugins.batchcontrol.model;

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Classification of what caused a build, stored in a {@link RunRecord}.
 */
@Restricted(NoExternalUse.class)
public enum CauseType {
    USER,
    TIMER,
    UPSTREAM,
    APPROVED_REQUEST,
    SCM,
    OTHER
}
