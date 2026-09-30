# Security Review 23: re-review of the security-22 fixes, e2e-run3 part 4 (`fix/e2e-run3-part4`)

Reviewer: security-reviewer. 2026-09-30.

Scope:
- Primary: `git diff 1947148..HEAD -- src/main`, covering b83c254 (D-50b), d711d77 (S-22-03/04/06) and aa6761e (S-22-05). The views changed in 983dc84 are included.
- Re-scan: the whole part-4 diff, `git diff main..HEAD -- src/main` (11 files).

Standard: docs/HOSTING-CHECKLIST.md section B; SPEC lines 107 (D-51, D-51a) and 151 (D-50, D-50a, D-50b); DECISIONS D-35b, D-35d, D-50..D-51a; LIMITATIONS 13 and 35; CLAUDE.md.

Constraints: a `mvn clean verify` was running in the worktree, so Maven was not run and `target/` was not touched. Third-party behaviour was read from the local jars:
- jenkins-core 2.568.3 sources
- role-strategy 898.vc050ed2424ca_ sources
- workflow-cps 4383.v04fa_a_3d67b_d9 sources
- naginator 1.556.v14d723a_109a_c bytecode (`javap`)

## Summary: BLOCKER 0 / HIGH 0 / MEDIUM 0 / LOW 3 / INFO 5

Every security-22 finding is closed, either by code or, where the fix direction offered documentation as an alternative, by documentation (table below). The answers to the five questions asked are:

1. **Is the root-ACL question right for both strategies?** For the matrix subclass it is right for the common case: a global matrix entry, which is what SPEC line 151 asks. It does not see Configure given to the build account on a folder or job (S-23-01, LOW). For the role-based subclass the question only reads global roles, not item roles. That is harmless, because under role-strategy a matrix authorization property confers nothing and the D-35b guard is inert, so the exposure class does not exist there. The warning can only over-warn, which is the safe direction.
2. **Can the `@Terminator` deadlock, or run after the store has closed?** No deadlock: it takes the audit monitor only while collecting and appends with no lock held, so no lock cycle is possible. The store has no closed state: `FileStore` resolves `Jenkins.get().getRootDir()` on every call. Terminators run first in `Jenkins.cleanUp` (`Jenkins.java:3650`), before `theInstance = null` (l.3696). Residual: a summary opened after the terminator is never closed (S-23-06, INFO).
3. **Can the per-minute flush lose or duplicate records?** No duplicates: `close()` runs only under the monitor and clears `summaryUntil`. Nothing is lost in normal operation. On a store failure, a count is lost, because the budget is reset before the append (S-23-03, LOW). A race with the queue-lock path can change the order in the file, but not the content (S-23-05, INFO).
4. **Does the step-2 Replay change let a re-run escape the Replay refusal?** No, with the plugins this plugin supports.
   - A Rebuild of a replayed build always adds its own `UserIdCause`, so step 4 refuses it and records it as a Rebuild.
   - A naginator retry of a Pipeline build cannot exist: `NaginatorCause(AbstractBuild)` and `RunListener<AbstractBuild>`.
   - A Replay has only its own `CauseAction`: `ReplayAction.run2`, l.337, with `COPIED_ACTIONS` = parameters and SCM revision.
   - The replayed script is not carried forward: `ReplayFlowFactoryAction.create` nulls it.
   - A defence-in-depth gap is noted as S-23-07 (INFO).
5. **Are the `do*` methods unchanged?** Yes (checked item 1 below).

### Closure of security-22

