# Security Review 35: typed parameter values (D-72, D-72a; `r6/file-params`, head 2ff94c8)

Reviewer: security-reviewer. 2026-10-05.

Scope: `git diff cb5ad5d..2ff94c8 -- src/main` (18 files). Standard: HOSTING-CHECKLIST section B,
CLAUDE.md code rules, SPEC item 5 (D-72 acceptance), item 11, section 6 security; DECISIONS D-72,
D-72a, P-03, D-38a, D-60, D-51; ARCHITECTURE storage section (run request file fields, secret
masking); LIMITATIONS 16, 31, 32, 48. Reference: jenkinsci/schedule-build-plugin#448.

Method: every checklist item was swept with grep over the whole tree, then the changed code was
read in full. Five probes ran as throw-away JenkinsRule tests in a `git archive HEAD` copy in the
session scratchpad (never in the worktree, nothing committed). `mvn -DskipTests verify` on that
copy ran SpotBugs. The probes are quoted under each finding as "Probe".

## Summary: BLOCKER 1 / HIGH 0 / MEDIUM 2 / LOW 4

The secret handling of D-72 holds: no plaintext secret on disk, in HTML/JSON/REST, in a redirect
or in a log; the approved build gets the original. Files are never addressed by a user-supplied
name, no server path or Base64 is displayed, and disposal only ever reaches the request's own
temporary files. The body cap, `@RequirePOST`, crumb and permission order are correct, and the
diff adds no `ACL.SYSTEM2` switch.

One gap breaks the approval itself: a parameter name may be submitted twice. The approver and
every Batch Control screen see the first value; the build runs with the last one (S-35-01).
The same gap lifts the 10,000-character limit, which feeds two MEDIUM resource findings
(S-35-02, S-35-03).

## BLOCKER (grounds for hosting rejection)

- [S-35-01] `action/JobRequestAction.java:624-641` (parseParameters), `store/ParameterDisplay.java:57`,
  `policy/RunRequestService.java:331-338`, `policy/RunRequestService.java:1012-1015`.
  **The approved build can run with parameter values the approver never saw.** `parseParameters`
  accepts the same parameter name any number of times in the `json` blob, and every value is
  stored in `parameterValues`. `ParameterDisplay.masked` keeps the *first* value per name, and
  the request screen, the notification, CSV, history, run records and incidents all show that one.
  `submitApproved` hands *all* values to `ParametersAction`, and Jenkins resolves a repeated name
  to the *last* value for the build's environment, Pipeline `params` and Freestyle build variables.
  Core's `ParametersAction#getParameter`, which the build's Parameters page uses, returns the first.
  So the evidence pages, the build page included, show the harmless value while the job runs with
  the other one. The requester needs only `BatchControl/Request` and `Item/Read` (D-38a, no
  `Item/Build`). The D-22 limit is checked on the display map only, so the hidden value is also
  not length-limited.
  Before D-72 the values were a `Map<String, String>` (last wins in both places), so display and
  execution agreed. The raw path (`parseRawParameters`, one value per definition), the service
  API string form (a `Map`) and D-60 (`carriedValues`, last wins) are not affected. File values
  are: two uploads under one name show the first file name, and the Freestyle build copies both to
  the same location, the last one winning.
  *Probe:* u1 (Request + Read) POSTed `json={"parameter":[{"name":"TARGET","value":"staging"},
  {"name":"TARGET","value":"production"}],...}` to `/job/X/batch-control/submit` → 302, request
  shows `{TARGET=staging}`, the detail page has no "production". After a1 approved: Pipeline log
  `PARAMS_TARGET=production ENV_TARGET=production`, build `getParameter("TARGET")` = staging,
  2 values stored. Freestyle: env `TARGET=production`. A second probe stored a 5 MB duplicate
  value; the display showed `short`.
  *Basis:* SPEC item 5 ("the approved build receives exactly those values"; display derived from
  them), checklist B items 1-2 (a state change only with the right authority). Per the
  SECURITY-* conventions an unauthorised state change is a BLOCKER: the approval is bypassed for
  the values.
  *Fix direction:* refuse a repeated parameter name at every entry point. On the form, give a
  field error on `parameters` ("Parameter 'X' was submitted more than once") and dispose of the
  values as for any other refusal. `RunRequestService.create(Job, List, ...)` throws
  `IllegalArgumentException` before anything is stored, which also covers the rerun and any later
  caller; `RerunParameters.recover` should not carry a failed run's repeated names. Apply the
  D-22 limit to every typed textual value, not to the display map. As defence in depth, at
  `approve` and in `submitApproved`, schedule nothing when the stored typed names repeat or differ
  from the display map keys (see S-35-04).
  *Regression test:* Given an approval-required Pipeline job with string parameter `TARGET` and
  requester u1 holding only Request + Item/Read, When u1 posts the form `json` with two `TARGET`
  entries (`staging`, `production`), Then the answer is 400 with a field error on `parameters`,
  no request file is created and no temporary file remains. And Given
  `RunRequestService.create(job, [TARGET=a, TARGET=b], ...)`, When it is called, Then it throws
  `IllegalArgumentException` and nothing is stored. And Given a stored request whose
  `parameterValues` hold two `TARGET` values, When it is approved, Then no build is scheduled and
  the request records why.

