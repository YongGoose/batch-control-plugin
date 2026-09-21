# Security Review 01

Phase 4 pre-hosting self-review of `src/main` (branch `main`, 148/148 tests green, SpotBugs 0).
Criteria: docs/HOSTING-CHECKLIST.md section B, SPEC.md section 6, ARCHITECTURE.md section 4,
DECISIONS.md (D-18/D-19/D-20/D-23, P-03/P-06). Every checklist item was verified by exhaustive
grep + full read of all 66 Java files, all 17 Jelly views and the 6 webapp help files; no sampling.
Severity follows Jenkins SECURITY-* convention: unauthorized state change / secret exposure /
privilege escalation = BLOCKER, missing CSRF protection / information disclosure = HIGH,
CSV injection / missing escaping = MEDIUM.

## Summary: BLOCKER 0 / HIGH 1 / MEDIUM 4 / LOW 6

## BLOCKER (hosting-rejection grounds)

None found.

## HIGH

- **[S-01]** `action/RequestsSection.java:40-48`, `action/GrantsSection.java:62-71`,
  `action/RequestItem.java:113-123` — **Cross-boundary information disclosure through coarse
  section gates.** The whole `/batch-control/requests/**` subtree is gated only by
  `checkAnyPermission(REQUEST, APPROVE, MANAGE)`, and `/batch-control/grants/**` by
  `checkAnyPermission(REQUEST_GRANT, APPROVE, MANAGE)`. Any holder of `BatchControl/Request`
  (a permission meant for ordinary requesters) can therefore read EVERY user's run requests —
  job full names, string parameter values, reasons, requester/approver ids, decision comments —
  including requests targeting jobs on which the viewer holds no `Item/Read`. Likewise any
  `RequestGrant` holder can enumerate all grant requests and the full active-grant table (who
  holds which change permission, where, until when — recon data for targeting a permission
  window). Neither the list nor the detail view (`RequestItem`/`GrantRequestItem` have no
  per-object check beyond the section gate) applies an ownership or `Item/Read` filter.
  Password parameters are already masked (P-03), which caps the damage, but non-secret
  parameter values and reasons routinely carry business data.
  — Grounds: checklist B "정보 노출 방지" / role procedure step 9 (what does a low-privilege
  user see); SPEC item 12 only defines `ViewHistory` gating for history screens and is silent on
  request-screen scoping, so least privilege applies.
  — Fix direction (ui-dev, action layer only — no service change needed): in
  `RequestsSection`/`GrantsSection` list building and in `getDynamic`, show a request to (a) its
  requester, (b) its currently designated approver, (c) `APPROVE` or `MANAGE` holders; hide the
  active-grant table from plain `REQUEST_GRANT` holders (keep it for `APPROVE`/`MANAGE`).
  Alternatively (spec-guardian route): document in SPEC that request visibility is
  plugin-permission-wide by design; then this drops to a documented limitation.
  — Regression test: Given users `alice` (REQUEST only) and `bob` (REQUEST only) where `bob`
  created a request for job `secret-job` (alice has no Item/Read on it), When `alice` GETs
  `/batch-control/requests/` and `/batch-control/requests/<bob's id>/`, Then bob's request (and
  its parameters/reason) is not listed and the detail returns 404/403; When the designated
  approver GETs the same URLs, Then the request is visible.

## MEDIUM

- **[S-02]** `action/IncidentItem.java:146-168` (`doAcknowledge`:148, `doResolve`:157,
  `doComment`:166) — **State-changing incident endpoints are gated by the read-scoped
  `ViewHistory` permission.** `BatchControl/ViewHistory` is described as "View batch control
  history, dashboards and CSV exports" (Messages.properties:6), yet it authorizes the
  OPEN→ACKNOWLEDGED→RESOLVED workflow and comment writes. An auditor account given ViewHistory
  for read-only oversight can silently alter incident handling state — a permission-semantics
  mismatch reviewers flag as authorization-model weakness.
  — Grounds: checklist B "모든 do* 메서드 첫 부분에 checkPermission" (the check exists but
  against a permission whose documented scope is viewing); SPEC item 11 names no permission for
  incident handling, so the SPEC does not bless this choice.
  — Fix direction: gate acknowledge/resolve/comment on a stronger permission (e.g. `MANAGE`, or
  `REQUEST` as the "operator" role), or record a DECISIONS proposal to bless ViewHistory as the
  incident-handling permission and update its description text.
  — Regression test: Given a user with only `ViewHistory` (per the chosen model), When they POST
  `acknowledge` on an OPEN incident, Then the response is 403 and the incident stays OPEN (or,
  if option (b) is chosen, Then the permission description string includes incident handling).

