# Contributing to batch-control

Thanks for looking at this plugin. This file is for people who want to build it,
change it, or understand why its documents look the way they do. If you only want
to *use* the plugin, `README.md` and the user guide (`docs/USER-GUIDE.md`) are
the right place.

Read this file once before your first change — a few of the conventions here are
unusual, and two of them (how tests are derived, and how the matrix row IDs work)
are what makes the rest of the repository readable.

---

## 1. Build and test

### Requirements

| | |
|---|---|
| JDK | **21 or 25** (Temurin). JDK 17 cannot build against Jenkins 2.568.x, whose core needs Java 21. ci.jenkins.io (`Jenkinsfile`) builds Linux on 25 and Windows on 21 with the core tests only; GitHub Actions runs the whole suite on Linux with 21 and 25 and on Windows with 21. |
| Maven | **3.9.6** or newer (the parent POM enforces it). |
| Docker | Only for the end-to-end environment (§5). Not needed for `mvn verify`. |

Pinned in `pom.xml`: parent `org.jenkins-ci.plugins:plugin:6.2236.v12dd4c483242`,
`jenkins.baseline` 2.568 / `jenkins.version` 2.568.3, BOM
`bom-2.568.x:7093.v37de7b_4a_8a_4f`. Do not bump these in a change that is about
something else.

### Commands

```sh
mvn -ntp clean verify                      # compile + full test suite + SpotBugs
mvn -ntp test -Dtest=ClassName             # one test class
mvn -ntp test -Dtest=ClassName#t_06_07_x   # one test method
mvn hpi:run                                # local Jenkins at http://localhost:8080/jenkins
mvn -ntp clean package -DskipTests         # target/batch-control.hpi
```

Surefire runs one JVM per test class, `forkCount` of them at a time, and the
default is `1C` (one per CPU core). Each fork starts its own Jenkins, so on a
machine with many cores and comparatively little memory, or in a Docker
container with a memory limit, `1C` can run out of memory: the symptom is a
test that dies with "Jenkins process terminated prematurely" or a crashed fork,
and that passes when run alone. Cap the forks for such runs:

```sh
mvn -ntp clean verify -DforkCount=4        # bounded parallelism for local and Docker runs
```

Budget roughly 1 GB of free memory per fork. The default stays `1C` because the
CI runners are small (GitHub's `ubuntu-latest` has 4 cores, so `1C` already
means 4 forks there) and a fixed number would slow the CI agents that have more.

On Windows, Git Bash with an explicit environment is what the project has been
built with:

```sh
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/<jdk-21-or-25-directory>"
export PATH="$JAVA_HOME/bin:<maven-directory>/bin:$PATH"
```

### What a healthy run looks like

`mvn clean verify` takes roughly **ten minutes** and must end with:

- **0 failures and 0 errors.** The suite is the hand-written test classes plus the
  parent POM's generated `InjectedTest`, which contributes about twenty more checks
  over the plugin's extensions and Jelly resources. What the suite is *meant* to
  cover is `docs/TEST-MATRIX.md`, not a total quoted here.
- **`BugInstance size is 0`** from SpotBugs. There is no baseline exclusion file
  to hide findings behind; zero means zero.
- `BUILD SUCCESS`.

> The test count is the one number here that legitimately moves, and it moves with
> every lane that lands, so this guide deliberately does not pin it: take the total
> your own green run prints as the baseline for your next one. Treat *any* failure,
> and any SpotBugs finding, as something to explain before you write code. A first build that is not green
> is nearly always an environment difference, not a product defect, and finding
> that out later is expensive.
>
> Related: `ban-junit4-imports.skip` in `pom.xml` is back to `false` now that the
> migration of the test sources is done, so a JUnit 4 import fails the build
> rather than being merely discouraged. Write new tests against JUnit 5.

### Running it twice at once

Don't — see §6.

---

## 2. Repository layout

