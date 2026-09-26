# Security Review 03 (pre-merge re-review of `handoff/phase-4-5-continuation` → `main`)

Full pre-merge review on the assumption that this branch is merged to `main` and the repository
is published in the `jenkinsci` organisation. Scope was set from
`git log --oneline origin/main..HEAD` (45 commits) and `git diff origin/main..HEAD --stat`
(135 files, +11340/−1417). The focus is everything that landed **after** `security-02` closed the
Phase 4 gate: the `Permission.impliedBy` walk in `GrantAwareACL` (P-11), the
`MARKER_REUSE_BLOCKED` audit record (D-30), the new-job approval default and the computed-child
exemption (D-31/D-32), the approval screen's recent-run table and its `?runs=` parameter, the
history screen's re-use warning block, and the JUnit 5 migration. Everything `security-01`/`-02`
already closed was re-checked only where this branch touched it; the items those reviews left
**open by human decision** are restated at the end, because merging to a public `main` publishes
them.

Criteria: `docs/HOSTING-CHECKLIST.md` section B, `docs/SPEC.md` sections 1/6/8/9/12,
`docs/ARCHITECTURE.md` section 4, `docs/DECISIONS.md` (D-16/D-17/D-18/D-23/D-25/D-30/D-31/D-32,
P-09/P-11/P-12/P-13), and the conventions in `CLAUDE.md`.

Method: the 11-step exhaustive grep procedure over all of `src/main` (62 Java files, 27 Jelly
views), plus — new in this round — verification of the plugin's claims about Jenkins core and
matrix-auth semantics **against the actual dependency sources** rather than against memory:
`jenkins-core-2.568.3-sources.jar` and `matrix-auth-3.3-sources.jar` were unpacked and read. A
full-history secret/PII scan was run over all 76 commits on all refs.

`mvn` was **not** run (another agent held the build; concurrent Maven on Windows deadlocks on file
locks), so `target/spotbugsXml.xml` could not be re-read. SpotBugs is therefore **unconfirmed** for
this round — see "Could not confirm".

## Summary: BLOCKER 0 / HIGH 1 / MEDIUM 2 / LOW 7

**Merge blockers: none.** One HIGH (S-14) should be resolved or explicitly re-documented before a
public release, but it is a pre-existing gate gap that this branch did not introduce and does not
prevent the merge itself.

---

## BLOCKER (grounds for hosting rejection)

None.

---

## HIGH

### [S-14] A build authentication token bypasses the run-control gate, and the documentation says it does not

- **File:line** — `src/main/java/io/jenkins/plugins/batchcontrol/queue/ApprovalQueueDecisionHandler.java:128-136`
  (the final `return true` for unclassified causes), read against
  `jenkins/model/ParameterizedJobMixIn.java:283-295` and `hudson/model/Cause.java:537` in
  jenkins-core 2.568.3.
- **The problem** — the gate classifies causes and falls open on anything it does not recognise.
  Core's `ParameterizedJobMixIn.getBuildCause` chooses the cause like this:

  ```java
  if (authToken != null && authToken.getToken() != null && req.getParameter("token") != null) {
      cause = new Cause.RemoteCause(req.getRemoteAddr(), causeText);
  } else {
      cause = new Cause.UserIdCause();
  }
  ```

  `Cause.RemoteCause extends Cause` — it is not a `UserIdCause`, not a `TimerTriggerCause`, not an
  `UpstreamCause` and not an `SCMTriggerCause`. So for a job that has **Trigger builds remotely
  (authentication token)** configured, `POST /job/<x>/build?token=<t>` reaches step 6 of
  `shouldSchedule` and is **scheduled**. `BuildAuthorizationToken.checkPermission`
  (`ParameterizedJobMixIn.java:244`) authorises that request on the token alone — no `Item/Build`,
  no authenticated user. The run is not blocked, no `Failure` is raised, and no audit record of a
  blocked attempt is written; only the generic "unclassified causes" `INFO` log line appears.
- **The basis** — checklist item B (authorisation bypass) and, more directly, two of the plugin's
  own contracts:
  - `docs/SPEC.md:77` — acceptance criterion: "실행 통제 on + `approvalRequired` 잡에서 다음 경로가
    모두 큐에 들어가지 않는다: 빌드 버튼, `/job/X/build` POST, …". This criterion is **not met** for
    the token variant of that exact URL.
  - `README.md:56-57` — "Blocked: "Build Now", the `build` and `buildWithParameters` REST
    endpoints, `jenkins-cli build`, and Pipeline Replay." That statement is false when a build
    token is set.

  The README does disclose the general policy three lines later ("Allowed: … any build whose cause
  the plugin does not recognise", "Nothing narrows the SCM or unrecognised-cause path"), so the
  fail-open principle is documented. What is not disclosed — and is actively contradicted — is that
  the single most sensitive instance of it sits on a URL the documentation names as blocked, and is
  reachable **without authentication**.
- **Why this matters in the realistic case** — the intended adoption path is "turn run control on
  for jobs that already exist". A pre-existing batch job with a build token (a common way to let an
  external scheduler start a Jenkins job) becomes `approvalRequired=true` and the operator believes
  it is gated, while the token path keeps working for anyone who has the token.
- **Reproduction / inference path** — static; not executed (no `mvn`, no live instance). Path:
  (1) job `x` has an auth token configured and `approvalRequired=true`, run control on;
  (2) `curl -X POST "$JENKINS/job/x/build?token=$T"`;
  (3) `BuildAuthorizationToken.checkPermission` passes on the token;
  (4) `getBuildCause` returns `CauseAction(RemoteCause)`;
  (5) `ApprovalQueueDecisionHandler.shouldSchedule` finds no `ApprovedRunAction`, no
  `ReplayCause`, no `UserIdCause`, no `TimerTriggerCause`, no `UpstreamCause`, no
  `SCMTriggerCause` → line 136 `return true`. The build runs.
- **Direction of the fix** — do not leave this to the catch-all. Either
  (a) classify `Cause.RemoteCause` explicitly and refuse it (quietly, like the other
  unattended paths — the caller has no error channel), which makes the SPEC criterion true as
  written; or
  (b) if the owner decides a build token is a deliberate automation channel that should pass, add
  it to the per-job policy the way `blockTimer`/`blockUpstream` work, and correct `SPEC.md:77`,
  `README.md:56-57` and `help-approvalRequired.html` to say so. Option (a) is the safer default: a
  build token means "anyone holding this string may start this job", which is the exact inverse of
  "this job only runs with approval".
  Independently of which is chosen: an unrecognised cause that is *refused* should also produce an
  audit record, otherwise the bypass is invisible in the history the plugin exists to provide.
- **Proposed regression test** (Given/When/Then)
  - *Given* run control is on, job `x` has `approvalRequired=true` and an auth token `T`,
  *When* an unauthenticated `POST /job/x/build?token=T` is made,
  *Then* the job is not queued and a `ChangeRecord` records the refused submission.
  - *Given* the same job with no auth token, *When* an authenticated `POST /job/x/build` is made,
  *Then* the existing `Failure`-with-guidance behaviour is unchanged (guard against a regression
  in the `UserIdCause` branch).

---

## MEDIUM

