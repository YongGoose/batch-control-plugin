---
name: slice-workflow
description: How to take one unit of work through this repository from start to finish — pick the matrix rows, commit a failing test, implement, run the gate, review, route the defects, close. Use it before delegating any feature, bug fix or slice to sub-agents, when several agents will work at the same time, when you are unsure of the order the steps must happen in, or when an agent needs a change in a file it does not own. It is the counterpart of `verify-gate` (is the build actually green) and `test-contract` (is the test worth trusting).
---

# Taking one slice around the loop

This is the *procedure* the repository was built with. The roles are defined in
`.claude/agents/*.md`, the phase plan in `docs/WORKFLOW.md`, the path ownership
table in `CLAUDE.md`, and the conventions a contributor must follow in
`CONTRIBUTING.md`. This skill is only the order of operations and the reasons
behind it — read those files for their own content rather than expecting it
repeated here.

A "slice" is any coherent piece of work: one of the four implementation slices in
`docs/WORKFLOW.md` Phase 3, a bug fix, or a follow-up from a review report. The
loop is the same size either way.

## The loop

```
0. fix the contract      →  which SPEC item / decision / matrix rows are in scope
1. freeze the names      →  before any delegation: types, endpoints, field names
2. red test first        →  test-author writes and commits a FAILING test
3. implement             →  core-dev and ui-dev, in parallel, inside their paths
4. gate                  →  mvn -ntp clean verify  (see the verify-gate skill)
5. review                →  spec-guardian / security-reviewer / e2e-tester
6. route the defects     →  each finding to the agent that OWNS the path
7. close                 →  STATUS.md updated, Conventional Commit, report
```

Steps 2 and 3 overlap on purpose: the implementer may start while the test is
still red. Steps 5 and 6 never start before step 4 is green.

## 0. Fix the contract first

Nothing is implemented because it seems right. Every change points at a line
someone can look up: a `SPEC.md` item with its acceptance criteria, a settled
decision, or a matrix row. `CONTRIBUTING.md` §3 is the map of those documents and
of the ID vocabulary (`D-nn` settled vs. `P-nn` still awaiting a human ruling —
never cite a `P-nn` as decided). If no such line exists yet, the change is a
specification question for the maintainer, not a coding task.

Behaviour change → specification change first. That ordering is what keeps the
suite derivable from something other than the code.

## 1. Freeze the names at delegation time — not later

The single most expensive failure mode when several agents run at once is not
merge conflicts in files; it is two agents building *different things that cannot
be joined*. The test author writes `getApprovers()`, the implementer writes
`getApproverList()`, the screen posts to `…/approve/`, the endpoint is `doApprove`
under a different path — every piece is individually correct and the whole does
not link.

So pin the shared vocabulary **in the delegation prompt**, before any of them
starts:

- class and method signatures the test will call (the test author derives the
  expected public API from the spec's data model and the architecture's package
  names, and reports it as an "expected API" list — accept that list as the
  contract, and hand it to the implementer verbatim)
- **form field names**, because the screen and the test both hard-code them
- **URL shapes**, because a test asserts against them and a Jelly view links to
  them

This worked in this repository: `approversText` (the global configuration form
field), `blockUpstream` (the job property field), and
`batch-control/requests/<id>/approve` (the approval endpoint) were fixed up front
and the parallel test/core/UI work merged without a rename pass. Spellings that
were *not* fixed up front are where the rework happened.

Write the frozen names into the report as well, so the next slice inherits them
instead of re-inventing them.

## 2. Commit a failing test first — and why it is not about TDD

The order is: the test author writes the test, commits it red, and *then* the
implementation makes it green. The reason is not the discipline of writing tests
first. It is that **the test author does not read `src/main`** (see the
`test-contract` skill and `CONTRIBUTING.md` §4 rule 2). The tests are derived from
`docs/SPEC.md` and `docs/TEST-MATRIX.md` alone.

That is what makes the suite worth something: it does not share the
implementation's assumptions, so it can catch the case where the implementation is
*self-consistently wrong* — internally coherent, coherent with its own tests, and
not what the specification asked for. A test written after reading the code
inherits the code's misreading and then confirms it.

The red commit has a second use: it proves the test can fail at all. A test that
has never been seen failing is not evidence of anything.

Practical consequence for delegation: a test that does not compile before the
implementation exists is the expected state, not a defect. Do not ask the test
author to "make it build" against a stub — ask for the expected-API list instead,
and give it to the implementer.

## 3. Implement inside your own paths — and say "Request:" for the rest

`CLAUDE.md` holds the authoritative path-ownership table; it is what makes
parallel agents safe. Two rules follow from it and neither is optional:

- Never run two agents that write to the same path at the same time. The pairs
  that are safe to parallelise are the ones whose paths are disjoint (the test
  suite, the core packages, and the UI packages plus resources are three such
  areas).
- When your work needs a change in a path you do not own, **do not make it**.
  Write a line in your report:

  ```
  ## Request: <path> — <what is needed and why>
  ```

  The orchestrator forwards it to the owning agent. This is why the reports in
  `docs/reports/` are full of such lines. The cost of the round trip is far lower
  than the cost of two agents disagreeing about a file neither of them fully
  understands — and the owner keeps a single coherent view of their own area.

The same rule is why a test is never "fixed" by the implementer to make the build
pass. If the test looks wrong, that is a `Request:` line plus a sentence of
reasoning, and a human rules on it.

## 4. Gate

`mvn -ntp clean verify`. Whether the result counts as a pass is a question with
non-obvious answers — use the **`verify-gate`** skill. Do not report "green"
from a filtered tail of the output.

## 5–6. Review, then route

Review output lands in `docs/reports/` as a point-in-time document, with a
severity per finding. Two things matter when routing:

- Route each finding to the **owner of the path** it touches, never to whoever
  found it. A reviewer that edits the code it just reviewed has stopped being an
  independent reviewer.
- Keep defects and awkwardness apart. A specification or security defect is
  routed, fixed and re-run. A usability complaint from an end-to-end pass is a
  judgement call for the maintainer, and belongs in its own section of the report
  so it is not silently "fixed" into a behaviour change with no spec line behind
  it.

A regression test is part of the fix for a review finding, not a follow-up: the
finding becomes a matrix row and a test, then the code changes.

## 7. Close

- `docs/STATUS.md` gets the outcome (newest entry on top). It is the single place
  progress is recorded — do not spread status across report files.
- Conventional Commits, and reference the anchor: the spec item, decision or
  matrix row. `CONTRIBUTING.md` §7 has the full pull-request expectations.
- The slice is closed when the gate is green, the review has no blocking finding,
  and every `Request:` line raised inside it has been answered or explicitly
  deferred with a reason.

## When a step cannot be completed

Try at most a few times, then stop and ask. A blocked slice reported early is
cheap; a slice that was silently narrowed until it passed is the expensive one,
and the review will not catch it because the evidence was removed along with the
scope.
