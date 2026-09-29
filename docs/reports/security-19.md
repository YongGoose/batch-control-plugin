# Security Review 19: e2e-run3 part 3, batch 2 (`fix/e2e-run3-part3`, D-48, DEF-06, S-18-01/02/03/06)

Reviewer: security-reviewer. 2026-09-30. Scope: `git diff 71affc2..HEAD -- src/main`.
- ui-dev commits: aa5b155, b967282, 5e5af2a, 4ba393e.
- core-dev commits: fa7da2d, 24d5109, ca96c82, e7126b8.

Standard: docs/HOSTING-CHECKLIST.md section B; SPEC item 2 (D-35b and D-48 acceptance, SPEC.md:44-45), items 6, 8 and 9; D-35b, D-35d, D-48; CLAUDE.md. Each fix was checked against docs/reports/security-18.md.
Maven was not run, as instructed.

## Summary: BLOCKER 0 / HIGH 0 / MEDIUM 0 / LOW 3 / INFO 3

## BLOCKER (grounds for hosting rejection)
None.

## HIGH
None.

## MEDIUM
None.

## LOW
- [S-19-01] `queue/ApprovalQueueDecisionHandler.java:195-196` (with `:229-230` and `:280-326`): the S-18-03 skip of step 3 is keyed only on "retry cause + current request + not SYSTEM". It does not require the fresh `UserIdCause` that marks a person's Retry.
  - Problem: a submission that carries a `NaginatorCause` and a copied `RemoteCause`, but no `UserIdCause`, and that is scheduled on a request thread under any non-SYSTEM identity, now skips step 3. Anonymous counts as non-SYSTEM. Step 4 finds no `UserIdCause`, so it does nothing. Step 5 finds no `UserIdCause`/`ApprovedCause` in `effective`. The submission then reaches `activatedOrRefuse(KIND_OTHER)` at `:326`, so it is **scheduled** on an activated approval-required job. Before e7126b8, step 3 refused it. The same submission on a non-request thread (a real automatic retry from naginator's run listener) is still refused at step 3, so the decision now depends on which thread schedules. This is not reachable today: naginator 1.556 `getCauseAction` always adds the clicking user's fresh `UserIdCause` (verified in security-18), and no remote caller can add a retry cause (core and build-token-root build their causes on the server side). If some plugin ever produced such a submission, this would be an unapproved run (BLOCKER class). That is why it is filed as LOW, as a hardening regression.
  - Basis: SPEC item 6 (a build token does not substitute for an approval); D-47 (a person acts only through their own fresh `UserIdCause`); checklist B (no unauthorised state change).
  - Fix direction: `userClickedRetry = isAutomaticRetry(causes) && isUserClickedRetry() && hasUserIdCause(causes)`. naginator strips copied `UserIdCause`s, so any `UserIdCause` present is the fresh one. Without it, the submission stays on the step-3 path.
  - Regression test: Given change control and run control on, and an activated approval-required freestyle job; When, inside an HTTP request made as a non-admin user, the queue is asked to schedule the job with a `NaginatorCause` plus a `RemoteCause` and no `UserIdCause` (for example through a test root action that calls `scheduleBuild2` with those causes); Then nothing is queued and a `REMOTE_RUN_BLOCKED` record exists.

- [S-19-02] `listener/ConfigNormalizer.java:98-106, 279-303`: the numeric-leaf rule (S-18-02) drops the whole start tag, attributes included.
  - Problem: `<configVersion anything="…arbitrary text…">1</configVersion>` at any depth still disappears from the normal form. A Configure holder, possibly working under a JIT grant, can therefore still hide an arbitrary string in `config.xml`. If that is the only difference, no CONFIGURE record is written. The hidden content is inert: XStream either ignores it or rejects it through JEP-200 class filtering (`class=`/`reference=` on a `Long` field fail type checks). So this remains the LOW audit gap of S-18-02, narrowed but not closed. The optional "known owners" restriction from S-18-02 was not applied either. That is acceptable on its own.
  - Basis: SPEC item 9 (every configuration change is recorded with its diff); checklist B ("user input validated").
  - Fix direction: drop only when the start tag is exactly `<configVersion>` (optional whitespace before `>`, no attributes), that is when `end == i + 1 + CONFIG_VERSION.length() + 1` after trimming whitespace. Otherwise copy the element verbatim.
  - Regression test: Given a job with a baseline snapshot; When `config.xml` is POSTed with `<configVersion x="hidden">1</configVersion>` added under a builder; Then a CONFIGURE record exists whose diff contains `x="hidden"`. T-09-21 (a bare numeric bump writes no record) stays green.

- [S-19-03] `listener/SelfGrantRevertFilter.java:396-399, 414-425`: the filter is registered unconditionally at `PLUGINS_STARTED` and wraps the response of every POST on the instance, including while change control (and with it the D-35b guard) is off.
  - Problem: while nothing is flagged, the wrapper only forwards. I found no functional difference (see "Checked and found to be fine"). It still puts a plugin wrapper, and an attribute lookup per `setStatus`/`sendRedirect`/`getWriter`/`getOutputStream`/`flushBuffer` call, into every POST: CLI over HTTP, other plugins' endpoints, large uploads and downloads. CLAUDE.md requires that "a new feature does not change existing Jenkins behaviour while the global switch is off", and it is also the smallest blast radius should a future servlet container or plugin rely on response identity.
  - Basis: CLAUDE.md (global switch off = unchanged behaviour); D-48 ("nothing changes for saves the guard does not touch").
  - Fix direction: at the top of `doFilter`, pass straight through (`chain.doFilter(request, response)`, no wrapper) unless change control is enabled (`BatchControlGlobalConfiguration.get().isChangeControlEnabled()` or the equivalent switch the guard reads). Optionally also skip `/cli` and WebSocket upgrade paths explicitly.
  - Regression test: Given change control off; When any POST (for example `configSubmit` of a job) is made; Then the response object that the endpoint sees is not a `SelfGrantRevertFilter` wrapper (a test root action records `rsp.getResponse()` class chain), and the answer is unchanged.

## INFO
- [S-19-04] `SelfGrantRevertFilter.java:401-411` (javadoc of `flag`): "Without a current request (CLI, …) it does nothing" is not accurate for the CLI in `-http` mode. That mode runs the command inside the `/cli` POST download request, so `Stapler.getCurrentRequest2()` is non-null there and the request is flagged. It is harmless. `FullDuplexHttpService` commits the response (`flushBuffer`) and takes the raw output stream before the command runs, so every `divert()` sees `isCommitted()` and passes, and `finish()` does nothing. The CLI user is not told, and the GRANT_VIOLATION record is written as usual. The `-webSocket` mode is a GET and is never wrapped. Fix direction: correct the comment. Optionally, make the CLI `update-job`/`create-job` path print the notice through `CLICommand.getCurrent()`. That would be a D-48 scope question for the human, not a security fix.
- [S-19-05] `SelfGrantRevertFilter.java:460-482, 494-501`: robustness edges of the replacement, none of them security-relevant:
  - (a) If the endpoint obtained the writer or stream before the save and keeps writing to that reference after the replacement, its bytes are appended after the failure page. Only the saving user sees them, and they are the endpoint's own output.
  - (b) The fallback in the `catch` block calls `super.reset()`/`getWriter()` unguarded. If the container refuses (for example `IllegalStateException` after `getOutputStream()`), the `RuntimeException` escapes from `sendRedirect`/`setStatus` and the user sees a 500. The save and the revert are already done.
  - (c) `finish()` does not check `request.isAsyncStarted()`. No Jenkins POST endpoint I know of goes async and saves an item.

  Fix direction: guard the fallback with its own try/catch, and return early from `finish()` when async has started.
- [S-19-06] `ops/ConfigureWithoutGrantMonitor.java:241-246, 690-697`: the group set is capped at `MAX_CANDIDATES` (100), and extra realm authorities are silently dropped. When at least one group is known, users are probed principal-only, so a member whose only standing Configure comes through a group cut off by the cap is reported nowhere. This is a warning monitor, not an enforcement point, and only very large realms hit the cap. Fix direction: when the cap is hit, log at INFO/WARNING and say so on the monitor ("scan truncated"), or probe the affected users with their full authorities minus the baseline.

## Fix verification against security-18
- S-18-01 (MEDIUM): **closed** (24d5109). Group probes are now the union of the strategy's own group entries and every authority returned by `loadUserByUsername2` for the candidate users (`:233-246`). `authenticated` is removed. When no group can be enumerated, users fall back to the full-authority probe minus the baseline (`:690-697`). No realm call was added: the authorities come from the `authenticate(sid)` call the scan already made. That call stays capped at 100 candidates, catches `RuntimeException` (`UsernameNotFoundException` included, `:446-455`), and is cached for 5 minutes per strategy (`:206-215`). Cost: at most 100 groups × |CHANGE_PERMISSIONS| `hasPermission2` calls on the root ACL, once per TTL. No `ACL.SYSTEM2` switch: the probes are built `Authentication` objects passed to `ACL.hasPermission2`, and the thread identity is never changed. The only `SYSTEM` reference is the candidate exclusion at `:323`. Residual: S-19-06.
- S-18-02 (LOW): **mostly closed** (ca96c82). Only a non-self-closing leaf whose text matches `\s*-?\d+\s*` is dropped. A child element, comment, CDATA, entity reference (`&` fails the digit test), a near-miss end tag (`</configVersionX>`) or a self-closing `<configVersion/>` is now copied into the normal form. Residual: attributes (S-19-02).
- S-18-03 (LOW): **closed as reported, with a new hardening gap**. (a) A person's Retry now reaches step 4 and is recorded as `TRIGGER_BLOCKED cause=RETRY` under their name. (b) `RemoteRunRefusal` is thrown only when the last request ancestor is the scheduled job itself and the last URI token is `build`/`buildWithParameters`, or when the ancestor is `BuildRootAction` by class name (`:373-398`). Any other caller keeps the quiet `false`. The refusal decision is unchanged, only its presentation. Gap: S-19-01.
- S-18-06 (INFO): **closed** (b967282). `RunApprovalNoticeActionFactory.createFor` returns `List.of()` unless `RunRequestService.requiresApprovalToRun(job)`, which returns false when run control is off, the job is null or `approvalRequired` is unset (`RunRequestService.java:136-142`). That is a config read with no permission side effect and no `SYSTEM2`. A build's `api/json` is unchanged while the switch is off.

## Checked and found to be fine
- **B1/B2 web methods.** The diff adds no Stapler `do*` method; the only `+public .* do[A-Z]` hit is the servlet `Filter.doFilter`, which is not a Stapler route. The two `do*` methods behind the changed routes are unchanged, and both still start with `@RequirePOST` plus permission checks:
  - `JobActivationForm.doSubmit` (`:157-160`: `REQUEST` on Jenkins, then `Item.READ` on the item);
  - `JobRequestAction.doSubmit` (`:217-224`: `Item.READ`, `REQUEST`, then `Item.BUILD`).
- **StaplerProxy routing (5e5af2a, 4ba393e).** Both `JobRequestAction` and `ComputedFolderActivationAction` still return `getUrlName() == null` without `BatchControl/Request` (`JobRequestAction.java:129`, `ComputedFolderActivationAction.java:62`). So `Actionable.getDynamic` never reaches them, and `getTarget()` is only consulted after that gate: the absent action still answers 404.
  - `getTarget()` only picks which of two objects serves the already-authorised URL. It returns `this` except when the rest of the path is exactly `/activation` or starts with `/activation/`, and then it returns `JobActivationRoute`.
  - `JobActivationRoute` exposes only `getActivation()` (the same `JobActivationForm` that the removed getters returned) and `getItem()`. `getItem()` is unreachable, because the first remaining token is always `activation`, and the item was already the parent in the URL anyway.
  - No new object or URL becomes reachable. `/activationX` falls back to the action and gets 404.
  - `JobActivationForm.getViewPermissions()` is unchanged (`{REQUEST}`), and the form's views have `escape-by-default='true'`.
- **B3 doFill/doCheck.** None were added or changed.
- **B4 Jelly output.** `SelfGrantRevertedFailure/index.jelly` and `JobActivationRoute/activation.jelly` both declare `escape-by-default='true'`. There is no `escapeXml="false"` and no `<j:out>` in the diff. `${it.itemFullName}` is the only dynamic value on the failure page. The plain-text path is `text/plain;charset=UTF-8` with `X-Content-Type-Options: nosniff`, on both `SelfGrantRevertedFailure.java:873-878` and the filter fallback at `:485-490`.
- **Failure page disclosure.** The page names only the item that the guard reverted for the current request's own user. The guard flags only when that user's save was made with Item/Configure on the item that came from a grant (`GrantViolationGuard.java:147-176`), or when that user just created the item under a Create grant (`:495-509`). The user therefore already has Read/Configure on it, or named it themselves. A Discover-only user cannot reach any save path. The page is produced only in the flagged request.
- **Filter scope and request attribute.** Non-POST and non-HTTP requests pass through unwrapped (`:416-419`). For an unflagged POST every override forwards to `super` after a `getAttribute` lookup; `setStatus(>=400)` and `sendError` move the state to PASSED, so the endpoint's own errors stay. Once the response is committed, `divert()` always passes (`:450`), so streaming or large responses and the CLI's full-duplex POST are unaffected. WebSocket upgrades are GET and are not wrapped.
  - The attribute can be set only by server code (`flag`, package-private, `:406`), never from client input.
  - It is scoped to one request object. The container clears it on recycle, and `Stapler.getCurrentRequest2()` is a thread-local, so no other request or thread sees it.
  - It holds only the item's full name. `SelfGrantRevertedFailure` is `Serializable` as an exception, but it is never persisted (checklist B9).
  - No clearing is needed beyond the request's end. Rendering the failure itself runs in the RENDERING state, which passes through, so there is no recursion.
- **Filter and CSRF.** The filter never skips or reorders the crumb check. It acts only after a save that already passed the endpoint's own crumb and permission checks, and it can only replace a success answer with a 403. It never turns a refusal into a success.
- **B5 `ACL.SYSTEM2`.** No new switch. The only new references are the `SYSTEM2` identity comparison already present in `isUserClickedRetry` (reused) and nothing in the monitor.
- **B6 secrets.** No new flow. The failure message contains the item name and constant text only, and the queue change logs nothing new.
- **B7 paths / B10 XML.** No new file paths. `ConfigNormalizer` scans only the item's own stored XML and does not parse it.
- **B8 CSV.** No new exported fields.
- **B9 XStream.** No new persisted type.
- **B12 permission objects.** The permission logic uses `Permission` objects. The monitor's sid strings are probe inputs to `hasPermission2`, not permission decisions.
- **B13 delegating strategy.** Unchanged by this diff.
- **B14 `@Restricted(NoExternalUse.class)`.** Present on all three new classes: `SelfGrantRevertFilter`, `SelfGrantRevertedFailure` and `JobActivationRoute`.
- **B16.** `.github/workflows/jenkins-security-scan.yml` exists.
- **Concurrency (item 10).** The filter keeps per-request state only; its `state` field is confined to the request thread. The marker consumption at step 1 (single use) is untouched by e7126b8. The monitor cache is a single volatile snapshot, as before.
- **Monitor visibility.** It is still an `AdministrativeMonitor` without a `getRequiredPermission()` override, so it is visible to Overall/Administer only. The realm group names it now lists are shown to administrators only.
- **B15 SpotBugs.** Unconfirmed. Maven was not run (instructed), and `target/spotbugsXml.xml` does not exist in the worktree. The caller should check the gate's `mvn verify` log.

## Request: src/main/java/io/jenkins/plugins/batchcontrol/queue/ApprovalQueueDecisionHandler.java (core-dev) require a `UserIdCause` among the submission's causes before skipping step 3 (S-19-01)
## Request: src/main/java/io/jenkins/plugins/batchcontrol/listener/ConfigNormalizer.java (core-dev) drop a numeric `configVersion` leaf only when its start tag carries no attributes (S-19-02)
## Request: src/main/java/io/jenkins/plugins/batchcontrol/listener/SelfGrantRevertFilter.java (core-dev) pass POSTs through unwrapped while change control is off; fix the CLI note in `flag`'s javadoc; guard the plain-text fallback and skip `finish()` for async requests (S-19-03, S-19-04, S-19-05)
## Request: src/main/java/io/jenkins/plugins/batchcontrol/ops/ConfigureWithoutGrantMonitor.java (core-dev) report or fall back when the group cap truncates the scan (S-19-06)
## Request: src/test/** and docs/TEST-MATRIX.md (test-author) add the Given/When/Then rows of S-19-01, S-19-02 and S-19-03
