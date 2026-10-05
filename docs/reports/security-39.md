# Security Review 39: re-check of the round-6 changes (D-74; closure of security-38)

Reviewer: security-reviewer. 2026-10-06.

Scope: `git diff cb5ad5d..r6/integration -- src/main pom.xml` (82 files; `r6/integration` at
4f38876, reviewed on branch `r6/sec39`). Focus: closure of S-38-01 and that S-34-01, S-35-01,
S-36-01/02 and S-37-01 stay closed after D-74; the D-74 mechanisms (windows follow their item,
the values file, the two-stage cap, durable ends, DELETE attribution, the optional file-parameters
guard); every new or changed `do*` method, `ACL.SYSTEM2` switch, path and view; the breadth of a
window. Standard: HOSTING-CHECKLIST section B, CLAUDE.md code rules, SPEC items 5, 6, 8, 11,
DECISIONS D-71..D-74 (D-71a/b/c, D-72a/b, D-73), ARCHITECTURE section 4 (following the item,
durability) and section 5 (values file), LIMITATIONS 11, 31, 32, 33, 44, 51. Severity follows the
SECURITY-* conventions, calibrated against security-35 S-35-02 (MEDIUM, heap exhaustion by a
Request holder) and security-36 S-36-03 (LOW, binding races that need environmental conditions).

Verification. Everything ran on `git archive r6/integration` copies under the session scratchpad,
never in the worktree; the probes were not committed:
`<scratchpad>/sec39/copy/src/test/java/io/jenkins/plugins/batchcontrol/Sec39RenameProbeTest.java`
(the security-38 matrix, re-run), `Sec39RoleProbeTest.java` (role-strategy), `Sec39FollowProbeTest.java`
(following, approval race, failed writes, durability, deletion attribution, disclosure),
`Sec39ValuesProbeTest.java` and `Sec39ValuesCostProbeTest.java` (values file, cap). Logs:
`<scratchpad>/sec39-probe.log` (17 tests, 0 failures), `<scratchpad>/sec39-cost.log` (1 test).
Fourteen D-74 test classes were also run in that copy (`<scratchpad>/sec39-focused.log`: 80 tests,
0 failures: OptionalDependencyWithoutFileParametersTest, OptionalDependencyWithoutAllOptionalTest,
ItemBindingEventsTest, ItemIdentityBindingTest, ItemIdentityRoleStrategyTest, ItemScopeRestartTest,
ValuesFileTest, ValuesFileRestartTest, ChangeRecordFolderDeleteAttributionTest, RequestBodyCapTest,
StashedFileBodyCapTest, TypedValueRemovalTest, CreateNameRestrictionSecurityTest, WindowItemKindGapTest).
SpotBugs: `mvn -o -B verify -DskipTests` on a second copy, JDK 21: `BugInstance size is 0`,
`total_classes='218'` (`<scratchpad>/sec39-spotbugs.log`). Core 2.568.3, credentials 1511 and
file-parameters 433 were read as bytecode where a finding depends on them. "Probe" quotes are
output lines of these runs.

## Summary: BLOCKER 0 / HIGH 0 / MEDIUM 1 / LOW 3 / INFO 5

S-38-01 is closed. The rename method is now taken from the first token after the item Stapler
dispatched to, so every trailing-segment variant of security-38 is refused with HTTP 400 and
recorded, under matrix and under role-strategy. Neither a folder window nor a job window turns
into standing role permissions any more. S-34-01, S-35-01, S-36-01/02 and S-37-01 stay closed
after D-74. The windows follow an item renamed or moved by others, and a window holder cannot
steer a window: every rename through a window is refused. Values are kept in their own encrypted
file, which is deleted at run start and at every end; a missing or foreign file refuses approval.
The two-stage cap holds against the chunked encodings tried. Deletion attribution names the right
user in the ordinary case. The file-parameters guard uses no reflection and passes the tests
without the plugin.

The new problems are at the edges of D-74:
- **MEDIUM (S-39-01):** on a case-insensitive file system (Windows, default macOS), a request URL
  with the suffix `.VALUES` loads the values file itself. That file is deserialized in full,
  before the visibility check, and the request fails with HTTP 500. A 40 MB values file cost
  250-430 MB of heap for each GET. Any Request holder can create such a file and repeat the GET.
- **LOW (S-39-02):** windows and D-35c records stay keyed by a name. Three things can leave a
  record on a name that no longer belongs to its item: the approval race (the post-register check
  of D-71c (3) is gone), a failed grant write during a rename, or interleaved renames. Only a new
  item created at that name ends such a record. An item renamed or moved there is not checked, so
  the record applies to it.
- **LOW (S-39-03):** the restart re-end reads at most 50,000 change records. More records than that
  after a window's end, with the grant file still unwritable, bring the window back.
