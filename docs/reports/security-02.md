# Security Review 02 (Phase 4 re-review)

Re-verification of every finding in `docs/reports/security-01.md` against the CURRENT code
(branch `handoff/phase-4-5-continuation`; `src/main` is identical to `main` @ 6f37f2d — the only
later commit, d71b642, touches `docs/HANDOFF.md` and `docs/STATUS.md` only; working tree clean),
plus a fresh-eyes pass over everything the hardening commit changed and over the surfaces the
first round flagged as adjacent risk.

Criteria unchanged: `docs/HOSTING-CHECKLIST.md` section B, `docs/SPEC.md` section 6, `ARCHITECTURE.md`
section 4, `DECISIONS.md` (D-18/D-19/D-20/D-23, P-03/P-06/P-09). Method unchanged: the 11-step
exhaustive grep procedure re-run over all 65 Java files, all 17 Jelly views and the changed diff;
no sampling. Authoritative build state (not re-run here): 155/155 tests pass; `target/spotbugsXml.xml`
contains 0 `<BugInstance>` (verified by reading the file).

## Summary: BLOCKER 0 / HIGH 0 / MEDIUM 0 / LOW 2

Counts are NEW findings only. Carried forward, not counted against the gate: 2 MEDIUM and 2 LOW
deferred by human decision (S-02, S-04, S-08, S-09 — restated at the end).

**Phase 4 gate (BLOCKER 0 / HIGH 0): MET.**

## Verdict per security-01 finding

| id | severity (01) | verdict | evidence |
|---|---|---|---|
| S-01 | HIGH | **FIXED** | `ui/Visibility.java:35-66`, `action/RequestsSection.java:92-95,144-160`, `action/GrantsSection.java:116-120,317-351` |
| S-02 | MEDIUM | **DEFERRED-BY-DECISION** | `action/IncidentItem.java:154,163,172` unchanged |
| S-03 | MEDIUM | **FIXED** | `policy/GrantRequestService.java:136-146` |
| S-04 | MEDIUM | **DEFERRED-BY-DECISION (P-06)** | `policy/GrantRequestService.java:87-90` unchanged |
| S-05 | MEDIUM | **FIXED** | `ops/ConfigureWithoutGrantMonitor.java:57-83,105-123`, `config/BatchControlGlobalConfiguration.java:93-95` |
| S-06 | LOW | **FIXED** | `action/IncidentItem.java:185-198` (+ service-side defence `ops/IncidentService.java:186-190`) |
| S-07 | LOW | **FIXED** | `action/JobRequestAction.java:47,62-69` |
| S-08 | LOW | **DEFERRED-BY-DECISION (README, Phase 7)** | `queue/ApprovalQueueDecisionHandler.java` unchanged |
| S-09 | LOW | **DEFERRED-BY-DECISION (README, Phase 7)** | `security/BatchControlAuthorizationStrategy.java:72-74` unchanged |
| S-10 | LOW | **FIXED** | all 65 files under `src/main/java` carry a top-level `@Restricted` (grep: 0 misses) |
| S-11 | LOW | **FIXED (code path); XStream path knowingly uncovered** | `security/BatchControlAuthorizationStrategy.java:54-62` |

## BLOCKER (hosting-rejection grounds)

None found.

## HIGH

None found. The single HIGH of round 01 (S-01) is closed — detail below.

## MEDIUM

No new MEDIUM. S-02 and S-04 remain open by human decision (restated below).

## LOW (new findings)