## HIGH
None.

## MEDIUM

- [S-35-02] `action/RequestsSection.java:184`, `ui/SectionTabs.java:104` → `policy/RunRequestService.java:112-114`,
  `policy/RunRequestService.java:695, 747` (expiry job, every minute), `store/FileStore.java:235-258, 1029-1041`.
  **A Request holder can make every Batch Control page and the expiry job load gigabytes.**
  D-72 puts the typed values inside `requests/run/<id>.xml`, and every listing deserializes them
  in full:
  - the tab badge on every Batch Control page (`countPendingFor` → `listOpenRunRequests`);
  - the Run Requests page (`list()` → every request file, closed ones included, all held at once);
  - the expiry job each minute (`expireOverdue` and `notifyExpiring`, each listing and then
    re-loading every open request).

  A request can be made large without a file parameter. A `base64File` value keeps the whole
  file. A password's plaintext length is not limited (LIMITATIONS 31). A repeated string value
  escapes the 10,000-character check (S-35-01). The only bound is the 100 MB body cap per
  submission. There is no per-user cap on pending requests (LIMITATIONS 31). Cancelling does not
  help: the content stays in the file for the retention period (24 months by default).
  *Probe:* four PENDING requests with a 20 MB `base64File` each produced 83.9 MB of request XML.
  `countPendingFor` took 200-240 ms per call and `GET /batch-control/` about 470 ms. After all
  four were cancelled the files were unchanged (83.9 MB) and `GET /batch-control/requests/` still
  took 440-480 ms. The cost is linear: a few dozen requests at the cap (about 140 MB of XML each)
  exceed a typical controller heap on any Batch Control page.
  *Basis:* checklist B (bounded input, D-38a reasoning for the cap: Request does not imply
  Build); LIMITATIONS 31/32 document the storage growth but not that low-privileged users can
  exhaust the heap. Availability, not on the BLOCKER/HIGH list, so MEDIUM.
  *Fix direction:*
  1. Bound what is stored, not only what is displayed: refuse repeated names (S-35-01), give
     every textual value, a password's plaintext included, the 10,000-character limit or a
     separate one, and give a `base64File` value its own, smaller limit.
  2. Read listings without the typed values, for example with a second XStream instance that
     omits `RunRequest.parameterValues` for list and summary reads and is never used to save.
     Load the full request only to schedule (`submitApproved`) or dispose (`disposeFiles`).
  3. Drop the typed values from the file when a request ends. A proposal for DECISIONS:
     ARCHITECTURE describes the field. After REJECTED, CANCELLED, EXPIRED or INVALIDATED, and
     once the queue has taken the run, the values are never read again.

  *Regression test:* Given 20 PENDING requests each holding a 5 MB `base64File` value, When a
  requester opens `/batch-control/` and the expiry job runs, Then neither deserializes the
  values: a store spy or heap delta shows no value payload loaded, and the page time is
  independent of payload size. And Given a cancelled request with a `base64File` value, Then its
  file no longer holds the Base64 text (if fix 3 is accepted).

