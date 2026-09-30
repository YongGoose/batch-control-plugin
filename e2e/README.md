# e2e environment

A real Jenkins in Docker with the built plugin installed, the accounts of
`CHECKLIST.md` section 1.2, the sample jobs of section 1.3 and a mail sink,
used for the end-to-end passes (`docs/reports/e2e-*.md`).

## Prerequisites

1. `docker` and `docker compose`.
2. The artifact: from the project root, `mvn -ntp clean package -DskipTests`
   leaves `target/batch-control.hpi`, which `docker-compose.yml` mounts.
3. `cp .env.example .env` and set the four passwords.
4. For the browser driver: Node.js and Google Chrome; `cd browser && npm install`
   (Playwright drives the installed Chrome, no browser download).

## Lifecycle

```bash
scripts/up.sh      # build the image (first run), start Jenkins + mailpit, wait for /login
scripts/down.sh    # stop, keep JENKINS_HOME
scripts/reset.sh   # stop and delete JENKINS_HOME (accounts, jobs, store)
```

Jenkins: `http://localhost:${BC_PORT:-8080}/`. Mail sink UI and API (mailpit):
`http://localhost:${BC_MAIL_PORT:-8025}/`.

## How it is configured

| What | Where | When |
|---|---|---|
| Accounts (with `<id>@e2e.local` addresses), **Batch Control: Matrix-based security** with every entry, Batch Control global configuration, Mailer (`mailpit:1025`), Jenkins URL | `casc/jenkins.yaml` (JCasC) | every boot, before jobs load |
| Sample jobs, `team/` folder with per-item matrix, `agent-1`, `team-mb` multibranch over a local repository | `init.groovy.d/20-sample-jobs.groovy` | first boot only (existing items are left alone) |

Because JCasC re-applies on every boot, a global setting or strategy changed in
the UI is reset by a restart.

Other profiles:

| File | How | Used by |
|---|---|---|
| `casc/profile-role.yaml` | Manage Jenkins -> Configuration as Code -> Apply configuration -> `/var/jenkins_casc/profile-role.yaml`; back with `/var/jenkins_casc/jenkins.yaml` | Batch Control: Role-Based Strategy (B19-03) |
| `compose.locale-th-utc.yml` | `docker compose -f docker-compose.yml -f compose.locale-th-utc.yml up -d jenkins`; back with `docker compose up -d jenkins` | JVM locale th_TH_TH and zone UTC (B18-02/03) |
| `compose.ldap.yml` + `casc/profile-ldap.yaml` | `scripts/ldap-up.sh` (renders `out/ldap/bootstrap.ldif` from `ldap/bootstrap.ldif.template` with the `.env` passwords and starts `batch-control-e2e-ldap`), then Manage Jenkins -> Configuration as Code -> Apply configuration -> `/var/jenkins_casc/profile-ldap.yaml`; back with `/var/jenkins_casc/jenkins.yaml` (or a restart), then `scripts/ldap-down.sh` | LDAP realm (ldap plugin) with group entries: `bc-admins`, `bc-requesters`, `bc-approvers`, `bc-configurers`, `bc-auditors`; users `admin`, `lrequester`, `lapprover-1/2`, `lconfigurer`, `lauditor`, `lnobody` with `<id>@ldap.e2e.local` addresses (e2e-05) |

The permission names in `casc/jenkins.yaml` are `BatchControl/<Name>`; if the
import works, the README's permission names are right.

## Accounts

| Account | Permissions | Purpose |
|---|---|---|
| `admin` | Overall/Administer | administration, self-approval |
| `manager` | Overall/Read, Job/Read, BatchControl/Manage | configuration and revocation without Administer |
| `requester` | Overall/Read, Job/Read, Job/Build, Request, RequestGrant. **No** Job/Configure | the normal requester |
| `approver-1`, `approver-2` | Overall/Read, Job/Read, Approve, ViewHistory | listed approvers |
| `approver-disc` | Overall/Read, Approve; Job/Discover on `team/` only | approver without job read access |
| `approver-unlisted` | Overall/Read, Job/Read, Approve | holds Approve, not on the Approvers list |
| `reqonly` | Overall/Read, Job/Read, Request (no Build) | D-38 refusal, section refusals |
| `auditor` | Overall/Read, ViewHistory | history family only |
| `nobc` | Overall/Read, Job/Read, Job/Build, no Batch Control permission | root action and job action absent |
| `configurer` | Overall/Read, Job/Read, Job/Configure (standing), Request | standing-permission monitor, delete veto |

Passwords: `admin` = `BC_ADMIN_PASSWORD`, `requester` = `BC_REQUESTER_PASSWORD`,
`approver-1`/`approver-2` = `BC_APPROVER_PASSWORD`, everyone else =
`BC_OTHER_PASSWORD`. Global Approvers list: `approver-1, approver-2,
approver-disc, admin`.

## Jobs and activation

