# Security Review 33: hosting-review fixes of 2026-10-03 (`integration/e2e-2`, head 7a7747d)

Reviewer: security-reviewer. 2026-10-03.

Scope: `git diff main...integration/e2e-2 -- src/main pom.xml` (60 files, the six PR branches merged:
fix/move-bypass, fix/auth-strategy-cleanup, fix/new-job-ui, feat/run-request-without-build,
fix/ui-polish, feat/new-ui-layout). Standard: HOSTING-CHECKLIST section B, CLAUDE.md code rules,
D-35e, D-35f, D-35f(a), D-38a, D-59, D-60. Hosting reviewer: mawinter69
(jenkins-infra/repository-permissions-updater#5338).

Maven is not available in this sandbox (`mvn` not on PATH), so the merged tree was not built.
SpotBugs on the merge is **unconfirmed**; see "Checked and found to be fine" for the per-branch reports.

## Summary: BLOCKER 0 / HIGH 0 / MEDIUM 1 / LOW 3 / INFO 6

The diff adds two web methods: `BatchControlRootAction#doContextMenu`, which is read-only, and the role descriptor's
`doCheckSidName`, which has `@RequirePOST` and an inline permission check. It adds no `ACL.SYSTEM2` switch, no file path and no raw output.
D-59 closes MV-1/MV-2 on every path through the folders plugin. D-60 builds its redirect only from
the job's own URL and URL-encodes the values. Sensitive values are filtered out on both the way in and the way out.
The one design-level gap: a move carries the job's activation. Under D-34 a recreated job starts locked, so
"Delete here, Create there" is not the same as delete and recreate (S-33-01).

## BLOCKER
None.

## HIGH
None.

## MEDIUM
- [S-33-01] `security/MoveGuard.java:70-82` (rule of D-59), LIMITATIONS item 44. The move rule
  says a move is "delete here, create there". The LIMITATIONS justification for the residual
  is "such a user could already delete and recreate them". That equivalence does not hold for
  activation. An activation "survives configuration edits, renames and moves" (SPEC item 6a,
  LIMITATIONS), while a recreated job starts locked (D-34). Scenario: user U holds native
  Item/Move. U gets a `DELETE` window on `prod/x`, which LIMITATIONS item 44 itself recommends
  for moves, and a `CREATE`+`CONFIGURE` window on `sandbox`. U moves the activated `prod/x` into
  `sandbox` and edits its script. The job keeps its cron/upstream triggers active (it stays
  activated) without any activation request. With `DELETE` on `sandbox` and Create at the source,
  U can also move it back. No approver approved "edit and keep running `prod/x`". They approved
  deleting it and creating new (locked) jobs in `sandbox`. MV-2 is closed only for users who lack
  Delete. Basis: B (unauthorised state change through a grant combination), D-34/D-59
  consistency. Classified MEDIUM, not BLOCKER, because every step needs an approved window or a
  standing Delete. If the owner reads D-34 as "no unapproved configuration ever runs activated",
  raise it to HIGH. Fix direction (owner decision first, `docs/DECISIONS.md`): while change control is on,
  a move by a non-administrator puts the moved job and its descendants back into the D-34 locked
  state, recorded like a creation. Alternatively, it requires Item/Configure held natively on the
  item in addition to Delete. Either way, correct the LIMITATIONS 44 sentence. Test: Given change control and run control are on, an activated
  job `prod/x` with a cron trigger, and user U with native Move, a DELETE window on `prod/x` and a
  CREATE window on `sandbox`, When U moves `prod/x` into `sandbox`, Then `sandbox/x` is not
  activated (timer runs are blocked) and an activation request is needed.
  Owner: human (D-59 amendment), then core-dev (`security/**`, `listener/**`), release-manager (`docs/LIMITATIONS.md`).

## LOW
- [S-33-02] `security/ChangeControlledRelocationHandler.java:27,49`: the guard runs only inside
  the folders plugin's `move/move` handler chain. It is not a veto on `Items.move`. Core offers no
  listener that can veto a move, so two paths remain outside it. First, any other plugin that calls `Items.move`
  directly. Second, a third-party `RelocationHandler` registered with an ordinal above 10 000 that answers
  `HANDLE` and moves without delegating. Scripts are not a gap: they need Overall/Administer, which is exempt anyway. Core has no CLI move command and
  core renames stay within the parent. LIMITATIONS item 44 says "moving an item" without that scope.
  Fix: document that D-59 governs the folders plugin's Move action (UI and REST, same endpoint), and
  that moves made by other plugins are outside it. Optionally, log a WARNING at startup when another
  `RelocationHandler` with a higher ordinal is installed. Test: Given change control is on, When
  the extension list of `RelocationHandler` is read, Then `ChangeControlledRelocationHandler` is
  first among the non-SKIP handlers for a job. Owner: release-manager (`docs/LIMITATIONS.md`), core-dev.
- [S-33-03] `ui/ApprovalRequiredFailure.java:115`, `ui/RequestRunPrefill.java:50`: D-60 puts the
  refused submission's non-sensitive values into the redirect URL (`?p.NAME=value`). There they reach
  the browser history, reverse-proxy and Jetty access logs, and the `Referer` of the next request.
  A plain `StringParameterDefinition` that holds an internal hostname, ticket text or a token typed by
  mistake is not "sensitive" in Jenkins' sense. Up to 2 000 characters per value with no total limit
  can also produce a `Location` header or request line beyond Jetty's default header buffers.
  The result is then an HTTP 431/500 instead of the form, which is functional only. Not an open redirect: the
  target is `contextPath + job.getUrl()` and the values are URL-encoded. Basis: B (secrets in
  logs, defensively). Fix: cap the whole query (for example 6 KB) and drop the pre-fill beyond it.
  State in the help/LIMITATIONS text that values of non-password parameters travel in the URL, or
  carry them in a one-shot session attribute instead of the query (needs a D-60 amendment).
  Test: Given a parameterized approval-required job with 10 string parameters of 2 000
  characters, When a requester's build POST is refused, Then the response is a 303 whose
  `Location` is shorter than the cap. Owner: ui-dev (`ui/**`), release-manager (`docs/LIMITATIONS.md`).
- [S-33-04] `security/MoveGuard.java:234`: every refused move logs one INFO line. The audit record is
  coalesced per key for one minute by `BlockedAttemptAudit`, so the file is bounded, but the log is not.
  An authenticated user with Item/Move can repeat the POST (crumb or API token) and fill the
  controller log. The impact is low: it needs authentication and Move, and core logs similar lines elsewhere. Fix: log at
  INFO only when `BlockedAttemptAudit.record` returns `true` (a new record), else FINE. Test:
  Given a refused move, When it is repeated 100 times within a minute, Then one GRANT_VIOLATION record
  and at most one INFO line exist. Owner: core-dev (`security/**`).

## INFO
- [S-33-05] `security/MoveGuard.java:173`: `checkCurrentRequest` runs inside
  `GrantAwareACL.hasPermission2` and resolves `destination` with `Jenkins#getItemByFullName` as the
  user. For a destination the user can only Discover, this throws `AccessDeniedException3` out of a
  permission check. The answer is a 403 instead of the folders plugin's own handling. It reveals nothing beyond Discover
  and moves nothing. Fix: catch `AccessDeniedException` there and return `null`.
- [S-33-06] D-60 pre-fill by crafted link: anyone can send a requester a link `<job>/batch-control/?p.TARGET=prod`.
  The form shows the values with a constant "pre-filled" notice, and nothing is queued until the
  requester submits with a crumb. The approver then reviews the request. Social engineering only,
  the same as core's `buildWithParameters` links. No action needed.
- [S-33-07] D-38a, Request at ITEM scope (`security/BatchControlPermissions.java`): no other
  permission is implied by Request, and grants map only to Create/Configure/Delete
  (`GrantAction.fromPermission`), so the grant layer cannot confer Request. A window holder's attempt
  to add a per-item Request entry is reverted by the D-35b/D-58 guard. A **native** Item/Configure
  holder can give themself Request on that item through matrix-auth's item property. That is matrix-auth's
  documented model, it lets them only *ask*, and it is no wider than their existing ability
  to grant themself Build. Role-strategy item roles are administrator-configured.
- [S-33-08] D-38a consistency (functional, not a leak): Request is now checked on the job for run
  requests. Other places still check it globally: the root action/sections (`ui/SectionAccess.java:36-60`), the own-request
  cancel (`action/RequestItem.java:376`) and activation requests (`policy/ActivationService.java:297`,
  `action/JobActivationForm.java:159`). A user with Request assigned only on a job or folder can therefore
  submit a run request but gets 404 on its detail page (`/batch-control/requests/<id>/`) and
  cannot cancel it. The activation entry also appears under the job's `batch-control` URL space and answers 403 on submit.
  Fail-safe direction, but worth an e2e check and a SPEC sentence. Owner: core-dev / ui-dev, e2e-tester.
- [S-33-09] `policy/RunRequestService.java:168-173`: `requesterLacksBuild` impersonates the
  requester (`User#impersonate2`, a realm lookup) on every render of the request detail page and for
  each created/approver-changed notification. No `ACL.SYSTEM2` switch, the lookup of the job is made as
  the viewer, and failures fall back to "lacks Build" (visible, conservative). On an LDAP realm this
  costs one directory query per page view; caching per request id for a short time would be enough.
  The notice reveals one bit (whether the requester holds Build) to viewers of the request, by D-38a design.
- [S-33-10] SpotBugs on the merge is unconfirmed: Maven is not available here, and `target/` of the
  integration worktree has no `spotbugsXml.xml`.

## Checked and found to be fine
- Web methods (B-1/B-2/B-3): `grep "public .* do[A-Z]"` over the diff finds two new ones.
  `BatchControlRootAction#doContextMenu` is read-only. It returns an empty menu unless `isVisible()`, the action's
  URL is 404 for others, and it lists exactly `SectionTabs.current()` (`SectionAccess` predicates) plus
  Configuration only when `getConfigurationLink()` is non-null. `RoleBasedAuthorizationStrategy.DescriptorImpl#doCheckSidName`
  has `@RequirePOST` and, as its first statement, `checkAnyPermission(SYSTEM_READ, ITEM_ROLES_ADMIN, AGENT_ROLES_ADMIN)`,
  the same as the existing `doCheckName/Pattern/ForWhitespace`. No existing `do*` lost its annotation or check.
  `doRerun`, `doSubmit`, `doMigrate`, `doRevert` and `doMarkReviewed` keep `@RequirePOST` plus a permission check first. `doRerun` now checks ViewHistory
  and then Request on the job.
- Realm probing (B-3): `RoleSidChecks.checkName/checkSidName` return the bare escaped sid
  without any realm lookup unless the caller holds Overall/SystemRead, as upstream does. No `User` record is created as
  a side effect (`User.getById(id, false)`).
- Escaping of the copied role-strategy code (B-4): every sid and realm display name goes through
  `Functions.escape`/`Util.escape`, which also escapes `'`, before it goes into the single-quoted `tooltip`
  attribute or the cell. Constant messages go through `FormValidation.error/ok`, which escape.
  `FormValidation.error(e, escapedSid)` double-escapes, which is cosmetic only.
- Jelly (B-4): all 26 changed/new `.jelly` files start with `escape-by-default='true'`, and the diff
  adds no `escapeXml="false"` or `<j:out`. `MoveRefusal/index.jelly` renders the message and item name
  escaped, and links the item only when `getItemUrl()` (looked up as the viewer) is non-null.
  `MoveRefusal` sends plain text with `X-Content-Type-Options: nosniff`. The `l:confirmationLink`
  titles contain item names, which `Jenkins.checkGoodName` restricts (no `<`, `&`), and they are escaped anyway.
- CSRF (B-11): migrate, revert, markReviewed and the cancel/revoke buttons moved to `l:confirmationLink post="true"`.
  Core's script adds the crumb, and the endpoints keep `@RequirePOST`. The remaining plain `<form method="post">` (migrate) gets
  core's crumb.
- Badges: `SectionTabs` counts only PENDING requests where the viewer is a designated approver
  (with Approve) or the requester. Both are visible to that viewer by `Visibility.canSee*Request` (P-09).
  A badge is computed only for a tab the viewer may open. No count leaks requests the viewer cannot see.
- D-59 enforcement: `ChangeControlledRelocationHandler` (ordinal 10 000, DELEGATE) re-checks
  right before the standard handler's `Items.move`, whatever authorization strategy is installed. The UI and REST use the same
  `move/move`. `MoveGuard.check` judges Delete on the item and Create on the destination as the current user through the grant
  layer. A CREATE grant's name restriction matches the moved item's name only for a POST to
  `move/move` dispatched through `RelocationAction` (`NewItemName.movedItem`). Outside a request this is UNKNOWN, so a
  restricted grant confers nothing (fail-safe). A folder move needs Delete on the folder, which
  in core already allows deleting its children. Admin and SYSTEM are exempt by design. The refusal is recorded
  once: the `GrantAwareACL` path throws before the handler runs, and the handler records only when that path
  returned `null`. Repeats are coalesced by `BlockedAttemptAudit` (one minute, keyed by user/item/destination). No
  `ACL.SYSTEM2` is used. With change control off, the handler is SKIP and the ACL hook decides nothing.
- D-60: the redirect only happens for a POST to the job's own `build`/`buildWithParameters` (the last
  ancestor is the job and the last URI segment matches), when `canRequest(job)` holds. It is never an open redirect.
  `carriedValues` and `apply` both exclude password definitions, `isSensitive()` values and `Secret` values,
  and pre-fill is read only on GET. Values are re-created through the definition (`createValue`, so a choice outside
  the list is dropped) and rendered by core's escaped parameter views. Nothing is queued: the queue handler
  refused before scheduling, and the redirect stores nothing. Re-runs pass `null` (no carry).
