# Spec Review S2

Reviewed: commit `752c38b` (feat: S2 run control), test commit `c56872e`.
Scope: SPEC items 3 (approver designation), 5 (run request and approval), 6 (run path blocking), 7 (expiry and cancellation) + the D-16..D-23 criteria that fall in this slice (upstream empty list, size limits, CAS/check-at-submit, INVALIDATED, single-consumption marker) + the section 4 state machine.

## Verdict: PASS WITH NOTES

## BLOCKER (spec violations, must fix)

- None.

## MAJOR (missing acceptance criteria / must be handled)

- [docs/DECISIONS.md:55] Commit 752c38b, while adding proposal P-03, **destroyed the header of the
  existing P-02 entry**. The leading `**P-02 | 권한 함의 구조·스코프 문서화 ...` of the former row
  was overwritten by the P-03 body, so one line now contains P-03 and P-02 fused together as
  `| 상태: 사람 판정 대기 | 권한 함의 구조·스코프 문서화 (spec-review-S1 MINOR)** | ...` (the P-02
  label is gone). The DECISIONS.md rule is "append only" — this edit corrupted an existing
  proposal. P-02 must be restored as its own row and P-03 separated into an independent entry.
  Registering P-03 itself was legitimate (proposal addition).

## MINOR (defaults, naming, documentation)

