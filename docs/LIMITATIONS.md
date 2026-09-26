# Known limitations

This is the complete list. [`README.md`](../README.md) carries the subset that
changes an administrator's decisions; everything else is here, because each of
these will otherwise be discovered in production.

The numbering is stable so that issues and reviews can cite an item. The
authority for behaviour is [`SPEC.md`](SPEC.md); the reasoning behind the
deliberate choices is in [`DECISIONS.md`](DECISIONS.md) and section 7 of
[`ARCHITECTURE.md`](ARCHITECTURE.md).

## Scope of control

1. **Administrators bypass every control.** `Overall/Administer` implies every
   Batch Control permission and every Jenkins permission. The plugin does not try
   to stop administrators; it records what they do.
2. **Only causes the plugin recognises are classified.** A build started by a
   trigger plugin with its own `Cause` type, a generic webhook trigger for
   instance, is not recognised as person-initiated and therefore **passes** the
   approval gate. The pass is logged. This is the same default-open posture as
   the timer and upstream policies: verify how your own trigger plugins behave
   before relying on the gate.
3. **cron, upstream and SCM triggers pass by default**, and anything installed
   during a permission window keeps firing after that window closes. Expiry
   removes the *permission*, not the automation that was configured with it.
4. **A blocked job called from a Pipeline `build` step fails its caller.** When
   a protected job is refused at queue entry, the upstream job ends as `FAILURE`.
   This happens even with `wait: false`; it is Jenkins' behaviour, not a choice
   of this plugin.
5. **Changes made outside Jenkins are not recorded.** Editing `config.xml` on
   disk and then using "Reload Configuration from Disk" produces no `CONFIGURE`
   record, because Jenkins reports that as a load, not a change.
6. **Multibranch and organisation-folder children are not change-controlled.**
   Their configuration is generated, so only their runs and failures are
   recorded.
7. **Blocking happens only at queue entry.** Nothing the plugin does interrupts
   a build that is already running: not flipping a global switch, not toggling a
   job property, not changing the job's configuration, and not a permission
   window expiring mid-build.

## The authorization strategy

8. **JIT change control works only with matrix-family authorization
   strategies.** With **Role-Based Authorization Strategy** as the strategy, only
   **run control and recording** work; permission windows do not. Wrapping Role
   Strategy leaves permission decisions correct but breaks its own role
   management screens, so it is not supported. An administrative monitor says so
   when Role Strategy is in use. Support for it is a possible future item, not a
   present one.
9. **Change control is permission-based, not save-based.** Jenkins offers no way
   to intercept the job configuration "Save" itself, so if the wrapping strategy
   is not selected, change control has no effect at all, and only the monitor
   warning tells you.
10. **A wrapping strategy saved without a delegate locks everyone out**,
    including administrators, and is recoverable only by editing
    `$JENKINS_HOME/config.xml` on disk.
11. **Grants must name a concrete job or folder.** There is no instance-wide
    grant, which means a grant can confer `Item/Create` only inside a named
    folder, never at the Jenkins root.
12. **The "standing change permissions" monitor is best-effort.** Its verdict is
    cached for up to five minutes and it deliberately ignores administrators, so
    it is a warning, never an enforcement point.

## Automation and generated jobs

Read this section before enabling run control on an instance that generates jobs
from scripts.

13. **While run control is on, every newly created job starts with
    `approvalRequired=true`**, whatever the creation path (UI, REST, CLI, Job
    DSL, a seed job) and whoever the creator is. An explicit
    `approvalRequired=false` in the creation payload is overwritten. Turning the
    control off afterwards means editing the job, which is itself a recorded
    change; that recorded path is the intended way out.

    A Job DSL or JCasC definition that pins `approvalRequired: false` is not
    idempotent against a *fresh* creation: the first seed run creates the job
    controlled, and only a second run, an update rather than a creation, clears
    it. A generated job that must run unattended needs that second pass, and the
    pass is recorded.

    This is not theoretical. The seed jobs in this repository's own e2e
    environment stopped building silently the first time this default landed,
    including the ones whose whole purpose was to run unattended, and the fix was
    to make the seed script clear the property right after creating them. Note
    that automatic builds are not what breaks: timer, upstream and SCM causes
    still pass. What stops is anything a person has to press.

14. **Branch jobs generated by a multibranch project are exempt** from that
    default. They have no configuration screen, so there would be no way to turn
    the control off, and re-indexing regenerates their configuration anyway.
    Their runs are still recorded.

## Secrets

15. **Console-log masking has a detection limit.** When an incident's log tail is
    stored, the build's own sensitive parameter values and Jenkins `Secret`
    plaintexts are masked. **Anything else a secret is printed by, a token
    echoed by a script, a stack trace, a third-party tool, is not detected and
    is stored and displayed verbatim** to anyone holding `ViewHistory`. Generic
    secret-pattern detection was deliberately rejected: it gives false confidence
    and still misses things.