- **[S-03]** `model/GrantScope.java:54-60`, `policy/GrantRequestService.java:136-138` —
  **Root-scope `FOLDER:""` grant covers every item in the instance and is undocumented.**
  `GrantScope.includes` returns `true` for every item name when the FOLDER scope full name is
  empty, and `checkScopeExists` explicitly accepts `""` ("the Jenkins root is a valid folder
  scope"). A single approved grant with scope `FOLDER:""` + CONFIGURE/DELETE therefore confers
  Item/Configure/Delete on EVERY job and folder, instance-wide. The HTTP form rejects an empty
  scope (`GrantsSection.parseScope`:160-163), so today it is reachable only through the
  @Restricted service API, but nothing in SPEC item 8 ("잡 또는 폴더" scope) or ARCHITECTURE
  section 4 defines this instance-wide semantics, no test covers it (grep of src/test: no
  `FOLDER, ""` fixture), and a future caller (JCasC, REST API in phase 2) could expose it.
  — Grounds: SPEC item 8 "지정 범위 밖 잡에는 권한이 생기지 않는다" — with `""` nothing is
  outside the scope, hollowing the acceptance criterion; checklist B delegating-strategy row.
  — Fix direction (core-dev): reject an empty FOLDER full name in `GrantRequestService.create`
  (make root scope impossible until a deliberate D-decision introduces it), or add the semantics
  to SPEC/README via a DECISIONS proposal. Rejecting is the conservative pre-hosting choice.
  — Regression test: Given change control on and the wrapper strategy active, When
  `GrantRequestService.create(new GrantScope(FOLDER, ""), [CONFIGURE], ...)` is called, Then it
  throws IllegalArgumentException; and Given (until fixed) an approved `FOLDER:""` grant, Then
  `GrantAwareACL` must NOT return true for an arbitrary job (currently it does — this asserts
  the fix).

- **[S-04]** `policy/GrantRequestService.java:88-90` — **`RequestGrant` permission enforced at
  the HTTP layer only (known issue P-06).** `GrantRequestService.create` deliberately skips the
  `BatchControl/RequestGrant` check (comment cites T-08-13), asymmetric with
  `RunRequestService.create:109` which checks `REQUEST` in-service. Today the only HTTP caller
  (`GrantsSection.doCreate:131`) checks it, so there is no live bypass, but the single
  defense-in-depth layer for the security-sensitive entry point of the JIT machinery is the
  action class. Any future internal caller (REST API item 14, JCasC bootstrap, another action)
  silently inherits a permission-free path to grant requests.
  — Grounds: checklist B "모든 do* 메서드 첫 부분에 checkPermission" spirit (the transition
  entry point is the service per ARCHITECTURE section 3 boundary rule); DECISIONS P-06 is still
  "awaiting human decision" — hosting review will ask for the resolved state.
  — Fix direction: resolve P-06 before hosting; prefer option (b) (check in the service,
  adjust the S3 tests to grant the permission), or option (a) with an explicit
  `@Restricted`-javadoc contract sentence "caller must have checked RequestGrant".
  — Regression test: Given an authenticated user without `RequestGrant`, When
  `GrantRequestService.create(...)` is invoked directly under their authentication, Then
  AccessDeniedException is thrown (option b), or Then the javadoc contract is documented and
  T-08-13 continues to pass (option a).

- **[S-05]** `ops/ConfigureWithoutGrantMonitor.java:58-88, 166-175` — **Unbounded synchronous
  SecurityRealm lookups on every admin page render.** `isActivated()` iterates up to 100
  candidate sids and calls `SecurityRealm.loadUserByUsername2(sid)` for each, plus a
  `delegate.getRootACL().hasPermission2` matrix probe per candidate. Jenkins core's
  `AdministrativeMonitorsDecorator` evaluates `isActivated()` of every monitor on (almost) every
  HTML page an administrator loads. With an LDAP/AD realm each `loadUserByUsername2` is a remote
  directory query: 100 sequential LDAP round-trips per admin page view — page-load latency,
  directory load, and a self-inflicted DoS lever (the monitor is on whenever change control is
  on). There is no caching and no time budget.
  — Grounds: prompt scrutiny area "Monitor code impersonating users — resource exhaustion";
  hosting reviewers flag per-request remote calls in `isActivated()`.
  — Fix direction (core-dev): compute the result in a `PeriodicWork`/on-demand with a TTL cache
  (e.g. recompute at most every 15 minutes or on strategy/config change) and have
  `isActivated()` return the cached flag; keep the reflective sid enumeration as-is.
  — Regression test: Given change control on and a delegate matrix strategy, When
  `isActivated()` is called twice in a row, Then `loadUserByUsername2` is invoked at most once
  per candidate across both calls (mock realm counts invocations).

## LOW

- **[S-06]** `action/IncidentItem.java:177-188` (`doRerun`), `policy/RunRequestService.java:105-110`
  — The rerun path never checks `Item/Read` on the target job, unlike the direct request path
  (`JobRequestAction.doSubmit:110` checks `job.checkPermission(Item.READ)` first). A
  ViewHistory+Request user can create run requests for jobs invisible to them. Low because the
  incident (job name, parameters) is already visible under ViewHistory and an approver still
  gates execution — but the asymmetry is unintentional. Fix: add `job.checkPermission(Item.READ)`
  in `doRerun` (or in `RunRequestService.create`). Test: Given a user with ViewHistory+Request
  but no Item/Read on the incident's job, When they POST `rerun`, Then 403 and no request exists.
- **[S-07]** `action/JobRequestAction.java:59-66` + `JobRequestAction/index.jelly:18-46` — The
  request form at `/job/<name>/batch-control/` renders for any `Item/Read` user without
  `BatchControl/Request`: it exposes the eligible approver user-id list (account enumeration;
  parameter definitions are already API-visible to Read users, so no new leak there), and offers
  a form whose submit will only 403. Fix: wrap the form/approver list in
  `hasPermission(REQUEST)` (the doSubmit re-check stays). Test: Given a user with Item/Read
  only, When they GET the page, Then no approver ids appear in the response body.
- **[S-08]** `queue/ApprovalQueueDecisionHandler.java:129-137` — Unclassified causes pass the
  approval gate by default (log-only). Third-party trigger plugins with their own Cause types
  (e.g. generic-webhook-trigger) can therefore start an approval-required job without approval.
  Consistent with the D-16 default-open philosophy (whoever can configure the job configures
  its triggers, and configuring is itself change-controlled), but it must be a documented known
  limitation, not an accident. Fix: add to README/known limitations (release-manager); optional
  phase-2: per-job `blockUnclassified` switch. Test: Given an approval-required job, When
  scheduled with a custom `Cause` subclass, Then it passes and a WARNING/INFO log line records
  the unclassified pass (asserting today's contract).
- **[S-09]** `security/BatchControlAuthorizationStrategy.java:57-63`,
  `security/GrantAwareACL.java:24,44-46` — The null-delegate safe default denies everything
  except SYSTEM, including `Overall/Administer` holders (no delegate means admins cannot be
  recognized). This fails safe (checklist intent) but is an admin lockout: recovery requires
  editing `config.xml` on disk. Fix: document the recovery path in README ("if the wrapper is
  saved without a delegate..."); code change not required. Test: Given the wrapper with null
  delegate, When any authenticated non-SYSTEM user is checked for any permission, Then denied
  (exists conceptually in tests; add the explicit admin-denied assertion if missing).
- **[S-10]** Missing `@Restricted(NoExternalUse.class)` on 20 non-API classes: all of
  `action/*` (e.g. `JobRequestAction.java:42`, `RequestsSection.java:31`), all of `ui/*`
  (`CsvWriter.java:16`, `RunLinks.java:12`, `ApproverOptions.java:19`, `Dates.java:9`,
  `FilterParser.java:26`), `config/BatchControlGlobalConfiguration.java:29`,
  `config/BatchControlJobProperty.java:20`, `queue/ApprovedCause.java:10`,
  `queue/ApprovedRunAction.java:16`, `security/BatchControlAuthorizationStrategy.java:39`,
  `security/BatchControlPermissions.java:18`. Checklist B: attach to everything that is not a
  deliberate public API. Stapler routing, form binding and XStream all work on restricted
  classes, so annotating is safe; `BatchControlPermissions` and the strategy may deliberately
  stay public — then say so in javadoc. Fix: add the annotation (ui-dev for action/ui, core-dev
  for the rest). Test: `mvn verify` access-modifier check stays green (build-time enforcement).
- **[S-11]** `security/BatchControlAuthorizationStrategy.java:45-48,149-157` — Self-nesting is
  prevented only in the UI dropdown (`getDelegateDescriptors`); JCasC or a hand-edited
  `config.xml` can configure the wrapper as its own delegate (or wrapper-in-wrapper). No
  infinite recursion results (each level just wraps an ACL) and no privilege change, but a
  nested wrapper double-consults grants and confuses the monitors. Fix: throw
  IllegalArgumentException in the `@DataBoundConstructor` when
  `delegate instanceof BatchControlAuthorizationStrategy`. Test: Given a wrapper delegate of
  the wrapper type, When constructed, Then IllegalArgumentException.

## Verified clean (per checklist item, with grounds)

- **@RequirePOST on every state-changing do\*** — grep step 1 enumerated all 29 `do*` methods
  (2 are PeriodicWork.doRun, not web). The 13 state-changing web endpoints
  (`RequestItem.doApprove/doReject/doCancel/doChangeApprover`,
  `GrantRequestItem.doApprove/doReject/doCancel`, `GrantsSection.doCreate`,
  `ActiveGrantsSection.Item.doRevoke`, `JobRequestAction.doSubmit`,
  `IncidentItem.doAcknowledge/doResolve/doComment/doRerun`) all carry `@RequirePOST` with a
  permission check (or explicit anonymous rejection for the two `doCancel`, whose
  requester-or-Manage rule the services enforce at `RunRequestService.cancel:213` /
  `GrantRequestService.cancel:217`) as the first statements. The 14 read `doIndex`/`doSummary`/
  `doDynamic` endpoints refuse non-GET/HEAD with 405 + `Allow` header.
- **Permission checks use the right scope** — all plugin permissions are `PermissionScope.JENKINS`
  and checked on `Jenkins.get()` (correct for global scope, P-02 documents the scope decision);
  the one job-scoped check (`JobRequestAction.doSubmit:110`) uses the job's ACL (`Item.READ`).
- **doFill\*/doCheck\* endpoints** — none exist (grep `doFill|doCheck|@JavaScriptMethod|Api`:
  0 hits); no form-validation information-disclosure surface.
- **Jelly escaping (D-18)** — all 17 Jelly files start with `<?jelly escape-by-default='true'?>`;
  grep step 2 found zero `escapeXml="false"`, `<j:out`, or `<st:out`. User-controlled text
  (reason, comments, parameter values, diff, logTail) renders only through default-escaped
  `${...}`; `IncidentItem/index.jelly:103` and `ChangesSection/index.jelly:65` render logTail/diff
  inside `<pre>` with default escaping and an explicit comment. Job links are built via
  `Util.rawEncode` per segment (`RunLinks.jobUrl:26-34`); no `javascript:`-capable href source.
  `JobRequestAction/index.jelly:37` sets the SECURITY-353 `escapeEntryTitleAndDescription` guard
  before including parameter-definition views.
- **ACL.SYSTEM2 discipline** — grep step 3: exactly one switch site,
  `RunRequestService.submitApproved:589-597`, with the reason comment; approver authority is
  verified by `ApprovalPolicy.checkDecision` (designated-approver identity + `APPROVE` +
  list membership + self-approval policy, `ApprovalPolicy.java:102-120`) BEFORE the APPROVED
  commit, and the queue gate re-validates the marker (`consumeMarker`) even for SYSTEM
  submissions. The startup-recovery resubmission (`recoverApprovedRequests`) reuses the same
  method for previously committed approvals — matches ARCHITECTURE and the CLAUDE.md rule.
  `ConfigureWithoutGrantMonitor.authenticate` builds tokens for ACL queries only and never
  installs a SecurityContext (no auth-state side effects; perf concern is S-05).
- **Secrets (P-03, D-19)** — grep step 4 traced every `Secret`/`Password`/`getPlainText` use.
  Request creation masks sensitive values at the action layer (`JobRequestAction.flatten:169-178`)
  so no plaintext ever reaches the store, CSV, JSON or Jelly; incident parameters and logTail are
  masked at capture (`IncidentService.maskedParameters:236-254`, `maskedLogTail:260-279`,
  including `Secret.getPlainText()` used only as a mask target); config diffs mask BOTH sides
  before diffing (`ConfigSnapshotListener.onChange:78-86`) with a masked-only-change note; the
  four CSVs emit only stored (already masked) values. `snapshots/<encoded>.xml` keeps the raw
  config.xml (secrets in it are Jenkins-encrypted at rest, identical protection to
  `$JENKINS_HOME/jobs/*/config.xml` on the same disk) — acceptable; README should mention it.
  Failure/log messages carry ids, job names and user ids, never secret values.
- **Path traversal** — grep step 5: all `resolve`/`new File` sites live in `FileStore` (plus the
  read-only `queue.xml` existence probe in `RunRequestService:457`). Every file name — including
  the user-influenced ids from `getDynamic(String)` — goes through
  `PathCodec.resolveUnder` (`PathCodec.java:104-117`: rejects separators, `.`, `..`, and any
  escape from the normalized base) and job names through `PathCodec.encode` (strict
  `[A-Za-z0-9_-]` + `%XX`, length-capped with SHA-256 shortening). `InvalidPathException` is an
  `IllegalArgumentException`, which the `getDynamic` callers catch and turn into 404.
- **CSV injection (D-18)** — `CsvWriter.encode:52-65` prefixes `= + - @` with `'` and applies
  RFC-style quoting; all four exports route every cell through it; download filenames are
  constants (no header injection).
- **XStream** — models are final, non-`Serializable` POJOs with primitive/String/enum/epoch-millis
  fields; `FileStore` uses Jenkins-hardened `XStream2` (blacklist active); no external XML is
  parsed anywhere (config.xml snapshots are handled as plain text) — no XXE surface.
- **CSRF** — every mutating form is `f:form method="post"` (crumb included by the form library)
  or `l:confirmationLink post="true"` (crumb-carrying); no custom AJAX exists.
- **Permission objects, not string comparison** — the only string comparisons on identities are
  the requester==caller / approver==caller business rules (`ApprovalPolicy:104,114`,
  `cancel` requester checks), which are identity rules, not permission grants; all permission
  logic uses `Permission` objects.
- **Delegating strategy (ARCHITECTURE section 4)** — a grant can confer ONLY
  `Item.CREATE/CONFIGURE/DELETE` (`GrantAction.fromPermission:37-48` whitelist consulted before
  `GrantService`); scope matching is exact for JOB and segment-boundary for FOLDER
  (`GrantScope.includes:47-62`; `team/batch` never matches `team/batch-other`); anonymous is
  excluded (`GrantAwareACL:54`); every `getACL` overload delegates, including the `IComputer`
  overload the PoC found; `getGroups()` delegates (`:125-127`); with no active grant behavior is
  byte-identical to the delegate; null delegate fails safe (deny-all-but-SYSTEM — see S-09 for
  the admin-lockout nuance); expiry is check-time clock comparison, first check at/after
  `expiresAt` denies (`Grant.isActiveAt:102-107`), revocation is Manage-gated in BOTH the action
  and the service (`GrantService.revoke:127`) and audited as `ChangeRecord(GRANT_REVOKE)`.
- **Concurrency (D-20/D-23)** — all three transition services serialize
  load→validate→transition→persist under one `ReentrantLock`; double-approve loses on the status
  re-read; approve/cancel races yield exactly one winner with no state mixing; the approval
  marker is bound to requestId AND job full name and consumed exactly once
  (`consumeMarker:267-305`, refusing unknown/wrong-status/already-ticketed/wrong-job/expired);
  check-at-submit expiry is inside the same critical section; the expiry work's queue-snapshot
  staleness window is closed by the `queuedAt` ticket comparison (`expireOverdue:344-376`);
  recovery dedups against the restored queue and existing builds under the queue lock.
- **Low-privilege view (checklist step 9)** — an Overall/Read-only user sees at `/batch-control/`
  only the static landing page (links, no data); every section 403s via `StaplerProxy.getTarget`
  before any `doIndex`/`getDynamic`/CSV/JSON runs (so `doDynamic` CSV routing and `doSummary`
  cannot be reached around the gate — Stapler consults `getTarget` first). Item/Discover-only
  users cannot reach `/job/X/batch-control/` (core requires Item/Read to route to the job).
  Request/incident/grant ids are not enumerable by low-privilege users (404 only behind the
  permission gate; ids also carry 31 bits of SecureRandom). Within-permission over-breadth is
  S-01.
- **SpotBugs** — `target/spotbugsXml.xml` from the last verify contains 0 `BugInstance`.
- **jenkins-security-scan workflow** — `.github/workflows/jenkins-security-scan.yml` exists.
- **Global config safety** — `BatchControlGlobalConfiguration` mutates only via core's
  Overall/Administer-gated `configSubmit`; numeric setters ignore non-positive values; switch
  toggles are audited (`CONFIG_TOGGLE`) at the setter so JCasC changes are recorded too.
- **DoS bounds on read endpoints** — history date span capped at 36 month buckets
  (`FilterParser.MAX_MONTHS`), dashboard window capped at 365 days, text filters capped at 256
  chars, diff generation capped (`UnifiedDiff` MAX_LCS_CELLS/MAX_DIFF_LINES), request sizes
  capped per D-22.

## Unverified (미확인)

- Runtime behavior against a live `hpi:run` instance (this review is static; e2e-tester owns that).
- The reflective matrix-auth sid enumeration (`ConfigureWithoutGrantMonitor.enumerateStrategySids`)
  against a real matrix-auth / Role Strategy installation.
- `Messages_ko.properties` / localization checklist items (section C, out of my scope).
- Behavior of Jenkins core's `Failure` error page escaping (relied on core; message strings do
  embed user input such as job names, which core escapes).

## Requests (요청)

- 요청: `src/main/java/io/jenkins/plugins/batchcontrol/action/**` — ui-dev: S-01 visibility
  filtering (requester/designated-approver/APPROVE/MANAGE), S-02 permission change on
  IncidentItem endpoints (after human decision), S-06 Item/Read check in doRerun, S-07 gate the
  JobRequestAction form/approver list on REQUEST, S-10 @Restricted on action/ui classes.
- 요청: `src/main/java/io/jenkins/plugins/batchcontrol/{policy,security,ops,queue,config}/**` —
  core-dev: S-03 reject empty FOLDER scope in GrantRequestService.create, S-04 resolve P-06
  (in-service RequestGrant check preferred), S-05 TTL cache for ConfigureWithoutGrantMonitor,
  S-10 @Restricted on remaining classes, S-11 constructor guard against self-nesting.
- 요청: `docs/DECISIONS.md` — human decision needed on: S-01 visibility model (filter vs
  document), S-02 incident-handling permission, S-03 root-scope semantics; P-03/P-06 remain
  open and hosting review will ask about them.
- 요청: `src/test/**` — test-author: the regression tests proposed per finding above
  (S-01 visibility, S-02 permission, S-03 empty-scope rejection + ACL non-grant, S-04 service
  check, S-05 realm-lookup count, S-06 rerun 403, S-07 approver-id absence, S-11 nesting guard).
- 요청: `README.md` — release-manager: document S-08 (unclassified trigger causes pass the
  gate), S-09 (null-delegate recovery path), the raw-config snapshot note, and the existing
  D-19/P-03 masking limitations in the known-limitations section.