| Finding | Status | Evidence |
|---|---|---|
| S-22-01 (HIGH) a privileged build identity counts as safe | Closed (residual S-23-01) | `SystemBuildCheck.probe` l.144-153: after a non-SYSTEM identity, the probe asks `GrantLayer.hasPermissionWithoutGrants(jenkins, identity, ADMINISTER \| Item.CONFIGURE)`, the parent-ACL question of D-35d (1). This matches SPEC l.151 ("root-level Item/Configure"). T-08-63 and T-08-64 cover rows a and b. LIMITATIONS 35 now documents the per-job privileged-strategy case. Both views say "SYSTEM or an account that already has Configure permission". |
| S-22-02 (LOW) probe representativeness | Closed | `PROBE_NAME = "batch-control:system-build-probe"`. `:` is in `Jenkins.checkGoodName`'s reject set (`Jenkins.java:4300`), so no item can take the name. The dual-context probe was not adopted. Instead, authenticators keyed by type, folder or caller are a documented limitation (D-50b, LIMITATIONS 35), which is acceptable. |
| S-22-03 (LOW) the closing count is lost at restart | Closed (residuals S-23-03, S-23-06) | The flush now runs first in `ExpiryPeriodicWork.doRun`, in its own `try`. `@Terminator flushAtShutdown` → `closeAllOpen` writes every open summary, with "the window ended early because Jenkins was shutting down" and a try per record. Crash loss is documented in LIMITATIONS 13. |
| S-22-04 (LOW) work done under the queue lock | Closed | `recordPersonRefusal` no longer walks the budget map. It closes only the caller's own expired summary (at most one extra append), and idle eviction on insert is gone. `flushPersonSummaries` collects under the monitor and appends outside it, on the Timer thread. The Javadoc is corrected. There is no regression test (S-23-08). |
| S-22-05 (LOW) chained re-runs named wrongly | Closed | `lastRerunKind` and `sourceBuild` iterate in reverse, and the kind is taken from the submission's own cause at steps 2, 4 and 5. Covered by T-06-82 and T-06-83. |
| S-22-06 (INFO) overflow attribution and "..." | Closed | `summaryListed` counts listed attempts, so repeats of a listed build count. The overflow `user` field is kept and documented in the `close` Javadoc. That was optional. |
| S-22-07 (INFO) cache hygiene | Closed | `Cached.owner` is a `WeakReference`, and the unused `invalidate()` is removed. The cache miss is still not single-flight, which is accepted: at most one probe per concurrent render after expiry. |
| S-22-08 (INFO) probe side effects | Unchanged | The same constructor chain and one queue id per cache period. Now there is also one realm lookup via `hasPermission2` group resolution per probe. |

## BLOCKER (grounds for hosting rejection)
None. No web method was added, removed or changed. No permission check was weakened. No new `ACL.SYSTEM2` switch was added. Nothing new confers a permission.

## HIGH
None.

## MEDIUM
None.

## LOW
- [S-23-01] `security/SystemBuildCheck.java:152-153` and `docs/LIMITATIONS.md` item 35 ("an account which already holds `Overall/Administer` or `Item/Configure` in the installed strategy ... the administrative monitor on Manage Jenkins says so"). The problem:
  - The probe asks the **root** ACL, but the D-35b guard exempts an identity whose native Configure comes from the **item** ACL, inherited ACLs included (`GrantViolationGuard.java:152`: `hasPermissionWithoutGrants(item, auth, Item.CONFIGURE)`).
  - Under the matrix subclass, a build account given Item/Configure by a folder's or job's `AuthorizationMatrixProperty`, and not globally, is exempt from the guard on those jobs. The probe still reports "safe", so the monitor and the approver's page stay silent.
  - LIMITATIONS 35 promises more than the code does. SPEC l.151 says "root-level", so the code matches the SPEC and the documentation overstates it.
  - Why only LOW:
    - The folder or job entry for the build account has to be made by someone who already holds native Configure there. A CONFIGURE window holder who adds it is reverted by the guard, because their Configure comes only from a grant.
    - This is the same class as the per-job privileged strategy that D-50b and LIMITATIONS 35 already accept as undetectable instance-wide.
  - Role-based subclass: root means global roles only (`RoleBasedAuthorizationStrategy.java:223`), and item roles are not read (l.263). That is not a gap, because under role-strategy the matrix property confers nothing and the guard is inert (GrantViolationGuard Javadoc: "acts only while ... a Batch Control matrix strategy is installed"). The warning can only over-warn there.
  - Basis: D-35d (1); D-50b; SPEC l.151; LIMITATIONS 35; checklist B-8 (privilege paths).
  - Fix direction (documentation):
    - In LIMITATIONS 35, say "holds Overall/Administer, or Item/Configure granted at the root (the global matrix)".
    - Add: "Configure given to the build account on a folder or a job is not detected; do not grant the build account Configure anywhere."
    - Checking every folder property would need the per-item scan that D-50a withdrew, so no code change is proposed.
  - Regression test (documentation of the limit, optional): Given change control on, a global default `SpecificUsersAuthorizationStrategy("svc")`, `svc` with only Overall/Read and Item/Read globally, and Item/Configure on folder `f` through the folder's matrix property. When `buildsMayRunAsSystem()` is evaluated. Then it is `false`, and the test is named as the documented limit, so a later change of the question is noticed.

