# Security Review 17 — PR #46 (`fix/e2e-run3-part2`, e2e-03 part-2 defects)

Reviewer: security-reviewer (returned as text; persisted by the main session, condensed). 2026-09-30.

## Summary: BLOCKER 0 / HIGH 0 / MEDIUM 0 / LOW 1

## LOW
- **S-17-01** `security/BatchControlRoleBasedAuthorizationStrategy.java` (`doCheckPattern`): no permission check in the method body, only `Pattern.compile()`. Identical to upstream role-strategy (`RoleBasedAuthorizationStrategy.java:1745`), so the PR reproduces an existing exposure rather than adding one. No change needed here; harden both places if ever.

## Checked and found to be fine
Every new or changed `do*` keeps `@RequirePOST` and a permission check first; `doSubmit`/`doRerun` check Item/Build (D-38) and the view gates mirror the service checks; the recorded refused grant request is written only after the existing permission gate and is size-capped; `BatchControlConfigurationLink` exposes only the Batch Control configuration to BatchControl/Manage; the role-variant descriptor methods delegate to the parent's permission checks and hide the same dangerous permissions as upstream; the name-restriction validation only re-renders the current user's own refusal; all new Jelly is escape-by-default; a refused form never echoes secret parameters; history and dashboard links check record-level visibility; CLI and Replay refusal paths only change how an already-decided refusal is rendered; cancel attribution is written inside the existing critical section.