### [S-15] The change-control switch does not gate the permission-window machinery it is documented to gate

- **File:line** — `src/main/java/io/jenkins/plugins/batchcontrol/security/GrantAwareACL.java:59-67`
  (no switch consulted); `action/GrantsSection.java:68-77` (`getTarget()` checks permissions only);
  `policy/GrantRequestService.java:85-90` and `:168-200` (`create`/`approve` check no switch).
  The complete set of `isChangeControlEnabled()` call sites in `src/main` is three:
  `listener/DeleteVetoListener.java:26`, `ops/ConfigureWithoutGrantMonitor.java:92`, and
  `listener/ChangeRecording.java:32` (there only as an `OR` with run control).
- **The problem** — with **change control off**, a `BatchControl/RequestGrant` holder can still
  open `/batch-control/grants/`, submit a window, have an `Approve` holder approve it, and the
  resulting `Grant` still confers `Item/Create`, `Item/Configure` and `Item/Delete` through
  `GrantAwareACL`. Two consequences:
  1. `docs/SPEC.md:32` — acceptance criterion "실행 통제만 켜면 변경 통제 관련 UI·차단은 나타나지
     않는다(반대도 동일)" is **not met**: the change-control UI and its authorisation effect are
     both present with only run control on.
  2. `README.md:113` describes the switch as governing "Permission windows and delete vetoing". It
     governs only the delete veto (and the administrative monitor). Turning the switch **off** does
     not suspend or revoke windows that are already active — a user keeps plugin-conferred
     `Item/Configure` for up to `maxGrantMinutes` (default 240) afterwards. There is no kill
     switch; the only remedies are per-grant revocation by a `Manage` holder or changing the
     authorization strategy.
- **The basis** — checklist item B, plus `CLAUDE.md` code convention: "A new feature does not change
  existing Jenkins behaviour while the global switch is off."