- **LOW (S-39-04):** a followed name is shown to the holder and the approvers even after an
  administrator moved the item into a folder they cannot read.

## Closure of earlier findings

| Finding | State | Evidence |
|---|---|---|
| S-38-01 (BLOCKER) trailing-segment rename | **Closed** | `NewItemName.renamedItem`/`methodAfter` (`security/NewItemName.java:190-241`) read the nearest `Item` ancestor's `getRestOfUrl()` and take its first raw token, decoded with `TokenList.decode`, skipping `.` and cutting at `;`, `/` and `\`. Probe P1 (matrix, CONFIGURE window on folder): `confirmRename/extra`, `confirmRename/extra/`, `confirm%52ename/extra`, `confirmRename//extra`, `confirmRename/%2E`, `confirmRename/.`, `confirmRename/x/..`, `confirmRename/confirmRename`, `x/../confirmRename` each give **HTTP 400, violations+1, folder unchanged**. The earlier closed forms stay closed (`confirmRename`, `/`, `%63onfirmRename`, `confirm%52ename`, `//`, `./`). `doRename`, `do%52ename`, `doRename/extra`, `confirmrename`, `confirmRename%2F` and `confirmRename%5Cextra` give 404 on a folder (no such method). ROLE-FOLDER: five of the six variants give 400, +1 (`doRename` gives 404: a folder has no such method), and `Configure on opsN/prod=false` holds after all six. ROLE-JOB: `confirmRename`, `confirm%52ename`, `doRename`, `confirmRename/extra` and `doRename/x` give 400, +1, `Configure=true Build=false Workspace=false` (the window's Configure only). P3 (restricted CREATE, job created through it): all seven variants, `confirmRename/extra?newName=prod4` and `doRename/extra?newName=prod5` included, give 400, +1. P5 (DELETE+CREATE pair): `confirmRename/extra` gives 400, +1. The core-blocked `;jsessionid` and `%2E%2E` forms still go unrecorded (S-39-07). |
| S-38-02 (LOW) long new name | **Closed** | `NewItemName.forRecord` caps the name at 255 characters in the key and the detail (`GrantAwareACL.java:307, 543`). Probe P8: a 6000-character name produces a 477-character detail. 5 identical attempts produce 1 record. |
| S-38-03 (LOW) D-71b wording | **Closed** | DECISIONS D-71b now says "the precondition is file-system access (a per-item reload itself needs only Item/Configure)". |
| S-34-01 (HIGH) swap re-points windows | **Closed** (stays) | A holder cannot rename through any window (above). An administrator's rename or move leaves each window on its own item. Probe F1: `admin rename -> 302; Configure on b(old a) = true, on new a = false`. The role-strategy variant is closed (ROLE-FOLDER). |
| S-35-01 (BLOCKER) repeated name | **Closed** (stays) | Refused in `JobRequestAction.parseParameters` (seen set, `:807`) and in `RunRequestService.checkValues`. `valuesProblem` checks the values file at approve (`requireValues`) and again in `submitApproved`. A missing values file of a request with parameters is "the stored values are missing". ValuesFileTest and TypedValueRemovalTest pass. |
| S-36-01/02 (BLOCKER/HIGH) encoded and job renames | **Closed** (stays) | P1 and ROLE-JOB above. |
| S-37-01 (MEDIUM) cap bypass | **Closed** (stays) | Stage 2 measures what the values keep (`RequestBodyLimit.keptSize`). The uploaded parts are counted for every top-level value of an entry, a number included (`partNames`). The same `getSubmittedForm()` object is used for measuring and for creating. RequestBodyCapTest T-05-98/T-05-99 pass (11/11). Probe V2: a declared `Content-Length` over the cap gives 413. A 400 KiB chunked body gives 302 but keeps a 525-byte values file: core's and credentials' definitions rebuild the value (S-39-08). |
| S-36-03 (LOW) binding races | **(i) regressed, (ii) replaced** | D-74 removed the post-register `stillAtName` check that D-71c (3) required. The replacement durability has a residual (S-39-03). See S-39-02. |

## BLOCKER (grounds for hosting rejection)
None.

## HIGH
None.

## MEDIUM

