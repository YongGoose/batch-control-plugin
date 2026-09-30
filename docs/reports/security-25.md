# Security Review 25: part 5, the fixes for the e2e-04 fresh-eyes findings (`fix/e2e-fresh-fd`)

Reviewer: security-reviewer. 2026-09-30.

Scope: `git diff 8e2db39..HEAD -- src/main` (25 files, +842/-164). 6f65f65 (a duplicate cherry-pick of S-23-07, reviewed in security-24) is ignored.
- ui: bc8c8f7 FD-01, 72721ad FD-08, 43eac8f FD-10, 163fe21 and 90a928a FD-11 plus saveError, b8a47b3 FD-05, b3b26dc D-55.
- core: a5b40d3 and d5c5fab (D-52, D-53, the detached draft), fa58c18 (D-54, FD-05 `EndReasons`, D-55), d237209 (FD-07), 64a91f5 (FD-09), ad08cbc (S-24-01/02/05 and `swapStoreForTesting`).

Standard: docs/HOSTING-CHECKLIST.md section B; DECISIONS D-52..D-57; SPEC l.88, l.208, l.219-221; docs/reports/security-24.md; CLAUDE.md.

Constraint: Maven was not run (caller's instruction), so `target/spotbugsXml.xml` does not exist.

## Summary: BLOCKER 0 / HIGH 0 / MEDIUM 1 / LOW 2 / INFO 6

No web method lost `@RequirePOST`/`@POST` or its permission check. One new endpoint (`doCheckApproversText`) is POST-only and answers only Manage holders. No new `ACL.SYSTEM2` switch. No secret reaches a record, a log or a mail. The one real finding is FD-09: the run user now comes from an `ApprovedCause` that Rebuild can copy, so a person's Rebuild can be recorded as another user's run (S-25-01).

Answers to the questions asked:

1. **Approver validation (`doCheckApproversText`).** It is `@POST`. With the global crumb filter that makes it CSRF-safe. `mayCheck()` requires `BatchControl/Manage`, and anyone else gets `FormValidation.ok()`, so there is no oracle for them. A Manage holder can use it, and the save path, to test whether an id exists in the security realm, including LDAP/AD accounts that never logged in and do not show in /people. That permission is delegated below Administer. The comparable core check (matrix-auth `doCheckName`) needs Administer. Realm lookups are unbounded: one per unknown id, with no cap on the number of ids. On the save path they run inside the `synchronized configure` (S-25-02, LOW). Realms that throw `UserMayOrMayNotExistException2` are wrongly reported as "unknown" (S-25-05, INFO).
2. **CONFIG_CHANGE.** `describeChanges` diffs exactly ten fields: approvers, allowAdminSelfApproval, the four timeouts/limits, grantDurationOptions, incidentResults, retentionMonths, emailNotifications. None is a `Secret`. The class has no `Secret` or password field (grep over `config/`: 0 matches). The mail password lives in Mailer's own descriptor and is never read. The record is shown on the History > Changes tab to `BatchControl/ViewHistory` holders, like every change record. What it shows (approver ids, timeouts) is already visible to requesters on the request forms and pages.
3. **Detached draft.** It is built with the private copy constructor, so `candidate = true`. The switch setters are therefore plain assignments with no toggle record, no side effect and no `persistBestEffort`. It is never registered in the ExtensionList. Only `writeConfigFile()` persists a candidate, and only `configure` calls that, on its own `bound` copy. `getConfiguration()` returns the draft only while the request attribute is set, which happens only inside the refused POST's own forward. A new request, including the next `configSubmit`, always gets the singleton. Check URLs resolve to the registered descriptor. The typed values are echoed through `f:textarea`/`f:textbox`, and the refusal text through `${saveError}` in an `escape-by-default='true'` view. The page keeps `permission="${it.requiredPermission}"`. The only hardening left is S-25-06 (INFO).
4. **Notifications.** `MailNotifier` sends one message per recipient with a single `TO`, so no recipient learns the others. The body names only the requester, the job or scope, the request id, the end reason and the link. Approver ids are not in it. Recipients (`endRecipients`) are the requester and, for a pending request, its designated approvers. The canceller is removed by `Approvers.sameUser` against the live authentication. Every call site runs under the caller's own authentication or SYSTEM (the expiry work), so the removal is correct. The end reasons are constants (`EndReasons`), "Cancelled by <id>", or the existing invalidation reason. They go into `details`, which `oneLine()` flattens, and the subject is flattened too, so no line or header can be injected. A requester can make approvers get two mails per create/cancel pair. That is bounded by the 1,000-entry queue (S-25-08, INFO).
5. **`swapStoreForTesting`.** It is not reachable from the web: it is static and not a `do*`/`get*`, so Stapler does not route it. It is also not reachable from sandboxed Pipeline (not whitelisted). It is reachable from the script console and from other plugins, but both already run arbitrary code in the JVM, so it grants them nothing new. `@Restricted(NoExternalUse)` is a compile-time check only. The residual risk is misuse or leaking in tests, and a production footgun that silently drops refusal records. A safer form is suggested in S-25-04 (INFO).
6. **FD-09.** The run user is taken from the `ApprovedCause` on the run, not from the request's store record. The only place that creates the cause is `RunRequestService.submitApproved` (l.800), from the stored request. But the Rebuild plugin copies the build's causes into the new build (D-47, security-24 question 4). A Rebuild of an approved run therefore carries a copied `ApprovedCause` next to the clicking user's `UserIdCause`. It passes the gate in two cases: on a job that does not require approval (D-47), and on any job while run control is off but change control keeps recording on. Before 64a91f5 such a run was recorded as the clicking user's. Now it is recorded as the original requester's (S-25-01, MEDIUM).
7. **D-55.** The check is inside `lock`, after the status check, `ApprovalPolicy.checkDecision` and the pending-expiry step, and before the status change (`RunRequestService.java:223`). No approval check was removed or reordered. `RequestItem.approve` (l.327) is the only caller, so every approval passes it. The job lookup uses `jobForPolicy` (a SYSTEM2 read, documented, after `checkDecision`). The job name in the refusal is shown only to someone who passed the decision checks. A job disabled right after the check leaves an APPROVED request that waits and expires, which D-55 accepts and the page now explains. Enabling or disabling the job needs Item/Configure and cannot bypass any approval. The view uses the permission-aware `findJob()`, so it reveals nothing to a viewer who cannot read the job.
8. **Endpoints.** 43 `public ... do[A-Z]` matches (42 at 8e2db39). The one new endpoint is `BatchControlGlobalConfiguration.doCheckApproversText` (`@POST`, Manage via `mayCheck()`). `doConfigSubmit` keeps `@RequirePOST` and `checkPermission(MANAGE)` as its first two lines. `doMigrate` and `doRevert` keep `@RequirePOST` plus `checkPermission(ADMINISTER)`. The FD-01 changes only hide their controls from non-administrators.

### Closure of security-24

| Finding | Status | Evidence |
|---|---|---|
| S-24-01 (LOW) no per-user budget after shutdown | Closed | `BlockedAttemptAudit.java:300-311`: after `shutDown`, a record is appended only while `writes.size() < PERSON_BUDGET` and the key is not the overflow key. Beyond that the refusal is logged at INFO and no summary is opened. |
| S-24-02 (INFO) WARNING text says "retried" when the record is dropped; stack trace flood | Closed | `appendOrKeep(record, withTrace)` says "kept for the next flush" or "dropped (the pending list is full)". It logs the stack trace only for the first failure of a flush, then one count line. |
| S-24-03 (INFO) torn or duplicated line on a late write failure | Open (optional) | `FileStore` is not touched. |
| S-24-04 (INFO) no test seam or rows | Partly closed | The seam exists (`swapStoreForTesting`, see S-25-04) and `RerunSummaryFlushTest` uses it. |
| S-24-05 (INFO) `closeAllOpen` without `forgetOtherInstance` | Closed | `forgetOtherInstance()` is now the first statement in the `synchronized` block, before `shutDown = true`. |

## BLOCKER (grounds for hosting rejection)
None.

## HIGH
None.

## MEDIUM
- [S-25-01] `listener/RunRecordListener.java:75,86-90`: FD-09 attributes the run to the requester named in any `ApprovedCause` on the run, including one copied by a Rebuild.
  - The problem:
    - `run.getCause(ApprovedCause.class)` also finds a cause that the Rebuild plugin copied from an earlier approved build.
    - The new branch prefers `approved.getRequester()` over the `UserIdCause` of the person who clicked Rebuild.
    - So user B's Rebuild is written to History, runs.csv, the dashboard and the `user=` filter as a run by user A. It is also classified `APPROVED_REQUEST` and linked to A's old request id.
    - The classification and the request link are older behaviour (l.83-84, `classify`). The user attribution is new in 64a91f5. Before it, the record named B.
  - Reach: any holder of Item/Build, with the Rebuild plugin installed, on a job that has an approved build and where the Rebuild passes the gate. That is either a job without `approvalRequired` (D-47: a Rebuild is a person acting), or any job while run control is off and change control keeps recording on (the gate returns at its first line). A naginator retry of an approved build, which copies the cause too, is attributed the same way.
  - Why MEDIUM: no state change and no permission is gained. But the audit trail, which is the plugin's purpose, names the wrong person for a run, and the person who acted can choose that.
  - Basis: checklist B-9/B-10 spirit (the record must name the actor); SPEC item 10 (run record user); CLAUDE.md (user input must not decide attribution).
  - Fix direction:
    - Attribute to the requester only when this run is the request's own execution. `markExecuted` (`RunRequestService.java:453`) runs at start, so at `onFinalized` load the request by `approved.getRequestId()` and require `executedRunId` to equal `jobFullName#number`. Alternatively, require the `ApprovedRunAction` with the same request id and no top-level `UserIdCause`.
    - Otherwise fall back to the `UserIdCause`, as before.
    - Apply the same test to `classify` and `setRunRequestId`, so a copied cause no longer makes the run `APPROVED_REQUEST`.
  - Regression test (JenkinsRule, synthetic cause list, no Rebuild plugin needed): Given change control on, run control off, and a job with a finished approved run of request R by requester A. When user B schedules the job with `CauseAction(new ApprovedCause(R, "A", "approver"), new Cause.UserIdCause("B"))` and the build finishes. Then the new run record's user is B, its cause type is USER, and it carries no run request id. The record of R's own run still names A.

## LOW
- [S-25-02] `config/BatchControlGlobalConfiguration.java:134-138,680-697,709`: unbounded security-realm lookups, and a realm-membership oracle for Manage holders.
  - The problem:
    - `approversValidation` calls `SecurityRealm.loadUserByUsername2` once for every id that has no Jenkins `User` record. There is no cap on the number of ids and no de-duplication.
    - On the save path this runs inside `synchronized configure`. A slow or unreachable LDAP/AD makes each lookup wait for its timeout, so one POST with many ids holds the configuration monitor, and a request thread, for minutes. `setRunControlEnabled`/`setChangeControlEnabled` (JCasC, scripts) wait on the same monitor. The queue gate does not, because it reads volatile fields.
    - The three answers (ok, error "not a known Jenkins user", warning "could not be checked") let a `BatchControl/Manage` holder test whether an account exists in the directory, one POST per batch of ids. Manage is a delegated permission below Administer. Core's comparable check (matrix-auth `doCheckName`) requires Administer.
  - Why LOW: it needs BatchControl/Manage, a trusted role that can already change who approves. The oracle reveals only whether an account exists, and D-53 asks for this lookup.
  - Basis: checklist B-9 (information disclosure through `doCheck*`), B-10 (work under a lock); D-53.
  - Fix direction:
    - Refuse more than a fixed number of approver ids (for example 100), and de-duplicate before any lookup.
    - Stop asking the realm after a small number of unknown ids, and report the rest as not checked.
    - Run `validate` and `validateApprovers` before taking the monitor: make `configure` unsynchronized for the validation and binding phase, then `synchronized (this)` for the write and apply. Keep the #19 transaction order.
    - Optionally, answer the realm lookup in `doCheckApproversText` only for Administer, and for Manage report only Jenkins `User` records.
  - Regression test: Given a Manage-only user and a realm whose lookup counts its calls. When the user POSTs `checkApproversText` with 1,000 distinct unknown ids. Then the answer is an error naming the limit, and the realm was asked at most the cap. Also: given a user without Manage, a POST with an unknown id gets `ok` and the realm is not asked; a GET gets 405.
- [S-25-03] `queue/ApprovalQueueDecisionHandler.java:498`: FD-07 reads the activation state from disk under the queue lock on every blocked unattended trigger, and does not catch its failure.
  - The problem:
    - `ActivationService.getState(subject)` goes straight to `FileStore.loadActivationState`, which does a `Files.isRegularFile` check and, for a held job, an XStream parse. It does not use the activation cache that `mayRunUnattended` uses one line earlier.
    - It runs under the global queue lock for every cron tick and every upstream trigger of a job that is not activated.
    - An `IOException` becomes an `UncheckedIOException` (FileStore l.383) and escapes `shouldSchedule`. The refusal still holds, because the trigger is not scheduled, but its record is lost and the exception reaches `Queue.schedule2`'s caller.
  - Why LOW: the result is fail-closed, the files are small, and triggers come at most once per minute per cron job.
  - Basis: checklist B-10 (work under the queue lock); security-24 S-24-01 (the same concern).
  - Fix direction: keep `deactivatedAt` in the activation cache entry (`Cached`) and read it from there. Wrap the lookup in `try/catch (RuntimeException)` and fall back to an epoch of `"unknown"`, so the record is still written.
  - Regression test: Given run control on, a held cron job, and an activation-state file that cannot be read. When the timer fires. Then the build is not scheduled, one TRIGGER_BLOCKED record is written, and no exception leaves `shouldSchedule`.

## INFO
- [S-25-04] `store/BlockedAttemptAudit.java:99-104`: `public static swapStoreForTesting` in production code.
  - Reach: see question 5. There is no web or sandbox path. Only code that is already fully trusted can call it (the script console needs Administer; plugins run in the JVM).
  - Residual risk: swapping in a no-op or failing store silently stops the refusal records, which are the audit of blocked re-runs. `INSTANCE` is JVM-static, so a swap that is not restored survives into the next Jenkins session of the same JVM.
  - Safer form (any one):
    - Make it package-private, and give the test a small accessor in `src/test/java/io/jenkins/plugins/batchcontrol/store/`.
    - Or keep it public but fail outside tests: `if (!hudson.Main.isUnitTest) throw new IllegalStateException(...)`, and mark it `@VisibleForTesting`.
    - Log at WARNING on every swap, naming the replacement class.
- [S-25-05] `config/BatchControlGlobalConfiguration.java:691`: `UserMayOrMayNotExistException2` extends `UsernameNotFoundException`.
  - Realms that cannot say whether a user exists (for example Active Directory without a bind account) throw it. The catch reports such an id as UNKNOWN and refuses the save, while D-53 asks for acceptance with a warning.
  - The effect is availability for the administrator (a valid new approver cannot be added until that user has logged in), not a bypass.
  - Fix: catch `hudson.security.UserMayOrMayNotExistException2` before `UsernameNotFoundException` and return UNCHECKED.
- [S-25-06] Detached draft hardening (`BatchControlGlobalConfiguration.java:97`, `BatchControlConfigurationLink.java:138`).
  - Nothing persists the draft today (question 3). But `Descriptor.save()` on a candidate would write the real `config.xml`, since it has the same class and id.
  - Fix: override `save()` to do nothing, or to throw, when `candidate`. Separately, `req.getView(this, "index.jelly")` returning `null` would give an NPE instead of the 400; fall back to `throw e`.
- [S-25-07] `ops/BatchControlStrategyMonitor.java:211-221`: the STRATEGY_CHANGE record names the authorization strategy classes.
  - It is shown to every ViewHistory holder, which is information otherwise shown only on the Security page (Administer). This is low value to an attacker, and D-52 asks for it.
  - Optional: name the display names only.
- [S-25-08] D-54 mail volume.
  - A requester can create and cancel requests in a loop. The approvers then get two mails per pair (REQUEST_CREATED is older behaviour; CANCELLED is new).
  - This is bounded by `QUEUE_CAPACITY` (1,000, dropped beyond) and by the `BatchControl/Request` permission.
  - Optional: a per-requester rate limit on create, or coalescing CANCELLED with the REQUEST_CREATED still queued.
- [S-25-09] Test coverage.
  - No row checks that `doCheckApproversText` answers `ok` to a user without Manage, refuses GET, and does not ask the realm then.
  - No row covers the copied-`ApprovedCause` attribution of S-25-01.
  - `ConfigAuditTest` covers D-52. `RequestEndStateTest` and `ExpiryAndCancelTest` cover D-54/D-55.

## Checked and found to be fine
1. **Web methods.** `grep -rn "public .* do[A-Z]" src/main/java` gives 43, against 42 at 8e2db39. The one new method is `doCheckApproversText` (`@POST`, `mayCheck()` = `BatchControl/Manage`, otherwise `ok()`). In the diff, the only `+`/`-` lines matching `@POST`, `@RequirePOST`, `checkPermission` or `do*(` are that method's. `doConfigSubmit` (l.118-120) keeps `@RequirePOST` and `checkPermission(MANAGE)` as its first two lines. The new `catch` comes after both. No job-scoped permission is checked globally.
2. **Raw output.** `grep -rn 'escapeXml="false"\|<j:out' src/main/resources` gives 0. All ten changed views start with `<?jelly escape-by-default='true'?>`. New dynamic values: `${saveError}`, `${filter.fromError}`/`${filter.toError}` (which echo user input, length-capped by `FilterParser.text`), `${filter.fromInput}`/`${filter.toInput}` in `value=` attributes, `${it.approvedRunTimeoutMinutes}`, `${it.request.decisionComment}`. All are escaped. The GrantsSection change (FD-08) is a pure move of the form block.
3. **`ACL.SYSTEM2` / `ACL.as`.** No such line in the diff. The new callers of the existing SYSTEM2 lookup `ApprovalPolicy.jobForPolicy` (l.64-66, reason comment present) are `RunRequestService.jobDisabled`, which is called after `checkDecision`. `RequestItem.isJobDisabled` uses the permission-aware `findJob()` (P-09).
4. **Secrets.** In the diff, `getPlainText`, `Secret` and `Password` give 0. The CONFIG_CHANGE detail and its WARNING hold only the ten non-secret fields. STRATEGY_CHANGE holds class and display names. Notifications hold ids, names and constant reasons.
5. **Paths.** In the diff, `new File`, `Paths.get` and `resolve(` give only the private `resolve(String id)` realm helper, which touches no path. The records go through the existing `appendChangeRecord`/`PathCodec.resolveUnder`.
6. **CSV.** CONFIG_CHANGE and STRATEGY_CHANGE details start with a constant ("Batch Control configuration changed: ...", "Batch Control authorization strategy ..."), and `CsvWriter` sanitises `=+-@` in every cell anyway. Approver ids are now validated to be real users. When the History dates are invalid the CSV links are not rendered. The CSV endpoints keep their earlier fallback, which is documented in `FilterParser`.
7. **XStream.** No new persisted type. `CONFIG_CHANGE` and `STRATEGY_CHANGE` are enum values in the existing JSONL `ChangeRecord`. The draft is transient and never written. `decisionComment` already existed on all three request types.
8. **Delegating strategy.** `security/` is not touched. There is no new Grant path to Overall/Administer, and the null-delegate and `getGroups` handling is unchanged. FD-01 only hides controls whose endpoints already require Administer (`BatchControlStrategyMonitor.java:161-163,186-188`).
9. **Information disclosure.**
   - `doCheckApproversText` gives nothing to non-Manage users (see S-25-02 for Manage).
   - The D-55 notice uses the permission-aware `findJob()`.
   - The FD-10 and FD-05 text is constant.
   - CONFIG_CHANGE is behind ViewHistory and holds nothing a requester cannot already see.
   - A Discover-only user sees nothing new.
   - Mails go to one recipient each, and approver identities are not in the body.
10. **Concurrency.**
    - D-55 is under the service `lock`, and the single approval entry point passes it.
    - The D-54 dispatch calls inside `lock` only build a small object and enqueue it on the bounded executor.
    - The FD-07 key keeps its bound (`MAX_COALESCED_KEYS` 10,000, LRU). An epoch changes only when an approved HOLD ends an activation.
    - The S-24 fixes keep every access to `shutDown`, `pendingClosing` and `budget` under the audit monitor. `store` is `volatile`.
    - A request still cannot be approved twice: the status check is under the lock and unchanged.
11. **Mail injection.** The subject and details go through `oneLine()`, the free-text reason lines are quoted with `> ` (security-08 S-08), and the link comes only from the configured root URL (security-08 S-04).
12. **Change control off.** No switch on: `RunRecordListener` returns at `ChangeRecording.isActive()`. `configure` still writes CONFIG_CHANGE when the other fields change, which D-52 asks for. It is the plugin's own configuration page, so no existing Jenkins behaviour changes. `shouldSchedule` returns before FD-07 while run control is off.
13. **SpotBugs.** Unconfirmed. Maven was not run and `target/spotbugsXml.xml` is absent. The diff adds no `@SuppressFBWarnings`.

## Request
- Request: src/main/java/io/jenkins/plugins/batchcontrol/listener/RunRecordListener.java (core-dev). Attribute a run to the requester, and classify it `APPROVED_REQUEST` with a request id, only when it is the request's own execution (`executedRunId` matches). Otherwise use the `UserIdCause` (S-25-01).
- Request: src/main/java/io/jenkins/plugins/batchcontrol/config/BatchControlGlobalConfiguration.java (core-dev).
  - Cap and de-duplicate approver ids, and bound the realm lookups (S-25-02).
  - Validate before taking the monitor (S-25-02).
  - Treat `UserMayOrMayNotExistException2` as UNCHECKED (S-25-05).
  - Make `save()` inert on a candidate (S-25-06).
- Request: src/main/java/io/jenkins/plugins/batchcontrol/config/BatchControlConfigurationLink.java (core-dev). Fall back to `throw e` when `getView` returns `null` (S-25-06).
- Request: src/main/java/io/jenkins/plugins/batchcontrol/queue/ApprovalQueueDecisionHandler.java and policy/ActivationService.java (core-dev). Read `deactivatedAt` from the activation cache and catch its failure (S-25-03).
- Request: src/main/java/io/jenkins/plugins/batchcontrol/store/BlockedAttemptAudit.java (core-dev). Narrow `swapStoreForTesting`: package-private with a test accessor, or guard it with `Main.isUnitTest` (S-25-04).
- Request: src/test/** and docs/TEST-MATRIX.md (test-author). Add rows for S-25-01, S-25-02 (including non-Manage, GET and the cap), S-25-03 and S-25-05.
