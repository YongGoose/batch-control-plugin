# Security Review 26: final check of the security-25 fixes on part 5 (`fix/e2e-fresh-fd`)

Reviewer: security-reviewer. 2026-09-30.

Scope: `git diff 0dfaef7..02a745d -- src/main` (0dfaef7 is the security-25 report commit). There are 7 files, +137/-18:
- 18df2d5: S-25-01.
- 3fa882b: S-25-02, S-25-05, S-25-06.
- 6bde8c6: S-25-03.
- f6c6623: S-25-04.
- 3dc975e: S-25-07.
- 02a745d: S-25-09.

The test rows d0c22b9 (T-CFG-09, T-10-11) were read, but only to check coverage.

Standard: docs/HOSTING-CHECKLIST.md section B; docs/reports/security-25.md; DECISIONS D-23, D-47, D-53; CLAUDE.md.

Constraint: Maven was not run and `target/` was not touched, because a full build was running in this worktree (caller's instruction). SpotBugs is therefore unconfirmed.

Jenkins core behaviour cited below (queue folding, `Job.checkRename`, `UserMayOrMayNotExistException2`) was checked against `jenkins-core-2.568.3-sources.jar`.

## Summary: BLOCKER 0 / HIGH 0 / MEDIUM 0 / LOW 1 / INFO 4

Every security-25 finding that asked for a code change is closed, and none of the fixes opens a new gap. No web method lost `@POST`/`@RequirePOST` or its permission check. There is no new `ACL.SYSTEM2` switch, secret path, file path, CSV cell or persisted type.

The one LOW finding (S-26-01) was not introduced by this diff. It came up while answering the question "can the requester credit be forged some other way?". Core's queue folding can merge an approved submission into another queue item of the same job. The approval ticket is then spent, but the request never gets its own run. With S-25-01 in place, the merged run is correctly credited to the other user, so no credit is forged. What is lost is the approved request.

### Answer 1: can an attacker control `executedRunId`, or forge the requester credit another way?

No, for these reasons:
- **Where `executedRunId` is written.** Only `RunRequestService.markExecuted` (l.453-470) writes it. That runs under the service `lock` and only on the APPROVED -> EXECUTED transition, so it is written once.
- **Who calls `markExecuted`.** Only `RunRequestExecutionListener.onStarted` calls it, with the run's own `jobFullName#number`, for a run that carries an `ApprovedRunAction`.
- **Who can put the marker on a run.**
  - The marker is a queue action. No web endpoint, CLI command or Pipeline step accepts arbitrary actions. Only `submitApproved` (l.802) creates one.
  - While run control is on, the gate lets a marker pass only once (`consumeMarker`, l.368-411: `queuedAt`/`executedRunId`/EXECUTED refused, job bound).
  - While run control is off, a copied marker is not consumed at the gate. But `markExecuted` changes nothing unless the request is still APPROVED. A run that carries a copied marker exists only after the source run has started, and that start already moved the request to EXECUTED.
- **The run id cannot be ambiguous or reused.**
  - `#` is not allowed in item names (`Jenkins.checkGoodName`), so `jobFullName#number` has one reading.
  - Build numbers never go back without Administer-level tools.
  - `Job.checkRename` refuses to rename a job while it is building, so a run cannot be finalized under a different name than the one it started under.
  - A job that is deleted and re-created starts with no build that carries the old cause, so nothing can be copied from it.
- **Can a copied or forged `ApprovedCause` win?**
  - `ownApproval` (RunRecordListener l.105-119) accepts the cause only if the request it names has this exact run as `executedRunId`.
  - A Rebuild or Retry copies `ApprovedCause(R0)`, but R0's `executedRunId` is a different run, so the result is `null`. The run is then credited to the clicking user's `UserIdCause`, as USER, with no request id. T-10-11 covers this.
  - `linkResolvedRerun` uses the same test, so a copied cause can no longer link an incident either.
- **Folding.** Core's queue folding (`Queue.scheduleInternal` l.623-677) appends the incoming causes to an existing item.
  - If the approved item is queued first, its own `ApprovedCause` stays first, so `getCause` returns the right one.
  - If the other item is queued first, the approved submission's marker is dropped. No run then matches R's `executedRunId`, and nothing is credited to R's requester (see S-26-01).
- **Hardening only.** The requester comes from the cause rather than from the stored request, and only the first `ApprovedCause` is examined. Neither can be exploited, because only `submitApproved` ever builds an `ApprovedCause` for a given request id (S-26-04).

### Answer 2: does validating before the lock open a time-of-check/time-of-use problem?

Not a usable one.
- **Why the form's own values are safe.** `configure` (l.143-154) validates the local `form` object and then passes the same object to `bindWriteApply` under the monitor. `req.bindJSON(bound, form)` does not read the request again. So every value the POST carries is bound exactly as it was checked.
- **The realm answer.** Whether a user exists in the security realm is external state. No lock in Jenkins can pin it, and holding the monitor around the lookup was the S-25-02 problem.
- **The one gap: the switch fallback.** The only live state read outside the monitor is the fallback in `validateApprovers` (l.648-649). It uses `json.optBoolean("runControlEnabled", runControlEnabled)`, and the same for change control.
  - The UI form always sends both checkboxes, so this matters only for a hand-made POST by a `BatchControl/Manage` holder that leaves them out.
  - Suppose such a POST clears the approver list, and in the microseconds between the check and `synchronized (this)` another actor turns a switch on through JCasC or a script. The saved state is then "switch on, no approvers".
  - This gives no new capability. The direct setters (`setRunControlEnabled`/`setChangeControlEnabled`, l.191-248) never check the approver list, so an administrator can reach that state directly anyway. With an empty list, approvals fail closed.
  - Recorded as INFO (S-26-02), with a cheap fix: check again on `bound` under the monitor.
- **The copy under the monitor.** `bound` is copied from `this` under the monitor, so fields the form leaves out take the current values, not stale ones. The #19 order (bind, write, apply, record) is unchanged.

### Closure of security-25

| Finding | Status | Evidence |
|---|---|---|
| S-25-01 (MEDIUM) copied `ApprovedCause` credits the requester | Closed | `RunRecordListener.ownApproval` l.105-119 is used by `buildRecord` (user, `classify`, `runRequestId`) and by `linkResolvedRerun`. A failed read is logged and gives `null` (the run falls back to its own causes). This is the only other `getCause(ApprovedCause.class)` call in `src/main` (grep). T-10-11 drives a real Rebuild. |
| S-25-02 (LOW) unbounded realm lookups under the monitor; oracle | Closed | IDs are de-duplicated (`LinkedHashSet`) and more than `MAX_APPROVERS` = 100 is an error before any lookup (l.664-667). The loop stops asking after `MAX_UNKNOWN_REPORTED` = 5 unknown ids (l.677-681). `validate`/`validateApprovers` run before `synchronized (this)` (l.148-153). The optional "realm lookup for Administer only" was not taken, which security-25 left optional. The oracle remains for Manage holders, bounded to 100 ids per POST. Residuals: S-26-03. |
| S-25-03 (LOW) disk read under the queue lock; exception escapes | Closed | `ActivationService.holdEpoch` (l.152-167) reads `Cached.deactivatedAtMillis`. It fills the cache via `isActivated` only when there is no entry, and `mayRunUnattended` has just filled it. It catches `RuntimeException` and returns `unknown`. The gate calls it at `ApprovalQueueDecisionHandler.java:499`. Every `setCached` goes through `Cached.of` or `NOT_ACTIVATED` (l.751-762). |
| S-25-04 (INFO) `swapStoreForTesting` in production | Closed | `BlockedAttemptAudit.java:101-109`: throws `IllegalStateException` unless `hudson.Main.isUnitTest`, and logs a WARNING that names the replacement class. Only code that already runs in the JVM can set `isUnitTest`. |
| S-25-05 (INFO) `UserMayOrMayNotExistException2` reported as unknown | Closed | It is caught before `UsernameNotFoundException` and gives UNCHECKED (l.725-728). Core confirms it is a subclass (`UserMayOrMayNotExistException2 extends UsernameNotFoundException`). |
| S-25-06 (INFO) draft `save()`; `getView` null | Closed | `save()` does nothing when `candidate` (l.108-115). It is `synchronized`, like `Descriptor.save()`. `BatchControlConfigurationLink.java:135-138` throws the original `FormException` when the view is `null`. |
| S-25-07 (INFO) class names in STRATEGY_CHANGE | Closed | `BatchControlStrategyMonitor.java:214-218` uses descriptor display names only. |
| S-25-08 (INFO) D-54 mail volume | Open (optional) | Not touched. Still bounded by `QUEUE_CAPACITY` and `BatchControl/Request`. |
| S-25-09 (INFO) test coverage | Partly closed | T-CFG-09 (non-Manage gets 403, a GET is refused) and T-10-11 (Rebuild attribution) were added. In code, `doCheckApproversText` now calls `checkPermission(MANAGE)`, so a caller without Manage gets 403 instead of `ok`, and it stays `@POST` (l.746-752). No rows yet for the 100 cap, the 5-unknown stop, S-25-03, S-25-05 or the S-25-04 guard (S-26-05). |

## BLOCKER (grounds for hosting rejection)
None.

## HIGH
None.

## MEDIUM
None.

## LOW
- [S-26-01] `queue/ApprovedRunAction.java:19`, `policy/RunRequestService.java:800-805`, `queue/ApprovalQueueDecisionHandler.java:139-151`: an approved submission can be folded into another queue item of the same job. Its ticket is spent, and the request never runs as itself. This predates this diff.
  - The problem:
    - `Queue.schedule2` asks the decision handlers first. The gate calls `consumeMarker`, which sets `queuedAt`, and returns true.
    - Then `Queue.scheduleInternal` (core l.623-677) looks for an item of the same task that no `QueueAction` separates from the new one. `ApprovedRunAction` is not a `QueueAction`. The only `QueueAction` in the approved submission is the `ParametersAction`, and only when the job has parameters.
    - So an item that is already waiting, blocked or buildable for a parameterless job (or for the same parameter values) counts as a duplicate. Only the `FoldableAction`s are merged into it: the `CauseAction` carrying `ApprovedCause(R)`. The marker is discarded, and `scheduleBuild2` returns the existing item, not `null`.
    - Result: no run ever carries R's marker, so R is never marked EXECUTED. It stays APPROVED with a spent ticket until `expire` ends it as "approved but not started".
    - The other item's run shows "Approved batch run request R" among its causes. Thanks to S-25-01 it is credited to its own user, as USER, with no request id. Without S-25-01 it would have been credited to R's requester.
    - After a restart inside the timeout, `recoverUnderQueueLock` finds no run carrying the marker (`hasRunFor`), issues a new ticket and submits R once more. That is R's first own run, so it is not a double execution of R.
  - Reach:
    - It needs an item of the same job already in the queue when R is approved.
    - That can be a timer or upstream build of an activated job waiting for an executor, which happens without any attacker.
    - It can also be a build started by any Item/Build holder on a job without `approvalRequired` (D-47), started just before the approval.
    - A person's submission cannot fold into an approval-required job, because the gate refuses it before scheduling.
  - Why LOW:
    - Permissions are not bypassed. The item that absorbs R already passed the gate on its own merits.
    - Attribution is correct after S-25-01.
    - The damage is that an approved request is lost (availability), plus one misleading cause line on another user's run.
  - Basis: checklist B-10 (state transitions; D-23 "the marker authorizes exactly one queue entry"); SPEC section 4 (APPROVED -> EXECUTED).
  - Fix direction:
    - Make `ApprovedRunAction` implement `hudson.model.Queue.QueueAction` with `shouldSchedule(List<Action>)` returning `true`. The check runs in both directions (core l.626-631), so an approved submission never folds into another item and nothing folds into it.
    - Optionally, in `submitApproved`, treat a `ScheduleResult` that is not `Created` as "not scheduled" and give the ticket back (clear `queuedAt`) under `lock`.
  - Regression test (JenkinsRule):
    - Given run control on, and a parameterless job without `approvalRequired` that is activated.
    - Given `j.jenkins.setNumExecutors(0)`, and u2's build of the job waiting in the queue.
    - When u1's request for the job is approved.
    - Then the queue holds two items of the job, and one of them carries `ApprovedRunAction(R)`.
    - After the executors are restored and the queue drains: R is EXECUTED, its `executedRunId` names the run that carries the marker, that run's record names u1 as APPROVED_REQUEST, and u2's run names u2 as USER with no request id.

## INFO
- [S-26-02] `config/BatchControlGlobalConfiguration.java:648-649`: the empty-list rule uses the live switch values, read outside the monitor, when the POST leaves the switches out.
  - The time-of-check/time-of-use analysis is Answer 2. It gives no new capability, because direct setters never check approvers.
  - Fix: in `bindWriteApply`, under the monitor, refuse with the same `FormException` when `bound.approvers.isEmpty() && (bound.runControlEnabled || bound.changeControlEnabled)`. This needs no realm call, so it cannot hold the monitor for long.
- [S-26-03] `config/BatchControlGlobalConfiguration.java:365-367,664-697`: the residual bounds of S-25-02.
  - The cap counts distinct ids, but `setApproversText`/`setApprovers` store the list without de-duplication. One id repeated any number of times passes validation and is persisted as a long list. It is then iterated by every approver check.
  - The id length is not limited before it goes to the realm.
  - UNCHECKED answers (realm error or timeout) do not stop the loop the way UNKNOWN answers do. With an unreachable LDAP, one POST can wait on up to 100 timeouts. That now happens outside the monitor, on one request thread, and needs Manage.
  - Fix:
    - De-duplicate in both setters.
    - Refuse ids longer than, for example, 256 characters.
    - Stop asking the realm after the first UNCHECKED answer (an unreachable realm will not answer the next id either), and report the rest as "not checked".
- [S-26-04] `listener/RunRecordListener.java:86-90,105-114`: take the credited user from the stored request, not from the cause.
  - `ownApproval` already loads the request. Crediting `request.getRequester()` would make the record independent of what the cause object says.
  - The same applies to iterating over all `ApprovedCause`s rather than `getCause` (the first one).
  - Neither can be exploited today (Answer 1). This is defence in depth for the case where another plugin one day builds or copies causes in a different order.
- [S-26-05] Test coverage of the remaining security-25 fixes. There are no rows for:
  - the 100-id cap and the 5-unknown early stop (the realm counts its calls);
  - S-25-05 (a realm that throws `UserMayOrMayNotExistException2` gives a warning, and the save goes through);
  - S-25-03 (the activation-state file cannot be read: the trigger is refused, one TRIGGER_BLOCKED record is written, and no exception leaves `shouldSchedule`);
  - S-25-04 (`swapStoreForTesting` throws when `Main.isUnitTest` is false);
  - S-25-06 (`save()` on a draft does not change `config.xml`).
  - T-CFG-09's GET branch asserts only `>= 400`, which is enough.

## Checked and found to be fine
1. **Web methods.** `grep -rn "public .* do[A-Z]" src/main/java` gives 43, the same as security-25. In the diff, the only changed web method is `doCheckApproversText`.
   - It is `@POST`, and its first statement is `Jenkins.get().checkPermission(BatchControlPermissions.MANAGE)`, which fits a global configuration descriptor.
   - `doConfigSubmit`, `doMigrate` and `doRevert` are not touched.
   - `configure` is still reached only through `doConfigSubmit`, which keeps `@RequirePOST` and `checkPermission(MANAGE)`. Dropping `synchronized` from its signature does not change who can call it.
2. **Raw output.** `grep -rn 'escapeXml="false"\|<j:out' src/main/resources` gives 0. The diff touches no view.
   - The new messages ("at most 100 approvers", "the remaining ids were not checked") are constant text plus quoted ids.
   - They reach the page through `FormValidation.error` (escaped) or through `FormException` -> `${saveError}` in an `escape-by-default='true'` view.
3. **`ACL.SYSTEM2` / `ACL.as`.** No match among the diff's `+`/`-` lines.
   - `ownApproval` calls `RunRequestService.load`, a plain store read with no permission logic. It runs in a `RunListener`, so no user decision depends on it.
4. **Secrets.** `getPlainText`, `Secret` and `Password` give 0 matches in the diff.
   - The new WARNING logs name only a request id and a run id (RunRecordListener l.115-116), or a store class name (BlockedAttemptAudit l.107).
5. **Paths.** `new File`, `Paths.get` and `resolve(` give only the realm helper `resolve(String id)`, which touches no path.
6. **CSV.** No new column or cell source. After S-25-01, a Rebuild row carries fewer values (no request id), not more. `CsvWriter` sanitising is unchanged.
7. **XStream.** No persisted type changed.
   - `Cached` is an in-memory record.
   - `candidate` stays `transient`.
   - The STRATEGY_CHANGE `details` string is just shorter.
8. **Delegating strategy.** `security/` is not in the diff. The null-delegate handling, `getGroups` delegation and grant-to-Administer paths are unchanged since security-25.
9. **Information disclosure.**
   - Non-Manage callers now get 403 from `doCheckApproversText`, not a result. That is the same answer as the page itself.
   - STRATEGY_CHANGE no longer shows class names.
   - A Discover-only user sees nothing new.
   - Rebuild rows no longer expose the original request id to History readers.
10. **Concurrency.**
    - `holdEpoch` reads a `ConcurrentHashMap`-style cache without taking `lock` or the queue lock, and never blocks.
    - `saveState` resets the entry before writing, so for the length of one write the key reads `never-activated`. That can only split one refusal record in two. It never merges a record across a HOLD, and the result is fail-closed.
    - `save()` synchronises on `this`, the same monitor as `bindWriteApply` and the setters. Nothing takes that monitor and then another lock, so no deadlock was found.
    - A request still cannot be approved twice, or marked executed twice (`markExecuted` checks APPROVED under `lock`).
11. **Change control off.** `RunRecordListener` still returns before `ownApproval` when `ChangeRecording.isActive()` is false. With run control off, `shouldSchedule` returns before `holdEpoch`.
12. **SpotBugs.** Unconfirmed. Per the caller's instruction, Maven was not run and `target/` was not read. The diff adds no `@SuppressFBWarnings`, and the new `ownApproval` has `@CheckForNull`.

## Request
- Request: src/main/java/io/jenkins/plugins/batchcontrol/queue/ApprovedRunAction.java (core-dev). Implement `Queue.QueueAction` so that an approved submission is never folded into, or folded with, another queue item (S-26-01).
- Request: src/main/java/io/jenkins/plugins/batchcontrol/config/BatchControlGlobalConfiguration.java (core-dev).
  - Check the empty-list rule again on `bound` under the monitor (S-26-02).
  - De-duplicate in `setApproversText`/`setApprovers`, cap the id length, and stop asking the realm after the first UNCHECKED answer (S-26-03).
- Request: src/main/java/io/jenkins/plugins/batchcontrol/listener/RunRecordListener.java (core-dev). Credit `request.getRequester()` from the loaded request (optional, S-26-04).
- Request: src/test/** and docs/TEST-MATRIX.md (test-author). Add rows for S-26-01, and for the untested security-25 fixes listed in S-26-05.