- [S-39-01] `store/FileStore.java:223-226` (`runRequestFile`: `id.endsWith(".values")`, case-sensitive),
  `:277-290` (`loadRunRequest`), `:338-342` (`readRunRequest`: unchecked `(RunRequest)` cast);
  reached from `action/RequestsSection.java:83-97` (`getDynamic`, which catches only
  `IllegalArgumentException` and runs the visibility check after the load).
  **On a case-insensitive file system, `GET /batch-control/requests/<id>.VALUES/` deserializes the
  request's values file in full, ahead of the visibility check, and answers HTTP 500.** D-74
  keeps the typed values in `requests/run/<id>.values.xml`. `runRequestFile` keeps that file out of
  `loadRunRequest` only for an id that ends in the lower-case `.values`. With `<id>.VALUES` (or
  `.Values`) the path `<id>.VALUES.xml` resolves to the values file on NTFS and on default APFS.
  XStream then reads the whole `RunRequestValues`: every value, a `base64File`'s content and the
  Secrets (decrypted into `Secret` objects) included. Only after that does the cast throw
  `ClassCastException` (`FileStore.readRunRequest(FileStore.java:341)` <-
  `RequestsSection.getDynamic(RequestsSection.java:89)`).
  Probe V1: `requests/<id>/` gives 200 to u1, u2 and a1 (u2 holds `BatchControl/Request`, is not the
  requester, and may see the request because u2 can read the job). `<id>.values/` gives 404.
  **`<id>.VALUES/` and `<id>.Values/` give HTTP 500 to all
  three**, while `no-such-id.VALUES/` gives 404. The load runs before `Visibility.canSeeRunRequest`
  (`RequestsSection.java:89` before `:93`), so by code order a user who may not see the request
  gets the same 500 instead of the 404 of P-09, and the answer tells an open request with
  parameters from a missing one.
  Probe V3: u1 holds `BatchControl/Request` and Item/Read only. Through the service API u1 created
  a request whose `base64File` value made a 41,943,466-byte values file (under the default 100 MB
  cap). Then, three times: `GET .VALUES -> HTTP 500 in 814 ms; heap used before 142 MB, after
  571 MB; max 768 MB` (781 ms / +413 MB, 813 ms / +245 MB). So each GET allocates roughly ten times
  the file size, and at the default cap one GET costs on the order of 1 GB of heap. A few
  concurrent GETs exhaust a typical controller heap. The values file is read only by approve,
  submit, recovery and disposal. That property is what fixed S-35-02, and this path breaks it.
  The requester needs only Request + Item/Read (D-38a), and the request id is theirs. Windows
  controllers and default macOS are affected. Linux file systems are case-sensitive, so these
  probes do not reach the values file there. A Windows 8.3 short name (`XXXXXX~1`) is a second
  route to the same file where 8.3 names are enabled (analysis).
  Basis: checklist B (input reaching a file path is validated; bounded input), SPEC item 5 / D-74
  ("listings never load it"; "only approving, submitting, recovering and disposing read it"),
  P-09. This is availability with the reach of S-35-02, so MEDIUM. It is LOW where the controller
  runs on a case-sensitive file system only.
  Fix direction (core-dev): (1) Validate the request id's shape before any path is built.
  `Ids.newRequestId()` makes UUIDs, and D-68 keeps the earlier `yyyyMMdd-HHmmss-<6>` ids, so allow
  `[A-Za-z0-9-]+` only (no `.`) in `runRequestFile`, `runRequestValuesFile` and every other
  `PathCodec.resolveUnder(runRequestDir(), ...)`. (2) Compare the suffix case-insensitively as
  defence in depth. (3) In `readRunRequest`, return `null` (and log) unless the object read is a
  `RunRequest`. Better still, read with a type check before the payload is materialised. (4) Have
  `RequestsSection.getDynamic` map any lookup failure to 404.
  Regression test: Given a pending run request `<id>` of u1 with a string parameter (and,
  separately, one with a 5 MB `base64File` value), When u1, u2 (Request, not the requester) and a1
  GET `batch-control/requests/<id>.VALUES/`, `<id>.Values/` and `<id>.vAlUeS/`, Then each answer is
  404, the response time and heap do not depend on the values file's size, and no
  `ClassCastException` is logged. The test must also pass on a case-insensitive test file system:
  assert that `<id>.VALUES.xml` resolves there as the premise, or skip the test when it does not.

## LOW