- **[S-12]** `action/HistorySection.java:192-203` (`requests.csv`), `HistorySection.java:159-190`
  (runs/incidents/changes CSV) and the corresponding history/dashboard/incident tables —
  **the P-09 visibility model is not applied to the history surfaces, and nothing says that is
  deliberate.** After the S-01 fix, `/batch-control/requests/` and `/batch-control/grants/`
  filter per object, but `/batch-control/history/?kind=requests` and
  `/batch-control/history/requests.csv` still emit EVERY run request — `jobFullName`,
  `parameters`, `reason`, `requester`, `approver`, `decisionComment` — to any holder of
  `BatchControl/ViewHistory`, with no `Item/Read` and no ownership filter (same for
  `DashboardSection` run records and `IncidentsSection`). The plugin therefore has two different
  visibility models over the same data.
  — Grounds: this is **not** a re-opening of S-01. SPEC item 12 defines `ViewHistory` as exactly
  the audit-read permission ("`ViewHistory` 권한이 없으면 모든 조회 화면과 CSV가 403"), and
  `Messages.properties:6` describes it as "View batch control history, dashboards and CSV
  exports" — so an administrator granting it is knowingly granting instance-wide audit read,
  unlike `BatchControl/Request`, which was the S-01 defect. Checklist B "정보 노출 방지" is
  satisfied by a documented boundary; what is missing is the documentation of the split.
  — Fix direction (human / spec-guardian, docs only — no code change recommended): add one
  sentence to DECISIONS P-09 and to SPEC item 12 stating that ViewHistory is an instance-wide
  audit permission deliberately exempt from P-09 per-object filtering, and repeat it in the
  README permission table so an operator does not grant ViewHistory expecting P-09 scoping.
  — Regression test: Given a user with only `ViewHistory` and no `Item/Read` on `secret-job`,
  When they GET `/batch-control/history/requests.csv`, Then the row for a `secret-job` request
  IS present (pinning the documented contract, so a future accidental filter change is caught).

- **[S-13]** `model/GrantScope.java:54-60`, `security/BatchControlAuthorizationStrategy.java:75-76`,
  `policy/GrantRequestService.java:136-146,` and the absence of a scope re-check in
  `GrantRequestService.approve` — **the S-03 fix left two stale contracts and one unvalidated
  path behind it.** (a) `GrantScope.includes` still contains the `fullName.isEmpty() → return
  true` instance-wide branch, and its own comment still claims such a scope "is only creatable
  through the service/API" — after S-03 it is creatable nowhere, so the comment is now false and
  the branch is live code guarding a state that must never exist. (b)
  `BatchControlAuthorizationStrategy.getRootACL` still advertises "a FOLDER-scope grant on `''`
  covers root-level Item/Create" — a capability that commit e9d926e deliberately added and the
  S-03 fix silently removed; root-level `Item/Create` can no longer be granted at all, and no
  DECISIONS entry records that functional change. (c) `approve()` does not re-run
  `checkScopeExists`, so a `GrantRequest` persisted by a pre-fix build with `FOLDER:""` would
  still approve into an instance-wide CREATE/CONFIGURE/DELETE grant. (c) is unreachable in
  practice — the plugin is unreleased, so no such stored data exists anywhere — which is why this
  is LOW and not MEDIUM.
  — Grounds: checklist B delegating-strategy row (a residual code path that would confer
  instance-wide change permissions must be impossible, not merely unreached); CLAUDE.md
  "코드와 스펙이 다르면 코드가 틀린 것" applies equally to comments that contradict the code.
  — Fix direction (core-dev): make `GrantScope.includes` return `false` for an empty FOLDER name
  (or assert non-empty in the `GrantScope` constructor) so the instance-wide branch cannot exist
  even for hand-edited store files; re-run `checkScopeExists(request.getScope())` at the top of
  `GrantRequestService.approve`; correct both stale comments. Route the removed root-scope
  capability to DECISIONS/SPEC (human) so the e9d926e behaviour change is on the record.
  — Regression test: Given a `GrantRequest` written directly to the store with scope
  `FOLDER:""`, When an approver approves it, Then the approval is rejected; and Given a `Grant`
  with scope `FOLDER:""`, Then `GrantAwareACL` returns false for an arbitrary job.

## Per-finding re-verification detail

### S-01 (HIGH) — FIXED. The disclosure is closed on every surface that was in scope.

