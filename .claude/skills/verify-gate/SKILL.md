---
name: verify-gate
description: How to run `mvn clean verify` in this repository and decide whether it actually passed. Use it before reporting a build as green, when a build fails for a reason that looks unrelated to the change, when `clean` itself fails or a file under target/ cannot be deleted, when the test count moved, or when you are about to pipe Maven output through head/grep to keep it short. Also lists the reasons a change gets sent back rather than accepted.
---

# The gate, and how to read it

The gate for every change in this repository is one command:

```sh
mvn -ntp clean verify
```

`CONTRIBUTING.md` §1 has the environment (JDK, Maven, the pinned parent POM and
BOM versions) and what a healthy run looks like in numbers. This skill is about
**judging the result** and about the ways the command lies to you.

## Pass

All three, together:

1. **0 failures and 0 errors** across the whole suite.
2. **`BugInstance size is 0`** from SpotBugs. There is no exclusion file to hide
   findings behind, so zero means zero. Adding one — or a suppression annotation —
   is not a way to reach this state (see "Sent back", below).
3. `BUILD SUCCESS`.

`0 failures` is the signal. The exact test count is informational and legitimately
moves as rows are added, so never gate on the integer — but see the next section,
because the *direction* it moves in is a signal of its own.

## A test count that went DOWN is not a pass

This is the most common real accident in this repository, and it looks exactly
like success: fewer tests ran, all of them passed, `BUILD SUCCESS`.

Tests stop running silently. A missing or wrong annotation is enough — a method
that lost its `@Test`, a class whose runner annotation no longer matches the
JUnit version it is written against, a name that no longer matches the surefire
include pattern. Nothing reports an error, because from the build's point of view
there was simply less to do.

So compare against the previous run before calling it green:

- Note the test count of the run you are comparing to (the last green run, or the
  figure recorded in `CONTRIBUTING.md` §1 / the newest `docs/reports/` entry).
- If the new count is **lower and you did not delete tests on purpose**, the build
  has not passed. Find the tests that stopped running before doing anything else.
- If you did delete or merge tests, say so explicitly in the report with the
  expected delta, so the next person can tell an intended drop from a silent one.

This check was made an explicit gate step during the JUnit 5 migration of the test
sources, precisely because a migration touches exactly the annotations that decide
whether a test runs at all. Any change that rewrites test annotations, moves test
classes, or touches surefire configuration inherits the same risk.

The same reasoning applies to SpotBugs: a *smaller* analysis is not a cleaner one.
If the reported class count drops, analysis stopped covering something.

## Getting the output without breaking the build

**Never pipe Maven's output through `head`, `sed NUMq`, or anything else that
closes the pipe early.** When the reader exits, Maven gets SIGPIPE and dies
mid-build, and the JVMs it had spawned can outlive it still holding
`target/patch-modules/org-netbeans-insane-hook.jar`. On Windows that lock then
blocks the **`clean` phase of the next build** — so the failure appears one build
later, on a command that has nothing to do with it, and looks like a broken
checkout rather than a truncated pipe. This happened twice in a single session
here; both times it presented as "build failure" and both times the code was
fine.

Do this instead — capture the whole run to a file, then search the file:

```sh
mvn -ntp clean verify > /tmp/verify.log 2>&1 ; echo "exit=$?"
grep -nE "Tests run|\[ERROR\]|BugInstance|BUILD " /tmp/verify.log
```

If a lock has already happened: do not fight it. Confirm no `java` process
belonging to the build is left (`jps`, or the platform's process list), wait for
them to exit, then delete `target/` and start over. Killing Maven while it holds
the lock reproduces the same state.

**And do not filter too narrowly.** A filter of `Tests run|BUILD` leaves you with
a single `BUILD FAILURE` line and no cause — the reason was in the lines you
dropped. Always include `[ERROR]` (and for a test failure, the surefire summary
plus the failing class's own lines). Read the first error, not the last: later
errors are usually consequences.

Related, and in `CONTRIBUTING.md` §6: **never run two Maven builds against this
checkout at once**, including a `verify` in one terminal and an `hpi:run` in
another. They share `target/` and deadlock on the same Windows file locks. If
someone else is running the gate, wait — do not start a second one.

## Before you investigate a failure as a product defect

`CONTRIBUTING.md` §6 is the canonical list of traps that have already cost time
here, and several of them make a green product look red. Read it before debugging.
The shape to keep in mind: **a first failing build is more often an environment
difference or a fixture trap than a product defect**, and the specific ones
already catalogued include a Windows temporary-directory flake that reddens
whichever test the runner happened to be on, fixture ordering that makes a
job-level setting silently ineffective, and Jelly views that only compile at
runtime so a screen can be broken with the whole suite green.

Two consequences for judging the gate:

- A single red test in a class: re-run that class alone before reporting it.
- A green gate is **not** evidence that a screen works. Anything under
  `src/main/resources/**` needs `mvn hpi:run` or the `e2e/` environment and an
  actual look, because Jelly is not compiled by the build.

## Sent back, not accepted

These are rework, regardless of what the build says:

- **An assertion was loosened, widened, deleted, or its scenario narrowed to make
  it pass.** If the test and the code disagree, either the code is wrong or the
  contract is wrong — fix the code, or change the contract in the spec and say so.
  See the `test-contract` skill.
- **A static-analysis finding was silenced** — a suppression annotation, a
  SpotBugs exclusion, a lowered threshold. The finding is either a real defect or
  a false positive worth explaining in the pull request; it is never something to
  annotate away quietly.
- **A test pins behaviour the specification does not require**, presented as if it
  did. Implementation-defined screen contracts are allowed here, but they are
  labelled as such so they can be dropped with the feature (`CONTRIBUTING.md` §3,
  the `T-UI-nn` row type).
- **An agent edited a path it does not own**, including an implementer editing a
  test to make it green. Ownership is in `CLAUDE.md`; the mechanism for needing
  someone else's file is a `Request:` line (`slice-workflow` skill).
- **The gate was reported green from a filtered or truncated log**, or from a
  partial run (`-Dtest=…`) presented as the whole suite.

## Reporting it

State the three pass conditions with their actual values, the previous count you
compared against, and the log path. "Green" on its own is not a report — the next
person cannot tell whether the count was checked.
