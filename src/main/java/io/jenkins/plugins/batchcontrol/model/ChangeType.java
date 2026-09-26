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
    MARKER_REUSE_BLOCKED,
    /**
     * A run submission refused by the queue gate because its cause is a remote trigger carrying
     * no Jenkins identity ({@code hudson.model.Cause$RemoteCause}, which core mints for
     * {@code /job/X/build?token=…} and {@code /job/X/buildWithParameters?token=…}). The refusal
     * is silent to the caller — a script has no error channel to read guidance from — so this
     * record is the only place the attempt becomes visible (SPEC item 6, S-14).
     *
     * <p>Deliberately not {@link #MARKER_REUSE_BLOCKED}: that type means "an authorization this
     * plugin once granted was presented a second time", and its {@code detail} is contractually
     * about a consumed request id (D-30). A token-triggered run never had an approval at all, so
     * filing it under the same type would tell an operator reading the history that a request
     * was replayed when no such request exists.
     *
     * <p>{@code user} is the account whose credentials carried the request, {@code target} is the
     * job the run was aimed at, and {@code grantId}/{@code diff} stay {@code null} (a refused run
     * happens outside any change window and has no before/after configuration).
     */
    REMOTE_RUN_BLOCKED
}
