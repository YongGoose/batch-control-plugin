# Spec Review r6

Scope: the whole round-6 change set, the diff `cb5ad5d..r6/integration` (r6/integration at
4f38876; 205 files, about 25,000 lines added). This covers code, tests, TEST-MATRIX notes
260-272, SPEC, ARCHITECTURE, DECISIONS, LIMITATIONS, README, README.ko and the help files. The
spec text checked is SPEC items 5, 6, 8, 9, 11 and sections 3, 4, 5 and 6, ARCHITECTURE sections
2 to 6, and DECISIONS D-71, D-71a, D-71b, D-71c, D-72, D-72a, D-72b, D-73 and D-74. Two hosting
review blockers were checked as the reviewer worded them (jenkins-infra/repository-permissions-updater#5338,
round 6, comment 5986189363):
(1) ITEM scope: a folder Configure window must not reach children, and the UI must show the item
kind; (2) file parameters (core `FileParameterValue`, file-parameters `StashedFile` and
`Base64File`) must work on the run-request form, where a `Map<String,String>` of values is not
enough (reference: schedule-build PR #448).

Reviewed 2026-10-06 by spec-guardian. Read-only; only this report was written.

Build evidence: `mvn -B -o clean verify` on 4f38876 in this worktree, JDK 21.0.12, Maven 3.9.16: BUILD SUCCESS, 997 tests, 0 failures, 0 errors, 0 skipped, SpotBugs `BugInstance size is 0` (23:51 min). That includes T-08-164, T-08-190 (red in note 270, fixed by 6325e85), T-06-103 (red in note 269, fixed by d15e31c) and T-09-25..29.

## Verdict: PASS WITH NOTES

There is no blocker. Both hosting-review blockers are met as the reviewer worded them (see
"Conforms" 1 and 2). Every behaviour in the diff traces to a SPEC line or to a ruling from D-71
to D-74. One SPEC acceptance clause has no test: D-73 coalescing of refused moves (M-1). The
other findings are documentation drift between SPEC, ARCHITECTURE, DECISIONS and LIMITATIONS
after D-71c, D-72b and D-74, plus small test and clean-up gaps.

## BLOCKER (spec violation, must be fixed)

None.

## MAJOR (missing acceptance criteria)

- **M-1** SPEC 8 line 168 / D-73: "the same refused move by the same user — same item and
  destination — is recorded once per minute; repeats inside that minute go to the Jenkins log
  only". No matrix row or test covers this.
  - The implementation exists from before this round: `security/MoveGuard.java:378-381` records
    through `BlockedAttemptAudit` with key `move <item> <destination>`, and the cooldown is
    `store/BlockedAttemptAudit.java:64`.
  - The only move row near it, T-08-128 (`docs/TEST-MATRIX.md:581`), says outright: "what the
    immediate repeat adds is not pinned, note 261". Note 261 left the count "to the human". The
    human then ruled D-73, but no row followed.
  - D-73 is tested for renames only: T-08-147, T-08-156 and T-08-184. The coalescing code is
    shared, but the move key and the move path are not exercised.
  - Expected (test-author): a row with change control on and a refused move of `prod/x` to
    `/team` by u1. The repeat within a minute writes no second GRANT_VIOLATION. The same move to
    another destination, the move of another item, and the same move by another user each write
    their own record. After the clock moves past one minute (`BatchClock.setForTest`), the repeat
    writes again.

## MINOR (defaults, naming, documentation)

- **m-1** `docs/SPEC.md:155` (D-40) still says the name restriction "governs renames: renaming an
  item that the holder created through the restricted grant, and a rename authorised by the
  grant's Create on the parent, must match the restriction, or it is refused". That reads as if a
  matching rename were allowed.
  - SPEC 169 and 170 (D-71c) say that no window allows renaming. The code refuses every rename
    resting on a window, the D-35c Configure included: `security/GrantAwareACL.java`
    `grantConfers` / `refuseRename`. LIMITATIONS 33 (`docs/LIMITATIONS.md:585-592`), README
    (`README.md:592-595`) and T-SEC-35/36/76 follow D-71c.
  - Strictly, line 155 states only a necessary condition, so the code does not violate it. A
    reader or test-author following line 155 alone would still expect a matching rename to
    succeed.
  - Expected (human): line 155 says that no window, the D-35c Configure included, allows a rename
    (D-71c), and drops or rewrites the rename sentence.
- **m-2** The upload cap's second stage is missing from SPEC and ARCHITECTURE.
  - `docs/SPEC.md:98` describes only stage 1: the body is refused "before Batch Control reads the
    form". `docs/ARCHITECTURE.md:136-141` (section 6) says the same.
  - D-72b (4), D-74 (2), LIMITATIONS 31 (`docs/LIMITATIONS.md:458-471`) and the code have a
    second stage: the kept-size check before storing (`policy/RequestBodyLimit.java` `checkKept`,
    called from `RunRequestService.create`). It runs after the form is read. It counts a
    `base64File` value as its stored Base64 text, about 4/3 of the file.
  - So a submission whose body is under the cap can still be refused with 413. SPEC does not say
    so; only LIMITATIONS does.
  - Expected (human): SPEC 98 and ARCHITECTURE section 6 name the two stages as D-74 (2) rules
    them.
- **m-3** spec-review-S7 m-5 is still open in SPEC and ARCHITECTURE.
  - `docs/SPEC.md:95` and `docs/ARCHITECTURE.md:105` say that run records and incidents take a
    request's parameters from the request's masked map "derived once at submission".
  - D-72b (8) rules otherwise: run records and incidents mask the build's own values with the
    same rule. The code does that: `ops/IncidentService.java` `maskedParameters` →
    `store/ParameterDisplay.masked`.
  - Expected (human): align the wording of SPEC 95 and ARCHITECTURE 105 with D-72b (8).
- **m-4** A missing deletion case for the values file.
  - `docs/SPEC.md:97` and `docs/ARCHITECTURE.md:106` say the values file is deleted "when the
    approved run starts or the request ends".
  - The code also deletes it when the approved run's queue item is cancelled. The request stays
    APPROVED in that case: `policy/RunRequestService.java:1244-1255`, `recordQueueCancelled` →
    `persistEnded(request, false)`.
  - LIMITATIONS 16 and 32 (`docs/LIMITATIONS.md:314-316`, `:510-513`) document it, and D-72b (7)
    implies it ("never submitted again").
  - Expected (human): add the case to SPEC 97 and ARCHITECTURE 106.
- **m-5** ARCHITECTURE has not followed the new mechanisms.
  - (a) The storage tree (`docs/ARCHITECTURE.md:88`) does not list `requests/run/<id>.values.xml`.
    Only the bullet at line 106 does.
  - (b) The extension-point table in section 2 (`docs/ARCHITECTURE.md:12-30`) has no
    `QueueListener`, which is new in this round (`queue/ApprovedRunQueueListener.java:24`,
    D-72b (7)). It also does not name the disposal mechanism, which is calling core's
    `FileParameterValue.CancelledQueueListener` and file-parameters'
    `StashedFileParameterValue.CancelledQueueListener` with a synthetic cancelled `Queue.LeftItem`
    (`policy/ParameterFiles.java`). D-74 (2) asks that "its reason is documented". The reason is
    documented only in that class's javadoc.
  - (c) Section 4 lists matrix-auth and role-strategy as optional dependencies but not
    file-parameters (D-74 (2), `store/FileParametersSupport.java`).
  - D-72a (1) already used "an extension point ARCHITECTURE does not list" as a reason to reject
    an option, so the table should be complete.
  - Expected (human): add the values file to the tree, a `QueueListener` row and the disposal
    mechanism to section 2, and file-parameters to the optional dependencies in section 4.
- **m-6** DECISIONS entries superseded within the round carry no marker.
  - D-71 (`docs/DECISIONS.md:184`) still says "renaming follows core's rule ... Configure on the
    item, or else Delete on it and Create in its parent, as before". D-71c replaced that, but its
    header says only "amends D-71a (2)".
  - D-71a (1) (identity binding, `:186`) and all of D-71b (`:188`) were replaced by D-74 (3).
    D-71a (2) was replaced by D-71c.
  - Unlike D-65 ("*Replaced by D-71*"), none of these entries is marked.
  - Expected (human): add "amended/replaced by" markers to D-71, D-71a and D-71b.
- **m-7** D-73 for moves is not documented for users.
  - LIMITATIONS 44 (`docs/LIMITATIONS.md:927-928`), README (`README.md:91-92`) and README.ko
    (`README.ko.md:72-73`) say a refused
    move "is recorded as a `GRANT_VIOLATION`", without the once-per-minute merge.
  - The same documents do state the merge for renames (`docs/LIMITATIONS.md:613-615`,
    `README.md:605`). SPEC 168 states it for moves.
  - Expected (release-manager): add the D-73 sentence to LIMITATIONS 44 and to the README move
    paragraph.
- **m-8** SPEC section 3 (`docs/SPEC.md:247-266`) does not list two persisted fields added this
  round: RunRequest `queueCancelledAt` and Incident `runTimestampMillis`.
  - ARCHITECTURE 106 and 107 do list them. This is the same pattern as the open proposal P-05.
  - Expected (human): decide together with P-05.
- **m-9** A code comment is stale after D-74.
  - `action/FolderCreateWindowAction.java:57` and `:67-69` still describe the D-71a binding:
    "one whose folder was renamed, moved or deleted ... confers nothing here and is not shown".
  - Under D-74, the window of a renamed or moved folder follows it and is listed. The code
    (`findActiveGrants(user, folder, CREATE)`) is right.
  - Expected (ui-dev): fix the comment.
- **m-10** One D-74 (4) clean-up remnant: `policy/RequestBodyLimit.java:81`,
  `exceeds(HttpServletRequest, Job)`, has no caller in `src/main` or `src/test`.
  - Expected (core-dev): remove it.
- **m-11** Some rulings and documented claims without a SPEC acceptance line have no row.
  - (a) #107 (D-74 (4)): the name restriction is read only when Create is ticked
    (`action/GrantsSection.java:253`). This changes form behaviour. Before, a restriction without
    CREATE was refused next to the field. Now it is silently ignored. The service still refuses
    it.
  - (b) #111 (D-74 (4), `descriptor.valuePage`) is not named by any row. It is exercised
    implicitly by T-05-102..108 and T-06-100..104, which render parameters through the form.
  - (c) LIMITATIONS 31 claims refusal of an XML-illegal character in an approve or reject
    comment (`policy/RunRequestService.java:401`, `:505`, `:573`) and in a parameter name
    (`:426`). Neither has a row; T-05-76 and T-05-77 cover values and the reason only.
  - Expected (test-author): rows, or recorded gaps.
- **m-12** Two SPEC acceptance lines were added in this range without a DECISIONS entry:
  - `docs/SPEC.md:171`, the naming strategy (a636d3a, "coverage inventory T-08-168");
  - `docs/SPEC.md:206`, incident actions need ViewHistory (f0f67b0, "documents existing
    behaviour").
  - SPEC is human-owned. Both commits carry the agent attribution, so whether the owner approved
    them is unconfirmed. The behaviour of each matches the code and is tested (T-08-168,
    T-11-26..29).
  - Expected (main session): confirm owner approval, or record the lines in DECISIONS.
- **m-13** spec-review-S6 m-9 is still open: 61 files under `e2e/` still use the removed
  `scopeType` control or `FOLDER`/`FOLDER_ONLY`. Examples: `e2e/README.md`, `e2e/r14/actions.py`,
  `e2e/r14/seed.py`.
  - There is no round-6 e2e report, so the real-browser regression of D-71 to D-74 has not been
    run.
  - Expected (e2e-tester): update the suite and run it before re-review.

## Out-of-scope items

None needs removal. Each item below traces to a SPEC line; keep it.

- **O-1** DELETE change records for items deleted together with their folder.
  - The records now name the user who deleted the folder, not SYSTEM:
    `listener/ItemChangeListener.java:74`, `security/DeletionAttribution.java`, commit 1864bdc.
  - It traces to SPEC 9 ("who changed what, whatever the path") and the SPEC section 6 usability
    line ("recorded history names who did what"). Rows T-09-25..29 cover it.
  - It is active only while recording is active (either switch on).
  - Optional (release-manager): one LIMITATIONS sentence next to item 26, since this is visible
    in history and CSV.
- **O-2** The approval page of a CONFIGURE request on a job now states that the window does not
  allow renaming (security-36 S-36-04; `GrantRequestItem#isShowNoRenameNotice`).
  - SPEC 172 names only the item-group notice. This one is consistent with the section 6
    usability line and with D-71c. LIMITATIONS 11 and README document it.
- **O-3** A run parameter is now carried from a refused build to the Request Run form (T-06-103,
  `ui/RequestRunPrefill.java`, `_runParameter.jelly`). It follows from SPEC 6 line 113: a run
  value is neither sensitive nor a file, so it must be carried.
- **O-4** Disposal of the files of refused direct builds (spec-review-S7 O-2). It still rests on
  D-60 ("nothing is stored"); LIMITATIONS 32 lists the channels.
- **O-5** `GET/POST /batch-control/grants/checkScopeFullName` (spec-review-S6 O-4). It is how
  the form shows the kind (D-71).

## Conforms

1. **Hosting blocker 1 (ITEM scope, item kind): met.**
   - *A window names one item.* `model/GrantScope.java` `includes` is an exact full-name match
     with `type == ITEM`; `isParentOf` serves D-35c. `security/GrantAwareACL` answers a window
     only on the item it names. The parent's decision is taken with every grant layer off
     (`withoutGrants`), so a folder window does not reach a child through matrix-auth
     inheritance.
   - *Create and Delete.* CREATE confers only on a regular folder (`createApplies`). DELETE
     confers only on a job (`deleteApplies`, `GrantService.findActiveDeleteGrant`). Both are
     checked again at submission and approval (`GrantRequestService.checkActionsApply`).
   - *Item kind in the UI.* The kind and its icon are shown in the lists and on the detail page
     (`tags/scopeItem.jelly`), in the form's name check (`GrantsSection.doCheckScopeFullName`,
     `ui/KindIcon`), in mails ("Item kind:") and on refusal pages (`GrantRequiredFailure`). The
     scope type selector is gone, and `grant-scope-type.html` is deleted.
   - *Rows:* T-08-100..129, T-08-165..176, T-08-188, T-02-12/24/38, T-SEC-03.
2. **Hosting blocker 2 (file parameters on the run-request form): met.**
   - *The form.* It posts `multipart/form-data` (`JobRequestAction/_form.jelly`) and renders
     each definition's `valuePage` (#111). `JobRequestAction.doSubmit` keeps the typed
     `ParameterValue`s, no longer flattened.
   - *Storage.* `RunRequestService.create(Job, List<ParameterValue>, ...)` derives the masked map
     once (`store/ParameterDisplay`) and stores the typed values in `<id>.values.xml`.
   - *The approved build.* `submitApproved` schedules the build with exactly those values, as
     schedule-build #448 does. This covers core `file`, `stashedFile` and `base64File`, and the
     new job page's dialog too.
   - *Rows:* T-05-41..47, T-05-101..108, T-05-110/111, T-05-130, T-SEC-07.
3. **Acceptance coverage by ruling.** Each clause has an implementation and at least one row,
   except M-1 and the items in m-11.
   - **D-71**
     - Single ITEM scope type; earlier scope types are not converted: T-08-105, T-08-125,
       T-08-126, T-08-145.
     - CONFIGURE covers the item only: T-08-100, T-08-101, T-08-11, T-08-169..171.
     - CREATE applies to a regular folder, directly inside it only: T-08-102, T-08-103,
       T-08-113, T-08-142, T-08-165.
     - D-35c matched by parent: T-08-112.
     - DELETE applies to a job only, matrix projects included: T-08-107..110, T-08-114,
       T-08-143, T-08-166.
     - The kind is recorded, and approval is refused when the item is gone or its kind changed:
       T-08-115..117, T-08-129, T-08-175, T-08-176.
     - Screens, check and mail: T-08-118..124, T-08-139, T-08-140, T-UI-98, T-UI-112.
     - Folder move needs an administrator: T-08-111, T-08-128, T-08-144, T-08-172.
   - **D-71a**
     - (3) canonical full name: T-08-137.
     - (4) top-level items only: T-08-127, T-08-171.
     - (5) the item-group notice: T-08-138.
   - **D-71c**
     - (1) no rename through a window: T-08-134, T-08-156..162, T-08-173, T-08-174,
       T-SEC-35/36/76.
     - (2) detection on the decoded path, including security-38: T-08-156, T-08-157,
       T-08-180..186, T-SEC-90.
     - Switch off: T-08-163.
   - **D-72**
     - Typed values and display: T-05-41..47, T-05-93, T-SEC-19, T-10-12/13.
     - Disposal: T-05-53..58, T-05-65..69, T-05-88..92, T-05-96.
     - Cap: T-05-59..64, T-05-94.
     - Rerun: T-11-08..12, T-11-23..25.
   - **D-72a**
     - (1) the cap guarantee: T-05-59..64, T-UI-113, T-UI-114.
     - (2) the validated rerun link: T-11-13..20, T-UI-115.
   - **D-72b**
     - (1) repeated or mismatched names: T-05-70..73, T-05-78..80, T-05-82.
     - (2) length and XML characters: T-05-74..77.
     - (3) a request stored before D-72: T-05-81.
     - (4) bodies without a declared length: T-05-95, T-05-98..100.
     - (5) values removed and never listed: T-05-83..86.
     - (6) only the incident's own build: T-11-21, T-11-22.
     - (7) cancelled or refused queue item: T-05-87, T-05-89, T-05-92.
   - **D-74**
     - (1) the values file: T-05-101, T-05-131..133.
     - (2) the two-stage cap: T-05-134, T-05-135. Optional dependency: T-02-123.
     - (3) a window follows its item: T-08-131, T-08-146, T-08-149, T-08-152, T-08-155,
       T-08-188, T-08-189, T-SEC-56.
     - (3) a window ends on deletion: T-08-148, T-08-150, T-08-153; on a new item at its name:
       T-08-187; at startup: T-08-151.
     - (3) durability of a window's end: T-08-164, T-08-190.
   - **Other lines**
     - SPEC 171 (naming strategy): T-08-168.
     - SPEC 206 (incident actions need ViewHistory): T-11-26..29.
     - SPEC 6 line 113 (D-60 as amended): T-UI-116, T-06-100..104.
   - All 220 round-6 rows were checked mechanically. Each names an existing test class, and that
     class mentions the row id.
4. **Boundaries.**
   - `action`/`ui` add no state transitions and write nothing to the store.
   - This round actually removed `Store.get().loadGrant`/`listGrants` reads from `action`/`ui`.
     `ActiveGrantsSection`, `GrantRequestItem`, `GrantsSection` and `GuardInfo` now read through
     `GrantService.find`/`listAll`, which matches ARCHITECTURE 73 ("the screens read grants
     through the same service as the permission checks").
   - Typed values are read only by `RunRequestService` (approve, submit, recovery, disposal).
     `RunRequestValues.values()` is not a bean getter.
   - Each file type in the diff stays within its owner's path family. Who made each commit is
     unconfirmed, since all carry the same identity.
5. **CLAUDE.md conventions.**
   - The one new web method, `GrantsSection.doCheckScopeFullName`, starts with `@RequirePOST`
     and `checkPermission(REQUEST_GRANT)`. `JobRequestAction.doSubmit` keeps its permission
     checks first, then the stage-1 cap.
   - The one new `ACL.SYSTEM2` switch, `GrantRequestService.itemExists`, has its reason in a
     comment and runs after the approver's checks.
   - User input reaching HTML is escaped: `kindMarkup` uses `Util.escape`, and Jelly escapes by
     default.
6. **Defaults.**
   - The SPEC section 5 table is unchanged, and no global setting was added.
   - The cap default is 104,857,600 bytes (`RequestBodyLimit.DEFAULT_MAX_BYTES`), which matches
     "100 MB" in SPEC 98, ARCHITECTURE section 6, LIMITATIONS 31, README and README.ko. The
     system property name matches everywhere.
7. **State machine.** No transition outside SPEC section 4 was added.
   - A window ended by its item's deletion, by a new item at its name, or at startup is ACTIVE →
     REVOKED, with the reason "its item was deleted" (D-63).
   - The D-71b "no longer applies" state was removed (D-74).
   - An approved run whose queue item was cancelled stays APPROVED until the approved-run timeout
     makes it EXPIRED, which is the existing transition.
   - `Grant.followItem` changes the scope's name, not the state.
8. **Storage.**
   - `requests/run/<id>.values.xml` follows ARCHITECTURE 106. Its root is `RunRequestValues`
     with `requestId` and `values`. It is written after `<id>.xml`, each through a temporary file
     and an atomic move. `<id>.xml` is deleted if the values file fails. There is no file for a
     request without parameters. Retention deletes it too (`FileStore` retention). Listings skip
     it.
   - `queueCancelledAtMillis` (ARCHITECTURE 106), Incident `runTimestampMillis`
     (ARCHITECTURE 107) and grant and request `itemKind` with three strings (ARCHITECTURE 109)
     are as documented.
   - The grant fields `itemIdentity` and `createdItemIdentities` are gone, and ARCHITECTURE no
     longer lists them.
   - No new file format. The D-74 startup re-derivation reads existing `GRANT_REVOKE` records.
9. **Switches off.**
   - *Change control off.* `GrantAwareACL.grantConfers` returns NONE, so no rename refusal,
     Create or Delete check from this round applies. `refuseRename` is reached only with a window
     decision, or with change control on (T-08-163, T-SEC-75).
   - *Run control off.* The disposal of refused builds sits behind the queue gate's early return
     (T-05-97).
   - *Listeners that run regardless.* `WindowItemListener`, `ApprovedRunQueueListener` and
     `ExpiryPeriodicWork.flushUnsavedEnds` only update Batch Control's own bookkeeping. They
     never veto, block or change a Jenkins item, queue item or build.
   - *Recording.* DELETE attribution is behind `ChangeRecording.isActive()`.
10. **Documentation consistency (beyond m-1 to m-8).** These five topics are stated the same way
    in SPEC 169/170/172/173, LIMITATIONS 11, 33 and 44, README, README.ko,
    `help/grant-actions.html`, `help/grant-scope-full-name.html`, `help/grant-duration.html`
    and `help-changeControlEnabled.html`:
    - the one-item rule;
    - Create and Delete applicability;
    - no rename through a window;
    - a window follows its item and ends on deletion;
    - the item kind shown with its icon.
    The help files mention only the deletion end, not the new-item and startup ends; README and
    LIMITATIONS have all three.
    On disposal, the temporary files disposed of (core `fileParameterValueFiles/` and
    `stashedFileParameterValueFiles/`, never Stapler's upload directories) are stated the same way
    in SPEC 96 and 98, ARCHITECTURE 105, LIMITATIONS 31 and 32, and D-72b (8). For DELETE
    attribution, LIMITATIONS 11's "revoked, by the account that deleted the item" matches
    `WindowItemListener.onDeleted` → `DeletionAttribution.deletingUser`.

## NOT checked

- The suite on JDK 25. Only JDK 21 was run here; the Jenkinsfile builds on 21, and the hosting checker accepts 21 and 25.
- A real browser with JavaScript: core's dialog with file inputs on both job pages (G-H7, G-M13)
  and the #107 optional block. Not run here, and there is no round-6 e2e report (m-13).
- Maven projects and Maven modules (no maven-plugin test dependency; documented in LIMITATIONS
  11, D-74 (3)).
- Windows file systems and reverse proxies that forward chunked bodies.
- Whether each commit to a human-owned document (SPEC, ARCHITECTURE, DECISIONS) was approved by
  the owner (m-12).
- A race, by analysis only, not reproduced and not a finding of this review (for
  security-reviewer):
  - `GrantRequestService.approve` checks the item (`policy/GrantRequestService.java:397`) before
    `GrantService.register(grant, item)` (`:417`; `security/GrantService.java:923-934`).
  - If someone with their own permissions deletes the item and creates another one of the same
    kind at the same name between those two lines, both item events have already run before the
    window exists. `register` then writes the window under the old object's full name, so it
    applies to the new item.
  - The window is milliseconds wide and needs an administrator or a standing-permission holder
    acting at that moment. A same-object check in `register` (`getItemByFullName(name) == item`,
    as SYSTEM) would close it.

## Request: test-author — `src/test/**`, `docs/TEST-MATRIX.md`: a D-73 row for refused moves (M-1); rows or recorded gaps for #107, #111 and XML-illegal characters in decision comments and parameter names (m-11)
## Request: human — `docs/SPEC.md:155` (m-1), `:98` (m-2), `:95` (m-3), `:97` (m-4), section 3 (m-8, with P-05); `docs/ARCHITECTURE.md:88`, section 2, section 4, `:105`, `:106`, `:136-141` (m-2 to m-5); `docs/DECISIONS.md:184/186/188` markers (m-6); SPEC 171 and 206 approval or DECISIONS lines (m-12)
## Request: release-manager — `docs/LIMITATIONS.md` item 44, `README.md:91-92` and `README.ko.md:72-73`: the D-73 once-per-minute merge for refused moves (m-7); optional LIMITATIONS sentence for O-1
## Request: ui-dev — `src/main/java/io/jenkins/plugins/batchcontrol/action/FolderCreateWindowAction.java:57,67-69`: comment follows D-74 (m-9)
## Request: core-dev — `src/main/java/io/jenkins/plugins/batchcontrol/policy/RequestBodyLimit.java:81`: remove the unused `exceeds(HttpServletRequest, Job)` (m-10)
## Request: e2e-tester — `e2e/**`: drop `scopeType`/`FOLDER`/`FOLDER_ONLY`, run the round-6 regression (m-13)