- [S-35-03] `store/FileStore.java:1011-1025` (saveXmlFile), `policy/RunRequestService.java:380-387`
  (checkReason), `action/JobRequestAction.java:528`. **A failed save leaves an unbounded orphan
  file in JENKINS_HOME.** `saveXmlFile` creates `requests/run/<id><random>.tmp` and then
  serializes. When XStream refuses a character, for example U+0000 in a string value or in the
  reason, it throws `RuntimeException`. Only `IOException` and the atomic move are handled, so
  the partly written `.tmp` file stays. It holds every field written before the bad one:
  `parameterValues` comes before `parameters` and `reason`, so the requester can put a large
  value first. No request exists for it, so neither expiry, cancellation nor retention removes
  it, and `listXmlEntities` never sees it (`*.xml`). The submitter gets a 500 page: doSubmit
  catches only IAE/ISE, which breaks the SPEC section 6 usability rule. Secrets in it are
  encrypted (`Secret.ConverterImpl`). The flaw predates D-72 for the 4,000-character reason. D-72
  and S-35-01 make it unbounded.
  *Probe:* three `create` calls with values `[X=short, X=<5 MB>, Y="bad\u0000"]` each threw
  "Failed to serialize RunRequest#parameterValues" and each left a 5,243,365-byte `.tmp` file in
  `requests/run/`. A reason containing U+0000 threw "Failed to serialize RunRequest#reason".
  *Basis:* checklist B (input validation where user input reaches a file); disk exhaustion by a
  Request holder.
  *Fix direction:*
  1. In `saveXmlFile`, delete the temporary file on any failure (try/finally with a "moved" flag).
     This applies to every entity.
  2. Refuse characters XML cannot carry (U+0000-U+001F except tab, LF and CR, plus U+FFFE and
     U+FFFF) in the reason and in textual parameter values at submission, as a field error.
  3. Map a store failure to a refusal message instead of a 500.

  *Regression test:* Given a requester and a parameterized job, When the form is submitted with
  a parameter value or reason containing U+0000, Then the answer is a 400 field error, no file
  of any name is added under `batch-control/requests/run/`, and no temporary parameter file
  remains.

## LOW

- [S-35-04] `model/RunRequest.java:200-203`, `policy/RunRequestService.java:398-440, 1012-1015`.
  **The approved build can silently get fewer values than the approver saw.**
  (a) When the class of a stored value no longer loads (its plugin removed or downgraded, the
  class renamed), core's `RobustCollectionConverter` drops the element and logs only at FINE.
  `RunRequest` is not `Saveable`, so nothing reaches OldDataMonitor. The display map still lists
  the value.
  (b) A request file written before D-72 has no `parameterValues`, so the build gets the job's
  default values: `scheduleBuild2` adds defaults when no `ParametersAction` is given.
  *Probe:* (a) the `TARGET` element renamed to an unknown class → typed `[A]`, display
  `{A=1, TARGET=approved-target}`, approved build env `TARGET=null`, no warning in the log.
  (b) `<parameterValues>` removed → display `{TARGET=approved-target}`, build env
  `TARGET=default-target`.
  *Basis:* SPEC item 5 (exactly those values). Needs an administrator action or a pre-release
  file (D-43), hence LOW.
  *Fix direction:* the S-35-01 defence-in-depth check. At `approve` (before the APPROVED commit)
  and in `submitApproved`, compare the typed names with the display map keys. On a mismatch,
  refuse with a message that tells the requester to submit again, schedule nothing, and log
  WARNING.
  *Regression test:* Given a PENDING request whose stored value class cannot be resolved (or
  whose file has no `parameterValues`), When the approver approves, Then the approval is refused
  with that message and no build is scheduled.

- [S-35-05] `docs/LIMITATIONS.md:343-345`, `docs/DECISIONS.md:188` (D-72a). **The leftover
  statement is inaccurate.** Stapler parses into a new `jenkins-stapler-uploads*` directory under
  `java.io.tmpdir` and calls `deleteOnExit()` on that directory only (stapler 2088.2093
  `RequestImpl.parseMultipartFormData`). Java does not delete a non-empty directory at exit, so
  the uploaded parts (those over 10 KB) stay after the JVM exits, until the OS or an
  administrator removes them. The same leftover exists for every *accepted* submission with a
  core `file` parameter: core's `FileParameterDefinition` copies the upload and never deletes the
  Stapler item (the file-parameters values do delete theirs). Where `java.io.tmpdir` points into
  JENKINS_HOME, "nothing kept in JENKINS_HOME" does not hold either.
  *Evidence:* 33 `jenkins-stapler-uploads*` directories with files remain in the macOS temp
  directory from test JVMs that have exited, for example one from 12:19 holding a 63,822-byte
  upload.
  *Basis:* the documented leftovers (task focus 2/3) must be exact.
  *Fix direction:* reword LIMITATIONS 31 and D-72a: "...into a new `jenkins-stapler-uploads*`
  directory under `java.io.tmpdir`; Jenkins asks for the directory to be deleted when the JVM
  exits, which does not happen while it holds files, so the parts stay until removed. A core
  `file` parameter leaves such a copy for every submission, accepted or not."
  *Regression test:* none (documentation); spec-guardian checks the wording.