The fix is a single predicate class, `ui/Visibility.java:35-66`, used by BOTH the list and the
detail path, so list and detail cannot diverge:

- run requests — `RequestsSection.allSorted:144-160` filters the list with
  `Visibility.canSeeRunRequest`; `RequestsSection.getDynamic:92-95` applies the identical
  predicate and returns `null`, which Stapler renders as a plain **404** — the same response as a
  nonexistent id, so no 404-vs-403 oracle exists. `RequestItem` has no other construction site
  (grep: `new RequestItem(` occurs once, at `RequestsSection.java:95`), so the four
  state-changing endpoints `doApprove`/`doReject`/`doCancel`/`doChangeApprover` are also
  unreachable for a request the caller may not see — the fix closed a write path, not only a read
  path.
- grant requests — same shape: `GrantsSection.allRequestsSorted:317-333` and
  `GrantsSection.getDynamic:116-120`; `new GrantRequestItem(` occurs once (`GrantsSection.java:119`).
- active grants — `GrantsSection.allActiveSorted:335-351` applies the own-only rule
  (`Visibility.canSeeGrant:57-59`: Manage, or `grant.getUser()` equals the caller).

Answering the specific leak channels asked about, for a holder of only `BatchControl/Request`:

- **list rows** — filtered before sorting and before paging.
- **paging totals / page counts** — `getTotal:127-129` and `isHasNext:135-137` read the FILTERED
  list (`allSorted()`), and `GrantsSection.getTotal:255-257`/`getActiveTotal:279-281` likewise;
  `RequestsSection/index.jelly:54` and `GrantsSection/index.jelly:54,108` render only those
  filtered counts. No "N requests" total leaks the hidden rows.
- **sort order** — the sort (`Comparator.comparing(createdAt).thenComparing(id).reversed()`) runs
  AFTER the filter, over the filtered list only; no gap in a sequence can be observed.
- **error-code differences** — non-visible detail is `null` → 404, identical to a nonexistent id
  and to a malformed id (`IllegalArgumentException` → `null`, `RequestsSection.java:89-91`). No
  403/404 distinction remains at the object level. The section gate 403 fires before any id is
  parsed, so it reveals nothing about ids either.
- **CSV / history** — deliberately unchanged, and this is the one residual: see the new **S-12**
  above. It is not reachable by a plain `Request` holder (every history URL is behind
  `HistorySection.getTarget:77-83` `checkPermission(VIEW_HISTORY)`), so the S-01 attacker profile
  — a `BatchControl/Request` holder — gains nothing from it. No `Request`-only path to request
  data survives.
- **id enumeration** — `ActiveGrantsSection.getDynamic:48-54` still returns an `Item` for any
  string, but that object exposes only a `Manage`-gated `@RequirePOST doRevoke` and a GET that
  redirects; it performs no store lookup, so it is not an existence oracle.

Workflow not over-restricted (checked against P-09, which documents this exact model as the
implemented default awaiting ratification):

- requester — `me.equals(request.getRequester())` (`Visibility.java:40`).
- designated approver — `me.equals(request.getApprover())`; the approver inbox therefore still
  works without `Item/Read` on the target job. Confirmed by the regression test
  `SecurityRegressionTest.java:126-129,177-180`.
- `Item/Read` holders — `Visibility.java:43-44`; this is what keeps team-mates able to see the
  requests on jobs they can read.
- Manage / Overall-Administer — `Visibility.isManager:62-66` short-circuits everything, so the
  MANAGE cancel/oversight path is intact (`SecurityRegressionTest.java:131-133,182-186`).
- Note, not a defect: an `APPROVE` holder who is NOT the designated approver and has no
  `Item/Read` no longer sees the request. That breaks nothing, because
  `ApprovalPolicy.checkDecision` has always required designated-approver identity — such a user
  could never have decided the request anyway.
