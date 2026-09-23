# Continuation Guide

How to pick this project up from a different account or machine. Last updated 2026-09-23.

If you read only one thing, read **§1 and §2**. Everything else is reference.

---

## 1. First twenty minutes

```bash
git clone https://github.com/YongGoose/batch-control-plugin
cd batch-control-plugin
git checkout handoff/phase-4-5-continuation   # where the current work lives (PR #8)
```

Read in this order — each one takes a few minutes and they build on each other:

| order | file | what you get from it |
|---|---|---|
| 1 | `CLAUDE.md` | how work is divided, who may write which paths, the code rules |
| 2 | `docs/WORKFLOW.md` | the phase plan and what each gate requires |
| 3 | `docs/STATUS.md` (top entry) | exactly where the project stopped |
| 4 | `docs/SPEC.md` | what the plugin is supposed to do — **this is the arbiter**; if code disagrees with it, the code is wrong |
| 5 | `docs/ARCHITECTURE.md` | which Jenkins extension points are used, package layout, storage format |
| 6 | GitHub issue #7 | the remaining roadmap, then #4 for decisions waiting on a human |

Then prove the build works before changing anything (§3). A green run takes about ten minutes and is the fastest way to confirm your environment matches.

---

## 2. How this project is actually run

This is not a normal "one developer edits files" repo. It is run as an **orchestration**: a main session delegates to specialised subagents defined in `.claude/agents/`, and the value comes from the separation between them. The two rules that matter most:

**Tests are written blind.** The test author never reads `src/main`. Tests are derived from `docs/SPEC.md` and `docs/TEST-MATRIX.md` only, written first in a failing state and committed that way, and the implementer then makes them pass without editing them. If an implementer thinks a test is wrong, they stop and report instead of changing it. This is why the suite is worth trusting — it does not share the implementation's assumptions.

**Nobody edits outside their lane.** `CLAUDE.md` has an ownership table (core logic vs. screens vs. tests vs. reports vs. release files). An agent that needs a change elsewhere writes "요청: `<path>` `<what>`" in its report and the orchestrator routes it. When that discipline slipped, it showed up in review — see the retroactive-approval items in issue #4.

The main session does not write code. It picks the phase, delegates, checks gate conditions, updates `docs/STATUS.md`, and stops at gates marked 🧑 for a human.

### Vocabulary you will hit immediately

| prefix | meaning | where |
|---|---|---|
| `D-01`..`D-23` | **settled** design decisions, with rationale and rejected alternatives | `docs/DECISIONS.md` |
| `P-01`..`P-10` | **proposals** awaiting a human ruling (some already implemented as defaults) | same file, proposals section, and issue #4 |
| `S-01`..`S-13` | security review findings | `docs/reports/security-01.md`, `security-02.md` |
| `T-05-02`, `T-SEC-07`, `T-RT-14`, `T-E2E-03` | test matrix rows — by SPEC item, security, red-team origin, browser layer | `docs/TEST-MATRIX.md` |
| `RT-01`..`RT-20` | red-team attack scenarios | `docs/reports/red-team-01.md` |
| S1..S4 | the four implementation slices (foundation, run control, change control, operations) | `docs/WORKFLOW.md` Phase 3 |

So "P-03 blocks T-SEC-07" means: a pending human decision about password parameters is preventing one security test row from being written.

---

## 3. Environment

The original machine had these; replicate or adjust:

- **JDK 21** (Temurin). Build with 21. JDK 17 also worked but 21 is what every green run used.
- **Maven 3.9.16**.
- **Docker Desktop 29.8.0 + Compose v5.5.1** — needed for Phase 5 only. On the original machine it was installed user-scope at `%LOCALAPPDATA%\Programs\DockerDesktop\resources\bin` and was **not on PATH** in fresh shells; call the binary by full path or fix PATH.

