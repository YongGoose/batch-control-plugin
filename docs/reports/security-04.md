# Security Review 04
Scope: `git diff main...hosting-review/cleanup -- src/main` (hosting-review cleanup, issue #31).
Basis: docs/HOSTING-CHECKLIST.md section B, docs/SPEC.md item 2 acceptance lines.

## Summary: BLOCKER 0 / HIGH 0 / MEDIUM 0 / LOW 2

## BLOCKER (grounds for hosting rejection)
None found.

## HIGH
None found.

## MEDIUM
None found.

## LOW
- [S-01] `src/main/java/io/jenkins/plugins/batchcontrol/ui/HttpVerbs.java:24` and every
  `getTarget()` that calls `HttpVerbs.refuseUnsupported()` (RequestsSection.java:47,
  GrantsSection.java:98, ChangesSection.java:48, DashboardSection.java:56,
  IncidentsSection.java:50, HistorySection.java:94) — the removal of the per-endpoint
  `doIndex`/`refuseNonGet` methods (which previously answered 405 to any verb but GET/HEAD on a
  read-only list/detail page) was replaced by one subtree-wide check that also allows POST
  (`ALLOW = "GET, HEAD, POST"`). A bare `POST /batch-control/history/`,
  `POST /batch-control/requests/<id>/`, etc. now renders the same read-only view (200) instead
  of 405. No state change occurs and the permission gate (`checkAnyPermission`, still the first
  statement in every `getTarget()`) is unaffected, so this is not exploitable — but it is a
  documented, deliberate widening (HistorySection.java's class javadoc now says "PUT/DELETE/PATCH
  get 405 everywhere" rather than "every verb but GET/HEAD"), and is worth a conscious choice
  rather than an incidental one at hosting review. Checklist basis: B/1 (RequirePOST on
  state-changing `do*`) and B/2 (permission-then-verb ordering) — both satisfied; this is a
  method-permissiveness note, not a control gap.
  Direction of fix: either accept the documented behaviour as-is, or give read-only
  sections their own `GET, HEAD`-only verb guard distinct from `HttpVerbs.ALLOW` (which is meant
  for subtrees that do carry POST endpoints as children).
  Regression test (Given/When/Then): Given a user with `ViewHistory`, when they `POST` to
  `/batch-control/history/` with no body, then the response is 405 with an `Allow: GET, HEAD`
  header (currently 200).

- [S-02] `src/main/java/io/jenkins/plugins/batchcontrol/ops/ConfigureWithoutGrantMonitor.java:181-202`
  (`delegatePermissionSids`) — reflection for `getAllPermissionEntries()` (matrix-auth 3.0+) is
  the only source of delegate-granted sids now that the old `getAllSIDs()`/
  `getGrantedPermissionEntries()` dual lookup was removed. If matrix-auth is absent or older than
  3.0 (no such method), the call falls into the existing `catch (ReflectiveOperationException |
  RuntimeException)` and silently contributes zero sids; `candidateSids()` still scans every
  `User.getAll()` account, so the gap is narrow (a sid granted `Item/Configure` directly in an
  old/absent matrix-auth that has never logged in as a Jenkins `User`). This is an
  **advisory `AdministrativeMonitor`**, not an enforcement point (`BatchControlAuthorizationStrategy`
  is the actual gate and is unchanged by this diff), so the gap cannot itself grant a permission —
  it can only make the "you have a standing-permission bypass" warning silently incomplete on an
  old/absent matrix-auth. Checklist basis: B item on delegating-strategy safe defaults (item 8 of
  the review procedure) — read as "informational, not an enforcement bypass."
  Direction of fix: log at INFO (not just FINE) when `getAllPermissionEntries()` is entirely
  absent, so an admin running old matrix-auth learns the warning is degraded rather than trusting
  a silently narrower scan.
  Regression test (Given/When/Then): Given a delegate whose class exposes neither
  `getAllPermissionEntries()` nor `getAllSIDs()`, when `ConfigureWithoutGrantMonitor.isActivated()`
  runs with a non-admin sid known only to that delegate (not to `User.getAll()`) holding
  `Item/Configure`, then the monitor stays quiet (documents the known gap; currently unconfirmed
  by any test in `src/test`, since matrix-auth 3.x is on the test classpath).

## Checked and found to be fine
- do* RequirePOST + permission-check-first (checklist B/1, B/2): every state-changing endpoint
  touched or left behind by this diff (`RequestItem.doApprove/doReject/doCancel/doChangeApprover`,
  `GrantRequestItem.doApprove/doReject/doCancel`, `GrantsSection.doCreate`,
  `IncidentItem.doAcknowledge/doResolve/doComment/doRerun`, `ActiveGrantsSection.Item.doRevoke`,
  `JobRequestAction.doSubmit`) keeps `@RequirePOST` and a `Jenkins.get().checkPermission(...)`
  (or `job.checkPermission`) as the literal first statement. No new `do*` was added without this
  pattern; the only methods removed (`doIndex` on RequestsSection/RequestItem/
  GrantRequestItem/HistorySection) were read-only GET views, not state changes.
- Absent-not-refused routing (SPEC item 2, #31): `BatchControlRootAction.getUrlName()` and
  `JobRequestAction.getUrlName()` return `null` for a user without any/the required permission;
  Jenkins core's action dispatch (`Jenkins.getDynamic`/`Actionable`, confirmed against
  jenkins-core-2.568.3 bytecode) keys off `Action.getUrlName()`, so a null name makes the whole
  subtree unroutable (404), never merely a 403 disguised as absence. Verified green:
  `ActionVisibilityTest`, `DiscoverOnlyRequestScreenTest`, `SecurityRegressionTest` (10/10),
  `JobRunSidebarEntryTest`, `JobGrantSidebarEntryTest`.
- CSV/JSON exports and detail pages are not gated by `l:layout permissions=` alone (checklist
  B item 2 on the review procedure): `RequestsSection`/`GrantsSection`/`ChangesSection`/
  `DashboardSection`/`IncidentsSection`/`HistorySection` all implement `StaplerProxy` and call
  `Jenkins.get().checkAnyPermission(SectionAccess.xxx())` as the first statement of `getTarget()`,
  which Stapler evaluates before dispatching to any child object — including `HistorySection`'s
  `doSummary`/`doDynamic` (CSV) and every section's `getDynamic(id)` detail item. The `permissions=`
  attribute on `l:layout` (`Functions.checkAnyPermission`, confirmed against jenkins-core bytecode)
  is therefore a redundant second layer, never the sole check. Verified green: `HistoryWebTest`,
  `GrantWebTest`, `RunRequestWebTest`.
- `GrantsSection` change-control-off gate runs its permission check
  (`checkAnyPermission(SectionAccess.grants())`) before the `isChangeControlEnabled()` check, so a
  caller who lacks Grant permissions learns nothing about the switch state (no info-disclosure
  ordering bug).
- `ui/SectionAccess` and `ui/HttpVerbs` permission sets (checklist B/2, review item 3): the five
  permission sets (`requests`, `grants`, `history`, `anyPermission`) are fixed, non-empty literal
  arrays reused identically by `getTarget()`, `getViewPermissions()` (l:layout) and the link
  predicates, so there is no drift between "what is checked" and "what is linked/rendered."
  `Functions.checkAnyPermission`/`AccessControlled.checkAnyPermission` only no-op on a null or
  empty `Permission[]` (confirmed by bytecode); none of `SectionAccess`'s getters can return
  such an array. `HttpVerbs.refuseUnsupported()` only fails open when
  `Stapler.getCurrentRequest2()` is null, unreachable during real HTTP dispatch.
- No job-scoped permission checked globally (review item 1): all five Batch Control permissions
  are declared `PermissionScope.JENKINS`-only in `BatchControlPermissions.java` (unchanged by this
  diff), so `Jenkins.get().hasPermission/checkPermission(...)` in `JobRequestAction` is the correct
  scope, not a global check masquerading as a job check.
- Jelly escaping (checklist B, review item 5): grepped the full diff for `escapeXml="false"` and
  `<j:out` — none introduced; every touched `.jelly` file keeps `escape-by-default='true'`. The
  new conditional grant/request/incident-id links fall back to interpolated (still-escaped) plain
  text when the viewer may not follow the link, not to raw HTML.
- `BatchControlPermissions.GROUP` pinning to a literal `"BatchControl"` group id (D-41) does not
  weaken permission identity: matrix entries key on `Permission.getId()`, unaffected by the group
  id change.
- Build health: `mvn -q -o compile` clean; `target/spotbugsXml.xml` regenerated
  (`mvn -q -o spotbugs:spotbugs`), 0 `<BugInstance>` entries (checklist B: "SpotBugs 경고 0").
  Targeted test run green: `ActionVisibilityTest` (3), `DiscoverOnlyRequestScreenTest` (7),
  `SecurityRegressionTest` (10), `XssEscapingTest` (1), `JobRunSidebarEntryTest` (3),
  `JobGrantSidebarEntryTest` (7), `GrantWebTest` (7), `RunRequestWebTest` (9),
  `HistoryWebTest` (8), `GrantMonitorsTest` (2) — 57/57, 0 failures/errors.

## Unconfirmed
- Full `mvn clean verify` (entire test suite) was not run to completion — only the tests touching
  this diff's classes were run individually (all green, see above). No sign of a broken test
  elsewhere, but a full-suite run was not performed.
- `ConfigureWithoutGrantMonitor` behaviour with matrix-auth **entirely absent** from the runtime
  classpath (as opposed to present-but-old): could not be exercised without altering the plugin's
  test dependencies; see LOW S-02.
- Behaviour of `l:layout permissions="${it.viewPermissions}"` on `JobRequestAction` under an
  exotic custom `AuthorizationStrategy` whose per-job ACL for a `PermissionScope.JENKINS`-only
  permission diverges from the root ACL: not reachable with the matrix/role strategies in the
  test dependency set, and the real enforcement (`getUrlName()`, `doSubmit`) does not depend on
  it, so this is defense-in-depth only.

## Request
None — no change to a path outside `docs/reports/security-*.md` is needed for this diff.
