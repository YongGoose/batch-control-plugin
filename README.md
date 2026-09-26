# Batch Control

Run approval, just-in-time change permissions, and an append-only audit history
for Jenkins instances that are operated as a **batch execution manager** rather
than as a CI server.

Installing the plugin changes nothing. Every control is off until an
administrator turns it on, and the two controls are switched on independently.

---

## The problem

Many organisations use Jenkins to run their nightly and periodic batch workload —
Spring Batch jobs, data loads, settlement runs, reconciliation scripts. Those
jobs are production. Starting one by hand out of schedule, or changing what one
does, is an operational change, and the questions asked afterwards are the
questions asked about any production change: *who started this, with which
parameters, who agreed to it, and why?*

Jenkins by itself answers those questions only partially. A user with
`Item/Build` can start any job at any moment, a user with `Item/Configure` can
change any job permanently, and the record of what happened lives in the build,
so it disappears when the build is rotated out.

Batch Control adds the missing pieces: a manual run of a protected job becomes a
**request** that a designated approver has to approve before anything is queued; a
configuration change requires a **time-boxed permission** that expires by itself;
and every run, approval decision, configuration change and failure is recorded in
a store that is independent of build retention.

---

## Two kinds of control

The plugin has two switches, and they solve different problems. Reading them as
one thing is the most common misunderstanding.

### Run control — per-run approval

Governs *starting a build*. When it is on and a job is marked "Require approval
to run", a person who wants to run that job submits a run request with the
parameters and a reason. The designated approver sees exactly those parameters
and either approves or rejects with a reason. On approval the build is queued by
the plugin with the stored parameters, unchanged — there is no path that edits
parameters after approval; changing them means a new request.

An approval is attached to **one run request**, not to the job, and it is
**consumed by one queue submission**. It cannot be replayed, re-queued or
rebuilt; an attempt to do so is blocked and written to the audit history as a
`MARKER_REUSE_BLOCKED` record.

### Change control — just-in-time permission windows

Governs *changing a job*. When it is on, a user who needs to create, configure or
delete a job requests a temporary permission: a scope (one job, or a folder), a
set of actions (`CREATE` / `CONFIGURE` / `DELETE`), a duration and a reason. Once
an approver approves it, the requester does the work **with their own account**
inside that window, with all the usual Jenkins safeguards in place. When the
window's expiry time passes, the permission is gone from the next permission
check onwards — no timer, no revocation step, and it stays gone across a
controller restart.

Recording is independent of both switches: if **either** switch is on, runs,
changes, approvals and failures are recorded.

---

## What is blocked and what is not

This is the part worth reading twice.

Run control intercepts builds at **queue entry**, and it distinguishes *a person
pressing a button* from *automation firing*. With run control on and "Require
approval to run" set on the job:

| How the build was started | Result | Adjustable per job? |
|---|---|---|
| "Build Now" in the UI | **Blocked** — the sidebar link becomes "Request Run" | no |
| `POST /job/<name>/build`, `/job/<name>/buildWithParameters` (REST) | **Blocked** | no |
| `jenkins-cli build <name>` (CLI) | **Blocked** | no |
| Pipeline **Replay** | **Blocked** | no |
| An approved run request (queued by the plugin) | **Allowed**, once per request | no |
| cron / `TimerTrigger` | **Allowed** | yes — `Block cron (timer) triggers` |
| An upstream job (`build` step, post-build trigger) | **Allowed** | yes — `Block upstream triggers`, plus `Allowed upstream jobs` |
| SCM trigger | **Allowed** | no |
| Any other, unrecognised cause | **Allowed** (the pass is logged) | no |

Two consequences follow, and they are the reason the table exists:

> **A scheduled batch job keeps running on schedule when you turn run control
> on.** Approval is what a *person* needs in order to intervene out of band. The
> nightly run is not a request, it is not approved by anyone, and it is not
> consumed — it simply passes, indefinitely. If you want a periodic job to stop
> as well, set `Block cron (timer) triggers` on it or disable the job.

> **Blocking happens only at queue entry.** Nothing the plugin does interrupts a
> build that is already running — not flipping a global switch, not toggling a
> job property, not changing the job's configuration, and not a permission window
> expiring mid-build.