- Note on breadth: the `Item/Read` clause means that in an installation where `Item/Read` is
  granted broadly (e.g. to `authenticated`), run-request reasons and parameter values remain
  widely visible. That is the ratified P-09 trade-off (the data stays inside the job's own read
  boundary), not a residual defect — but it is the reason P-09 needs human ratification before
  hosting, since it is the plugin's stated disclosure boundary.

### S-03 — FIXED

`GrantRequestService.checkScopeExists:136-146` now rejects `null`/empty for EVERY scope type
before the type switch; the old "the Jenkins root is a valid folder scope" early return is gone.
`GrantsSection.parseScope:171-174` still rejects an empty name at the HTTP layer (defence in
depth). Residual comment/dead-branch issues are the new **S-13**.

### S-05 — FIXED (with a bounded, acceptable staleness window)

`ConfigureWithoutGrantMonitor.isActivated:91-106` now performs only the three cheap pre-checks
(switch off → false, wrong strategy → true, null delegate → false) inline — none of them cached,
so a strategy swap or a switch flip can never be answered from cache — and delegates the
expensive part to `cachedScanResult:113-123`.

- **Thread safety: sound.** The cache is a single `private static volatile CachedScan`
  (`:78`) holding an immutable snapshot (`:65-76`, all fields `final`). Publication is a volatile
  write of a fully-constructed object; reads take one volatile read into a local
  (`:114`) and never re-read the field, so no torn or mixed state is observable. The only race is
  two threads computing concurrently and one overwriting the other — both compute the same
  function of the same delegate, so it is benign. No lock is held across the realm lookups, so the
  scan cannot serialise admin page renders behind each other.
- **Cannot serve a stale "safe" verdict after a security-relevant change** in the cases that
  matter: the key is delegate *identity* (`cached.delegate == delegate`, `:116`), and every
  route that edits permissions through Jenkins' security form data-binds a NEW delegate instance,
  which misses the key and recomputes immediately. A change-control toggle calls
  `invalidateCache()` explicitly (`BatchControlGlobalConfiguration.setChangeControlEnabled:93-95`),
  covering the "turn control on, monitor must warn now" case. The only stale window left is a
  delegate mutated IN PLACE (a strategy whose own management screens edit the live object), which
  can under-report for up to 5 minutes. Accepted: this monitor is a best-effort administrator
  *warning*, explicitly "never an enforcement point" (`:37-38`), no authorization decision reads
  the cache (grep: `cachedScan` appears only inside this class), and the strategy family that
  mutates in place — Role Strategy — is already declared unsupported by
  `RoleStrategyNoticeMonitor`. The javadoc states the window (`:40-46`).
- The static helper does hold the write; `invalidateCache()` is a plain `null` store with no
  `Jenkins.get()` call, so it is safe to invoke from a JCasC/XStream-time setter during startup.
- Minor, non-security: `config` now calls into `ops` by fully-qualified name
  (`BatchControlGlobalConfiguration.java:95`) — a layering note for core-dev, not a finding.

### S-06 — FIXED. The new `ACL.SYSTEM2` block is correctly scoped.

`IncidentItem.doRerun:185-198`, scrutinised against the CLAUDE.md rule
("`ACL.SYSTEM2`로 전환하는 코드는 이유를 주석으로 남기고, 전환 전에 권한 체크가 끝나 있어야 한다"):

- **Strictly an existence lookup.** The `try (ACLContext ignored = ACL.as2(ACL.SYSTEM2))` body
  (`:193-195`) contains exactly one statement, `Jenkins.get().getItemByFullName(...)`, assigning a
  local. Nothing is written, no service is called, nothing is rendered inside the block.
- **The permission decision is made as the real caller, never under SYSTEM2.**
  `job.checkPermission(Item.READ)` is at `:197`, outside the try-with-resources, so the
  `ACLContext` is already closed and the original `Authentication` restored;
  `checkPermission` resolves `Jenkins.getAuthentication2()` at call time. The plugin-level
  `checkPermission(REQUEST)` at `:185` precedes the switch, satisfying "권한 체크가 끝나 있어야
  한다". This is the only correct ordering — the comment at `:186-191` explains why the
  caller-scoped lookup could not be used (it returns `null` for an existing-but-unreadable job and
  would silently skip the very check being added).
