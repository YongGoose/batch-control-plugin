<!-- The guide is CONTRIBUTING.md. Delete any line below that does not apply. -->

## What this changes

<!-- One paragraph. Why, not only what. -->

## Anchor

- **Issue:** `fix #<n>` — or `[no-issue]` if there genuinely is none.
- **Contract:** the SPEC item, decision or matrix row this implements —
  e.g. `SPEC §8`, `D-31`, `T-06-11`. Reviewers start from "which contract is
  this?", so a change with no anchor is hard to review here.

## Checks

- [ ] `mvn clean verify` passed locally: **0 failures**, SpotBugs
      **`BugInstance size is 0`**.
- [ ] **Behaviour change?** `docs/SPEC.md` says so — the spec is the arbiter, and
      it changes first. (Not applicable to a pure bug fix that restores what the
      spec already requires.)
- [ ] **New behaviour?** It has a row in `docs/TEST-MATRIX.md`, the row ID is in
      the test's name, the test was committed failing first, and it would fail if
      the feature were removed (see CONTRIBUTING §4 — a test that passes while
      measuring nothing is worse than none).
- [ ] **Touched `src/main/resources/**`?** Opened the screen with `mvn hpi:run`
      or in `e2e/` and looked at it — Jelly only compiles at runtime, so the
      build cannot tell you this.
- [ ] This PR does **not** disclose a security vulnerability. Those go privately
      to the Jenkins **SECURITY** project, never to a public issue or PR — see
      *Reporting security vulnerabilities* in `README.md`.
