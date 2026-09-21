# Spec Review S4 — Operations (SPEC items 10, 11, 12) + Phase-3 closing assessment

- Scope: implementation commit `f49dbad`, tests commit `f87d8f0`, branch `phase-3-impl`.
- Basis: docs/SPEC.md items 10/11/12 + §3/§4/§5/§6, docs/ARCHITECTURE.md §2/§3/§5, docs/DECISIONS.md D-09/D-10/D-13/D-18/D-19/D-22, docs/TEST-MATRIX.md S4 rows + notes 22–26.
- Build/test evidence: orchestrator-reported `mvn clean verify` green, 147/147, SpotBugs 0 (not re-run by this review, per instruction).

## Verdict: PASS WITH NOTES

No BLOCKER. Two MAJORs (one implementation-hygiene/divergence, one matrix coverage gap at phase close) and one MAJOR-graded state-machine fidelity question that needs either a one-line tightening or a human ruling. All S4 acceptance criteria that have matrix rows are implemented and pass, with the known deferrals noted below.

## BLOCKER (spec violation, must fix)

None.

## MAJOR

1. **[ops/IncidentService.java:136–152] Extra transition not in SPEC §4: `resolve()` accepts OPEN → RESOLVED (ACKNOWLEDGED skipped).**
   SPEC §4 draws `OPEN -> ACKNOWLEDGED -> RESOLVED` and the service refuses only when the incident is already RESOLVED, so a direct `POST /batch-control/incidents/<id>/resolve` on an OPEN incident succeeds. The UI does not offer it (`IncidentItem.isCanResolve()` requires ACKNOWLEDGED, `action/IncidentItem.java:108–110`), but the HTTP endpoint and service API allow it. Forward-only and the audit trail are intact, so this is not graded BLOCKER, and "resolve without a separate acknowledge step" is a defensible operational reading — but it is a transition the SPEC does not list and no test pins either behavior. Request: **core-dev** — either require `ACKNOWLEDGED` as the source state in `resolve()`, or a human ruling via DECISIONS proposal; **test-author** — add the corresponding assertion once decided.

2. **[ops/HistoryService.java (whole file), store/CsvSupport.java (whole file)] Dead parallel implementations that semantically diverge from the live ui code.**
   Neither class has a single reference anywhere in src/main or src/test outside itself (verified by grep). The live implementations are `action/HistorySection.summarize` (serves `GET history/summary`, i.e. **T-12-04 is verified against HistorySection, not HistoryService**) and `ui/CsvWriter.encode` (serves all four CSV exports, T-RT-11/T-12-03).
   The two summary implementations already disagree today:
   - *incidentsOpen*: `HistoryService.monthlySummary` (ops/HistoryService.java:163–171) counts OPEN **and ACKNOWLEDGED** as open; `HistorySection.summarize` (action/HistorySection.java:412–420) counts **only OPEN** — an acknowledged-but-unresolved incident silently disappears from both aggregate counts on the live endpoint.
   - *requestsApproved*: HistoryService (ops/HistoryService.java:172–184) counts every decided-non-REJECTED request (approved-then-EXPIRED/INVALIDATED included) as approved; HistorySection (action/HistorySection.java:421–435) counts only status APPROVED/EXECUTED — an approved request that later expired drops out of both counts.
   T-12-04's fixture contains neither an ACKNOWLEDGED-only incident nor an approved-then-expired request, so both variants pass it; the divergence is live but untested. `CsvSupport` and `CsvWriter.encode` are byte-identical in behavior today (same D-18 prefix set, same RFC 4180 quoting, same order) — pure duplication, i.e. a future divergence risk for a security control (D-18).
   Request: **core-dev/ui-dev (orchestrator to route)** — pick one summary implementation (delete the other or make HistorySection delegate), and keep exactly one CSV sanitizer; **human (DECISIONS proposal, suggest P-08)** — fix the counting semantics: does ACKNOWLEDGED count as "OPEN" in the SPEC-12 aggregate, and does an approved-then-EXPIRED/INVALIDATED request count as approved?