- **Justification comment present** and specific (`:186-191`), matching the style of the only
  other switch site (`RunRequestService.java:589`). Grep step 3 confirms exactly these two
  `ACL.as2(ACL.SYSTEM2)` sites in `src/main`.
- **Existence leak: none beyond what the caller already has.** Reaching `doRerun` requires
  `ViewHistory` (the whole `/batch-control/incidents/**` subtree is behind
  `IncidentsSection.getTarget`), and the incident page already displays
  `incident.jobFullName`. The 403 (job exists, unreadable) vs `Failure` ("no longer exists",
  raised by `IncidentService.rerun:187-190`) distinction therefore discloses only whether a job
  name the caller is already shown still exists — no new information. Worth knowing, not worth a
  finding.
- Defence in depth confirmed: `IncidentService.rerun:186` performs its OWN caller-scoped
  `getItemByFullName`, so even an internal caller bypassing the action cannot rerun a job the
  authenticated user cannot read — it fails closed with "no longer exists".

### S-07 — FIXED

`JobRequestAction` now implements `StaplerProxy` (`:47`) and `getTarget:62-69` calls
`Jenkins.get().checkPermission(BatchControlPermissions.REQUEST)` before anything in the subtree
renders, so the approver user-id list (`getApproverOptions:110-112`, rendered at
`JobRequestAction/index.jelly:28-32`) is no longer reachable by a plain `Item/Read` user.
`doSubmit:124-125` keeps both checks (`Item.READ` on the job, then `REQUEST`) — the gate is
additive, not a replacement. Core still requires `Item/Read` to route to the job at all, so the
combination is `Item/Read AND BatchControl/Request`. No collateral: `getTarget` is consulted only
on URL traversal, the class is not `@ExportedBean` (so `/job/x/api/json` does not traverse it),
and the sidebar link continues to be hidden via `getIconFileName:74-80`.

### S-10 — FIXED (complete)

Grep sweep: every one of the 65 `.java` files under `src/main/java` has a `@Restricted` at column
0 immediately preceding its top-level type — 0 misses, verified by an exhaustive per-file check,
not sampling. The two classes round 01 said "may deliberately stay public" were annotated WITH a
javadoc justification rather than left open: `BatchControlPermissions.java:19-22`
("other plugins interact with these permissions through the authorization-strategy screens") and
`BatchControlAuthorizationStrategy.java:41` ("selected via the security form / JCasC, not a
code-level API"); same for the two `config` classes. Build-time enforcement (access-modifier
checker) is green in the authoritative `mvn clean verify`.

### S-11 — FIXED for the reachable path; the XStream path is acceptably uncovered

`BatchControlAuthorizationStrategy` `@DataBoundConstructor:54-62` throws
`IllegalArgumentException` when the delegate is the wrapper type, covering the form-submission and
JCasC routes (JCasC data-binds through the same constructor). XStream deserialization of a
hand-edited `config.xml` bypasses the constructor and remains uncovered.

**Judged acceptable**, for three reasons: (i) writing `$JENKINS_HOME/config.xml` already requires
filesystem access to the controller, which is strictly above every permission this plugin defines
— no security boundary is crossed to reach it; (ii) the consequence is not privilege escalation —
a nested wrapper produces `GrantAwareACL(GrantAwareACL(realAcl))`, and since a grant can only ever
ADD `Item.CREATE/CONFIGURE/DELETE` from the same `GrantService` state, consulting it twice yields
the identical verdict; (iii) the delegate chain is finite, so no recursion results. The one
pathological variant — an XStream `reference` making the strategy its own delegate — would
`StackOverflowError` on every permission check, i.e. a self-inflicted denial of service by an
operator who already had root on the controller, recovered by editing the same file. Not worth
adding a `readResolve`; if core-dev wants belt-and-braces, `readResolve()` returning a
delegate-flattened instance is the cheap option.

