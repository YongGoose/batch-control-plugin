*This page is the canonical version. 한국어: [`README.ko.md`](README.ko.md).*

# Batch Control

Run approval, time-boxed change permissions and an append-only audit history for
Jenkins instances that are operated as a **batch execution manager** rather than
as a CI server.

## Why

Plenty of organisations run their nightly and periodic workload on Jenkins:
Spring Batch jobs, data loads, settlement runs, reconciliation scripts. Those
jobs are production, so starting one by hand outside its schedule, or changing
what it does, is an operational change, and it attracts the questions any
production change attracts. Who started it, with which parameters, who agreed to
it, and why? Jenkins answers those only in part. `Item/Build` lets a user start
any job at any moment, `Item/Configure` lets them change one permanently, and the
record of what happened lives in the build, so it disappears when the build is
rotated out.

Batch Control fills the gaps. A manual run of a protected job becomes a request
that a designated approver has to approve before anything reaches the queue; a
configuration change needs a temporary permission that expires on its own; and
runs, decisions, configuration changes and failures are written to a store that
is independent of build retention. Installing the plugin changes nothing by
itself, because both controls are off until an administrator turns them on, and
they are turned on independently.

## The two controls

**Run control** governs starting a build. With it on and "Require approval to
run" set on a job, whoever wants to run that job submits the parameters and a
reason; the designated approver sees exactly those parameters and approves, or
rejects with a reason the requester can read. Only that one approver can decide
the request, not the rest of the approver list and not an administrator, though
the requester may swap the approver while it is still pending. On approval the
plugin queues the build with the stored parameters. No path edits parameters
after approval, and the approval is consumed by a single queue submission, so it
cannot be replayed, re-queued or rebuilt; the attempt is blocked and recorded.

**Change control** governs changing a job. A user who does *not* hold the standing
permission to create, configure or delete one asks for a permission window
instead: a scope (one job, or a folder), some combination of `CREATE`,
`CONFIGURE` and `DELETE`, a duration and a reason. A window **adds** those
permissions to whatever the user already has, for as long as it lasts; it never
takes anything away and it imposes nothing on someone who holds the permission
standing, which is what the "standing change permissions" monitor is for. Once
it is approved they do the work under their own account, with every usual Jenkins
safeguard still in place. What the approver decides is *who* may change *what*, and
*for how long*; it is not an approval of the change itself, which does not exist
yet and is never shown to them. What was actually changed is answered afterwards,
by the change record and its diff. Expiry is decided by comparing the clock at each
permission check, so the window closes with no timer and no revocation step, and
stays closed across a controller restart. A `BatchControl/Manage` holder can
revoke one early.

Recording does not depend on which of the two is on. If either one is on, runs,
changes, decisions and failures are recorded.

## What run control stops, and what it does not

Run control intercepts builds at queue entry and decides on the *cause* Jenkins
attached to the submission. Refused: "Build Now", the `build` and
`buildWithParameters` REST endpoints, `jenkins-cli build`, Pipeline Replay, and a
submission carrying a build token, which Jenkins attributes to no user at all.
Admitted: an approved run request (once), cron and other timer triggers, builds
triggered by an upstream job, SCM triggers, and any build whose cause the plugin
does not recognise.

Two consequences follow, and they are worth stating plainly.

**Whether a build needs approval is decided by what started it, not by how many
times the job has already run.** Run control asks one question of every
submission: did a person start this, or did automation? A person needs an
approved request every time, on the first run and on the hundredth. A timer
trigger needs none, ever, because a scheduled build is not a request and there is
nothing for an approver to decide. So a nightly batch job keeps running on
schedule after you turn run control on, and "approve it once and it is free after
that" is not how it works: the next time a person presses the button, that person
needs another approval. Individual jobs can be made stricter with
`Block cron (timer) triggers` and `Block upstream triggers`, the second of which
takes a list of upstream jobs that may still trigger the job anyway. Nothing
narrows the SCM or unrecognised-cause path.

**Blocking happens only at queue entry.** Nothing the plugin does interrupts a
build that is already running, not a global switch, not a job property, not a
configuration change, and not a permission window expiring mid-build.

A person refused at "Build Now", at a REST `build` call or at the CLI gets an
"approval required" page or CLI message linking to the request form. Two refusals
are silent instead, because neither caller has a screen to read them: Pipeline
Replay, whose UI offers no channel for the message, and a build-token submission,
whose caller is a script reading an HTTP status. Both are logged, and the token
case is also written to the audit history as a blocked attempt, so a refusal nobody
saw is still answerable afterwards. Automation refused by one of the per-job
options is likewise turned away quietly and logged.

## Requirements

Jenkins 2.568.3 or newer, the baseline the plugin is compiled against;
`structs` and `cloudbees-folder`, which the Plugin Manager resolves for you.