When a *person-initiated* start is blocked, they get an "approval required" page
or CLI message with a link to the request screen — never a silent failure.
Automation-initiated starts that are blocked (`Block cron (timer) triggers`, a
disallowed upstream job) are refused quietly and logged.

---

## Features

Run control

- Per-run request and approval, with the reason mandatory on both the request and
  a rejection. The requester can see who rejected, when, and why.
- Approvers are an explicit list of user IDs in the global configuration; the
  requester picks one of them. Only that designated approver can decide the
  request — no one else on the list, and no administrator, can decide it for
  them. Until a decision is made the requester can change the approver, and the
  change is recorded.
- A job may narrow the approver list further (`Job-level approvers`).
- Requests expire: pending requests after `pendingTimeoutHours`, approved but
  unstarted requests after `approvedRunTimeoutMinutes`. The requester can cancel
  a request while it is still pending.
- If the target job is renamed or moved while a request is open, the request ends
  as `INVALIDATED` rather than executing against a different job.

Change control

- Time-boxed permission grants, scoped to a job or a folder, for any combination
  of `CREATE`, `CONFIGURE` and `DELETE`.
- Expiry is decided by comparing the clock at each permission check, so it
  survives restarts and needs no scheduler.
- A `BatchControl/Manage` holder can revoke an active grant immediately.
- An administrative monitor warns when users hold standing `Item/Configure`,
  `Item/Create` or `Item/Delete` outside any grant, which is the situation that
  makes change control pointless.

Audit history

- Every build is recorded — Freestyle and Pipeline alike — with job, cause
  (`USER` / `TIMER` / `UPSTREAM` / `APPROVED_REQUEST` / `SCM` / `OTHER`), user,
  parameters, result, duration, and who aborted it when Jenkins recorded that.
- Every job create / configure / delete / rename / move is recorded whatever path
  it came through (UI, REST `config.xml`, CLI, Job DSL), with a unified diff for
  configuration changes and a link to the grant that authorised the work — or an
  explicit "no grant" when there was none.
- `FAILURE` and `UNSTABLE` results open an **incident** automatically, with the
  last 100 console lines attached. Each incident transition carries a user, a
  timestamp and a comment. A
  rerun can be requested straight from the incident, and a successful rerun is
  linked back to it. (Incident status wording: `OPEN`, then `ACKNOWLEDGED`, then
  `RESOLVED`; there is no way back.)
- Records live in `$JENKINS_HOME/batch-control/`, separate from builds, so they
  outlive build rotation. They are append-only: there is no edit or delete API,
  only retention expiry.
- Filtering by period, job, user, result and status; a monthly summary; and CSV
  export for every list.

---

## Requirements

