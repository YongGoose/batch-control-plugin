# Contributing to batch-control

Thanks for looking at this plugin. This file is for people who want to build it,
change it, or understand why its documents look the way they do. If you only want
to *use* the plugin, `README.md` is the right place.

Read this file once before your first change — a few of the conventions here are
unusual, and two of them (how tests are derived, and how the matrix row IDs work)
are what makes the rest of the repository readable.

---

## 1. Build and test

### Requirements

| | |
|---|---|
| JDK | **21** (Temurin). 17 also compiles, but 21 is what every green run and both `Jenkinsfile` configurations use. |
| Maven | **3.9.16** or newer. |
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

On Windows, Git Bash with an explicit environment is what the project has been
built with:

```sh
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
export PATH="$JAVA_HOME/bin:$HOME/tools/apache-maven-3.9.16/bin:$PATH"
```

### What a healthy run looks like

`mvn clean verify` takes roughly **ten minutes** and must end with:

- **0 failures and 0 errors**, around **195 tests** run. The suite is
  33 hand-written test classes (177 `@Test` methods at the time of writing) plus
  the parent POM's generated `InjectedTest`, which contributes about twenty more
  checks over the plugin's extensions and Jelly resources.
- **`BugInstance size is 0`** from SpotBugs. There is no baseline exclusion file
  to hide findings behind; zero means zero.
- `BUILD SUCCESS`.

> The test count is the one number here that legitimately moves. A JUnit 5
> migration of the test sources is in progress, and the last figures recorded in
> the reports are 194 tests (`docs/reports/e2e-02.md`) plus one row added
> afterwards. Treat "about 195, zero failures" as the signal and the exact
> integer as informational — but treat *any* failure, and any SpotBugs finding,
> as something to explain before you write code. A first build that is not green
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
  security/   the delegating AuthorizationStrategy that grants are built on
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
| `docs/TEST-MATRIX.md` | every test row: given/when/then, priority, layer, owning test class — plus a long `비고` (notes) section recording the traps found while writing them | test author |
| `docs/STATUS.md` | progress log, newest entry on top — read the top entry to see where the project stands | maintainer |
| `docs/HOSTING-READINESS.md`, `docs/HOSTING-CHECKLIST.md` | the jenkinsci hosting requirements and the current verdict per requirement | maintainer |
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
| `D-01` … `D-33` | a **settled** design decision — rationale plus the rejected alternatives | `docs/DECISIONS.md`, settled section |
| `P-01` … `P-13` | a **proposal awaiting a human ruling**. Some are already implemented as defaults; none of them is settled. Do not cite a `P-nn` as if it were decided | `docs/DECISIONS.md`, proposals section |
| `T-05-02` | a test matrix row derived from SPEC item 5 (`T-<spec item>-<serial>`) | `docs/TEST-MATRIX.md` |
| `T-CFG-01` | a row about global configuration (SPEC §5) | same |
| `T-SEC-07` | a security row — non-functional security (SPEC §6) or cross-cutting | same |
| `T-RT-14` | a row that came out of a red-team scenario rather than the spec | same, and `docs/reports/red-team-01.md` |
| `T-OS-01` | an **owner scenario** row: an operational scenario the owner described directly (S-1…S-6), not derived from a spec sentence | same, note 32 |
| `T-UI-06` | a **screen contract** row: behaviour the implementation defined, with no spec acceptance criterion behind it. These rows are dropped with the feature if the contract is rejected | same, notes 40 and 43 |
| `T-E2E-03` | a browser/REST row that can only be checked against a real Jenkins (§5) | same, and `docs/reports/e2e-01.md`, `e2e-02.md` |
| `RT-01` … `RT-20` | a red-team attack scenario | `docs/reports/red-team-01.md` |
| `S-01` … `S-13` | a security review finding | `docs/reports/security-01.md`, `security-02.md` |
| S1 … S4 | the four implementation slices: foundation, run control, change control, operations | `docs/WORKFLOW.md` Phase 3 |
| "falsifiability guard" | an assertion whose only job is to fail if the feature is absent, paired with a positive row (§4) | `docs/TEST-MATRIX.md` notes |

So "P-03 blocks T-SEC-07" reads as: a pending human decision about password
parameters is why one security row has not been written.

Current matrix size: **173 rows** — P0 116 / P1 49 / P2 8; unit 3 /
integration 161 / e2e 9. P0 means release-blocking.

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
built `.hpi` installed, three accounts (`admin`, `approver`, `requester`) with
deliberately different permissions, and sample jobs (a parameterised Freestyle
job, a Pipeline job, an uncontrolled cron job). The authorization strategy is the
plugin's own delegating strategy wrapping matrix-auth, because that is what makes
just-in-time change control observable at all.

```sh
mvn -ntp clean package -DskipTests   # from the project root: target/batch-control.hpi
cd e2e
cp .env.example .env                 # set the three passwords
scripts/up.sh                        # build image, start Jenkins, wait for /login
scripts/down.sh                      # stop, keep JENKINS_HOME
scripts/reset.sh                     # stop and wipe JENKINS_HOME
```

`e2e/scripts/` holds one script per scenario (`rest-run-request.sh`,
`rest-grant-configure.sh`, `rest-csv-export.sh`, …), each mapped to the matrix
rows it covers, printing the HTTP status and the relevant part of every response
and saving raw responses under `e2e/out/`. `lib.sh` holds the shared curl
helpers — cookie-jar login and a CSRF crumb on every POST — plus `bc_script`,
which runs Groovy on the script console. The script console is used only to read
or arrange state that has no HTTP surface, never to perform the behaviour under
test. Helpers like `grant-setup.sh`, `approvers-clear.sh` and `executors.sh`
arrange the awkward preconditions (an active grant, an empty approver list, an
approved run that cannot start because there are no executors).

`e2e/README.md` is the full reference, including the two traps in the seed data.
Results go to `docs/reports/e2e-NN.md`, PASS/FAIL per row with screenshot links,
and — separately — a UX section for things that work but are awkward. Keeping
those apart matters: a defect gets routed and re-run, an awkwardness is a
judgement call for the maintainer.

---

## 6. Pitfalls that have already cost time

Collected here so nobody rediscovers them — this list is the canonical one. Each
trap that came out of a specific repair is also written up at length in the notes
(`비고`) section of `docs/TEST-MATRIX.md`, which is where to go for the full story
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
  `mvn -ntp clean verify` yourself before opening the PR; ci.jenkins.io builds
  both Linux and Windows on JDK 21.
- Behaviour change → spec change first (§3). Screen change → looked at in a
  browser (§6). New behaviour → matrix row and a failing test first (§4).
- Security vulnerabilities do **not** go in a GitHub issue or PR. Use the Jenkins
  security process described at the end of `README.md`.

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
`WORKFLOW.md`, the "request" lines in the reports (`요청:` in the older Korean
ones) where one role needed a change in another's files, and above all the
separation in §4 between the
people who wrote the tests and the people who wrote the code. The two
conventions that are genuinely load-bearing — the spec is the arbiter, and tests
are derived from the spec rather than from the implementation — are the ones to
keep, whatever tooling you use.