The five Batch Control permissions are only visible on authorization strategies
that draw a permission matrix, so with Jenkins' built-in "Logged-in users can do
anything" there is no screen on which to assign them. Install **Matrix
Authorization Strategy** or an equivalent first. Change control needs one more
step beyond that, described below.

## Installation

Once the plugin is published, from **Manage Jenkins → Plugins → Available**,
searching for *Batch Control*. To install a build of your own, upload the `.hpi`
under **Manage Jenkins → Plugins → Advanced settings → Deploy Plugin**. Building
from source needs Maven and JDK 21, the version the CI build uses.

```sh
mvn clean package   # produces target/batch-control.hpi
mvn hpi:run         # a local Jenkins at http://localhost:8080/jenkins
```

## Configuration

### 1. Turn on what you need

**Manage Jenkins → System → Batch Control**, which needs `BatchControl/Manage`
(implied by `Overall/Administer`). Flipping either switch is itself recorded.

| Field | Default | Meaning |
|---|---|---|
| Enable run control | off | The approval gate at queue entry |
| Enable change control | off | Permission windows, the delete veto and the standing-permission monitor |
| Approvers | empty | The user IDs allowed to be designated as an approver |
| Allow administrators to approve their own requests | on | When off, separation of duties applies to administrators too |
| Pending request timeout (hours) | 72 | A pending request expires after this |
| Approved-but-not-run timeout (minutes) | 60 | An approved request that was never queued expires after this |
| Grant duration options (minutes) | 15, 30, 60 | The choices offered on the window request form |
| Maximum grant duration (minutes) | 240 | Upper bound on a custom duration |
| Results that open an incident | FAILURE, UNSTABLE | `ABORTED` can be added |
| Retention period (months) | 24 | Month files older than this are deleted, and the deletion is recorded |

A request cannot be created unless its designated approver is on the Approvers
list, and `BatchControl/Approve` is checked on them again at the moment they
decide.

**Turning change control off is a kill switch, and it is abrupt.** While the switch
is off no window confers anything, so every permission decision is the delegate
strategy's alone, exactly as before the plugin was installed. Flipping it off also
*revokes* every window that is open at that moment, writing one revocation record
per closure naming the account that flipped it. Anyone in the middle of a change
loses the permission to finish it, with no warning and no way back but a new
request. That is the deliberate trade: the alternative was a switch that leaves
windows quietly conferring `Item/Configure` for up to the maximum grant duration
after the control was supposedly turned off. Two things the switch does not do:
the Grants screens keep working while it is off, and a window requested and
approved during that time is not caught by the revocation, so turning the switch
back on brings that window to life for the rest of its duration.

### 2. Select the wrapping authorization strategy

Change control does nothing until this is done, and it is the step most easily
missed: windows get approved and then have no effect at all.