- **Note on the counter-argument** — one can argue the *real* gate for change control is selecting
  the wrapping authorization strategy (`README.md:129` — "Change control does nothing until this is
  done"), which is a separate, deliberate admin action. That is a coherent design. But then the
  switch is mislabelled, and an administrator who reaches for the switch to stop the feature will
  find it does nothing. The mismatch has to be resolved in one direction or the other.
- **Reproduction / inference path** — static. Set `changeControlEnabled=false`,
  `runControlEnabled=true`, wrapping strategy selected with a Matrix delegate; grant a user
  `RequestGrant` and another `Approve`. Nothing in the code path from `doCreate` →
  `GrantRequestService.create` → `approve` → `GrantService.register` → `GrantAwareACL.grantConfers`
  reads `isChangeControlEnabled()`.
- **Direction of the fix** — owner's call between:
  (a) gate it — `GrantAwareACL.hasPermission2` returns the delegate's decision unchanged while
  change control is off, and `GrantsSection.getTarget()`/`GrantRequestService.create` refuse; this
  makes the switch a real kill switch and satisfies `SPEC.md:32`; or
  (b) document it — correct `README.md:113` to say the switch governs delete vetoing and the
  monitor, state that the authorization strategy is what enables windows, and state explicitly that
  switching off does not revoke active windows.
  (a) is preferable; a security feature's off switch should stop the feature.
- **Proposed regression test**
  - *Given* run control on, change control off, the wrapping strategy with a Matrix delegate, and
    an active `Grant(CONFIGURE)` for user `u` on job `j`, *When* `u` opens `j/configure`,
    *Then* access is denied (the delegate's answer).
  - *Given* an active grant, *When* an administrator turns change control off,
    *Then* the next permission check for that grant is denied.

### [S-16] `Item/Discover`-without-`Item/Read` turns the requests list and the approval screen into a 403 for users who are entitled to see them

- **File:line** — `src/main/java/io/jenkins/plugins/batchcontrol/ui/Visibility.java:43` and
  `action/RequestItem.java:345-347` (both comment "getItemByFullName is permission-aware: returns
  null when the job is gone or invisible"), against `jenkins/model/Jenkins.java:3040-3052` and
  `:3147` in jenkins-core 2.568.3.
- **The problem** — the comment is wrong for one case. Core:

  ```java
  @Override public TopLevelItem getItem(String name) throws AccessDeniedException {
      ...
      if (!item.hasPermission(Item.READ)) {
          if (item.hasPermission(Item.DISCOVER)) {
              throw new AccessDeniedException("Please login to access job " + name);
          }
          return null;
      }
  ```

  and `getItemByFullName` is declared `throws AccessDeniedException as per ItemGroup#getItem`. So
  for a caller holding `Item/Discover` but not `Item/Read` on the job, the lookup **throws** rather
  than returning `null`. Effects:
  - `RequestsSection.allSorted()` (`action/RequestsSection.java:144-160`) calls
    `Visibility.canSeeRunRequest` for **every** stored request. One request whose target job is
    discover-only for this caller makes the whole `/batch-control/requests/` page a 403 — the
    caller cannot even see their own requests.
  - `RequestItem.getRecentRuns()`/`getJobUrl()` are reached from the Jelly during rendering, so the
    detail page 403s for a requester or designated approver who is already entitled to it through
    the requester/approver branch of `canSeeRunRequest`.
  - A low-privilege actor can trigger it for others: a `Request` holder creating a request against
    a job that is discover-only for user B breaks B's list page.

  `Item/Discover` is enabled by default and granting it (often to `anonymous` or `authenticated`,
  so that a job URL redirects to login instead of 404) is a standard matrix-auth pattern, so the
  precondition is ordinary configuration, not an exotic one.
- **The basis** — checklist item B (availability of the plugin's own screens; also review procedure
  step 9, "what does a user with only `Item/Discover` see?"). It is **not** a disclosure: the path
  fails closed, and core already discloses existence to a `Discover` holder by design.
- **Provenance, stated honestly** — `ui/Visibility.java` is **unchanged** in this branch
  (`git diff origin/main..HEAD -- ui/Visibility.java` is empty), and `RequestItem.findJob()`
  pre-dates it. This is therefore a latent defect that `security-01`/`-02` did not catch, which the
  new recent-run table (`RequestItem.getRecentRuns`, `getExecutedRunUrl`) **amplifies** by adding
  two more call sites on a rendering path. The same latent throw exists at
  `action/IncidentItem.java:226`, `ops/IncidentService.java:186`,
  `policy/GrantRequestService.java:145,150` and `policy/RunRequestService.java:246,622`; in
  `GrantRequestService.approve` the practical effect is an approver seeing a 403 instead of the
  intended `IllegalArgumentException`.
- **Reproduction / inference path** — static, from core source. Grant user B `Item/Discover` but
  not `Item/Read` on job `j`; have another user create a run request for `j`; open
  `/batch-control/requests/` as B.
- **Direction of the fix** — introduce one helper (for example `Visibility.findVisibleJob(String)`)
  that wraps the lookup in `try { … } catch (AccessDeniedException e) { return null; }`, and route
  every caller in `action/**`, `ui/**` and `policy/**` through it. That restores the semantics every
  call site's comment already claims, and it keeps the disclosure boundary unchanged (a
  discover-only job is treated exactly like an invisible one).
- **Proposed regression test**
  - *Given* user B has `Item/Discover` but not `Item/Read` on job `j`, and a run request for `j`
    exists that B did not create, *When* B opens `/batch-control/requests/`,
    *Then* the page renders 200 and the row for `j` is absent.
  - *Given* B is the requester of a request for a job on which B has only `Discover`,
    *When* B opens the request detail page, *Then* it renders 200, the job name is plain text, and
    the recent-run table is absent.

---

## LOW

### [S-17] The `impliedBy` walk is faithful, but what a CONFIGURE grant actually confers is open-ended and invisible to the approver

- **File:line** — `src/main/java/io/jenkins/plugins/batchcontrol/security/GrantAwareACL.java:101-114`
  and its javadoc at `:69-100`.
- **Verified, not assumed** — I unpacked `matrix-auth-3.3-sources.jar` and
  `jenkins-core-2.568.3-sources.jar` and read the rules the javadoc cites:
  - `org/jenkinsci/plugins/matrixauth/AuthorizationContainer.java:301-325` —
    `for (; p != null; p = p.impliedBy) { if (!p.getEnabled()) continue; … }`. The walk is
    unconditional and a disabled link is skipped as a candidate without stopping the walk.
    `grantConfers` reproduces this **exactly**, including the `continue` on `!getEnabled()`.
  - `hudson/security/ACL.java:76-78` and `:106-108` — the `while (!p.enabled && p.impliedBy != null)`
    loop the javadoc says is *not* the decision rule is indeed only the message-naming loop after
    `hasPermission2` already returned false. The javadoc's reading is correct.
  - `hudson/security/SparseACL.java:91-99` — same unconditional walk (core's version does not skip
    disabled links; matrix-auth's does, and the plugin follows the stricter matrix-auth form, which
    is the right choice since matrix-auth is the delegate this is written for).
  - **The core claim checks out at this baseline.** Every `new Permission(...)` in jenkins-core
    2.568.3 that names `Item.CONFIGURE`/`CREATE`/`DELETE` as `impliedBy` is in `hudson/model/Item.java`,
    and the only one is `EXTENDED_READ → CONFIGURE` (`Item.java:299-305`). `Item.WIPEOUT` has
    `impliedBy = null` (`Item.java:321-327`); `Item.BUILD`/`CANCEL` use `Permission.UPDATE`;
    `hudson.scm.SCM.TAG` uses `Permission.CREATE`, the **generic root**, not `Item.CREATE`
    (`hudson/scm/SCM.java:761`) — I checked this one specifically because it is the obvious
    candidate, and it does not reach `Item.CONFIGURE`.
- **What remains, and it is the owner's question** — the walk answers whatever the *requested*
  permission's chain reaches, so the effective set is
  `{P : Item.CONFIGURE ∈ ancestors(P)} ∪ {… CREATE …} ∪ {… DELETE …}`. That set is
  **not bounded by this plugin**: any installed plugin may declare a permission with
  `impliedBy = Item.CONFIGURE`, and from that moment a CONFIGURE grant confers it, with no change to
  this plugin, no warning, and no entry in the audit record. Meanwhile the grant request form, the
  approval screen and the `Grant` record all say only `CONFIGURE`. So the approver cannot see the
  real permission set, and it can widen after the fact.
  The mirror-image risk — a plugin that mis-declares a dangerous permission as implied by
  `Item.CONFIGURE` — is **inherited, not introduced**: matrix-auth would confer it from a standing
  matrix entry the same way. The one real difference is provenance: a matrix entry is an
  administrator's standing decision, whereas this is a self-service window approved by a
  non-administrator.
- **Also checked and clean on this path** — anonymous is excluded before the walk
  (`GrantAwareACL.java:63`, `!ACL.isAnonymous2(a)`), so a grant recorded for `anonymous` can never
  take effect; `itemFullName == null` (views, users, nodes, clouds, computers and the Jenkins root)
  never reaches `grantConfers` at all, because `BatchControlAuthorizationStrategy.noScope()`
  constructs the wrapper with a null name (`BatchControlAuthorizationStrategy.java:71-78,150-152`) —
  that is the S-13 root-scope closure and it holds; `SYSTEM2` short-circuits to `true` as core's own
  ACLs do; a null delegate denies everything but SYSTEM. No upward path reaches
  `Jenkins.ADMINISTER` or any `BatchControl/*` permission: `MANAGE.impliedBy = Jenkins.ADMINISTER`
  and the other four are implied **by** `MANAGE` (`security/BatchControlPermissions.java:31-48`), so
  walking up from them never meets a grantable item permission — no self-escalation.
- **Direction of the fix** (documentation and visibility, not the algorithm — the algorithm is
  right):
  1. Correct the javadoc's absolute phrasing. `:96-97` says "in core only `Item.EXTENDED_READ`
     reaches `Item.CONFIGURE` this way" — true for 2.568.3, but it reads as a property of the
     design when it is a property of one baseline. Say "as of the 2.568.x baseline" and say that
     plugins may add links.
  2. `:93-96` says "the walk cannot widen a grant". Narrow that to what is actually proven: the walk
     cannot widen the grant's **scope**, and cannot make a non-grantable permission the **target**
     of a grant lookup. It can and does widen the set of permissions answered.
  3. Surface it: the grant request form and the approval screen should say, once, that granting
     `CONFIGURE` also answers the permissions Jenkins treats as implied by it (naming
     `Item/ExtendedRead` — reading `config.xml` — as the concrete case), so the approver's consent
     covers what is actually conferred.
- **Proposed regression test** — a test that walks `Permission.getAll()` at runtime, collects every
  permission whose `impliedBy` chain reaches `Item.CONFIGURE`/`CREATE`/`DELETE`, and asserts the set
  equals exactly `{Item.EXTENDED_READ}` for the test's dependency set. It then **fails when a
  dependency bump or a new test plugin widens the set**, which turns the javadoc claim into something
  the build enforces rather than something a reviewer has to re-derive.

### [S-18] A null delegate is documented but not prevented

- **File:line** — `src/main/java/io/jenkins/plugins/batchcontrol/security/BatchControlAuthorizationStrategy.java:54-62`
  (the constructor rejects self-nesting but accepts `null`);
  `src/main/resources/.../BatchControlAuthorizationStrategy/config.jelly:13-14`
  (`f:dropdownDescriptorSelector` with no validator); `DescriptorImpl` has no `doCheckDelegate`.
- **The problem** — saving the strategy with no delegate makes every ACL deny everything except
  SYSTEM, locking out every user including administrators; recovery is editing
  `$JENKINS_HOME/config.xml` on disk. This is the correct fail-closed direction and it **is**
  documented (`README.md:141-143`, `docs/LIMITATIONS.md:54`), which is why this is LOW and not
  higher. But it is preventable and is not prevented: JCasC that omits `delegate`, or a truncated
  form submission, reaches it silently. Combined with D-31 the instance is doubly stuck: no one can
  configure anything, and newly created jobs are also approval-required.
- **Direction of the fix** — reject `null` in the `@DataBoundConstructor` the same way self-nesting
  is rejected (a `Descriptor.FormException`/`IllegalArgumentException` surfaces on the security
  screen and JCasC fails loudly), or add `doCheckDelegate` returning
  `FormValidation.error(...)`. Keep the deny-all runtime default as the last line of defence for a
  hand-edited `config.xml`.
- **Proposed regression test** — *Given* a JCasC/`config.xml` fragment selecting the strategy with
  no `delegate`, *When* it is loaded, *Then* configuration fails with a message naming the missing
  delegate, and Jenkins does not end up in a deny-all state.

### [S-19] `ChangeRecording.endSuppression()` clears instead of restoring

- **File:line** — `src/main/java/io/jenkins/plugins/batchcontrol/listener/ChangeRecording.java:55-65`,
  used at `listener/ItemChangeListener.java:149,164`.
- **The problem** — `endSuppression()` sets `FALSE` unconditionally rather than restoring the prior
  value, so a nested `begin`/`end` pair would clear an outer suppression and cause plugin-internal
  saves to be recorded as user `CONFIGURE` changes (audit pollution, not a bypass). There is exactly
  one caller today (verified by grep), so there is **no current impact**; this is a latent trap for
  the next caller. The `ThreadLocal` is also never `remove()`d, which leaves a `FALSE` box on pooled
  request threads — harmless, but it is the pattern SpotBugs/reviewers flag.
- **Direction of the fix** — have `beginSuppression()` return the previous value and
  `endSuppression(boolean previous)` restore it (or use a depth counter), and `remove()` at depth
  zero.
- **Proposed regression test** — *Given* suppression is active, *When* a nested begin/end pair runs
  and an internal save follows, *Then* no `CONFIGURE` record is written.

### [S-20] The D-31 property rebuild can lose the whole job property if the second save fails

- **File:line** — `src/main/java/io/jenkins/plugins/batchcontrol/listener/ItemChangeListener.java:150-165`
  (`removeProperty` then `addProperty`, two separate saves, one `catch (IOException)` around both).
- **The problem** — when the creation payload already carried a `BatchControlJobProperty` with
  `approvalRequired=false`, the code removes it and then adds the rebuilt one. If the second save
  throws, the job is left with **no** `BatchControlJobProperty` at all — losing `blockTimer`,
  `blockUpstream`, `allowedUpstreamJobs` and `jobApprovers`, which are security-relevant settings
  (`blockTimer`/`blockUpstream` are the only controls over the unattended trigger paths). The
  failure is logged at `WARNING` and creation proceeds. `withApprovalRequired`
  (`config/BatchControlJobProperty.java:40-56`) does correctly carry every field into the copy, so
  the loss window is only the gap between the two saves.
- **Direction of the fix** — build the replacement first, then swap under a single save, or on
  `IOException` attempt to restore `existing` before logging. A test that injects an `IOException`
  on the second save and asserts the original property survives would pin it.
- **Proposed regression test** — *Given* a created job whose payload carries
  `BatchControlJobProperty(approvalRequired=false, blockTimer=true)` and a store that fails the
  second save, *When* D-31 runs, *Then* the job still has a `BatchControlJobProperty` with
  `blockTimer=true`.

### [S-21] The D-30 audit record has no rate limit and is written on the scheduling path

- **File:line** — `src/main/java/io/jenkins/plugins/batchcontrol/policy/RunRequestService.java:327-353`
  (`recordMarkerReuseBlocked`), called from `consumeMarker` at `:284-307` while holding
  `lock`, which itself runs inside `Queue.QueueDecisionHandler.shouldSchedule`
  (`queue/ApprovalQueueDecisionHandler.java:67-79`) — i.e. inside `Queue.schedule2`'s lock.
- **The problem** — every blocked re-use appends one line to `changes/<month>.jsonl`
  (`store/FileStore.java:461-465`) with no deduplication, no per-request cap and no throttle,
  bounded only by `retentionMonths` (default 24). The write is synchronous disk I/O taken while the
  queue lock is held, so a sustained stream of blocked re-uses both grows the audit store without
  bound and contends on the lock that serialises **all** scheduling on the controller. The UI layer
  anticipated the loop case and capped the alert at 10 rows
  (`action/HistorySection.java:76,344-360`); the store did not get the same treatment.
- **Honest assessment of reachability** — I could **not** demonstrate a high-rate loop from the HTTP
  surface. Reaching the record requires an `ApprovedRunAction` on the submitted actions; the
  plugin's own test drives it programmatically
  (`src/test/java/io/jenkins/plugins/batchcontrol/MarkerReuseAuditTest.java:140-155`,
  `job.scheduleBuild2(0, new Cause.UserIdCause(), marker)`), and no core HTTP endpoint re-submits a
  `Run`'s arbitrary actions. Realistic triggers are third-party rebuild-style plugins and Pipeline
  Replay, i.e. one record per human click. So the practical risk today is **low**; the missing bound
  is the finding, not a demonstrated flood.
- **Direction of the fix** — cap records per `(requestId, job)` (one record, or one per interval),
  or move the append off the queue lock (record after `consumeMarker` returns, outside the lock).
  Either keeps D-30's purpose — the attempt is visible — while removing the unbounded write on a
  global lock.
- **Proposed regression test** — *Given* run control on and a consumed marker, *When* the same
  marker is presented N times, *Then* at most the capped number of `MARKER_REUSE_BLOCKED` records
  exist and the history screen still reports the true attempt count.

### [S-22] CSV formula sanitisation does not cover a leading TAB

- **File:line** — `src/main/java/io/jenkins/plugins/batchcontrol/ui/CsvWriter.java:55-68`.
- **The problem** — D-18 prefixes a cell starting with `=`, `+`, `-` or `@`. OWASP's list also
  includes a leading TAB and CR, on the grounds that some spreadsheet importers strip leading
  whitespace before parsing the formula. CR and LF are already forced into a quoted cell by the
  quoting branch; TAB is not. Exploiting it needs a job name, user id or reason beginning with a tab,
  and RFC 4180 quoting is applied correctly on top, so the residual risk is small.
- **Direction of the fix** — add `'\t'` to the sanitised first-character set.
- **Proposed regression test** — `CsvWriter.encode("\t=1+1")` starts with `'`.
- **Explicitly checked and clean while here** — the D-30 `detail` string is built "comma-free so the
  CSV keeps one cell per column" (`RunRequestService.java:337-340`), and a job name **may** contain a
  comma (core's `Jenkins.checkGoodName` does not reject `,`, `=`, `+` or `-`). That reasoning is
  therefore not load-bearing, but it is also not needed: `CsvWriter.encode` performs correct RFC 4180
  quoting (`:63-66`), so a comma in a job name produces a properly quoted cell, not a column split.
  No CSV injection.

### [S-23] Inline `style` attributes throughout the Jelly views

- **File:line** — ~40 occurrences across `src/main/resources/**/index.jelly`, including new ones in
  this branch (`action/RequestItem/index.jelly:117,118,129`,
  `action/HistorySection/index.jelly:58,60,88`).
- **The problem** — not a current rule violation: Jenkins does not apply a page-wide CSP to plugin
  Jelly views today. But the jenkinsci CSP-compatibility effort targets inline styles as well as
  inline scripts, and a hosting reviewer may raise it. Recording it so the decision is deliberate.
- **Checked and clean on the part that does matter** — a full sweep of `src/main/resources` and
  `src/main/webapp` for `escapeXml="false"`, `<j:out`, `innerHTML`, `<script`, `javascript:` and
  `on*=` event-handler attributes returns **zero** matches. Every view carries
  `<?jelly escape-by-default='true'?>`. So `script-src` compatibility is already clean, and there is
  no raw-output XSS point anywhere in the plugin.
- **Direction of the fix** — optional; move the repeated declarations into a plugin CSS resource
  when convenient.

---

## Checked and found to be fine (with the basis)

**Permission decisions (the branch's riskiest change)**

1. `grantConfers` reproduces matrix-auth 3.3's `AuthorizationContainer#hasPermission(String,
   Permission, boolean)` walk exactly, verified against the unpacked source
   (`AuthorizationContainer.java:304-323`), including the skip-disabled-but-continue property. P-11's
   claim is accurate. (Residual documentation issues are S-17.)
2. Only `Item.CREATE`/`CONFIGURE`/`DELETE` can ever be matched: `GrantAction.fromPermission`
   (`model/GrantAction.java:37-48`) is reference-identity based, so the generic root permissions the
   chain passes through (`Permission.CONFIGURE`/`UPDATE`/`WRITE`/`CREATE`/`DELETE`,
   `Jenkins.ADMINISTER`) are unmatched. Confirmed against `hudson/security/Permission.java:343-358`.
3. No self-escalation: walking up from any `BatchControl/*` permission reaches `MANAGE` then
   `Jenkins.ADMINISTER` (`security/BatchControlPermissions.java:31-48`) and never a grantable item
   permission. A grant cannot confer `Manage`, `Approve`, `ViewHistory` or `Administer`.
4. Anonymous never benefits from a grant (`GrantAwareACL.java:63`). Fail-closed even if a store file
   names `anonymous` as the grant user.
5. `itemFullName == null` never consults `GrantService`: all eight non-item `getACL` overloads and
   `getRootACL` go through `noScope(...)` (`BatchControlAuthorizationStrategy.java:71-152`), which is
   the structural form of the S-13 root-scope closure.
6. The S-13 fix is complete: `GrantScope.includes("")` now returns `false` for both scope types
   (`model/GrantScope.java:56-65`) — the previous `return true` instance-wide branch is gone — and
   `GrantRequestService.approve` re-validates the stored scope after `checkDecision`
   (`policy/GrantRequestService.java:181-186`), so a hand-edited or legacy store file cannot confer
   anything and a non-approver learns nothing about scope validity from the ordering.
7. Every `getACL` overload delegates, including `IComputer`
   (`BatchControlAuthorizationStrategy.java:117-124`), and `getGroups` delegates with an empty-set
   fallback (`:138-142`). Self-nesting is rejected in the constructor (`:54-62`).

**Information disclosure on the new screens**

8. The recent-run table cannot leak a job the caller may not see. `getRecentRuns`
   (`action/RequestItem.java:147-167`) resolves the job through `findJob()` only; a requester or
   designated approver who is visible via the requester/approver branch of
   `Visibility.canSeeRunRequest` but lacks `Item/Read` gets an empty list, and the view renders "The
   job is not available: it has been deleted, or you may not view it."
   (`RequestItem/index.jelly:80-83`) — the deleted case and the invisible case are deliberately
   indistinguishable. Any caller who *does* see rows already has `Item/Read` and could read the same
   build history from the job page. (The `Discover` edge is S-16.)
9. `?runs=` cannot widen anything: it is matched against the allow-list `List.of(5, 10, 20, 50)`
   rather than clamped (`RequestItem.java:206-222`), so `?runs=100000`, `-1`, `7` and text all fall
   back to 5. It changes only row count, never whether the job is resolved. The size links are built
   from the constant, not from the raw parameter (`RequestItem/index.jelly:131-141`), and are `<a>`
   links rather than a GET form specifically so core's `hudson-behavior.js` cannot put a CSRF crumb
   in the address bar (pinned by a test, commit `ce06880`).
10. `getExecutedRunUrl` (`RequestItem.java:108-133`) refuses to link outside the request's own job:
    it parses the stored `job#number`, requires the job part to equal `request.getJobFullName()`, and
    resolves through the permission-aware lookup. The stored id is never used as a routing input.
11. The history re-use warning introduces no new reader and no new URL. It is rendered inside
    `HistorySection`'s view, and the whole `/batch-control/history/**` subtree — index, `summary`
    JSON and all four CSVs — is gated by `checkPermission(VIEW_HISTORY)` in
    `getTarget()` (`action/HistorySection.java:87-93`), which Stapler applies before any
    `do*`/`getDynamic` on the subtree. `getMarkerReuseItems` narrows the already-filtered
    `getChangeItems()` by type (`:319-343`), so it can never show a record the Changes tab would not.
12. The other five sections are gated the same way: `ChangesSection:44-48`, `DashboardSection:54-58`,
    `IncidentsSection:48-52` require `ViewHistory`; `RequestsSection:44-52` and `GrantsSection:68-77`
    require any of `Request`/`Approve`/`Manage` and `RequestGrant`/`Approve`/`Manage` respectively,
    with per-row `Visibility` predicates reused verbatim by the detail URLs
    (`RequestsSection.java:92-95,146-152`; `GrantsSection.java:116-120`) so list and detail cannot
    diverge, and a non-visible object renders as a 404.
13. `HistorySection.doDynamic` (`:150-167`) allow-lists the four literal CSV paths and 404s anything
    else, so no path traversal through `getRestOfPath`. CSV download filenames are constants
    (`CsvWriter.open` javadoc contract, honoured at `:170,180,192`).

**Jenkins rules**

14. Every state-changing web method carries `@RequirePOST` **and** a permission or authentication
    check as its first statement — verified exhaustively, not sampled:
    `RequestItem.doApprove/doReject/doChangeApprover` (`:292-327`),
    `RequestItem.doCancel` (`:310-318`, authentication check, with requester-or-Manage enforced by
    the service), `GrantRequestItem.doApprove/doReject/doCancel` (`:119-145`),
    `GrantsSection.doCreate` (`:142-144`), `ActiveGrantsSection.doRevoke` (`:86-88`),
    `IncidentItem.doAcknowledge/doResolve/doComment/doRerun` (`:151-185`),
    `JobRequestAction.doSubmit` (`:121-125`, `Item.READ` on the job **then** `Request`). No
    state-changing method is missing either half.
15. Read-only URLs actively refuse non-GET rather than relying on convention: `doIndex` on
    `RequestItem`, `RequestsSection`, `GrantsSection`, `HistorySection` and the other sections return
    405 with an `Allow: GET, HEAD` header (for example `RequestItem.java:277-287`). This is stricter
    than required and worth keeping.
16. Both `ACL.SYSTEM2` switches are correct and narrow. `RunRequestService.submitApproved:636-644`
    switches only around `scheduleBuild2`, after the approver's authority was verified by
    `ApprovalPolicy.checkDecision`, and the queue gate still validates and consumes the marker.
    `IncidentItem.doRerun:193-197` switches for the *existence* lookup only — deliberately, because
    a caller-scoped lookup returns `null` for an existing-but-unreadable job and would skip the very
    check it exists for — then closes the context and runs `job.checkPermission(Item.READ)` as the
    real caller. Both carry the reason in a comment, as `CLAUDE.md` requires.
17. No secret reaches a store, a diff or a screen. Run-request parameters are masked at the HTTP
    entry point: `JobRequestAction.flatten` (`:182-191`) returns the mask for
    `value.isSensitive()` **and** for a raw `Secret`, before anything is persisted.
    `RunRecord` and `Incident` parameters go through `IncidentService.maskedParameters`
    (`:236-255`, `isSensitive()`-based), used at `listener/RunRecordListener.java:89` and
    `ops/IncidentService.java:101`. Config diffs mask **both** sides before diffing
    (`listener/ConfigSnapshotListener.java:78-83`), and the console tail is masked twice — by literal
    secret value and by pattern (`IncidentService.java:273-276`). `SecretMasker`
    (`store/SecretMasker.java:31-37`) covers `password|secret|token|passphrase` element names and the
    `{AQ…}` encrypted-`Secret` shape.
18. No path is built from user input. Every store path is composed from `Jenkins.getRootDir()` plus
    fixed segments (`store/FileStore.java:69-105`), and the one place a name becomes a file name goes
    through `PathCodec.resolveUnder` (`store/PathCodec.java:103-117`), which rejects `/`, `\`, `.`,
    `..` and an empty name, then re-checks `normalize().startsWith(base)` and rejects a result equal
    to the base. Encoded names are hex-escaped with an allow-list (`:92-98`).
19. Store files are written with default permissions and no explicit mode — the same as core's own
    `jobs/*/config.xml` and `AtomicFileWriter`. `$JENKINS_HOME` permissions are the deployment's
    responsibility, and the snapshots the store holds are masked (17). Consistent with core; no
    finding.
20. Deserialisation uses `XStream2` (`store/FileStore.java:58,382,409`), which applies Jenkins'
    class filter. No custom converter, no `allowTypes`, no `readObject`/`readResolve` in the model.
    Timestamps are persisted as `long` precisely because the class filter rejects
    `java.time.Instant` (`model/Grant.java:20` and siblings) — deliberate, documented, and it keeps
    the persisted graph to primitives and strings.
21. No external network access anywhere in `src/main`: a sweep for `URL(`, `openConnection`,
    `HttpURLConnection`, `HttpClient`, `Socket` and `URI.create("http` returns zero matches. No
    bundled third-party code: no `*.js`, `*.css`, minified bundle or vendored font is tracked
    anywhere in the repository, and no source file carries a third-party copyright header.
22. No SpotBugs exclusion file and no `@SuppressFBWarnings` anywhere in `pom.xml` or `src/main` —
    nothing is being hidden from the analyser. (The analyser result itself is unconfirmed this
    round; see below.)
23. `pom.xml` moved in the right direction: the personal `<developers>` block with an email address
    was removed, `<scm>`/`<url>` now point at `jenkinsci/${project.artifactId}-plugin`, the BOM was
    advanced to `7093.v37de7b_4a_8a_4f`, and five enforcer gates were **enabled**
    (`hpi.strictBundledArtifacts`, `ban-commons-lang-2`, `ban-deprecated-stapler`,
    `ban-junit4-imports`, `banObsoleteDependencyOverrides`). All are hosting-positive.

**D-31 / D-32 lockout and denial-of-service analysis (the owner's question 3)**

24. **D-31 does not stall automation, and this is by design rather than by luck.** The gate
    classifies before it refuses: timer causes pass unless `blockTimer`
    (`ApprovalQueueDecisionHandler.java:99-109`), upstream causes pass unless `blockUpstream`
    (`:111-126`), SCM causes pass (`:128-133`), and both job-level switches default to off. So in a
    Job-DSL/seed-job or auto-generating environment the generated jobs' **scheduled and chained**
    builds keep running after run control is turned on. `SPEC.md:115` (D-31) states exactly this, and
    the code matches it. The residual exposure is human- and API-triggered builds of newly created
    jobs, which is the intended control. *One correction to that claim belongs in S-14*: the
    "automation keeps working" promise does **not** extend to `POST /job/x/build` with an API token,
    which produces a `UserIdCause` and **is** refused — while the same URL with a *build* token is
    wrongly allowed. The two automation shapes behave opposite to the way the documentation implies.
25. D-31 cannot run during startup or reload: it hangs off `ItemListener.onCreated`
    (`ItemChangeListener.java:44-58`), and loading an existing item fires `onLoaded`, not
    `onCreated`. No mass re-application on restart.
26. D-32's `instanceof ComputedFolder` test (`ItemChangeListener.java:210-212`) is the right
    predicate and the dependency reasoning holds: `cloudbees-folder` is a non-optional compile
    dependency, so the class is always present and no optional-dependency guard is needed; a
    `ComputedFolder` is an `AbstractFolder`, not a `Job`, so the container itself is already filtered
    out by the `instanceof Job` check above. `MultiBranchProject` and `OrganizationFolder` both
    extend `ComputedFolder`, so branch jobs and repository projects are both covered by one rule.
27. The recursion guard is correct for the single caller that exists: `beginSuppression`/
    `endSuppression` around `addProperty` (`:149-165`) stops the property save from being recorded as
    a user `CONFIGURE` change and from re-entering the listener. (Nesting fragility is S-19.)
28. The grant permission check does **not** hit disk on the hot path in steady state:
    `GrantAction.fromPermission` filters to three permissions before the synchronized
    `findActiveGrant` is entered (`security/GrantService.java:63-66`), and the cache is only reloaded
    when the Jenkins instance changes (`:152-163`). So ordinary `Item/Read`/`Build` checks never take
    the `GrantService` monitor at all.
29. `?runs=50` costs at most 51 build loads per render, hard-capped server-side and not reachable
    without `Item/Read` on the job. Comparable to core's own build-history page; not an amplification
    primitive.

**Concurrency and state**

30. `consumeMarker` (`RunRequestService.java:277-323`) performs the whole check-and-claim under
    `lock`, and the spent-ticket check was deliberately moved **before** the status check so a replay
    of an executed approval is reported as the spent ticket it is rather than as a status mismatch.
    A marker authorises exactly one queue entry (D-23). Double approval is prevented by the status
    check inside the same lock.

**Public-repository readiness (the owner's question 5) — full 76-commit history scan**

31. **No secret of any kind, in any commit, anywhere.** `git log --all -S` returned zero commits for
    each of `ghp_`, `github_pat_`, `gho_`, `AKIA`, `xoxb-`, `xoxp-`, `BEGIN … PRIVATE KEY`,
    `client_secret`, `private_token`, `npmrc`, `settings.xml`, `JENKINS_ADMIN`, `admin:admin`. A
    full-history added-lines sweep for secret-assignment shapes yielded three hits, all benign source
    code (`for (String secret : secrets)`, the `ENCRYPTED_SECRET` pattern, and prose about
    `Asia/Seoul`).
32. **`e2e/.env` was never committed** (`git log --all --oneline -- e2e/.env` is empty) and is ignored
    via `e2e/.gitignore:2`. It exists locally as untracked-and-ignored. Only `e2e/.env.example` is
    tracked, and it contains placeholders only (`BC_ADMIN_PASSWORD=change-me-admin` etc. plus a
    "Never commit e2e/.env" comment).
33. **No hardcoded credential in the e2e environment, and none ever.** `e2e/docker-compose.yml:25-27`
    uses the fail-if-unset form `"${BC_ADMIN_PASSWORD:?set BC_ADMIN_PASSWORD in e2e/.env}"` with no
    default; `e2e/init.groovy.d/00-security.groovy:32-38` throws when the variable is absent;
    `e2e/scripts/lib.sh:11-20` exits 2 without `.env`. `git log --all --follow -p` shows both files
    were introduced in this shape — no earlier revision held a literal password. The `Dockerfile`
    contains no credential.
34. **No build output, archive, keystore or binary blob in history.** All 333 paths that have ever
    existed were enumerated: no `target/`, `out/`, `node_modules/`, `work/`, `*.hpi`, `*.jar`,
    `*.class`, `*.key`, `*.pem`, `*.p12`, `*.jks`, `*.log`, `*.har`, `id_rsa`, `credentials*` or
    `secrets*`. Largest blob in the whole history is `docs/TEST-MATRIX.md` (90 KB). `e2e/out/`,
    `target/` and `poc/target/` are correctly ignored.
35. **No internal hosts, no private IPs, no leaked local paths.** Zero matches for `10.`/`192.168.`/
    `172.16-31.` ranges and zero for `C:\Users`, `/home/<user>/`, `/Users/<user>/` across all tracked
    files. The complete hostname set in the repository is `localhost`, `github.com`,
    `www.jenkins.io`, `repo.jenkins-ci.org`, `maven.apache.org`, `issues.jenkins.io`, `www.w3.org`,
    `opensource.org`, `raw.githubusercontent.com`, `javadoc.jenkins.io`, `api.github.com`,
    `accounts.jenkins.io` — no corporate or `.internal`/`.corp`/`.local` domain.
36. **Only two email addresses exist in the tree**: `jenkinsci-cert@googlegroups.com` (×3, the
    correct Jenkins security-reporting address) and the owner's own `dev.yongjunh@gmail.com` (×2,
    both in `docs/HOSTING-READINESS.md`). Commit authorship is two identities, both the owner
    (`dev.yongjunh@gmail.com`, 34 commits; `yongjunh@apache.org`, 42 commits — a public ASF ID). No
    third-party or corporate address anywhere.
37. **All 76 commit messages are clean and professional** — no secret, no internal URL, no personal
    data, nothing that would be regretted publicly.
38. **Licensing is consistent.** `LICENSE` is the unmodified MIT text; `pom.xml:20-25` and
    `poc/pom.xml:19-24` both declare MIT. Zero `Copyright`/`SPDX-License` headers in `src` or `poc`,
    so no conflicting or copied third-party licence.
39. **CI and repository metadata are clean.** `.github/workflows/jenkins-security-scan.yml` calls the
    official reusable workflow SHA-pinned, uses no `secrets.*` and declares minimal `permissions:`.
    `.github/CODEOWNERS` names the org team, not a personal handle. `Jenkinsfile` is a standard
    seven-line `buildPlugin(...)`.
40. **Only three files were ever deleted**, all benign (`docs/HANDOFF.md`, reviewed at its last
    revision — no credential; and two refactored sources). Nothing sensitive is hiding in a deletion.
41. The one uncommitted file, `src/test/java/.../SecretParameterMaskingTest.java`, was reviewed: its
    `"pl41nt3xt-s3cr3t-7b2c9e"`-style literals are synthetic leet-speak fixtures for the masking
    test, documented as false-positive guards. Not a real secret.

---

## Public-repository items that are judgement calls, not defects

These are not security findings; they are decisions the owner should take deliberately before the
repository is read by a hosting reviewer.

- **20 committed screenshots need a human eyeball.** All under `e2e/screenshots/`
  (19 `.jpg` + `global-config-label-collision.png`, 6.9 KB–69 KB). The environment they were taken
  from is a throwaway `localhost:8080` container with synthetic accounts (`admin`/`approver`/
  `requester`) and synthetic jobs (`batch-daily`/`batch-pipeline`/`batch-cron`) per `e2e/README.md`
  and `e2e/init.groovy.d/00-security.groovy`, so the *expected* content is safe. What cannot be ruled
  out by scanning: an address bar showing a non-localhost host, a bookmark bar or other tab titles, a
  CSRF crumb in a URL, or an OS username in browser chrome. Highest risk (largest, full-page):
  `grants.jpg` (69 KB), `T-10-06.jpg` (61 KB), `incident-detail-open.jpg` (55 KB). **I cannot read
  raster content; a human must open all 20 before merge.** This is the single largest residual risk in
  the publication scan.
- **Publishing `security-01.md`/`-02.md` publishes a line-referenced map of the plugin's accepted
  weaknesses.** `security-02.md:388-402` names four still-open items with exact `file:line`. Jenkins
  routes security issues through `jenkinsci-cert@googlegroups.com`, not public docs. Either close
  S-02/S-04 in code first, or carry their substance as entries in `docs/LIMITATIONS.md` and keep the
  raw reports out of the published tree. Owner's call — and it applies to **this report** too.
- **Root `.gitignore` does not ignore `.env`** — only `e2e/.gitignore` does, so a `.env` created
  anywhere else would be committable. Adding `.env`, `*.hpi` and `.DS_Store` to the root file is
  cheap insurance.
- **`poc/` ships as an orphaned second `hpi` project.** `poc/pom.xml` declares
  `batch-control-poc:0.1.0-SNAPSHOT`, `packaging hpi`, and the root `pom.xml` has no `<modules>`, so
  it is never compiled or tested. A hosting reviewer will very likely ask about a second plugin
  project in the tree. Either freeze it behind a `poc/README` saying it is Phase-1 evidence, or remove
  it.
- **`docs/HOSTING-READINESS.md:854` still contains an unfilled template slot**
  (`<OWNER: GitHub handles with @, one per line …>`) and open questions addressed to the owner
  (`Q3`, `Q6`, `E1`, `E2`) that will be public as written.
- **`docs/reports/e2e-01.md` and `e2e-02.md` embed `1.0.0-SNAPSHOT (private-…-Yongjun Hong)`** — the
  `hpi:run` marker picking up the OS account name. Harmless given the `LICENSE` copyright line, but it
  carries no information and can be trimmed.
- **`docs/reports/e2e-01.md` cites `*.png` screenshots that are all committed as `.jpg`** — the links
  will render broken on GitHub. Cosmetic.
- **Mixed-language docs.** `SPEC.md` (167/226 lines), `TEST-MATRIX.md` (179/274),
  `ARCHITECTURE.md` (81/128), `POC-RESULTS.md`, `HOSTING-CHECKLIST.md`, `DECISIONS.md` and
  `STATUS.md` are majority Korean, and `security-02.md` still carries the `## Requests (요청)` /
  `요청:` markers. `CLAUDE.md` states all repository artifacts are English. The documents a hosting
  reviewer will actually read (`SPEC.md`, `ARCHITECTURE.md`) are the least converted. Not a security
  matter; a readiness one.

---

## Carried forward from security-01/-02, deferred by human decision — still open on this branch

Restated because merging publishes them, and because a hosting reviewer will read them as current.

- **S-02** (MEDIUM) — incident state changes are gated by the **read**-scoped `ViewHistory`:
  `action/IncidentItem.java:154,163,172` (`doAcknowledge`, `doResolve`, `doComment`) all check
  `VIEW_HISTORY`. Verified unchanged on this branch. A permission whose description is "View history
  screens and CSV exports" (`BatchControlPermissions.java:47-48`) should not authorise writes; this is
  the kind of thing a Jenkins security reviewer names explicitly. Either introduce a write-scoped
  permission or have these check `Approve`/`Manage`.
- **S-04 / P-06** (MEDIUM) — `BatchControl/RequestGrant` is enforced at the HTTP layer only.
  `GrantRequestService.create` (`policy/GrantRequestService.java:85-90`) still carries the comment
  "The RequestGrant permission is enforced by the HTTP layer (GrantsSection.doCreate)" and performs no
  check, whereas its sibling `RunRequestService.create:111` does check `Request`. The asymmetry is
  what makes it worth closing: defence in depth exists on one path and not the other, for no stated
  reason. P-06 is still "awaiting human decision".
- **S-08, S-09** (LOW) — documented limitations; `S-09` (null-delegate lockout) is now in
  `README.md:141-143` and `docs/LIMITATIONS.md:54`, which is why S-18 above is only LOW.

---

## Could not confirm

1. **SpotBugs.** `mvn` was off-limits (concurrent build) and `target/spotbugsXml.xml` is absent, so
   step 11 of the review procedure was not executed. `security-02` recorded 0 `<BugInstance>` against
   an earlier tree; this branch adds ~600 lines of `src/main`, so the result **must be re-read before
   merge**. I did verify there is no exclusion file and no `@SuppressFBWarnings`, so whatever the
   analyser reports will be complete.
2. **The test suite.** Not run, for the same reason. The 45 commits include a full JUnit 4 → 5
   migration plus a re-enabled `ban-junit4-imports` enforcer gate (`pom.xml`), which is exactly the
   kind of change that can leave a test silently not running (a JUnit 4 `@Test` import removed but the
   method never re-annotated compiles and is simply never executed). **Before merge, compare the test
   count against the pre-migration baseline**, not just the pass/fail result. I could not do this
   statically with confidence.
3. **Screenshot pixel content** (20 files, listed above). Human eyeball required.
4. **Transitive dependency licences.** `dependency:tree` and `license:aggregate-third-party` need
   Maven. `pom.xml` declares only the Jenkins parent, the `2.568.x` BOM and Jenkins plugin
   dependencies, so the risk is low but unverified.
5. **The exhaustive plugin-ecosystem answer to S-17.** I proved the `impliedBy` set is exactly
   `{Item.EXTENDED_READ}` for **jenkins-core 2.568.3**, and I checked the obvious plugin candidate
   (`SCM.TAG`, which does *not* reach `Item.CREATE`). I did **not** enumerate every plugin in the
   update centre for permissions declared with `impliedBy = Item.CONFIGURE/CREATE/DELETE`; that is not
   statically knowable for an arbitrary future instance, which is precisely why S-17 recommends a
   runtime assertion test instead of a static claim.
6. **S-14 end to end.** The bypass is derived from core source (`getBuildCause`, `RemoteCause`'s class
   hierarchy, and the gate's final `return true`) and I am confident in the reading, but I did not
   execute it against a live Jenkins with a build token configured. That is the one thing that would
   turn the inference into a demonstration, and it is cheap to do in the existing `e2e/` container.

---

## Request: paths this report cannot write

- **Request:** `docs/SPEC.md`, `docs/DECISIONS.md` — human only. Decide **S-14**: does a build
  authentication token pass or fail the run-control gate? If it fails, `SPEC.md:77` stands as written
  and core-dev implements it. If it passes, `SPEC.md:77` must be amended to exclude the token variant
  and a DECISIONS entry must record why. Decide **S-15**: does `changeControlEnabled` gate permission
  windows (making `SPEC.md:32` true) or only the delete veto (making `README.md:113` wrong)? Also
  still open from the previous round: ratify **P-09**, resolve **P-06** (S-04) and the **S-02**
  incident-permission scoping.
- **Request:** `src/main/java/io/jenkins/plugins/batchcontrol/{queue,security,policy,listener,ui}/**`
  — core-dev, once the two decisions above are taken:
  - **S-14** — classify `Cause.RemoteCause` in `ApprovalQueueDecisionHandler.shouldSchedule`, and
    record refused unrecognised submissions rather than only logging them.
  - **S-15** — gate `GrantAwareACL.grantConfers`, `GrantsSection.getTarget()` and
    `GrantRequestService.create` on `isChangeControlEnabled()` (option (a)).
  - **S-16** — add a single `AccessDeniedException`-swallowing item-lookup helper and route all nine
    `getItemByFullName` call sites through it.
  - **S-19** — make suppression restore-based or depth-counted, and `remove()` the `ThreadLocal`.
  - **S-20** — make the D-31 property swap single-save, or restore `existing` on `IOException`.
  - **S-21** — cap or dedupe `MARKER_REUSE_BLOCKED` appends, and move the append off the queue lock.
  - **S-22** — add `'\t'` to `CsvWriter`'s formula-prefix set.
  - **S-17** — correct the three over-absolute sentences in `GrantAwareACL`'s javadoc
    (`:93-97`) and add the effective-permission note to the grant request/approval screens
    (`ui-dev` for the screens).
- **Request:** `src/test/**`, `docs/TEST-MATRIX.md` — test-author: the eight Given/When/Then tests
  proposed above, and in particular **the S-17 runtime assertion** (walk `Permission.getAll()`, assert
  the set reaching the three grantable permissions is exactly `{Item.EXTENDED_READ}`) — it converts a
  claim a reviewer must re-derive into something the build enforces. Also: **verify the JUnit 5
  migration did not drop tests** by comparing the executed test count against the pre-migration
  baseline, not just pass/fail.
- **Request:** `README.md`, `docs/LIMITATIONS.md`, `.gitignore` — release-manager: correct
  `README.md:56-57` and `:113` per the S-14/S-15 decisions; add `.env`, `*.hpi`, `.DS_Store` to the
  root `.gitignore`; fill or remove the `HOSTING-READINESS.md:854` template slot and the open
  `Q3/Q6/E1/E2` questions; decide whether `poc/` ships and whether `docs/reports/security-*.md`
  (including this file) belong in the published tree.
- **Request:** `e2e/**`, `docs/reports/e2e-*.md` — e2e-tester: an e2e row for **S-14** (job with an
  auth token + `approvalRequired=true`, `POST /job/x/build?token=…`, assert not queued) — the
  container already has everything needed; fix the `.png`/`.jpg` screenshot links; drop the
  `private-…-Yongjun Hong` version fragments.
- **Request (human, before merge):** open all 20 files in `e2e/screenshots/` and confirm no
  non-localhost host, no CSRF crumb in an address bar, no bookmark bar and no OS username is visible.
  Start with `grants.jpg`, `T-10-06.jpg` and `incident-detail-open.jpg`.
