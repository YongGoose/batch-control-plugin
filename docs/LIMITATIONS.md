# Known limitations

This is the complete list. [`README.md`](../README.md) carries the subset that
changes an administrator's decisions; everything else is here, because each of
these will otherwise be discovered in production.

The numbering is stable so that issues, reviews and tests can cite an item: a new
item takes the next free number and goes in the section it belongs to, so the
numbers within a section are not always consecutive (item 46 is under "The
authorization strategy"). The authority for behaviour is [`SPEC.md`](SPEC.md);
the reasoning behind the deliberate choices is in [`DECISIONS.md`](DECISIONS.md)
and section 7 of [`ARCHITECTURE.md`](ARCHITECTURE.md).

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
   recorded. A `CONFIGURE` window on the multibranch project or organization
   folder itself can still create or delete them, through the indexing that
   its save starts (item 11).
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
9. **role-strategy 927 or newer is required, and its "Manage Roles" and
    "Assign Roles" saves keep the Batch Control variant in place.**
    Batch Control declares 927.v9cf5527c4085, the release with the
    redesigned Assign Roles page, as the minimum for its optional
    role-strategy dependency, and Jenkins will not load it next to an older
    role-strategy (D-35g). Releases before 918, including 898, the version
    in the plugin BOM, also replaced the variant with a plain
    `RoleBasedAuthorizationStrategy` on a Manage Roles save, so open windows
    stopped conferring (D-35f). On 927 every save path of the role pages
    (adding and removing roles and templates, Assign Roles) edits the
    installed strategy in place. The Batch Control descriptor does not copy
    role-strategy's descriptor methods: it forwards to role-strategy's own,
    so validation and the permission lists behave exactly as on the plain
    strategy. The administrative monitor stays, because an administrator can
    still install a plain strategy on the Security page, and it offers a
    one-click reinstall of the variant that keeps every role and assignment.
    A regression test checks that every descriptor method role-strategy's
    pages call on the installed strategy exists on the Batch Control
    descriptor and that the save paths keep the variant, so a later
    role-strategy release that breaks the integration fails Batch Control's
    build instead of reaching users unnoticed.
10. **Change control is permission-based, not save-based.** Jenkins offers no way
    to intercept the job configuration "Save" itself, so if the applicable
    Batch Control strategy variant is not selected, change control has no
    effect at all, and only the monitor warning tells you.
11. **A permission window covers exactly one job or folder.** A window names
    one top-level item, of any kind, and grants no permission on any other item,
    including the jobs and folders inside a folder it names (D-71). A part of a
    job, such as a configuration of a multi-configuration project or a Maven
    module, cannot be named; such a request is refused when it is submitted.
    There is no instance-wide window and no window for a folder and everything
    below it, so a change that spans several jobs needs a window for each of
    them, each decided on its own. What each action reaches:

    - `CONFIGURE`, with the permissions Jenkins implies from it (item 33),
      covers the item's own configuration only; for a folder that is the
      folder's settings. Those settings are not walled off from the items
      inside the folder, though: what they define reaches every item below it,
      such as a folder-level Pipeline library marked "Load implicitly", which
      changes what every Pipeline in the folder runs, or folder properties and
      environment that the jobs read. Reconfiguring a multibranch project or
      an organization folder (its sources, filters or orphaned-item strategy)
      makes the next indexing create its generated items, or delete them with
      their build history, as SYSTEM (item 6). The page of a `CONFIGURE`
      request on a folder, multibranch project or organization folder states
      both points to the approver and the requester, together with the fact
      that the window does not allow renaming it (item 33; security-34
      S-34-02); the page of a `CONFIGURE` request on a job states that rename
      rule alone (security-36 S-36-04).
    - `CREATE` applies only to a regular folder and allows creating items
      directly inside it: never at the Jenkins root, never inside a folder
      nested in it, and never inside a multibranch project or organization
      folder, whose children are generated (item 6). The Configure that the
      holder keeps on items created that way (D-35c) is matched by the created
      item's parent being the window's folder, not by its name, and does not
      extend to renaming them (item 33).
    - `DELETE` applies only to a job, including multi-configuration and Maven
      projects, whose sub-items are part of the job. Maven projects are
      covered by the same rule, because a Maven project is a job, but no test
      exercises them: the build has no `maven-plugin` test dependency (D-74).
      No window confers
      `Item/Delete` on a folder, a multibranch project or an organization
      folder, because core deletes everything inside one as SYSTEM without
      checking those items. While change control is on, the delete veto
      therefore leaves deleting one of these to administrators, and moving one
      (item 44) needs an administrator or standing `Item/Delete` on it.

    A request for `CREATE` or `DELETE` on an item where it cannot apply is
    refused when it is submitted. The request records the item's kind (for
    example Pipeline, Freestyle project, Folder, Multibranch Pipeline or
    Organization Folder), the request screens show that kind with its icon
    (there is no scope type to choose), and approval is refused when no
    item exists at that name any more or its kind has changed. The stored
    scope is the item's canonical full name, whatever spelling was typed
    (Jenkins resolves `team/` or `TEAM` to `team`, and the request records
    `team`).

    Once approved, a window applies to its item, not to a name (D-74). It
    matches the item by its exact full name, and Jenkins' item events keep that
    name current, as matrix-auth does for its item permissions. When an
    administrator or a user with their own permissions renames or moves the
    item, the window follows it to the new full name, and when a folder is
    renamed or moved, the windows on the items inside it follow too. No window
    can authorise the rename itself (item 33). Deleting the item ends its
    windows, and deleting a folder ends the windows on everything inside it:
    each is revoked, by the account that deleted the item, with the reason
    "its item was deleted" and a `GRANT_REVOKE` record. Creating a new item at
    a window's name ends that window the same way, since its own item must
    have disappeared without a deletion event, and so does starting Jenkins
    after the item has vanished, as if it had been deleted while Jenkins was
    down. A window therefore either applies to its item, under whatever name
    the item has now, or has ended; renaming, moving, swapping or re-creating
    items, or combining several windows, cannot make a window reach an item
    nobody approved. One gap remains: an item replaced on disk outside Jenkins,
    followed by a reload, fires no item event, so a window naming it applies to
    the replacement. The precondition is file-system access to `$JENKINS_HOME`,
    which is outside the plugin's reach anyway (item 5); the reload itself does
    not need `Overall/Administer`, because reloading a single item from disk
    needs only `Item/Configure` on it.