### Regression tests — genuine, with one shallow spot

`src/test/java/io/jenkins/plugins/batchcontrol/SecurityRegressionTest.java` (7 methods as of this
review; an 8th, `t_sec_15_submitRunRequestWithoutRequestPermissionIs403:248-280`, is being added
concurrently by test-author and also looks sound — it asserts 403 plus "no request created, no
build started, next build number unchanged"). Assessment:

- **Pins the fixed behaviour, not shallow (6 of 7).** `s_01_runRequestVisibilityFollowsP09:89-134`
  and `s_01_grantVisibilityIsOwnAssignedOrManageOnly:142-187` drive real HTTP through
  `JenkinsRule.WebClient` under `MockAuthorizationStrategy`, and assert all four P-09 arms
  positively AND negatively: list filtering, absence of the confidential reason string, 404 (not
  403) on the foreign detail URL, visibility for `Item/Read`, for the designated approver without
  `Item/Read`, and for MANAGE — including the own-only active-grant rule. These would fail on any
  regression of `Visibility`. `s_06_rerunWithoutItemReadOnJobIs403:191-223` asserts the 403 plus
  the absence of side effects (no linked rerun id, no run request). `s_07:226-245` asserts 403 for
  `Item/Read`-only and 200 for a `Request` holder — pinning both directions, so an over-broad
  "gate everything" regression is caught too. `s_03_emptyScopeNameIsRejected:283-305` loops over
  BOTH scope types and additionally asserts the store stayed empty.
  `s_11_selfNestingWrapperStrategyIsRejected:309-315` pins the constructor guard.
- **Shallow: `s_05_monitorActivationConsistentAndInvalidatedOnToggle:323-352`.** It asserts that
  two back-to-back `isActivated()` calls agree and that a toggle invalidates — but a build with
  the cache entirely removed would pass every one of those assertions, because an uncached scan is
  also consistent and also re-evaluates after a toggle. The test pins correctness, not the caching
  that was the actual fix. The round-01 proposal (count `loadUserByUsername2` invocations on a
  mock realm across two calls) is the assertion that would pin it.
  — 요청 to test-author: strengthen `s_05` with an invocation-counting `SecurityRealm` so that
  removing the cache fails the test; add the S-13 scope tests listed above.

## Verified clean (per checklist item, with grounds)

Re-run of the 11-step procedure over the current tree. Items marked *(unchanged)* were verified in
round 01 and re-confirmed here as untouched by the hardening commit.

- **Step 1 — `@RequirePOST` + first-statement permission check on every state-changing `do*`.**
  Grep enumerated 29 `do*` methods (2 are `PeriodicWork.doRun`, not web). 19 `@RequirePOST`
  annotations across the 6 action classes cover all 14 state-changing endpoints — the 13 of round
  01 plus nothing new; `doRerun` gained a check, it did not lose one. The 13 read endpoints
  (`doIndex`/`doSummary`/`doDynamic`) still refuse non-GET/HEAD with 405 + `Allow`.
- **Step 1b — Stapler routing / `getDynamic` reachability (fresh pass).** All five `getDynamic`
  implementations were re-read. Three now resolve through a visibility predicate
  (`RequestsSection:82-96`, `GrantsSection:106-120`) or resolve nothing at all
  (`ActiveGrantsSection:48-54`). The `getTarget` gates fire before any `getDynamic`, `doIndex`,
  `doSummary` or `doDynamic` runs, because Stapler consults `StaplerProxy.getTarget` first — so
  `HistorySection.doDynamic`'s CSV routing (`:140-157`, exact-match allow-list of four literal
  paths, everything else 404) and `doSummary` (`:108-131`, `YearMonth.parse` → 400 on garbage)
  cannot be reached around the `VIEW_HISTORY` gate. `JobRequestAction` joined the proxy set (S-07).
