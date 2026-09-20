# Spec Review S3 — Change Control (SPEC items 8, 9)

Reviewed: implementation commit `e3bbc3f`, tests commit `f1f4793`, branch `phase-3-impl`.
Basis: docs/SPEC.md items 8/9 + §3/§5/§6, docs/ARCHITECTURE.md §2/§4/§5/§7, docs/DECISIONS.md D-16..D-23 / P-01..P-06, docs/TEST-MATRIX.md S3 rows and notes 17–21.
Test verdict provided by the orchestrator: 119/119 green, SpotBugs 0 (not re-run here; a full `mvn clean verify` was in progress).

## Verdict: PASS WITH NOTES

No BLOCKER. One MAJOR (an ARCHITECTURE §4 delegation gap on a new core overload). Everything else is MINOR or an accepted, documented deviation.

## Acceptance-criterion coverage

### SPEC item 8 (JIT grants)

| Criterion | Implementation | Test |
|---|---|---|
| Approval makes the requester hold Item/Create·Configure·Delete in scope immediately | `GrantRequestService.approve` → `GrantService.register` in one critical section (`policy/GrantRequestService.java:156-186`); `GrantAwareACL.hasPermission2` | T-08-01 (GrantServiceTest), `approvePostCreatesActiveGrant` (GrantWebTest) |
| No permission outside the scope | `GrantScope.includes` (segment-boundary, `model/GrantScope.java:47-55`) — single source, reused by ACL, D-17 check, delete veto, record linkage | T-08-02, T-08-11, T-SEC-03 (store/PathCodecTest `t_sec_03`) |
| Denied from first check past expiry, no timers | `Grant.isActiveAt` strict `< expiresAt` (`model/Grant.java:102-107`); no scheduler anywhere; grep confirms no Timer usage | T-08-03, T-RT-06 |
| Restart: kept before expiry, gone after | `GrantService.grants()` cache keyed to the Jenkins instance, reloaded from `grants/<id>.xml` (`security/GrantService.java:152-163`) | T-08-04, T-08-07 (GrantRestartTest, JenkinsSessionRule split per matrix note 21) |
| Manage revoke, immediate + recorded | `GrantService.revoke` — MANAGE checked in the service too, `ChangeRecord(GRANT_REVOKE)` with grantId (`security/GrantService.java:125-147`) | T-08-05 (service + web), `revokePostWithoutManageIs403`, T-SEC-06 remainder |
| Monitor: direct change permission without grant, change control on | `ops/ConfigureWithoutGrantMonitor.isActivated` (off-switch → false; non-wrapper strategy → true per ARCHITECTURE §7; delegate scan skips admins) | T-08-06 (activation + negative states, note 17) |
| Monitor: Role Strategy notice | `ops/RoleStrategyNoticeMonitor` (detects plain and wrapped Role Strategy, by class name only — no compile dep) | T-08-08 |
| `grantDurationOptions` / `maxGrantMinutes` | presets rendered from global config; service enforces `(0, maxGrantMinutes]` (`policy/GrantRequestService.java:96-104`) | T-08-09 |
| Ungranted action stays denied | ACL only adds the granted actions; delete veto (`listener/DeleteVetoListener.java`) additionally requires a DELETE grant under change control | T-08-10 (two tests, incl. delegate-held Item/Delete) |
| FOLDER scope: inside works, outside denied | `GrantScope.includes` + folder ACL = `getACL(AbstractItem)` (folders are AbstractItems) | T-08-11 |
| Pending grant request expiry | `GrantRequestService.expireOverduePending`, wired into `ExpiryPeriodicWork.doRun`; approve() self-expires late requests | T-08-12 |
| RequestGrant enforced on creation | HTTP layer only (`action/GrantsSection.doCreate`), P-06 registered | T-08-13 (uses `grants/create`, satisfying matrix note 18) |
| D-17 auto approvalRequired | `ItemChangeListener.forceApprovalRequiredIfCreatedUnderGrant` | T-RT-05 (GrantWindowAbuseTest, note 19 scope) |
| Delegating strategy, admin-selected | `security/BatchControlAuthorizationStrategy` + `config.jelly` dropdown excluding self-nesting | wrapper used in every S3 integration test |

### SPEC item 9 (change records)

