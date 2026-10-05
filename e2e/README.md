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

## CI runner and e2e coverage (`ci/`)

The scripted part of the e2e passes (the e2e-11 round-3 checks as `r14/round3.py`, the e2e-12 crawl with its dialog
cycles and targeted checks, the e2e-13 role-strategy profile as `r14/role/`, the e2e-14 DEF-07 recheck and the e2e-15
multibranch checks) as N independent shards. Each shard runs on a fresh Jenkins with the JaCoCo agent in its JVM, so
the run also records which plugin code it executed; `ci/coverage-diff.py` then lists the lines a branch changed that no
scenario executed. Made for GitHub Actions (one shard per runner); it runs the same way locally.

### Run it locally

```bash
mvn -ntp clean package -DskipTests                     # target/batch-control.hpi and target/classes, one build
python3 -m venv venv && venv/bin/pip install -r e2e/ci/requirements.txt    # Python >= 3.10
venv/bin/python -m playwright install chromium         # or BC_BROWSER_CHANNEL=chrome for the installed Chrome
PY=$PWD/venv/bin/python e2e/ci/run.sh 1/5              # shard 1 of 5; artefacts in e2e/ci/out/1/ (then 2/5 ... 5/5)
venv/bin/python e2e/ci/shard.py plan-all 5             # which units each shard runs, estimated minutes
BC_UNITS=round3,role PY=$PWD/venv/bin/python e2e/ci/run.sh 1/1     # the setup plus just these units
venv/bin/python e2e/ci/coverage-diff.py --exec 'e2e/ci/out/*/jacoco-*.exec' --base main   # -> e2e/ci/out/coverage/summary.md
venv/bin/python e2e/ci/selftest_content.py             # the crawl's content checks on synthetic pages (no Jenkins)
```

`run.sh` uses its own compose project (`bc-cov-<k>`), its own container names and ports 18080/18025, so it can run
next to the regular stack; it never touches the `batch-control-e2e` volume and removes its own containers and volume
at the end (`BC_KEEP=1` keeps them). One shard per checkout at a time: the drivers write to `r14/out/`,
`r15/out/` and `screenshots/`, which `run.sh` moves into the shard's artefact directory (an earlier run's output is
moved to `ci/out/_previous/` first). For shards in parallel on one machine, use separate checkouts and different
`BC_PORT`, `BC_MAIL_PORT` and `BC_PROJECT`.

### What a shard does

1. Uses `target/batch-control.hpi` (builds it only when it is missing and `BC_BUILD` is not `never`) and writes its
   sha256 and the git HEAD to `build-info.txt`.
2. Writes `e2e/.env` with random passwords when there is none (CI; nothing secret is committed), masked in the log.
3. `ci/fetch-jacoco.sh`: the JaCoCo agent and CLI pinned in `ci/jacoco.env`, downloaded from Maven Central and checked
   against the pinned sha256 (a mismatch is fatal), cached in `ci/.cache/jacoco/`.
4. `docker compose -p bc-cov-<k> -f docker-compose.yml -f compose.prefix.yml -f compose.coverage.yml up -d --build`
   from an empty JENKINS_HOME, then waits for the login page, the admin API and `batch-control` active.
5. `ci/shard.py run <k>/<N>`: the setup (`r7/arrange.py`, `r8/arrange.py`, `r8/arrange_side.py`, `r12/arrange.py`,
   `r14/arrange_fast.py`, `ci/arrange_ci.py`, `r14/seed_fast.py`, `r14/seed.py`, `r14/seed_paging.py`,
   `ci/seed_markup.py`: the e2e-14 arrangement), then the shard's units in the e2e-14 order. One log per step in
   `logs/`, `summary.md` / `summary.json` with the verdicts and durations.
6. Always, also after a failure: a coverage dump through the script console (`snapshot-before-stop.exec`), a graceful
   `docker compose stop -t 180` (JaCoCo writes the exec file when the JVM exits), the final `jacoco-<k>.exec`, the
   container log (`jenkins.log`, SEVERE count in `build-info.txt`), the drivers' logs and screenshots (`driver/`), then
   `down -v`.
7. Exit 1 when a step failed, 2 when the stack did not start, else 0.

A step fails when its driver exits non-zero or prints one of the drivers' own failure markers: a line starting with
`FAIL ` (`round3.py`, `def07.py`, the crawl's content checks) or `"EXCEPTION":` (`misc.py` logs a section's exception
that way). `r15/check.py` writes `"ok": false` as a hint for the reviewer (e2e-15 judged each row by hand); the runner
judges its rows from `r15/out/check.jsonl` and leaves out exactly the known or provoked items of e2e-13/14/15
(`r15_problems` in `ci/shard.py`: core's GET 405/404 links, the build history widget's repeated status labels, the
400 of the deliberately empty submit, core's D-70 MIME line and core's breadcrumb `reading 'replace'` page error,
U-1); anything else fails. The assertions of the drivers are unchanged. The crawl, `actions.py` and `jobui.py` are
observational (they log rows for review, as in the e2e-12/14 reports); `summary.md` counts their notable rows (controls
that do nothing, broken links, console errors, exceptions) without turning them into a verdict.