- **Step 2 — Jelly escaping (D-18).** All 17 Jelly files begin with
  `<?jelly escape-by-default='true'?>` (per-file check, 0 misses); grep for `escapeXml="false"`,
  `<j:out`, `<st:out` in `src/main/resources`: 0 hits. The new visibility work added no raw output
  site. *(unchanged otherwise)*
- **Step 3 — `ACL.SYSTEM2` discipline.** Exactly two switch sites, both with justification
  comments and both with permission checks completed beforehand: `RunRequestService:589-590`
  *(unchanged)* and the new `IncidentItem:193` (analysed under S-06 above).
- **Step 4 — secrets (P-03, D-19).** Re-traced every `Secret`/`Password`/`getPlainText` hit: the
  set is identical to round 01 (`JobRequestAction.flatten:183-192` masks at the action layer,
  `IncidentService:247,274,294-295` masks parameters and log tail at capture,
  `ConfigSnapshotListener:78-83` masks both diff sides before diffing). The hardening commit
  introduced no new secret-carrying path; `ConfigureWithoutGrantMonitor.authenticate:221-230`
  still builds tokens with an empty credential and never installs a `SecurityContext`.
- **Step 5 — path handling.** Grep for `new File` / `Paths.get` / `.resolve(`: every hit is inside
  `FileStore` (fixed literal subdirectory names) or `PathCodec:112`. No user input reached a new
  path site. *(unchanged)*
- **Step 6 — CSV injection.** `CsvWriter.encode` still prefixes `= + - @` and applies RFC quoting;
  all four exports route every cell through it; filenames are constants. *(unchanged)*
- **Step 7 — XStream.** Models unchanged, still final POJOs of primitives/String/enum/epoch-millis
  over Jenkins-hardened `XStream2`. The one XStream-adjacent change is the S-11 constructor guard
  (analysed above). *(unchanged otherwise)*
- **Step 8 — delegating strategy.** All nine `getACL` overloads still delegate, `getGroups`
  delegates (`:139-141`), null delegate still fails safe to `GrantAwareACL.denyAll()`, the
  `GrantAction` whitelist still caps grants at `Item.CREATE/CONFIGURE/DELETE`. The constructor
  guard is the only behavioural change and it only ever *rejects*. Note the stale root-scope
  comment at `:75-76` → S-13.
- **Step 9 — information exposure / low-privilege view.** No `doFill*`/`doCheck*`/
  `@JavaScriptMethod`/`Api` endpoints exist (grep: 0 hits) *(unchanged)*. An Overall/Read-only user
  still sees only the static landing page (`BatchControlRootAction/index.jelly` renders links and
  no data; every section 403s at `getTarget`). Within-permission breadth for `Request`/
  `RequestGrant` holders is now closed (S-01); the remaining documented breadth is `ViewHistory`
  → S-12.
- **Step 10 — concurrency (D-20/D-23).** The transition services were not touched by the hardening
  commit; the single-`ReentrantLock` load→validate→transition→persist discipline, the
  requestId+job-bound single-consumption approval marker and the `queuedAt` ticket comparison are
  unchanged. The only new shared mutable state in the whole commit is the S-05 cache, analysed
  above and found sound.
- **Step 11 — SpotBugs.** `target/spotbugsXml.xml` (42,625 bytes, from the authoritative verify)
  contains 0 `<BugInstance>` — the new `Visibility` class, the static cache and the
  try-with-resources block introduced no `EI_EXPOSE`/`ST_WRITE_STATIC`/`OBL` finding.
- **CSRF.** Unchanged: every mutating form is `f:form method="post"` or
  `l:confirmationLink post="true"`; no custom AJAX; the new `getTarget` gates do not bypass
  `@RequirePOST`.
