Korean translation: [README.ko.md](README.ko.md)

# Batch Control

Run approval, time-boxed change permissions and an append-only audit history for
Jenkins instances that are operated as a **batch execution manager** rather than
as a CI server.

It is for teams whose nightly and periodic production workload runs on Jenkins,
where starting a job by hand or changing what it does is an operational change.
Installing the plugin changes nothing by itself: both controls are off until an
administrator turns them on.

## Key features

- **Run approval.** A person's manual run of a protected job (Build Now, REST,
  CLI, Replay) is refused at queue entry and becomes a run request. Only the
  designated approvers may decide it, and the approved build gets exactly the
  parameters they saw, once.
- **Activation.** A job created while run control is on does not run on a
  timer, upstream trigger, SCM trigger or webhook until an approver activates
  it. Existing jobs keep their schedules.
- **Permission windows.** A user without standing rights requests `CREATE`,
  `CONFIGURE` or `DELETE` on one job or folder for a limited time; the window
  closes by itself.
- **Audit history independent of builds.** Runs, requests, decisions,
  configuration changes with diffs, and failures are kept append-only under
  `$JENKINS_HOME/batch-control/`, with history screens and CSV export.
- **Incidents.** Failed and unstable runs open an incident with the last 100
  console lines, to acknowledge, resolve and rerun through a request.
- **Fits your security setup.** Works through Batch Control variants of Matrix
  Authorization Strategy and Role-based Authorization Strategy; e-mail
  notifications (off by default); configurable with Configuration as Code.

## Screenshots

**Request Run** on a protected job.

![Request Run form of a protected job, with its build parameters, a reason field and approver checkboxes](docs/images/request-run.png)

A pending run request, as its approver sees it.

![An approver's view of a pending run request showing its parameters, reason and requester, with Approve and Reject buttons](docs/images/approval.png)

Requesting a permission window.

![Permission window request form with the job or folder name, the CREATE, CONFIGURE and DELETE actions, a duration and a reason](docs/images/permission-window.png)

The History page, Requests view.

![Batch Control History page in its Requests view, listing eight run requests with their job, requester, approvers, who decided, status (PENDING, EXECUTED, REJECTED, CANCELLED), and created and decided times](docs/images/dashboard.png)

The global configuration.

![Batch Control section of the Jenkins global configuration, with the run control and change control switches and the approver list](docs/images/global-config.png)

## Requirements

- **Jenkins 2.568.3 or newer** on **Java 21 or 25**.
- **Required plugins:** `cloudbees-folder`, `ionicons-api` and `caffeine-api`,
  resolved for you on installation.
- **An authorization strategy that draws a permission matrix**, such as Matrix
  Authorization Strategy or Role-based Authorization Strategy; the Batch
  Control permissions cannot be assigned on "Logged-in users can do anything".

Batch Control loads without these plugins; if one is installed, it must be at
least this version:

| Optional plugin | Minimum version |
|---|---|
| `matrix-auth` | 3.3 |
| `role-strategy` | 927.v9cf5527c4085 |
| `configuration-as-code` | 2121.v86fe99d4b_b_a_b_ |
| `mailer` | 534.v1b_36f5864073 |
| `rebuild` | 338.va_0a_b_50e29397 |
| `file-parameters` | 433.va_0b_80359d54d |

With an older version installed Batch Control does not load. **Plugins →
Available plugins** offers the upgrade; **Deploy Plugin** does not, so upgrade
first.

## Installation

Install from **Manage Jenkins → Plugins → Available plugins** (search for
*Batch Control*), or upload a build under **Advanced settings → Deploy Plugin**.
Building needs Maven and JDK 21 or 25: `mvn clean package` produces
`target/batch-control.hpi`.

## Quick start

1. In **Manage Jenkins → System → Batch Control**, check **Enable run control**
   (and **Enable change control** for permission windows), list the approvers'
   user IDs under **Approvers**, and save.
2. For change control only, select the Batch Control strategy: the monitor on
   **Manage Jenkins** offers **Install the Batch Control variant**
   ([details](docs/USER-GUIDE.md#2-select-a-batch-control-authorization-strategy)).
3. Give requesters `BatchControl/Request` and approvers `BatchControl/Approve`,
   both with `Overall/Read` and `Item/Read`.
4. On a job, check **Require approval to run** (section **Batch Control**).
5. The requester chooses **Request Run** on the job, fills in the parameters, a
   reason and the approvers, and submits.
6. The approver opens **Batch Control** (in the **☰** menu) → **Run Requests**
   and chooses **Approve** or **Reject**. On approval the build is queued.

Jobs created while run control is on need an approved activation before they run
unattended ([Activation](docs/USER-GUIDE.md#activation-putting-a-job-into-service)).
The full setup is in the [user guide](docs/USER-GUIDE.md#configuration).

## Permissions

| Permission | What it allows |
|---|---|
| `BatchControl/Request` | Create run, activation and hold requests (with `Item/Read` on the job; `Item/Build` is not required) |
| `BatchControl/Approve` | Approve or reject run, activation, hold and window requests |
| `BatchControl/RequestGrant` | Request temporary change permissions |
| `BatchControl/ViewHistory` | View the history screens, dashboards and CSV exports, for every job |
| `BatchControl/Manage` | Manage the global configuration and revoke windows (implied by `Overall/Administer`) |

[The full rules](docs/USER-GUIDE.md#3-assign-the-permissions).

## Documentation

- [User guide](docs/USER-GUIDE.md): how both controls work, configuration, the
  screens, notifications, and the [limitations](docs/USER-GUIDE.md#limitations)
  that change decisions.
- [Known limitations](docs/LIMITATIONS.md): the complete list.
- Issues and feature requests: <https://github.com/jenkinsci/batch-control-plugin/issues>.

## Contributing

Issues and pull requests are welcome; [`CONTRIBUTING.md`](CONTRIBUTING.md) is
the guide. `docs/SPEC.md` is the functional contract (if the code and the spec
disagree, the code is wrong); it, `docs/DECISIONS.md` and `docs/ARCHITECTURE.md`
are written largely in Korean, with the newer sections in English.

## Reporting security vulnerabilities

Please **do not** open a GitHub issue. Report privately in the Jenkins
[**SECURITY** project](https://issues.jenkins.io/secure/CreateIssueDetails!init.jspa?pid=10180&issuetype=10103)
under the `specific-plugin` component, naming the plugin in the summary, or mail
`jenkinsci-cert@googlegroups.com`. Full policy:
<https://www.jenkins.io/security/reporting/>.

## License

MIT. See [`LICENSE`](LICENSE).