```
src/main/java/io/jenkins/plugins/batchcontrol/
  model/      requests, grants, records, incidents, state enums
  store/      on-disk persistence (append-only; format is in ARCHITECTURE.md)
  policy/     the decisions: who may approve, what is blocked, when
  security/   the Batch Control matrix and role-strategy variants (subclasses) that grants are built on
  queue/      the queue gate that refuses an unapproved run
  listener/   run and configuration listeners that produce audit records
  config/     global configuration and the per-job JobProperty
  ops/        administrative monitors (misconfiguration banners)
  action/     root action, per-job action, and the dashboard sections
  ui/         view-model helpers for the screens
src/main/resources/            Jelly views, help HTML, Messages.properties
src/main/webapp/help/          help pages referenced from the grant forms
src/test/java/.../batchcontrol/  the test suite (one class per SPEC area)
  BatchControlFixtures.java    shared fixture helpers — read this before writing a fixture
docs/                          the specification, decisions, matrix, and reports
docs/reports/                  point-in-time review output (security, red team, e2e, spec)
e2e/                           Docker Jenkins + REST scenario scripts (§5)
poc/                           throwaway proofs of extension-point assumptions (Phase 1 history)
.github/                       CI workflows, CODEOWNERS, dependabot
```

`poc/` is kept as evidence for `docs/POC-RESULTS.md`; it is not part of the
plugin and nothing in `src/` depends on it.

---

## 3. The documents, and the vocabulary they use

Issue titles, commit messages and code comments in this repository are full of
short identifiers. They are not decorative — each one points at a row or an
entry you can look up.

| document | what it is | who changes it |
|---|---|---|
| `docs/SPEC.md` | the functional contract, with a testable "acceptance criteria" list per item | maintainer |
| `docs/DECISIONS.md` | every design decision, with the alternatives that were rejected | maintainer |
| `docs/ARCHITECTURE.md` | extension points used, package layout, storage format, state machines, known constraints (§7) | maintainer |
| `docs/TEST-MATRIX.md` | every test row: given/when/then, priority, layer, owning test class — plus a long notes section (headed `비고`, Korean for "notes") recording the traps found while writing them | test author |
| `docs/STATUS.md` | progress log, newest entry on top — read the top entry to see where the project stands | maintainer |
| `docs/HOSTING-READINESS.md`, `docs/HOSTING-CHECKLIST.md` | historical: the jenkinsci hosting requirements and the verdict per requirement during the hosting review, not kept up to date since | maintainer |
| `docs/WORKFLOW.md` | the phase plan the project was built along | maintainer |

**`docs/SPEC.md` is the arbiter.** If the code and the spec disagree, the code is
wrong. That is the rule, not a figure of speech: a behaviour change is a spec
change first, and a pull request that changes behaviour without a spec line
behind it will be asked for one. If you believe the spec itself is wrong, say so
in the issue or PR and leave the spec to the maintainer — don't "fix" it in the
same change as the code.

### Glossary

| you will see | it means | look it up in |
|---|---|---|
| `D-nn` (e.g. `D-37`, `D-35e`) | a **settled** design decision — rationale plus the rejected alternatives; a letter suffix marks an amendment | `docs/DECISIONS.md`, settled section |
| `P-nn` | a **proposal awaiting a human ruling**. Some are already implemented as defaults; none of them is settled. Do not cite a `P-nn` as if it were decided | `docs/DECISIONS.md`, proposals section |
| `T-05-02` | a test matrix row derived from SPEC item 5 (`T-<spec item>-<serial>`) | `docs/TEST-MATRIX.md` |
| `T-CFG-01` | a row about global configuration (SPEC §5) | same |
| `T-SEC-07` | a security row — non-functional security (SPEC §6) or cross-cutting | same |
| `T-RT-14` | a row that came out of a red-team scenario rather than the spec | same, and `docs/reports/red-team-01.md` |
| `T-OS-01` | an **owner scenario** row: an operational scenario the owner described directly (S-1…S-6), not derived from a spec sentence | same, note 32 |
| `T-UI-06` | a **screen contract** row: behaviour the implementation defined, with no spec acceptance criterion behind it. These rows are dropped with the feature if the contract is rejected | same, notes 40 and 43 |
| `T-E2E-03` | a browser/REST row that can only be checked against a real Jenkins (§5) | same, and the `docs/reports/e2e-NN.md` reports |
| `RT-nn` | a red-team attack scenario | `docs/reports/red-team-01.md` |
| `S-nn`, `S-nn-nn` | a security review finding (`S-12-01` is finding 1 of review 12) | the `docs/reports/security-NN.md` reports |
| S1 … S4 | the four implementation slices: foundation, run control, change control, operations | `docs/WORKFLOW.md` Phase 3 |
| "falsifiability guard" | an assertion whose only job is to fail if the feature is absent, paired with a positive row (§4) | `docs/TEST-MATRIX.md` notes |