- [S-39-02] `security/GrantService.java:923-934` (`register`), `policy/GrantRequestService.java:397, 412, 417`
  (check, then a store write, then `register`); `security/GrantService.java:1005-1032`
  (`followItem`: a failed write keeps the old name, and windows already naming the destination
  are not ended), `:1058-1064` (`endWindowsOnNewItem`: creation only, exact case-sensitive match),
  `:983-993` with `:1180-1217` (`relocateCreatedItem`/`forgetCreatedItem` -> `updateCreatedItems`: on
  a failed write the cached D-35c record keeps its old name); `security/WindowItemListener.java:64-73`.
  **A window, or a D-35c created-item record, can be left on a name its item no longer has. Only
  the creation of a new item at that name ends it; an item renamed or moved there picks it up. The
  approval race is one way to get there, and it also lands a window directly on an item re-created
  at the name.** Since D-74 a record is matched by name only. Item events keep the names correct,
  but nothing re-checks a record against the item when a name is reused by a rename or move. Ways
  to such a stale record:
  (a) *Approval race (regression of D-71c (3), S-36-03 (i)).* `approve` checks the item
  (`checkScopeAtApproval`, `:397`), writes the request file (`:412`), then registers the window
  under the checked object's full name (`:417`). Item events take neither lock while that runs.
  If the item is deleted in between, its `onDeleted` finds no window. If an item is then created
  at the name, its `onCreated` finds none either. The window is registered on the new item.
  D-71c (3) ("approval re-verifies the item's identity after registering the grant") was
  implemented as `stillAtName` in commit 5c9174f. D-74 removed it without a replacement, and D-74
  does not amend D-71c. Inode reuse is no longer a precondition, because no identity is compared
  any more.
  Probe F2a (simulates the interleaving by calling `register` with the item `approve` checked,
  after it was deleted and re-created): `register(grant, deleted item) after re-creation: window
  scope = ITEM:r, Configure on the NEW r = true`. F2b (deleted only): the window stays active on
  the free name `s`, then `admin renames 'other' to 's' -> 302; Configure on it (now s) = true`.
  (b) *Failed follow.* When the grant file cannot be written during a rename or move, `followItem`
  logs "applies to no item there until an administrator checks it". The window keeps the old name
  in memory, and the next item renamed into that name gets it. Probe F3 (grants directory made
  read-only during the rename, then writable): `admin rename a->b -> 302; Configure on b(old a) =
  false`, then `admin renames x->a -> 302; Configure on a (old x) = true`. A failed write in
  `relocateCreatedItem`/`forgetCreatedItem` leaves a D-35c record (Read + Configure) on the old
  name in the same way. That path is analysis: the same `save` throws and `replaceInCache` is
  skipped.
  (c) *Interleaved renames (analysis, not probed).* Core fires `fireLocationChange` after it
  releases the parent's monitor (`AbstractItem.renameTo` bytecode: `monitorexit` at 309-331,
  `fireLocationChange` at 338). `WindowItemListener` runs last (ordinal -1000). So suppose rename
  R1 (`f` to `g`) is still in its listeners when rename R2 (`h` to `f`) completes with its events.
  Then R1's `followItem("f","g")` and `followItem("f/x","g/x")` move the windows that had just
  followed from `h` too, onto R1's items.
  Who controls it: none of the paths can be steered by the holder alone. Each needs a third party
  to delete, re-create or rename within milliseconds of an approval or of another rename, or a
  grant-store write failure. A blue/green swap ("rename old away, rename new in") while the store
  cannot write is a realistic case of (b). Basis: B (permission on an item nobody approved), SPEC
  item 8 line 170 ("renaming, moving, swapping or re-creating items never makes a window reach an
  item nobody approved"), D-71c (3), D-74 (3). Rated LOW like S-36-03: the conditions are
  environmental. It is flagged because (a) undoes an owner ruling.
  Fix direction (core-dev):
  (1) Check inside `GrantService.register`, under its monitor: look the name up as SYSTEM, and do
  not make the window effective (end it with "its item was deleted") unless the item at
  `item.getFullName()` is the same object as `item`. Item events take the same monitor, and
  `onDeleted` fires before the name is freed, so this one check closes (a).
  (2) In `followItem(old, new)`, first end every active window already naming `new`, and drop
  D-35c records naming `new`: a window naming a name that has just been taken over by another
  item cannot be about it. Also end, rather than move, the windows naming `old` when an item other
  than the moved one is at `old` again by the time the event is handled. That closes (c).
  (3) On a failed write in `followItem`, end the window through `revokeOne`, which is durable via
  `unsavedEnds` and the GRANT_REVOKE record, instead of keeping the old name. In
  `updateCreatedItems`, put the updated copy in the cache before the write and retry the write,
  as `unsavedEnds` does.
  (4) Optionally, make `endWindowsOnNewItem` (and the D-35c equivalent) case-insensitive, matching
  Jenkins' name lookup.
  Regression tests: Given u1's CONFIGURE window approved on job `r` while the approval is
  intercepted between the item check and the registration (a test hook, or by calling
  `register` with the checked item), When `r` is deleted and a new `r` is created in between, Then
  u1 holds no Configure on the new `r`. Given u1's window on `a` and a grants directory that
  refuses writes, When an administrator renames `a` to `b`, restores the directory and renames `x`
  to `a`, Then u1 holds no Configure on the item now named `a`, and either the window names `b` or
  it has ended with a GRANT_REVOKE record.

- [S-39-03] `security/GrantService.java:1433-1475` (`applyRecordedEnds`, scan capped at
  `Store.MAX_SCANNED_RECORDS` = 50,000; `:1459-1463` logs the truncation and goes on), and
  LIMITATIONS 11 ("Either write succeeding is enough for the end to survive the restart").
  **A window whose end could not be written to its grant file returns after a restart if more than
  50,000 change records were appended after its end.** The restart re-end scans the GRANT_REVOKE
  records newest first, from the grant time of the oldest open window. It stops after 50,000
  records within that period. A truncated scan logs a warning and leaves the remaining windows as
  their files say: open.
  Probe D1: window on job `a`; grants directory read-only; an administrator deletes `a`
  (`Could not save grant ... as ended`) and creates a new `a`; simulated restart
  (`GrantService.resetCacheOnStartup()`, then `WindowItemListener.onLoaded()` as SYSTEM). Result:
  `Configure on new a = false` (the record ended it again).
  Probe D2: the same, plus 50,100 change records appended before the restart. Result: `Not every
  change record since 1 open permission window(s) were granted could be read`, then `Configure on
  new a = true`.
  Preconditions: the grant file stays unwritable from the end until the restart (any later
  successful grant write, item event or minute of periodic work rewrites the end); the change
  directory stays writable; and more than 50,000 change records are appended within the window's
  lifetime (at most `maxGrantMinutes`, default 240). A user with standing Configure, Create or
  Delete somewhere can produce that many CONFIGURE, CREATE or DELETE records. Basis: B, SPEC item
  8 (an ended window never returns), ARCHITECTURE section 4 durability note. LOW: it needs a
  persisting store failure plus a restart.
  Fix direction (core-dev): bound the re-end scan by time, not by record count. Only records since
  the oldest open window's grant time can matter, and windows last at most `maxGrantMinutes`.
  Alternatively, read GRANT_REVOKE records through a dedicated, uncapped filter by grant id. On a
  truncated or failed scan, fail closed: end every window whose end cannot be ruled out, as
  `revokeAllActive` does, with a reason, so its holder can request it again. Correct LIMITATIONS
  11 until then (release-manager).
  Regression test: Given u1's window on job `a`, a grant store that refuses writes, an
  administrator who deletes `a` and creates a new `a`, and 60,000 further change records, When
  Jenkins restarts while the store still refuses writes, Then u1 holds no Configure on the new `a`.

- [S-39-04] `action/GrantRequestItem.java:171-175` (`getScope` returns the window's current
  scope), `action/GrantsSection.java:671, 684` (rows), `tags/scopeItem.jelly:20`,
  `ops/NotificationDispatcher.java:231` (the GRANT_EXPIRING mail names `grant.getScope()`).
  **After an administrator moves a windowed item into a folder the holder and the approvers cannot
  read, the grant pages and the expiry notice show them the item's new full name.** Under D-74
  the window follows the item. The pages render the followed name to everyone who may see the
  request (P-10: requester, designated approvers, Manage), whether or not they can read the item
  now.
  Probe F4: u1's window on `team/x`; administrator `Items.move` into `hidden-hr` (a folder with
  inheritance blocked; Read for admin only). Result: `u1 Read on hidden-hr=false a1 Read on
  hidden-hr=false; u1 Configure on moved item=true u1 Read on moved item=false`; for a1 and for u1,
  `request page shows 'hidden-hr/x': true; grants list shows it: true`.
  The window itself is inert there: without Read on `hidden-hr` the item cannot be reached by URL
  or CLI. What is disclosed is one full name, to users who are already parties to the window,
  after an administrator's action. ViewHistory holders see the same name in the MOVE record by
  design (LIMITATIONS 19). Basis: B (information disclosure to a user without Item/Read), P-10.
  LOW because of the reach.
  Fix direction (ui-dev): render the followed name only when the viewer can read the item
  (`Visibility.findVisibleItem` returns it). Otherwise show the approved name with a fixed note
  such as "moved; you cannot see its new location". Do the same in the GRANT_EXPIRING notice
  (core-dev, `ops/`).
  Regression test: Given u1's approved window on `team/x` and approver a1, When an administrator
  moves `team/x` into a folder neither u1 nor a1 may read, Then neither the request page nor the
  grants list shows the new full name to u1 or a1, and an administrator still sees it.

