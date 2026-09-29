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
2. **Only causes the plugin recognises are classified as human.** A build
   started by a trigger plugin with its own `Cause` type, a generic webhook
   trigger for instance, or by a script or plugin code with no cause or only
   `LegacyCodeCause`, is not recognised as person-initiated, so it is treated as
   unattended: it needs no run request, but it passes only on an **activated**
   job (item 39). On an activated job it **passes** the approval gate even when
   `Require approval to run` is set, and neither `blockTimer` nor `blockUpstream`
   narrows it; the pass is logged. Verify how your own trigger plugins behave
   before relying on the gate. Core's own build token is *not* in this category:
   the `RemoteCause` that a `?token=` submission carries is classified, refused
   quietly and written to the audit history as a blocked attempt.
3. **cron, upstream and SCM triggers pass by default on an activated job**,
   and anything installed during a permission window keeps firing after that
   window closes. Expiry removes the *permission*, not the automation that was
   configured with it. Jobs that existed at install, and jobs created while run
   control is off, are activated. A job **created** while run control is on is
   the exception: it starts not activated (item 39) and with cron and upstream
   blocked (item 13), so this applies to it only once an approver has activated
   it and, for cron and upstream, somebody has cleared the switches.
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

8. **Batch Control's grants work with matrix-auth and role-strategy through
    Batch Control's own strategy variants**, `Batch Control: Matrix-based
    security` and `Batch Control: Role-Based Strategy`. Each is a subclass of
    the corresponding upstream strategy, so the upstream's own per-item
    configuration (folder, job and agent authorization properties, item and
    agent roles, pattern-based naming) stays configurable and effective. Any
    other authorization strategy is not a supported grant target: on upgrade
    from an older release, a saved variant whose delegate was neither
    matrix-auth nor role-strategy is unwrapped back to a plain instance of that
    strategy, and grants stop conferring anything from that point. Selecting a
    strategy that is not one of the two variants gets you run control and
    recording only, with an administrative monitor saying so.
9. **role-strategy's own "Manage Roles" and "Assign Roles" saves keep the
    Batch Control variant in place**, tested against role-strategy 918: neither
    save reinstalls the plain `RoleBasedAuthorizationStrategy`, so grants keep
    conferring afterwards; an earlier assumption that they did is not reproduced
    on this version (e2e-03 DD-09). The administrative monitor that detects a
    swap and offers a one-click reinstall of the variant, carrying over every
    role and assignment, stays in place as a safety net should a different
    role-strategy release replace it another way.
10. **Change control is permission-based, not save-based.** Jenkins offers no way
    to intercept the job configuration "Save" itself, so if the applicable
    Batch Control strategy variant is not selected, change control has no
    effect at all, and only the monitor warning tells you.
11. **Grants must name a concrete job or folder.** There is no instance-wide
    grant, which means a grant can confer `Item/Create` only inside a named
    folder, never at the Jenkins root.
12. **The "standing change permissions" monitor is best-effort.** Its verdict is
    cached for up to five minutes and it deliberately ignores administrators, so
    it is a warning, never an enforcement point.

## Automation and generated jobs

Read this section before enabling run control on an instance that generates jobs
from scripts.

13. **While run control is on, every newly created job starts locked**, whatever
    the creation path (UI, REST, CLI, Job DSL, a seed job, a copy) and whoever the
    creator is: `approvalRequired=true`, `blockTimer=true`, `blockUpstream=true`
    and an empty `allowedUpstreamJobs`. It also starts **not activated** (item
    39). Clearing these switches is a recorded change and, with change control
    on, needs a permission window, but it does **not** put the job into service:
    no unattended cause starts the job until an approver approves an `ACTIVATE`
    request, whatever its configuration says. Clearing `approvalRequired` only
    lets a person start it without a run request. Creating a job and putting it
    into service are deliberately two separate acts.

    **Values in the creation payload do not survive the lock.** An
    `approvalRequired=false`, a `blockTimer=false`, a `blockUpstream=false` or an
    `allowedUpstreamJobs` list supplied in a `config.xml` POST, a CLI
    `create-job`, a Job DSL seed or the job being copied from is discarded. Only
    `jobApprovers` is carried over, because it can only ever *narrow* who may
    approve. The allow list is emptied rather than kept because with
    `blockUpstream=true` that list is precisely the set of upstream jobs **exempt**
    from the block, so honouring a supplied one would leave the upstream door open
    and make the lock cosmetic. The case that makes this concrete is name
    squatting: an existing pipeline runs `build job: 'X'` where `X` does not exist
    yet, and whoever may create items creates `X` with the calling pipeline in its
    exempt list; the pipeline's next run would then execute their job with no
    approval and no window.

    **A refused timer, upstream or Replay submission is recorded and shown, not
    silent (#21).** Each quiet queue refusal writes a `TRIGGER_BLOCKED` change
    record naming the job, the cause kind (`TIMER`, `UPSTREAM`, `REPLAY`, ...) and
    what blocked it (`blockTimer`, `blockUpstream`, `approvalRequired`, or
    `activation` for a job that is not activated),
    listed on the change-history screens and exported in `changes.csv`. While run
    control is on and the switch is checked, the job's own main page carries a
    notice to any viewer holding `Item/Read`, naming the switch and pointing at
    the job configuration to clear it; clearing it is itself a recorded change
    and, with change control on, needs a permission window. The controller log
    line ("Blocked timer-triggered run of job" / "Blocked upstream-triggered run
    of job", INFO at most once an hour per job, FINE otherwise) and the job's own
    `CREATE`-with-no-later-`CONFIGURE` history remain, as before, additional
    evidence. The `help-blockTimer` and `help-blockUpstream` inline help texts
    describe all of this, which is where an operator whose cron did not fire
    looks first.

    **Unattended refusals are coalesced, so the trail shows that a job is
    locked, not how often each attempt recurred.** For unattended submissions
    (timer, upstream, SCM, scripts, automatic retries), a `TRIGGER_BLOCKED`
    record merges every refusal of one job and cause kind into at most one
    record per hour, whoever the attempt ran as — a per-minute cron on a locked
    job would otherwise write 1,440 identical rows a day. A refusal of something
    a person did, a clicked Retry, Rebuild or Replay, is not merged that way:
    each attempt writes its own record naming the user and the build it
    re-runs, and a repeat of the same attempt by the same user within one
    minute is merged, so a double click stays one record. These per-attempt
    records are limited to 20 per user in any 10 minutes. The next refusal by
    that user in the same 10 minutes writes one summary record saying that
    further refusals are counted, not listed; later ones are only counted, and
    when the 10 minutes end one closing record gives their number and the
    builds they named. No record is ever rewritten to update a count. A generated nightly job whose Job DSL or JCasC
    definition pins `blockTimer: false` (or an allow list) still does not run its
    first night, since that value does not survive a fresh creation (above); the
    difference now is that the first refusal already produced the notice, the
    change record and the log line, rather than looking exactly like a cron that
    never fired.

    This is not theoretical. The seed jobs in this repository's own e2e
    environment stopped building silently the first time the approval default
    landed, including the ones whose whole purpose was to run unattended, and the
    fix was to make the seed script clear the property right after creating them.
    A generated job that must run unattended needs that second pass, and the pass
    is recorded; it also needs an approved activation, which no script can
    supply.

14. **Branch jobs generated by a multibranch project are exempt** from that
    default. They have no configuration screen, so there would be no way to turn
    the control off, and re-indexing regenerates their configuration anyway.
    Their runs are still recorded. They are *not* exempt from activation: it is
    carried by their computed folder (item 39).

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
22. **Holding `Approve` does not by itself add visibility.** An `Approve` holder
    who is not the designated approver of a request, and who does not otherwise
    qualify under item 21 (not `Manage`, not the requester, not `Item/Read` on
    the target job), sees nothing of it; the permission to decide requests in
    general is not the same as being able to see this one. It is not an
    exception to item 21: the same holder does see the request, as anyone
    would, if they separately hold `Item/Read` on the job. Only the designated
    approver can decide it either way. Approver absence is handled by the
    requester changing the approver before a decision is made; there is no
    delegation or deputy chain. (e2e-03 DD-07)
23. **A grant request's approver set can be changed while it is pending**, from
    the **Change Approvers** action on the grant request's own detail screen,
    the same as a run request (item 3, D-37): the requester edits the
    designated set at any time before a decision is made, and the change is
    recorded (previous set, new set, changed by, time). (e2e-03 DD-08)
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
    mean storing a change that was just judged unauthorised. The page says only
    "missing the Job/Configure permission", so it names neither windows nor where
    to ask for one, and it is identical to the page a user who never had a window
    sees: the two situations cannot be told apart from the refusal itself. What
    stands in for it is the **Request Change Permission** entry on the job's own
    sidebar, which is reachable before the refusal rather than after it.
28. **Monthly summary edge cases** (current behaviour, not yet ratified): an
    `ACKNOWLEDGED` incident is counted in neither the open nor the resolved
    column, and a request that was approved and then expired or was invalidated
    is counted in neither the approved nor the rejected column.
29. **With change control off the Grants screen is closed**, its links are gone from
    the Batch Control landing page, and requesting or approving a window is refused
    with a message that says why, plus a `GRANT_REQUEST_BLOCKED` record. The URL
    itself still answers, deliberately, so that an old bookmark reaches that
    explanation rather than a dead link. Nothing about the audit trail is gated this
    way: the history, dashboard, incident and change-record screens show the same
    content whichever way the switch is set. The Role Strategy notice likewise
    appears with both switches off.
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
32. **Performance at volume is unmeasured.** The history, dashboard and
    change-record screens read a whole month bucket into memory on every page
    load, the incident list opens one file per incident, and run and grant
    request files are never pruned and are all scanned every minute by the
    expiry job. SPEC item 6's target of 5,000 runs a day has therefore not been
    measured, and it is not expected to hold at that scale until the store gains
    an index.

## Before you switch either control on

Two consequences of the design that are easy to meet unprepared. Both were
observed on a running Jenkins 2.568.3, and neither is a defect: they are what the
code does on purpose.

33. **A `CONFIGURE` window confers more than `Item/Configure`.** The window is
    resolved the way Jenkins resolves any permission, by walking `impliedBy`, so
    every permission that declares itself implied by a granted action is answered
    too. Enumerated over the installed permissions of the reference environment, a
    `CONFIGURE` window also confers `Item/ExtendedRead` (reading `config.xml`),
    `Credentials/UseItem` and `Run/Replay`; the last two come from the
    `credentials` and `workflow-cps` plugins, so the set is a property of what is
    installed and another plugin can extend it. Scope is never widened, and no
    chain reaches `Overall/Administer` or any `BatchControl/*` permission. This is
    the same set a standing matrix entry for `Item/Configure` confers; the
    difference is provenance, since a window is approved by a non-administrator who
    is shown only the word `CONFIGURE`. `Run/Replay` is the one worth naming: the
    queue gate still refuses a replay of a job that requires approval, so it is not
    a run-gate bypass there, but on a job without run control a window holder can
    replay a build with a modified Pipeline script.

    A `CONFIGURE` window also lets its holder **rename** the job, to any free
    name in its folder, because Jenkins allows a rename to anyone who may
    configure the job. The rename is recorded as `RENAME` with the window it was
    made under, and like any rename it ends the job's pending requests (item 30).
    An approver who wants to rule out renames has no narrower window to grant.
    A `CREATE` window with a name restriction is different: renaming a job its
    holder created through that window, or a rename that relies on the window's
    Create permission on the folder, is allowed only to a name that matches the
    restriction, and any other name is refused and recorded as a violation.
34. **Turning change control off cuts off work in progress.** The switch is a kill
    switch: while it is off no window confers anything, and flipping it off revokes
    every window open at that moment, one `GRANT_REVOKE` record per closure naming
    the account that flipped it. Whoever is part-way through a change loses the
    permission to finish it with no warning, and the only way back is a new request
    once the control is on again. That is the deliberate trade against a switch that
    would leave windows quietly conferring `Item/Configure` for up to
    `maxGrantMinutes` (default 240) after the control was supposedly off. The off
    period accumulates nothing that could take effect later, because a window can
    neither be requested nor approved during it (item 29), and it removes nothing
    from the audit history of the windows that did exist.

## SYSTEM builds and the global-matrix upgrade

35. **A build that runs as SYSTEM, or as an account with Configure
    permission, can still write a permanent authorization entry.** A Pipeline `properties([authorizationMatrix(...)])` step, or a Job
    DSL seed job, executes as SYSTEM unless the instance runs builds under a
    real user; the guard that reverts a grant holder's self-escalating edit to
    a job's authorization property looks at who saved the item, and SYSTEM is
    not a grant holder, so nothing is reverted or recorded. The same holds for
    a build that runs as an account which already holds `Overall/Administer`
    or `Item/Configure` in the installed strategy, such as a service account:
    that account's save is a legitimate Configure save, so the guard keeps the
    entry. This is not new
    exposure: any user who already holds standing `Item/Configure` on that job
    has the identical path today, with or without Batch Control, since Jenkins
    itself does not distinguish a script's save from a human one.

    The remedy is **Authorize Project** with a **global default build
    authorization** that gives every build, whatever the job's own
    configuration and whatever started it, an identity that is neither SYSTEM
    nor an account holding Configure: the user who started the build, or an
    account without Configure permission. A build that runs as the user who
    started it comes under the same guard as that user's manual save. A
    service account with `Overall/Administer` or `Item/Configure` is not safe
    as the default. Installing the plugin is not enough, and a strategy set on
    a single job does not protect that job: anyone who can configure the job,
    a `CONFIGURE` window holder included, can remove the strategy, and a
    strategy that runs builds as the user who triggered them leaves timer and
    SCM builds, which no user triggered, running as SYSTEM. With Authorize
    Project's per-project setting and no global default, a job without a
    strategy of its own also builds as SYSTEM. Conversely, a job whose own
    build authorization runs as an administrator is exposed to anyone who can
    configure that job, and the instance-wide check below does not see it.

    While change control is on, Batch Control checks this once for the whole
    instance, not job by job. If builds can run as SYSTEM or as an account
    with Configure permission, the administrative monitor on Manage Jenkins
    says so and that a suitable global default build authorization fixes it, and the detail page of a pending request that
    includes `CONFIGURE` shows the same warning to the users who may decide it
    and to `BatchControl/Manage` holders, before the decision. The same monitor
    also warns when no build authenticator (a `QueueItemAuthenticator`) is
    configured at all. The answer is cached for five minutes, so after the
    build authenticators change the warning can take up to five minutes to
    appear or disappear; when they are replaced by saving the security
    configuration, it is re-evaluated at once. The check asks the configured
    authenticators about one representative job, so it cannot judge an
    authenticator that decides by job type, by folder or by the identity of
    the caller; with such an authenticator the warning may be absent although
    some jobs still build as SYSTEM or as an account with Configure.
36. **A legacy wrapper around the global matrix strategy is unwrapped on
    upgrade, not converted.** `GlobalMatrixAuthorizationStrategy` ignores
    per-item ACLs, so converting it straight into the Batch Control matrix
    strategy would make every stale job, folder and agent
    `AuthorizationMatrixProperty` effective at once and let any native
    Configure holder start editing item ACLs. Upgrading from an older release
    therefore leaves the plain global matrix strategy installed. Moving to
    **Batch Control: Matrix-based security** afterwards is a separate action
    an administrator takes explicitly, with the **Install the Batch Control
    variant** button of the administrative monitor on Manage Jenkins (shown
    while change control is on), which says plainly that per-item
    properties become effective from that point.

## Notifications and computed folders

37. **A CREATE name restriction does not cover children a computed folder
    creates during indexing.** Branch jobs a multibranch project generates and
    child projects an organization folder generates are created by the system
    while it re-indexes, not by the holder of the restricted `Item/Create`
    grant, so the exact name or pattern given under D-40 does not apply to
    them (security-08 S-07, D-40a). This follows the same boundary as item 6:
    those children are generated rather than authored, and only their runs are
    recorded.
38. **A notification e-mail includes a link back to the request only when the
    Jenkins URL is configured.** Set it under Manage Jenkins → System
    (Jenkins Location). Without it, a `REQUEST_CREATED`, `APPROVERS_CHANGED`,
    `APPROVED`, `REJECTED` or `GRANT_EXPIRING` message still carries its
    subject and reason text but no link, rather than guessing one from the
    request that triggered it (security-08 S-04, D-36).

## Activation and other plugins' buttons

39. **While run control is on, a job created under it runs unattended only
    after an approved activation** (SPEC 6a). Unattended means every cause that
    is not a person acting now: timer, upstream, SCM, webhooks and other
    unclassified trigger causes, submissions from scripts and plugin code (no
    cause, or only `LegacyCodeCause`), and automatic retries such as naginator's,
    even when the build being retried was started by a person. The gate applies
    to every non-computed job whatever `approvalRequired` says, and to every
    user including administrators; clearing `blockTimer`, `blockUpstream` or
    `approvalRequired`, or removing the job property, never activates a job, and
    no configuration write path can. An activation is requested per job
    (`ACTIVATE`, with a reason and one or more approvers) with
    `BatchControl/Request` plus `Item/Read`, decided on the **Activations**
    screen like a run request, and recorded as `ACTIVATED`. A hold (`HOLD`) is
    also a request that needs approval, recorded as `HELD`; for an immediate
    stop use Jenkins' **Disable Project** or the global run-control switch. An
    activation survives configuration edits, renames and moves.

    What counts as activated without a request: every job and folder present
    when this version is first installed (`activatedBy = (upgrade)`, recorded
    once), and every job created while run control is off
    (`activatedBy = (uncontrolled)`), so turning run control on never stops an
    existing schedule. Computed folders (multibranch projects, organization
    folders) carry activation for their children: one created while run control
    is on starts not activated, the `ACTIVATE`/`HOLD` request is made on the
    folder, and a child passes only if its nearest computed-folder ancestor is
    activated. The state fails closed: a job re-created under a deleted job's
    name starts not activated.
40. **Other plugins' build buttons show their own generic failure message.**
    When Batch Control refuses a run started from another plugin's button
    (Rebuild or Rebuild Last where they are shown, naginator's Retry, a button
    relabelled or replaced by customize-build-now), the refusal happens at queue entry, but that plugin
    submits by script and shows only its own message, such as "Failed to
    schedule build. Reload the page and try again." or "Failed.", which says
    nothing about approval or activation. The run was correctly refused and
    nothing was queued. The job's own page is where the reason is: on a job
    that requires approval a notice says that manual runs need an approved run
    request, whichever button started them, and links to **Request Run** for a
    `Request` holder; the activation notice says whether unattended runs are
    allowed, with a **Request activation** link (e2e-03 DEF-01).

41. **Some re-run links cannot be hidden on a job that requires approval.**
    Three entries are drawn for every user who holds the underlying
    permission, and no extension point lets Batch Control remove them:
    Jenkins' own build link (relabelled **Direct Build (needs approval)** on
    such a job), Pipeline's **Replay**, and naginator's **Retry**. They
    therefore stay visible, and a click is refused at queue entry with an
    explanation: the job page and the build page carry the approval notice,
    and the refusal page, or the other plugin's failure message next to that
    notice (item 40), points to **Request Run**. Nothing is queued. The rebuild
    plugin's **Rebuild** is different: that plugin lets Batch Control hide it,
    so it does not appear on such a job. A user who may see the job but not
    build it is not offered the rerun form, and a rerun submitted anyway is
    refused without creating a request (e2e-03 DEF-12, DEF-16, DEF-25, DEF-01).

## Out of scope by design

Bypass by `Overall/Administer`; detecting edits made directly on disk; restarting
or resuming a step *inside* the batch application; and controlling changes to
Pipeline scripts held in Git. None of these are things this plugin attempts.