12. **The "standing change permissions" monitor is best-effort.** Its verdict is
    cached for up to five minutes and it deliberately ignores administrators, so
    it is a warning, never an enforcement point.

<!-- Item 46 was added after 13-45 and sits here by topic. The comment ends the list so that it renders as 46, not 13. -->

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
16. **An incident rerun can reuse only the parameter values its build still
    holds.** A run request keeps the submitted parameter values with their
    types (D-72), so the approved run receives exactly what was submitted: the
    original value of a password or other sensitive parameter, and file
    parameters, both core `file` and the file-parameters plugin's `stashedFile`
    and `base64File`. Secrets are stored only in Jenkins' encrypted form, the
    protection core gives them in a build's `build.xml`, and every screen, CSV,
    history record, run record and incident shows them as `********`. A file
    shows only as `[file] <original file name>`, never its content, its Base64
    or a server path. The typed values are kept in a values file of their own,
    `requests/run/<id>.values.xml`, only until the approved run starts, the
    request ends, or the queue item of the approved run is cancelled; then
    that file is deleted and the masked values remain as the record (D-72b,
    D-74, item 32). An incident rerun reuses the failed
    run's own values the same way, original secrets included: a core file is
    recreated from the copy the build keeps, and a `base64File` value carries
    its content. It takes values only from the incident's own build: the
    incident records the failed build's timestamp, and a build counts as that
    run only while it still has that timestamp. A build that is not that run,
    such as the build with the same number in a job that was deleted and
    re-created under the same name, is treated like a deleted build, and its
    values are not used. An incident recorded before the timestamp was kept
    cannot confirm its build, so its rerun always opens the Request Run form
    described below (D-72b). A failed run that holds the same parameter name
    more than once is not rerun at all: its values are never carried, and the
    message points to the job's Request Run form. Some values cannot be
    recovered from the build: a `stashedFile`, whose stash the build clears
    when it completes, and a core file whose copy is gone. A rerun that cannot
    recover all of its values creates no request. Instead it opens the job's
    Request Run form with the build's recoverable non-sensitive values filled
    in. Nothing can be recovered from a deleted build, so for it the form is
    filled in with the non-sensitive values recorded on the incident instead;
    secrets and files are recorded only masked, so they are never filled in.
    These values travel in the URL as described in item 48, and the files and
    secrets have to be provided again. A request submitted from that form is
    linked to the incident only after the server has validated the incident
    reference again (the incident exists, belongs to that job, and the
    submitter holds `ViewHistory`), so a successful run still records
    `resolvedByRunId` (D-72a).
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