- **Global config safety.** `BatchControlGlobalConfiguration` still mutates only via core's
  `Overall/Administer`-gated `configSubmit`, still audits toggles as `CONFIG_TOGGLE`; the added
  `invalidateCache()` call performs no privileged action.

## Unverified (미확인)

- Runtime behaviour against a live `hpi:run` instance — this review is static; e2e-tester owns it.
  In particular the P-09 filtering has been verified through `JenkinsRule.WebClient` (test level)
  but not against a real browser session with a real security realm.
- `ConfigureWithoutGrantMonitor` cache behaviour against a REAL matrix-auth / Role Strategy
  installation: whether saving those strategies' management screens produces a new delegate
  instance (immediate recompute) or mutates in place (up to 5 minutes stale) is reasoned from the
  data-binding contract, not observed. This is what bounds the S-05 residual window.
- The reflective sid enumeration (`enumerateStrategySids`) against a real matrix-auth /
  Role Strategy install *(carried from round 01)*.
- Whether Jenkins core renders a `null` `getDynamic` result as 404 in every core version in the
  supported LTS line — asserted by `SecurityRegressionTest` on the build's core version only.
- `Messages_ko.properties` / localization (checklist section C, out of scope).
- `T-SEC-07` remains unwritten pending decision **P-03** (password-parameter fidelity) — carried
  forward, not a Phase 4 gate item.

## Deferred by human decision (restated, NOT counted against the gate)

- **S-02** (MEDIUM) — incident `acknowledge`/`resolve`/`comment` gated by the read-scoped
  `ViewHistory` permission (`IncidentItem.java:154,163,172`). Unchanged by decision; needs a
  DECISIONS entry blessing ViewHistory as the incident-handling permission (and a
  `Messages.properties:6` description update) or a stronger permission.
- **S-04 / P-06** (MEDIUM) — `BatchControl/RequestGrant` enforced at the HTTP layer only;
  `GrantRequestService.create:87-90` still carries the comment and no check. P-06 is still
  "awaiting human decision" in DECISIONS; hosting review will ask.
- **S-08** (LOW) — unclassified queue causes pass the approval gate by default; README
  known-limitation item for Phase 7.
- **S-09** (LOW) — null-delegate admin lockout and its `config.xml` recovery path; README item
  for Phase 7.

## Requests (요청)

- 요청: `docs/DECISIONS.md` — human: **ratify P-09** (it is implemented as the default and is now
  the plugin's stated disclosure boundary — hosting review will ask what it is); resolve **P-06**
  (S-04) and the S-02 incident-handling permission; record the root-scope capability removal that
  the S-03 fix caused (S-13b); add the one-sentence ViewHistory exemption from P-09 (S-12).
- 요청: `src/main/java/io/jenkins/plugins/batchcontrol/{model,policy,security}/**` — core-dev:
  S-13 — make `GrantScope.includes` return `false` for an empty FOLDER name (or reject it in the
  `GrantScope` constructor), re-validate the scope in `GrantRequestService.approve`, and correct
  the two stale comments (`GrantScope.java:54-60`,
  `BatchControlAuthorizationStrategy.java:75-76`). Optional: `readResolve` flattening for the
  S-11 XStream path.
- 요청: `src/test/**` — test-author: strengthen `s_05` with a realm-invocation-counting mock so the
  cache itself is pinned (today's assertions pass without a cache); add the S-13 tests (stored
  `FOLDER:""` request must not approve; `FOLDER:""` grant must not match an arbitrary job); add
  the S-12 contract test (a ViewHistory-only user DOES get the row in `requests.csv`) so the
  documented split is deliberate and tested.
- 요청: `README.md` — release-manager (Phase 7): the permission table must state that
  `ViewHistory` is instance-wide audit read and is NOT subject to the P-09 request/grant
  filtering (S-12), alongside the already-agreed S-08, S-09, the raw-config-snapshot note and the
  D-19/P-03 masking limitations.
