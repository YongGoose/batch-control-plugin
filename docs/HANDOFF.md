# Continuation Handoff

Last updated: 2026-09-23. This document lets any account/machine resume the work exactly where it stopped. The single source of truth for process is `docs/WORKFLOW.md` + `CLAUDE.md`; this file only records the current position and environment.

## Current position in the workflow

```
P0 setup ✅ → P1 PoC ✅🧑 → P2 test matrix ✅🧑 → P3 implementation (S1..S4) ✅
→ P4 security (security-01 ✅, fixes ✅, security-scan workflow ✅, security-02 re-review ⏳ NOT RUN)
→ P5 E2E ⏳ → P6 pilot 🧑 ⏳ → P7 release/hosting ⏳
```

- Branch state: everything is merged to `main` (`6f37f2d`). Phase branches `phase-1-poc`, `phase-2-matrix`, `phase-3-impl` are historical.
- Build state at `6f37f2d`: `mvn clean verify` = **BUILD SUCCESS, 155/155 tests, SpotBugs 0** (verified twice).
- Test matrix: `docs/TEST-MATRIX.md`, 134 rows. P0 non-e2e coverage 76/77 (the only gap is T-SEC-07, blocked on decision P-03). P1 non-e2e 100%. 9 e2e rows are Phase 5 scope.
- Reports so far: `docs/POC-RESULTS.md`, `docs/reports/red-team-01.md`, `spec-review-S1..S4.md`, `security-01.md`. **`security-02.md` does not exist yet** — the re-review after the security fixes is the immediate next step (see the Phase 4 issue).

## Immediate next steps (in order)

1. **security-02 re-review** (Phase 4 gate: BLOCKER 0 / HIGH 0). Verify each security-01 finding against commit `6f37f2d`: S-01 (P-09 visibility, `ui/Visibility.java`), S-03, S-05, S-06 (note: introduces a NEW `ACL.SYSTEM2` lookup-only site in `IncidentItem.doRerun` — scrutinize), S-07, S-10, S-11. S-02 and S-04 are deferred by pending human decisions; S-08/S-09 are README-documentation items for Phase 7.
2. **Phase 5 E2E** per `docs/WORKFLOW.md` Phase 5 and the e2e rows in the matrix (T-E2E-01..08, T-10-06), plus the visual checks deferred from earlier phases (PoC assumption D, T-02-02 visible-text check, T-06-15).
3. **Overall cross-review** (user-requested): feature-vs-SPEC and test-coverage assessment after Phase 5; includes the red-team second pass (its role file mandates a re-run against real code after Phase 4).
4. Phase 6 (human pilot), Phase 7 (release prep + hosting request draft).

## Open human decisions (blocking various follow-ups)

Tracked in `docs/DECISIONS.md` proposals section: **P-01..P-09**. Highest impact:
- **P-03** (password-parameter fidelity) — blocks **T-SEC-07**, the only missing P0 row (release-blocking follow-up).
- **P-06** (RequestGrant service-layer enforcement) / security-01 **S-04**.
- Security-01 **S-02** (incident transitions behind ViewHistory) — permission-model choice.
- **P-09** (visibility model) — implemented as default in Phase 4, needs ratification.
Also pending: a CLAUDE.md ownership-table row for `src/main/webapp/help/**` (assigned to ui-dev ad hoc), and retroactive approval for two mechanical test edits made outside test-author's normal flow (CLICommandInvoker imports; `(Cause) null` cast in IncidentTest).

## Environment (what this machine has; replicate as needed)

- JDK: Temurin 21 (`C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot`; Temurin 17 also present). Build with 21.
- Maven: 3.9.16 at `%USERPROFILE%\tools\apache-maven-3.9.16`.
- Build command (Git Bash):
  `export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot" && export PATH="$JAVA_HOME/bin:$HOME/tools/apache-maven-3.9.16/bin:$PATH" && mvn -ntp clean verify`
  Full suite ≈ 10 min on this machine.
- Docker: Docker Desktop 29.8.0 + Compose v5.5.1 installed (user-scope: `%LOCALAPPDATA%\Programs\DockerDesktop\resources\bin` — may not be on PATH in fresh shells). Needed for Phase 5.
- Versions pinned in `pom.xml`: parent `org.jenkins-ci.plugins:plugin:6.2236.v12dd4c483242`, `jenkins.version 2.568.3`, BOM `bom-2.568.x:7046.v43536164769c`.

## Process rules that bit us (avoid repeats)

- Multi-agent work per `CLAUDE.md` (orchestrator delegates; path ownership table). Agents must not run mvn concurrently — the shared `target/` causes file-lock failures on Windows.
- All written artifacts (docs, reports, issues, PRs, comments) in **English**; conversation with the user in Korean (CLAUDE.md, 2026-09-20 instruction).
- Every phase gate ends with a push to `origin`.
- test-author never reads `src/main`; expected-API contracts are pinned in the delegation prompts and in the tests themselves.
