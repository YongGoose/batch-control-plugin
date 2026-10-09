Korean translation: [README.ko.md](README.ko.md)

# Batch Control

Run approval, time-boxed change permissions and an append-only audit history for
Jenkins instances that are operated as a **batch execution manager** rather than
as a CI server.

It is for teams whose nightly and periodic production workload runs on Jenkins:
Spring Batch jobs, data loads, settlement runs, reconciliation scripts. Starting
one of those by hand, or changing what it does, is an operational change. With
Batch Control, a manual run of a protected job is a request that a designated
approver approves before anything reaches the queue, a configuration change
needs a permission window that expires on its own, and runs, decisions, changes
and failures are kept in a store that outlives build rotation. Installing the
plugin changes nothing by itself: both controls are off until an administrator
turns them on.

## Key features

- **Run approval.** A person's manual run of a protected job (Build Now, REST,
  CLI, Replay) is refused at queue entry; the person submits a run request with
  parameters and a reason instead, and the build is queued only after a
  designated approver approves it.
- **Single-use approvals.** The approved build gets exactly the parameters the
  approver saw, passwords and files included. An approval is consumed by one
  queue submission and cannot be replayed, re-queued or rebuilt.
- **Approver sets.** A request may name several eligible approvers. Only that
  set may decide, and the first decision closes the request. Self-approval is
  refused (configurable for administrators).
- **Activation.** A job created while run control is on does not run on a
  timer, upstream trigger, SCM trigger or webhook until an approver activates it.
  Existing jobs keep their schedules; `Block cron (timer) triggers` and
  `Block upstream triggers` make a single job stricter.
- **Permission windows.** A user without standing rights requests `CREATE`,
  `CONFIGURE` or `DELETE` on one job or folder for a limited time. The window
  closes by itself and can be revoked early. While change control is on,
  deleting a job needs a `DELETE` window from everyone but administrators.
- **Audit history independent of builds.** While either control is on, runs,
  requests, decisions, configuration changes with diffs, and failures are
  written append-only under `$JENKINS_HOME/batch-control/`, with a configurable
  retention period.
- **Incidents.** Failed and unstable runs (configurable) open an incident with
  the last 100 console lines, to acknowledge, resolve, comment on and rerun
  through a request.
- **History and dashboards.** Filters by period, job, user, result and status,
  CSV export, and a monthly summary.
- **Notifications.** Optional e-mail through the Mailer plugin (off by default),
  and an extension point for other channels.
- **Fits your security setup.** Change control works through Batch Control
  variants of Matrix Authorization Strategy and Role-based Authorization
  Strategy, which keep their own configuration. The global configuration and
  the strategy variants can be set with Configuration as Code.

## Screenshots

**Request Run** on a protected job: parameters, a reason and the approvers to ask.

![Request Run form of a protected job, with its build parameters, a reason field and approver checkboxes](docs/images/request-run.png)

A pending run request as its approver sees it, with **Approve** and **Reject**.