Build (Git Bash on Windows; adjust paths elsewhere):

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
export PATH="$JAVA_HOME/bin:$HOME/tools/apache-maven-3.9.16/bin:$PATH"
mvn -ntp clean verify          # full suite + SpotBugs — about 10 minutes
mvn -ntp -q test -Dtest=ClassName   # one class — 30 s to 3 min
mvn hpi:run                    # local Jenkins at http://localhost:8080/jenkins
```

Expected at `4550d00`: **159 tests, 0 failures, SpotBugs `BugInstance size is 0`, BUILD SUCCESS**. If you get anything else, stop and find out why before writing code.

Pinned versions live in `pom.xml`: parent `org.jenkins-ci.plugins:plugin:6.2236.v12dd4c483242`, `jenkins.version 2.568.3`, BOM `bom-2.568.x:7046.v43536164769c`.

---

## 4. Where the project stopped

```
P0 setup ✅ → P1 PoC ✅🧑 → P2 test matrix ✅🧑 → P3 implementation S1..S4 ✅
→ P4 security ✅ (gate met: BLOCKER 0 / HIGH 0)
→ P5 E2E ⏳ NEXT → P6 pilot 🧑 → P7 release/hosting
```

- Everything through Phase 4 is merged to `main` or sits on `handoff/phase-4-5-continuation` (PR #8). The phase branches `phase-1-poc`, `phase-2-matrix`, `phase-3-impl` are history.
- Test matrix: **138 rows**. P0 coverage outside the browser layer is complete except **T-SEC-07**, which is blocked on decision P-03. P1 is complete. The nine e2e rows are Phase 5 scope.
- Reports produced so far: `docs/POC-RESULTS.md`, `docs/reports/red-team-01.md`, `spec-review-S1..S4.md`, `security-01.md`, `security-02.md`.

**What has never been verified:** anything a browser sees. Screens exist and their endpoints are covered by HTTP-level tests, but no one has opened them and looked. That is precisely Phase 5. The plugin has also never run on a real Jenkins (Phase 6).

---

## 5. The next task, concretely (Phase 5 — issue #2)

1. Build the artifact: `mvn -ntp clean package -DskipTests` → `target/batch-control.hpi`.
2. Write `e2e/docker-compose.yml`: Jenkins LTS matching `jenkins.version`, the `.hpi` mounted into `/var/jenkins_home/plugins/`, matrix-auth installed, three users (`requester`, `approver`, `admin`) and three sample jobs (parameterized Freestyle, Pipeline, cron) created by init groovy scripts. This path belongs to the e2e-tester per `CLAUDE.md`.
3. Walk the e2e rows T-E2E-01..08 and T-10-06 in a browser, screenshotting each to `e2e/screenshots/<row-id>.png`. Drive REST paths with `e2e/scripts/*.sh` (curl + crumb).
4. Also settle three visual checks deferred from earlier phases: the block-guidance page (PoC assumption D), the permission group appearing on the security screen (T-02-02 — it is asserted at DOM level today because matrix-auth renders group titles inside collapsed cards), and the sidebar showing "Request Run" instead of "Build Now" (T-06-15).
5. Write `docs/reports/e2e-01.md`: PASS/FAIL per row with screenshot links, plus a **separate UX section** for things that work but are awkward — those are a human call, not a bug list. Failures get routed to the owning agent and re-run as `e2e-02.md`.

Gate: all e2e P0 rows pass.

---

## 6. Things that cost us time — do not rediscover them

- **Never run two Maven builds at once.** Agents sharing `target/` deadlock on Windows file locks (`patch-modules`). Serialize mvn across agents.
- **Long-running agents hit session limits.** Two were killed mid-task. Work committed to disk survived; in-memory context did not. Commit early, and when resuming an agent tell it what already landed — a resumed agent will otherwise repeat stale claims about its own earlier state.
- **`AsyncPeriodicWork.doRun()` is `public final`** and spawns a thread, so tests cannot drive it synchronously. Retention uses plain `PeriodicWork` for that reason.
- **`@Initializer(after = COMPLETED)` stalls the init graph** (JENKINS-37759). Startup recovery runs at `JOB_CONFIG_ADAPTED` and coordinates with `queue.xml` under `Queue.withLock`.
- **The Pipeline `build` step lives in `pipeline-build-step`**, not `workflow-basic-steps`, and its cause is `BuildUpstreamCause`, a *subclass* of `UpstreamCause` — classify with `instanceof`.
- **Returning `false` from the queue gate is a completely silent failure** (REST returns 200, CLI exits 0). User-originated causes therefore throw `hudson.model.Failure` so the person sees why; unattended causes return false and log.
- **HtmlUnit's normalized text is visible-text only.** matrix-auth renders permission group titles inside collapsed cards, so assert on the raw DOM and verify visually in Phase 5.

---

## 7. Conventions

- **Everything written is English** — code, comments, commits, docs, reports, issues, PRs. (Conversation with the owner is Korean.)
- Conventional Commits (`feat:`, `fix:`, `test:`, `docs:`, `chore:`, `ci:`).
- Every phase gate ends with a push to `origin`.
- `docs/SPEC.md`, `ARCHITECTURE.md` and `DECISIONS.md` are owner-owned: agents propose in the proposals section, they do not edit the settled parts.
- `docs/STATUS.md` is the single place progress is recorded, newest entry on top, updated by the main session only.