| Criterion | Implementation | Test |
|---|---|---|
| UI / REST / CLI / DSL all recorded | `ConfigSnapshotListener` on `SaveableListener.onChange` — the one hook all four paths cross (deviation (a), judged below) | T-09-01/02/05/06 |
| CONFIGURE unified diff, secrets masked | `SecretMasker.mask` applied to BOTH sides before `UnifiedDiff.diff` (`listener/ConfigSnapshotListener.java:77-86`); LCS diff size-guarded | T-09-04 |
| grantId linked when active grant, else null | `ChangeRecording.activeGrantIdFor` on all record paths (CREATE/CONFIGURE/DELETE/RENAME/MOVE) | T-09-03, T-09-09 |
| Recording active when ANY switch on, silent when both off | `ChangeRecording.isActive` (D-13); both listeners return early | T-09-10, T-09-11 |
| CREATE/DELETE/RENAME/MOVE with user + time | `ItemChangeListener`; MOVE = location change whose parent changed | T-09-07, T-09-08 |
| Storage format | `changes/YYYY-MM.jsonl` diff-free + `changes/diff/<id>.patch` written first; snapshots `snapshots/<encoded>.xml` latest-only; matches ARCHITECTURE §5 exactly | ChangeRecordTest, T-RT-20 (baseline chaining under 30 rapid saves) |

T-SEC-07 remains pending P-03 by design (noted, not counted against S3).

## Delegating strategy — overload audit

Overridable surface of `hudson.security.AuthorizationStrategy` in jenkins-core 2.568.3 (verified with `javap`): `getRootACL()`, `getACL(AbstractProject)`, `getACL(Job)`, `getACL(View)`, `getACL(AbstractItem)`, `getACL(User)`, `getACL(Computer)`, `getACL(IComputer)`, `getACL(Cloud)`, `getACL(Node)`, `getGroups()`.

- Overridden and delegated: root, AbstractItem, Job, View, User, Computer, Cloud, Node, getGroups. Non-item objects wrap with `itemFullName=null` so grants can never apply there.
- `getACL(AbstractProject)`: not overridden, but the core default `invokevirtual getACL(Job)` (verified in bytecode) dispatches into the wrapper's Job override — safe.
- `getACL(IComputer)`: **not overridden** — see MAJOR 1.
- `GrantAwareACL` intercepts only `Item.CREATE/CONFIGURE/DELETE` via `GrantAction.fromPermission` (identity comparison, null for everything else); anonymous excluded; every miss goes verbatim to the delegate ACL → identical-to-delegate with no active grant. Null delegate → `denyAll()` (deny all but SYSTEM2) on every path, including the descriptor-less form case. Verified.
- Root-level CREATE: `getRootACL()` wraps with scope `""`; a FOLDER grant on `""` would confer root Item/Create. But see MINOR 2 — this path is unreachable from the UI and partially inconsistent.
- `ACL.SYSTEM2`: no new `ACL.as2(SYSTEM2)` switch sites; the only elevation remains the S2 submission site in `RunRequestService` (grep-verified). `GrantAwareACL` merely compares against SYSTEM2.

## Grant lifecycle