- [S-35-06] `ops/RerunParameters.java:68-77, 139-149`. **A rerun picks the build by number only.**
  The incident names `<job>#<n>`, and `recover` takes build `n` of whatever job now has that
  name. If the job was deleted and recreated under the same name, build `n` is a different run,
  and the rerun carries *its* values, secrets included. Before D-72 the rerun used the incident's
  own masked record. Approval-gated and needs a delete and recreate, hence LOW.
  *Fix direction:* confirm the build is the incident's run before reusing its values, without a
  format change: the build ended before `incident.createdAt` and its result equals
  `incident.result`. Otherwise treat it as unrecoverable and fall back to the form.
  *Regression test:* Given an incident for `X#1`, job X deleted and recreated, and a new `X#1`
  run with different values, When the incident is rerun, Then the request does not carry the new
  build's values (the form fallback opens).

- [S-35-07] `policy/RunRequestService.java:868-915` (pre-existing, outside the diff; not probed).
  Startup recovery re-submits an APPROVED request whose queue item was cancelled before the
  restart (`queuedAt` set, item gone → `setQueuedAt(null)` and submit). The cancellation is
  undone. With D-72, core's `CancelledQueueListener` has already deleted the file values'
  temporary files, so the resubmitted Freestyle build fails on the missing file.
  *Fix direction:* mark a request as ended when its approved queue item is cancelled (a
  `QueueListener#onLeft` with `isCancelled()` for items that carry `ApprovedRunAction`), or
  record the cancellation and skip it in recovery.
  *Regression test:* Given an approved request whose queue item a user cancels, When Jenkins
  restarts before the approved-run timeout, Then no build is scheduled for it and the request
  records the cancellation.

## Checked and found to be fine

- Web methods: `grep -rn "public .* do[A-Z]" src/main/java` gives 44 matches: 41 Stapler web
  methods, plus a servlet filter's `doFilter` and two `PeriodicWork#doRun`. The diff changes two:
  `JobRequestAction#doSubmit` (`@RequirePOST`, `checkPermission(Item.READ)` then
  `REQUEST`, then the body cap, then the first body read) and `IncidentItem#doRerun`
  (`@RequirePOST`, `VIEW_HISTORY` first, then job `READ` + `REQUEST`, both job-scoped). The
  other 39 are unchanged by the diff and keep their checks.
- CSRF with multipart: core's `CrumbFilter` runs before dispatch and reads the crumb from the
  header or the query string. Jetty does not parse a multipart body into `getParameterNames()`,
  so a crumb in the body alone is refused. Core's `crumb.appendToForm` adds it to the action's
  query string for a multipart form. Tests t_05_64 and t_ui_118 pin both.
- Body cap (`policy/RequestBodyLimit.java`): it reads only headers. A declared `Content-Length`
  over the cap is refused, as are a `Transfer-Encoding` and a `multipart/*` body without a length
  (HTTP/2 included). A URL-encoded body without a length is bounded by Jetty's form size limit.
  The cap is read on every call. It runs after the permission checks and before `getParameter`
  or `getSubmittedForm`. The 413 path reads only the query string (`Dialogs.fromDialogQuery`,
  `RequestRunPrefill.rerunFromQuery`) and renders `withoutInput()`. `isReviewedNotice` and
  `RequestRunPrefill.apply` read parameters only on GET. The D-72a premise holds: Stapler
  `RequestImpl` parses multipart with `FILEUPLOAD_MAX_SIZE` default `-1`, read from the system
  property.
- No plaintext secret on disk: `FileStore` uses `new XStream2()`, whose `AssociatedConverterImpl`
  writes `PasswordParameterValue`'s `Secret` with `Secret.ConverterImpl` (encrypted), as in
  `build.xml`. Test t_05_45 pins it. The orphan `.tmp` of S-35-03 is encrypted too.
