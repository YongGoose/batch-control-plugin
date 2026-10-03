# Known limitations

This is the complete list. [`README.md`](../README.md) carries the subset that
changes an administrator's decisions; everything else is here, because each of
these will otherwise be discovered in production.

The numbering is stable so that issues and reviews can cite an item. The
authority for behaviour is [`SPEC.md`](SPEC.md); the reasoning behind the
deliberate choices is in [`DECISIONS.md`](DECISIONS.md) and section 7 of
[`ARCHITECTURE.md`](ARCHITECTURE.md).

## Scope of control

1. **Administrators are recorded, not stopped.** `Overall/Administer` implies
   every Batch Control permission and every Jenkins permission, so an
   administrator can approve their own requests (unless
   `allowAdminSelfApproval` is off), switch either control off, clear a job's
   switches or change the authorization strategy. The plugin does not try to
   prevent that; it records it. The gates themselves still apply to
   administrators: on a job that requires approval an administrator's direct
   build, REST build or Replay is refused like anyone else's, and the
   activation gate holds unattended runs of a job that is not activated for
   every user (item 39).
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
    Batch Control's own strategy variants**, `Batch Control: Project-based
    Matrix Authorization Strategy` and `Batch Control: Role-Based Strategy`.
    Each is a subclass of the corresponding upstream strategy, so the
    upstream's own per-item configuration (folder, job and agent authorization
    properties, item and agent roles, pattern-based naming) stays configurable
    and effective. No other authorization strategy is a grant target, including
    Jenkins' built-in global "Matrix-based security": selecting a strategy that
    is not one of the two variants gets you run control and recording only,
    with an administrative monitor saying so. See item 36 before converting
    from the global matrix strategy.
9. **role-strategy 918 or newer is required, and its "Manage Roles" and
    "Assign Roles" saves keep the Batch Control variant in place.** Older
    role-strategy releases, including 898, the version in the plugin BOM,
    replace the variant with a plain `RoleBasedAuthorizationStrategy` on a
    Manage Roles save, so open windows stop conferring. Batch Control
    therefore declares 918.v91e5468d8db_2 as the minimum for its optional
    role-strategy dependency, and Jenkins will not load it next to an older
    role-strategy (D-35f). On 918 every save path of the role pages (adding
    and removing roles and templates, Assign Roles) edits the installed
    strategy in place. The administrative monitor stays, because an
    administrator can still install a plain strategy on the Security page,
    and it offers a one-click reinstall of the variant that keeps every role
    and assignment. role-strategy's UI rework is still going on upstream (the
    open pull request jenkinsci/role-strategy-plugin#766 redesigns Assign
    Roles and adds a `checkSidName` descriptor endpoint), so a regression
    test checks that every descriptor method role-strategy's pages call on
    the installed strategy exists on the Batch Control descriptor and that
    the save paths keep the variant. A role-strategy release that breaks the
    integration fails Batch Control's build instead of reaching users
    unnoticed.
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
46. **A `config.xml` that still names the removed generic wrapper stops
    Jenkins at boot.** The class
    `io.jenkins.plugins.batchcontrol.security.BatchControlAuthorizationStrategy`
    no longer exists, and Jenkins core treats the authorization strategy as
    critical, so a controller whose `$JENKINS_HOME/config.xml` still names it
    does not start. Replace the `<authorizationStrategy>` element by hand or
    set the strategy with JCasC. No released version of the plugin ever
    contained that class, so only development and test instances can be
    affected (D-35e).

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
    builds they named. No record is ever rewritten to update a count. On a
    clean shutdown, the closing record of every open summary is written
    before Jenkins stops and says that the window ended early because Jenkins
    was shutting down. On a crash the open count is lost: those counted
    refusals remain only in the controller log, one INFO line each. A generated nightly job whose Job DSL or JCasC
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

35. **On an item a grant has touched, authorization can only be widened by
    an administrator's HTTP save until the item is reviewed.** A Pipeline
    `properties([authorizationMatrix(...)])` step saves its job's
    authorization property without asking whether the build's account holds
    Configure, so running builds as a low-privilege account does not by
    itself stop a `CONFIGURE` window holder from giving themself a permanent
    entry through the Pipeline script. Batch Control therefore guards
    *items*, not accounts. While change control is on, these items are
    guarded:

    - every item in the scope of an active grant;
    - every item whose configuration was changed under a grant (saved or
      created by a user whose permission came only from a grant), every item
      a non-administrator created inside a guarded folder, and every
      Pipeline job on which a user whose permission came only from a grant
      submitted a **Replay**, a Pipeline **Rebuild** or a **Restart from
      Stage** (a replayed script is a configuration change that is never
      saved), until the item is marked as reviewed.

    Guarding covers the item and everything below it: the jobs in a guarded
    folder and the branch jobs of a guarded multibranch project.

    The guard on a changed item ends only through **Mark as reviewed**, a
    deliberate action offered to administrators next to each item on the
    Manage Jenkins monitor, and to users who hold `Item/Configure` natively
    (not from a grant) on the item's Batch Control page. That page exists
    only for holders of `BatchControl/Request`, so a native Configure holder
    needs `BatchControl/Request` as well to use the button there; otherwise
    they ask an administrator, who uses the monitor. It writes a
    `GUARD_REVIEWED` change record naming the reviewer. An ordinary save,
    even an administrator's, is not a review.

    Guarding follows renames and moves. On a guarded item, any change that
    widens access is put back and recorded as `GRANT_VIOLATION`, whoever makes
    it: a build running as any account or as SYSTEM, a script, the CLI, or
    another user. Widening means an added or widened entry for anyone
    (`anonymous` and `authenticated` included), an inheritance change that
    widens, removing the authorization property, or adding a second one; a
    new item created inside a guarded folder has its authorization entries
    removed. The only exception is a save made through an HTTP request (the
    web UI, a `config.xml` POST, or REST or CLI over HTTP) by a user who holds
    `Overall/Administer`. A user who holds `Item/Configure` natively but is
    not an administrator is not exempt: until the item is marked as
    reviewed, their widening is put back like anyone else's. A save made through an HTTP
    request is answered with HTTP 403 and a plain message saying which
    authorization entries were not kept and that the other changes were
    saved. A Pipeline build whose own save was put back gets a line in its
    build log naming the reverted entries. A save whose build cannot be
    identified gets no such line, only the `GRANT_VIOLATION` record: for
    example a seed job saving another job, or a Freestyle build.

    This changes how administrators manage authorization on those items, and
    only on those. Until the item is marked as reviewed, a Jenkinsfile, Job
    DSL or JCasC change that widens authorization on a guarded item is put
    back, and so is one made with the CLI over WebSocket or SSH, even by an
    administrator: only the CLI over HTTP (`-http`) or the web UI carries the
    exemption, so use one of those. Items that no grant has touched are not
    affected. The administrative monitor on Manage Jenkins lists the items
    waiting for review. Before marking an item as reviewed, check what was
    changed under the grant, the Pipeline script and any replayed runs
    included: once it is marked the item is no longer guarded, and a script
    left in it that writes authorization entries will then succeed.

    Deleting a guarded item and creating a new one under the same name drops
    the state: the new item is not guarded. An account that may delete and
    create jobs, typically a Job DSL seed job, can therefore re-create a
    guarded job with any authorization entries once the grant has ended.
    This is one more reason to run such builds under a low-privilege account
    (below).

    **Run builds under a low-privilege account as well.** The guard above does
    not make the build account irrelevant: a build that runs as SYSTEM or as
    an account with Configure permission can still change whatever such an
    account may change on items that are not guarded, and anything else
    besides authorization entries.
    Use **Authorize Project** with a **global default build authorization**
    that runs every build, whatever the job's own configuration and whatever
    started it, as an account without Configure permission: for example
    **Run as Specific User** with a dedicated low-privilege build account. Do
    not give that account `Overall/Administer` or `Item/Configure`, neither
    globally nor through a folder's or a job's own authorization entries.
    **Run as the user who triggered the build** is safe only together with
    such a fallback: timer and SCM builds have no triggering user, so without
    one they run as SYSTEM. Installing the plugin is not enough, and a
    strategy set on a single job does not protect that job: anyone who can
    configure the job, a `CONFIGURE` window holder included, can remove the
    strategy. With Authorize Project's per-project setting and no global
    default, a job without a strategy of its own builds as SYSTEM. A job
    whose own build authorization runs as an administrator is exposed to
    anyone who can configure that job, and the instance-wide check below does
    not see it.

    While change control is on, Batch Control checks this once for the whole
    instance, not job by job. If builds can run as SYSTEM or as an account
    with Configure permission, the administrative monitor on Manage Jenkins
    says so and that a suitable global default build authorization fixes it,
    and the detail page of a pending request that includes `CONFIGURE` shows
    the same warning to the users who may decide it and to
    `BatchControl/Manage` holders, before the decision. The same monitor also
    warns when no build authenticator (a `QueueItemAuthenticator`) is
    configured at all. The check has these limits:

    - It looks at the build account's permissions at the Jenkins root only
      (for example a global matrix entry). If the account gets Configure from
      a folder's or a job's own authorization entries, the warning does not
      see it; hence the advice above never to give the build account
      item-level Configure.
    - It asks the configured authenticators about one representative job, so
      it cannot judge an authenticator that decides by job type, by folder or
      by the identity of the caller; with such an authenticator the warning
      may be absent although some jobs still build as SYSTEM or as an account
      with Configure.
    - The answer is cached for five minutes. After the build authenticators
      change, or the build account's permissions change, the warning can take
      up to five minutes to appear or disappear; when the authenticators are
      replaced by saving the security configuration, it is re-evaluated at
      once.
    - When "Run as Specific User" names an account that has no Jenkins user
      record yet, builds run as anonymous, and the warning judges anonymous's
      permissions. Once that account exists, the warning reflects its
      permissions within the same five minutes.
36. **Coming from the global matrix strategy, converting turns on per-item
    permissions.** Converting from Jenkins' built-in global matrix strategy
    (`GlobalMatrixAuthorizationStrategy`, "Matrix-based security") is not a
    like-for-like swap. Grants need the project-matrix variant, **Batch
    Control: Project-based Matrix Authorization Strategy**, and the global
    matrix strategy ignores per-item authorization. Converting therefore makes
    every job, folder and agent `AuthorizationMatrixProperty` already saved on
    the instance effective at that moment, including stale ones nobody has
    looked at since they stopped mattering, and from then on anyone who holds
    `Item/Configure` on an item can edit that item's permissions. Review the
    per-item properties before converting. The conversion is never
    automatic: an administrator starts it with the **Install the Batch Control
    variant** button of the administrative monitor on Manage Jenkins (shown
    while change control is on), and the monitor says the same thing before
    the click.

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
    `APPROVED`, `REJECTED`, `CANCELLED`, `EXPIRED`, `INVALIDATED` or
    `GRANT_EXPIRING` message still carries its
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
    Four entries are drawn for every user who holds the underlying
    permission, and no extension point lets Batch Control remove them:
    Jenkins' own build link (relabelled **Direct Build (needs approval)** on
    such a job), Pipeline's **Replay**, Pipeline's own **Rebuild** on a
    Pipeline build page (shown to users who may build the job, even without
    `Run/Replay`), and naginator's **Retry**. They
    therefore stay visible, and a click is refused at queue entry with an
    explanation: the job page and the build page carry the approval notice,
    and the refusal page, or the other plugin's failure message next to that
    notice (item 40), points to **Request Run**. Nothing is queued. A refused
    click on Pipeline's **Rebuild** shows the "Approval required" page and is
    recorded in the change history like a refused Replay. The rebuild
    plugin's **Rebuild** (a different link from a different plugin) is not
    among these: that plugin lets Batch Control hide it, so it does not
    appear on such a job. A user who may see the job but not
    build it is not offered the rerun form, and a rerun submitted anyway is
    refused without creating a request (e2e-03 DEF-12, DEF-16, DEF-25, DEF-01).

    Jenkins' own build link has one more rough edge. On a job without
    parameters, clicking **Direct Build (needs approval)** submits in the
    background, and the only response is Jenkins' toast "Failed to schedule
    build. Reload the page and try again.", which suggests that trying again
    might help; it will not. On a job with parameters the link opens the
    parameters page first, and submitting it shows the "Approval required"
    page. Either way nothing is queued, and this first click writes no change
    record: it is an ordinary manual click that the gate refused, not a re-run,
    and recording every such click would flood the history (D-56). The link is
    shown to every `Item/Build` holder, administrators included. The way to run
    the job is the approval notice on the job page and its **Request Run**
    link.

## Records from earlier releases and strategy changes

42. **Approved runs recorded by an earlier release have no user.** A run
    record written before this release for a build started by an approved
    request carries no user, so the user filter on History does not find it;
    it can still be found by job or period. From this release on, such a run
    carries the requester as its user, and the request id is kept as before.
    Existing records are not rewritten.
43. **Only Batch Control's own strategy actions write a `STRATEGY_CHANGE`
    record.** Installing a Batch Control authorization strategy with the
    administrative monitor's button, or reverting it with **Revert to the
    plain strategy**, is recorded. Changing the authorization strategy
    directly on **Manage Jenkins → Security** writes no Batch Control record,
    so that change is not in the Batch Control history.

## Moving items

44. **While change control is on, moving an item needs `Item/Delete` on it.**
    A move is treated as deleting the item at the source and creating it at
    the destination, so a user without `Overall/Administer` needs `Item/Delete`
    on the item and `Item/Create` on the destination, each standing or from an
    active window (D-59). The cost: a non-administrator who holds `Item/Move`
    and `Item/Create` standing but not `Item/Delete` can no longer move items
    while change control is on, although plain Jenkins would let them. A
    `DELETE` window on the item lets such a user move it, but the move then
    costs what a delete and recreate would: while run control is also on, a
    job moved by a non-administrator is no longer activated, gets the same
    lock as a newly created job and is recorded as `HELD` naming the move, so
    it does not run unattended until a new activation request is approved
    (D-59a). Administrators' moves keep the job's state. The refused move
    changes nothing and is recorded as a `GRANT_VIOLATION`. A user who holds
    `Item/Delete` on two jobs can still swap them by moving them in and out
    of a job-scoped window's name; such a user could already delete and
    recreate them, and with run control on the swapped jobs arrive locked
    and not activated, as recreated ones would. With change control off,
    moves behave exactly as in Jenkins.

    The rule governs the folders plugin's Move action, in the UI and over
    REST, which use the same endpoint. Jenkins core offers no way to veto a
    move, so a move made by another plugin that calls `Items.move` directly,
    or by a third-party relocation handler ordered before Batch Control's,
    is outside it. Core itself has no CLI move command, its renames stay
    within the parent folder, and scripts need `Overall/Administer`, which
    is exempt anyway.

## Out of scope by design

Bypass by `Overall/Administer`; detecting edits made directly on disk; restarting
or resuming a step *inside* the batch application; and controlling changes to
Pipeline scripts held in Git. None of these are things this plugin attempts.