- Expiry is time comparison only (`isActiveAt`), restart-safe via instance-keyed cache reload; revoke is immediate (cache replace under the same monitor) and leaves `GRANT_REVOKE` with grantId. Verified.
- Scope boundary logic exists exactly once (`GrantScope.includes`); no divergent `startsWith` prefix matching anywhere else in src/main (grep-verified; the only other `startsWith` is PathCodec's path-escape guard).

## D-17 recursion guard

Sound. `onCreated` → property add runs inside `beginSuppression()/endSuppression()` (thread-local), so the `save()` fired by `removeProperty`/`addProperty` is invisible to `ConfigSnapshotListener`; the snapshot seeded afterwards already contains the property, so the next real CONFIGURE diffs correctly. Gated on `isRunControlEnabled()` (correct per D-17) and on an active grant covering the created item (any action — broader than CREATE-only, which is the safe direction). `addProperty` does not re-fire `onCreated`, so no ItemListener recursion either.

## core-dev's documented deviations — judgments

- **(a) CONFIGURE via SaveableListener instead of ItemListener.onUpdated** — ACCEPTED. ARCHITECTURE §2 itself maps "설정 diff" to `SaveableListener#onChange`; `onUpdated` fires only on `updateByXml` (REST/CLI) and would MISS the UI form save, so SaveableListener is the only hook that satisfies "regardless of the path". Merging the CONFIGURE record with the diff record avoids double records. No gap found.
- **(b) Grant.id == grantRequestId** — ACCEPTED. Both SPEC §3 fields exist; 1:1 is guaranteed by approve() being the only creation path inside one lock. Side benefit: `ChangesSection` links grantId straight to the request detail. Caveat: any future "re-grant from the same request" feature breaks the invariant — keep the two fields (they are kept).
- **(c) RequestGrant enforced HTTP-layer only** — ACCEPTED PENDING P-06. T-08-13 proves the HTTP 403; the service is `@Restricted(NoExternalUse)`. Asymmetry with `RunRequestService.create` is real; the P-06 proposal is the right vehicle. My recommendation for the human: option (b) (check in the service too) is cheap defense-in-depth once the test contract allows it.
- **(d) Secret-only changes stored as masked note** — ACCEPTED. With both sides masked identically a diff would be empty; storing the explanatory note in the diff field keeps the record non-silent and leaks nothing. Degenerate but defensible reading of "unified diff is stored (secrets masked)".
- **(e) Folder-rename children produce MOVE records** — ACCEPTED. `onLocationChanged` fires per descendant with a changed parent path; recording each child's old→new full name is audit-complete and truthful (their location did change). The folder itself gets RENAME, not MOVE (parent unchanged) — consistent. Suggest one line in README/user docs so auditors are not surprised.

## Monitors

- Activation conditions match SPEC 8 + ARCHITECTURE §7 (`ConfigureWithoutGrantMonitor`: change control on AND (non-wrapper strategy OR non-admin with a standing change permission); `RoleStrategyNoticeMonitor`: Role Strategy plain or as delegate, no switch condition — which matches the SPEC criterion's letter; see MINOR 5).
- Reflective matrix sid enumeration: no security concern. It only calls `getAllSIDs()`/`getGrantedPermissionEntries()` read-only, builds throwaway `UsernamePasswordAuthenticationToken`s that never enter a SecurityContext, checks against the DELEGATE's ACL only, swallows all failures, and is capped at 100 candidates. It cannot grant, cache, or persist anything. (Performance caveat: MINOR 6.)

## Boundary / ownership

- Actions are state-logic-free: `GrantsSection` parses input and delegates to `GrantRequestService`/`GrantService`; `GrantRequestItem`/`ActiveGrantsSection` are pure routing + permission checks. All `do*` are `@RequirePOST` with the permission check first (doCancel checks authenticated + service enforces requester-or-Manage — same pattern as S2). Reads of `FileStore` from `ChangesSection` are within the ARCHITECTURE §3 boundary (actions may call store's public methods; no writes).
- `src/main/webapp/help/**` (6 grant-form help files) is a NEW path outside the CLAUDE.md ownership table. The orchestrator assigned it to ui-dev — recording that here; recommend the human adds a row to the table (human-owned edit).
- `docs/DECISIONS.md`: only the P-06 append to the proposals section (the sanctioned mechanism, consistent with P-02..P-05 practice).
- Commit `e3bbc3f` mixes core-dev and ui-dev paths in one commit (informational; content per path respects ownership).

## BLOCKER (spec violation, must fix)

None.

## MAJOR (missing acceptance criterion / architecture requirement)

1. **[security/BatchControlAuthorizationStrategy.java:64-111] `getACL(jenkins.model.IComputer)` is not delegated.** ARCHITECTURE §4 requires ALL `getACL` overloads to delegate, for exactly this failure mode: the core default routes a non-`Computer` `IComputer` to the wrapper's `getRootACL()` instead of the delegate's own `getACL(IComputer)` override (verified against jenkins-core 2.568.3 bytecode; `Computer` instances are safe — the default dispatches into the overridden `getACL(Computer)`). Practical impact today is low (mainstream strategies don't override it yet), but it is precisely the "missing one silently falls back" case the PoC documented. → Request: core-dev add `@Override getACL(IComputer)` delegating with `noScope(...)`, mirroring the other non-item overloads.

## MINOR (defaults, naming, docs)

2. **[model/GrantScope.java:54 + policy/GrantRequestService.java:136-138 + action/GrantsSection.java:160-163] Root-scope (`""`) FOLDER grant is inconsistent three ways.** The service explicitly blesses `""` as a valid FOLDER scope ("the Jenkins root"), and `getRootACL()` wires scope `""` for root-level CREATE — but (i) `includes("")` matches only the root itself, never root-level jobs (`"job".startsWith("/")` is false), so a `""` CONFIGURE/DELETE grant silently confers nothing; (ii) the HTTP form rejects an empty `scopeFullName`, so users cannot request it anyway; (iii) no test covers it. Not demanded by any SPEC criterion, so MINOR. → Request: core-dev either reject `""` in `checkScopeExists` too (making the trio consistent: no root grants in MVP) or fix `includes` for the empty-scope case; if kept, ui-dev needs a form affordance and test-author a row.
3. **Approver change is not possible on a PENDING GrantRequest.** SPEC §2 item 3 (common foundation) says the requester may change the approver before decision with history, but SPEC §3 gives `approverChanges` only to RunRequest, and TEST-MATRIX maps all item-3 rows to run requests. Implementation follows the §3 model (cancel + recreate is the workaround). SPEC-internal ambiguity → propose a DECISIONS entry rather than code; not counted as a violation.
4. **[resources/action/BatchControlRootAction/index.jelly:7,18] The Grants link/screens show regardless of the change-control switch.** ARCHITECTURE §4 deliberately makes the strategy (and thus grants) switch-independent, but SPEC item 1's "run control only → no change-control UI" criterion reads against an always-visible Grants section (T-01-04 only asserts job-page UI, so tests pass). Human judgment requested: either accept (grants are permission-gated and functional regardless of switch) or gate the link on `changeControlEnabled`.
5. **[ops/RoleStrategyNoticeMonitor.java:37-46] The notice activates with both switches off** (plugin installed + Role Strategy selected). This matches the SPEC 8 criterion's letter (no switch condition) and the test, but sits oddly with SPEC 1's "installation alone does nothing". Dismissible monitor, informational only — flagging for awareness, no change requested.
6. **[ops/ConfigureWithoutGrantMonitor.java:73-88,166-175] `isActivated()` may call `loadUserByUsername2` for up to 100 sids on every /manage render, uncached.** With an LDAP/AD realm this is a network round-trip per sid. → Request: core-dev consider a short-lived cache or moving the scan to a periodic computation.
7. **[listener/ChangeRecording.java:55-61] `endSuppression()` sets false instead of restoring the previous value** — non-reentrant if a second suppression site ever nests. Single call site today; robustness note only.
8. **T-08-06 covers the "user with direct change permission" branch but not the "strategy is not the wrapper" activation branch** (ARCHITECTURE §7). Implemented (`isActivated` returns true then), unverified by tests. → Optional row for test-author.

## Out-of-scope additions

- `customDurationMinutes` free-form duration on the request form: judged IN scope — SPEC 8 caps duration with `maxGrantMinutes` (240) above the largest preset (60), which presupposes non-preset durations; the service re-validates.
- `ChangesSection` month filter/pagination: minimal read UI needed to make item-9 records visible; the real filter/CSV screens remain item 12 (S4). No removal needed.
- Nothing else outside SPEC was found in `e3bbc3f`.

## Requests (for the orchestrator to route)

- core-dev: `security/BatchControlAuthorizationStrategy.java` — add `getACL(IComputer)` delegation (MAJOR 1).
- core-dev: `model/GrantScope.java` / `policy/GrantRequestService.java` — resolve the root-scope `""` inconsistency one way or the other (MINOR 2).
- core-dev: `ops/ConfigureWithoutGrantMonitor.java` — consider caching the sid scan (MINOR 6).
- human: DECISIONS proposal for grant-request approver change (MINOR 3); judgment on Grants UI visibility vs SPEC 1 (MINOR 4); add `src/main/webapp/**` ownership row to CLAUDE.md (ui-dev).
- test-author (optional): non-wrapper activation row for T-08-06 (MINOR 8).

Unverified items: none besides the explicitly deferred visual checks (Phase 5 e2e, matrix note 17) and T-SEC-07 (pending P-03).