- No secret or file content in any textual form: `ParameterDisplay.text` masks
  `isSensitive()`/`Secret` values. A file value (core `FileParameterValue`, file-parameters
  `AbstractFileParameterValue` by class name, or a raw `FileItem`/`File`) becomes
  `[file] <basename>`, so no Base64 and no `StoreLocation` path. Run records
  (`RunRecordListener` → `maskedParameters`), incidents, the request map, CSV
  (`RunLinks.formatParameters` + `CsvWriter` formula guard) and notifications all use it. Logs
  name parameters, never values.
- Typed values unreachable from the web: `RunRequest.parameterValues()` is not a bean getter,
  carries `@Restricted(NoExternalUse)`, and has exactly two callers (`submitApproved`,
  `disposeFiles`). There is no `@Exported`, `@JavaScriptMethod`, `getApi` or Jelly reference.
  Fields are private. This matters because `AbstractFileParameterValue#doDownload` would serve
  Base64 content if a value were ever bound to a URL. Test t_05_46 pins it.
- No secrets in redirects: D-60 `carriedValues` and the rerun `rerunQuery` carry only values of
  carriable definitions (`SimpleParameterDefinition`, not password) whose raw value is a
  String/Boolean/Number and not sensitive. `RerunParameters.prefill` and `prefillFromRecord`
  also drop the mask and `[file]` texts. File definitions are excluded by class.
- The approved build gets the original values: `submitApproved` uses the stored typed values
  unchanged (t_05_41 to t_05_45). The masked map is never turned back into values (the P-03
  path is removed).
- Paths: no user-supplied name reaches a path. Core and file-parameters reduce file names to
  basenames, and Batch Control only displays them. `RerunParameters.buildCopy` normalises,
  requires `startsWith(base)`, a regular file without following links, and a real path inside
  the real base. Temporary paths (`tmpFileName`, `tmpFile`) are created by the value classes and
  cannot be set through data binding: core has no setter, and `$class` binding is limited to
  subclasses.
- Disposal reaches only the request's own files. `ParameterFiles` hands a synthetic cancelled
  `LeftItem` only to listeners nested in a `ParameterValue` class. Core and file-parameters
  delete only the values' own `createTempFile`/`createTempDirectory` paths. Request values are
  created at submission, or recreated for a rerun (core file). A rerun never reuses a value
  that keeps a temporary file (`keepsTemporaryFile` → form). Disposal runs on REJECTED,
  CANCELLED and EXPIRED, on INVALIDATED (PENDING, or APPROVED with no ticket and not queued),
  on a refused creation, and on a refused own submission. Once `queuedAt` is set, nothing
  disposes, so a queued or executed run keeps its files. All of it happens under the service
  lock with status checks. Cancel needs the requester or Manage (`canCancel`), reject an
  approver. Refused builds: step 1 (marker) returns before any disposal. Steps 3 and 4 dispose
  only when `carriesOwnValues` (no naginator, Rebuild, Replay, Pipeline Rebuild or Restart
  cause). For a completed build an unrecognised re-run could only reach files core has already
  consumed (Freestyle nulls `tmpFileName` in `setUp`, stashed nulls `tmpFile`). The one
  exception is a completed Pipeline build's unused core-`file` leftover. Residual, not a finding.
- XStream: `new XStream2()` registers `BlacklistedTypesConverter` (JEP-200 `ClassFilter.DEFAULT`).
  The value classes are those the job's own definitions create; a user cannot choose them.
  Unknown classes are dropped, not instantiated (S-35-04 covers the consequence).
- Rerun permissions: `IncidentItem#doRerun` checks ViewHistory, then Read and Request as the
  caller (the SYSTEM2 lookup is the existing S-06 one, commented). `IncidentService.rerun`
  re-checks Request + Read before `RerunParameters.recover` reads the build. No values are read
  or prefilled for a caller who could not request.
- D-72a link: `linkableIncident` checks the id shape (`[A-Za-z0-9][A-Za-z0-9._-]{0,99}`, no `/`),
  Jenkins-level ViewHistory and `canRequest(job)` before the store is read. It then checks
  existence and the same job by full name, and returns the stored id. Only that result reaches
  `create`, which checks again through `requireRerunTarget`. A `fromRerun` value in the field,
  the json, or the query string is never trusted, and a refusal re-renders from the validated
  request attribute. A crafted link shows nothing (t_11_14 to t_11_20).
