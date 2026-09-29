# Security Review 20: security-19 follow-up (`fix/security-19`)

Reviewer: security-reviewer. 2026-09-30. Scope: `git diff main..HEAD -- src/main`. The commits are:
- 2f2a395 (S-19-01);
- 38c0767 (S-19-02);
- cb5c95e (S-19-03, S-19-04, S-19-05);
- 74968f4 (S-19-06).

The test commit 58bc4d9 was read only for coverage.

Standard: docs/HOSTING-CHECKLIST.md section B; SPEC items 2 (D-35b, D-48), 6 and 9; CLAUDE.md. Each fix was checked against docs/reports/security-19.md. Maven was not run, as instructed.

## Summary: BLOCKER 0 / HIGH 0 / MEDIUM 0 / LOW 1 / INFO 3

## BLOCKER (grounds for hosting rejection)
None.

## HIGH
None.

## MEDIUM
None.

## LOW
- [S-20-02] `listener/SelfGrantRevertFilter.java:246-255, 267-294`: the gated writer changes two behaviours of `getWriter()` for every POST while change control is on. This applies whether or not the request is flagged.
  - Problem 1, `checkError()`. The endpoint now receives `new PrintWriter(new GatedWriter(raw))`. `PrintWriter.checkError()` hands the check to the inner writer only when that writer is itself a `PrintWriter`. `GatedWriter` is not one. The container's `raw` PrintWriter swallows the `IOException` of a client disconnect, so `GatedWriter` never throws. The outer `checkError()` therefore always returns `false`. Any POST endpoint that streams until `checkError()` turns true keeps producing output after the client has gone. This is a resource-use effect only: nothing is truncated or disclosed.
  - Problem 2, an extra copy. `GatedWriter` does not override `write(String,int,int)`, so `Writer`'s default copies every string into a `char[]`. A string longer than 1024 chars gets a new array the size of the string. A large body written as a single `String` is held in memory twice, briefly.
  - What stays correct:
    - The wrapper `PrintWriter` built on a `Writer` adds no buffer, so no byte is held back or lost.
    - `flush`/`close` reach `raw` unchanged, and the commit points are the container's own.
    - An unflagged request never diverts.
    - Large responses, POST downloads (for example support-core's bundle) and the CLI `-http` stream pass through in full. Once the response is committed, `divert()` is always `false`.
    - The core console's progressive `progressiveHtml`/`progressiveText` polls are POSTs. They now pass through the gate as well, and their output is unchanged.
  - Basis: CLAUDE.md ("a new feature does not change existing Jenkins behaviour"); D-48 ("nothing changes for saves the guard does not touch").
  - Fix direction: return a `PrintWriter` subclass whose `checkError()` is `super.checkError() || raw.checkError()`. Override `GatedWriter.write(String,int,int)` (and `append`) to forward to `raw` after `divert()`, without the copy.
  - Regression test: Given change control on, and a test root action whose POST method writes through `rsp.getWriter()` to a response whose underlying writer is in error (a test wrapper that makes `raw.checkError()` return true); When the action calls `checkError()` on the writer it got; Then it returns `true`, as it does with change control off.

## INFO
- [S-20-01] `listener/SelfGrantRevertFilter.java:87-100, 103-109`: whether to wrap is decided once, at the start of the filter chain. The guard reads the switch again later, at save time (`GrantViolationGuard.java:354-360`).
  - The only mismatch that matters is off at the filter and on at the save. The request is then unwrapped, the guard reverts and calls `flag`, and the saving user is not told.
  - The revert itself is always recorded, because the record does not depend on the filter:
    - `appendViolation` at `GrantViolationGuard.java:172` runs before `flag` at `:176`;
    - `appendChangeRecord` at `:511` runs before `flag` at `:512`.
    So "a guard revert is always reported" holds for the audit history. Only the D-48 answer can be lost.
  - Reachability is practically nil. The guard flags only a save made with Item/Configure (or Item/Create) that comes from a grant alone. Grants can be neither requested nor approved while change control is off (`GrantRequestService.java:464-480`). Turning the switch off revokes the open windows (S-15). The endpoint's own `checkPermission` runs before it reads the body (`configSubmit`, `config.xml`, `createItem`). So, between the filter's check and that `checkPermission`, all of the following would have to happen: the switch goes on, a window is requested and it is approved.
  - The other direction (on at the filter, off at the save) is harmless. The request is wrapped and the guard does nothing, so the wrapper only forwards. A request flagged before the switch goes off still gets its notice, which is correct: the revert happened.
  - The one long-lived POST is the CLI `-http` session. Its answer is committed before any command runs (S-19-04), so no notice was possible there anyway.
  - Fix direction (optional hardening):
    - The filter sets a request attribute when it wraps. `flag` logs at WARNING when a request exists but carries no such attribute ("revert of X not shown to the user: the request was not guarded").
    - Separately, catch `RuntimeException` in `changeControlOn()`, not only `IllegalStateException`. A broken extension lookup would then fall back to "unwrapped" instead of failing every POST with a 500.

- [S-20-03] `ops/ConfigureWithoutGrantMonitor.java:231-300, 401-424`: residuals of the S-19-06 fix.
  - (a) `strategyGroupSids` still stops silently at 100 strategy group entries (`:408`), with no warning. This cap does not lose a member: once `groups` is full, every realm authority the entry list did not contribute goes to `unchecked`, and its members are probed in full. It does lose group holders: a strategy group entry past the cap that no candidate user carries is never listed.
  - (b) A truncated user is probed with all their authorities minus the baseline. They are also listed for permissions that come through groups that were checked, so the same permission can appear twice (over-reporting, which is the safe direction).
  - (c) The WARNING is written once per scan. The scan runs at most once per 5-minute TTL, and only when an administrator's page asks `isActivated()`. A large realm therefore logs one line every 5 minutes for as long as it exists.
  - Cost of the fallback, which is bounded:
    - It adds no security-realm call. The authorities come from the `loadUserByUsername2` call the scan already makes, which is capped at 100 candidates.
    - It adds no `hasPermission2` call. A truncated user gets one probe of `|CHANGE_PERMISSIONS|` checks instead of the principal-only one, so the total is still at most 100 users plus 100 groups, each times `|CHANGE_PERMISSIONS|`.
    - What grows is the size of the authority set each check walks. `unchecked` keeps every distinct extra group name, at most the union of 100 users' authorities. It is transient, and only its size is used.
  - There is no `ACL.SYSTEM2` switch, and no identity change on the thread.
  - This remains a warning monitor, not an enforcement point.
  - Fix direction:
    - log the strategy-side cap as well;
    - keep only a counter instead of the `unchecked` set;
    - log the WARNING only when the count changes;
    - say "scan truncated" on the monitor page, as security-19 suggested.

- [S-20-04] `listener/SelfGrantRevertFilter.java:128-172, 297-341, 352-372`: edges of async (non-blocking) output. None of them is reachable from a Jenkins save path I know of.
  - (a) `GatedStream` forwards `isReady`/`setWriteListener`. If a flagged request is in non-blocking mode, `divert()` then renders the notice with blocking writes. Jetty may refuse these (`IllegalStateException`). Both `catch` blocks log this, and the state becomes REPLACED, so the answer may be empty.
  - (b) After REPLACED, `DiscardingStream.setWriteListener` is a no-op. A non-blocking writer that registers there is never called back and waits until the async timeout.
  - (c) `state` is not `volatile`, but a `WriteListener` runs on container threads.

  `finish()` now returns early when async has started (S-19-05 (c)), which is correct. Fix direction, if ever needed: pass straight through (no gate) once `req.isAsyncStarted()`.

## Fix verification against security-19
- S-19-01 (LOW): **closed** (2f2a395). The retry skip at `ApprovalQueueDecisionHandler.java:198-199` now requires a `UserIdCause`.
  - With one present, step 3 is skipped, but step 4 (`:232-248`) then always throws the refusal, so the skip can only lead to a refusal. Without one, the submission stays on step 3 and is refused as `REMOTE_RUN_BLOCKED`.
  - A forged `UserIdCause` can come only from server-side code (an admin script or a plugin). Causes are never taken from request input. Core and build-token-root build theirs on the server.
  - Such a cause can do only two things. It can move a record from `REMOTE_RUN_BLOCKED` to `TRIGGER_BLOCKED cause=RETRY`, and it can move the answer from a quiet `false` to the refusal page. It can never schedule a build.
  - The record is attributed to `Jenkins.getAuthentication2()` (`:238`), not to the cause's `userId`, so a cause naming another user misattributes nothing.
  - A copied (non-fresh) `UserIdCause` from some other retry plugin fails closed in the same way.
  - Test: T-06-76.
- S-19-02 (LOW): **closed** (38c0767). The bare-tag test at `ConfigNormalizer.java:99-100` checks the text between the tag name and `>`:
  - `tagEnd` returns the index after `>`, and quotes are honoured, so a `>` inside an attribute value is not taken for the end of the tag;
  - `tagName` stops at whitespace, `/` or `>`;
  - the text between the name and `end - 1` is therefore empty or whitespace for a bare tag, and anything with an attribute is copied.

  `isBlank()` accepts Unicode whitespace beyond XML's four characters, but such a start tag is not well-formed XML. `config.xml` is stored only after core has parsed it (`updateByXml`), and form saves are XStream output, so the case cannot occur. Test: T-09-23.
- S-19-03 (LOW): **closed** (cb5c95e). With change control off, every POST passes straight through (`:90-95`) and no wrapper is created. For the switch changing during a request, see S-20-01. Test: T-01-16.
- S-19-04 (INFO): **closed**. The javadoc of `flag` (`:71-78`) now describes the CLI `-http` behaviour correctly.
- S-19-05 (INFO): **closed**:
  - (a) Every writer and stream handed to the endpoint while watching is gated (`:246-264`). Output written through a reference taken before the save is swallowed once the answer has been replaced. The raw objects are handed out only during RENDERING.
  - (b) The fallback has its own `try/catch` (`:160-168`), and `IOException` is now caught too.
  - (c) `finish()` returns early for async requests (`:185-187`).

  New edges: S-20-02, S-20-04.
- S-19-06 (INFO): **closed as reported** (74968f4):
  - realm groups beyond the cap are no longer dropped silently: they are logged, and their members are probed in full (`:249-264, 295-297`);
  - `authenticated` no longer takes a cap slot.

  Residuals: S-20-03.

## Checked and found to be fine
- **B1/B2 web methods.** The diff adds no Stapler `do*` method: `grep` over the added lines finds none. `doFilter` is the servlet filter, not a route.
- **Truncation of an unflagged POST.** In WATCHING, `divert()` returns `false` unless the attribute is set:
  - The attribute can be set only by the package-private `flag`, from the guard.
  - `GatedWriter` and `GatedStream` forward every `write`, `flush` and `close` to `raw` unchanged. They add no buffering, so the commit, `Content-Length` and chunking points are the container's.
  - Endpoint-set 4xx/5xx still move the state to PASSED.
  - This covers large bodies, gzip bodies (Stapler's `GZIPOutputStream` over the gate), POST downloads and the CLI full-duplex stream.
  - Remaining deviations: S-20-02.
- **Recursion and state.** While rendering the notice, the raw writer and stream are used, and `divert()` returns `false` in RENDERING. The notice's own `setStatus(403)` does not move the state to PASSED, because it is checked only in WATCHING. After REPLACED, every gated write is dropped.
- **Pass-through when off.** `changeControlOn()` reads the same field that `GrantViolationGuard.guardApplies()` reads (`BatchControlGlobalConfiguration.isChangeControlEnabled`). It returns `false` on `IllegalStateException`, which `lookupSingleton` and `Jenkins.get()` throw early in startup. The decision is made per request, with no caching.
- **Filter and CSRF.** Unchanged: the filter never bypasses or reorders the crumb check, and it can only turn a success into a 403.
- **B3 doFill/doCheck, B4 Jelly output.** Not touched: the diff has no resource change, and `grep` finds no `escapeXml` or `<j:out>`.
- **B5 `ACL.SYSTEM2`.** No new switch. The only use is the existing identity comparison in `isUserClickedRetry` (`:376-378`).
- **B6 secrets.** No new flow. The new log lines contain only counts (monitor) and item names (unchanged).
- **B7 paths / B10 XML.** No new path. `ConfigNormalizer` still scans text only and does not parse it.
- **B8 CSV / B9 XStream.** No new exported field and no new persisted type.
- **B13 delegating strategy.** Unchanged by this diff.
- **B14 `@Restricted(NoExternalUse.class)`.** Still on `SelfGrantRevertFilter`. The new classes are private and nested.
- **Information disclosure (item 9).** The monitor is still an `AdministrativeMonitor` visible to Overall/Administer only. The new WARNING names no group or user.
- **Concurrency (item 10).** The step-1 marker consumption is untouched. The monitor cache is still a single volatile snapshot. The filter state is per request (async aside: S-20-04).
- **B15 SpotBugs.** `target/spotbugsXml.xml` has 0 `BugInstance`. It already analyses `GatedWriter`, so it covers this code. It is dated 02:22, before the rebase that rewrote the commit dates to 02:41, so the gate's own `mvn verify` should confirm it.
- **B16.** `.github/workflows/jenkins-security-scan.yml` is present.
- **Tests.** T-06-76 (S-19-01), T-09-23 (S-19-02) and T-01-16 (S-19-03) are in 58bc4d9. S-19-05 and S-19-06 have no regression rows. They were INFO, so none is required.

## Request: src/main/java/io/jenkins/plugins/batchcontrol/listener/SelfGrantRevertFilter.java (core-dev) propagate `checkError()` and forward `write(String,int,int)` without the copy in the gated writer (S-20-02); optionally warn in `flag` when the request is not guarded and catch `RuntimeException` in `changeControlOn()` (S-20-01)
## Request: src/main/java/io/jenkins/plugins/batchcontrol/ops/ConfigureWithoutGrantMonitor.java (core-dev) optionally log the strategy-side group cap, count instead of collecting `unchecked`, and log the truncation only on change (S-20-03)
## Request: src/test/** and docs/TEST-MATRIX.md (test-author) add the Given/When/Then row of S-20-02
