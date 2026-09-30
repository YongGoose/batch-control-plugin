# Security Review 24: final check of e2e-run3 part 4 (`fix/e2e-run3-part4`)

Reviewer: security-reviewer. 2026-09-30.

Scope: `git diff f24b0a3..HEAD -- src/main` (4 files), covering:
- b155a1a (S-23-03, S-23-06): `store/BlockedAttemptAudit.java`
- 8e2db39 (S-23-07): `queue/ApprovalQueueDecisionHandler.java`
- dbc3647 (S-23-02): `ops/BatchControlStrategyMonitor/message.jelly`, `action/GrantRequestItem/index.jelly`

Standard: docs/HOSTING-CHECKLIST.md section B; SPEC l.107 (D-51, D-51a); docs/reports/security-23.md; CLAUDE.md.

Constraint: a `mvn clean verify` was running in the worktree, so Maven was not run and `target/` was not touched.

## Summary: BLOCKER 0 / HIGH 0 / MEDIUM 0 / LOW 1 / INFO 4

S-23-03, S-23-06 and S-23-07 are closed. S-23-02 (UI part) is closed as well. One new LOW gap: after the shutdown flush, the per-user budget no longer applies (S-24-01). The answers to the questions asked:

1. **Can the retry list duplicate records?** Not inside the process. Every record is in exactly one place at a time: the local `closing` list of one flush, or `pendingClosing`. Both `flushPersonSummaries` (l.340-341) and `closeAllOpen` (l.392-393) drain `pendingClosing` under the monitor. `appendOrKeep` puts a record back only after its own append failed. Two concurrent flushes therefore never both hold the same record. The only way to duplicate is below the plugin: `Files.write` throws after the line reached the disk (a close or flush error). Then the retry writes a second line with the same `id` (S-24-03, INFO).
2. **Can it grow without bound?** No. `appendOrKeep` adds only while `pendingClosing.size() < MAX_PENDING_CLOSING` (1,000). A record is only ever re-added, never copied, so the bound holds across repeated failures. A closing record lists at most 50 builds (`addBuild`), so the list stays in the low megabytes at worst. A record beyond the bound is only in the WARNING log. The log text then wrongly says "it is retried" (S-24-02).
3. **Can it hold the lock during I/O?**
   - In `flushPersonSummaries` (Timer thread): no. The append in `appendOrKeep` runs without the monitor. The monitor is taken only around `pendingClosing.add` on failure.
   - In `recordPersonRefusal`: yes, but this is the design that S-22-04 accepted. It runs `synchronized` under the queue lock, and still does at most one closing append plus the caller's own record. The retry adds no I/O there, because pending records are retried only by the per-minute flush.
   - `closeAllOpen`: appends outside the monitor, as before.
   - The lock order (queue lock, then audit monitor, then store stripe lock) is unchanged. `appendOrKeep` takes the monitor again only after the stripe lock has been released (`appendLine`'s `finally`), so no cycle is possible.
4. **Can the S-23-07 refusal block a legitimate run?** No. The paths:
   - **An approved request's run.** It carries `ApprovedRunAction`. Step 1 (l.140-152) returns `consumed` before step 5 is reached, whatever causes it carries.
   - **A Replay by a person on a job without `approvalRequired`.** Step 5 is inside `if (approvalRequired)` (l.154-293), so it never applies. `isHumanSubmission` treats the Replay as a person's and returns `true` (l.294-299). A SYSTEM Replay on such a job goes to steps 6-8 (activation), as before.
   - **A real Replay on an approval-required job.** Its own last re-run cause is the `ReplayCause`, so step 2 refuses and records it first, as before.
   - **What step 5 can now see with a `ReplayCause`.** A submission whose last re-run cause is a naginator or Rebuild cause after a copied `ReplayCause`:
     - A Rebuild adds the clicking user's `UserIdCause`, so step 4 refuses it first (security-23 question 4).
     - What is left is an automatic naginator retry of a replayed build. Naginator strips the copied `UserIdCause`, so the `ReplayCause` is the only proof that a person started the run. Refusing it is the same rule D-47 applies to a copied `UserIdCause` or `ApprovedCause`. The replayed build can only exist if the Replay happened while the job was not approval-required, because step 2 refuses it otherwise.
   - **Upstream-triggered builds of a replayed upstream run.** They carry only an `UpstreamCause` at the top level. The upstream causes are nested inside it, and `collectCauses` (l.622-630) does not flatten them, so they are unaffected.

   With the supported plugins the new clause is unreachable today: naginator retries only `AbstractBuild`s, and Replay exists only for Pipeline. It is defence in depth, as requested.

### Closure of security-23

| Finding | Status | Evidence |
|---|---|---|
| S-23-02 (LOW) remedy text recommends a triggering-user strategy on its own | Closed (views) | Both views now recommend "Run as Specific User" with a low-privilege account. They say that a triggering-user strategy is safe only together with such a fallback, because of timer and SCM builds. The text is constant and `escape-by-default='true'` is kept. LIMITATIONS 35 was updated in 5849c83 (docs, out of this scope). |
| S-23-03 (LOW) closing count lost on a store failure | Closed | (a) `flushPersonSummaries` appends each record through `appendOrKeep` (l.355-357), so one failure no longer drops the records after it. (b) `recordPersonRefusal` routes its own closing record through `appendOrKeep` (l.284-287), which catches the failure, so the caller's per-attempt record is still written. A failed record's full text is logged at WARNING and kept for the next flush, which retries it first. Not covered by a test: TEST-MATRIX note 166 explains that there is no store seam for a failure injected once. See S-24-04. |
| S-23-06 (INFO) summary opened after the terminator is never closed | Closed (residual S-24-01, S-24-05) | `closeAllOpen` sets `shutDown` inside the same `synchronized` block that closes every open summary (l.390-400). While it is set, `recordPersonRefusal` writes a per-attempt record and never opens a summary (l.289-293), so no summary can be open after the terminator. |
| S-23-07 (INFO) copied Replay cause not treated as a manual origin at step 5 | Closed | `ApprovalQueueDecisionHandler.java:279-280` adds `REPLAY_CAUSE_CLASS` to the step-5 test. Question 4 shows that no legitimate path is affected. The record says "re-uses an earlier manual run", which is accurate for a Replay. No test row (see S-24-04). |

## BLOCKER (grounds for hosting rejection)
None. No web method, permission check or `ACL.SYSTEM2` switch changed. Nothing confers a permission.

## HIGH
None.

## MEDIUM
None.

## LOW
- [S-24-01] `store/BlockedAttemptAudit.java:289-293`: after the shutdown flush, the D-51a per-user budget no longer applies.
  - The problem:
    - The S-23-06 branch appends a per-attempt record for every refusal once `shutDown` is set. It does not check `budget.writes.size() < PERSON_BUDGET` and does not honour the shared `OVERFLOW_USER` budget.
    - Only the one-minute merge per attempt key (l.258-262) still applies.
    - Before b155a1a, a user over budget in that window only incremented a counter. Now every distinct attempt (job, kind, build number) is one synchronous JSONL append under the global queue lock. That is the self-inflicted denial of service the class comment and D-51a bound (S-21-05).
  - Reach:
    - The window runs from the plugin's `@Terminator` until the queue and HTTP stop.
    - Other terminators run in it too. workflow-cps suspends the running Pipelines there, for example. So the window can last seconds to tens of seconds.
    - A person with Item/Build on jobs with many builds can POST Retry or Rebuild on each build in that window.
  - Why only LOW: it needs a shutdown in progress, and it is bounded by the number of distinct builds the person can re-run. Each append is one small line. The refusal itself stands.
  - Basis: D-51a; SPEC l.107; checklist B-10 (concurrency, work under the queue lock).
  - Fix direction:
    - Keep the budget after shutdown. If `writes.size() < PERSON_BUDGET` and the key is not the overflow key, add to `writes` and append as usual.
    - Otherwise log the refusal at INFO and return `false`, without opening a summary. No closing record could follow, so the log is the only honest place.
    - Optionally write one record per user saying "counting stopped at shutdown" when the budget is first exceeded after `shutDown`.
  - Regression test (unit, needs a seam to call the terminator): Given change control on and `flushAtShutdown()` already called. When user u is refused 30 distinct re-runs within a minute. Then at most `PERSON_BUDGET` (20) records by u are appended, no summary record is written, and the rest are only in the log.

## INFO
- [S-24-02] `store/BlockedAttemptAudit.java:368-374`: the WARNING says "it is retried on the next flush" in two cases where it is not.
  - When `pendingClosing` is full, the record is dropped.
  - After `shutDown`, a record re-kept by a flush that fails late has only a Timer flush before `Jenkins.cleanUp` stops the timer.
  - Also, while the store keeps failing, every flush logs up to 1,000 WARNINGs with a stack trace per minute.
  - Fix direction:
    - Say "dropped (the pending list is full)" when it is not kept.
    - Log the stack trace only for the first failure of a flush, and the others at FINE or as a count.
- [S-24-03] `store/FileStore.java:1076-1090` together with the retry: a write that fails after the bytes reached the file is retried in full.
  - If `Files.write` throws on close, the line is written twice with the same `id`.
  - If it fails midway (for example the disk is full), a partial line without a newline stays in the file. The retried line is then appended to that partial line, so the combined line is invalid JSON and `parseLines` skips it. The count is then lost, though it is still in the WARNING log.
  - This is the store's general behaviour, not new with this commit. The retry only makes it reachable for closing records.
  - Optional fix direction: when reading, keep the first record per `id`. When appending, start with a newline if the file does not end in one.
- [S-24-04] Test coverage. No row exercises the retry of S-23-03, the no-summary-after-shutdown rule of S-23-06 or the step-5 Replay rule of S-23-07. T-06-84 covers the clean-shutdown closing record only.
  - TEST-MATRIX note 166 explains why S-23-03 has no row: `FileStore.get()` has no replacement seam.
  - A package-private test hook on `BlockedAttemptAudit` would let a unit test cover S-23-03, S-23-06 and S-24-01: a `Store` supplier, or calling `closeAllOpen` directly.
  - S-23-07 is unreachable with the real plugins (question 4), so a row would need a synthetic cause list in a unit test of `shouldSchedule`.
- [S-24-05] `store/BlockedAttemptAudit.java:388-401` with `:460-470`: `closeAllOpen` does not call `forgetOtherInstance()`.
  - `ExpiryPeriodicWork.doRun` flushes only after `StartupRecovery` has completed. Suppose Jenkins stops before any refusal or flush has set `trackedFor`, which is within about the first minute after start. Then the first refusal after the terminator runs `forgetOtherInstance`. It sees the current instance differ from the empty reference and resets `shutDown = false`.
  - A user who then goes over budget in the shutdown window opens a summary that nothing closes, which is S-23-06 again.
  - This needs a shutdown within about a minute of start, with no refusal before it and 21 refusals after it. The effect is one summary record without its count.
  - Fix direction: call `forgetOtherInstance()` first inside the `synchronized` block of `closeAllOpen`, before `shutDown = true`.

## Checked and found to be fine
1. **Web methods.** `grep -rn "public .* do[A-Z]" src/main/java` gives 42 matches, the same as security-23. The diff has no `+` or `-` line with `do*(`, `@RequirePOST`, `@POST` or `checkPermission`.
2. **Raw output.** `grep -rn 'escapeXml="false"\|<j:out' src/main/resources` gives 0 matches. Both changed views keep `<?jelly escape-by-default='true'?>`. The new text is constant, plus the existing `${rootURL}`.
3. **`ACL.SYSTEM2` / `ACL.as`.** No such line in the diff. The step-5 change reads only the cause class names.
4. **Secrets.** No `getPlainText`, `Secret` or `Password` in the diff. The WARNING logs only the closing record's detail: user id, kind, job names, build numbers and instants, which is the text already stored in the audit history.
5. **Paths.** No `new File`, `Paths.get` or `resolve(` in the diff. Retried records use the existing `appendChangeRecord`, which writes into the month bucket of the record's own `at`, through `PathCodec.resolveUnder`.
6. **CSV.** No new text shape. The closing and per-attempt texts are unchanged and go through `CsvWriter`'s `=+-@` sanitising.
7. **XStream.** `pendingClosing` and `shutDown` are in memory only, and no new persisted type was added. The records are the existing JSONL `ChangeRecord` (ARCHITECTURE storage section).
8. **Delegating strategy.** Not touched by the diff (`security/` unchanged since security-23). No Grant path to Overall/Administer, and the null-delegate and `getGroups` handling is unchanged.
9. **Information disclosure.** The views' text is fixed and still shown only to deciders and Manage holders. `doFill*`, `doCheck*` and the dashboard are unchanged. A Discover-only user sees nothing new.
10. **Concurrency.**
    - `shutDown` and `pendingClosing` are read and written only while the audit monitor is held:
      - `recordPersonRefusal` is `synchronized`.
      - `flushPersonSummaries` and `closeAllOpen` use `synchronized (this)` blocks.
      - `appendOrKeep`'s add is inside `synchronized (this)`.
      - `forgetOtherInstance` is only called with the monitor held.
    - Double closing is still impossible, because `close()` runs only under the monitor and clears `summaryUntil`.
    - No state-transition service changed, so double approval is unaffected.
11. **Retry ordering.** Pending records keep their original `at` (`ChangeRecord.create` at close time), so the history pages, which sort by time, show them at the right place, and they go into the right month file even across a month boundary. Only the raw JSONL order differs (as S-23-05).
12. **Instance change.** `forgetOtherInstance` clears `pendingClosing` and resets `shutDown` when the Jenkins instance changes, so a restart in the same JVM (JenkinsSessionExtension) starts clean. See S-24-05 for the one case where that reset comes too late.
13. **Change control off.** `shouldSchedule` returns at l.126-128 before any step. The budget map, the pending list and the flag are touched only by refusals on the `approvalRequired` path, so existing Jenkins behaviour is unchanged (CLAUDE.md).
14. **SpotBugs.** Unconfirmed. `target/spotbugsXml.xml` does not exist, because the build is running and Maven was not run. The diff adds no `@SuppressFBWarnings`. Every access to the new fields happens while the monitor is held (item 10), so no IS2_INCONSISTENT_SYNC finding is expected.

## Request
- Request: src/main/java/io/jenkins/plugins/batchcontrol/store/BlockedAttemptAudit.java (core-dev).
  - Keep the per-user budget after `shutDown`: beyond it, log only (S-24-01).
  - Correct the WARNING text when a record is dropped, and limit the stack traces during a persistent failure (S-24-02).
  - Call `forgetOtherInstance()` at the start of `closeAllOpen` (S-24-05).
  - Optional: a package-private `Store` seam for tests (S-24-04).
- Request: src/main/java/io/jenkins/plugins/batchcontrol/store/FileStore.java (core-dev). Optional: keep the first record per `id` when reading, and start an append with a newline after a torn line (S-24-03).
- Request: src/test/** and docs/TEST-MATRIX.md (test-author). Add the rows for S-24-01, and for S-23-03 and S-23-06 once a seam exists. Optionally add a unit row for S-23-07 with a synthetic cause list (S-24-04).