- [src/main/java/io/jenkins/plugins/batchcontrol/policy/RunRequestService.java:322-353,
  ops/ExpiryPeriodicWork.java:44] The APPROVED-expiry branch decides "already submitted to the
  queue" from a queue snapshot (`queuedRequestIds`) taken outside the service lock. A request
  whose marker is consumed between the snapshot and the per-request lock acquisition is absent
  from the snapshot, so if the expiry deadline falls exactly inside that few-ms window, a request
  that WAS submitted in time could flip to EXPIRED (edge against the SPEC 7 wording "expires if
  not submitted to the queue"). The request already carries `queuedAt` (the consumption ticket);
  recommend re-checking queue presence when `queuedAt != null` in the APPROVED branch. Real-world
  probability is negligible — MINOR.
- [src/main/java/io/jenkins/plugins/batchcontrol/queue/ApprovalQueueDecisionHandler.java:151-157]
  The blocking guidance links to the global landing page (`<root>/batch-control/`). The actual
  request-creation form lives at `/job/<X>/batch-control/`, so a per-job form link (or both)
  would fit the criterion ("link to the request screen") better. T-06-13 only asserts that
  "batch-control" is present, so the current form passes — improvement recommendation.
- [src/main/java/io/jenkins/plugins/batchcontrol/policy/RunRequestService.java:267-275]
  For D-23 "re-use is blocked and **recorded**", the record is only a `java.util.logging`
  WARNING. test-author deferred the assertion for the same reason (TEST-MATRIX note 15: the
  record location is undefined in SPEC). A human decision is needed on the record location
  (system log sufficient, or ChangeRecord / request history) — recommend a DECISIONS proposal.
  The blocking itself is verified by T-RT-02.
- [src/main/java/io/jenkins/plugins/batchcontrol/model/RunRequest.java:72-85]
  `queuedAtMillis` (D-23 consumption ticket), `expiryBaseMillis` (SPEC 7 restart exception
  implementation) and `invalidationReason` (D-21 history) are fields not listed in the SPEC
  section 3 data model table. All three are implementation vehicles of adopted decisions
  (D-20/D-21/D-23), so they are not removal candidates, but propose updating the section 3
  model table to the human (doc-code consistency).
- [src/test/java/io/jenkins/plugins/batchcontrol/QueueBlockTest.java:37,
  RunRequestWebTest.java:29 (commit 752c38b)] The implementation commit modified two
  test-author-owned files. The content is a `CLICommandInvoker` import package correction
  (`org.jvnet.hudson.test` → `hudson.cli`), one line each — a compile fix with zero change in
  verification meaning. Unlike the S1 precedent this is purely mechanical, but per the CLAUDE.md
  wording it is recorded here for retroactive human approval.
- [docs/TEST-MATRIX.md, row T-RT-02] The matrix wording says the marker is bound to
  "requestId+jobFullName+parameters", but the implementation, SPEC (D-23) and the test bind
  requestId+jobFullName plus single consumption. Parameters are effectively equivalent: the
  approved submission builds the ParametersAction exclusively from the stored parameters (an
  internal SYSTEM2 path) and no user-facing path can attach a marker with arbitrary parameters —
  recommend aligning the matrix wording (test-author).
- [src/main/java/io/jenkins/plugins/batchcontrol/action/BatchControlRootAction.java:29-40]
  Holders of any plugin permission see the "Batch Control" sidebar icon even with
  runControlEnabled=false. This is consistent with records/history being switch-independent
  (D-13) and does not conflict with T-01-05 (job-page based), but a strict reading of "no run
  control UI while off" leaves room for debate. Informational note.

## Out-of-scope additions

- No substantive scope creep. Every new file is an implementation vehicle of items 3/5/6/7 or of
  the delegated carried-over rows (T-02-03/04, T-04-02/04, T-SEC-01/02/05/06,
  T-RT-01/02/03/14/15/16/17/19). `ui/Dates` and `ui/ApproverOptions` are view helpers (no state
  transitions); `Store.listRunRequests` is the minimal addition required by expiry, recovery and
  the list view, with no storage-format change.

## Per-item findings

**SPEC 3 (approver designation)** — implementation and tests both confirmed.
- Non-listed approver designation refused: ApprovalPolicy.checkDesignation:65 / T-03-01.
- Decision-time double check (list membership + Approve permission): checkDecision:88-99 checks
  designated-approver match → `checkPermission(APPROVE)` → `isListedApprover` / T-03-03.
- Self-designation ban (admin exception): checkDesignation:74 + selfApprovalAllowedForCaller
  (allowAdminSelfApproval && ADMINISTER) / T-03-02, T-03-06, T-02-03 (selfApproved=true
  recorded), T-02-04 (separation applies to admins when false).
- Approver change: changeApprover (requester only, PENDING only, new approver re-validated) +
  approverChanges entries (from,to,by,at) / T-03-04, T-03-05.
- Job-level approver restriction: jobApproverRestriction + T-05-06.

**SPEC 5 (run request and approval)** — implementation and tests both confirmed.
- Reason mandatory: create:102 / T-05-02. Rejection comment mandatory: reject:173 / T-05-04.
- D-22 size limits (reason 4,000 / parameter value 10,000 each): create:105-115 / T-RT-19
  (boundary values 4000/10000 asserted as accepted).
- Parameter fidelity: RunRequest.parameters is final + defensively copied (model 105-122); the
  approved submission reconstructs values from the stored parameters through the job's
  ParameterDefinitions (parameterValues:567) / T-05-01. No path to change parameters after
  approval: no such endpoint + append-only 405 guards (RequestItem.doIndex:113,
  RequestsSection.doIndex:60) / T-05-03, T-04-04.
- Cause/Action visibility: ApprovedCause(requestId, requester, approver) + ApprovedRunAction
  persisted on the executed Run / T-05-05.
- JobProperty (approvalRequired, jobApprovers): reused S1 asset, now live in S2.
- **Limitation (P-03 reference, not a new finding)**: sensitive parameters (Password etc.) are
  masked to `********` by JobRequestAction.flatten:169-178 before reaching the service, so no
  plaintext ever reaches disk (the request XML stores only the mask) — verified. The trade-off
  is that jobs with secret parameters cannot reproduce the original value on approved execution;
  the policy is pending the human decision on P-03. T-SEC-07 is unwritten pending that decision
  (matrix file column empty).

**SPEC 6 (run path blocking)** — blocking matrix exhaustively checked.
- All 6 manual paths blocked: build button (T-06-09), POST /build (T-06-01),
  buildWithParameters (T-06-02), CLI build (T-06-03; CLICause is a UserIdCause subtype, matching
  the PoC), Replay (T-06-04; class-name match, quiet refusal justified in a comment — the Replay
  UI has no Failure channel), upstream via build step (T-06-05). No effect when off / job not
  protected: early pass-through at the top of the handler / T-01-01, T-06-16.
- Approved submission passes: marker validated and consumed, then passes / T-06-08.
- Timer passes by default, blockTimer=true blocks quietly (false+log): handler 99-109 /
  T-06-06/07 — matches the ARCHITECTURE section 2 rule "unattended causes: false + log".
- Upstream: `instanceof Cause.UpstreamCause` (covers BuildUpstreamCause, matching PoC and
  ARCHITECTURE section 6), default pass (T-06-10), allow-list pass (T-06-11), off-list blocked
  (T-06-12), **D-16 empty list = block all** (handler 114-126: blockUpstream=false passes
  regardless of the list; true passes only list members — an empty list naturally blocks all) /
  T-RT-01.
- D-23 marker: requestId binding + jobFullName match check + queuedAt/executedRunId ticket for
  single consumption (consumeMarker:254-292, CAS inside the service lock) / T-RT-02 (re-queue on
  the same job and re-submission on another job both refused, build count unchanged). The
  "blocked re-use is recorded" part: see MINOR 3.
- Blocking guidance (no silent failure): user-originated causes throw `Failure` — HTTP 400 +
  guidance + link (T-06-13), CLI exit != 0 with stderr guidance (T-06-14). Exactly the
  ARCHITECTURE section 2/6 dichotomy (user-originated: Failure throw / unattended: false+log).
- Build Now → Request Run: AlternativeUiTextProvider on both message keys (Freestyle and
  Pipeline, PoC assumption D) + JobRequestAction sidebar / T-06-15.

**SPEC 7 (expiry and cancellation)** — implementation and tests both confirmed.
- Defaults: pendingTimeoutHours=72, approvedRunTimeoutMinutes=60 (S1 verification stands,
  matches section 5).
- PENDING expiry → EXPIRED: expireOverdue + re-check on approve entry (a late approval attempt
  itself expires the request, approve:150-155) / T-07-01, T-RT-16.
- APPROVED-unsubmitted expiry: expiryBase-based + queued requests excluded / T-07-03. Restart
  recovery delay exception: recovery moves expiryBase to the recovery instant
  (recoverUnderQueueLock:453) and ExpiryPeriodicWork is a no-op until recovery completes
  (per-session completion flag) / T-07-04.
- Cancellation: requester or Manage holder, PENDING only (cancel:195-215) / T-07-02 (403),
  T-07-05/06/07.
- D-20 CAS: every transition is reload→validate→persist inside a single ReentrantLock (an
  effective CAS combined with the store's atomic tmp→ATOMIC_MOVE rewrite). The lock never spans
  queue operations (lock-order comment present; submission happens outside — avoids inversion
  with the consumeMarker callback) / T-RT-14 (exactly one of two concurrent approvals),
  T-RT-15 (approve vs cancel, no mixed state), T-RT-16 (check-at-submit, matrix note 13
  approximation).
- Check-at-submit: consumeMarker re-validates status, job binding, ticket and expiry at queue
  decision time, and persists EXPIRED on the spot when expiry is detected (267-285).
- D-21 INVALIDATED: onLocationChanged (fires for rename, move, and recursively for folder moves)
  invalidates only PENDING/APPROVED + invalidationReason history + cancels queued marker items
  (RequestInvalidationListener:39-57) / T-RT-03 in three variants (rename swap, move, rename
  after approval — asserting neither the swapped nor the original job runs).
- Restart recovery (item 4 carry-over): in the JOB_CONFIG_ADAPTED band, queue.xml is pre-loaded
  under the queue lock so the dedup sees restored queue items (requestId-based: marker in queue →
  skip resubmission; existing build → skip; otherwise re-issue the ticket and submit exactly
  once; COMPLETED-milestone avoidance justified via JENKINS-37759 in a comment) / T-04-02,
  T-RT-17 (note 14 approximation: session ends after QuietDown submission).

**Section 4 state machine** — exhaustive code-path check: no transition outside section 4.
- The only setStatus caller is policy.RunRequestService (full-source grep). Transitions:
  create→PENDING / approve: PENDING→APPROVED, PENDING→EXPIRED (expiry re-check) /
  reject: PENDING→REJECTED / cancel: PENDING→CANCELLED / consumeMarker & expireOverdue:
  APPROVED→EXPIRED, PENDING→EXPIRED / markExecuted: APPROVED→EXECUTED (APPROVED-guarded) /
  invalidateForJob: PENDING|APPROVED→INVALIDATED. No reverse or skipping transitions.

**Boundary rules (action/ui)** — no violation.
- Zero state transitions across the 4 action and 2 ui classes: RequestItem's four do* endpoints
  are `@RequirePOST` + permission/authentication check, then delegation to RunRequestService
  only (the whole requests subtree is additionally gated by RequestsSection.getTarget's
  checkAnyPermission). JobRequestAction.doSubmit is `@RequirePOST` + Item.READ + REQUEST check,
  then service.create. Zero direct store writes (no store import exists in action/ui). All four
  Jelly views use `escape-by-default='true'`; every state-changing form is `method="post"`.

**ACL.SYSTEM2** — convention respected.
- The only usage is RunRequestService.submitApproved:556 (approved submission / recovery
  resubmission). Reason comment present; permission checks complete before the switch (approval
  path: checkDecision precedes the APPROVED commit; recovery path: approval committed before the
  restart). The queue gate still re-validates the marker after the switch. No other SYSTEM2
  usage (full-source grep).

**Storage format / no effect while switched off**
- No storage layout change (`requests/run/<id>.xml` XStream kept; listRunRequests is read-only).
- Switch off: queue handler passes early, Request Run UI/label hidden (T-01-01/05 stay green).
  RequestInvalidationListener and ExpiryPeriodicWork run regardless of the switches but touch
  only the plugin's own data (requests) and never change Jenkins behavior — rationale stated in
  code comments; legitimate.

## Known carry-overs (not defects; for tracking)

- T-SEC-06 GET-refusal checks for revoke, Incident transitions, switch changes → S3 (stated in
  the matrix).
- T-RT-02 "re-use blocking recorded" assertion → after the record-location decision (matrix
  note 15, linked to MINOR 3).
- T-SEC-07 (secret parameter masking end to end) → depends on the P-03 decision.
- T-RT-07 (consecutive approver-change audit trail, P1) → outside the S2 delegation, no file
  assigned — needs scheduling in a later slice.
- T-E2E-01/02/05/07/08 etc. → Phase 5.

## Unverified

- `mvn clean verify` 79/79 green and SpotBugs 0 are per the commit message and delegation info —
  this review did not re-run the full suite (individual class re-runs judged unnecessary).
- Visual quality of the blocking guidance in a browser (T-06-13 is a WebClient assertion) →
  Phase 5 (same as PoC assumption D).

## Requests

- Request: docs/DECISIONS.md (human) — repair the P-02/P-03 fusion at :55 (restore P-02 as its
  own row, separate P-03 into an independent entry). MAJOR 1.
- Request: docs/DECISIONS.md (human decision) — decide the record location for D-23 "blocked
  re-use is recorded" (system log vs ChangeRecord / request history). Once decided, test-author
  adds the T-RT-02 assertion.
- Request: docs/SPEC.md section 3 (human) — decide whether to reflect the queuedAt, expiryBase
  and invalidationReason fields in the RunRequest model table.
- Request: docs/TEST-MATRIX.md (test-author) — align the T-RT-02 row wording ("parameters
  binding") with the implementation and SPEC (D-23: requestId + single consumption); assign a
  file to T-RT-07.
- Request: src/main/java/io/jenkins/plugins/batchcontrol/policy/RunRequestService.java
  (core-dev, recommendation) — harden the queue-snapshot boundary window in the APPROVED expiry
  branch (MINOR 1); add the per-job request form to the blocking guidance link (MINOR 2,
  queue/ApprovalQueueDecisionHandler.java).
- Request: (human) retroactive approval of the two one-line import corrections in
  test-author-owned files in commit 752c38b (no change in meaning).
