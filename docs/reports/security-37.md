# Security Review 37: re-check of typed parameter values (D-72b; `r6/file-params`, head 7168e4a)

Reviewer: security-reviewer. 2026-10-05.

Scope: closure of security-35 (S-35-01..07) and the new code in `git diff 44b157f..HEAD -- src/main`
(core 4fd2fac, ui a76f194; 18 files). Standard: HOSTING-CHECKLIST section B, CLAUDE.md code rules,
DECISIONS D-72b, ARCHITECTURE storage bullets "Run request file layout (D-72b)" and "Incident file".
This is a focused re-check, not a full re-review: the earlier probes were re-run and the new code
was read in full (lazy listing reader, partial save, parsed-size cap, released claim, queue
listener, U+FFFD echo, changed `do*`).

Method: nine probes ran as a throw-away JenkinsRule test (`Sec37ProbeTest`) in a `git archive HEAD`
copy in the session scratchpad. Nothing ran in the worktree and nothing was committed apart from this
report. Stapler 2088.2093 (`RequestImpl`, `Stapler$4`) and core 2.568.3 / file-parameters 433 were
read as bytecode where the finding depends on them. Probe output is quoted as "Probe".

## Summary: BLOCKER 0 / HIGH 0 / MEDIUM 1 / LOW 0

All seven security-35 findings are closed. Approval now fails closed on every route probed:
repeated names, unknown classes, missing values and nameless values are all refused before
anything is scheduled. Listings no longer deserialize the values, a failed save leaves nothing
behind, and a cancelled approved queue item is not resubmitted. The new lazy reader and partial save
held up against crafted values. The one new problem is in the D-72b (4) parsed-size cap: it counts
only the parts it predicts the submission will read, and two simple encodings get past that
prediction. This lifts the body cap for chunked bodies (S-37-01).

## Closure of security-35

| Finding | State | Evidence |
|---|---|---|
| S-35-01 (BLOCKER) repeated name | **Closed** | Probe: u1 POSTs `json` with `TARGET=staging` and `TARGET=production`: 400 with "Parameter 'TARGET' was submitted more than once", 0 requests created. `create(job, [TARGET=a, TARGET=b])` throws IAE with the same message. A stored file with a second `TARGET` element: approve refuses (ISE "parameter 'TARGET' is stored more than once"), status PENDING, 0 builds, empty queue. Code: `JobRequestAction#parseParameters` (seen set), `RunRequestService#checkValues` (line 421), `valuesProblem` (line 473) at approve and in `submitApproved`, `RerunParameters.recover` (repeated names refused). The length limit now applies to the stored text, a password's plaintext included (`ParameterDisplay.storedText`). |
| S-35-02 (MEDIUM) listings load the values | **Closed** (with S-37-01 caveat) | Probe: four PENDING requests with a 5 MB `base64File` each (28.0 MB of request XML): `list()` 0-1 ms and `countPendingFor` 0-1 ms (security-35: 200-240 ms per call), 4/4 objects `typedValuesOmitted`, `load(id).parameterValues()` throws ISE. `GET /batch-control/` 187-207 ms, `GET /batch-control/requests/` 185-216 ms (not compared with an empty store; T-05-86 counts deserializations). After cancel the file is 770 bytes with no `<parameterValues`. After execution the file has no values. Caveat: the per-request bound behind fix 1 is the body cap, which S-37-01 lifts for chunked bodies. |
| S-35-03 (MEDIUM) orphan `.tmp`, 500 | **Closed** | Probe: `create` with U+0000, U+FFFE, U+D800 or U+0001 in a value, or U+0000 in the reason, throws IAE naming the character. No non-`.xml` file exists in `requests/run/` afterwards. The form path answers 400 with the field error. Code: `saveXmlFile` and `saveRunRequestKeepingValues` delete the temporary file on any failure. `StoreWriteException` is an `IllegalStateException`, which `doSubmit`, `doApprove` and `doReject` show on the form. The comment is checked at approve and reject (`checkComment`). |
| S-35-04 (LOW) silently fewer values | **Closed** | Probe: approval refused, status PENDING, nothing scheduled, for (a) the value element renamed to an unknown class ("stored names [] differ from the names shown [TARGET]"), (b) `<parameterValues>` removed ("stored before Batch Control kept the submitted parameter values ... not converted"), and (c) `<name>` removed ("a stored value could not be loaded"). Each refusal is logged at WARNING. |
| S-35-05 (LOW) leftover wording | **Closed** | `docs/LIMITATIONS.md` 370-380 now says Stapler only marks the `jenkins-stapler-uploads*` directory for deletion at JVM exit, that a non-empty directory is not removed, and that a core `file` parameter leaves its part for every accepted submission. D-72b (8) amends D-72a's wording. |
| S-35-06 (LOW) rerun picks build by number | **Closed** (code and T-11-22; not probed) | `IncidentService` records `run.getTimeInMillis()`. `RerunParameters.isIncidentRun` reuses a build only when its timestamp equals the recorded one. An incident without it opens the form. Both values come from the build and are never user-supplied. |
| S-35-07 (LOW) recovery undoes a cancel | **Closed** | Probe: with 0 executors, approved requests X (u1) and Y (u2) with identical values give two queue items (`ApprovedRunAction#shouldSchedule` is always true, so they never fold). c1, holding only Item/Cancel, POSTs `queue/cancelItem` for X's item: X stays APPROVED with `queueCancelledAt` set and no values in its file, and Y is untouched (`queueCancelledAt=null`, its item still queued). `recoverApprovedRequests()` then schedules nothing for X. |

