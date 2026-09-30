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
     * A permission-window request or approval refused because change control is off (S-15). The
     * switch is a kill switch: while it is off a window confers nothing, so creating or approving
     * one would only bank authority to be redeemed the moment the switch goes back on — exactly the
     * hidden state the kill switch exists to remove. {@code target} is the scope the window was
     * asked for (the job or folder full name), {@code user} is the account that attempted it, and
     * {@code detail} says which of the two transitions was refused.
     *
     * <p>Deliberately not {@link #CONFIG_TOGGLE}: that type's {@code target} is contractually a
     * configuration key and its {@code detail} an {@code old -> new} pair, and nothing about a
     * refused request is a configuration change. Deliberately not {@link #GRANT_REVOKE} either —
     * nothing was revoked here, because nothing was ever granted; filing it there would tell an
     * operator reading the history that a live window had been closed.
     *
     * <p>Like the other two refusal types, this exists because the alternative is a refusal that
     * lives only in a log nobody reads. Unlike them, this refusal is <em>not</em> silent to the
     * caller: it surfaces as a {@code Failure} page naming the switch, so the record is the audit
     * half rather than the only evidence.
     */
    GRANT_REQUEST_BLOCKED,
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
    REMOTE_RUN_BLOCKED,
    /**
     * D-35b: a save by a user whose Item/Configure on the item came only from a grant changed the
     * item's (or folder's) matrix-auth authorization property, and Batch Control put the previous
     * property back. Without this, a temporary Configure could be turned into a permanent
     * authorization entry that outlives the window.
     *
     * <p>{@code user} is the account that made the save, {@code target} the item full name,
     * {@code grantId} the grant the Configure came from, and {@code detail} says that the
     * authorization property was restored. {@code diff} stays {@code null}.
     */
    GRANT_VIOLATION,
    /**
     * An unattended run submission (timer, upstream) or a Pipeline Replay refused quietly by the
     * queue gate (#21, SPEC item 6). The refusal has no error channel, so this record is where it
     * becomes visible; without it a locked job (D-34) looks exactly like a job whose cron never
     * fires.
     *
     * <p>{@code target} is the job full name. {@code detail} starts with {@code cause=<KIND>
     * switch=<name>}, where the kind is {@code TIMER}, {@code UPSTREAM} or {@code REPLAY} and the
     * switch is the job setting that refused it ({@code blockTimer}, {@code blockUpstream}, or
     * {@code approvalRequired} for a replay), or {@code activation} when the job's settings let the
     * cause through but the job is not activated (SPEC item 6a). {@code user} is the account the submission ran as
     * (for a timer normally {@code SYSTEM}). {@code grantId}/{@code diff} stay {@code null}.
     *
     * <p>Coalesced per job and cause kind to at most one record per hour, whoever submitted it
     * (see {@code store.BlockedAttemptAudit#recordCoalesced}): a per-minute cron on a locked job
     * would otherwise write 1,440 identical rows a day.
     */
    TRIGGER_BLOCKED,
    /**
     * A job was put into service (SPEC item 6a, D-39): an ACTIVATE request was approved, or the job
     * existed when this plugin version was first installed and was seeded as activated.
     *
     * <p>{@code target} is the job full name, {@code user} the deciding approver (or
     * {@code upgrade} for the seeding), and {@code detail} names the request id and the requester.
     * {@code grantId}/{@code diff} stay {@code null}.
     */
    ACTIVATED,
    /**
     * A job was put on hold (SPEC item 6a): a HOLD request was approved, so timer and upstream
     * causes no longer run it. Fields as for {@link #ACTIVATED}.
     */
    HELD,
    /**
     * A save of the Batch Control configuration changed something besides the two switches
     * (D-52). {@code target} is {@code batch-control-configuration}, {@code user} the saving user,
     * {@code detail} lists each changed field as {@code name: old -> new} (the approver list in
     * full).
     */
    CONFIG_CHANGE,
    /**
     * A Batch Control authorization strategy was installed (migrated to) or reverted (D-52).
     * {@code target} is {@code authorization-strategy}, {@code detail} names the strategy classes
     * before and after.
     */
    STRATEGY_CHANGE,
    /**
     * An administrator or a native Configure holder marked an item as reviewed (D-58b): it and
     * everything below it left the "changed under a grant" state. {@code user} is the reviewer.
     */
    GUARD_REVIEWED,
    /**
     * A run was started by a Replay, Pipeline Rebuild or Restart from Stage by a user whose
     * permission for it came only from a grant (D-58c). {@code target} is the job, {@code user}
     * the submitter, {@code grantId} the grant; the detail names the run.
     */
    REPLAY_UNDER_GRANT
}