## INFO

- [S-39-05] `security/DeletionAttribution.java:45-57` (`remember` returns early for SYSTEM, before
  stale entries are purged), `:75-88` (`deletingUser`).
  **A deletion that fails after `onCheckDelete` leaves its entry. A later deletion of the same
  folder object, started as SYSTEM on the same thread, names the earlier user for the items below
  it.** Core calls `checkBeforeDelete` before `ItemDeletion.register` (bytecode of
  `AbstractItem.delete`). A fresh deletion therefore always starts unregistered, but a SYSTEM
  start never reaches the purge. Probe F6: admin deletes folder `G`; a test listener vetoes child
  `c2`, so `c1` is deleted and the deletion aborts. Then, as SYSTEM on the same thread, `G` is
  deleted. Result: `DELETE G/c1 user=admin`, `DELETE G/c2 user=admin` (wrong: SYSTEM deleted it),
  `DELETE G user=SYSTEM`. Audit attribution only. No permission decision uses it:
  `DeleteVetoListener` uses the current authentication. The ordinary case is correct (probe F5:
  `DELETE F/c user=admin`, `GRANT_REVOKE F/c user=admin ... 'F/c' was deleted`), and so is the
  reverse direction: a user's own deletion always runs as that user. Fix direction (core-dev):
  in `remember`, purge unregistered entries and remove `item`'s own entry before the SYSTEM
  return.