So "P-03 blocks T-SEC-07" reads as: a pending human decision about password
parameters is why one security row has not been written.

The matrix grows with every change, so this guide does not quote its size. Each
row has a priority; P0 means release-blocking.

Issue and pull request numbers written before 2026-10-08 (in tests, `e2e/`,
`docs/TEST-MATRIX.md` and the reports) refer to the former repository
YongGoose/batch-control-plugin, which no longer exists; they do not match the
numbers of this repository.

### Language

Everything written from 2026-09-20 onward is **English**: code, comments, commit
messages, documents, reports, issues and pull requests. The older record documents
(`SPEC.md`, `ARCHITECTURE.md`, `DECISIONS.md`, `TEST-MATRIX.md`, `STATUS.md`) are
still in Korean and are deliberately not being translated retroactively; newer
sections inside them are English, so those files are mixed. Please write anything
new in English even when the file around it is Korean.

The files that are *tools* rather than records — `CLAUDE.md`, `docs/WORKFLOW.md`
and the agent definitions in `.claude/agents/` — were translated to English on
2026-09-26 so that a fork is usable without Korean.

`README.ko.md` is a Korean translation of `README.md`, and **`README.md` is the
canonical version**: if the two disagree, the English one is right. A pull request
that changes `README.md` is *not* expected to update `README.ko.md` in the same
change — the translation is refreshed from the English page in a separate pass, and
its header says which date it corresponds to. Never describe behaviour only in the
Korean page.

---

## 4. Test conventions

This is the part of the repository that is genuinely unusual, and the part worth
preserving. Five rules:

**1. Decide the matrix row first, then write the test.** A new behaviour gets a
row in `docs/TEST-MATRIX.md` — given / when / then, priority, layer — before any
test code. The row ID then appears in the test method name and in a Javadoc
comment on it, so either direction of the lookup works:

```java
/** T-01-02: admin turns runControlEnabled false -> true; ChangeRecord(CONFIG_TOGGLE, admin, false->true). */
@Test
public void t_01_02_enableRunControlLeavesConfigToggleRecord() throws Exception {
```

**2. Derive tests from the specification, not from the implementation.** Tests
here are written without reading `src/main` — from `docs/SPEC.md` and
`docs/TEST-MATRIX.md` only. This is the reason the suite is worth trusting: it
does not share the implementation's assumptions, so it can catch the case where
the implementation is self-consistently wrong. Please keep new tests on that
footing. If you are fixing a bug you found in the code, write the assertion from
what the spec requires, not from what the code currently does.

**3. Commit a failing test first.** New behaviour lands as a red test, then the
implementation that turns it green. This is also how you demonstrate that the
test can fail at all.

**4. Never loosen an assertion to make it pass.** If a test and the code
disagree, exactly one of two things is happening: the code is wrong, or the
contract is wrong. Fix the code, or change the contract in the spec and say so —
do not widen the assertion, delete it, or narrow the scenario until it goes
green. If you think a test is wrong, stop and say so in the PR instead of
editing it quietly; that conversation is cheap and a silently weakened suite is
not.

