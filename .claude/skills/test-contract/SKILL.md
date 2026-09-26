---
name: test-contract
description: The two test rules in this repository that are easy to break and expensive to lose — an assertion is never loosened to make a test pass, and every positive assertion is paired with a guard that fails if the feature is removed. Use it when writing a new test, when a test and the implementation disagree, when you are tempted to relax or delete an assertion, when a test passes on the first try, or when reviewing whether a test actually measures anything. The full conventions are in CONTRIBUTING.md §4; this is what to weigh while deciding.
---

# What makes a test here worth trusting

`CONTRIBUTING.md` §4 is the canonical list of the five test conventions — matrix
row first, derive from the specification and not the implementation, commit the
failing test first, never loosen an assertion, pair every positive row with a
false-positive guard. Read it; it is short, and it has the concrete fixture rules
(`BatchControlFixtures`, the standard blocking triple) that this file does not
repeat.

The ID vocabulary — which row type is a spec row and which pins an
implementation-defined screen contract — is in `CONTRIBUTING.md` §3. The
independence rule (the test author does not read `src/main`, and why) is in the
`slice-workflow` skill.

What follows is the two rules that decide whether the suite keeps its value, and
what they look like when they are actually under pressure.

## 1. Never loosen an assertion to make it pass

When a test fails, exactly one of two things is true: **the code is wrong, or the
contract is wrong.** Both have a legitimate fix. "Widen the assertion until it
goes green" is neither, and it is the one that costs the most, because it leaves
behind a test that reports coverage it no longer has.

So:

- Code wrong → fix the code.
- Contract wrong → change the contract where the contract lives (the
  specification, or the documented screen contract), say so explicitly, and then
  adjust the test to the *new* contract. Not quietly, and not in the same breath
  as the code change.

If you are not the owner of the contract, stop and say so. That conversation is
cheap.

**The case worth remembering.** A row about the approval screen's recent-run size
parameter asserted that a padded value such as `runs=10 ` was an unsupported value
and fell back to the default. The implementation trimmed the value first and
honoured it as 10. The test was red, and the tempting repair was to relax the row
to "either behaviour is acceptable" — which would have left the parameter's
handling unpinned in both directions.

What happened instead: the owner ruled that trimming a hand-typed query parameter
is ordinary web input handling, so the trimmed value is an *allowed* value. The
contract was made explicit — trim first, then match the allow-list — and the
assertions were **increased**, not reduced: every unsupported value still falls
back silently with HTTP 200 and no error surfaced, *and* each padded allowed value
renders the window of its trimmed number. The row now says something precise
about both halves. The write-up is `docs/TEST-MATRIX.md` note 40 and the
screen-contract summary at the end of that file.

The pattern to copy: a disagreement is an opportunity to state the contract more
sharply. A test that got weaker in the repair is a sign the real question was
dodged.

## 2. Pair every positive assertion with a false-positive guard

An assertion that would still pass with the feature removed is worse than no
assertion, because it reports coverage that does not exist. Ask, of every test:
**if I deleted the feature, would this test fail?** If you cannot answer yes from
the assertions on the page, the test is not finished.

This is not hypothetical here. Two examples, both real:

**Two rows were passing while measuring nothing.** They installed their job-level
settings in a way a later default silently shadowed, so the plugin read the
default instead of the fixture — one field happened to agree between the two
copies, so the rows went green while the settings they exist to exercise
(`blockUpstream` and its allow-list, and an "uncontrolled job" premise) had no
effect at all. Green was not evidence the fixture had taken effect. The full
story, and the fixture helper that now makes it impossible to reproduce silently,
is `docs/TEST-MATRIX.md` note 42 — the best five minutes to spend before writing
a fixture in this repository.

The lesson generalises past that one default: **assert that the premise took
effect, not only that the outcome looks right.** Read the setting back and check
it is the instance the fixture installed.

**A notice that is always on the screen passes a "the notice appears" test.** The
post-approval notice is supposed to be visible only while a request is approved
and has not yet executed. A test asserting "the notice is present on the approved
screen" would have passed against an implementation that rendered it
unconditionally. So the row asserts both directions through a stable marker the
UI keeps as a test hook: **present** on the approved-and-unexecuted screen, and
**absent** on the executed, rejected and cancelled ones. Appearance and
disappearance, in the same row.

### The three habits that come out of this

- **Assert the premise, not only the outcome.** Read state back after arranging
  it. A fixture that did not apply is invisible otherwise.
- **Write the negative twin.** If one row asserts a record is written, another
  asserts a clean run writes none. If a control blocks something, one row shows it
  does not block what it must leave alone. The pairs are as important as the
  positives, and `CONTRIBUTING.md` §4 names the established ones.
- **Assert every part of a compound outcome.** For a blocked run this repository
  asserts three things together (empty queue, unchanged next build number, no
  build after waiting) — because a run that was blocked and then quietly executed
  a moment later satisfies any one of them alone.

## A test that passed the first time is a question, not a result

It may be correct. It may also be pinning nothing. Before accepting it: break the
feature on purpose — comment out the check, flip the setting, remove the listener
— and confirm the test goes red. Then put it back. That is the cheapest possible
version of the failing-test-first rule for a test that arrived after the code, and
the only way to tell the two cases apart.