Under **Manage Jenkins → Security → Authorization**, choose **Batch Control
(wrapping)** and select your real strategy, Project-based Matrix Authorization
for instance, as its delegate. Your existing matrix configuration stays inside
the delegate. While an approved window is open the wrapper *adds* that window's
actions, `Item/Create`, `Item/Configure` or `Item/Delete`, on that window's scope,
and passes every other decision through unchanged, so with no active window it
behaves exactly like the delegate alone. It resolves a permission the way Jenkins
itself does, by walking the `impliedBy` chain, so a window also answers the
permissions Jenkins treats as implied by the granted action: on the plugin set this
project is built against, a `CONFIGURE` window additionally confers
`Item/ExtendedRead`, `Credentials/UseItem` and `Run/Replay`, which is worth
knowing before approving one ([Limitations](#limitations)). An administrative
monitor warns if change control is on without this strategy. Run control and
recording do not need it. A window confers its permissions only when both this
strategy is selected *and* change control is on.

> **Do not save the wrapping strategy without a delegate.** It fails closed,
> denies every permission to everyone including administrators, and the only way
> back is editing `$JENKINS_HOME/config.xml` on disk.

### 3. Assign the permissions

| Permission | What it allows |
|---|---|
| `BatchControl/Request` | Create run requests for approval-protected jobs |
| `BatchControl/Approve` | Approve or reject run requests and window requests |
| `BatchControl/RequestGrant` | Request temporary change permissions |
| `BatchControl/ViewHistory` | View the history screens, dashboards and CSV exports |
| `BatchControl/Manage` | Manage the global configuration and revoke windows |

`Manage` is implied by `Overall/Administer` and implies the other four, so
administrators pass every check. A requester typically holds `Overall/Read`,
`Item/Read`, `Item/Build`, `Request` and `RequestGrant`, and deliberately not
`Item/Configure`, since that is precisely what a `CONFIGURE` window is for. An
approver usually gets `Approve` and `ViewHistory` but not `Request`. Note that
`ViewHistory` is broader than its name suggests; see [Limitations](#limitations).

### 4. Configure jobs

A **Batch Control** section appears in the job configuration, with inline help on
each field: `Require approval to run`, the two trigger overrides described above
with their `Allowed upstream jobs` list, and an optional job-level approver list
that narrows the global one. While run control is on, **every newly created job
starts locked**: `Require approval to run`, `Block cron (timer) triggers` and
`Block upstream triggers` all on and the allowed-upstream list empty, whoever
created it and however. Creating a job therefore does not put it into service.
Bringing it into service means turning a switch off in its configuration, and that
change is recorded and, with change control on, needs a permission window.

Values in the creation payload do not survive the lock. An `approvalRequired=false`,
a `blockTimer=false` or an allowed-upstream list in a `config.xml` POST, a CLI
`create-job`, a Job DSL seed or a copied job is overwritten; only the job-level
approver list is carried over, because it can only narrow who may approve. If your
instance generates jobs from scripts this will bite on the first run, silently, so
read [the automation note](docs/LIMITATIONS.md#automation-and-generated-jobs)
before turning run control on.

A working reference configuration, with a Dockerfile, plugin list, security
bootstrap and global settings, lives under [`e2e/`](e2e/). It exists to run the
end-to-end suite rather than as a deployment template, but its init scripts do
show each of these four steps.

## The screens

Everything is under **Batch Control** in the left sidebar, permission-gated
section by section, so a user sees only what they can act on.

**Run Requests** carries each request's stored parameters, reason, requester,
approver, status and decision history, and is where the approver decides. On a
protected job the sidebar carries **Request Run**, which opens the same form with
the job's parameters, while Jenkins' own build entry is relabelled **Direct Build
(needs approval)** so that the two cannot be mistaken for each other: following the
core entry reaches the queue with no approval and is refused. **Request Change
Permission** sits alongside them while change control is on, for a user who does
not already hold `Item/Configure` on the job, and opens the window request form
with that job filled in. **Grants** shows pending window requests, the time left on
an active window, the history of expired ones and a link to request another.
**Run Dashboard** lists every build in the instance with its
cause (`USER`, `TIMER`, `UPSTREAM`, `APPROVED_REQUEST`, `SCM`, `OTHER`), user,
parameters, result and duration, linking approved runs back to the request that
authorised them. **Incidents** collects the failures that opened automatically,
each with the last 100 console lines, and offers acknowledge, resolve, comment and
a rerun request with the original parameters prefilled; the lifecycle runs `OPEN`
→ `ACKNOWLEDGED` → `RESOLVED`, one way only, each transition carrying a user, a
timestamp and a comment. **History** filters runs, incidents, change records and
requests by period, job, user, result and status, exports each as CSV, prefixing
any cell whose first non-whitespace character is `=`, `+`, `-` or `@` so that a
spreadsheet does not evaluate it, and gives a monthly summary.
**Change Records** is the create / configure / delete / rename / move trail,
recorded whatever path the change came through (UI, REST, CLI, Job DSL), with a
unified diff and the window the change was made under, or an explicit note where
there was none.

Records live in `$JENKINS_HOME/batch-control/`, separately from builds, so they
outlive build rotation. They are append-only: no edit or delete API exists, only
retention expiry.

## Limitations

What follows is the part that changes decisions. The complete list is in
[`docs/LIMITATIONS.md`](docs/LIMITATIONS.md).

**Administrators bypass everything.** `Overall/Administer` implies every Batch
Control permission, so the plugin records what administrators do rather than
trying to stop them.

**Turning change control off cuts off work in progress.** The switch revokes every
open permission window the moment it goes off, so a user part-way through a change
loses the permission to finish and has to request a new window once the control is
back on. The revocation is recorded per window. It is also not symmetrical: a
window approved while the switch was off survives the next flip and becomes live
when change control is turned back on.

**A refused configuration change still shows Jenkins' own 403.** Opening or saving
a job configuration without an active window produces the stock "missing the
Job/Configure permission" page. The plugin deliberately does not intercept it, so
that page says nothing about permission windows, and a user who never had a window
sees exactly what a user whose window has just expired sees. What the plugin does
instead is put **Request Change Permission** on the job's sidebar, before the 403
rather than after it, and keep the remaining time, the expiry history and the
re-request link on the Grants screen. Deleting is the exception: the plugin's own
veto message names the grant to request.

**A `CONFIGURE` window confers whatever Jenkins implies from `Item/Configure`.**
On the plugin set this project is built against that means `Item/ExtendedRead`
(which reads `config.xml`), `Credentials/UseItem` and `Run/Replay`, and another
installed plugin can add to the list, since any permission may declare itself
implied by `Item/Configure`. It is the same set a standing matrix entry for
`Item/Configure` confers, the difference being that this one comes from a window
that a non-administrator approved while being shown only the word `CONFIGURE`.
`Run/Replay` is the one to know about: run control still refuses a replay of a job
that requires approval, so it is not a way around the run gate there, but on a job
without run control a window holder can replay a build with a modified Pipeline
script.

**JIT change control works only with matrix-family authorization strategies.**
With **Role-Based Authorization Strategy** selected you get run control and
recording, and no permission windows: wrapping Role Strategy would keep
permission decisions correct but break its own role management screens, so it is
not supported, and an administrative monitor says so when it is in use. Saving
the wrapping strategy **without** a delegate is worse, locking out everyone
including administrators, recoverable only by editing
`$JENKINS_HOME/config.xml` on disk.

**A protected job refused at queue entry fails its caller.** A Pipeline `build`
step that hits the gate ends the upstream job as `FAILURE`, even with
`wait: false`. That is Jenkins' behaviour, not a choice made here.

**Plan for the new-job lock before enabling run control, because it fails
silently.** Every job created while run control is on starts with approval
required and both trigger overrides on, and any value the creation payload supplied
for those is overwritten, so a Job DSL or JCasC definition that pins
`blockTimer: false` is not idempotent against a fresh creation and appears simply to
be ignored. A generated nightly job therefore does not run its first night, and
because an unattended refusal is silent by contract, that looks exactly like a cron
that never fired. The job's own configuration screen is what tells the two apart:
if `Block cron (timer) triggers` is checked, the lock is why. The controller log
also carries one "blocked timer-triggered run" line per refusal. The seed jobs in
this repository's own e2e environment stopped building the first time this landed.

**Secrets survive only as far as detection reaches.** A stored incident log tail
masks the build's own sensitive parameter values and Jenkins `Secret` plaintexts
and nothing else, so a token echoed by a script, a stack trace or a third-party
tool is kept and displayed verbatim. Request parameters, on the other hand, are
masked before they are stored, which means no plaintext secret is ever written
but an approved run submits the mask rather than the original value: jobs with
password parameters cannot be run through approval today.

**`BatchControl/ViewHistory` is an instance-wide audit read.** The history
screens, the dashboard and the CSV exports show every request and run, job names
and parameter values and reasons and decision comments included, to any holder,
with no `Item/Read` check. It also authorizes incident acknowledgement and
resolution, so a read-only auditor account can close incidents.

Out of scope by design: bypass by `Overall/Administer`, detecting edits made
directly on disk, restarting or resuming a step *inside* the batch application,
and controlling changes to Pipeline scripts kept in Git.

## Compared with other approaches

The Pipeline `input` step gates a point *inside* a run that has already started,
works for Pipeline jobs only, and leaves its record in the build. Batch Control
gates the *start* of a run, for any job type, and keeps the record independently
of build retention. Audit Trail, AuditFlow and Job Configuration History record
what happened, and they do it well: Job Configuration History keeps configuration
diffs with the user who made them, and AuditFlow adds a searchable store and an
export. What none of them puts in front of the record is a control plane: a
request, a designated approver, a decision, and a permission that ends by itself.
And matrix and role-based authorization decide a permission by who you are rather
than for how long, where Batch Control wraps whichever of them you configured and
adds permissions that exist only inside an approved window.

## Roadmap

Deliberately not in this release: notifications on requests, decisions, imminent
expiry and failures; a REST API, JCasC support for the global configuration, and
approval events exported to the Audit Log plugin; multi-stage approval chains
beyond the single designated approver; and JIT change control for Role-Based
Authorization Strategy, which needs a different mechanism from the wrapping
strategy.

## Contributing

Issues and pull requests are welcome, and
[`CONTRIBUTING.md`](CONTRIBUTING.md) is the guide: a couple of the conventions
here are unusual enough to be worth reading before a first change. Two matter
even earlier. `docs/SPEC.md` is the functional contract, so if the code and the
spec disagree the code is wrong, and the reasoning behind every settled decision
is in `docs/DECISIONS.md`. `docs/ARCHITECTURE.md` describes the extension points,
the package layout and the on-disk storage format.

```sh
mvn clean verify   # compile, full test suite, SpotBugs; must end with 0 failures and 0 bugs
```

## Reporting security vulnerabilities

Please **do not** open a GitHub issue. Report privately in the Jenkins
[**SECURITY** project](https://issues.jenkins.io/secure/CreateIssueDetails!init.jspa?pid=10180&issuetype=10103)
under the `specific-plugin` component, naming the plugin in the summary because it
has no Jira component of its own until it is hosted in the `jenkinsci`
organisation. Such issues are visible only to the reporter and the Jenkins
security team. If you cannot use the tracker, mail
`jenkinsci-cert@googlegroups.com` and the team will file on your behalf. Full
policy: <https://www.jenkins.io/security/reporting/>.

## License

MIT. See [`LICENSE`](LICENSE).