**5. Pair every positive row with a false-positive guard.** An assertion that
would still pass with the feature removed is worse than no assertion, because it
reports coverage that does not exist. This is not hypothetical here: on
2026-09-26 two rows were found to have been **passing while measuring nothing** —
`T-06-04` and `T-06-11` installed their job settings in a way that a later
default silently shadowed, so the plugin read the default instead of the fixture
and the rows went green without ever exercising the setting they exist for. The
write-up is `docs/TEST-MATRIX.md` note 42, and it is the best five minutes you
can spend before writing a fixture in this repository.

Concretely, that means:

- Assert that the premise took effect, not only that the outcome looks right —
  e.g. read the property back and check it is the instance the fixture
  installed. `BatchControlFixtures.setBatchControl(job, property)` does exactly
  that and is the only way job-level settings should be installed; use
  `BatchControlFixtures.uncontrolled(job)` for an "uncontrolled job" premise
  rather than assuming a freshly created job is one (see `D-31`).
- Add the negative twin: if one row asserts a record *is* written, another must
  assert a clean run writes none (`T-06-17` / `T-06-18` is the pattern), and if
  a control blocks something, one row must show it does not block the thing it
  must leave alone.
- For blocking rows the project has a standard triple: the queue is empty,
  `getNextBuildNumber()` is unchanged, and no build exists after
  `waitUntilNoActivity()`. Asserting only one of the three is how a blocked run
  that quietly executed later slips through.

---

## 5. The end-to-end environment

Everything a browser sees lives in `e2e/`: a real Jenkins in Docker with the
built `.hpi` installed, a set of accounts with deliberately different
permissions, sample jobs and folders, and a mail sink. The authorization
strategy is the plugin's own Batch Control project-matrix variant (a subclass of
matrix-auth's strategy), with a role-strategy profile beside it, because a
Batch Control strategy variant is what makes just-in-time change control
observable at all.

```sh
mvn -ntp clean package -DskipTests   # from the project root: target/batch-control.hpi
cd e2e
cp .env.example .env                 # set the passwords
scripts/up.sh                        # build image, start Jenkins, wait for /login
scripts/down.sh                      # stop, keep JENKINS_HOME
scripts/reset.sh                     # stop and wipe JENKINS_HOME
```

`e2e/README.md` is the reference: the accounts and what each of them may do,
the sample jobs and how they are activated, the configuration profiles, and the
drivers. The scenarios are scripted with Playwright, in Python and Node.js, and
the CI runner in `e2e/ci/` runs them as shards on fresh Jenkins instances and
reports which changed lines no scenario executed (§7). The older
`e2e/scripts/rest-*.sh` curl scripts from the first passes are still there. The
script console is used only to read or arrange state that has no HTTP surface,
never to perform the behaviour under test.

Results go to `docs/reports/e2e-NN.md`, PASS/FAIL per row with screenshot links,
and — separately — a UX section for things that work but are awkward. Keeping
those apart matters: a defect gets routed and re-run, an awkwardness is a
judgement call for the maintainer.

---

## 6. Pitfalls that have already cost time

Collected here so nobody rediscovers them — this list is the canonical one. Each
trap that came out of a specific repair is also written up at length in the notes
section (headed `비고`) of `docs/TEST-MATRIX.md`, which is where to go for the full story
behind any of them.

- **Never run two Maven builds against this checkout at once.** They share
  `target/` and deadlock on Windows file locks (`patch-modules`). Serialise your
  builds — this includes a `verify` in one terminal and an `hpi:run` in another.
- **Never pipe Maven's output through `head`** (or anything else that closes the
  pipe early). The reader exits, Maven takes SIGPIPE mid-build, and the JVMs it
  spawned can outlive it still holding
  `target/patch-modules/org-netbeans-insane-hook.jar` — which on Windows then
  blocks the **`clean` phase of the next build**. The failure therefore surfaces
  one build later, on a command that has nothing to do with it, and looks like a
  broken checkout. Redirect the whole run to a file and grep the file instead:
  `mvn -ntp clean verify > /tmp/verify.log 2>&1 ; echo "exit=$?"`. If a lock has
  already happened, wait for the leftover `java` processes to exit before deleting
  `target/`; killing Maven while it holds the lock reproduces the same state.
