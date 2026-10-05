# Spec Review S7

Scope: `cb5ad5d..2ff94c8` on `r6/file-params` (docs, core, ui, tests, TEST-MATRIX, README, README.ko, LIMITATIONS, pom). The decisions checked are D-72 (typed parameter values; supersedes P-03 option 1, amends LIMITATIONS 16) and D-72a (body cap limits, validated rerun fallback link) against SPEC item 5 (lines 95-97), item 6 (line 112, D-60 as amended), item 11 (line 202), sections 3, 4, 5 and 6 (line 295), and ARCHITECTURE section 2 (line 16), section 5 (lines 104-105) and section 6 (lines 133-137).
Reviewed 2026-10-05 by spec-guardian. Read-only; only this report was written.

Build evidence: no Maven run in this worktree (the caller's gate is running here). Whether the suite passes at 2ff94c8 on JDK 21 and 25 is unconfirmed in this report; the caller's gate is the evidence. The older logs in the session scratchpad are runs on intermediate commits (763f994, b7ce183) and show the expected red rows only.
Probe: `git archive 2ff94c8` into the session scratchpad (`specS7/`) plus one uncommitted probe test, offline Maven, JDK 21.0.12, two runs, BUILD SUCCESS. Results are quoted in M-1 and M-2. Nothing from the probe is committed.

## Verdict: PASS WITH NOTES

There is no blocker. Every D-72 and D-72a clause has an implementation that matches SPEC, with one exception, M-1: an approved run that Batch Control's queue gate accepted but a later queue handler refused leaves its temporary files. Disposal for "approved run could not be queued" and for an expired approval has no matrix row (M-2). The P-03 supersession is carried through correctly (T-SEC-07 rewrite, note 44). No D-71 code is on this branch. Issues #107 to #114 are not implemented, and #115 carry-over is not implemented (only the "select the file again" notice).

## BLOCKER (spec violation, must be fixed)

None.

## MAJOR (missing acceptance criteria)

- **M-1** SPEC 5 line 96 / D-72: "When a request ends without a run (rejected, cancelled, expired, invalidated, or its approved run could not be queued) Batch Control disposes of those temporary files." One case of "could not be queued" is not disposed.
  - Mechanism: `policy/RunRequestService.java:615-616` (`consumeMarker`) claims the ticket (`queuedAt`) inside Batch Control's `QueueDecisionHandler`. A handler that runs after it can still refuse the submission. `Queue.schedule2` then returns refused and `submitApproved` (`RunRequestService.java:1022-1027`) leaves the request APPROVED with `queuedAt` set and nothing queued. On expiry, `RunRequestService.java:726-730` disposes only when `queuedAt == null`, and `invalidateForJob` (`:827-831`) applies the same rule. So the request ends EXPIRED or INVALIDATED without a run and its files stay. The comment at `:726-727` assumes "a claimed ticket means the queue took the values", and that assumption is false in this case.
  - Probe (confirmed): handlers of equal ordinal run in class-name order. The observed order was `FolderJobQueueDecisionHandler`, `ApprovalQueueDecisionHandler`, `jenkins.branch.*Dispatcher` x3, then core's `jenkins.model.queue.ItemDeletion`. The probe ran `ItemDeletion.register(job)` (core's veto for an item being deleted), then approved the request, then ran `deregister`, then `ExpiryPeriodicWork.doRun()` at T0+3h. Result: APPROVED with `queuedAt=T0`, no build, empty queue. Then EXPIRED with `queuedAt=T0`, and the request's file under `fileParameterValueFiles/` was still there.
  - Why not a blocker: in core, the only trigger found is approving while the job is being deleted. Other plugins' handlers that sort after `io.jenkins.plugins.batchcontrol.queue...` and refuse are unconfirmed. The other "could not be queued" cases are disposed: a refusal before the claim (probe run 1: EXPIRED and the file is gone), a missing or unbuildable job (`queuedAt` stays null), and Batch Control's own expired-approval refusal (`:604-609`).
  - Fix (core-dev): when a submission was refused after the ticket was claimed, its values never reached the queue, so the request's end must dispose of them. `scheduleBuild2` is synchronous, so a null result after a claim made by that same call is a reliable sign. Keep two constraints: startup recovery re-issues the ticket of an APPROVED request (`:907-910`), so files must not be disposed while the request can still be resubmitted; and a build that has just left the queue must keep its files. Then LIMITATIONS 32 (`docs/LIMITATIONS.md:378-379`, "approved but impossible to queue") is true as written.