Isolation between units: the admin crawl clicks every control it may, including the Dismiss of the strategy monitor on
`/manage/` (e2e-12 Known 5), which disables that monitor for good. `misc.py` S then reverts the strategy and finds no
"Install the Batch Control variant", and the rest of the shard would run without a Batch Control strategy (every grant
check failing for that reason alone). The `crawl-admin` unit therefore ends with `ci/arrange_ci.py monitors`, which
re-enables the plugin's monitors.

### Units and shards

`ci/shard.py units` lists the units with their steps. A unit is a group of driver invocations that must run in order on
one Jenkins (for example the crawl of one role on both job UIs, with the new job page flag set per account in between).
Units are assigned with a deterministic longest-processing-time split over the measured minutes, so the same N always
gives the same shards; `role` replaces the authorization strategy and always runs last in its shard.

Measured 2026-10-05 (MacBook, Docker Desktop, Playwright Chromium headless, two shards at a time on one machine; the
image was cached, so no build time is included). Every shard: 2 min until Jenkins is ready, 3.3 min setup.

| Shard of 5 | Units | Steps | Driver minutes (incl. setup) | Wall minutes | Lines covered by the shard |
|---|---|---:|---:|---:|---:|
| 1/5 | `def07`, `crawl-requester`, `crawl-reqonly`, `role` | 34 | 24.8 | 27.0 | 57.3% |
| 2/5 | `crawl-admin`, `jobui-new-admin`, `jobui-classic-others` | 22 | 28.7 | 30.9 | 57.9% |
| 3/5 | `crawl-approver-1`, `actions`, `jobui-new-requester`, `jobui-classic-reqonly`, `misc`, `round3` | 24 | 21.3 | 23.4 | 65.9% |
| 4/5 | `crawl-manager`, `crawl-nobc`, `jobui-classic-requester`, `targeted`, `multibranch` | 39 | 24.5 | 26.7 | 59.7% |
| 5/5 | `jobui-new-reqonly`, `jobui-new-others`, `jobui-classic-admin` | 19 | 24.2 | 26.4 | 52.0% |

All 138 steps passed on main (`cb5ad5d`); the five exec files merged: **lines 67.3% (6466/9608), instructions 67.3%,
branches 51.7%, methods 79.7%** of `io.jenkins.plugins.batchcontrol`. The crawls reported no content defect. N is
free (`run.sh 1/3` works, `shard.py plan-all <N>` shows the split); 5 keeps every shard under half an hour.

### Coverage

- `compose.coverage.yml` adds `-javaagent:...=includes=io.jenkins.plugins.batchcontrol.*,output=file,append=true,...`
  through `JAVA_TOOL_OPTIONS` (independent of the `JAVA_OPTS` of the other files). Groovy call-site classes that
  Groovy generates as `<PluginClass>$<method>` when the script console calls plugin code are excluded by class loader.
- Flush: `output=file` writes on a normal JVM exit. The file exists but is empty until then (JaCoCo opens it at
  startup); `docker compose stop` (SIGTERM, 180 s grace) lets Jenkins shut down and the shutdown hook write it. A
  SIGKILL would lose the data, which is why `run.sh` first dumps through the script console
  (`org.jacoco.agent.rt.RT.getAgent().dump(false)`); with `append=true` the final file then holds two sessions, the
  dump and the exit, and merging them is idempotent. `jacococli execinfo` shows the sessions.
