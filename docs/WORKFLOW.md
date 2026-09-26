# Development workflow — agent assignment per Phase

The main session (orchestrator) proceeds in the order of this document. Each Phase is made up of "input → agent → deliverables → gate". If a gate is marked 🧑, move on only after human confirmation.

Overall flow:
```
P0 setup → P1 PoC 🧑 → P2 test matrix 🧑 → P3 implementation (4 slices) → P4 security review → P5 E2E → P6 pilot operation 🧑 → P7 publication & application 🧑
```

---

## Phase 0. Project setup (main session directly, 30 minutes)

**Work**
1. Generate `empty-plugin` with `mvn -U archetype:generate -Dfilter="io.jenkins.archetypes:"`. artifactId `batch-control`, groupId `io.jenkins.plugins`, package `io.jenkins.plugins.batchcontrol`.
2. Merge the generated files into this kit directory (keeping the kit's CLAUDE.md, docs/, .claude/).
3. pom.xml: latest parent POM, latest LTS `jenkins.version`, dependencies `workflow-job`, `workflow-cps` (for tests), `matrix-auth` (for tests), `cloudbees-folder`, `structs`. `jenkins-test-harness` in test scope.
4. `buildPlugin()` in `Jenkinsfile`.
5. Confirm `mvn -q clean verify` passes. `git init`, first commit.
6. Record Phase 0 as complete in `docs/STATUS.md`.

**Gate**: `mvn clean verify` green.

**Prompt**
```
Carry out Phase 0 of docs/WORKFLOW.md. Read the rules in CLAUDE.md first, and when you are done update docs/STATUS.md and report the result.
```

---

## Phase 1. PoC — validating the design assumptions (poc-engineer, 2–3 days)

**Purpose**: before the main development, confirm the following three assumptions on a real Jenkins. If they fail, go back to the design.

| # | Assumption | How it is validated |
|---|---|---|
| A | `QueueDecisionHandler` intercepts all of the UI, REST (build/buildWithParameters), CLI build, Pipeline Replay and `build` step paths | confirm blocking on each of the 5 paths with a `JenkinsRule` test |
| B | `ItemListener.onCheckDelete` rejects UI, REST and CLI deletion alike | test on 3 paths |
| C | A delegating `AuthorizationStrategy` grants temporary Item/Configure, Create and Delete on top of the Matrix and Role strategies, and denies immediately once the expiry time has passed | test with Matrix; for Role, confirm compatibility through documentation research |
| D | A notice can be shown to the user when a build is blocked (avoiding silent failure) | manual confirmation with `hpi:run` + screenshots |

**Input**: `docs/SPEC.md` items 6 and 8, `docs/ARCHITECTURE.md` sections 2 and 4
**Writable paths**: `poc/` (a separate Maven module or the branch `phase-1-poc`), `docs/POC-RESULTS.md`
**Deliverables**: `docs/POC-RESULTS.md` — the result per assumption (pass/fail/conditional), the file names of the supporting tests, the constraints found, and design change proposals

**Gate 🧑**: the human reads POC-RESULTS and decides either "keep the design" or to amend DECISIONS. If even one of A, B or C fails, entering Phase 3 is forbidden.

**Prompt**
```
Delegate Phase 1 of docs/WORKFLOW.md to the poc-engineer subagent. Have it validate assumptions A through D in order and write docs/POC-RESULTS.md. When it finishes, report a summary of the results and wait for my confirmation.
```

---

## Phase 2. Test matrix (test-author + red-team, in parallel, 1 day)

**Purpose**: settle the verification criteria before implementation. This matrix is the source of every later test.

**Work**
- test-author: move every acceptance criterion in `docs/SPEC.md` into a row of `docs/TEST-MATRIX.md` (Given/When/Then, layer, priority). Include at least one negative path per item.
- red-team: looking only at SPEC and ARCHITECTURE, find "ways to break this design" and write them as scenarios in `docs/reports/red-team-01.md`. Concurrency, boundary times, renames and moves, escaping the folder scope, parameter tampering, abusing the permission window, and so on.
- main session: pass the valid red-team scenarios to test-author to be added to the matrix.

**Writable paths**: test-author → `docs/TEST-MATRIX.md` / red-team → `docs/reports/red-team-01.md`
**Deliverables**: the completed `docs/TEST-MATRIX.md` (ID, spec item, layer [unit/integration/e2e], priority [P0/P1/P2], Given/When/Then)

**Gate 🧑**: **the human reads the matrix personally.** A scenario missing here will be missing from every later layer. Note down what is to be added, and approve.

**Prompt**
```
Start Phase 2. Delegate test-author and red-team in parallel. test-author converts all of SPEC's acceptance criteria into TEST-MATRIX, and red-team writes scenarios that would break the design into docs/reports/red-team-01.md. When both are done, pass the valid red-team scenarios to test-author to be merged into the matrix, then report a summary of the final matrix (total rows, number of P0, distribution per item) and wait for my confirmation.
```

---

## Phase 3. Implementation (core-dev, ui-dev, test-author, spec-guardian; 4 slices, 1–2 weeks)

Each slice goes around the same loop:

```
1. test-author: the slice's matrix rows → write JenkinsRule tests (src/test). Commit them in a failing state.
2. core-dev: the implementation that makes the tests pass (its own packages). In parallel with ui-dev.
3. ui-dev: implement screens and Actions. Uses core-dev's policy/store public API.
4. mvn clean verify green.
5. spec-guardian: review whether this slice's diff matches SPEC → docs/reports/spec-review-S<n>.md
6. Fix the findings → slice ends, commit.
```

Parallelism rule: 1 starts before 2 and 3, but 2 and 3 may start before 1 has finished (implementation starts while the tests are failing). 2 and 3 run at the same time (their paths do not overlap). 5 comes after 4.

| Slice | Scope (SPEC items) | core-dev | ui-dev |
|---|---|---|---|
| S1 foundation | 1, 2, 4 | model, store(FileStore), config(GlobalConfiguration, JobProperty), security(Permissions) | global configuration screen, job configuration section |
| S2 run control | 3, 5, 6, 7 | policy(RunRequestService, ApprovalPolicy), queue, ops(ExpiryPeriodicWork, StartupRecovery) | JobRequestAction (request form, replacing Build Now), RunRequestRootAction (pending queue, approve/reject) |
| S3 change control | 8, 9 | security(AuthorizationStrategy, GrantService), policy(GrantRequestService), listener(ItemChange, ConfigSnapshot, DeleteVeto), ops(Monitor) | GrantRootAction (request, decision, active permissions, revocation), change record screen |
| S4 operations | 10, 11, 12 | listener(RunRecordListener), ops(IncidentService, RetentionPeriodicWork) | Dashboard, Incident screen, History, summaries, CSV |

**Gate (per slice)**: `mvn clean verify` green + no "BLOCKER" in the spec-guardian report.
**Gate (end of Phase)**: all P0 tests pass, P1 90% or more.

**Prompt (example for slice 1; for S2–S4 only the number changes)**
```
Start Phase 3 slice S1. In this order:
1) Have test-author pick the rows for SPEC items 1, 2 and 4 from TEST-MATRIX and write JenkinsRule tests in src/test. State explicitly that it must not read src/main.
2) When test-author reports the list of test files, delegate core-dev and ui-dev in parallel. core-dev takes model/store/config/security, ui-dev takes the global configuration screen and the job configuration section. Have each of them respect the package boundaries in ARCHITECTURE.md.
3) Once mvn clean verify is green, have spec-guardian review the S1 diff and get docs/reports/spec-review-S1.md.
4) If there is a BLOCKER, have the owning agent fix it and go back to 3).
5) When complete, update STATUS.md and report a summary.
```

---

## Phase 4. Security self-review (security-reviewer, 1–2 days)

**Work**
1. security-reviewer: a complete review of `src/main` against the security items of `docs/HOSTING-CHECKLIST.md` → `docs/reports/security-01.md`. Severity (BLOCKER/HIGH/MEDIUM/LOW), file:line, direction of the fix.
2. main session: distribute the findings to the owning agents (core-dev/ui-dev) to be fixed. Ask test-author to add regression tests (403 for an unauthorised call, 405 for attempting a state change with GET, and so on).
3. release-manager: add `.github/workflows/jenkins-security-scan.yml` (the Jenkins project's official security scan action). SpotBugs is included in `mvn verify`.
4. security-reviewer re-reviews → `security-02.md`.

**Gate**: 0 BLOCKER/HIGH. The security scan workflow file exists.

**Prompt**
```
Start Phase 4. Have security-reviewer review all of src/main against the HOSTING-CHECKLIST security criteria and get docs/reports/security-01.md. Distribute the findings to the owning agents to be fixed, and have test-author add a regression test for each finding. Have release-manager add the jenkins-security-scan workflow. After the re-review, report once BLOCKER/HIGH are at 0.
```

---

## Phase 5. E2E scenarios (e2e-tester, 2–3 days)

**Prerequisites**: Docker installed, Playwright MCP connected to Claude Code.

**Work**
1. e2e-tester: write `e2e/docker-compose.yml` (Jenkins LTS + the plugin `.hpi` mounted + Matrix permissions + 3 users `requester`/`approver`/`admin` + an initialisation script with 3 sample jobs (a parameterised Freestyle, a Pipeline, a cron job)).
2. Perform the rows of `docs/TEST-MATRIX.md` with layer=e2e in a browser. Save a screenshot per scenario (`e2e/screenshots/<id>.png`).
3. Cover the REST paths alongside with `e2e/scripts/*.sh` (curl + crumb).
4. `docs/reports/e2e-01.md`: PASS/FAIL per scenario, screenshot links, and a separate section for UX problems (things that are not spec violations but are awkward to use).
5. Distribute the FAILs and UX problems to the owning agents → fix → re-run as `e2e-02.md`.

**Gate**: all e2e P0 PASS. UX problems are judged by the human.

**Prompt**
```
Start Phase 5. Have e2e-tester set up the docker-compose environment and perform the e2e rows of TEST-MATRIX with Playwright, and get docs/reports/e2e-01.md. Distribute the FAILs to the owning agents to be fixed and have them re-run. Report the list of UX problems to me and wait for my judgement.
```

---

## Phase 6. Pilot operation (human, 1 week) 🧑

- Install the `.hpi` on the in-house Jenkins, start with run control switched on, and apply it to 2–3 real batch jobs.
- Have real approvers try it and collect what they find inconvenient.
- Pass the collected items to the main session → distribute to the owning agents → fix → re-run a reduced Phase 4 and 5.

**Gate 🧑**: the human judges it "ready to publish".

---

## Phase 7. Preparing for publication and the hosting application (release-manager, 2–3 days + review wait)

**Work**
1. release-manager: check every item of `docs/HOSTING-CHECKLIST.md` → `README.md` (in English: purpose, the batch-environment context, installation, configuration, screenshots, known limitations, the differences from the `input` step and Job StrongAuthSimple), `LICENSE` (MIT), `CHANGELOG.md`, pom metadata (licenses, developers, scm, url), `Jenkinsfile`.
2. release-manager: write a draft of the hosting request issue body (in English) in `docs/HOSTING-REQUEST.md`. Items: repository URL, new repository name `batch-control-plugin`, description, GitHub user, Jenkins account, issue tracker (GitHub), the differences from existing plugins.
3. Human: push to a personal GitHub, and file an issue in `jenkins-infra/repository-permissions-updater`.
4. Bot findings → the main session distributes them to the owning agents → fix → `/hosting re-check`.
5. Approval → fork into jenkinsci → delete the original → set up CD (JEP-229) (release-manager writes `.github/workflows/cd.yml`) → first release.

**Gate 🧑**: installable from the update centre.

**Prompt**
```
Start Phase 7. Have release-manager check every item of HOSTING-CHECKLIST and write README, LICENSE, CHANGELOG, pom metadata and docs/HOSTING-REQUEST.md. If there are unmet items in the checklist, distribute them to the owning agents. When it is done, show me the draft request.
```

---

## Operating rules

- If you get stuck inside a Phase, try to resolve it yourself up to 3 times and then report to the human.
- If an agent report contains `Request: <path>`, the main session passes it to the owning agent.
- STATUS.md is updated before and after every delegation. For the format, see the top of STATUS.md.
