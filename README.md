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
rejects with a reason the requester can read. A request may name more than one
eligible approver; whichever of them decides first closes it, and nobody
outside that set, not the rest of the approver list and not an administrator,
can still decide it afterwards, though the requester may change the designated
approver(s) while it is still pending. On approval the
plugin queues the build with the stored parameters. No path edits parameters
after approval, and the approval is consumed by a single queue submission, so it
cannot be replayed, re-queued or rebuilt; the attempt is blocked and recorded.
Run control also governs whether a job may run *unattended* at all: a job
created while run control is on does not run on a timer, an upstream trigger,
an SCM trigger or a webhook until an approver has activated it
([Activation](#activation-putting-a-job-into-service)).

**Change control** governs changing a job. A user who does *not* hold the standing
permission to create, configure or delete one asks for a permission window
instead: a scope (one job, or a folder), some combination of `CREATE`,
`CONFIGURE` and `DELETE`, a duration and a reason. A window **adds** those
permissions to whatever the user already has, for as long as it lasts; it never
takes anything away and it imposes nothing on someone who holds the permission
standing, which is what the "standing change permissions" monitor is for. Deleting
is the one exception: while change control is on, deleting a job needs an active
`DELETE` window even from a user whose standing permissions would allow it, and
only administrators are not vetoed. Moving an item between folders (the folders
plugin's `Item/Move`) is treated as deleting it here and creating it there: while
change control is on, a user without `Overall/Administer` can move an item only
if they hold `Item/Delete` on the item and `Item/Create` at the destination, each
either standing or from an active window, and a `CREATE` window's name
restriction is matched against the moved item's name. A refused move changes
nothing, tells the user why and is recorded as a `GRANT_VIOLATION`. While run
control is also on, a job moved by a non-administrator arrives the way a newly
created job does: not activated and locked, recorded as `HELD`, so it needs a
new activation before it runs unattended again. The rule covers the folders
plugin's Move action (UI and REST); the cost and scope are in
[Limitations](docs/LIMITATIONS.md#moving-items). Once a window
is approved its holder does the work under their own account, with every usual Jenkins
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
Admitted: an approved run request (once) and, **on an activated job only**, cron
and other timer triggers, builds triggered by an upstream job, SCM triggers,
webhooks, submissions from scripts and plugin code, and any other build whose
cause is not a person acting now. An automatic retry (naginator and the like)
counts as unattended even when the build it retries was started by a person.

Two consequences follow, and they are worth stating plainly.

**Whether a build needs approval is decided by what started it, not by how many
times the job has already run.** Run control asks one question of every
submission: did a person start this, or did automation? A person needs an
approved request every time, on the first run and on the hundredth. Automation
needs no run request, because a scheduled build is not a request and there is
nothing for an approver to decide per run; what it needs instead is that the job
is activated, which is decided once per job, not per run. So a nightly batch job
that already exists keeps running on schedule after you turn run control on, and
"approve it once and it is free after that" is not how manual runs work: the next
time a person presses the button, that person needs another approval. Individual
jobs can be made stricter with `Block cron (timer) triggers` and
`Block upstream triggers`, the second of which takes a list of upstream jobs that
may still trigger the job anyway. Nothing narrows the SCM or other unattended
paths on an activated job beyond activation itself.

### Activation: putting a job into service

While run control is on, creating a job does not put it into service. A job
created while run control is on starts **not activated**: no unattended cause
(timer, upstream, SCM, webhook, script or plugin code, automatic retry) starts
it until an approver approves an `ACTIVATE` request for it. This holds whatever
the job's configuration says, for every user including administrators: clearing
`Require approval to run`, `Block cron (timer) triggers` or
`Block upstream triggers`, or removing the Batch Control job property, does
**not** activate a job, and no configuration write path (web form, REST
`config.xml`, CLI, script, JCasC) can. Activation is stored by the plugin,
outside the job configuration.

The job's own page says whether the job is activated, not activated or on hold,
and to a holder of `BatchControl/Request` offers a **Request activation** link
(or, on an activated job, **request a hold**). The request carries a reason and
one or more designated approvers and is decided exactly like a run request, on
the **Activations** screen. Approval writes an `ACTIVATED` change record.
Putting a job **on hold** (`HOLD`) is likewise a request that needs approval;
an approved hold marks the job not activated and writes a `HELD` record. For an
immediate stop, Jenkins' own **Disable Project** and the global run-control
switch remain available. An activation survives configuration edits, renames
and moves, and is removed when the job is deleted.

Existing schedules are not interrupted:

- every job and folder present when this version is first installed is recorded
  as activated (`activatedBy = (upgrade)`), once;
- a job created while run control is **off** is recorded as activated at
  creation (`activatedBy = (uncontrolled)`), so turning run control on later
  never stops a schedule created in between;
- with run control off, nothing is gated at all.

Computed folders (multibranch projects, organization folders) carry activation
for their children: a computed folder created while run control is on starts
not activated, the `ACTIVATE`/`HOLD` request is made on the folder, and a branch
or child job runs unattended only if its nearest computed-folder ancestor is
activated. The child's page names that folder to viewers who may read it.

**Blocking happens only at queue entry.** Nothing the plugin does interrupts a
build that is already running, not a global switch, not a job property, not a
configuration change, and not a permission window expiring mid-build.

A person refused at a REST `build` call, at the CLI, or at the build link of a
job with parameters gets an "approval required" page or CLI message linking to
the request form. The build link of a job without parameters is the exception:
Jenkins shows only its own toast, "Failed to schedule build. Reload the page and
try again.", and the approval notice on the job page is where the reason is
([Limitations](#limitations)). Some
refusals have no screen to read them at the time: Pipeline Replay, whose UI
offers no channel for the message; a build-token submission, whose caller is a
script reading an HTTP status; and a timer or upstream trigger turned away by
one of the per-job options or by activation. None of these are untraceable
afterwards: each is logged and writes a change record to the audit history. A
blocked build-token attempt gets its own record. A person's refused Replay,
Retry or Rebuild gets its own `TRIGGER_BLOCKED` record per attempt, naming the
build it re-runs; a repeat of the same attempt within a minute is merged, and
at most 20 are listed per user in any 10 minutes. The next one writes a summary
record saying that further refusals are counted, not listed, and when the 10
minutes end a closing record gives their number and the builds. No record is
ever rewritten. A refused timer or upstream submission, or any other
unattended submission refused because the job is not activated, is coalesced
into at most one `TRIGGER_BLOCKED` record per job and cause per hour.
While a job's `Block cron (timer) triggers` or `Block upstream triggers` switch
is on, that job's own page also shows a notice naming it to anyone who can read
the job.

## Requirements

Jenkins 2.568.3 or newer, the baseline the plugin is compiled against;
`cloudbees-folder` and `ionicons-api`, both required, which the Plugin Manager
(or Deploy Plugin, when installing a build of your own) resolves for you.

The integrations with these plugins are optional: Batch Control loads without
them. If one of them is installed, though, it must be at least the version
Batch Control is compiled against, which Jenkins enforces when loading plugins:

| Optional plugin | Minimum version |
|---|---|
| `matrix-auth` | 3.3 |
| `role-strategy` | 918.v91e5468d8db_2 |
| `configuration-as-code` | 2121.v86fe99d4b_b_a_b_ |
| `mailer` | 534.v1b_36f5864073 |
| `rebuild` | 338.va_0a_b_50e29397 |

If an older version of any of these is installed, Batch Control fails to load
until that plugin is upgraded. Installing Batch Control from **Manage Jenkins →
Plugins → Available** offers the needed upgrade along with it. Uploading the
`.hpi` through **Deploy Plugin** does not, so upgrade those plugins first.

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

**Holding `BatchControl/Manage` without `Overall/Administer`? Open
`/batch-control-configuration/`**, or **Batch Control → Configuration**. Manage
Jenkins is not open to you.

The settings are in two places, with the same fields and the same checks:

- **Batch Control → Configuration** (`/batch-control-configuration/`), the
  entry in the sidebar of the Batch Control page ([The screens](#the-screens)
  says how to reach that page). It needs only `BatchControl/Manage`, so a
  user who holds that permission but not `Overall/Administer` opens and saves the
  configuration here. The same page is listed as **Batch Control** on
  **Manage Jenkins** for users who can open Manage Jenkins.
- The Batch Control section of **Manage Jenkins → System**. That is Jenkins'
  own page and needs `Overall/Manage`, so a holder of `BatchControl/Manage`
  alone gets 403 there.

`BatchControl/Manage` is implied by `Overall/Administer`. Every save that changes
something is recorded, whichever page it was saved from: flipping either switch
writes a `CONFIG_TOGGLE` change record, and a change to any other field (the
approver list included) writes one `CONFIG_CHANGE` record naming the user and
each changed field with its old and new value. Installing or reverting a Batch
Control authorization strategy (step 2) through Batch Control's own buttons
writes a `STRATEGY_CHANGE` record; changing the strategy directly on
**Manage Jenkins → Security** writes no Batch Control record. A
save that changes nothing writes nothing.

| Field | Default | Meaning |
|---|---|---|
| Enable run control | off | The approval gate at queue entry |
| Enable change control | off | Permission windows, the delete veto and the standing-permission monitor |
| Approvers | empty | The user IDs allowed to be designated as an approver |
| Allow administrators to approve their own requests | on | When off, separation of duties applies to administrators too |
| Pending request timeout (hours) | 72 | A pending request expires after this |
| Approved-but-not-run timeout (minutes) | 60 | An approved request that was never queued expires after this |
| Grant duration options (minutes, comma-separated) | 15, 30, 60 | The choices offered on the window request form |
| Maximum grant duration (minutes) | 240 | Upper bound on a custom duration |
| Results that open an incident (comma-separated) | FAILURE, UNSTABLE | `ABORTED` can be added |
| Retention period (months) | 24 | Month files older than this are deleted, and the deletion is recorded |
| Notify before expiry (minutes) | 10 | How long before an active grant window expires its holder is notified |
| Send e-mail notifications | off | Shown only while the Mailer plugin is installed |
| Batch Control strategy | shown only while installed | Not a saved field: **Revert to the plain strategy** appears here while a Batch Control authorization strategy variant is installed (step 2); using it needs `Overall/Administer` |

A request cannot be created unless its designated approver is on the Approvers
list, and `BatchControl/Approve` is checked on them again at the moment they
decide.

**Turning change control off is a kill switch, and it is abrupt.** While the switch
is off no window confers anything, so every permission decision is the installed
strategy's own, exactly as before the plugin was installed. Flipping it off also
*revokes* every window that is open at that moment, writing one revocation record
per closure naming the account that flipped it. Anyone in the middle of a change
loses the permission to finish it, with no warning and no way back but a new
request. That is the deliberate trade: the alternative was a switch that leaves
windows quietly conferring `Item/Configure` for up to the maximum grant duration
after the control was supposedly turned off. Nothing accumulates while the switch
is off, either: a window cannot be requested or approved, both refusals explain
themselves and are recorded, and the Grants screen is closed, so there is no state
waiting to take effect when change control is switched back on. That URL still
answers, deliberately, so that somebody following an old bookmark reads what
changed instead of meeting a dead link. The audit trail is untouched throughout:
History and Change Records go on showing the windows that did exist and the changes
made under them whichever way the switch is set.

### 2. Select a Batch Control authorization strategy

Change control does nothing until this is done, and it is the step most easily
missed: windows get approved and then have no effect at all.

Under **Manage Jenkins → Security → Authorization**, choose **Batch Control:
Project-based Matrix Authorization Strategy** if you use (or want) matrix-auth,
or **Batch Control: Role-Based Strategy** if you use role-strategy (918 or
newer). Each is a
drop-in variant of the corresponding upstream strategy: matrix, folder and agent
authorization properties, and role assignments, all stay configurable and
effective exactly as they are on the plain strategy. Already running the plain
strategy? Turn change control on first; an administrative monitor then appears
on **Manage Jenkins** saying that grants confer nothing, with an **Install the
Batch Control variant** button that converts your existing configuration into
the matching variant in one click, with every entry kept. There is no such
button on the Security page. If you run Jenkins' built-in global
"Matrix-based security", note that grants need the *project*-matrix variant,
and converting makes every per-item authorization property already saved on
jobs, folders and agents effective from that moment; review them first
([Limitations](#limitations)). To go back, use **Revert to the plain strategy**
in the Batch Control section of **Manage Jenkins → System**, shown while a
variant is installed. While an approved window is open, the selected variant
*adds* that window's actions, `Item/Create`, `Item/Configure` or `Item/Delete`,
on that window's scope, and passes every other decision through unchanged, so
with no active window it behaves exactly like the plain strategy. It resolves a
permission the way Jenkins itself does, by walking the `impliedBy` chain, so a
window also answers the permissions Jenkins treats as implied by the granted
action: on the plugin set this project is built against, a `CONFIGURE` window
additionally confers `Item/ExtendedRead`, `Credentials/UseItem` and
`Run/Replay`, which is worth knowing before approving one
([Limitations](#limitations)). An administrative monitor warns if change control
is on without one of these two variants installed, for example after a plain
strategy is selected on the Security page, and offers the one-click reinstall.
On role-strategy 918 and newer, saving **Manage Roles** or **Assign Roles**
keeps the Batch Control variant in place, so grants keep conferring; older
role-strategy releases did not, which is why 918 is the minimum. Run control and recording do not
need any of this. A window confers its permissions only when a Batch Control
variant is selected *and* change control is on.

### 3. Assign the permissions

| Permission | What it allows |
|---|---|
| `BatchControl/Request` | Create run requests for approval-protected jobs, and activation and hold requests (with `Item/Read` on the job; `Item/Build` is not required) |
| `BatchControl/Approve` | Approve or reject run, activation, hold and window requests |
| `BatchControl/RequestGrant` | Request temporary change permissions |
| `BatchControl/ViewHistory` | View the history screens, dashboards and CSV exports |
| `BatchControl/Manage` | Manage the global configuration on **Batch Control → Configuration**, and revoke windows |

`Manage` is implied by `Overall/Administer` and implies the other four, so
administrators pass every check. A requester typically holds `Overall/Read`,
`Item/Read`, `Request` and `RequestGrant`, and deliberately not
`Item/Configure`, since that is precisely what a `CONFIGURE` window is for. An
approver usually gets `Approve` and `ViewHistory` but not `Request`. Note that
`ViewHistory` is broader than its name suggests; see [Limitations](#limitations).

Requesting a run needs `BatchControl/Request` and `Item/Read` on the job, and
nothing else: `Item/Build` is not required. On a job that requires approval,
`Item/Build` confers nothing by itself while run control is on, since every
direct path is refused and the approved run is queued by the plugin, so
`Request` alone decides who may ask. **On approval-required jobs, grant
`Request` instead of `Build`.** Keep `Item/Build` for the jobs that do not
require approval, and for the whole instance while run control is off, where
Jenkins' own Build semantics apply unchanged. Because an approval can now
authorise a run for someone who could not start the job themselves, the request
detail page and the approver notification say so when the requester lacks
`Item/Build` on the job, so the approver makes that decision knowingly. If you
want only Build holders to be able to ask, assign `Request` only to them.
A requester without `Item/Build` who posts to the job's `/build` endpoint from a
script gets Jenkins' own 403 ("missing the Job/Build permission"), because core
checks Build before Batch Control's gate runs; such users request runs through
**Request Run** or the service API, not the build endpoint.

### 4. Configure jobs

A **Batch Control** section appears in the job configuration, with inline help on
each field: `Require approval to run`, the two trigger overrides described above
with their `Allowed upstream jobs` list, and an optional job-level approver list
that narrows the global one. While run control is on, **every newly created job
starts locked**: `Require approval to run`, `Block cron (timer) triggers` and
`Block upstream triggers` all on and the allowed-upstream list empty, whoever
created it and however. These switches decide how strict the job is once it is in
service; they do not put it into service. Clearing them is a recorded change and,
with change control on, needs a permission window, but a job created while run
control is on still runs unattended only after an approved `ACTIVATE` request
([Activation](#activation-putting-a-job-into-service)). Clearing
`Require approval to run` does let a person start it with Build Now without a
run request.

Values in the creation payload do not survive the lock. An `approvalRequired=false`,
a `blockTimer=false` or an allowed-upstream list in a `config.xml` POST, a CLI
`create-job`, a Job DSL seed or a copied job is overwritten; only the job-level
approver list is carried over, because it can only narrow who may approve. If your
instance generates jobs from scripts this will bite on the first run — the job's
own page and change history say so, but a generated job that must run unattended
still needs a second pass to clear the switches and an approved activation
request — so read
[the automation note](docs/LIMITATIONS.md#automation-and-generated-jobs) before
turning run control on.

A working reference configuration, with a Dockerfile, plugin list, security
bootstrap and global settings, lives under [`e2e/`](e2e/). It exists to run the
end-to-end suite rather than as a deployment template, but its init scripts do
show each of these four steps.

## The screens

Everything is on the **Batch Control** page, at `/batch-control/`. On Jenkins
2.568 its entry is not in the dashboard's left sidebar: open the **☰** (More
actions) menu in the page header and choose **Batch Control**. On that page the
sections are listed in its own sidebar, permission-gated section by section, so a
user sees only what they can act on. A user with no Batch Control permission gets
no entry and a 404 at that URL.

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
**Activations** lists activation and hold requests, with the ones awaiting your
decision at the top, and is where the approver decides them; the request itself
starts from the **Request activation** link in the notice on the job's page.
**Dashboard** (titled *Run Dashboard* on the page itself) lists the 50 most
recent builds in the instance, with a link to **History** for older ones, each with its
cause (`USER`, `TIMER`, `UPSTREAM`, `APPROVED_REQUEST`, `SCM`, `OTHER`), user,
parameters, result and duration, linking approved runs back to the request that
authorised them. **Incidents** collects the failures that opened automatically,
each with the last 100 console lines, and offers acknowledge, resolve, comment and
a rerun request. The rerun request carries the failed build's original parameters
as they were; they are fixed, not offered for editing. The rerun form has only
the approver checkboxes: the reason is generated from the incident and cannot be
typed in. Submitting it needs `BatchControl/Request` plus
`Item/Read` on the job, like any run request (`Item/Build` is not required), and
the Incidents screen itself needs `BatchControl/ViewHistory`, so the user needs
all three; the typical roles in step 3 give that combination only to administrators unless you
add it. The lifecycle runs `OPEN`
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

## Notifications and approver sets

**Notifications.** Batch Control sends e-mail through an optional dependency on
the Mailer plugin, so an instance without Mailer installed is unaffected, when a
request is created, its approver set changes, it is approved or rejected, or an
active grant window is about to expire. A request that ends without a decision is
notified too: the requester is told when their request expires (pending, or
approved but never run) or is invalidated, with the reason, and the designated
approvers of a pending request are told when it is cancelled, expires or is
invalidated, so that their inbox does not point at a request that is gone. A
requester is not mailed about their own cancel. The message carries the reason and,
only when the Jenkins URL is configured under **Manage Jenkins → System**, a
link back to the request; without that URL set, the message is sent with no
link rather than one guessed from the request itself
([Limitations](docs/LIMITATIONS.md#notifications-and-computed-folders)). Other
channels (Slack and the like) can be added by another plugin against the same
extension point.

**More than one approver.** A run request or a grant request may designate
several eligible approvers instead of one. Any one of them may decide, whichever
decides first closes the request, and both the self-approval ban and the
designated-set-only rule still apply to every member of the set. The requester
may change the set at any time before a decision is made.

**Restricting the name a CREATE window may create.** A `CREATE` permission
window request may optionally carry an exact job name or a regular expression.
When one is given, the window confers `Item/Create` only for a new item whose
name matches it; left empty, the window behaves as before, any name in the
scope folder. The pattern is validated at submission and shown to the approver
before they decide. It does not reach a child that a computed folder (a
multibranch project or an organization folder) creates while indexing, since
that child is created by the system rather than through the window
([Limitations](docs/LIMITATIONS.md#notifications-and-computed-folders)).

## Limitations

What follows is the part that changes decisions. The complete list is in
[`docs/LIMITATIONS.md`](docs/LIMITATIONS.md).

**Administrators are recorded, not stopped.** Stopping an administrator is out
of scope. `Overall/Administer` implies every Batch Control permission, so an
administrator can approve their own requests (unless *Allow administrators to
approve their own requests* is off), switch either control off, clear a job's
switches or change the authorization strategy, and each of these is recorded.
The gates themselves do apply to administrators: on a job that requires approval
an administrator's Direct Build, REST build or Replay is refused like anyone
else's, so an administrator also runs such a job through a request; and the
activation gate holds unattended runs of a job that is not activated, whoever
started them.

**Turning change control off cuts off work in progress.** The switch revokes every
open permission window the moment it goes off, so a user part-way through a change
loses the permission to finish and has to request a new window once the control is
back on. Each revocation is recorded. Nothing carries over the off period, since a
window cannot be requested or approved while the switch is off, and nothing is
removed from the audit history either.

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
script. A `CONFIGURE` window also lets its holder rename the job to any free name
in its folder, since Jenkins allows a rename to anyone who may configure the job;
the rename is recorded with the window. Under a `CREATE` window with a name
restriction, renames of what that window created are limited to matching names.

**On an item a grant has touched, only an administrator can widen
authorization.** A Pipeline `properties` step saves its job's authorization
entries whatever account the build runs as, so a `CONFIGURE` window holder could
use one to keep access after the window ends. While change control is on, Batch
Control therefore guards every item under an active grant, and every item whose
configuration was changed under a grant, including a Pipeline job on which a
grant holder ran a Replay, a Pipeline Rebuild or a Restart from Stage. Guarding
covers the item and everything below it. A changed item stays guarded until
someone marks it as reviewed with **Mark as reviewed**: administrators find it
next to the item on the Manage Jenkins monitor, which lists the items waiting
for review, and users with native Configure who also hold
`BatchControl/Request` find it on the item's Batch Control page (without
`Request` that page is not shown, so they ask an administrator). It writes a `GUARD_REVIEWED` record; an ordinary save is not a review. On
a guarded item, any change that widens access is put back and recorded, whoever
makes it, a non-administrator with native Configure included (an HTTP save gets
a 403 message). The only exception is a save made through an HTTP request (the
web UI, a `config.xml` POST, or REST or CLI over HTTP) by a user who holds
`Overall/Administer`. A Pipeline build whose own save was put back names the
reverted entries in its build log; a save whose build cannot be identified,
such as a seed job saving another job or a Freestyle build, gets only the change
record. Until the review, a Jenkinsfile, Job DSL, JCasC or non-HTTP CLI change
that widens authorization on such an item is put back, even an administrator's
CLI over WebSocket or SSH; use the web UI or the CLI over HTTP instead. Items
that no grant touched are unaffected. Deleting a guarded item and re-creating it
under the same name drops the guard.

Run builds under a low-privilege account as well, since a build that runs as
SYSTEM or as an account with Configure can still change whatever that account
may change. Use Authorize Project with a global default build authorization
that runs every build as an account without Configure permission, for example
**Run as Specific User** with a dedicated low-privilege build account. Give that
account neither `Overall/Administer` nor `Item/Configure`, and no Configure on
folders or jobs either. **Run as the user who triggered the build** is safe only
with such a fallback, since timer and SCM builds have no triggering user and
would otherwise run as SYSTEM. A strategy on a single job is not enough: anyone
who can configure the job, a window holder included, can remove it. While change
control is on and builds can run as SYSTEM or as an account with Configure, the
administrative monitor says so for the whole instance, and the detail page of a
pending `CONFIGURE` request shows the same warning to its approvers and to
`BatchControl/Manage` holders. The check looks at the build account's
permissions at the Jenkins root only, so Configure given to it on a folder or
job is not detected. It cannot judge authenticators that decide by job type,
folder or the caller's identity. Its answer is cached for five minutes, so after
the build authenticators or the build account's permissions change the warning
can take that long to appear or clear; replacing the authenticators through the
security configuration updates it at once.

**Grants work through Batch Control's own strategy variants.** Selecting
**Batch Control: Project-based Matrix Authorization Strategy** or **Batch
Control: Role-Based Strategy** keeps that plugin's own per-item configuration (folder, job and
agent authorization properties, item and agent roles) configurable and
effective, since each variant is a subclass of the corresponding upstream
strategy. Selecting any other strategy gets you run control and recording only,
and an administrative monitor says so. role-strategy must be 918 or newer:
older releases replace the variant with the plain strategy on a **Manage
Roles** save, so Batch Control declares 918 as its minimum, and on 918 the
**Manage Roles** and **Assign Roles** saves keep the variant. role-strategy's
pages are still being reworked upstream, so a regression test guards this
integration and a breaking role-strategy release fails Batch Control's build.

**Converting from the global matrix strategy turns on per-item permissions.**
Grants need the project-matrix variant. Jenkins' built-in global
"Matrix-based security" ignores the authorization properties saved on jobs,
folders and agents; once you convert to **Batch Control: Project-based Matrix
Authorization Strategy**, every one of them is effective, stale ones included,
and anyone with `Item/Configure` on an item can edit its permissions. The
conversion is an explicit step, with the administrative monitor's **Install
the Batch Control variant** button on Manage Jenkins, which says the same.
Review the per-item properties first.

**A protected job refused at queue entry fails its caller.** A Pipeline `build`
step that hits the gate ends the upstream job as `FAILURE`, even with
`wait: false`. That is Jenkins' behaviour, not a choice made here.

**Plan for the new-job lock and activation before enabling run control.** Every
job created while run control is on starts with approval required and both
trigger overrides on, and any value the creation payload supplied for those is
overwritten, so a Job DSL or JCasC definition that pins `blockTimer: false` is
not idempotent against a fresh creation and appears simply to be ignored. On top
of that the job starts not activated, and no configuration can change that: only
an approved `ACTIVATE` request can. A generated nightly job therefore does not
run its first night — but the refusal is recorded and shown, not silent: the
job's own page carries a notice saying it is not activated (and naming
`blockTimer`/`blockUpstream` while a switch is on), each refused attempt writes
a `TRIGGER_BLOCKED` change record (unattended refusals are coalesced to at
most one per job and cause per hour), and the controller log carries a line
at most once an hour per job.
A generated job that must run unattended needs a second pass to clear the
switches and an approver to activate it. Jobs that already exist when the
plugin is installed, and jobs created while run control is off, are activated
and keep their schedules.

**Some re-run links stay visible on a job that requires approval.** Jenkins'
own build link (relabelled **Direct Build (needs approval)**), Pipeline's
**Replay**, Pipeline's own **Rebuild** on a Pipeline build page, and naginator's
**Retry** are drawn for everyone with the underlying permission, and Batch
Control has no way to remove them. A click is refused, nothing is queued, and
the job and build pages explain why and point to **Request Run**; a refused
Pipeline **Rebuild** shows the "Approval required" page and is recorded. On a job without parameters, **Direct Build (needs approval)**
answers only with Jenkins' own toast, "Failed to schedule build. Reload the page
and try again.", which wrongly suggests trying again, and that first click writes
no record; use the approval notice on the job page and **Request Run** instead.
The rebuild plugin's **Rebuild**, a different link, can be hidden, and is.

**Other plugins' build buttons fail with their own generic message.** When
Batch Control refuses a run started from naginator's Retry, Rebuild or Rebuild
Last where they are shown, or a button customised by another plugin, that
plugin shows its own message ("Failed to schedule build", "Failed.") and says nothing about
approval. The run was refused correctly and nothing was queued; the job's own
page is where the reason is shown: on a job that requires approval a notice
says that manual runs need an approved run request and links to **Request
Run**, and the activation notice says whether unattended runs (an automatic
retry among them) are allowed, with a **Request activation** link.

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
than for how long, where Batch Control layers over whichever of matrix-auth or
role-strategy you install and adds permissions that exist only inside an
approved window.

## Roadmap

Deliberately not in this release: a REST API, and approval events exported to
the Audit Log plugin; sequential multi-stage approval chains (today's
designated set is decided by whichever member acts first, not a sequence of
stages). The global configuration and the authorization strategy variants
already round-trip through JCasC.

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