![An approver's view of a pending run request showing its parameters, reason and requester, with Approve and Reject buttons](docs/images/approval.png)

The permission window request form.

![Permission window request form with the job or folder name, the CREATE, CONFIGURE and DELETE actions, a duration and a reason](docs/images/permission-window.png)

The **History** page, Requests view: run requests with their approvers, who decided and the status.

![Batch Control History page in its Requests view, listing eight run requests with their job, requester, approvers, who decided, status (PENDING, EXECUTED, REJECTED, CANCELLED), and created and decided times](docs/images/dashboard.png)

The Batch Control section of the global configuration.

![Batch Control section of the Jenkins global configuration, with the run control and change control switches and the approver list](docs/images/global-config.png)

## Requirements

- **Jenkins 2.568.3 or newer**, the baseline the plugin is compiled against,
  running on **Java 21 or 25**, the Java versions that Jenkins line supports.
- **Required plugins:** `cloudbees-folder`, `ionicons-api` and `caffeine-api`.
  The Plugin Manager (or Deploy Plugin, when installing a build of your own)
  resolves them for you.
- **An authorization strategy that draws a permission matrix**, such as Matrix
  Authorization Strategy or Role-based Authorization Strategy. The five Batch
  Control permissions are only visible on such a strategy, so with Jenkins'
  built-in "Logged-in users can do anything" there is no screen on which to
  assign them.

The integrations with these plugins are optional: Batch Control loads without
them. If one of them is installed, though, it must be at least the version
Batch Control is compiled against, which Jenkins enforces when loading plugins:

| Optional plugin | Minimum version |
|---|---|
| `matrix-auth` | 3.3 |
| `role-strategy` | 927.v9cf5527c4085 |
| `configuration-as-code` | 2121.v86fe99d4b_b_a_b_ |
| `mailer` | 534.v1b_36f5864073 |
| `rebuild` | 338.va_0a_b_50e29397 |
| `file-parameters` | 433.va_0b_80359d54d |

If an older version of any of these is installed, Batch Control fails to load
until that plugin is upgraded. Installing Batch Control from **Manage Jenkins →
Plugins → Available plugins** offers the needed upgrade along with it. Uploading the
`.hpi` through **Deploy Plugin** does not, so upgrade those plugins first.

## Installation

Install from **Manage Jenkins → Plugins → Available plugins**, searching for
*Batch Control*, or upload a build of your own under **Manage Jenkins → Plugins
→ Advanced settings → Deploy Plugin**. Building from source needs Maven and JDK
21 or 25:

```sh
mvn clean package   # produces target/batch-control.hpi
mvn hpi:run         # a local Jenkins at http://localhost:8080/jenkins
```

## Quick start

One protected job, one requester, one approver; the details behind each step are
in the [user guide](docs/USER-GUIDE.md#configuration).

1. **Turn the controls on.** In **Manage Jenkins → System**, section **Batch
   Control** (or at `/batch-control-configuration/`), check **Enable run
   control**, and **Enable change control** if you want permission windows.
   Under **Approvers**, enter the user IDs that may approve, one per line.
   Save. Both switches are off by default, and each is recorded when flipped.
2. **Select the Batch Control strategy (change control only).** With change
   control on, a monitor on **Manage Jenkins** offers **Install the Batch Control
   variant**, which converts your matrix-auth or role-strategy configuration
   with every entry kept (coming from the global matrix strategy,
   [read this first](docs/USER-GUIDE.md#2-select-a-batch-control-authorization-strategy)).
   Without it, approved windows confer nothing; run control does not need it.
3. **Assign the permissions.** Give the requester `Overall/Read`, `Item/Read`
   and `BatchControl/Request` (plus `BatchControl/RequestGrant` for windows).
   Give the approver `Overall/Read`, `Item/Read`, `BatchControl/Approve` and
   `BatchControl/ViewHistory`, and make sure they are on the Approvers list.
4. **Protect a job.** In the job's configuration, section **Batch Control**,
   check **Require approval to run**. A job created while run control is on
   starts with it checked.
5. **Request a run.** As the requester, open the job and choose **Request Run**
   in its sidebar. Fill in the parameters and a reason, tick one or more
   approvers and choose **Submit Request**.
6. **Approve it.** As the approver, open **Batch Control** from the **☰** (More
   actions) menu in the page header, then **Run Requests**, open the request
   and choose **Approve**, or **Reject** with a comment. On approval the build
   is queued with the stored parameters.
7. **Activate jobs that must run unattended.** Jobs that existed when the plugin
   was installed, or were created while run control was off, keep their
   schedules. A job created while run control is on does not run on a timer,
   upstream trigger, SCM trigger or webhook until it is activated: request it
   with **Request activation** on the job's page and have an approver approve
   it. Approved manual runs, as in steps 5 and 6, do not need activation.

With change control on, a user without `Item/Configure` on a job finds **Request
Change Permission** in its sidebar; once an approver approves the window on the
**Grants** screen, that user can change the job until the window expires.

## Permissions

| Permission | What it allows |
|---|---|
| `BatchControl/Request` | Create run, activation and hold requests (with `Item/Read` on the job; `Item/Build` is not required) |
| `BatchControl/Approve` | Approve or reject run, activation, hold and window requests |
| `BatchControl/RequestGrant` | Request temporary change permissions |
| `BatchControl/ViewHistory` | View the history screens, dashboards and CSV exports, for every job |
| `BatchControl/Manage` | Manage the global configuration and revoke windows |

`Manage` is implied by `Overall/Administer` and implies the other four. On jobs
that require approval, grant `Request` instead of `Item/Build`: while run control
is on, every direct build path is refused there. `ViewHistory` is an
instance-wide audit read with no `Item/Read` check. Administrators are recorded,
not stopped. [The full rules](docs/USER-GUIDE.md#3-assign-the-permissions).

## Documentation

- [User guide](docs/USER-GUIDE.md):
  [why the plugin exists](docs/USER-GUIDE.md#why),
  [the two controls](docs/USER-GUIDE.md#the-two-controls),
  [what run control stops](docs/USER-GUIDE.md#what-run-control-stops-and-what-it-does-not),
  [activation](docs/USER-GUIDE.md#activation-putting-a-job-into-service),
  [configuration](docs/USER-GUIDE.md#configuration),
  [the screens](docs/USER-GUIDE.md#the-screens),
  [notifications and approver sets](docs/USER-GUIDE.md#notifications-and-approver-sets),
  [the limitations that change decisions](docs/USER-GUIDE.md#limitations),
  [comparison with other approaches](docs/USER-GUIDE.md#compared-with-other-approaches)
  and the [roadmap](docs/USER-GUIDE.md#roadmap).
- [Known limitations](docs/LIMITATIONS.md): the complete list. Read
  [Automation and generated jobs](docs/LIMITATIONS.md#automation-and-generated-jobs)
  before turning run control on in an instance that generates jobs.
- Issues and feature requests: <https://github.com/jenkinsci/batch-control-plugin/issues>.

## Contributing

Issues and pull requests are welcome, and [`CONTRIBUTING.md`](CONTRIBUTING.md)
is the guide; a couple of its conventions are unusual enough to read before a
first change. `docs/SPEC.md` is the functional contract (if the code and the
spec disagree, the code is wrong), `docs/DECISIONS.md` holds the reasoning behind
every settled decision, and `docs/ARCHITECTURE.md` the extension points, package
layout and storage format. Those three are written largely in Korean, with the
newer sections in English.

```sh
mvn clean verify   # compile, full test suite, SpotBugs; must end with 0 failures and 0 bugs
```

## Reporting security vulnerabilities

Please **do not** open a GitHub issue. Report privately in the Jenkins
[**SECURITY** project](https://issues.jenkins.io/secure/CreateIssueDetails!init.jspa?pid=10180&issuetype=10103)
under the `specific-plugin` component, naming the plugin in the summary. Such
issues are visible only to the reporter and the Jenkins security team. If you
cannot use the tracker, mail `jenkinsci-cert@googlegroups.com` and the team will
file on your behalf. Full policy: <https://www.jenkins.io/security/reporting/>.

## License

MIT. See [`LICENSE`](LICENSE).