- **Do not filter that log too narrowly — the reason gets filtered out.** A
  pattern of `Tests run|BUILD` leaves a bare `BUILD FAILURE` line with no cause in
  sight. Always include `[ERROR]`, e.g.
  `grep -nE "Tests run|\[ERROR\]|BugInstance|BUILD " /tmp/verify.log`, and read
  the *first* error rather than the last — the later ones are usually
  consequences.
- **A test count that went down is not a passing build.** Tests stop running
  silently: a method that lost its `@Test`, a class whose runner annotation no
  longer matches the JUnit version it is written against, a name outside the
  surefire include pattern. Nothing reports an error, because there was simply
  less to do. Compare the count against the previous green run, and if it dropped
  without your having deleted tests on purpose, find the tests that stopped
  running before looking at anything else. Any change that rewrites test
  annotations or moves test classes carries this risk — it is the reason the JUnit
  5 migration of the test sources made the comparison an explicit step.
- **A red `RunRequestServiceTest.t_03_05` is probably not a product defect.** On
  Windows a JenkinsRule temporary directory occasionally cannot be deleted
  because a handle is still open, and the teardown failure is reported against
  whichever test the runner happened to be on. Re-run that class alone first; if
  it goes green there is nothing to fix. `docs/TEST-MATRIX.md` note 41.
- **Jelly compiles only at runtime.** A screen change that compiles and passes
  HTTP-level tests can still be broken in a browser, and nothing in the build
  will say so. Any change under `src/main/resources/**` needs `mvn hpi:run` or
  the `e2e/` environment and an actual look.
- **A job created while run control is on already carries a
  `BatchControlJobProperty`** (`D-31`), and Jenkins core's `addProperty`
  *appends* rather than replaces while `getProperty(Class)` returns the *first*
  match. So a fixture that enables run control, creates the job, then calls
  `addProperty(...)` installs a second property that the plugin never reads.
  Use `BatchControlFixtures` (§4) or create the job before enabling run control.
- **Returning `false` from the queue gate is a completely silent failure** — REST
  returns 200 and the CLI exits 0. User-originated causes therefore throw
  `hudson.model.Failure` so the person sees why; unattended causes return false
  and log.
- **HtmlUnit's normalized text is visible text only.** matrix-auth renders
  permission group titles inside collapsed cards, so assert against the raw DOM
  and confirm visually in the e2e pass.
- **`AsyncPeriodicWork.doRun()` is `public final`** and spawns a thread, so tests
  cannot drive it synchronously. Retention uses a plain `PeriodicWork` for that
  reason.
- **`@Initializer(after = COMPLETED)` stalls the init graph** (JENKINS-37759).
  Startup recovery runs at `JOB_CONFIG_ADAPTED` and coordinates with `queue.xml`
  under `Queue.withLock`.
- **The Pipeline `build` step lives in `pipeline-build-step`**, and its cause is
  `BuildUpstreamCause`, a *subclass* of `UpstreamCause` — classify with
  `instanceof`.

---

## 7. Pull requests

- **Conventional Commits** for every commit: `feat:`, `fix:`, `test:`, `docs:`,
  `chore:`, `ci:`.
- **Reference the issue** your change belongs to — `fix #<n>`, or `[no-issue]`
  when there genuinely is none — and name the spec item, decision or matrix row
  it touches (`SPEC §8`, `D-31`, `T-06-11`). A change with no such anchor is hard
  to review here, because the reviewer's first question is always "which contract
  does this implement".
- `.github/PULL_REQUEST_TEMPLATE.md` asks for exactly these things and nothing
  else; if a line does not apply to your change, delete it rather than ticking
  it.