- [S-23-02] `resources/.../ops/BatchControlStrategyMonitor/message.jelly:58-64`, `resources/.../action/GrantRequestItem/index.jelly:86-92` and `docs/LIMITATIONS.md` item 35 (remedy paragraph). The remedy text contradicts D-50a and T-08-61:
  - All three recommend "a global default build authorization ... that runs builds as the user who started them, or as an account without Configure permission".
  - D-50a and LIMITATIONS 35 itself (a few lines further on) say that a strategy which follows the triggering user leaves timer and SCM builds as SYSTEM.
  - T-08-61 asserts that `GlobalQueueItemAuthenticator(TriggeringUsersAuthorizationStrategy)` alone still warns.
  - So the first option given does not remove the warning. It is not a silent exposure (the check stays correct and fail-safe), but an administrator who follows the advice keeps a warning they cannot explain. This conflicts with SPEC l.273 ("tells the user in plain words ... what to do instead").
  - Basis: D-50a; SPEC l.151, l.273; checklist B (accurate security guidance).
  - Fix direction:
    - In both views and in LIMITATIONS 35, drop "the user who started them". Say instead: "that runs every build, including timer and SCM builds, as an account without Configure or Administer permission (for example 'Run as Specific User' with a dedicated build account)".
    - If "triggering user" is kept, add "followed by such an account for builds no user started".
  - Regression test: Given the monitor text, when it is rendered, then it does not recommend a triggering-user strategy on its own. As a UI row, T-08-61 could also assert that the remedy text names an account without Configure.

- [S-23-03] `store/BlockedAttemptAudit.java:270-274` (`recordPersonRefusal`), `:318-339` (`flushPersonSummaries`) and `:386-395` (`close`): a closing count record is lost on a store failure. This is a regression against the pre-d711d77 code.
  - `close()` resets the budget (`summaryUntil = null`, `writes.clear()`) **before** the caller appends the returned record. The old `flushExpired` appended first and reset afterwards, so a failed append left the summary open and a later flush retried it.
  - (a) In `flushPersonSummaries`, the append loop has no per-record `try`. The first failing append throws out of the loop, and every later closing record in `closing` is dropped, although their budgets are already reset. `closeAllOpen` does have the per-record `try`.
  - (b) In `recordPersonRefusal`, a failing append of the caller's own closing record throws before the caller's per-attempt record is appended. `ApprovalQueueDecisionHandler.recordPersonRefusal` catches and logs it, so both the count and the new attempt are gone. The refusal itself still stands.
  - Only the count is lost. The counted refusals remain in the controller log, one INFO line each. It does contradict SPEC l.107 ("one closing record gives their number").
  - Basis: SPEC l.107; D-51a; checklist B (audit integrity).
  - Fix direction:
    - Wrap each append in `flushPersonSummaries` in its own `try`, as `closeAllOpen` does.
    - On failure, log the complete closing text at WARNING, so the count is at least in the log. Optionally put the record back on a small pending list that the next flush retries.
    - In `recordPersonRefusal`, catch around the closing append so that the caller's own record is still written.
  - Regression test: Given user u with an open summary counting 3 refusals and a `Store` whose `appendChangeRecord` throws once. When `flushPersonSummaries()` runs and then runs again with the store healthy. Then the history contains exactly one closing record for u with the count 3, or at minimum the WARNING log contains its text. A second user's closing record in the same flush is not dropped.

## INFO
- [S-23-04] `security/SystemBuildCheck.java:108-122`: the cache key is the authenticator list only.
  - A change to the build account's permissions takes up to 5 minutes to show: for example a root matrix entry given to `svc` through JCasC reload or the API, or a role change on Manage Roles.
  - A save of the security page is not delayed, because it replaces the authenticator instances.
  - LIMITATIONS 35 states the 5-minute delay only for "an account that has no Jenkins user record yet".
  - Optional: make the sentence general ("after the build account's permissions change, within five minutes").
- [S-23-05] `store/BlockedAttemptAudit.java:318-339`: file order is not time order.
  - `flushPersonSummaries` resets u's budget under the monitor and appends after releasing it. A queue thread refusing u in between appends u's first new per-attempt record before the closing record of u's previous window.
  - Each record's `at` is correct (`ChangeRecord.create` stamps `BatchClock.now()` under the monitor), and the history pages sort by time. Only readers in raw JSONL order see the inversion.
  - No loss and no duplicate. No action needed.
