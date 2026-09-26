# batch-control-plugin — Claude Code project rules

This repository is the Jenkins plugin `batch-control` (a plugin providing run/change
approval and audit history for environments that use Jenkins as a batch execution
manager). The goal is hosting in the official jenkinsci organisation.

## Single source of truth

- `docs/SPEC.md` is the authority on behaviour. If the code and the spec disagree, the code is wrong.
- If you conclude that the spec has to change, do not change the code: write the proposal in `docs/DECISIONS.md` and ask the human.
- Extension points and package structure follow `docs/ARCHITECTURE.md`.
- Progress is tracked in `docs/STATUS.md` and nowhere else.

## The role of the main session (orchestrator)

The main session does not write code itself. It does only the following.

1. Check the current Phase in `docs/WORKFLOW.md`.
2. Delegate the work to that Phase's subagents. Every delegation must state the "documents to read", the "writable paths" and the "deliverables".
3. When the deliverables come back, check the gate conditions and update `docs/STATUS.md`.
4. If the gate says "human confirmation", stop and report to the human.

Work that can run in parallel (for example core-dev and test-author) may be delegated
at the same time, but never run two agents that write to the same path at the same time.

## Path ownership (the core of conflict avoidance)

| Path | Agent allowed to write |
|---|---|
| `docs/STATUS.md` | main session |
| `docs/SPEC.md`, `docs/ARCHITECTURE.md`, `docs/DECISIONS.md` | humans only (agents may only propose) |
| `poc/**`, `docs/POC-RESULTS.md` | poc-engineer |
| `src/main/java/io/jenkins/plugins/batchcontrol/{model,store,policy,security,queue,listener,config,ops}/**` | core-dev |
| `src/main/java/io/jenkins/plugins/batchcontrol/{action,ui}/**`, `src/main/resources/**` | ui-dev |
| `src/test/**`, `docs/TEST-MATRIX.md` | test-author |
| `e2e/**` (including `e2e/README.md`), `docs/reports/e2e-*.md` | e2e-tester |
| `docs/reports/security-*.md` | security-reviewer |
| `docs/reports/red-team-*.md` | red-team |
| `docs/reports/spec-review-*.md` | spec-guardian |
| `src/main/webapp/help/**` | ui-dev |
| `pom.xml`, `README.md`, `CONTRIBUTING.md`, `Jenkinsfile`, `CHANGELOG.md`, `LICENSE`, `.github/**`, `docs/HOSTING-REQUEST.md`, `docs/HOSTING-READINESS.md`, `README.ko.md`, `docs/LIMITATIONS.md` | release-manager |

If you need a change in a path you do not own, do not make it: write it in your
deliverable report as `Request: <path> <what>`. The main session passes it to the
owning agent.

## Code conventions

- Java 21, Maven. The parent POM is the latest version of `org.jenkins-ci.plugins:plugin`, and `jenkins.version` is the latest LTS line supported by the plugin BOM. The jenkinsci hosting checker accepts only JDK 21 and 25, so the `Jenkinsfile` builds on 21.
- `groupId`: `io.jenkins.plugins`, `artifactId`: `batch-control`, package: `io.jenkins.plugins.batchcontrol`.
- All deliverables are written in English: code, comments, commit messages, README, design documents (docs/), reports, GitHub issues and PRs (human instruction 2026-09-20 — Jenkins targets a global audience). Existing Korean documents are not translated retroactively (only when the human gives a separate instruction). Conversation with the user is in Korean.
- Every Stapler web method (`do*`) that changes state has `@RequirePOST` plus a permission check as its first two lines. No exceptions.
- Anywhere user input ends up in a file path, a job name or HTML output, it must be validated/escaped.
- Code that switches to `ACL.SYSTEM2` leaves the reason in a comment, and the requester's and approver's permission checks must already be finished before the switch.
- A new feature does not change existing Jenkins behaviour while the global switch is off.
- The storage format follows the storage section of `docs/ARCHITECTURE.md`. Do not invent new file formats on your own.

## Frequently used commands

```bash
mvn -q clean verify            # compile + tests + SpotBugs
mvn -q test -Dtest=ClassName   # a single test
mvn hpi:run                    # local Jenkins (http://localhost:8080/jenkins)
mvn -q clean package -DskipTests && ls target/*.hpi
```

## Test independence rule

test-author does not read `src/main`. Tests are derived only from `docs/SPEC.md` and
`docs/TEST-MATRIX.md`. core-dev does not modify tests in order to make them pass. If a
test is judged to be wrong, write it in the report and the human decides.

## Commits and branches

- A branch per Phase: `phase-1-poc`, `phase-3-impl`, ... merged into `main` after the gate passes.
- Commit messages follow Conventional Commits (`feat:`, `fix:`, `test:`, `docs:`, `chore:`).
- Agents only commit; the human pushes.