- [S-39-06] D-59 / LIMITATIONS 44. **A move authorised by a DELETE window plus Create at the
  destination (standing, or a CREATE window) re-points name- or inheritance-matched permissions,
  as D-71c says a rename would.** Example: a role-strategy pattern, or a destination folder's
  matrix entries. The window follows the job. The holder's own standing permissions at the
  destination then apply to the job. D-59a relocks run control on such a move, and LIMITATIONS 44
  accepts it as "what a delete and recreate would cost". This is not new and not a finding; it is
  noted so the owner sees that D-71c's reasoning has this one remaining counterpart.
- [S-39-07] Carried over from S-38-01's note. `confirmRename;jsessionid=abc` (core 400,
  SuspiciousRequestFilter) and `confirmRename/%2E%2E` (core 500, `TokenList` rejects `..`) still
  give violations+0 (probe P1). No rename happens, because core stops both before dispatch.
  Recording them would need a servlet filter, which ARCHITECTURE does not list. No action needed.
- [S-39-08] `policy/RequestBodyLimit.java:158-204` (`keptSize`/`ownSize`). **The kept-size check
  measures `ParameterValue#getValue()` and the uploaded parts, not what XStream writes.** A
  third-party value type that persists user-bound fields beyond its value would not be counted.
  Checked and fine:
  - core's types: `StringParameterDefinition`, `RunParameterDefinition` and the file types rebuild
    or override the description, and `RunParameterValue` requires an existing run (bytecode);
  - credentials: `CredentialsParameterDefinition#createValue` rebuilds the value with the
    definition's description (bytecode; probe V2: a 400 KiB `description` in a chunked body
    leaves a 525-byte values file, and the build's description is the definition's 18
    characters);
  - file-parameters: `Base64FileParameterValue#getValue` returns the Base64 text.
  Optional hardening: measure the serialized values (or the written values file) against the
  remaining cap before keeping it.
- [S-39-09] `store/FileParametersSupport.java:41-82`. **A `LinkageError` from an incompatible
  future file-parameters API is not caught.** The guard checks that the plugin is active
  (`Jenkins#getPlugin` returns `null` for a disabled or failed plugin). A version below the
  declared minimum cannot load either, because Jenkins enforces optional-dependency versions. The
  effect would be a refused submission or an error page: fail closed, no security impact.
  Optional: catch `LinkageError` once, log it, and treat the plugin as absent.

## Checked and found to be fine

- **Web methods (B-1, B-2).** The search for public `do` + capital-letter methods in
  `src/main/java` gives 45 matches: 42 Stapler web methods, plus `SelfGrantRevertFilter#doFilter`
  and two `PeriodicWork#doRun`. The diff adds one, `GrantsSection#doCheckScopeFullName`:
  `@RequirePOST`, then `checkPermission(REQUEST_GRANT)` (security-34). These bodies changed, and
  each keeps `@RequirePOST` plus a permission check as its first statement:
  - `JobRequestAction#doSubmit` (`:514-519`: Item/Read, then Request on the job, then stage 1,
    then the first body read);
  - `GrantsSection#doCreate` (`:228-230`, `REQUEST_GRANT`);
  - `RequestItem#doApprove`/`#doReject` (`APPROVE`; only the refusal filing changed);
  - `IncidentItem#doRerun` (`VIEW_HISTORY`, then job Read + Request as the caller; the 302 goes to
    the context path plus `job.getUrl()`, so no open redirect).
  Incident acknowledge, resolve and comment check `VIEW_HISTORY` first (SPEC item 11).
- **`ACL.SYSTEM2` (B-5).** The diff adds one switch, `GrantRequestService#itemExists`. Its reason
  is in the comment, it runs after `ApprovalPolicy.checkDecision`, and only a boolean leaves it
  (security-34). There is no other new `ACL.as2` or impersonation. `WindowItemListener.onLoaded`
  relies on the startup SYSTEM context. Without it, more windows would end (fail closed).