- `ci/coverage-diff.py --exec <files or globs> --base <ref>` (Python 3.10+, no network): verifies the CLI jar's sha256,
  merges the exec files (`jacococli merge`), writes `jacoco.xml` and an HTML report (`jacococli report`), runs
  [diff-cover](https://github.com/Bachmann1234/diff_cover) on `git diff -U0 <base>...<head> -- src/main/java` and
  writes `summary.md`: overall e2e coverage per package; per changed file the changed executable lines, covered, and
  the line numbers not covered; changed Java files without an executable changed line; changed files JaCoCo cannot
  measure (Jelly, JavaScript, properties, help HTML under `src/main/resources` and `src/main/webapp`). Executability
  comes from JaCoCo: only lines with instructions (`<line nr mi ci>`) count, so comments, blank lines and declarations
  are never reported. With `--annotations on` (default under GitHub Actions): `::warning file=...,line=...,endLine=...`
  per not-covered range (at most `--max-annotations`, default 50, then one notice with the totals) and `summary.md`
  appended to `$GITHUB_STEP_SUMMARY`.
- Exit codes: 0 report written, whatever the coverage (non-blocking); 1 `--fail-under <pct>` given and the changed-line
  coverage is lower; 2 usage or tool error; **3 the execution data does not match the class files**: any JaCoCo
  "Execution data for class ... does not match" (class files of another build), a class in the exec that the given
  classes do not have, or no matched data at all. JaCoCo identifies a class by a checksum of its bytes, so the report
  must use the `target/classes` (or the `.hpi` itself, `--classes target/batch-control.hpi`) of the build whose hpi
  ran; a mismatch would otherwise show those lines as not covered.
- Another branch or checkout: `--repo <checkout>` (defaults for `--classes`/`--sources` follow it), `--base <any local
  ref or sha>`, `--head` (default `HEAD`), `--worktree` to count uncommitted edits too. The script and the JaCoCo
  jars come from this checkout, so it also works on a branch that does not have `e2e/ci/` yet, for example:
  `python3 e2e/ci/coverage-diff.py --repo ../r6-scope --base main --exec ../r6-scope/e2e/ci/out/*/jacoco-*.exec`.
  To record coverage in such a branch's own e2e run without `ci/run.sh`, add this checkout's overlay with an absolute
  agent path: `BC_JACOCO_AGENT=$PWD/e2e/ci/.cache/jacoco/jacocoagent.jar docker compose -p <p> -f <branch>/e2e/docker-compose.yml
  -f <branch>/e2e/compose.prefix.yml -f $PWD/e2e/compose.coverage.yml up -d`, stop it with `docker compose ... stop -t 180`
  and copy `/var/jenkins_home/jacoco/jacoco-local.exec` out of the container.

- How the tooling was checked (2026-10-05, the five exec files of the shard run above, `target/classes` of the same build):
  - Positive and negative control on existing code, in a scratch repository whose `ctl-head` is the source the hpi was
    built from and whose `ctl-base` differs from it only by a trailing `/* ctl */` on `RunRequestService.java` lines
    272 (a comment), 284 (the reason length check of every submit), 324 (`require(id)` of every approve), 291 (the
    `throw` for an undeclared parameter, which no scenario provokes) and 708 (the loop of `invalidateForJob`, which no
    scenario calls): `--base ctl-base --head ctl-head` reports 4 changed executable lines (272 is a comment), **284 and
    324 covered, 291 and 708 not covered**, two `::warning` annotations, exit 0.
  - Negative control on new code: a line added to `MoveRefusal.isCreateMissing` (recompiled; no scenario moves an item)
    is reported as not covered (line 71, one annotation).
  - Class mismatch: classes compiled from a slightly changed `store/Ids.java` exit 3 naming
    `io/jenkins/plugins/batchcontrol/store/Ids`; classes without `ui/DiffSummary` exit 3 naming that class; an empty exec
    exits 3 ("no execution data matches any class"). Each prints a `::error` under annotations.
  - Flush: every final exec holds two sessions, the script-console dump and the shutdown hook one second later (the
    second session exists only if the hook wrote), and both give the same coverage per shard. `jenkins_exit` 143 in
    `build-info.txt` is the JVM's normal exit status after SIGTERM, not a crash.
  - `--out` is emptied first (also because `jacococli merge` appends to an existing destfile); an `--out` that contains
    the `--exec` inputs is refused (exit 2).

### Content checks in the crawl

`r14/crawl.py` checks every page it reaches (status < 400, every role, both job UIs) and prints `FAIL content ...`
once per check, URL pattern and finding: an empty cell on a Batch Control page in a column whose value always exists
(`EXPECTED_COLUMNS`: ID, Job, Requester, Approvers, Created, Status, State, At, Scope, Action(s), Result, ...); a server
filesystem path in the visible text (`/var/`, `/tmp/`, `/home/`, `jenkins_home`, `JENKINS_HOME`, `StoreLocation=`,
`WEB-INF/`, `C:\`); an HTML entity shown as text (double escaping); `<script` shown as text; and the probe that
`ci/seed_markup.py` files as a run request and a grant request (reason and rejection comment
`bc-markup-probe <b>b</b> & "q" <img src=x onerror=window.__bcCanary=1>`) not reading exactly as typed, or its
`onerror` canary running; and (e2e-16) a scope written with a scope type that D-71 removed (`JOB:`, `FOLDER:`,
`FOLDER_ONLY:`). Text in `pre`, `code` and `textarea` is not checked. Empty cells in optional columns (Decided,
Aborted by, Comment, Parameters, ...) are logged once per pattern as `empty-optional` rows without a verdict.

Raw enum values (e2e-16): an UPPER_SNAKE_CASE constant (`GRANT_REVOKE`, `APPROVED_REQUEST`, ...), the scope prefix
`ITEM:` and the state and action words of the plugin's vocabulary (`PENDING`, `APPROVED`, ..., `CONFIGURE`, `CREATE`,
`DELETE`) shown as text are logged once per URL pattern and value as `raw-enum` rows with result INFO, without a
verdict: they are the known UX review items UX-4/UX-5, which the owner has deferred. `summary.md` lists them under
`raw_enum_values` (value: URL patterns) for the report's UX section.

`ci/selftest_content.py` checks the checks without Jenkins: it runs the crawl's own `CONTENT_JS` and `content_checks()`
(read from `r14/crawl.py`'s source) in headless Chromium on a synthetic page that plants each finding next to
look-alikes that must stay silent (a path in `<pre>`, a URL path, empty optional cells), and on a clean page with the
probe rendered correctly (also shortened with an ellipsis). Both must give exactly the expected findings and raw-enum
rows (2026-10-06: 9 defect findings and the raw-enum values `FOLDER_ONLY`, `GRANT_REVOKE`, `ITEM:`, `PENDING` on the
first page; no defect and only `JOB_NAME` on the second, where `GRANT_REVOKE` and `ITEM:` inside `<code>` and title-case
words must stay silent). Run it after editing the checks; CI can run it in the `coverage` job.

### Fixture preconditions

The last setup step, `ci/preconditions.py`, asserts the seeded state the drivers assume before any unit runs (read-only:
REST and the script console): the plugins a scenario needs are active (`batch-control`, `file-parameters`, folders,
matrix-auth, Pipeline, multibranch, git, role-strategy, job-dsl); both switches on, `approver-1` listed, the Batch Control
strategy installed; each account's permission profile (`requester`, `reqonly`, `approver-1`, `manager`, `nobc`,
`configurer`, `auditor`, `admin`: what it must and must not hold, read on `batch-pipeline`, which no seeded window names);
the seed jobs and folders (`batch-daily` approval-required with DATE/MODE/SECRET, `batch-cron`'s timer, `batch-pipeline`,
`team-mb` a multibranch project); the ids of `r14/out/ids.json` resolving with the status their names say; the markup
probe stored. A failure fails the setup, and every unit of the shard is then BLOCKED rather than passing for the wrong
reason. `r16/arrange.py` ends the same way for the e2e-16 fixtures (parameter types of each r16 job, the window holders
holding Item/Read only after their leftover windows are revoked).

### CI contract (for `.github/workflows`)

Proposed workflow `e2e.yml`, non-blocking at first (not a required status). Pin every action by commit SHA as
`build.yml` does; `permissions: contents: read` only (no PR comments, no uploads to third parties), so fork pull
requests work with the default read-only token.

| Job | Runner and tools | Steps |
|---|---|---|
| `build` | `ubuntu-latest`; `actions/setup-java` (temurin 21, `cache: maven`) | `mvn -ntp -B clean package -DskipTests`; upload artifact `e2e-build` = `target/batch-control.hpi` + `target/classes/` (one build: the classes must be the ones in the hpi) |
| `e2e` (matrix `shard: [1, 2, 3, 4, 5]`, `fail-fast: false`, `timeout-minutes: 90`) | `ubuntu-latest` (Docker and Compose v2 are preinstalled); `actions/setup-python` 3.12 | download `e2e-build` into `target/`; `pip install -r e2e/ci/requirements.txt`; `python -m playwright install --with-deps chromium`; `BC_BUILD=never PY=python e2e/ci/run.sh ${{ matrix.shard }}/5`; `if: always()`: `cat e2e/ci/out/${{ matrix.shard }}/summary.md >> "$GITHUB_STEP_SUMMARY"` and upload artifact `e2e-shard-${{ matrix.shard }}` = `e2e/ci/out/${{ matrix.shard }}/` (exec, summaries, step logs, `jenkins.log`, screenshots; keep 7-14 days) |
| `coverage` (`needs: [build, e2e]`, `if: always() && needs.build.result == 'success'`) | `ubuntu-latest`; `actions/checkout` with `fetch-depth: 0` (the merge base must be present); setup-java 21 (to run the JaCoCo CLI) and setup-python 3.12 | download `e2e-build` into `target/`; download `pattern: e2e-shard-*` into `e2e/ci/out/` (without `merge-multiple`: one directory per artifact, `e2e/ci/out/e2e-shard-<k>/jacoco-<k>.exec`); `pip install -r e2e/ci/requirements.txt`; optionally `python -m playwright install --with-deps chromium` and `python e2e/ci/selftest_content.py` (about 10 s); `e2e/ci/fetch-jacoco.sh`; `python e2e/ci/coverage-diff.py --exec 'e2e/ci/out/*/jacoco-*.exec' --base "$BASE" --annotations on` with `BASE=${{ github.event.pull_request.base.sha }}` on pull requests (`${{ github.event.before }}` on a push to main; skip the step when it is all zeros); upload `e2e/ci/out/coverage/` as `e2e-coverage` |

- Merging: `coverage-diff.py` merges whatever `--exec` matches (one exec per shard, each with two identical sessions)
  with `jacococli merge`; to merge by hand: `java -jar e2e/ci/.cache/jacoco/jacococli.jar merge e2e/ci/out/*/jacoco-*.exec
  --destfile merged.exec` into a file that does not exist yet (`merge` appends to an existing one). A missing shard
  (failed to start) only lowers the coverage; the report says how many exec files it merged.
- Exit codes: `run.sh` 1 = a scenario failed (red shard), 2 = the stack did not start; `coverage-diff.py` 0 whatever
  the coverage, 3 = execution data and classes do not match (the build/download wiring is broken), 2 = tool error.
  Leave `--fail-under` off until the owner sets a threshold.
- Services and ports: none as workflow `services:`. `run.sh` starts Jenkins (`localhost:18080/jenkins`) and mailpit
  (`localhost:18025`) with `docker compose`, building `e2e/Dockerfile` (`jenkins/jenkins:2.568.3-lts-jdk21` plus the
  plugins of `e2e/plugins.txt` from the Jenkins update centre) and pulling `axllent/mailpit`. Network needed:
  Docker Hub, updates.jenkins.io / get.jenkins.io, Maven Central (JaCoCo), PyPI, the Playwright CDN.
- Secrets: none. `run.sh` writes `e2e/.env` with random passwords and masks them (`::add-mask::`).
- Durations: per shard 23-31 min measured locally ("Units and shards" above: 2 min start, 3.3 min setup, the units);
  on a runner add the image build (the base image and the plugins of `plugins.txt`, a few minutes without a cache),
  the Playwright install (about 1 min) and the artifact upload. Expect 35-45 min per shard, the five in parallel, and
  2-3 min for `coverage`. `timeout-minutes: 90` leaves room; `BC_STEP_TIMEOUT` (default 2700 s) bounds one step.
- Independence from the request form: `run.sh` and `shard.py` only start Jenkins, run the listed drivers and judge
  their output. The drivers that fill the grant form (`round3.py`, `actions.py`, `jobui.py`, the crawl's dialog
  cycles) post the D-71 form (one item name, no scope type field); `ci/seed_markup.py` goes on with the run request
  probe alone, printing a `WARN`, if its grant probe cannot be filed. `BC_UNITS` runs a subset while a driver is being updated.
- Tools chosen (and not chosen): changed-line coverage by [diff-cover](https://github.com/Bachmann1234/diff_cover)
  (reads JaCoCo XML; `coverage-diff.py` adds only the JaCoCo CLI calls, the class-mismatch check, the list of
  non-measurable files and the capped annotations). A PR-comment action such as `madrapps/jacoco-report` would need
  `pull-requests: write` and does not work for fork PRs with the default token; the job summary and annotations
  carry the same information read-only. Uploads to coverage services (Codecov, Coveralls) would publish data to a third
  party and need the owner's decision; they are not used.

### Portability changes to the drivers (2026-10-05)

- `r6/lib.py`: `BC_BROWSER_CHANNEL` (`chrome` default, `chromium` = Playwright's own Chromium), headless unless
  `BC_HEADED=1`.
- `r14/lib.py`: `jenkins_today()` / `month()` read the controller's date; `r14/def07.py` and `r14/round3.py` used the
  literal months of 2026-10-04 (`2026-10`, `2026-09`, `from=2026-09-05&to=2026-10-04`, `changes/2026-10.jsonl`,
  `runs.csv?from=2026-09-01&to=2026-10-31`) and now use the current ones. The checks are otherwise unchanged.
- `r14/crawl.py`: the content checks above, and `BC_CRAWL_LOG` names the log (`crawl-role` for the crawl under the
  role profile).
- Base URL, passwords and ports were already configurable (`BC_BASE`, `e2e/.env`, `BC_PORT`). The `mails()` helpers
  of the lib files still read mailpit on 8025; no driver of the CI set calls them. The Node drivers in
  `browser/` are not part of the CI set (`browser/explore.mjs` writes to a fixed local scratch path; it is an ad-hoc
  orientation script, not evidence).

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
| `compose.prefix.yml` | `docker compose -f docker-compose.yml -f compose.prefix.yml up -d --build`; back with `docker compose up -d jenkins` | Jenkins under the context path `/jenkins` (`http://localhost:8080/jenkins/`, Jenkins URL set through `BC_JENKINS_URL`) with the new job page experiment on for every user (`-Dnew-job-page.flag.defaultValue=true`; a user can still turn it off on `/me/experiments/`) (e2e-06) |
| `compose.jdk25.yml` | `docker compose -f docker-compose.yml -f compose.prefix.yml -f compose.jdk25.yml up -d --build` | The same image built on `jenkins/jenkins:2.568.3-lts-jdk25` (Dockerfile `ARG JENKINS_TAG`), tagged `batch-control-e2e-jenkins:2.568.3-jdk25`; shares the JENKINS_HOME volume, so `down -v` first for a fresh home (e2e-11, R4-1) |
| `compose.coverage.yml` | last overlay, after `ci/fetch-jacoco.sh`; normally through `ci/run.sh` (section below) | JaCoCo agent in the Jenkins JVM (`JAVA_TOOL_OPTIONS`), exec file `/var/jenkins_home/jacoco/jacoco-${BC_SHARD}.exec`, own container names `${BC_CONTAINER}-jenkins` / `-mail` so it can run next to the regular stack (use another `-p` project and other `BC_PORT`/`BC_MAIL_PORT`); `BC_JACOCO_AGENT=<absolute path>` when used with another checkout's compose files |

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

## e2e-06 driver (`r6/`, Python)

For a machine without Node.js: `r6/lib.py` is `extra/lib.mjs` ported to Python Playwright
(`python3 -m venv venv && venv/bin/pip install playwright requests`; it drives the installed
Google Chrome). Base URL `http://localhost:8080/jenkins` (override with `BC_BASE`), screenshots to
`screenshots/run-6/`, logs to `r6/out/` (git-ignored). `arrange.py` adds the `mover1..3` accounts
and the `prod/` folder (script console, arrangement only; JCasC drops the accounts' matrix entries
on the next boot), `grants.py <user> <JOB|FOLDER> <full name> <ACTIONS> [minutes]` requests a
window over REST and has `approver-1` approve it, `move.py <id> <user> <source> <destination>
<refused|moved>` drives the folders Move page (D-59), `s2*.py` role-strategy 918 pages, `s3.py`
global matrix conversion, `s4*.py` new job page, `s5*.py` tab bar and destructive controls,
`s6.py` regression spot-checks.

The `rebuild` plugin's "Rebuild Last" entry has a null URL on a job without builds, which makes
the new job page's "More actions" menu fail to open (core JS `menuItem` throws). For new job page
menu checks disable it: `docker exec batch-control-e2e touch /var/jenkins_home/plugins/rebuild.jpi.disabled`
and restart; remove the marker afterwards.

## e2e-07 driver (`r7/`, Python)

Round E2E-2 (full regression, 2026-10-03). `r7/lib.py` loads `r6/lib.py` and only moves the
screenshots to `screenshots/run-7/` and the logs to `r7/out/`. `arrange.py` adds `mover1..3`,
`folderreq` (BatchControl/Request only on the `team/` folder matrix), `reqhist` (Request +
ViewHistory, no Build), `team/sub/deep-job` and `prod/`. Scenarios: `b_d38a.py`, `b_rerun.py`
(D-38a), `c_run.py`, `c_act.py ACTIVATE|HOLD`, `c_grant.py`, `c_del.py`, `c_misc.py`,
`c_switch.py`, `c_hist.py`, `e_dark.py`, `s3.py`, `a_ui.py`, `n_newjob.py`, `n_classic.py`,
`r_role.py` (applies `casc/profile-role.yaml` through the CasC page and back), plus copies of
`r6/move.py` and `r6/grants.py`. JCasC re-applies the matrix on every boot, so re-run
`arrange.py` after a restart. `scripts/cli.sh` needs `BC_PREFIX=/jenkins` under `compose.prefix.yml`.

## e2e-11 driver (`r11/`)

(e2e-16: `s_folder_only.py` and `s_folder_only_ui.py`, which checked the reach of the D-65 folder-only window, were
removed with that scope type; their D-71 counterparts are `r16/items.py` F and D and `r14/round3.py` A.)

Round E2E-6 (hosting review round 3, 2026-10-04). `r11/lib.py` is `r10/lib.py` with screenshots in
`screenshots/run-11/` and logs in `r11/out/`. Fresh JENKINS_HOME under `compose.prefix.yml`, arranged with
`r7/arrange.py`, `r8/arrange.py`, `r8/arrange_side.py` and `r11/arrange.py` (users `classic`, the requester's
permissions with the new job page turned off, and `fonly`, Read+Move+RequestGrant; `ops/a`, `ops/sub/b`, the
multibranch `ops/mb`, the non-approval job `fast`). `seed_fast.py` queues 60 admin builds of `fast` for the
dashboard bound. Scenarios: `s_dialogs.py [ADGH]` (grant and Request Run dialogs on the new UI, D-60, unknown ids),
`s_rundlg_err.py`, `s_approve.py <run id> <grant id>`, `s_classic.py` (classic sidebar dialogs, folder-page dialog
filing a window on the folder), `approve_api.py` / `grant_api.py` (arrangement over HTTP), `move.py` (copy of `r10/move.py`), `s_revoke.py` / `s_revoke_holder.py`, `s_activation.py`,
`s_pages.py`, `s_width.py`, `s_tabs.py` (turns `new-build-page.flag` on for admin and back), `s_modellink.py`,
`s_lacks_build.py`, `s_newmenu.py`, `s_d38b.py`, `r_role.py` and `d59b.py` (both replace the matrix profile; re-run
the arrange scripts afterwards), `s_dark_monitor.py`, and `j25.py` for the Java 25 smoke under `compose.jdk25.yml`.
Diagnostics kept as evidence: `console_check.py`, `recon_overflow.py`, `probe_tick2.py`.

## e2e-10 driver (`r10/`)

Round E2E-5 (targeted re-check, 2026-10-03). `r10/lib.py` is `r9/lib.py` with screenshots in
`screenshots/run-10/` and logs in `r10/out/`. Fresh JENKINS_HOME under `compose.prefix.yml`, arranged with
`r7/arrange.py`, `r8/arrange.py`, `r8/arrange_side.py`. Order: `setup_grants.py a` (mover1 windows approved by
approver-2), `move.py MV mover1 prod/mv-job ops moved`, revoke the DELETE window
(`POST /batch-control/grants/active/<id>/revoke`), `setup_grants.py b <id>` (pending 1-minute request), `s89.py`
(grants tables at 1280 px; `TAG=-b` for a second pass), `s86.py <1-minute grant id>` / `s86b.py` (monitor sentence,
Mark as reviewed, old URL), `s71.py`, `s74.py`, `s74b.py`, `s83.py` (on `prod/y`), `smoke.py` (after
`grants.py requester JOB team/app-1 CONFIGURE 1`), `s85.py`, and last `d59b.py arrange|create|viol|cc <bool>` with
`move.py` (D-59b: applies `casc/profile-role-naming.yaml` and role-strategy's role-based naming strategy; it
replaces the matrix profile, so run it last or reset).

## e2e-09 driver (`r9/`)

Round E2E-4 (backlog fixes #71-#91, 2026-10-03). `r9/lib.py` is `r8/lib.py` with screenshots in
`screenshots/run-9/` and logs in `r9/out/`. Arrange with `r7/arrange.py`, `r8/arrange.py` and
`r8/arrange_side.py`; `s74.py` adds `moverd` (Read/Move/Delete on `prod/`, Discover only on `ops/`) and
`s73.py` the ListView `nightly`. One script per issue: `s71.py` (activation form at
`<item>/batch-control-activation/`, D-64), `s72.py`/`s72b.py` (new job page card, classic page),
`s73.py` (Request Change Permission through a view), `s74*.py`, `s75.py`, `s76.py` (badges against the
admin's lists), `s83.py` (refusal links; run `grants.py mover1 FOLDER ops CREATE 60` first), `s85.py`,
`s86.py`, `s87.py`, `s88.py`, `s89.py`; regression: `smoke.py` (needs a 1-minute CONFIGURE window on
`team/app-1` for requester), `d38b.py`, `s3.py`, `r_role.py`, `checklist.py`, `c16.py`. The scripts
create state and are not idempotent: run them once on a fresh JENKINS_HOME, in that order.

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

## e2e-08 driver (`r8/`)

Round E2E-3 (targeted, 2026-10-03). `r8/lib.py` is `r7/lib.py` with screenshots in
`screenshots/run-8/` and logs in `r8/out/`. `arrange.py` adds `opsreq` (Overall/Read globally;
BatchControl/Request + Item/Read only on folder `ops/`), `mover1`, and the one-minute timer jobs
`ops/cron-a`, `prod/mv-job`, `prod/mvf/inner-job`, `prod/adm-job`, `prod/admf/adm-inner` (timer not
blocked); `arrange_side.py` adds `side/job-b` where `opsreq` has Item/Read only. `activate.py <job>...`
activates jobs through the real ACTIVATE request flow over REST (requester submits, approver-1
approves). Scenarios: `d38b.py` (D-38b), `move.py` + `mvstate.py` (D-59a), `rebuild.py` /
`rebuild2.py` (rebuild plugin and the new job page menu), `monitor.py` (Mark as reviewed),
`smoke.py`. Run under `compose.prefix.yml`; re-run `arrange.py` after a restart (JCasC drops the
added matrix entries).

## e2e-12 driver (`r12/`)

Round E2E-7 (every button, link and dialog, 2026-10-04). `r12/lib.py` loads `r6/lib.py` with screenshots in
`screenshots/run-12/` and logs in `r12/out/`. Fresh JENKINS_HOME under `compose.prefix.yml`, arranged with
`r7/arrange.py`, `r8/arrange.py`, `r8/arrange_side.py`, `r12/arrange.py`, then `seed_fast.py`, `seed.py` (requests,
activations, windows, changes and incidents through the plugin's own endpoints; ids to `out/ids.json`) and
`seed_paging.py`. `run_crawl.sh` (`crawl.py <role> <new|classic>` for six roles, `set_flag.py` switches the new job
page per account), `analyze.py`/`summarize.py` condense `out/crawl.jsonl`. State-changing controls: `actions.py`;
entry points and dialog cycles: `jobui.py <new|classic>`; targeted: `misc.py [CHPBTRSMK]`, `errpages.py`,
`helpcheck*.py`, `s_incident.py`, `s_listpager.py`, `s_monitor.py`.

## e2e-16 driver (`r16/`)

Hosting review round 6 (D-71..D-74, 2026-10-05/06). `r16/lib.py` loads `r6/lib.py` (screenshots in
`screenshots/run-16/`, rows in `r16/out/<driver>.jsonl`); every assertion prints `PASS {...}` / `FAIL {...}` and a
driver exits non-zero on a FAIL, so `ci/shard.py` judges them directly. `arrange.py` (idempotent, run first by every r16
unit) creates the accounts `w16`, `w16b` (window holders: Item/Read and RequestGrant only) and `vh16` (Request and
ViewHistory, no Build), the folders and jobs (core `file`, `stashedFile`, `base64File` and password parameters; jobs
that fail once when armed), and ends with the fixture preconditions above. Drivers and sections:

- `items.py [JFMKLD]`: one-item windows (D-71): kind with icon and no scope selector, a job window reaches only the job,
  a folder window only the folder, CREATE directly in a regular folder only, CREATE/DELETE refused on a computed folder,
  legacy scope types not approvable, DELETE only on a job.
- `rename.py [UEGCA]`: no rename through any window (D-71c) by the Rename page and every URL form; allowed for an
  administrator and for standing Item/Configure.
- `follow.py [WOVMXCN]`: windows follow a rename by an administrator or by `configurer` (standing Item/Configure) and an administrator's move (job; folder with a nested job), a new item at
  the old name gets nothing (D-74 (3)); a folder deleted by the administrator: its items' DELETE records name the
  administrator, not SYSTEM, and the windows below it end (1864bdc); `configurer`'s Delete Folder is refused (D-71);
  refused moves recorded once per minute (D-73, waits 62 s); a CREATE window under core's pattern naming strategy
  (T-08-168, restored afterwards).
- `params.py [FDSEB413CRUXY]`: typed values through the Request Run page and the job-page dialog (core file, stashedFile,
  base64File, password); the values file `requests/run/<id>.values.xml` and its removal (D-74 (1)); 413 at both stages
  (declared length; a chunked body judged on its kept size); repeated name and U+0000 as 400 form errors; disposal of
  temporary files; the History, CSV and Dashboard surfaces.
- `rerun.py [PFSVI]`: incident rerun with a secret, a core file (direct) and a stashed file (validated fallback form,
  D-72a); the `fromRerun` reference validated (ViewHistory, same job, unknown and hostile values); incident actions need
  ViewHistory (SPEC 11).
- `d60.py [RNFB]`: a refused direct build leads to the prefilled Request Run form: a run parameter carried (T-06-103),
  from the new job page's parameters dialog too (G-M5); files and secrets never carried.
- `names.py`: the CREATE name restriction in the #107 optionalBlock.
- `durable.py`: a window's end survives a failed grant write (the grants directory made read-only) and a restart
  (`docker restart $BC_CONTAINER-jenkins`; 6325e85). JCasC re-applies the authorization strategy at boot and drops the
  arrangement's permissions, so the driver runs `arrange.py` again after the restart and checks a control window; in CI
  it is a `last` unit.

Against any running stack: `BC_BASE=http://localhost:<port>/jenkins BC_BROWSER_CHANNEL=chromium python r16/arrange.py`,
then the drivers (the setup of `ci/shard.py` must have run on that Jenkins for the seeded accounts and jobs).

## e2e-15 driver (`r15/`)

Multibranch activation page after the DEF-08 fix (2026-10-04). `r15/lib.py` is `r14/lib.py` with screenshots in
`screenshots/run-15/` and logs in `r15/out/`. Fresh JENKINS_HOME under its own compose project:
`docker compose -p bc-e2e15 -f docker-compose.yml -f compose.prefix.yml up -d --build` (no extra plugins: the image
already has `workflow-multibranch` and `git`). `arrange.py` adds "Discover branches" to `team-mb` if missing (homes
seeded before the e2e-15 seed fix) and indexes it (`main`, `feature-1`). `check.py crawl` (requester and admin, new job
page on and off: every side-panel, app-bar, breadcrumb and main-panel control on the activation pages of `team-mb`, its
two branch jobs, `batch-pipeline` and `batch-daily`; approver-1 404), `check.py submit <flag> <page> <user>`, `menu.py`
(app-bar More actions on each job page vs its activation sub-page; disable `rebuild` first for `batch-daily`, see the
e2e-06 note), `side_dialog.py`, `folder_compare.py`, and `probe.py` (diagnostic). Stop with
`docker compose -p bc-e2e15 -f docker-compose.yml -f compose.prefix.yml down -v`.

## e2e-14 driver (`r14/`)

Final check of `main` after rounds 3 and 4 (2026-10-04). `r14/lib.py` is `r12/lib.py` with screenshots in
`screenshots/run-14/` and logs in `r14/out/`; the r12 scripts are copied unchanged apart from their docstrings.
Fresh JENKINS_HOME under its own compose project:
`docker compose -p bc-e2e14 -f docker-compose.yml -f compose.prefix.yml up -d --build`, then the r12 arrangement plus
`arrange_fast.py` (removes the new-job lock from `fast`, which the script console creates while run control is on)
before `seed_fast.py`. New scripts: `def07.py <new|classic> [role ...]` (e2e-12 DEF-07 recheck: filter URLs, pager and
export links keep the filter), `round3.py [ABCDEFGHI]` (the e2e-11 round-3 checks with assertions), `probe_pageerror.py`
(diagnostic). `role/` holds the e2e-13 role-strategy drivers with `BC_BASE` defaulting to the `/jenkins` prefix;
`grant_overlay.py` now opens the grants page dialog (D-66). Run `role/` last: `setup.py` replaces the matrix profile.
Stop with `docker compose -p bc-e2e14 down -v`.

## e2e-13 driver (`r13/`)

role-strategy 927 forwarding check (D-35g, 2026-10-04). `r13/lib.py` loads `r6/lib.py` with base URL
`http://localhost:8080` (plain `docker-compose.yml`, override with `BC_BASE`), screenshots in
`screenshots/run-13/` and logs in `r13/out/`. `PY=<venv python> r13/run_all.sh` starts a fresh
JENKINS_HOME under the separate compose project `bc-e2e13` (the shared `batch-control-e2e` volume is
left alone) and runs, in order: `setup.py` (applies `casc/profile-role.yaml`), `manage_roles.py`
(Add Role dialog, pattern check), `assign_roles.py` (Add User or Group dialog, sid check, assign,
unassign, remove), `grant_overlay.py` (CONFIGURE window on `team/app-1` over role-strategy roles,
Manage Roles save during the window, revoke) and `endpoints.py` (forwarded bodies against
role-strategy's own descriptor, 403/405 refusals). `reset_nobc.py` clears `nobc`'s assignments to
re-run `assign_roles.py` alone. Stop with `docker compose -p bc-e2e13 down -v`.