3. **[docs/TEST-MATRIX.md:136] T-RT-10 (P1, SPEC §6 output-escaping criterion, D-18/R-3) has no test at phase close.**
   The row's test-file column has been blank since Phase 2 and no `t_rt_10_*` method exists anywhere in src/test. It is the only P1 matrix gap (see closing numbers). The implementation side looks sound — every S4 Jelly view declares `escape-by-default='true'` and no `escape="false"`/`<j:out>` raw output exists in any action view — but the acceptance criterion ("payloads render as inert text on request detail, approval screen, dashboard") is unverified. Request: **test-author** — implement T-RT-10 (WebClient render of `<script>`/`<img onerror>` payloads in reason/parameter values across request detail, approval screen, dashboard).

## MINOR (defaults, naming, docs)

1. **[ops/RetentionPeriodicWork.java:35] `PeriodicWork` instead of ARCHITECTURE §2's `AsyncPeriodicWork` — deviation accepted.**
   Documented in the class Javadoc: `AsyncPeriodicWork.doRun()` is `public final` and only schedules a background thread, while matrix note 2 requires a synchronous, directly callable `doRun()` (HistoryWebTest calls `lookupSingleton(...).doRun()` and asserts completed cleanup on return); a once-daily directory sweep is cheap enough inline. Functionally equivalent (daily recurrence kept). Request: **human** — update the ARCHITECTURE §2 "만료·보관 정리" row to name `PeriodicWork` for the retention job (human-owned file).

2. **[action/IncidentItem.java:148,157,166] Incident transitions gated by `VIEW_HISTORY` — deviation accepted with a documentation request.**
   SPEC 11 says "담당자" without naming a permission. Choosing the weakest plugin permission that already gates the incident screens is coherent and satisfies the POST+permission-check rule (all three endpoints are `@RequirePOST` with the check as the first line; verified refused-on-GET by the T-SEC-06 sub-cases). Note the consequence: every history viewer can acknowledge/resolve/comment. `doRerun` correctly requires `REQUEST` instead (action/IncidentItem.java:179). Request: **human (DECISIONS)** — record "incident handling requires ViewHistory" as a decision, or introduce a dedicated permission in a later SPEC revision.

3. **[store/FileStore.java:114,244 vs ops/RetentionPeriodicWork.java:50–52, ops/HistoryService.java:199–205, action/HistorySection.java] Month-bucketing zone inconsistency.**
   FileStore buckets run records and incident index lines by `ZoneId.systemDefault()`, while retention, history filtering and the summary compute months in `BatchClock.clock().getZone()`. Identical in production (BatchClock defaults to the system zone) and the S4 tests dodge it deliberately (mid-month noon UTC fixture), but a test-overridden or future configurable clock zone can put a boundary record in a bucket the query/retention side disagrees with. Request: **core-dev** — bucket via `BatchClock` zone in FileStore for consistency.

4. **[commit f49dbad, src/test/.../IncidentTest.java:317] Implementation commit touched a test file.**
   Single change: `scheduleBuild2(0, null, ...)` → `scheduleBuild2(0, (hudson.model.Cause) null, ...)` — an ambiguous-overload compile fix with zero semantic change to the assertion. Test independence is materially intact, but the path-ownership rule (src/test → test-author) was crossed; noting for retroactive approval, same precedent as the S2 CLICommandInvoker import fix.