<!-- Item 49 was added after 26-48 and sits here by topic. The comment ends the list so that it renders as 49, not 26. -->

49. **The "requester does not have Build permission" notice can lag a permission
    change by up to five minutes.** The notice *The requester does not have Build
    permission on this job.* on a run request is evaluated for the requester and
    cached per request for up to five minutes. The cached value is dropped
    whenever the request is saved (approved, rejected, cancelled or its approvers
    re-designated), but not when the requester's permissions change, so after an
    administrator grants the requester `Job/Build` the notice can keep showing
    for up to five minutes (D-38a).

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
31. **No rate limiting, and the instance-wide upload limit is Jenkins' own.**
    There is a size cap on a reason (4,000 characters) and on every textual
    value a run request stores (10,000 characters per parameter), the plaintext
    of a password or other secret included, although it is displayed as
    `********` (D-72b). The content of a file, the Base64 of a `base64File`
    value included, is bounded only by the body cap below. A run request names
    each parameter at most once: a submission that repeats a name is refused
    with HTTP 400 and an error below that parameter on the re-displayed form,
    before anything is stored. A character that XML 1.0 cannot store, such as
    a control character other than tab, line feed and carriage return, is
    refused the same way in a parameter name or value, in the reason, and in
    an approve or reject comment. A save that fails anyway stores nothing,
    leaves no temporary file behind and is reported as an error above the form,
    not as an error page; a refused approval or rejection leaves the request
    pending (D-72b). The body of a run
    request submission is capped at 100 MB, configurable in bytes with the
    system property `io.jenkins.plugins.batchcontrol.maxRequestBodyBytes`,
    because requesting a run does not require `Item/Build` (D-38a). The cap is
    checked in two stages (D-74). The first is only an early filter: the
    declared `Content-Length`, judged from the headers alone before Batch
    Control reads the form; a body that declares no length (a chunked one, for
    example) passes it. The second, the one that counts, is judged on what the
    request would keep, before anything is stored: each value, with its name,
    counts as the larger of its own size and the size of the uploaded parts it
    was created from. A core `file` value therefore counts as its file
    content, a `base64File` value as its stored Base64 text, a `stashedFile`
    value as the uploaded part it came from, and every other value as its
    stored text. A value or uploaded part whose size cannot be determined is
    refused (fail closed). The reason has its own length limit above and is
    not counted. A submission over the cap at either stage is answered with
    HTTP 413, and Batch
    Control creates nothing and keeps nothing in JENKINS_HOME. It does not
    prevent the upload itself: Jenkins parses a multipart body posted under a
    job URL while it dispatches the URL, before any plugin code runs (D-72a),
    into a new `jenkins-stapler-uploads*` directory under `java.io.tmpdir`.
    Jenkins only marks that directory for deletion when the JVM exits, and
    Java does not delete a directory that still holds files, so the uploaded
    parts written there stay, after Jenkins has stopped as well, until the
    operating system or an administrator removes them. A core `file`
    parameter leaves its upload there for every accepted submission too, on
    Jenkins' own build form as on Batch Control's, because core copies the
    upload and does not delete Stapler's part (the file-parameters plugin's
    values delete theirs). If `java.io.tmpdir` lies inside JENKINS_HOME, these
    leftovers are in JENKINS_HOME as well. The instance-wide limit on such
    uploads is Stapler's
    `org.kohsuke.stapler.RequestImpl.FILEUPLOAD_MAX_SIZE` system property, the
    total size in bytes of one `multipart/form-data` request, which is
    unlimited (`-1`) by default. It applies to every multipart form Stapler
    parses, not only Batch Control's, so leave room for the largest file
    parameter your jobs legitimately take. Beyond these caps there is no
    per-user request rate limit, no cap on concurrent pending requests and no
    limit on the rate of configuration changes; bulk-created requests
    accumulate until the pending timeout clears them.