16. **A job with secret parameters cannot be rerun faithfully.** Request
    parameters are masked before they are stored, so no plaintext secret is ever
    written, but an approved run, and a rerun prefilled from an incident,
    therefore submit the mask rather than the original value. Approval-based
    execution of jobs with password parameters is not usable today.
17. **Stored configuration snapshots are not masked.** The diff shown in a change
    record is masked, but `batch-control/snapshots/<job>.xml` keeps the raw
    `config.xml`. Secrets inside it are Jenkins-encrypted exactly as they are in
    `$JENKINS_HOME/jobs/*/config.xml`: the same protection, on the same disk, and
    no more.
18. **A change that touched only secret values carries an explanatory note
    instead of a diff**, because both sides are masked identically and a real
    diff would be empty.

## Visibility and permissions

19. **`BatchControl/ViewHistory` is an instance-wide audit read, and it is not
    filtered per job.** The history screens, the dashboard and the CSV exports
    show **every** request and run, with job names, parameter values, reasons,
    requesters, approvers and decision comments, to any holder, with no
    `Item/Read` check and no ownership filter. This is a different rule from the
    Run Requests and Grants screens, which *are* filtered per object. Treat
    `ViewHistory` as what it is: full visibility of the audit trail.
20. **`ViewHistory` also authorizes incident state changes.** Acknowledging,
    resolving and commenting on an incident are gated on `ViewHistory`, so a
    read-only auditor account can also close incidents. Requesting a rerun
    correctly needs `Request`.
21. **Request visibility follows the job's own read boundary.** A run request is
    visible to `Manage` holders, its requester, its designated approver, and
    anyone with `Item/Read` on the target job. In an instance where `Item/Read`
    is granted broadly, reasons and parameter values are broadly visible.
22. **An `Approve` holder who is not the designated approver sees nothing** of
    that request, which is consistent, since only the designated approver can
    decide it. Approver absence is handled by the requester changing the approver
    before a decision is made; there is no delegation or deputy chain.
23. **Grant requests have no approver change.** Unlike run requests, changing the
    approver on a pending grant request means cancelling it and creating a new
    one.
24. **Active grants are visible only to their own holder** and to `Manage`
    holders.
25. **Approver accounts must map one-to-one to real people.** The plugin can only
    compare user IDs. One person holding two accounts, requesting as one and
    approving as the other, satisfies the two-person rule and is recorded as a
    normal, non-self approval. Keeping accounts and the approver list honest is an
    organisational control, not something a plugin can enforce.

## Records and screens

26. **A folder rename produces one `MOVE` record per descendant job**, plus a
    `RENAME` for the folder itself. This is accurate, since every child's full
    name did change, but it means one rename can generate a large number of
    records.
27. **The expired-window denial page is Jenkins' own.** Saving a configuration
    after a `CONFIGURE` window has expired gives the stock Jenkins 403 page. The
    plugin deliberately does not intercept it: Jenkins does not offer that as an
    extension point, and intercepting it would mean taking over *every*
    permission denial in the instance. The expiry notice, the history of expired
    windows and the re-request link are on the Grants screen instead, and the
    remaining time is shown there so you can renew before expiry.
    **Configuration you were editing is not restored**, because restoring it would
    mean storing a change that was just judged unauthorised.
28. **Monthly summary edge cases** (current behaviour, not yet ratified): an
    `ACKNOWLEDGED` incident is counted in neither the open nor the resolved
    column, and a request that was approved and then expired or was invalidated
    is counted in neither the approved nor the rejected column.
29. **The Grants section is visible even with change control off**, because the
    authorization strategy is deliberately independent of the switch. The Role
    Strategy notice likewise appears with both switches off.
30. **A request does not follow its job.** If the target job is renamed or moved
    while a run request is open, the request ends as `INVALIDATED` rather than
    executing against a job under a different name. This is deliberate, but it
    means a rename during a busy approval queue silently costs the requesters
    their pending requests, and they have to file them again.
31. **No rate limiting.** There is a size cap on a reason (4,000 characters) and
    on each string parameter value (10,000 characters), but no per-user request
    rate limit and no cap on concurrent pending requests; bulk-created requests
    accumulate until the pending timeout clears them. Likewise nothing limits the
    rate of configuration changes, so a burst of saves inside a window produces a
    burst of diff and snapshot writes against a single store lock.

## Out of scope by design

Bypass by `Overall/Administer`; detecting edits made directly on disk; restarting
or resuming a step *inside* the batch application; and controlling changes to
Pipeline scripts held in Git. None of these are things this plugin attempts.
