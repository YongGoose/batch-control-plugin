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

**Change control** governs changing a job. A user who needs to create, configure
or delete one requests a permission window: a scope (one job, or a folder), some
combination of `CREATE`, `CONFIGURE` and `DELETE`, a duration and a reason. Once
it is approved they do the work under their own account, with every usual Jenkins
safeguard still in place. Expiry is decided by comparing the clock at each
permission check, so the window closes with no timer and no revocation step, and
stays closed across a controller restart. A `BatchControl/Manage` holder can
revoke one early.

Recording does not depend on which of the two is on. If either one is on, runs,
changes, decisions and failures are recorded.

## What run control stops, and what it does not

Run control intercepts builds at queue entry, and it distinguishes a person
pressing a button from automation firing. Blocked: "Build Now", the `build` and
`buildWithParameters` REST endpoints, `jenkins-cli build`, and Pipeline Replay.
Allowed: an approved run request (once), cron and other timer triggers, builds
triggered by an upstream job, SCM triggers, and any build whose cause the plugin
does not recognise.

Two consequences follow, and they are worth stating plainly.

**A scheduled batch job keeps running on schedule after you turn run control
on.** Approval is what a *person* needs in order to intervene out of band. The
nightly run is not a request, nobody approves it, and nothing is consumed, so it
passes indefinitely. Individual jobs can be made stricter with
`Block cron (timer) triggers` and `Block upstream triggers`, the second of which
takes a list of upstream jobs that may still trigger the job anyway. Nothing
narrows the SCM or unrecognised-cause path.

**Blocking happens only at queue entry.** Nothing the plugin does interrupts a
build that is already running, not a global switch, not a job property, not a
configuration change, and not a permission window expiring mid-build.

A person whose start is refused gets an "approval required" page or CLI message
linking to the request form, never a silent failure. Automation refused by one of
the per-job options is turned away quietly and logged.

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
| Enable change control | off | Permission windows and delete vetoing |
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

### 2. Select the wrapping authorization strategy

Change control does nothing until this is done, and it is the step most easily
missed: windows get approved and then have no effect at all.

Under **Manage Jenkins → Security → Authorization**, choose **Batch Control
(wrapping)** and select your real strategy, Project-based Matrix Authorization
for instance, as its delegate. Your existing matrix configuration stays inside
the delegate. The wrapper only *adds* `Item/Create`, `Item/Configure` and
`Item/Delete` while an approved window is open and passes every other decision
through unchanged, so with no active window it behaves exactly like the delegate
alone. An administrative monitor warns if change control is on without it. Run
control and recording do not need this strategy.

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
starts with "Require approval to run" enabled**, whoever created it and however.
If your instance generates jobs from scripts, read
[the automation note](docs/LIMITATIONS.md#automation-and-generated-jobs) before
turning run control on.

A working reference configuration, with a Dockerfile, plugin list, security
bootstrap and global settings, lives under [`e2e/`](e2e/). It exists to run the
end-to-end suite rather than as a deployment template, but its init scripts do
show each of these four steps.

## The screens

Everything is under **Batch Control** in the left sidebar, permission-gated
section by section, so a user sees only what they can act on.

**Run Requests** carries each request's stored parameters, reason, requester,
approver, status and decision history, and is where the approver decides. On a
protected job the sidebar offers **Request Run** in place of Build Now, opening
the same form with the job's parameters. **Grants** shows pending window requests,
the time left on an active window, the history of expired ones and a link to
request another. **Run Dashboard** lists every build in the instance with its
cause (`USER`, `TIMER`, `UPSTREAM`, `APPROVED_REQUEST`, `SCM`, `OTHER`), user,
parameters, result and duration, linking approved runs back to the request that
authorised them. **Incidents** collects the failures that opened automatically,
each with the last 100 console lines, and offers acknowledge, resolve, comment and
a rerun request with the original parameters prefilled; the lifecycle runs `OPEN`
→ `ACKNOWLEDGED` → `RESOLVED`, one way only, each transition carrying a user, a
timestamp and a comment. **History** filters runs, incidents, change records and
requests by period, job, user, result and status, exports each as CSV with
spreadsheet formula injection neutralised, and gives a monthly summary.
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

**Plan for the new-job default before enabling run control.** Every job created
while run control is on starts controlled, and an explicit
`approvalRequired=false` in the creation payload is overwritten, so a Job DSL or
JCasC definition that pins it is not idempotent against a fresh creation and
needs a second pass. The seed jobs in this repository's own e2e environment
stopped building the first time this landed. Automatic builds are not what
breaks, since timer, upstream and SCM causes still pass; what stops is anything a
person has to press.

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
of build retention. Audit-trail style plugins record what happened, with no
notion of a request, a designated approver, a decision, or a permission that
expires; here the audit history is a by-product of a control plane. And matrix
and role-based authorization assign standing permissions, where Batch Control
wraps whichever of them you configured and adds permissions that exist only
inside an approved window.

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