5. **[ops/IncidentService.java:259–278] D-19 documentation duty is still open.**
   Masking implements exactly D-19 (build's sensitive parameter plaintexts + encrypted `Secret` payload shapes via `SecretMasker.mask`; verified by T-RT-13). SPEC 11 additionally requires the detection limit ("other secrets echoed to the console may not be masked") to be **stated in the docs** — that sentence must land in README's known-limitations section. Request: **release-manager (Phase 7)** — README known limitations: D-19 masking scope, plus the existing RT-08 note.

6. **[ops/IncidentService.java:183–205] Rerun of a job with secret parameters resubmits the masked value** (incident stores parameters masked, `rerun` prefills from them). This is exactly the P-03 known limitation (T-SEC-07 pending) — no new finding, recorded here so P-03's eventual ruling also covers the incident-rerun path.

## Scope excess

- **[action/DashboardSection.java:44–45,82–98] `?days=N` window selector (cap 365).** SPEC 10 fixes only the default (7 days / 50 per page — both honored: DEFAULT_DAYS=7, PAGE_SIZE=50). A read-only widening knob with validation and a hard cap; keep, no DECISIONS entry needed.
- **[ui/FilterParser.java:29–32] History default range 30 days, span cap 36 months.** SPEC 12 sets no history default; defensive bounds on how many month files one request may scan. Acceptable.
- **[ops/IncidentService.java:159–173] `addComment` allowed in every status**, not only RESOLVED. SPEC §4 explicitly allows comments on RESOLVED; allowing them earlier is a harmless superset consistent with transitions carrying comments. Acceptable.

## S4 acceptance-criteria coverage (implementation + test mapping)

| Criterion | Implementation | Test |
|---|---|---|
| 10: Freestyle + Pipeline recorded | listener/RunRecordListener.onFinalized (single append point) | T-10-01 ✓ |
| 10: cause classification USER/TIMER/UPSTREAM/APPROVED_REQUEST/SCM/OTHER | RunRecordListener.classify (ApprovedCause wins; BuildUpstreamCause ⊂ UpstreamCause) | T-10-03 ✓ |
| 10: abortedBy on ABORTED | RunRecordListener.abortedBy via InterruptedBuildAction (ARCHITECTURE §2) | T-10-02 ✓ |
| 10: APPROVED_REQUEST links to request detail | RunRecord.runRequestId + dashboard Jelly link + request.executedRunId | T-10-04 ✓ |
| 10: default 7 days / 50 per page | DashboardSection DEFAULT_DAYS=7, PAGE_SIZE=50 | T-10-06 → Phase 5 e2e (known deferral) |
| 10 (§6 compat): multibranch record-only | listener applies to every Run | T-10-07 ✓ (approximated, note 22) |
| 11: incident regardless of cause, incidentResults honored | IncidentService.openForRun (result-set check only; D-10/D-13) | T-11-01/03/07 ✓ |
| 11: OPEN→ACK→RESOLVED forward-only, transitions carry user/time/comment | IncidentService.acknowledge/resolve under one lock | T-11-04/05 ✓ (but see MAJOR 1: OPEN→RESOLVED skip untested/allowed) |
| 11: comments on RESOLVED allowed | IncidentService.addComment | T-11-05 ✓ (matrix note 24 honored: comment appended to transitions) |
| 11: rerun prefilled + incidentId + rerunRequestIds | IncidentService.rerun + RunRequestService.create(..., incidentId) | T-11-06 ✓ |
| 11: successful linked rerun sets resolvedByRunId, never auto-resolves | RunRecordListener.linkResolvedRerun + IncidentService.linkResolvedBy (status untouched) | T-11-02 ✓ |
| 11: logTail last 100 lines | LOG_TAIL_LINES=100, `run.getLog(100)` | T-11-01 ✓ |
| 11 (D-19): logTail masks sensitive param plaintexts + Secret payloads | IncidentService.maskedLogTail + SecretMasker; parameters masked via maskedParameters | T-RT-13 ✓ (doc duty → MINOR 5) |
| 12: period filter + CSV for runs/incidents/changes/requests | HistorySection + FilterParser + CsvWriter | T-12-03 ✓ |
| 12: monthly aggregate (8 counts) | HistorySection.summarize + doSummary JSON (matrix note 23 contract keys) | T-12-04 ✓ (see MAJOR 2 for the duplicate) |
| 12: ViewHistory gates all screens + CSV (403) | StaplerProxy.getTarget on Dashboard/Incidents/History sections | T-12-01/05, T-10-05 ✓ |
| 12: retention deletes expired month files + ChangeRecord(RETENTION) | RetentionPeriodicWork + FileStore.deleteMonth | T-12-02 ✓ |
| 12 (D-18): CSV formula neutralization | CsvWriter.encode (sanitize-then-quote, correct order) | T-RT-11 ✓ (note 26 shape) |
| §6: state changes POST-only | @RequirePOST endpoints; read URLs 405 non-GET | T-SEC-06 sub-cases ✓ (note 25) |

Retention specifics verified: deletion is strictly month-buckets older than `currentMonth − retentionMonths` (RetentionPeriodicWork.java:50–57, conservative: current partial month + retentionMonths full months kept); the RETENTION ChangeRecord's `target` is the deleted month as `YYYY-MM`; `FileStore.deleteMonth` is the store's only record-deletion path (the pre-existing `deleteConfigSnapshot` removes diff working state, not audit records) and it deletes exactly the month's runs/changes JSONL, that month's `<yyyyMM>*.patch` diffs, and the month's incident XMLs + index. Append-only otherwise preserved (no modify/delete HTTP API — T-04-04 from S2 still green).

Recording independence (D-13) verified: `ChangeRecording.isActive()` (any switch on) gates the whole listener; with both switches off nothing at all is written; incidents open regardless of the build's cause and depend only on `incidentResults`.

Boundary judgment (ARCHITECTURE §3): actions contain no state-transition logic — `IncidentItem` delegates every mutation to `IncidentService` and its `isCan*` getters are view gating that the service re-validates. `HistorySection`'s in-action filtering/aggregation calls only public read methods of store/ops/policy and mutates nothing; I judge that **acceptable read-only composition**, not a boundary violation — the §3 rule's target (state transitions in `action`) is respected. The real defect it creates is the duplication of MAJOR 2, which should be resolved by deleting or delegating, not by reclassifying the boundary.

## Phase-3 closing assessment (matrix: 127 rows = 118 non-e2e + 9 e2e)

Covered non-e2e rows: 115 of 118. The three uncovered rows: T-SEC-07 (P0, pending P-03), T-RT-10 (P1, no test — MAJOR 3), T-RT-18 (P2, deferred by D-22).

- **P0 (gate: 100% non-e2e): 76/77 = 98.7% — formally NOT met; effectively met minus one human-blocked row.**
  The single gap is T-SEC-07, which cannot be written until the human decides P-03 (secret-parameter storage design); it is not an implementation omission. All other 76 non-e2e P0 rows have passing tests. Gate recommendation: accept Phase 3 with T-SEC-07 explicitly carried as a release-blocking follow-up tied to P-03 (it must be green before Phase 7 release, since it is P0).
- **P1 (gate: ≥ 90% non-e2e): 34/35 = 97.1% — met.** Gap: T-RT-10 (route to test-author, MAJOR 3).
- P2 non-e2e (informational): 5/6 (gap: T-RT-18, deferred to phase 2 of the product per D-22).
- e2e rows (3× P0, 5× P1, 1× P2) are Phase 5 by design and excluded from the gates above.

## Requests (for the orchestrator to route)

- core-dev — `ops/IncidentService.resolve`: require ACKNOWLEDGED as source state, or obtain a human ruling (MAJOR 1).
- core-dev + ui-dev — remove or wire `ops/HistoryService` and `store/CsvSupport` so exactly one summary and one CSV sanitizer exist (MAJOR 2).
- test-author — implement T-RT-10 (MAJOR 3); add the OPEN→RESOLVED assertion once MAJOR 1 is decided.
- human (DECISIONS) — new proposal for aggregate counting semantics (ACKNOWLEDGED in incidentsOpen? approved-then-EXPIRED/INVALIDATED in requestsApproved?) (MAJOR 2); record the VIEW_HISTORY-gates-incident-handling decision (MINOR 2); ARCHITECTURE §2 retention row `AsyncPeriodicWork` → `PeriodicWork` (MINOR 1).
- core-dev — FileStore month bucketing via BatchClock zone (MINOR 3).
- release-manager (Phase 7) — README known limitations: D-19 masking scope; P-03/rerun masked-parameter limitation (MINOR 5/6).
- Unverified items: none beyond the orchestrator-reported build result (mvn not re-run per instruction); everything else above was verified by reading the code and tests at commit `f49dbad`.