- **Jelly (B-4).** All 53 views declare escape-by-default (a search for files without it finds
  none). There is no `escapeXml="false"` and no `j:out`. The only markup answer is
  `GrantsSection#kindMarkup` (`Util.escape` on every value; `KindIcon` accepts only `symbol-` and
  `plugin-` names).
  - `_runParameter.jelly` mirrors core's run parameter view: `h.escape(it.name)`, the description
    through the markup formatter (SECURITY-353), options escaped. `bcRunId` comes from
    `RequestRunPrefill.selectedRunId` and is a run id only after core's existence check.
  - `_form.jelly`: `fromRerun` is the id `IncidentService.linkableIncident` validated (shape,
    ViewHistory, `canRequest`, same job). The values from `submitQuery` are `Util.rawEncode`d.
  - `scopeItem.jelly`, `_requestForm.jelly` (the #107 block), `GrantsSection/index.jelly`,
    `idLink.jelly` and the notice views: classes replaced inline styles, and every value is
    escaped.
- **Paths (B-7).** Values file names are built with `PathCodec.resolveUnder`, which refuses `/`,
  backslash, `.` and `..` and checks the result stays under the base. Ids are generated (UUID,
  D-68). Retention deletes the values file before the request file. The grant screens and
  `ActiveGrantsSection` now look grants up in memory (`GrantService.find`), so no grant id
  reaches a path. Gap: case-insensitive name aliasing (S-39-01).
- **Secrets and file content (B-6).** The values file is written by the same `XStream2`, so Secrets
  are stored encrypted (TypedParameterValuesTest and TypedParameterIntegrityTest check
  `holdsEncrypted` on the values file). Core's `FileParameterValue.file` is transient (bytecode),
  so no upload content goes into XML. A stashed file stays in its own directory. A Base64 value is
  in the values file, as D-72 says. Displays use the masked map only.
- **Values lifecycle.** Writing: `saveNewRunRequest` writes the request file first and the values
  file second, and deletes the request file if the values cannot be written. A crash between the
  two leaves a request with missing values, which cannot be approved. Deleting: `markExecuted`
  (run start), `persistEnded` on reject, cancel, expiry and invalidation, `recordQueueCancelled`,
  and retention. Every deletion runs after the end state is saved. Refusing: a values file that is
  missing (request with parameters), holds another `requestId`, has an unknown class or has
  differing names is refused at approve and at submit (`valuesProblem`, `requireValues`).
  Listings and periodic work read the request file only (`listRunRequests` skips values files).
- **Two-stage cap.** Stage 1 judges only the declared `Content-Length`, and a chunked body or one
  without a length passes to stage 2. Stage 2 counts the parsed upload sizes and stored texts per
  value, the larger of a value's own size and its uploaded parts. Unreferenced extra parts are not
  kept by Batch Control (core's temp directory, D-72a). JSON-number references and `json` sent as
  a file part are covered (T-05-98/99 pass). A `Content-Length` sent together with chunked
  transfer encoding is refused by Jetty (security-37). Residual: S-39-08.
- **Durable ends.**
  - `revokeOne` ends the window in memory first, then appends GRANT_REVOKE, then writes the file.
  - A failed write goes to `unsavedEnds`. Every `load` applies it, so no other write re-opens the
    window. It is retried before every grant write, on every item event and by the per-minute
    work (`ExpiryPeriodicWork`, independent of startup recovery).
  - Only `GrantService` writes grant files (a search for `saveGrant` finds no other caller).
  - Probe D1: the end survives a simulated restart through its record.
  - Residuals: S-39-03; both directories unwritable is documented in LIMITATIONS 11.
- **DELETE attribution.** A per-thread map with weak keys. An entry counts only while its folder is
  registered in `ItemDeletion`, and `WindowItemListener` forgets it last (ordinal -1000). Probe F5
  and ChangeRecordFolderDeleteAttributionTest (5/5) show the deleting user. A user cannot get a
  deletion recorded as SYSTEM, because core checks Delete as the user. Residual: S-39-05.
- **Optional file-parameters guard.** There is no reflection, class-name matching or enclosing-class
  lookup in the file path any more. The plugin types are referenced only in the nested `Linked`
  class, which loads after `isInstalled()`. Disposal calls exactly core's and the plugin's
  cancelled-item listeners. The pom marks the dependency optional (BOM version).
  OptionalDependencyWithoutFileParametersTest and OptionalDependencyWithoutAllOptionalTest pass.
- **Windows follow their item (D-74 (3)).**
  - Probe F1: an administrator's rename keeps the window on its item and gives nothing to a new
    item at the old name.
  - A holder cannot steer a window: every rename through a window is refused (P1, P3, P5, ROLE).
    A move needs Delete on the item, which a window confers only on a job, and the window then
    follows that same job.
  - An administrator's swap leaves each window on its own item.
  - Deletion ends windows on the item and below it, before core frees the name (event order,
    security-36). Creation at a window's name ends it. Startup ends windows of missing items.
  - Residuals: S-39-02.
- **Permission breadth.**
  - ITEM scope is an exact full-name match (`GrantScope.includes`). The delegate is evaluated
    with the grant layer off, so a folder CONFIGURE window confers nothing on children.
  - No rename through any window: probes, plus the D-35c and DELETE+CREATE paths.
  - CREATE applies only directly inside a regular folder: `createApplies` on the live item,
    checked at submission and at approval.
  - DELETE applies only to a `Job`: `deleteApplies`, `findActiveDeleteGrant`, the delete veto.
  - Incident actions need ViewHistory.
  - WindowItemKindGapTest and CreateNameRestrictionSecurityTest pass.
- **Information disclosure, Discover-only (B-3).** `doCheckScopeFullName` answers "No such item"
  for missing, unreadable and Discover-only items (unchanged). Grant rows and pages stay behind
  P-10. Exception: S-39-04.
- **XStream (B-9).** `RunRequestValues` is a final POJO, not `Serializable`, written and read by
  the JEP-200-filtered `XStream2`. Its values are of the classes the job's own definitions create.
- **`@Restricted(NoExternalUse.class)` (B-14)** is on all 15 new classes.
- **SpotBugs (B-15):** 0 bugs, 218 classes.

## NOT checked

- The full test suite and JDK 25. Only the 14 D-74 classes (80 tests) and the probes ran, on JDK
  21. The caller's gate is authoritative.
- A real Jenkins restart. D1 and D2 simulate it with `GrantService.resetCacheOnStartup()` and
  `WindowItemListener.onLoaded()` as SYSTEM.
- The S-39-02 race paths. Path (a) is simulated by calling `register` directly, and path (c)
  (interleaved renames) is analysis only.
- Windows/NTFS. S-39-01 was probed on APFS (case-insensitive). The 8.3 short-name route is
  analysis.
- HTTP/2 bodies without a length (the same stage-2 path applies). Third-party parameter types
  other than core, credentials and file-parameters (S-39-08).
- Reflection outside the file-parameters path, for example `ApprovalQueueDecisionHandler`
  `fieldQuietly`/`invokeQuietly` and `ConfigureWithoutGrantMonitor`. It predates this diff and is
  outside this scope.
- Help-text wording (`src/main/webapp/help/**`) beyond checking that the pages are static text.

## Request
- Request: `src/main/java/io/jenkins/plugins/batchcontrol/store/FileStore.java` (core-dev),
  S-39-01: validate the request id shape (letters, digits and `-` only) before building any
  `requests/run` path; compare the `.values` suffix case-insensitively; return `null` unless the
  object read is a `RunRequest`.
- Request: `src/main/java/io/jenkins/plugins/batchcontrol/action/RequestsSection.java` (ui-dev),
  S-39-01: map any lookup failure in `getDynamic` to 404.
- Request: `src/main/java/io/jenkins/plugins/batchcontrol/security/GrantService.java` (core-dev),
  S-39-02 and S-39-03:
  - in `register`, under the monitor, re-check that the item at the name is the approved object,
    else end the window;
  - in `followItem`, end windows and D-35c records already naming the destination, end rather
    than move when the old name is taken again, and on a failed write end through `revokeOne`;
  - cache-first updates in `updateCreatedItems`;
  - a time-bounded, not count-bounded, restart re-end that fails closed on a failed or truncated
    scan.
- Request: `src/main/java/io/jenkins/plugins/batchcontrol/security/DeletionAttribution.java`
  (core-dev), S-39-05: purge stale entries, and drop the item's own entry, before the SYSTEM
  return in `remember`.
- Request: `src/main/java/io/jenkins/plugins/batchcontrol/action/GrantRequestItem.java`,
  `action/GrantsSection.java`, `src/main/resources/io/jenkins/plugins/batchcontrol/tags/scopeItem.jelly`
  (ui-dev) and `ops/NotificationDispatcher.java` (core-dev), S-39-04: show a followed name only
  to a viewer who can read the item.
- Request: `docs/LIMITATIONS.md` (release-manager), item 11: "Either write succeeding is enough"
  holds only while the restart re-end is complete (S-39-03); describe the follow-failure
  behaviour once S-39-02 is fixed.
- Request: `docs/DECISIONS.md` (human): D-74 removed the post-registration re-check that D-71c (3)
  rules. Either restore it (S-39-02 fix 1) or amend D-71c (3).
- Request: `src/test/**`, `docs/TEST-MATRIX.md` (test-author): rows for the Given/When/Then of
  S-39-01 (case-variant `.VALUES` URLs give 404 with no deserialization, on a case-insensitive
  file system), S-39-02 (window registered after a delete and re-create; rename into a name left
  by a failed follow), S-39-03 (re-end after more than 50,000 change records), S-39-04 (followed
  name hidden after a move into an unreadable folder) and S-39-05 (SYSTEM deletion after an aborted
  user deletion is recorded as SYSTEM).