- **The gate is the whole suite green and SpotBugs reporting zero.** Please run
  `mvn -ntp clean verify` yourself before opening the PR. Every pull request and
  every push to `main` runs the same command in GitHub Actions
  (`.github/workflows/build.yml`, Linux). Each JDK the hosting checker accepts
  has its own checks — `build (jdk 21)`, `build (jdk 25)` and one test job per
  feature group of test classes, such as `test (jdk 21, run approval and mail)`
  or `test (jdk 25, parameters and ui)` — and the aggregating job `build`
  succeeds only when all of them do. The groups are listed, by class-name prefix
  or class name, in `.github/test-shards.txt`, and the `shard safeguard` job
  fails when a test class matches no group or two, or a listed name matches no
  class. A new class whose prefix a group already lists (`Grant*Test`,
  `Window*Test`) needs nothing; otherwise add its prefix or its name to the
  group of the feature it tests, and take a renamed or deleted class's name out.
  The whole suite also runs on Windows with JDK 21, in the `Windows tests`
  workflow (`.github/workflows/windows-tests.yml`), on every pull request and
  every push to `main` that touches `src/`, `pom.xml`, `.mvn/`, the test groups
  or their scripts: one job per group, such as
  `windows test (jdk 21, run approval and mail)`, and a `windows test summary`
  job that lists every failing test and every class without a report and is
  red when there is any. It is not a required check, but a red run needs a
  look before merging.
  ci.jenkins.io builds the `Jenkinsfile`, a plain `buildPlugin` call, on Linux
  with JDK 25 and on Windows with JDK 21, and reports the check `Jenkins`. It
  runs only the tests tagged `@Tag("core")` and the generated `InjectedTest`:
  a Jenkins build sets `BUILD_URL`, which activates the
  `core-tests-on-jenkins` profile in `pom.xml` (local and GitHub Actions builds
  do not set it, and run everything). The profile fails the build when no test
  carries the tag, so a lost tag shows up as a red `Jenkins` check. The whole suite takes about 103 minutes
  on four forks there, longer than buildPlugin's default timeout, and spot
  agents get reclaimed during so long a run. ci.jenkins.io runs on spot agents
  only, and buildPlugin retries an agent that is reclaimed. Never add
  `nonspot` labels or turn the `Jenkinsfile` into a custom pipeline; the
  ci.jenkins.io administrators asked for that in
  jenkinsci/batch-control-plugin#80. buildPlugin also records coverage, static
  analysis and the Incrementals artifacts.
- **`@Tag("core")`** marks the tests ci.jenkins.io runs, about a quarter of the
  suite. The criteria for what belongs there are in `docs/TEST-MATRIX.md`,
  note 330; tag a new test only when it meets them. An untagged test still
  runs in `build` and in `Windows tests`.
- **Verify on your fork before you open the pull request.** ci.jenkins.io is
  shared by every Jenkins project and runs on sponsored capacity, and it builds
  every pull request again on every push. So:
  1. Fork the repository, enable Actions on the fork, and push your topic
     branch there, never to `jenkinsci/batch-control-plugin`.
  2. Run the `build` workflow on that branch from the fork's Actions tab
     ("Run workflow"), or with
     `gh workflow run build.yml --ref <branch> -R <you>/batch-control-plugin`.
     For a change to the UI or to behaviour, run the `e2e` workflow the same
     way (`e2e.yml`). A run started by hand has no base commit, so it reports
     no changed-line coverage; the pull request's own e2e run does. Run
     `windows-tests.yml` the same way if you want the Windows result before
     the pull request starts it.
  3. Open the pull request from that branch when they are green.
  4. Batch your commits: push the answers to a review round together, not one
     commit at a time, because every push to a pull request rebuilds it on
     ci.jenkins.io.
- **Merging into `main`** needs two required status checks, `build` and
  ci.jenkins.io's `Jenkins`, green on a branch that is up to date with `main`
  (update it when GitHub says it is behind), and one approving review, from the
  maintainer.