- Redirects: doRerun's 302 is `contextPath + "/" + job.getUrl() + "batch-control/" + query`.
  `getUrl` raw-encodes names, values go through `URLEncoder` (no CR/LF), and the path always
  starts at the context root, so it cannot be an open redirect. doSubmit redirects to a
  raw-encoded request id.
- Jelly: every `.jelly` under `src/main/resources` starts with `escape-by-default='true'`, and
  there is no `escapeXml="false"` and no `<j:out`. New outputs (`bcRerunId`, parameter names,
  `submitQuery`) are escaped, and the id is validated.
- `ACL.SYSTEM2`: the diff adds or removes no switch (`git diff | grep ACL.SYSTEM2` empty). The
  `submitApproved` comment was updated (Request + Item/Read at creation).
- `@Restricted(NoExternalUse)`: on all 15 changed classes, including the new `ParameterFiles`,
  `RequestBodyLimit`, `ParameterDisplay`, `RerunParameters` and `RerunNeedsFormException`.
- Concurrency: the approve, reject, cancel, expiry and invalidation transitions stay under the
  service lock with status re-checks. Disposal never races a queue hand-off: `consumeMarker`
  sets `queuedAt` under the same lock, and invalidation reads it under that lock. Double
  approval is impossible (PENDING check under lock).
- Rerun reusing another user's secret: by design (D-72, LIMITATIONS 16). The values are fixed
  and the run needs an approval, so it is no worse than Rebuild or Replay.
- SpotBugs: `mvn -o -DskipTests verify` on the scratch copy of 2ff94c8 analysed 205 classes and
  found 0 bugs (`BugInstance size is 0`).
- Unconfirmed: the full test gate of 2ff94c8 (running in the worktree, not awaited) and the
  behaviour of third-party re-run mechanisms not named in `lastRerunKind` (Blue Ocean, Build
  Pipeline view) against `carriesOwnValues`.

## Request
- Request: `src/main/java/io/jenkins/plugins/batchcontrol/action/JobRequestAction.java` refuse a repeated parameter name in `parseParameters` with a field error on `parameters`; map store failures to a refusal instead of a 500 (S-35-01, S-35-03).
- Request: `src/main/java/io/jenkins/plugins/batchcontrol/policy/RunRequestService.java` refuse repeated names and apply the length limits to every typed textual value (passwords included) before storing; reject XML-illegal characters in reason and values; at `approve`/`submitApproved` schedule nothing when typed names repeat or differ from the display keys, with a WARNING (S-35-01, S-35-02, S-35-03, S-35-04).
- Request: `src/main/java/io/jenkins/plugins/batchcontrol/ops/RerunParameters.java` refuse repeated names from the failed run; confirm the build is the incident's run (end time before the incident's creation, same result) before reusing its values (S-35-01, S-35-06).
- Request: `src/main/java/io/jenkins/plugins/batchcontrol/store/FileStore.java` delete the temporary file on any failure in `saveXmlFile`; read listings without `parameterValues` (a listing-only XStream that is never used to save) (S-35-02, S-35-03).
- Request: `src/main/java/io/jenkins/plugins/batchcontrol/policy/RunRequestService.java` (or a queue listener under `queue/`) end or mark a request whose approved queue item was cancelled so recovery does not resubmit it (S-35-07).
- Request: `src/test/**`, `docs/TEST-MATRIX.md` rows for the Given/When/Then of S-35-01 to S-35-04 and S-35-06, S-35-07.
- Request: `docs/LIMITATIONS.md` item 31: correct the Stapler leftover wording and add the per-submission core `file` copy in `java.io.tmpdir` (S-35-05); state that the 10,000-character cap holds for every stored value once S-35-01 is fixed; item 32: a `base64File` value stays in the request file after it ends and is read by every Run Requests page load (until S-35-02 fix 3).
- Request: `docs/DECISIONS.md` (humans) D-72a wording as in S-35-05; proposal: drop typed values from a request file once it ends or its run is queued, plus a separate `base64File` size limit (S-35-02); proposal for SPEC item 5: "a parameter name appears at most once in a run request" (S-35-01).