- [S-23-06] `store/BlockedAttemptAudit.java:345-367` with `Jenkins.java:3650-3668`: summaries opened after the terminator stay open.
  - Terminators run first in `cleanUp`. The queue keeps accepting submissions, and the Timer keeps running, until later steps: triggers shut down at l.3660, the timer at l.3662, the queue is persisted at l.3668.
  - A person's refusal that opens a summary in those seconds leaves a summary with no closing record.
  - This needs a user over budget during shutdown. It is harmless otherwise, and a concurrent per-minute flush is safe, because collection is under the monitor.
  - Optional: set a `closed` flag in `closeAllOpen`. While it is set, `recordPersonRefusal` writes per-attempt records without opening a summary.
- [S-23-07] `queue/ApprovalQueueDecisionHandler.java:159, 277-291`: step 5 does not treat a copied `ReplayCause` as proof of a manual origin.
  - Since aa6761e, a submission that carries a copied `ReplayCause` but whose own re-run cause is naginator reaches step 5. The effective causes then hold no `UserIdCause`, because naginator strips copied user causes, and no `ApprovedCause`, so it falls through to the activation check.
  - This cannot happen today: naginator retries only `AbstractBuild`s (`NaginatorCause(AbstractBuild)`, `NaginatorListener extends RunListener<AbstractBuild>`), and Replay exists only for Pipeline.
  - The Rebuild-of-a-Replay case is refused at step 4, because Rebuild appends its own `UserIdCause`. The replayed script is not carried forward either: it lives only in `ReplayFlowFactoryAction`, which nulls it on use, and Rebuild re-runs the job's own definition.
  - Optional hardening: at step 5, also refuse when the effective causes contain `REPLAY_CAUSE_CLASS` (a replayed run was a person's).
  - Suggested row: Given an approval-required Pipeline job whose build #1 is a Replay made while the job was uncontrolled. When user u clicks Rebuild on #1. Then it is refused, and one `TRIGGER_BLOCKED` record says "a Rebuild ... build #1 by 'u'".
- [S-23-08] Test coverage for the security-22 closures.
  - No test row exercises the `@Terminator` path (S-22-03), the flush isolation from `expireOverdue`, or the "own summary only" rule under the lock (S-22-04). The only matches for `S-22-0[1-7]` in `src/test` are T-08-63/64 and T-06-82/83.
  - The rows proposed in security-22 under S-22-03 and S-22-04 are still to be written.

## Checked and found to be fine
1. **Web methods.** `grep -rn "public .* do[A-Z]" src/main/java` gives 42 matches, the same set as security-21 and security-22. `git diff main..HEAD -- src/main | grep -E '^[+-].*(do[A-Z][A-Za-z]*\(|@RequirePOST|@POST|checkPermission|checkAnyPermission)'` is empty, so no `do*` method, annotation or first-line check changed in part 4. The new public statics are not routable by Stapler: they belong to no Stapler-reachable object and have no `do` prefix. They are `SystemBuildCheck.buildsMayRunAsSystem` and `BlockedAttemptAudit.flushAtShutdown`/`flushPersonSummaries`.
2. **Raw output.** `grep -rn 'escapeXml="false"\|<j:out' src/main/resources` gives 0 matches. The two changed views keep `escape-by-default='true'`. Their new text is constant, plus `${rootURL}`.
3. **`ACL.SYSTEM2` / `ACL.as2`.** The diff has no new `+` line with an `ACL.SYSTEM2` switch. The only `ACL.as2` is `SystemBuildCheck:139`, to `ANONYMOUS2`, which lowers privilege. The new `hasPermission2(identity, ...)` calls pass the identity explicitly and do not depend on the context.
4. **Secrets.** No `getPlainText`, `Secret` or `Password` in the diff. The closing text carries the user id, the kind, job names, build numbers and timestamps only.
5. **Paths.** No new `new File`, `Paths.get` or `resolve(` in the diff. The probe's `getRootDir` is still never called. `FileStore.appendLine` uses `PathCodec.resolveUnder` with a month file name that the code builds itself.
6. **CSV.** The closing and summary texts start with a digit or a fixed word, and every cell still goes through `CsvWriter`'s `=+-@` sanitising.
7. **XStream.** No new persisted type. `PersonBudget.summaryListed` and `Cached.owner` are in memory only. Change records are the existing JSONL format (ARCHITECTURE storage section).
8. **Delegating strategy.** `GrantAwareACL`, `GrantLayer` and both strategy subclasses are unchanged since security-22. `withoutGrants` restores or removes the thread-local in `finally`. For the matrix subclass, the root ACL is `GrantAwareACL(null scope)`, which passes through. For role-based, it is the parent's `SidACL` of global roles. No Grant path reaches Overall/Administer. The null-delegate and `getGroups` handling is untouched. The answer on root vs item ACL is in Summary question 1 and S-23-01.
9. **Information disclosure.** The warning is still fixed text, shown to deciders and Manage holders only (`GrantRequestItem.isShowSystemBuildWarning`). The requester and a Discover-only user see nothing new. `doFill*`, `doCheck*` and the dashboard are unchanged.
10. **Concurrency and double approval.** No state-transition service changed.
    - Lock order is queue lock → audit monitor → store stripe lock (`FileStore.lockFor`).
    - The flush and the terminator take the audit monitor, release it, then take a stripe lock.
    - Nothing takes a stripe lock and then the monitor or the queue lock (`appendLine` only does `Files.write`), so no cycle is possible.
    - Double closing is impossible: `close()` runs only under the monitor and clears `summaryUntil`.
11. **Step-2 Replay judgement.** `lastRerunKind` matches the last naginator, Rebuild or Replay cause by class name, as before. A Replay's cause list is `[UserIdCause, ReplayCause]` only (`ReplayAction.java:337, 343`), so step 2 always applies to a real Replay. See Summary question 4 and S-23-07.
12. **Terminator.**
    - Discovery: `@hudson.init.Terminator` on a static method is found by `TerminatorFinder` (SezPoz), the same as the existing `NotificationDispatcher.shutdown`.
    - Ordering: it runs in `_cleanUpRunTerminators` (`Jenkins.java:3650`), while `Jenkins.get()` and the home directory are valid.
    - Safety: the per-record `try` keeps one failure from stopping the rest. With change control off the budget map is empty, so it does nothing (CLAUDE.md: no change to existing Jenkins behaviour).
13. **Change control off.** `buildsMayRunAsSystem()` returns `false` before probing. The flush and the terminator only act on budgets, which exist only after a refusal on the `approvalRequired` path.
14. **SpotBugs.** Unconfirmed. `target/spotbugsXml.xml` does not exist yet, because the build was still running and Maven was not run as instructed. The diff adds no `@SuppressFBWarnings`. One likely candidate to check: the unsynchronised read of `store` in `flushPersonSummaries` is fine, since the field is `final`.

## Request
- Request: docs/LIMITATIONS.md (release-manager).
  - Item 35: say "Item/Configure granted at the root (global matrix)". Add that Configure given to the build account on a folder or job is not detected (S-23-01).
  - Item 35: replace "the user who started the build" as a stand-alone remedy (S-23-02).
  - Item 35: generalise the 5-minute sentence to any permission change of the build account (S-23-04).
- Request: src/main/resources/io/jenkins/plugins/batchcontrol/ops/BatchControlStrategyMonitor/message.jelly and src/main/resources/io/jenkins/plugins/batchcontrol/action/GrantRequestItem/index.jelly (ui-dev). The remedy should name an account without Configure or Administer that runs every build, timer and SCM builds included. Do not recommend a triggering-user strategy on its own (S-23-02).
- Request: src/main/java/io/jenkins/plugins/batchcontrol/store/BlockedAttemptAudit.java (core-dev).
  - Wrap each append in `flushPersonSummaries` in its own `try`, and log the lost closing text at WARNING. Catch around the caller's own closing append in `recordPersonRefusal` (S-23-03).
  - Optional: a closed-after-terminator flag (S-23-06).
- Request: src/main/java/io/jenkins/plugins/batchcontrol/queue/ApprovalQueueDecisionHandler.java (core-dev). Optional: refuse a copied `ReplayCause` at step 5 (S-23-07).
- Request: src/test/** and docs/TEST-MATRIX.md (test-author).
  - The rows under S-23-01 (documented limit), S-23-03 and S-23-07.
  - The still-missing security-22 rows for S-22-03 (shutdown closing record) and S-22-04 (only the caller's own record is appended under the lock) (S-23-08).