- D-38a impersonation (B-5): `requesterLacksBuild` uses `user.impersonate2()` for an ACL query
  only. There is no context switch and no `ACL.SYSTEM2`, so the CLAUDE.md SYSTEM2 rule does not apply. `create` checks
  Request and Read on the job before anything is stored. The approved run still passes the approval policy check
  (#26, unchanged).
- D-35e: `grep BatchControlAuthorizationStrategy|fromLegacyDelegate|emptyMatrix` over `src/main` finds nothing.
  No XStream alias or load converter remains that could trust or convert a wrapper. Conversion happens only through
  `doMigrate` (Administer, POST).
- D-35f: role-strategy is pinned to `918.v91e5468d8db_2` and stays `optional`. The pin narrows the versions it can be installed with and widens
  nothing. The descriptor methods delegate validation only. The Batch Control ACL is unchanged.
- Secrets (B-6): the diff adds no `getPlainText` and puts no secret into a log, record or notification. Notification
  `notices` are a constant sentence.
- File paths (B-7), CSV (B-8), XStream model (B-9), XML parsing (B-10): the diff touches none of them.
- Concurrency: the diff does not change the approval state transitions. `BlockedAttemptAudit.record` is
  `synchronized`, and `MoveGuard` calls it on the request thread outside any queue lock.
- `@Restricted(NoExternalUse.class)` (B-14): present on every new class (`MoveGuard`, `MoveRefusal`,
  `ChangeControlledRelocationHandler`, `RoleSidChecks`, `RequestRunPrefill`, `RequesterPermission`, `SectionTabs`).
- SpotBugs (B-15), per branch: `target/spotbugsXml.xml` has 0 `BugInstance` in `pr-ui-polish`, `pr-move-bypass`,
  `pr-run-request-without-build` (each at or after its head commit), and `pr-auth-strategy-cleanup`. The last one is
  dated 11:49, before its head commit at 12:11. `pr-new-job-ui` and `pr-new-ui-layout` have no report. The merge is unconfirmed (S-33-10).

## Request
- Request: `docs/DECISIONS.md` (human) amend D-59 for S-33-01: decide whether a non-administrator's move relocks the moved job, as for a creation under D-34, or whether a move also needs a native Configure.
- Request: `docs/LIMITATIONS.md` (release-manager) item 44: correct "could already delete and recreate them" (activation survives a move, S-33-01), scope the rule to the folders plugin's Move action (S-33-02), and note that values of non-password parameters travel in the D-60 redirect URL (S-33-03).
- Request: `src/main/java/io/jenkins/plugins/batchcontrol/security/MoveGuard.java` (core-dev) catch `AccessDeniedException` in `destination()` (S-33-05), and log at INFO only for a new record (S-33-04).
- Request: `src/main/java/io/jenkins/plugins/batchcontrol/ui/ApprovalRequiredFailure.java` (ui-dev) cap the total pre-fill query length (S-33-03).
- Request: main session, run `mvn -q clean verify` on `integration/e2e-2` and confirm 0 SpotBugs findings (S-33-10).
- Request: e2e-tester, check a user whose Request is assigned only on a folder: submit, then open the request page, then cancel (S-33-08).