| | |
|---|---|
| Jenkins | 2.568.3 or newer (the baseline the plugin is compiled against) |
| Plugin dependencies | `structs`, `cloudbees-folder` — resolved automatically by the Plugin Manager |
| Authorization strategy | A matrix-style strategy is needed in practice; see [Configuration](#2-select-the-wrapping-authorization-strategy) |

The five Batch Control permissions are exposed to authorization strategies that
render a permission matrix. With Jenkins' built-in "Logged-in users can do
anything" or "Anyone can do anything" there is no screen on which to assign them,
so install **Matrix Authorization Strategy** (or an equivalent) first. Change
control additionally requires this plugin's own wrapping strategy to be selected
— see below.

---

## Installation

From the update centre, once the plugin is published: **Manage Jenkins → Plugins
→ Available**, search for *Batch Control*, install.

To install a build of your own: build the `.hpi` and upload it under **Manage
Jenkins → Plugins → Advanced settings → Deploy Plugin**.

```sh
mvn clean package          # produces target/batch-control.hpi
mvn hpi:run                # a local Jenkins at http://localhost:8080/jenkins
```

Building from source needs Maven and a JDK matching the versions the CI build
uses (JDK 21).

After installation nothing is different: both switches default to off, and with
them off the plugin blocks nothing and shows none of its job-level UI.

---

## Configuration

### 1. Turn on what you need

**Manage Jenkins → System → Batch Control.** Requires `BatchControl/Manage`
(implied by `Overall/Administer`). Toggling either switch is itself recorded as a
`CONFIG_TOGGLE` change record.

| Field | Default | Meaning |
|---|---|---|
| Enable run control | off | Turns on the approval gate at queue entry |
| Enable change control | off | Turns on JIT permission windows and delete vetoing |
| Approvers | empty | The user IDs allowed to be designated as an approver |
| Allow administrators to approve their own requests | on | When off, separation of duties applies to administrators too |
| Pending request timeout (hours) | 72 | A pending request expires after this |
| Approved-but-not-run timeout (minutes) | 60 | An approved request that was never queued expires after this |
| Grant duration options (minutes) | 15, 30, 60 | The choices offered on the grant request form |
| Maximum grant duration (minutes) | 240 | Upper bound on a custom duration |
| Results that open an incident | FAILURE, UNSTABLE | `ABORTED` can be added |
| Retention period (months) | 24 | Month files older than this are deleted, and the deletion is recorded |

A request cannot be created unless its designated approver is on the Approvers
list **and** holds `BatchControl/Approve` at the moment of the decision — both are
checked, and the second is checked again at decision time.

### 2. Select the wrapping authorization strategy

**Change control does nothing until you do this.** It is the step most easily
missed: grants get approved and then have no effect at all.

**Manage Jenkins → Security → Authorization** → choose **`Batch Control
(wrapping)`**, and select your real strategy (for example Project-based Matrix
Authorization) as its delegate. Your existing matrix configuration is kept
inside the delegate; the wrapper only *adds* `Item/Create`, `Item/Configure` and
`Item/Delete` while an approved grant window is open, and delegates every other
decision unchanged. With no active grant it behaves exactly like the delegate
alone.

If change control is on and this strategy is not selected, an administrative
monitor says so on the manage screen.

> **Do not save the wrapping strategy without a delegate.** With no delegate it
> fails closed and denies every permission to everyone, administrators included,
> and the only way back is to edit `$JENKINS_HOME/config.xml` on disk.

Run control and recording do **not** need this strategy.

### 3. Assign the permissions

Five permissions appear in a **Batch Control** group on the authorization matrix:

| Permission | What it allows |
|---|---|
| `BatchControl/Request` | Create run requests for approval-protected jobs |
| `BatchControl/Approve` | Approve or reject run requests and grant requests |
| `BatchControl/RequestGrant` | Request temporary change permissions (JIT grants) |
| `BatchControl/ViewHistory` | View the history screens, dashboards and CSV exports |
| `BatchControl/Manage` | Manage the global configuration and revoke grants |

`Manage` is implied by `Overall/Administer`, and the other four are implied by
`Manage`, so administrators pass every check.

A useful shape for a requester is `Overall/Read`, `Item/Read`, `Item/Build`,
`BatchControl/Request`, `BatchControl/RequestGrant` — and deliberately **no**
`Item/Configure`, since that is precisely what a `CONFIGURE` grant is for. An
approver typically gets `BatchControl/Approve` and `BatchControl/ViewHistory`,
and not `BatchControl/Request`. Note that `ViewHistory` is broader than its name
suggests — see [Known limitations](#known-limitations).

### 4. Configure jobs

A **Batch Control** block appears in the job configuration:

| Field | Default | Meaning |
|---|---|---|
| Require approval to run | see note | A person-initiated run needs an approved request |
| Block cron (timer) triggers | off | Also stop `TimerTrigger` builds |
| Block upstream triggers | off | Also stop upstream-triggered builds |
| Allowed upstream jobs | empty | Only meaningful with the above on: the upstream jobs that may still trigger this job. Empty means *no* upstream job may. |
| Job-level approvers (optional restriction) | empty | Narrows the global approver list for this job |

**Note on the default.** While run control is on, **every newly created job
starts with "Require approval to run" enabled**, regardless of who created it or
how. Read the automation caveat in [Known limitations](#known-limitations) before
you turn run control on in an instance that generates jobs from scripts.

A working reference configuration — Dockerfile, plugin list, security bootstrap
and global settings — lives under [`e2e/`](e2e/). It exists to run the
end-to-end suite, not as a deployment template, but it is a real, working setup
and the init scripts show each of the four steps above.

---

## The screens

Everything lives under **Batch Control** in the left sidebar
(`/batch-control/`). Sections are permission-gated, so a user sees only what
they can act on.

**Run Requests** — the request list and each request's detail: job, parameters as
stored, reason, requester, designated approver, status and decision history. The
approver approves or rejects here; a rejection needs a reason. A requester can
change the approver or cancel while the request is pending. On a protected job,
the sidebar carries **Request Run** in place of Build Now, which opens the same
form with the job's parameters.

**Grants** — grant requests and active grants: scope, actions, duration and
reason on the request; remaining time on the active window, the history of
expired windows, and a link to request a new one. `Manage` holders can revoke an
active grant.

**Run Dashboard** — every build across the instance, with cause classification,
user, parameters, result, duration and who aborted it. Approved runs link back
to the request that authorised them. Defaults to the last 7 days, 50 per page.

**Incidents** — the failures that opened automatically, with the tail of the
console log. Acknowledge, resolve, comment, or request a rerun with the original
parameters prefilled.

**History** — period, job, user, result and status filters over four kinds of
record: runs, incidents, change records and requests. Each has a CSV export
(`runs.csv`, `incidents.csv`, `changes.csv`, `requests.csv`); values that would
otherwise be read as spreadsheet formulas are neutralised. A monthly summary
gives run counts, success / failure / unstable counts, incident counts and
approval / rejection counts.

**Change Records** — the create / configure / delete / rename / move trail, with
the unified diff for configuration changes and the grant each change was made
under, where there was one.

---

## Known limitations

Nothing here is hidden, because each of these will otherwise be discovered in
production.

### Scope of control

1. **Administrators bypass every control.** `Overall/Administer` implies every
   Batch Control permission and every Jenkins permission. The plugin does not try
   to stop administrators; it records what they do.
2. **Only causes the plugin recognises are classified.** A build started by a
   trigger plugin with its own `Cause` type — a generic webhook trigger, for
   instance — is not recognised as person-initiated and therefore **passes** the
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

### The authorization strategy

7. **JIT change control works only with matrix-family authorization
   strategies.** With **Role-Based Authorization Strategy** as the strategy, only
   **run control and recording** work; permission windows do not. Wrapping Role
   Strategy leaves permission decisions correct but breaks its own role
   management screens, so it is not supported. An administrative monitor says so
   when Role Strategy is in use. Support for it is a possible future item, not a
   present one.
8. **Change control is permission-based, not save-based.** Jenkins offers no way
   to intercept the job configuration "Save" itself, so if the wrapping strategy
   is not selected, change control has no effect at all — only the monitor
   warning tells you.
9. **A wrapping strategy saved without a delegate locks everyone out**, including
   administrators, and is recoverable only by editing
   `$JENKINS_HOME/config.xml`.
10. **Grants must name a concrete job or folder.** There is no instance-wide
    grant, which means a grant can confer `Item/Create` only inside a named
    folder, never at the Jenkins root.
11. **The "standing change permissions" monitor is best-effort.** Its verdict is
    cached for up to five minutes and it deliberately ignores administrators, so
    it is a warning, never an enforcement point.

### Automation and generated jobs — read this before enabling run control

12. **While run control is on, every newly created job starts with
    `approvalRequired=true`** — whatever the creation path (UI, REST, CLI, Job
    DSL, a seed job) and whoever the creator is. An explicit
    `approvalRequired=false` in the creation payload is overwritten. Turning the
    control off afterwards means editing the job, which is itself a recorded
    change; that recorded path is the intended way out.

    **If your instance generates jobs from scripts, plan for this.** A Job DSL or
    JCasC definition that pins `approvalRequired: false` is not idempotent
    against a *fresh* creation: the first seed run creates the job controlled, and
    only a second run (an update, not a creation) clears it. A generated job that
    must run unattended needs that second pass, and the pass is recorded.

    This is not theoretical. The seed jobs in this repository's own e2e
    environment stopped building silently the first time this default landed —
    including the ones whose whole purpose was to run unattended — and the fix
    was to make the seed script clear the property right after creating them.
    Note that automatic builds are not what breaks: timer, upstream and SCM
    causes still pass. What stops is anything a person has to press.

13. **Branch jobs generated by a multibranch project are exempt** from that
    default. They have no configuration screen, so there would be no way to turn
    the control off, and re-indexing regenerates their configuration anyway.
    Their runs are still recorded.

### Secrets

14. **Console-log masking has a detection limit.** When an incident's log tail is
    stored, the build's own sensitive parameter values and Jenkins `Secret`
    plaintexts are masked. **Anything else a secret is printed by — a token
    echoed by a script, a stack trace, a third-party tool — is not detected and
    is stored and displayed verbatim** to anyone holding `ViewHistory`. Generic
    secret-pattern detection was deliberately rejected: it gives false confidence
    and still misses things.
15. **A job with secret parameters cannot be rerun faithfully.** Request
    parameters are masked before they are stored, so no plaintext secret is ever
    written — but an approved run, and a rerun prefilled from an incident,
    therefore submit the mask rather than the original value. Approval-based
    execution of jobs with password parameters is not usable today.
16. **Stored configuration snapshots are not masked.** The diff shown in a change
    record is masked, but `batch-control/snapshots/<job>.xml` keeps the raw
    `config.xml`. Secrets inside it are Jenkins-encrypted exactly as they are in
    `$JENKINS_HOME/jobs/*/config.xml` — the same protection, on the same disk, and
    no more.
17. **A change that touched only secret values carries an explanatory note
    instead of a diff**, because both sides are masked identically and a real
    diff would be empty.

### Visibility and permissions

18. **`BatchControl/ViewHistory` is an instance-wide audit read, and it is not
    filtered per job.** The history screens, the dashboard and the CSV exports
    show **every** request and run — job names, parameter values, reasons,
    requesters, approvers, decision comments — to any holder, with no `Item/Read`
    check and no ownership filter. This is a different rule from the Run Requests
    and Grants screens, which *are* filtered per object. Treat `ViewHistory` as
    what it is: full visibility of the audit trail.
19. **`ViewHistory` also authorizes incident state changes.** Acknowledging,
    resolving and commenting on an incident are gated on `ViewHistory`, so a
    read-only auditor account can also close incidents. Requesting a rerun
    correctly needs `Request`.
20. **Request visibility follows the job's own read boundary.** A run request is
    visible to `Manage` holders, its requester, its designated approver, and
    anyone with `Item/Read` on the target job. In an instance where `Item/Read`
    is granted broadly, reasons and parameter values are broadly visible.
21. **An `Approve` holder who is not the designated approver sees nothing** of
    that request — which is consistent, since only the designated approver can
    decide it. Approver absence is handled by the requester changing the approver
    before a decision is made; there is no delegation or deputy chain.
22. **Grant requests have no approver change.** Unlike run requests, changing the
    approver on a pending grant request means cancelling it and creating a new
    one.
23. **Active grants are visible only to their own holder** and to `Manage`
    holders.
24. **Approver accounts must map one-to-one to real people.** The plugin can only
    compare user IDs. One person holding two accounts — requesting as one,
    approving as the other — satisfies the two-person rule and is recorded as a
    normal, non-self approval. Keeping accounts and the approver list honest is an
    organisational control, not something a plugin can enforce.

### Records and screens

25. **A folder rename produces one `MOVE` record per descendant job**, plus a
    `RENAME` for the folder itself. This is accurate — every child's full name
    did change — but it means one rename can generate a large number of records.
26. **The expired-window denial page is Jenkins' own.** Saving a configuration
    after a `CONFIGURE` window has expired gives the stock Jenkins 403 page. The
    plugin deliberately does not intercept it: Jenkins does not offer that as an
    extension point, and intercepting it would mean taking over *every* permission
    denial in the instance. The expiry notice, the history of expired windows and
    the re-request link are on the Grants screen instead, and the remaining time
    is shown there so you can renew before expiry. **Configuration you were
    editing is not restored** — restoring it would mean storing a change that was
    just judged unauthorised.
27. **Monthly summary edge cases** (current behaviour, not yet ratified): an
    `ACKNOWLEDGED` incident is counted in neither the open nor the resolved
    column, and a request that was approved and then expired or was invalidated
    is counted in neither the approved nor the rejected column.
28. **The Grants section is visible even with change control off**, because the
    authorization strategy is deliberately independent of the switch. The Role
    Strategy notice likewise appears with both switches off.
29. **No rate limiting.** There is a size cap on a reason (4,000 characters) and
    on each string parameter value (10,000 characters), but no per-user request
    rate limit and no cap on concurrent pending requests; bulk-created requests
    accumulate until the pending timeout clears them. Likewise nothing limits the
    rate of configuration changes, so a burst of saves inside a window produces a
    burst of diff and snapshot writes against a single store lock.

### Out of scope by design

Bypass by `Overall/Administer`; detecting edits made directly on disk; restarting
or resuming a step *inside* the batch application; and controlling changes to
Pipeline scripts held in Git. None of these are things this plugin attempts.

---

## Roadmap

Not in this release, and deliberately so:

- **Notifications** on request creation, decision, imminent grant expiry and
  failures — email first, with an extension point for Slack and others.
- **A REST API and external integrations** — requests, decisions and history over
  HTTP, global configuration through JCasC, and approval events exported to the
  Audit Log plugin.
- **Multi-stage approval chains**, extending the single designated approver.
- **JIT change control for Role-Based Authorization Strategy**, which needs a
  different mechanism from the wrapping strategy used for matrix-family
  strategies.

---

## How this differs from similar plugins

- **The Pipeline `input` step** gates a point *inside* a run that has already
  started, is available to Pipeline jobs only, and leaves its record inside the
  build. Batch Control gates the *start* of a run, for any job type, and keeps
  the record independently of build retention.
- **Audit-trail style plugins** record what happened. They have no notion of a
  request, a designated approver, a decision, or a permission that expires.
  Batch Control is a control plane whose audit history is a by-product.
- **Matrix and role-based authorization** assign standing permissions. Batch
  Control wraps whichever strategy you configured and adds permissions that exist
  only inside an approved window, delegating every other decision to it.

---

## Contributing

Issues and pull requests are welcome. **[`CONTRIBUTING.md`](CONTRIBUTING.md) is
the guide** — it covers the build, the repository layout, the identifier
vocabulary the documents and commit messages use, the test conventions, the
end-to-end environment, and the pitfalls that have already cost this project
time. Please read it before your first change; a couple of the conventions are
unusual.

Two things are worth knowing even before that:

- `docs/SPEC.md` is the functional contract. If the code and the spec disagree,
  the code is wrong. A change in behaviour is a change to the spec first, and
  the reasoning for every settled design decision is recorded in
  `docs/DECISIONS.md` — please read the relevant entry before proposing that a
  decision be reversed.
- `docs/ARCHITECTURE.md` describes the extension points, the package layout and
  the on-disk storage format.

To confirm a fresh clone builds (JDK 21, Maven 3.9.16 or newer; about ten
minutes):

```sh
mvn clean verify   # compile, full test suite, SpotBugs — must end with 0 failures and 0 bugs
mvn hpi:run        # local Jenkins at http://localhost:8080/jenkins
```

Anything beyond that — running one class, the expected test count, the
environment setup, the Docker-based end-to-end environment in `e2e/`, and what a
pull request needs — is in [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Reporting security vulnerabilities

Please **do not** open a GitHub issue for a security vulnerability. Jenkins
plugin vulnerabilities are reported privately through the Jenkins project's
issue tracker, in the **SECURITY** project, using the `specific-plugin`
component and naming the plugin in the issue summary:

<https://issues.jenkins.io/secure/CreateIssueDetails!init.jspa?pid=10180&issuetype=10103>

Such issues are visible only to the reporter and the Jenkins security team. If
you cannot use the tracker, mail `jenkinsci-cert@googlegroups.com` and the
security team will file the issue on your behalf. The full policy is at
<https://www.jenkins.io/security/reporting/>.

*(Procedure verified against <https://www.jenkins.io/security/reporting/> on
2026-09-26. Until the plugin is hosted in the `jenkinsci` organisation it has no
Jira component of its own, so name it in the summary.)*

## License

MIT. See [`LICENSE`](LICENSE).
