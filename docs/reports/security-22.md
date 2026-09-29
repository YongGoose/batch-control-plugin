# Security Review 22: e2e-run3 part 4 re-review (`fix/e2e-run3-part4`)

Reviewer: security-reviewer. 2026-09-30. Scope: `git diff main..HEAD -- src/main` (the complete part-4 diff), with focus on eee5e64 (ui, D-50a views), 1b8e62a (core, D-50a `SystemBuildCheck`) and 7642f2c (core, D-51a budget, Replay per attempt, re-run build named). The part since security-21 is `git diff c6b79a7..HEAD -- src/main` (8 files).

Standard: docs/HOSTING-CHECKLIST.md section B; SPEC lines 107 (D-51, D-51a) and 151 (D-50, D-50a); DECISIONS D-35b, D-35d, D-50, D-50a, D-51, D-51a (as amended in 3715223); CLAUDE.md. As instructed, Maven was not run and `target/` was not touched. Third-party behaviour was read from the local jars: jenkins-core 2.568.3 sources; authorize-project 534.v2f208c45e11c, naginator 1.556.v14d723a_109a_c and rebuild 338.va_0a_b_50e29397 bytecode (`javap -c`); workflow-cps 4383.v04fa_a_3d67b_d9 sources.

## Summary: BLOCKER 0 / HIGH 1 / MEDIUM 0 / LOW 4 / INFO 3

All of the security-21 findings are closed. The table below gives the closure evidence for each. One new HIGH remains. It is in the D-50a question itself, not in the code that implements it: "not SYSTEM" is treated as "safe", but a build identity with Administer is just as unguarded as SYSTEM.

### Closure of security-21

| Finding | Status | Evidence |
|---|---|---|
| S-21-01 (HIGH) wrong question | Closed | `SystemBuildCheck.probe` (l.131-144) asks D-50a's question: a job with no `AuthorizeProjectProperty` and no cause. That covers both cases. Case 1: `TriggeringUsersAuthorizationStrategy.authenticate` returns null without a root `UserIdCause` (bytecode l.0-7, 71-72), so the result falls through to SYSTEM and the probe warns. Case 2: a removed property is the same as the probe's no-property job. The remedy text in both views and in LIMITATIONS 35 now says that a strategy on a single job is not enough. |
| S-21-02 (HIGH) disclosure to viewers | Closed | `GrantRequestItem.isShowSystemBuildWarning` (l.135-144) shows the warning only for a pending CONFIGURE request, and only to `isCanDecide()` or a global `BatchControl/Manage` holder. The block is fixed text with no job name or count (`index.jelly`, escape-by-default). The per-job list and the hidden count are gone. |
| S-21-03 (MEDIUM) unbounded per-GET scan | Closed | There is one instance-wide probe, cached for 5 minutes (`Cached`, l.60-86). The page does no store reads and no per-scope walk. |
| S-21-04 (MEDIUM) silent bounds and failures | Closed | There are no bounds left. A failing authenticator or check returns `true` and shows the warning (fail-safe, l.112-115 and 140-143, logged at WARNING). |
| S-21-05 (MEDIUM) D-51 unbounded | Closed | Budget of 20 per user per 10 minutes. Both maps are bounded at 10,000. Eviction never reopens a budget: only idle budgets are removed (l.252-254), and when the map is full a new user is counted under a shared overflow budget. The one item security-21 left unconfirmed is now confirmed: naginator's `NaginatorRetryAction#doIndex` carries `@RequirePOST` (class file `RuntimeVisibleAnnotations`). Residual points: S-22-04 and S-22-06. |
| S-21-06 (LOW) scan on every admin page | Closed | `isActivated()` reads the cached value. The bound warning is removed. Authorize Project's "Invalid User" WARNING can now appear at most once per 5 minutes. |
| S-21-07 (LOW) probe context and queue ids | Closed | One fixed context, `ACL.as2(Jenkins.ANONYMOUS2)`, with no SYSTEM2 switch. At most one queue id is used per cache period. Residual: S-22-02. |
| S-21-08 (INFO) CLI / Rename messages | Unchanged, no action | `GrantAwareACL` and `NameRestrictionValidation` have not changed since c6b79a7. |
| S-21-09 (INFO) Replay/Rebuild coverage | Closed for the simple case | A person's Replay goes to `recordPersonRefusal` (handler l.160-171). The re-run build is read from `NaginatorCause#getSourceBuildNumber`, `ReplayCause#getOriginalNumber` and `RebuildCause` (confirmed that it `extends Cause$UpstreamCause`). Residual for chained re-runs: S-22-05. |