## BLOCKER (grounds for hosting rejection)
None.

## HIGH
None.

## MEDIUM

- [S-37-01] `policy/RequestBodyLimit.java:114-150` (`parsedSize`), `:132` (`json` read from
  `getParameterMap()`), `:173` (only `String` nodes collected); `action/JobRequestAction.java:515, 522`.
  **The D-72b (4) parsed-size cap can be bypassed, so a chunked submission is effectively unbounded.**
  For a body without a declared length, `parsedSize` counts the form fields and only the file parts
  it predicts will be read: parts named after a job parameter, and parts named by a *string* in the
  `json` value that `getParameterMap()` returns. The endpoint reads parts differently, so two
  encodings make the prediction miss:
  1. *A file reference given as a JSON number or boolean.* Core `FileParameterDefinition` and
     file-parameters `AbstractFileParameterDefinition` bind with `req.bindJSON`. Stapler's
     `FileItem` converter (`Stapler$4`) calls `getFileItem2(value.toString())`, so
     `{"name":"UPLOAD","file":7}` reads part `7`. `collect` adds only `String` nodes, so part `7`
     is never counted.
  2. *`json` sent as a file part.* `RequestImpl.parseMultipartFormData` puts every part in
     `parsedFormData` (last one wins per name) but only `isFormField()` parts in
     `parsedFormDataFormFields`, which `getParameter`/`getParameterMap` merge.
     `getSubmittedForm()` reads `parsedFormData.get("json")` whatever the part kind. So a
     small form-field `json` decoy followed by a file part named `json` behaves like this:
     `doSubmit` takes the form route (`getParameter("json")` is the decoy), `parsedSize` collects
     names from the decoy only, and the endpoint parses the file-part `json`, which names
     uncounted parts.

  Before D-72b, a multipart body without a length was refused outright, so this is a regression.
  The requester needs only `BatchControl/Request` + `Item/Read` (D-38a).
  *Impact:* what the cap exists to bound is kept without limit. A core `file` value is copied to
  `$JENKINS_HOME/fileParameterValueFiles/`. A `base64File` value is stored as Base64 inside
  `requests/run/<id>.xml`. The cost of that file is no longer bounded: approve, reject, cancel and
  the expiry job's end of a PENDING request all deserialize the whole file
  (`persistEnded` → `storedValues` → `loadRunRequestWithValues`, `RunRequestService.java:1287-1308`).
  So an oversized request can exhaust the controller heap each time the per-minute expiry job tries
  to end it. LIMITATIONS 366-368 ("judged by the actual size of what Jenkins parsed from it, the sum
  of its parts") does not hold.
  *Probe:* cap 64 KiB, u1 with an API token, chunked multipart (no `Content-Length`), with two
  400 KiB parts (`data.csv` for core `file` UPLOAD and `data.bin` for `base64File` B64):
  - control, references as strings `"blob"`/`"blob2"`: **413**, 0 created;
  - references as numbers `7`/`8`: **302**, 1 request `{UPLOAD=[file] data.csv, B64=[file] data.bin, DATE=2026-10-01}`, `fileParameterValueFiles` +409,600 bytes, `requests/run` +547,867 bytes;
  - decoy form-field `json` plus file-part `json`: **302**, the same request, +409,600 / +547,877 bytes.

  The body was 819.7 KB against the 64 KiB cap in all three cases. (A body declaring both
  `Content-Length: 1000` and `Transfer-Encoding: chunked` is refused by Jetty 12.1.12 itself, 400,
  0 created, so the declared-length route is sound.)
  *Basis:* checklist B (bounded input where user input reaches a file; D-38a reasoning for the cap),
  D-72b (4). This is availability with the same reach as S-35-02, so MEDIUM under the SECURITY-*
  conventions.
  *Fix direction:* bound what the submission *kept*, not what it might read. Preferred: after the
  values are created (`parseParameters`/`parseRawParameters`) and before `create`, sum the sizes of
  the file values in `submitted`: core `FileParameterValue#getFile2().getSize()`, the
  file-parameters value's upload or Base64 length (by class name, as `ParameterDisplay.isFile` does),
  plus the textual values already limited by D-22. When the sum is over the cap, answer 413 and
  dispose of the values as for any refusal. This holds whatever names, encodings or part kinds the
  client uses. If the reachability model is kept instead, two changes are needed: read the form as
  the endpoint does (`req.getSubmittedForm()` under the same `getParameter("json") != null`
  condition, so the measured and the used `json` are the same cached object), and collect every
  scalar (`String`, `Number`, `Boolean`) with `toString()`. The simplest alternative is to restore
  the pre-D-72b refusal of a multipart body without a declared length. That needs a DECISIONS
  change (D-72b (4)).
  *Regression test:* Given the cap at 65,536 bytes and a job with a core `file` parameter UPLOAD
  and a `base64File` parameter B64, When u1 POSTs a chunked multipart body whose `json` refers to
  two 400 KiB parts by the numbers `7` and `8` (and, separately, a body with a form-field `json`
  decoy followed by a file part named `json` that refers to them by name), Then each answer is 413,
  no request is created, and neither `fileParameterValueFiles/` nor `requests/run/` grows.

## LOW
None.

## Checked and found to be fine

- Lazy listing reader (`FileStore.readRunRequest` 299, `StopAtChildReader`): `parameterValues` is the last declared field (only the transient `typedValuesOmitted` follows), and XStream escapes `<` in every text and attribute. No element name is user-controlled (values are class aliases, map keys are text), so a value cannot add or move a root child. A file whose values are not last fails the tail check (`typedValuesLast` 395) and is read in full. A truncated or malformed file fails both reads (listing skips it with a WARNING, approve refuses), so the two reads cannot disagree on any field. Probe: a string value and the reason holding `\n  <parameterValues>`, `</parameterValues>\n</...RunRequest>` and `<parameterValues/>`: the light read returns both unchanged and `omitted=true`.
- Partial save (`saveRunRequestKeepingValues` 333, `typedValuesSection` 423): the first `"\n  <parameterValues"` can only be the root child (two-space indent, `<` escaped elsewhere), and the copy runs to the closing root tag the file must end with. Otherwise it falls back to a full read and a full save. Probe: after `changeApprovers` on the crafted request, the stored values section is byte-identical, there is exactly one `<parameterValues`, and approval runs build #1 with the crafted `S` value unchanged (2 values), after which the file holds no values. All saves are made by the service under its lock on objects it has just loaded. Writes go through a temporary file plus an atomic move, so an unlocked reader sees the old or the new file.
- The in-memory index keeps only `RequestSummary` (`EntityIndex.put`), and `listOpenRunRequests` loads light objects, so no values stay on the heap for open requests.
- Released claim (`releaseRefusedClaim` 1222): taken under the queue lock, then the service lock. It does nothing when a queue item carries the marker, or when the request is not APPROVED, is unqueued, executed or cancelled. Probe: approval refused after the gate (core `ItemDeletion` veto) leaves status APPROVED, `queuedAt=null` and an empty queue. A second approve is refused ("is APPROVED and can no longer be approved"). Two consecutive `recoverApprovedRequests()` calls give exactly one queue item. `scheduleBuild2` answers null only for a refusal or an unbuildable job, so no build exists when the claim is released.
- `ApprovedRunQueueListener` / `recordQueueCancelled` (1258): acts only on `isCancelled()` items carrying the marker, and only when the request is APPROVED, not executed, not yet cancelled and for the same job full name. Markers are added only by `submitApproved`, and the gate admits each marker once, so no user-made queue item can name another request. Cancelling another user's approved item needs core's Item/Cancel on that job and affects only that item's request (probe above). Lock order is queue then service, matching `consumeMarker`. Invalidation cancels queue items after it releases the service lock (`RequestInvalidationListener`). No full read happens under the queue lock.
- A run cannot start with an ended request's values: a cancelled item's values are removed only after core's cancel. Expiry disposes files only when `queuedAt` is null. Recovery skips `queueCancelledAt` and reads the values for that one submission (`requireWithValues`). `submitApproved` re-checks `valuesProblem`.
- U+FFFD echo: probe POSTs TARGET `<b id=x>bold</b>\u0001end` and reason `why\u0002 <i>r</i>`. The answer is 400, the page holds U+FFFD and no U+0001/U+0002, the markup appears only as `&lt;b id=x&gt;` and `&lt;i>r&lt;/i>`, and no file is left in `requests/run/`. `FormErrors.displayable` replaces each invalid character (consecutive and lone surrogates included). `JobRequestAction.displayable` shows a non-string value with an invalid character as the definition's default. `parameterField(String)` is not a getter, so it is not URL-bound. `bc:fieldError` renders the field name in an escaped attribute.
- Web methods: `grep -rn "public .* do[A-Z]" src/main/java` gives 44 (unchanged). The diff changes `JobRequestAction#doSubmit` (`@RequirePOST`, `Item.READ` then `REQUEST` on the job, then the body cap, then the first body read), `RequestItem#doApprove` and `#doReject` (`@RequirePOST`, `Jenkins.checkPermission(APPROVE)` first; only the refusal filing changed). No `do*` was added.
- Jelly: every `.jelly` has `escape-by-default='true'` (`grep -L` empty). There is no `escapeXml="false"` and no `<j:out`. The only new output is the `bc:fieldError` line.
- `ACL.SYSTEM2`/`ACL.as(`: the diff adds or removes none (`git diff 44b157f..HEAD -- src/main | grep` empty).
- Secrets: no new path writes plaintext. `storedText` reads a Secret's plaintext only for the length and character checks. Refusal messages name the parameter and the code point, never the value. Typed values stay reachable only through `@Restricted(NoExternalUse)` non-getters.
- XStream: the same `XStream2` with JEP-200 filtering. The listing reader uses the same driver's reader and stops before the values.
- Rerun timestamp: a millisecond-equal timestamp after a delete and re-create needs a clock set backwards. This is not user-controllable.
- `Content-Length` together with `Transfer-Encoding: chunked`: Jetty answers 400 before dispatch (probe P9), so `exceeds` cannot be fooled by a small declared length.
- SpotBugs: the worktree gate `gate-r6-params-21.log` and `-25.log` (run 20:31, after the last code commit 49c4869; 7168e4a is docs only) show `Tests run: 867, Failures: 0` and `BugInstance size is 0`.
- Residual, not a finding: a third-party value whose *nested* field class no longer loads is kept without that field and passes `valuesProblem`. Core and file-parameters values have no plugin-typed nested fields, it needs an administrator's plugin change, and core behaves the same for `build.xml`.
- Unconfirmed: HTTP/2 bodies without a length (not probed; the same `parsedSize` path applies, so S-37-01 covers them). Third-party `QueueDecisionHandler`s that refuse and reschedule under the same marker (not probed). The release check then relies on `Queue.getItems()` including pending items.

## Request
- Request: `src/main/java/io/jenkins/plugins/batchcontrol/policy/RequestBodyLimit.java` and `src/main/java/io/jenkins/plugins/batchcontrol/action/JobRequestAction.java`: bound the size of the file values a submission created, judged after parsing and before `create`; over the cap answer 413 and dispose of them. Or, if the reachability model stays, read `json` through `getSubmittedForm()` and collect every scalar (S-37-01).
- Request: `src/test/**`, `docs/TEST-MATRIX.md`: a row for the S-37-01 Given/When/Then (numeric references, and a file-part `json` after a form-field decoy, both chunked).
- Request: `docs/LIMITATIONS.md` item 31 (lines 366-368): once S-37-01 is fixed, describe what is measured (the files the submission kept), not "the sum of its parts".
- Request: `docs/DECISIONS.md` (humans): only if the fix restores the refusal of multipart bodies without a declared length, amend D-72b (4) accordingly.