Run control is already on when the seed creates the jobs, so every job starts
under the new-job lock (D-31/D-34) and **not activated** (SPEC 6a). The seed
replaces the lock with each job's intended property (see the header of
`20-sample-jobs.groovy`) but deliberately does not activate anything: nothing in
a job's configuration can. A job that must run unattended is activated through
the real ACTIVATE request flow (job page -> "Request activation" -> an approver
approves); the pre-flight does this for `batch-cron` (checklist E-10).

The Section E jobs (`batch-cbn`, `batch-rebuild`, `batch-nag`, `batch-token`,
`batch-lock`, `batch-throttle`, `batch-authz`, `batch-jch`, `batch-up-target`,
`batch-pt-source`) are created empty; each plugin is configured on them in the
browser by `browser/section-e.mjs`, as an administrator would.

## Screenshots are local only

Screenshots are kept on the machine that ran the pass and are **never committed** (owner decision
2026-09-30): `e2e/.gitignore` ignores every `screenshots/` directory and image files. The reports
(`docs/reports/e2e-*.md`), `CHECKLIST.md` and `reaudit/results.jsonl` still name each capture
(for example `run-3-verify/B5-01-refusal-page.png`) so that it can be looked up on that machine.
The e2e-01/02 images that were committed earlier are no longer tracked.

## Browser driver (`browser/`)

`lib.mjs` opens a fresh context per account (real login form), outlines the
relevant element in red and saves a clipped screenshot to
`screenshots/run-3/<name>.png`; `api()` reads server state with basic auth;
`groovy()` uses the script console only to arrange or read state. Scenario
files: `preflight.mjs`, `section-a.mjs`, `section-e.mjs`. Evidence logs go to
`out/` (git-ignored).

## Extra checks driver (`extra/`)

e2e-05 (dark theme, LDAP realm, Back button and two tabs). `extra/lib.mjs` is
`fresh/lib.mjs` with screenshots going to `screenshots/run-5/` and the LDAP accounts'
passwords. `theme.mjs` sets the theme through Manage Jenkins -> Appearance (the
`dark-theme` plugin, baked into the image) and audits every Batch Control screen per
role with a computed WCAG contrast check (`out/theme-*.json`); `diff.mjs` opens a
change-record diff. `stale.mjs` (`tabs`, `back`, `double`), `race.mjs` (parallel
POSTs from a browser session), `x1b.mjs`/`x1c.mjs` (stale change-approvers form).
`ldap.mjs` (`apply`, `validate up|down`, `who`, `monitor`, `run`, `grant`,
`down-request`), `ldap-down-check.mjs`, `ldap-down-approve.mjs` and
`ldap-designate.mjs`; the LDAP-down steps log in first and then stop
`batch-control-e2e-ldap` themselves, because a new login needs the directory.
`cd extra && npm install` once.

## Older scenario scripts

`scripts/rest-*.sh` (curl with crumb and cookie jar, raw output to `out/`) are
from e2e-01/e2e-02; `lib.sh` knows every account above. `scripts/cli.sh <user>
<command>` runs jenkins-cli inside the container (it needs Java 21).

`browser/audit-shots.mjs` lists screenshots that break the rule (no red box or a
full-viewport capture); `browser/fix-shots.mjs` re-crops such a capture to its
content and boxes it.

## Re-audit driver (`browser/audit/`)

The five-criterion re-audit of run 3 (checklist 0a) lives in `browser/audit/`:
one script per section (`e1.mjs`, `e10.mjs`, `sa-read.mjs`, `a02.mjs` ... `b15.mjs`),
`rec.mjs` appends one JSON line per row (`V G R C E`, verdict, defect) to
`out/audit.jsonl` (copied to `reaudit/results.jsonl` at each commit; the last line
per id wins) and `amend.mjs <id> '<json>'` re-appends a row with corrected cells.
Run with `BC_SHOTS=run-3-audit` so `shot()` writes to `screenshots/run-3-audit/`
(`lib.mjs` honours `BC_SHOTS`; the default is `run-3`). `audit/audit-shots.mjs` and
`audit/fix-shots.mjs` are the screenshot checks for that directory.

`init.groovy.d/20-sample-jobs.groovy` runs on the first boot only and leaves
existing items alone, so a fix to a seed job does not reach a kept `JENKINS_HOME`:
`batch-self` still had the pre-fix self-trigger guard in the re-audit and looped
until its hold was approved; it was corrected by posting the seed's script to the
existing job. After changing a seed job, either `scripts/reset.sh` or update the
existing item.

## Faked clock (`faketime/`)

`faketime/Dockerfile` builds a throwaway variant of the e2e image with libfaketime, used once for checklist D-05
(month boundary). Run it as a second, fresh Jenkins only while the main one is stopped, e.g. with
`-e LD_PRELOAD=/usr/local/lib/libfaketime.so.1 -e "FAKETIME=@2026-10-31 14:56:30" -e FAKETIME_DONT_FAKE_MONOTONIC=1
-e TZ=UTC` and `-Duser.timezone=Asia/Seoul` in `JAVA_OPTS` (the plugin clock is the JVM default zone), no volume for
JENKINS_HOME, and remove it with `docker rm -f -v`. With `@` each process starts at that time and the clock runs on.
