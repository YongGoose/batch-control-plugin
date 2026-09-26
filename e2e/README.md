# e2e environment

A real Jenkins in Docker with the built plugin installed, three accounts and
three sample jobs, used for the Phase 5 end-to-end pass.

## Prerequisites

1. `docker` and `docker compose`.
2. The artifact: from the project root, `mvn -ntp clean package -DskipTests`
   leaves `target/batch-control.hpi`, which `docker-compose.yml` mounts.
3. `cp .env.example .env` and set the three passwords.

## Lifecycle

```bash
scripts/up.sh      # build the image (first run), start Jenkins, wait for /login
scripts/down.sh    # stop, keep JENKINS_HOME
scripts/reset.sh   # stop and delete JENKINS_HOME (accounts, jobs, store)
```

Jenkins is published on `http://localhost:${BC_PORT:-8080}/`.

## What the environment contains

| Account | Permissions | Purpose |
|---|---|---|
| `admin` | Overall/Administer | administration screens, reading everything |
| `approver` | Overall/Read, Job/Read, BatchControl Approve + ViewHistory. **No** BatchControl/Request | decides requests; proves a submit by a non-requester is refused |
| `requester` | Overall/Read, Job/Read, Job/Build, BatchControl Request + RequestGrant. **No** Job/Configure | asks for runs and for JIT permissions; proves a grant is what adds Job/Configure |

The authorization strategy is the plugin's own delegating strategy wrapping
matrix-auth's `ProjectMatrixAuthorizationStrategy`, which is what makes JIT
change control observable.

| Job | Type | Batch Control | Notes |
|---|---|---|---|
| `batch-daily` | Freestyle, parameters `DATE` (string), `MODE` (choice full/partial) | approval required | the main run-request subject |
| `batch-pipeline` | Pipeline | approval required | proves both job types are recorded |
| `batch-cron` | Freestyle, `* * * * *` timer | not controlled | feeds the run dashboard with volume |

Global configuration is applied once (marker file
`$JENKINS_HOME/.batch-control-e2e-config-applied`) so changes made while testing
survive a restart: run control and change control on, approvers
`[approver, admin]`, grant duration options `1, 15, 30, 60` minutes — the
1-minute option exists so the grant-expiry scenarios do not have to wait out the
15-minute default.

## Scenario scripts

All of them print the HTTP status and the relevant part of every response, and
write raw responses to `out/` (git-ignored).

| Script | Rows |
|---|---|
| `rest-run-request.sh` | T-E2E-01 request -> approve -> build -> dashboard |
| `rest-permission-denied.sh` | negative half: submit without Request, approve without Approve, history without ViewHistory |
| `rest-grant-configure.sh` | T-E2E-03 / T-E2E-06 CONFIGURE grant, save, expiry |
| `rest-screens.sh` | T-E2E-02 / T-E2E-05 / T-E2E-07 markup content |
| `rest-csv-export.sh` | T-E2E-04 the four CSV exports |
| `rest-approvers-empty.sh` | T-E2E-08 empty approver list warning |
| `rest-dashboard.sh` | T-10-06 7-day window and 50-row paging |
| `rest-blocked-build.sh` | direct build attempt refused with guidance |
| `rest-admin-screens.sh` | global configuration, security screen, /manage |

Helpers for a browser pass: `grant-setup.sh <job> <minutes>` arranges an active
CONFIGURE grant, `approvers-clear.sh` / `approvers-restore.sh` toggle the empty
approver list.

`lib.sh` holds the shared curl helpers (login with a cookie jar, crumb header on
every POST) and `bc_script`, which runs Groovy on the admin script console. The
script console is used only to read or arrange state that has no HTTP surface,
never to perform the behaviour under test.

## Scenario scripts added for e2e-02

| Script | Rows |
|---|---|
| `rest-marker-reuse.sh` | D-30 - a blocked approval-marker re-use is refused and audited |
| `rest-new-job-default.sh` | D-31/D-32 - every newly created job starts approval-required |
| `rest-incident-flow.sh` | the incident lifecycle: auto-registration -> ACKNOWLEDGED -> comment -> RESOLVED |
| `rest-monitor-misconfig.sh` | `ConfigureWithoutGrantMonitor` really fires (breaks the configuration on purpose, then restores it) |
| `rest-screens-v2.sh` | the screens that changed after e2e-01: recent-run table and its `?runs=` allow-list, executed-run link, approved-but-not-run notice, global-config label, grant remaining time |

Extra helper: `executors.sh <count>` sets the controller's executor count; with 0
an approved run stays queued, which is the only way to hold a request in
APPROVED-but-not-yet-run long enough to look at its notice.

Two things to know before changing the seed:

* While run control is on, **D-31 gives every newly created job
  `approvalRequired=true`, including the jobs this seed creates.** `batch-cron` and
  `batch-failing` must run unattended, so `20-sample-jobs.groovy` removes the
  property from them right after creation. Without that they simply never build,
  and nothing in the log looks wrong.
* `rest-grant-configure.sh` revokes every active grant before it starts (otherwise
  a longer grant keeps the permission alive past its own 1-minute window and the
  expiry assertion fails for the wrong reason) and restores the job description it
  rewrites.