- The scripted e2e pass (`.github/workflows/e2e.yml`: `e2e build`, `e2e groups`,
  one `e2e (<label>)` job per group of `e2e/ci/shard.py`, such as
  `e2e (crawl and ui checks)`, and `e2e coverage`) runs against a real Jenkins
  in Docker and reports the JaCoCo coverage of the lines your change touched in
  the job summary, as annotations and in the `e2e-coverage` artifact. It runs on
  the same events, but only when the change touches `src/main/`, `pom.xml`,
  `.mvn/`, `e2e/` or the workflow file itself, so a docs-only or test-only pull
  request does not start it (a maintainer can still run it by hand). It is
  **not** a required check; a red `e2e (<label>)` job is worth a look (its logs
  and screenshots are in the `e2e-shard-<k>` artifact, k being the group's
  number). See `e2e/README.md`, "CI runner and e2e coverage" and "CI contract".
- Behaviour change → spec change first (§3). Screen change → looked at in a
  browser (§6). New behaviour → matrix row and a failing test first (§4).
- Security vulnerabilities do **not** go in a GitHub issue or PR. Use the Jenkins
  security process described at the end of `README.md`.

### Releases

Releases are made by continuous delivery (JEP-229). The maintainer runs the CD
workflow (`.github/workflows/cd.yaml`) by hand on `main`, and it publishes a
version of the form `<revision>.<commit count>.v<commit hash>`, for example
`1.0.1130.vabcdef456789`: the "manually controlled prefix" of the
[CD documentation](https://www.jenkins.io/doc/developer/publishing/releasing-cd/).
`revision` is a property in `pom.xml` (now `1.0`); the rest is set by the
changelist extension (`.mvn/maven.config`, `-Dchangelist.format=%d.v%s`). A
local build is `1.0.999999-SNAPSHOT`.

- **Bump `revision` only for a new line**: `1.1` when a release adds features
  worth marking, `2.0` when it breaks compatibility (settings, stored data or
  behaviour). Do it in its own pull request, labelled like the change it marks.
  The commit count keeps growing, so versions within a line and across lines
  stay ordered (`1.0.1130.v…` < `1.0.1131.v…` < `1.1.1140.v…`).
- **Never lower it.** Every new version must compare higher than every version
  on the update center. The first release, `1124.vfe83a_6d946c3`, compares higher
  than any `1.x` version, so it has to be removed from the update center once
  `1.0.x` is out, and anyone who installed it reinstalls the plugin (see the
  release notes of the first `1.0.x` release).

The release notes are drafted by release-drafter from the
titles and labels of the pull requests merged since the previous release, so:

- **The pull request title is the changelog line.** Write it for a user reading
  the release notes, not for a reviewer.
- **Labels decide whether there is anything to release, and where each change
  is listed.** A release needs at least one merged pull request with a label of
  interest to users, such as `enhancement`, `bug` or `breaking`, or `developer`
  for a change aimed at other plugin developers. Pull requests labelled only
  `chore`, `dependencies` or the like are listed but do not by themselves make a
  release. The maintainer sets the labels; suggest one in the pull request if
  you like.

---

## 8. A note on how this repository was developed

This plugin was built as an orchestration of specialised Claude Code agents —
separate roles for the specification, the implementation, the tests, security
review, red-teaming and the release files, each restricted to its own paths, with
the human owner ruling on every design decision. The agent definitions are in
`.claude/agents/`, the procedures they follow in `.claude/skills/`
(`slice-workflow` — the order one unit of work goes through and why;
`verify-gate` — how to decide whether a build actually passed; `test-contract` —
the two test rules that are easy to break), and `CLAUDE.md` plus
`docs/WORKFLOW.md` describe how the work was divided.

That is context, not a requirement: **you do not need to work that way to
contribute here.** It is worth a paragraph only because the process left visible
marks on the repository, and they are easier to read once you know where they
came from — the ownership tables, the phase and gate language in `STATUS.md` and
`WORKFLOW.md`, the `Request:` lines in the reports (written `요청:`, Korean for
"request", in the older ones) where one role needed a change in another's files,
and above all the
separation in §4 between the
people who wrote the tests and the people who wrote the code. The two
conventions that are genuinely load-bearing — the spec is the arbiter, and tests
are derived from the spec rather than from the implementation — are the ones to
keep, whatever tooling you use.