32. **Performance at volume is unmeasured.** The history, dashboard and
    change-record screens read a whole month bucket into memory on every page
    load, the incident list opens one file per incident, and the Run Requests
    and Grants screens read every run and grant request file on every page
    load (the expiry job, every minute, loads only the open ones). Request
    files are kept until retention deletes the closed requests last active
    before the first kept month (`retentionMonths`, 24 by default), so up to
    that age every one of them is read. SPEC item 6's target of 5,000 runs a
    day has therefore not been measured, and it is not expected to hold at
    that scale until the store gains an index. File parameters add to this.
    The file-parameters plugin's `base64File` keeps the file's content,
    Base64-encoded, inside the parameter value, so a run request with such a
    parameter carries the whole file in its values file,
    `requests/run/<id>.values.xml`, about a third larger than the file. A
    request's typed values live only in that file, next to the request file
    `requests/run/<id>.xml` (D-74). Batch Control does not copy the content
    elsewhere, and it keeps a request's typed values only as long as they are
    needed: the values file is deleted when the approved run starts, when the
    request ends (rejected, cancelled, expired or invalidated), or when the
    queue item of the approved run is cancelled, and the masked display values
    in `<id>.xml` stay as the record (D-72b). The screens, badges, listings and
    periodic jobs read only `<id>.xml` and never the values file; only
    approving, submitting, recovering after a restart and disposing of a
    request read it. Batch Control's
    storage therefore grows with every open request that carries such a file,
    not with every such upload ever made. Core `file` and `stashedFile`
    content stays in its own directory under JENKINS_HOME
    (`fileParameterValueFiles/`, `stashedFileParameterValueFiles/`) until a
    build takes it over. Jenkins core cleans up such a file only for a value
    that reached the queue (when its queue item is cancelled, for example)
    and never sweeps `$JENKINS_HOME/fileParameterValueFiles/` itself, so the
    file of a value that never reaches the queue stays there unless someone
    disposes of it. Batch Control disposes of the files of a run request that
    ends without a run (rejected, cancelled, expired, invalidated, or approved
    but impossible to queue) and of a person's own direct build that run
    control refuses, through any of core's channels: the build form, the
    parameters dialog, `build` and `buildWithParameters` over HTTP, the CLI,
    and a build token. That disposal covers exactly two parameter types: core
    `file` parameters, including another plugin's type built on core's file
    parameter value (a subclass), and the file-parameters plugin's
    `stashedFile`. Another plugin's parameter type that keeps its own
    temporary file is not covered: when a request carrying such a value ends
    without a run, Batch Control leaves that file alone, and it stays until an
    administrator removes it. A `base64File` value keeps no separate file; its
    content is in the request's values file, which is deleted as described
    above (D-74). The file-parameters plugin is an optional dependency: Batch
    Control works without it, and then core `file` is the only file parameter
    type there is. When the queue item of an approved run is cancelled (by
    a user, by clearing the queue, or because its job was deleted), the
    cancelled item's own parameter values delete their files, as for any
    cancelled queue item, and Batch Control deletes the request's values file
    and never submits that run again, not when Jenkins restarts
    either. The request stays `APPROVED` until the approved-run timeout ends
    it as `EXPIRED`, with the reason `Expired: approved but not started within
    <N> minutes; its queued run was cancelled.` When another plugin's queue
    handler refuses an approved run after Batch Control's gate has accepted
    it, nothing is queued: Batch Control releases the run's claim, so the
    request stays `APPROVED` and is submitted again when Jenkins restarts, and
    if it never runs, its files are disposed of when the approved-run timeout
    expires it or it is invalidated (D-72b). Batch
    Control leaves two kinds of refused submission alone: a refused re-run that
    uploads a new file (for example from the rebuild plugin's parameters
    page), and a refused unattended submission that creates file values (for
    example one from parameterized-trigger). Batch Control cannot tell the
    file values of such a submission apart from values it shares with another
    build, whose files must not be deleted, so it does not touch them, and
    their files stay in `fileParameterValueFiles/` or
    `stashedFileParameterValueFiles/` until an administrator removes them
    (D-72).

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

    No window allows **renaming** an item (D-71c, security-36 S-36-01,
    S-36-02; this replaces the folder-only refusal of D-71a). While change
    control is on, renaming a job or a folder of any kind (including a
    multibranch project or an organization folder) is refused whenever a
    window would be what allows it: neither a `CONFIGURE` window on the item,
    nor a `DELETE` window on it combined with a `CREATE` window on its parent
    (core's other rename path), nor the Configure a `CREATE` window's holder
    keeps on the items created through it (D-35c) is enough. Renaming needs
    an administrator, or the user's own (standing) permissions under core's
    rule: `Item/Configure` on the item, or `Item/Delete` on it plus
    `Item/Create` in its parent. The reason is that permissions matched by
    full name are re-pointed by a rename. Windows follow their item to its new
    name (item 11), but under role-strategy the holder's own item roles match
    full names by pattern, so renaming a job into one of their patterns, or a
    folder (which renames everything inside it), would give the holder that
    role on it long after the window ended. Refusing the rename closes that
    escalation, and it also means a window holder cannot re-point windows by
    swapping names. The cost: a user who renamed jobs through a
    `CONFIGURE` window now needs an administrator to do it, or their own
    standing permissions.

    Jenkins' sidebar still shows **Rename** to a window holder, because the
    window does confer `Item/Configure` for everything else; the refusal
    appears on the rename page itself, as a message under the new-name field
    while typing and as a plain refusal page when the rename is submitted, and
    nothing is renamed. Every core rename endpoint (`confirmRename`,
    `doRename`, `checkNewName`) is checked on the decoded request path, so an
    encoded URL form does not get past the refusal. A refused rename, by any
    URL form, is recorded as `GRANT_VIOLATION`; the same refused rename by the
    same user (same item and new name) is recorded once per minute, and repeats
    within that minute go only to the Jenkins log (D-73). Renaming an item is
    therefore, like deleting or moving a folder (items 11 and 44), not
    something a permission window can authorise. When a rename does happen, it
    is recorded as `RENAME`, ends the item's pending requests (item 30), and
    the windows on the item follow it to the new name (item 11). A folder
    rename also changes the full name of everything inside the folder, ends
    the pending run requests of the jobs inside, produces one `MOVE` record
    per descendant job (item 26), and the windows on anything inside it follow
    to the new full names.
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

    - every item an active grant names;
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
    appear on such a job. A user who may see the job but does not hold
    `BatchControl/Request` on it is not offered the rerun form, and a rerun
    submitted anyway is refused without creating a request; `Item/Build` is
    not needed to request (e2e-03 DEF-12, DEF-16, DEF-25, DEF-01; D-38a).

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

<!-- Item 51 was added after 44-50 and sits here by topic. The comment ends the list so that it renders as 51, not 44. -->

51. **A run request whose stored values do not match what the approver sees
    cannot be approved.** A run request keeps the submitted parameter values
    with their types (D-72, item 16), and the approved build must never
    receive values other than the ones the approver saw. Approval, and the
    submission of an approved run, are therefore refused for a request whose
    stored values repeat a parameter name, name different parameters than the
    request shows, or include a value Jenkins can no longer load, for example
    because the plugin that provides that parameter type was removed or
    downgraded while the request was open, or whose values file
    (`requests/run/<id>.values.xml`, item 32) is missing or cannot be read
    at all (D-72b, D-74). A request file written before typed values were
    introduced holds only the masked display values. It is not converted, as
    earlier grant files are not (D-69), and if it has parameters it cannot be
    approved; a request without parameters is unaffected. A refused approval
    leaves the request `PENDING` with the reason shown above the form, and the
    approver can still reject it. An approved request whose values fail this
    check when it is submitted, for example on recovery after a restart, is
    not submitted; it stays `APPROVED` until the approved-run timeout ends it.
    In each case the requester has to submit the run again.

## Moving items

44. **While change control is on, moving an item needs `Item/Delete` on it.**
    A move is treated as deleting the item at the source and creating it at
    the destination, so a user without `Overall/Administer` needs `Item/Delete`
    on the item and `Item/Create` on the destination, each standing or from an
    active window (D-59). The cost: a non-administrator who holds `Item/Move`
    and `Item/Create` standing but not `Item/Delete` can no longer move items
    while change control is on, although plain Jenkins would let them. A
    `DELETE` window on a job lets such a user move it, but the move then
    costs what a delete and recreate would: while run control is also on, a
    job moved by a non-administrator is no longer activated, gets the same
    lock as a newly created job and is recorded as `HELD` naming the move, so
    it does not run unattended until a new activation request is approved
    (D-59a). Administrators' moves keep the job's state. No window can make a
    folder, a multibranch project or an organization folder movable, because
    a window's `DELETE` applies only to a job (item 11, D-71): moving one of
    those needs an administrator or standing `Item/Delete` on it. Likewise no
    window confers `Item/Create` in the Jenkins root or inside a multibranch
    project or organization folder, so a move to one of those destinations
    needs standing `Item/Create` there or an administrator. In both cases the
    refusal says an administrator must make the move instead of suggesting a
    window. The refused move
    changes nothing and is recorded as a `GRANT_VIOLATION`. A user who holds
    `Item/Delete` on two jobs can still swap them by moving them; such a user
    could already delete and recreate them, and with run control on the
    swapped jobs arrive locked and not activated, as recreated ones would. A
    swap does not carry a window from one job to the other: each window
    follows its own job through every move, so a job moved into another
    job's former name does not pick up that job's windows (item 11, D-74).
    With change control off, moves
    behave exactly as in Jenkins.

    The rule governs the folders plugin's Move action, in the UI and over
    REST, which use the same endpoint. Jenkins core offers no way to veto a
    move, so a move made by another plugin that calls `Items.move` directly,
    or by a third-party relocation handler ordered before Batch Control's,
    is outside it. Core itself has no CLI move command, its renames stay
    within the parent folder, and scripts need `Overall/Administer`, which
    is exempt anyway.

## The new job page and pre-filled requests

45. **With the rebuild plugin installed and the new job page enabled, the
    "More actions" menu of a freestyle-type job that has no completed build
    fails to open**, because rebuild's **Rebuild Last** entry has no URL on
    such a job. This affects every such job, controlled or not, until it has
    a completed build; Pipeline jobs without builds open the menu normally.
    On approval-required jobs the menu offers only **Rebuild Last** (the
    rebuild plugin's **Rebuild** is hidden, see item 41); running it is
    refused and recorded like any direct run.

<!-- Item 46 is under "The authorization strategy". The comment ends the list so that 47 and 48 render with their own numbers. -->

47. **On the new job page, core's build button comes first and green, and
    Request Run second.** Core always places its own build button in the
    first app-bar group with the build role and colours it green; plugins
    cannot reorder or recolour it. On a job that requires approval Batch
    Control relabels it **Direct Build (needs approval)**, so **Request Run**
    appears after it, also green. For a user who may request a run, both lead
    to the same Request Run form, with any submitted parameter values filled
    in (D-60). Accepted by the owner (E2E-1 DEF-02).
48. **Pre-filled parameter values travel in the URL.** When a refused build
    submission leads to the Request Run form with the submitted values filled
    in (D-60), the values of non-sensitive parameters are carried in the
    redirect URL's query string. From there they can reach the browser
    history, reverse-proxy and servlet container access logs, and the
    `Referer` header of the next request. Password and other sensitive
    parameters are never carried. Anything typed into a plain string or text
    parameter is not sensitive in Jenkins' sense, so do not put secrets in
    such parameters. Long values are not carried. A value is carried only if
    it is at most 2,000 characters long, and the redirect's URL-encoded query
    (including the `?`, the `&` separators and each `p.<name>=` prefix) is
    capped at 4,000 characters: values are added in the order the parameters
    are defined, and one that would push the query over the cap is left out,
    while a later, shorter one may still fit. The form always opens; a field
    whose value was not carried starts at its default and has to be entered
    again. Files are not carried either: a file submitted with the refused
    build does not reach the Request Run form (issue #115), and the form names
    each file parameter and says to select the file again. The same URL
    mechanism and caps apply when an incident rerun continues on the Request
    Run form (item 16).
50. **The request dialogs on the new job page use a beta core API.** On the
    new job page an action can open a dialog only through
    `Action#getEvent()` returning `DialogEvent`, which core 2.568.x marks
    `@Restricted(Beta)`; the plugin builds with `useBeta` for it (D-70). If a
    later core release changes that API, the request dialogs on the new job
    page may stop opening. The full request pages under `/batch-control/`
    keep working either way.

## Out of scope by design

Bypass by `Overall/Administer`; detecting edits made directly on disk; restarting
or resuming a step *inside* the batch application; and controlling changes to
Pipeline scripts held in Git. None of these are things this plugin attempts.