## BLOCKER (grounds for hosting rejection)
None. No web method was added, removed or changed, no permission check was weakened, the new code confers nothing, and there is no new `ACL.SYSTEM2` switch.

## HIGH
- [S-22-01] `security/SystemBuildCheck.java:138-139` (`return identity == null || ACL.SYSTEM2.equals(identity) || ...`); the same claim appears in `docs/LIMITATIONS.md:336-338` ("the build's save comes under the same guard as a manual one"). The problem: the check reports "safe" for *any* identity other than SYSTEM, including one with Overall/Administer.
  - The D-35b guard only reverts an authorization change when the saving identity's Configure "comes only from a grant". Per D-35d (1), it asks the parent strategy whether that identity holds Configure natively.
  - A common way to migrate away from SYSTEM builds is an Authorize Project global default of "Run as Specific User: <service account>", where the service account is an administrator. Under that default, a Configure window holder can edit a Pipeline to run `properties([authorizationMatrix(...)])`. The build saves the job as the administrator, the guard sees native Configure and keeps the entry, and the permanent authorization entry of DEF-37 is written.
  - Meanwhile the probe returns that user's identity, so `buildsMayRunAsSystem()` is false and both the monitor and the approver's page show nothing. The docs say that the global default fixed the problem.
  - The same holds for a job that already has its own `SpecificUsersAuthorizationStrategy(<admin>)`. Under per-project ordering, `ProjectQueueItemAuthenticator` answers first. The holder cannot set that strategy (authorize-project requires the user's password or token unless the configurer is that user or an administrator), but they can keep it, because a normal job save preserves the property.
  - Rated HIGH for consistency with S-21-01: this is the same exposure class, and the mitigation the approver relies on stays silent. It is not a BLOCKER, because the exposure is Jenkins' own and D-35d accepted it. The human may downgrade it.
  - Basis: D-35b, D-35d (1)(2); D-50a ("identity other than SYSTEM" is too narrow for the goal it states: "a holder of a Configure window can make a build change permissions permanently"); SPEC line 151; checklist B (privilege and `ACL.SYSTEM2` reasoning).
  - Fix direction:
    - (1) After the probe returns a non-SYSTEM identity, also treat the instance as exposed when that identity is not subject to the D-35b guard. Use the same parent-ACL question the guard asks: the parent strategy's root ACL, without the grant layer, grants the identity Overall/Administer, or Item/Configure at the root. Word the warning as "builds run as SYSTEM or as a user who may change permissions".
    - (2) The per-job case (an existing privileged per-job strategy) cannot be answered instance-wide without the per-job scan that D-50a withdrew. At least document it in LIMITATIONS 35: "a job whose own build authorization runs as an administrator is exposed to any Configure holder of that job". Correct the "same guard as a manual one" sentence so that it names the condition: the build user must not hold Configure natively.
    - (1) changes the D-50a question, so see the Request below.
  - Regression test:
    - Row a: Given change control on, authorize-project installed, a global default `SpecificUsersAuthorizationStrategy("admin")` where `admin` holds Overall/Administer in the parent strategy, and a pending CONFIGURE grant request. When an administrator opens `/manage/` and a designated approver opens the request. Then both show the SYSTEM-or-privileged-build warning.
    - Row b (control): Given the same setup with the global default user `batch` holding only Item/Build and Item/Read. When the same pages are opened. Then neither shows the warning.

## MEDIUM
None.

## LOW
- [S-22-02] `security/SystemBuildCheck.java:58, 131-137`: the probe is representative only for authenticators whose answer does not depend on the job's name, location or type, or on the calling thread's identity.
  - What I confirmed: the four authorize-project strategies and both authenticators answer the same in any context. `GlobalQueueItemAuthenticator` checks `task instanceof Job` and then calls the strategy. `SpecificUsers` does `User.get(id,false)` and then `impersonate2()`. `Triggering` reads the root `UserIdCause`. `Anonymous` and `System` return constants. None of them reads `Jenkins.getAuthentication2()`.
  - Where the probe can be wrong: a third-party `QueueItemAuthenticator` that maps by job name or folder (for example "jobs matching `batch-.*` run as X"), or by type (FreeStyle vs Pipeline). Such an authenticator can answer for the root-level FreeStyle probe named `batch-control-system-build-probe` while leaving real jobs to SYSTEM.
  - An authenticator that returns the current authentication would answer `ANONYMOUS2` in the probe. At dispatch it runs on the queue or executor thread (SYSTEM) and would answer SYSTEM.
  - Because the probe's name is a legal item name, an Item/Create holder (a CREATE window whose pattern admits it) can create a real job with that name. A name-keyed authenticator that looks the job up by `getFullName()` then answers from that job's configuration. That is a narrow cache-poisoning path.
  - All of these are hypothetical for the authenticators this plugin documents, and a mismatch in the other direction only adds a warning.
  - Fix direction:
    - Use a probe name that `Jenkins.checkGoodName` rejects (for example one containing `:`), so it can never equal a real item.
    - Probe in both contexts (anonymous and `ACL.SYSTEM2`, with a reason comment: no permission is exercised, only the authenticator chain is read) and warn if either yields SYSTEM.
    - State in the class Javadoc and in LIMITATIONS 35 that authenticators which decide by job name, folder or type are not judged.
  - Regression test: Given a test `QueueItemAuthenticator` that returns user `u` only when `Jenkins.getAuthentication2()` is SYSTEM, and null otherwise. When `buildsMayRunAsSystem()` is evaluated. Then it is `true`, whereas the current code returns `false`.

- [S-22-03] `store/BlockedAttemptAudit.java:229-291, 308-329` together with `ops/ExpiryPeriodicWork.java:55-58`: the D-51a closing count record is lost on a restart or a crash.
  - Open summaries live only in `personBudgets`, in memory. `forgetOtherInstance()` clears them, and nothing flushes them at shutdown. After a restart inside a summary window, the history holds a record that promises "further refused re-runs ... are counted", and no count ever follows. Only the controller log (one INFO line per counted refusal) keeps the attempts.
  - `flushPersonSummaries()` runs after `RunRequestService.expireOverdue(...)`, which has no `try` around it. A failure there skips the flush for that minute. It is caught up at the next minute or the next refusal, so the record is delayed, not lost.
  - No attacker can trigger this: a restart needs Administer. It is still an append-only audit record that SPEC line 107 says is always written.
  - Basis: SPEC line 107 ("one closing record gives their number when the window ends"); D-51a; checklist B (audit integrity).
  - Fix direction:
    - Flush open summaries from a `@Terminator` (or `ItemListener.onBeforeShutdown`). Write the closing record with "window ended early by shutdown at <t>".
    - For a crash, document in LIMITATIONS that an open count can be lost. Records stay append-only, so there is nothing to rewrite at the next start.
    - Wrap `expireOverdue` so that the flush always runs.
  - Regression test: Given user u with 21 refused Retries (20 records and 1 summary) and 3 further refusals. When Jenkins is restarted (JenkinsSessionExtension) before the window ends. Then the history contains a closing record for u with the count 3.

- [S-22-04] `store/BlockedAttemptAudit.java:225-227, 238, 252-254, 308-329`: `recordPersonRefusal` is not constant-time, and it can append other users' records while the global queue lock is held.
  - Every call starts with `flushExpired(now)`. That loop walks all of `personBudgets`, and budgets are removed only when the map is full, so after long uptime it holds up to 10,000 entries.
  - The same call appends one closing record for each user whose summary has expired, in addition to the caller's own record.
  - When the map is full and a new user arrives, `removeIf(idle)` is O(n) and calls `forgetOld` on every budget.
  - This runs inside `Queue#schedule2` under the queue lock (see the class Javadoc). It is bounded (10,000 cheap checks, and one append per expired summary, where each summary is closed only once), so it is not a DoS. It does contradict both the Javadoc ("constant-time per call and the only I/O is the one append") and D-51a ("no record is written while the global queue lock is held longer than needed to hand it to the store").
  - Separately, `flushPersonSummaries()` on the periodic thread holds the same monitor during its appends. A queue thread refusing anything in that moment waits on it while holding the queue lock. That pattern already existed for `record`/`recordCoalesced`.
  - Basis: D-51a; S-21-05 fix direction; the class Javadoc.
  - Fix direction:
    - Leave cross-user flushing to the periodic work only.
    - In `recordPersonRefusal`, close only the *caller's* budget when its `summaryUntil` has passed. That is O(1) and at most one extra append.
    - Remove idle budgets in the periodic work instead of on insert.
    - Correct the Javadoc.
  - Regression test: Given 10,000 users with idle budgets and 5 users with expired summaries. When user x has one refused Retry. Then exactly one record (x's) is appended during that call, and the 5 closing records are appended by the next `ExpiryPeriodicWork` run.

- [S-22-05] `queue/ApprovalQueueDecisionHandler.java:244-249, 524-543` (`sourceBuild`, and the kind choice `retry ? KIND_RETRY : ...`): for a chained re-run, the record names the wrong kind and the wrong build.
  - Both plugins copy the earlier build's causes *before* adding their own. Rebuild's `constructRebuildCauses` copies every cause except `UserIdCause`/`RebuildCause`, then appends a new `UserIdCause` and `RebuildCause`. Naginator's `getCauseAction` copies causes except `NaginatorCause` (and the user cause), then appends the new `NaginatorCause` and a `UserIdCause`.
  - Rebuild of a build that was a Retry: the copied `NaginatorCause` makes `isAutomaticRetry` true. Because the Rebuild runs in the person's request, `userClickedRetry` is also true. The record then says "a Retry ... build #<the build that the earlier retry repeated>" instead of "a Rebuild ... build #<the rebuilt build>".
  - Retry of a build that was a Rebuild: `sourceBuild` returns the copied `RebuildCause`'s upstream build, which comes first in the list, rather than the retried build.
  - The refusal itself is correct. Only the audit text is wrong, against D-51a ("every such record names the re-run build").
  - Fix direction: take the kind and the build number from the submission's *own* re-run cause, which is the last `NaginatorCause`/`RebuildCause`/`ReplayCause` in the list: iterate in reverse in `sourceBuild` and in the kind choice. A copied cause is inherited, not this submission's.
  - Regression test: Given build #3 of an approval-required FreeStyle job, created by a naginator retry of #2. When user u clicks Rebuild on #3. Then the `TRIGGER_BLOCKED` record says "a Rebuild ... build #3 by 'u'", not "a Retry ... build #2".

## INFO
- [S-22-06] `store/BlockedAttemptAudit.java:256-262, 272-283, 314-319`: attribution in the shared overflow budget.
  - The budget is reachable only with 10,000 users who are all non-idle inside one 10-minute window.
  - Its summary and closing records carry the *first* overflow user's id in the `user` field and say "further users" in the text. A `?user=` history filter would therefore attribute the other users' attempts to that one user.
  - More generally, a summary names only its first target, and it lists up to 50 distinct `job #build` entries in the detail text, so counted attempts on other jobs do not appear under those jobs' job filter.
  - This is by design (counted, not listed), and it creates no new disclosure: change records are gated by `ViewHistory` as a whole (`HistorySection#getTarget`), not per job.
  - Optional: set `user` to `null` (or `*`) on overflow records.
  - Optional: print "..." only when `summaryCount` exceeds the number of *attempts* listed, not the number of distinct entries. The `LinkedHashSet` merges repeats of the same build, so "..." currently appears even when every attempt is listed.
- [S-22-07] `security/SystemBuildCheck.java:60-86, 105-110, 119`: cache hygiene. None of these can hide the warning for longer than the TTL.
  - `Cached.owner` is a strong static reference to a `Jenkins` instance. In a test JVM it keeps the previous instance alive until the next call. `BlockedAttemptAudit` uses a `WeakReference` for the same purpose; do the same here.
  - `invalidate()` has no caller in `src/main`.
  - A cache miss is not single-flight: concurrent renders after expiry each probe, and each uses a queue id and does a realm lookup.
  - The only way the cache can go stale is an authenticator that mutates in place, since the key compares authenticator identity and a save of the global security page replaces the instances. That is at most 5 minutes. A provider that returns new instances on every call makes the cache miss on every call (S-21-06 cost returns for that provider only).
- [S-22-08] Probe side effects: none, beyond the one documented. This answers the question asked.
  - Constructor chain: `FreeStyleProject(Jenkins,String)`, then `Project`, `AbstractProject`, `Job`, `AbstractItem`. It only sets fields: `doSetName`, `new RunMap<>(job)` (no base dir), `new DescribableList<>(this)`, `new NullSCM()`, an empty `CopyOnWriteList` of properties, and `Jenkins.getNodes().isEmpty()` for `canRoam`.
  - No `ItemListener` fires: `onCreated` is fired only by `ItemGroupMixIn`/`Jenkins.putItem`, and `onCreatedFromScratch`/`onLoad` are not called. No job-property descriptor is instantiated. `getRootDir`/`getConfigFile` are never called, so no config file is written. `Jenkins.getItem(PROBE_NAME)` cannot find it, because it is never put into `Jenkins.items`.
  - Nothing retains the objects after `probe` returns, so there is no leak (`FutureImpl(Task)` only stores the task).
  - `new Queue.WaitingItem(...)` takes one id from `QueueIdStrategy` (the default is an `AtomicLong`; a third-party strategy is handed the probe task). `Queue.Item#getCauses` runs any `TransientActionFactory<Queue.Item>` for the probe item.
  - Both side effects are at most once per cache period.

## Checked and found to be fine
1. Web methods: `grep -rn "public .* do[A-Z]" src/main/java` gives 42 matches, the same set as security-21. `git diff main..HEAD -- src/main | grep '^[+-].*\(do[A-Z]...(\|@RequirePOST\|@POST\|checkPermission\)'` is empty. I listed every match with its annotation and first statements (not sampled). Every state-changing method has `@RequirePOST` and a first-line check:
   - `doConfigSubmit`: Manage.
   - `JobActivationForm.doSubmit`: Request, then Item/Read on the item.
   - Activation, Request and Grant `doApprove`/`doReject`: Approve.
   - `doCancel`: `checkAnyPermission(Request, Manage)`, or the non-anonymous check plus the service check.
   - `doChangeApprover`: Request or RequestGrant.
   - `GrantsSection.doCreate`: RequestGrant.
   - Incident `doAcknowledge`/`doResolve`/`doComment`: ViewHistory. `doRerun`: Request.
   - `doRevoke`: Manage.
   - `JobRequestAction.doSubmit`: job Item/Read, a job-scoped check, then Request.
   - `doMigrate`/`doRevert`: Administer.

   The read-only `doSummary`, `doDynamic` and `doIndex` refuse non-GET and are gated by `getTarget()`. The `doCheck*` methods are `@POST`/`@RequirePOST` with `mayCheck()` or delegate to the parent. `doFilter` and `doRun` are not Stapler web methods. The new `isShowSystemBuildWarning` and `isBuildsMayRunAsSystem` are `is*` getters, which Stapler does not route, and the monitor is behind `AdministrativeMonitor`'s required permission.
2. Raw output: `grep -rn 'escapeXml="false"\|<j:out' src/main/resources` gives 0 matches. Both changed views declare `escape-by-default='true'`. The new blocks are constant text plus `${rootURL}`.
3. `ACL.SYSTEM2` / `ACL.as2`: the switch sites are unchanged (`IncidentItem:212`, `RunRequestService:753`, `ApprovalPolicy:65,80`, `ActivationService:660`), each with its reason comment. The one new `ACL.as2` is `SystemBuildCheck:133`, to `ANONYMOUS2`, which lowers privilege. The probe reads no item, so no permission check is needed before it.
4. Secrets: no new `getPlainText`/`Secret`/`Password` hit in the diff. The new record texts carry only the job full name, kind, build number, user id and timestamps.
5. Paths: no new `new File`/`Paths.get`/`resolve(` in the diff. The probe's `getRootDir` is never called.
6. CSV: the new record details are exported through `CsvWriter`, whose `FORMULA_STARTERS = "=+-@"` sanitisation applies to every cell. The new detail texts start with fixed words or a digit.
7. XStream: no new persisted type or field. `PersonBudget` and `Cached` are in-memory only, and change records use the existing JSONL format (ARCHITECTURE storage section).
8. Delegating strategy: `GrantAwareACL` has not changed since c6b79a7 (S-21-08 stands). No Grant path reaches Overall/Administer or Overall/RunScripts. I also confirmed that authorize-project's `SystemAuthorizationStrategy` needs `Jenkins.RUN_SCRIPTS` to be selected on a job (`hasAuthorizationConfigurePermission`, and the `newInstance` check), so a Configure window cannot set a per-job SYSTEM strategy. The null delegate and `getGroups` delegation are untouched.
9. Information disclosure: the detail-page warning names nothing and appears only to deciders or Manage holders (S-21-02 closed). A requester without either sees no block. `doFill*`/`doCheck*` and the dashboard are unchanged. A Discover-only user sees nothing new.
10. Concurrency and double approval: no state transition service changed. `recordPersonRefusal` and `flushPersonSummaries` are `synchronized`. `cached` is `volatile`, and a race between two probes self-heals because the key is the authenticator list. See S-22-04 for the work done under the lock.
11. D-51a budget soundness: the per-attempt records are at most 20 per user per rolling 10 minutes. `writes.clear()` runs only when a summary closes, and that happens at least 10 minutes after the last of the 20 writes. Per user, a flood costs at most 22 records per window. Evicting an attempt key only lets the same attempt write again, and that write still spends the budget. A budget is removed only when idle. Anonymous shares one budget. The key separator is `\u0001`, and `|`/`#` cannot appear in item names (`checkGoodName`).
12. Change control off: `buildsMayRunAsSystem()` returns `false` before probing, and the monitor's `isActivated()` short-circuits on the switch. The re-run recording sits inside the `approvalRequired` path, so existing Jenkins behaviour is unchanged (CLAUDE.md).
13. SpotBugs: unconfirmed. There is no `target/spotbugsXml.xml` in the worktree, and Maven was not run, as instructed. The diff adds no `@SuppressFBWarnings`.

## Request
- Request: docs/DECISIONS.md (humans). Amend D-50a: the instance-wide question should be "does a build of a job without its own build authorization and without a user cause get an identity that is neither SYSTEM nor exempt from the D-35b guard (native Configure or Administer in the parent strategy)?" Also decide whether a per-job strategy that runs as an administrator is only documented (S-22-01).
- Request: src/main/java/io/jenkins/plugins/batchcontrol/security/SystemBuildCheck.java (core-dev). S-22-01 (1): the privileged-identity check. S-22-02: an invalid probe name and a dual-context probe. S-22-07: a weak `owner`, single-flight on a miss, and removing or using `invalidate()`.
- Request: src/main/java/io/jenkins/plugins/batchcontrol/store/BlockedAttemptAudit.java and ops/ExpiryPeriodicWork.java (core-dev). S-22-03: shutdown flush, and isolating the flush from `expireOverdue`. S-22-04: close only the caller's own budget under the lock, move idle eviction to the periodic work, correct the Javadoc. S-22-06: overflow `user`.
- Request: src/main/java/io/jenkins/plugins/batchcontrol/queue/ApprovalQueueDecisionHandler.java (core-dev). S-22-05: take the kind and build from the last re-run cause.
- Request: src/main/resources/io/jenkins/plugins/batchcontrol/ops/BatchControlStrategyMonitor/message.jelly and action/GrantRequestItem/index.jelly (ui-dev). After the D-50a amendment, change the warning to "SYSTEM or a user who may change permissions" and the remedy to "a global default that runs builds as a user without Configure/Administer" (S-22-01).
- Request: docs/LIMITATIONS.md (release-manager). Item 35: qualify "the same guard as a manual one" (the build user must not hold Configure natively), add the per-job privileged-strategy case (S-22-01), note that name-, folder- or type-keyed authenticators are not judged (S-22-02), and note that an open refused re-run count can be lost on a crash (S-22-03).
- Request: src/test/** and docs/TEST-MATRIX.md (test-author). The regression rows under S-22-01 to S-22-05.