- **M-2** The same SPEC line: three listed end states have no matrix row or test.
  - Rows T-05-53..58 (`docs/TEST-MATRIX.md:247-252`) cover REJECTED, CANCELLED, pending EXPIRED, INVALIDATED of a PENDING request, the per-request guard and the queued case.
  - No row covers: APPROVED -> EXPIRED by `approvedRunTimeoutMinutes` while not queued; "its approved run could not be queued"; INVALIDATED of an APPROVED request that was never queued (`RunRequestService.java:829`).
  - Note 260 (`docs/TEST-MATRIX.md:1246`) says "no public input makes the queue refuse an approved request deterministically". That is not accurate:
    - A `@TestExtension` `Queue.QueueDecisionHandler` whose class name sorts before `io.jenkins.plugins.batchcontrol.queue.ApprovalQueueDecisionHandler` refuses before the claim (probe run 1, any class directly in `io.jenkins.plugins.batchcontrol`).
    - Core's `jenkins.model.queue.ItemDeletion.register(job)` refuses after the claim (probe run 2).
  - Fix (test-author): rows for both orderings (APPROVED, then EXPIRED after the approved-run timeout via `BatchClock` and `ExpiryPeriodicWork.doRun()`, then the request's files are gone) and for a rename of an APPROVED, unqueued request. The after-claim row fails today (M-1) and is the falsifier for its fix. Correct note 260's gap sentence.

## MINOR (defaults, naming, documentation)

- **m-1** SPEC 5 line 95, "A `PasswordParameterValue` and any other `Secret` field are stored only in Jenkins' encrypted form". Only `PasswordParameterValue` is tested (T-05-45, T-05-46, T-SEC-07). The implementation relies on XStream's `Secret` converter, and the display relies on `store/ParameterDisplay.java:66-76` (`isSensitive()` or a raw `Secret`). No row covers a non-Password value type with a `Secret` field (encrypted in `requests/run/<id>.xml`, `********` everywhere, original delivered). Owner: test-author.
- **m-2** Default cap. `policy/RequestBodyLimit.java:25` is 104,857,600 bytes (100 MiB). That matches SPEC's "100 MB", ARCHITECTURE line 134, LIMITATIONS 31 and the 413 message (`JobRequestAction.sizeText` renders "100 MB"). No test pins the default: T-05-63 (`docs/TEST-MATRIX.md:257`) proves only that 1 MiB is accepted with the property unset. Owner: test-author, either a row or a recorded gap.
- **m-3** Refusing bodies of undeclared length (`policy/RequestBodyLimit.java:48-50`).
  - SPEC 5 line 97 refuses a body "larger than the cap", and D-72a says the decision comes "from the declared length". The code also refuses every chunked body and every multipart body without a `Content-Length`, whatever its size.
  - The form now always posts multipart (`action/JobRequestAction/_form.jelly:31`). A client, or a front end that forwards the body chunked, therefore gets 413 for every run request. Whether common reverse-proxy defaults do that is unconfirmed.
  - It is documented in LIMITATIONS 31 (`docs/LIMITATIONS.md:340-341`). See O-1.
- **m-4** Request files written before D-72.
  - `model/RunRequest.java:201-202` returns an empty list when `parameterValues` is absent, so `submitApproved` (`RunRequestService.java:1012-1015`) schedules without a `ParametersAction`. Before D-72, the approval rebuilt the values from the stored map. Now such a request runs with the job's defaults instead of the values the approver saw (SPEC 5 line 95, "the approved build receives exactly those values").
  - The plugin was never released (D-35e and D-43 precedent), but no DECISIONS or ARCHITECTURE line says that pre-D-72 request files are not converted, as D-69 does for grants. Owner: human (a DECISIONS line), or core-dev if the owner wants such an approval refused.
- **m-5** "Derived once at submission".
  - SPEC 5 line 95 and ARCHITECTURE line 104 say the request's masked map is the form shown in "run records, incidents". In the code, run records and incidents mask the build's own values (`ops/IncidentService.java:346-349`, used by `listener/RunRecordListener.java:94` and `IncidentService.java:107`), with the same function (`ParameterDisplay`).
  - The text is identical for the same values. It differs only when core drops parameters the job no longer defines at build time. This derivation path existed before D-72.
  - Proposal: the human aligns the SPEC/ARCHITECTURE wording ("run records and incidents show the build's values masked the same way"). Code change not requested.
- **m-6** LIMITATIONS 32 (`docs/LIMITATIONS.md:360` "request files are never pruned", `:369` "because request files are not pruned, storage grows with every such upload") contradicts ARCHITECTURE section 5 line 102 and `ops/RetentionPeriodicWork.java:84-90`. Closed run requests older than `retentionMonths` (default 24) are deleted. The sentence existed before D-72, but the D-72 edit builds its base64File storage argument on it. Owner: release-manager.
- **m-7** LIMITATIONS 16 (`docs/LIMITATIONS.md:224-229`) says "every value of a build that has been deleted" cannot be recovered, and then says the form gets "the recoverable non-sensitive values". For a deleted build, the code (`ops/RerunParameters.java:70-78`, `prefillFromRecord`) and T-11-12 prefill the non-sensitive values recorded on the incident. State that. Owner: release-manager.
- **m-8** `README.md:422-426` and `README.ko.md:285-286` say the request submitted from the fallback form "is still linked to the incident" without a condition. SPEC 11 (D-72a) links it only after re-validation: the incident exists, it belongs to that job, and the submitter holds `ViewHistory`. LIMITATIONS 16 states the condition correctly. Owner: release-manager.
- **m-9** TEST-MATRIX note 263 (`docs/TEST-MATRIX.md:1247`) cites "LIMITATIONS items 16, 31 and 49 (31 as amended in 10cf566 ...)", "LIMITATIONS 31 naming the channels" and "select the file(s) again (LIMITATIONS 49)". 10cf566 amended item 32 (the channels and the refused re-runs left alone). The D-60 URL and file item is 48; item 49 is the Build-permission notice. Owner: test-author.
- **m-10** Test gaps beyond the acceptance minimum. Owner: test-author, a row each or a recorded gap.
  - (a) "Batch Control does not copy it": the store is scanned for the content only for `base64File` (T-05-44); T-05-42 and T-05-43 check surfaces, not `$JENKINS_HOME/batch-control/`, for core `file` and `stashedFile`.
  - (b) Incident rerun reuse of a `base64File` value, which LIMITATIONS 16 claims, is untested.
  - (c) A rerun refused after a core file was recreated (the copy must be disposed, `RunRequestService.java:353-357`) is untested.
  - (d) No D-72 row runs with run control off. Code check: `queue/ApprovalQueueDecisionHandler.java:368-370` returns before any D-72 code. The uncontrolled twins in T-05-65..68 run with run control on.
- **m-11** Stale P-03 citations for the D-60 rule "sensitive values are never carried": T-06-89..96 (`docs/TEST-MATRIX.md:353-360`) and `src/test/java/io/jenkins/plugins/batchcontrol/RefusedBuildPrefillTest.java:55,73`. SPEC line 112 now states the rule itself; the behaviour is unchanged. Owner: test-author (citation only).

## Out-of-scope items

- **O-1** Refusing bodies of undeclared length (m-3) is a refusal rule SPEC does not state. It is a DECISIONS proposal for the human: keep it as documented (and add a sentence to D-72a), or limit the 413 to a declared length over the cap (core has already parsed such a body during dispatch, per D-72a (1)).
- **O-2** Disposal of refused direct builds (`queue/ApprovalQueueDecisionHandler.java:483` token, `:523` person's own submission) is not in SPEC 5's disposal line, which speaks of requests. It rests on SPEC 6 D-60 ("nothing is queued and nothing is stored until the requester submits the form"), and LIMITATIONS 32 documents the channels and the exclusions. Keep it. It also settles the last sentence of issue #115 ("The temporary copies made by the refused submission are also left behind"), so #115 should be narrowed to the carry-over (human, issue text).
- **O-3** The typed service API now refuses an unknown or foreign `incidentId` (`policy/RunRequestService.java:343-346`, `ops/IncidentService.java:280`). D-72a states that for the form reference; extending it to the service API matches "never trusted", and T-11-18 pins it. Keep; no action.
- **O-4** UI details SPEC does not state: the fallback form starts with the generated rerun reason (`action/JobRequestAction.java:328`), and the "provide again" notices (`_form.jelly`). They are consistent with the section 6 usability line. No action.
- Not present (checked): no D-71 code (`itemKind`, scope type, `FOLDER_ONLY` absent from the `src` diff; only the shared docs commits b1fa41e and 1f0fb1f carry D-71 text). #107 to #114 are not implemented; for #111, `_form.jelly:122` still has `page="index.jelly"`. #115 carry-over is not implemented: `ui/RequestRunPrefill.java:270-273` carries only non-password `SimpleParameterDefinition`s, and only the notice is shown (`_form.jelly:80-87`).

## Checks that passed

1. Acceptance coverage (implementation / rows):
   - SPEC 5 line 95, typed values reach the approved build (core `file`, `stashedFile`, `base64File`, password, simple types): `RunRequestService.java:1011-1015` / T-05-41, 43, 44, 45, 47, T-SEC-07.
   - Masked display everywhere (`********`, `[file] <name>`): `store/ParameterDisplay.java`, `RunRequestService.java:331` / T-05-42, 44, 46, T-11-08, 09, 11, T-SEC-07, T-SEC-19.
   - Approval matching unchanged: T-05-48. D-22 limit on the display form: T-05-49, 50, LIMITATIONS 31.
   - SPEC 5 line 96: REJECTED, CANCELLED and both EXPIRED paths dispose (`RunRequestService.java:416, 473, 501, 609, 713, 728-730`). INVALIDATED of a pending request disposes (`:829-831`) / T-05-53..58. Exceptions: M-1, M-2.
   - SPEC 5 line 97: the cap comes after `Item/Read` and `Request` and before any body read (`action/JobRequestAction.java:469-479`). It answers 413 with the empty form (`:574`), keeps the dialog view and the validated rerun reference from the query string, and reads the property on every check (`RequestBodyLimit.java:31-34`) / T-05-59..64, T-UI-113, 114, 118, T-11-19. The FILEUPLOAD_MAX_SIZE wording is in LIMITATIONS 31, as D-72a requires.
   - SPEC 6 line 112 (D-60 as amended): files are never carried and the form names them; the refused submission's files are disposed / T-UI-116, T-05-65..69.
   - SPEC 11 line 202, rerun reuse:
     - original secret: T-11-08; recovered core file: T-11-11.
     - `RerunNeedsFormException` and the 302 to the prefilled form: `action/IncidentItem.java:262`, `ops/RerunParameters.java` / T-11-09, 10, 12. No secret is prefilled.
     - D-72a link only after `IncidentService.linkableIncident` (`IncidentService.java:254`, `JobRequestAction.java:489`) / T-11-13..20, T-UI-115.
   - SPEC 4 restart durability: T-05-51, 52. SPEC 6 security line: T-05-45, 46, T-SEC-07.
2. Boundaries:
   - `action`/`ui` hold no state transition and write nothing to the store. They call `RunRequestService`, `IncidentService.rerun` and the read-only `linkableIncident`.
   - Typed values are read only in `RunRequestService` (scheduling, disposal). `RunRequest.parameterValues()` is `@Restricted` and not a bean getter, and T-05-46 checks the pages and APIs.
   - Each commit stays within one owner's paths: core 114ab4b and 763f994; ui 68f52c3, b7ce183 and 82385fc; tests d6a7ab9 and 3434978; docs e0c7dbf and 10cf566; pom 2d083c1 (test-scope dependency only).
   - Human-only docs: b1fa41e, 1f0fb1f, and 885eacc (D-72a, recorded as an owner decision). Which agent or person made each commit is unconfirmed, since all carry the same git identity.
3. Defaults: the SPEC section 5 table is unchanged and no global setting was added. The cap default matches (m-2 is about the test only).
4. State machine: no new RunRequest transition. Disposal is added only after the existing REJECTED, CANCELLED, EXPIRED and INVALIDATED commits. The new incident check refuses creation and is not a transition.
5. Storage format: `requests/run/<id>.xml` gains `parameterValues` (XStream) next to the masked `parameters` map, as ARCHITECTURE section 5 says. No new file under `batch-control/`. Recreated rerun files go to core's own directory.
6. Switch off: `ApprovalQueueDecisionHandler.java:368-370` returns before any disposal. Listeners only change the recorded text (`RunRecordListener.java:94`). `ParameterFiles` runs only when a request ends or a submission is refused.
7. Test contract:
   - T-SEC-07 is a decision-driven rewrite (note 260). The withdrawn "the build receives the mask" assertion became a stricter equality: a sensitive `PasswordParameterValue` equal to the typed secret (`src/test/java/io/jenkins/plugins/batchcontrol/SecretParameterMaskingTest.java:195-202`).
   - Every "no plaintext" assertion and the `PLAIN_PARAM` twin are unchanged; the diff touches only the delivery block and Javadoc. T-SEC-19 changed in wording only. Note 44 is superseded.
   - No other existing test was modified. New rows carry premises, twins and blocking triples.
8. CLAUDE.md conventions: no new `do*` method and no new `ACL.SYSTEM2` switch. `doSubmit` still begins with its permission checks, and the reason comment of the existing SYSTEM2 block was updated to D-38a.

## Unconfirmed

- The test results of 2ff94c8 on JDK 21 and 25: the caller's gate was still running when this report was written.
- Whether common reverse proxies forward run request bodies chunked or without a length (m-3).
- Whether any commonly installed plugin has a `QueueDecisionHandler` that sorts after Batch Control's and refuses approved submissions (M-1 reach beyond core's `ItemDeletion`).
- The new job page's parameters dialog with file parameters (refusal disposal through that channel) is left to e2e, as note 263 says.

## Requests

- Request: `src/main/java/io/jenkins/plugins/batchcontrol/policy/RunRequestService.java` (core-dev) — M-1: dispose of the values of an approved request whose submission was refused after the ticket was claimed, at the point the request can no longer run. Mind startup recovery (`:907-910`) and builds that have just left the queue.
- Request: `src/test/**`, `docs/TEST-MATRIX.md` (test-author) — M-2 rows (approved-run timeout, refused before and after the claim, rename of an APPROVED unqueued request) and the note 260 correction; m-1, m-2 and m-10 rows or recorded gaps; m-9 and m-11 citation fixes.
- Request: `docs/LIMITATIONS.md` (release-manager) — m-6 (retention prunes closed requests), m-7 (a deleted build's recorded values are prefilled), and after M-1 is fixed, recheck item 32 lines 378-379.
- Request: `README.md`, `README.ko.md` (release-manager) — m-8: the fallback request is linked after validation (same job, `ViewHistory`).
- Request: `docs/DECISIONS.md` (human) — O-1 (refusing bodies of undeclared length: keep and record it, or drop it), m-4 (pre-D-72 request files are not converted, or are refused on approval), m-5 (run record and incident wording in SPEC 5 and ARCHITECTURE section 5).
- Request: issue #115 text (human) — narrow it to the carry-over; disposal of refused submissions is done here (O-2).
