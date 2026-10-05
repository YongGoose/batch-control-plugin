# Spec Review S6

Scope: `cb5ad5d..d8f02f8` on `r6/item-scope` (docs, core, ui, help, tests, TEST-MATRIX), plus `de62e60` (README, README.ko, LIMITATIONS), which landed while this review ran. The decision checked is D-71 (one-item permission windows; replaces D-65, amends D-35c and D-59) against SPEC item 8 (lines 154, 167, 168, 169), item 13 (mails), sections 3, 4, 5 and the section 6 usability line, and ARCHITECTURE sections 3 to 5.
Not reviewed: the test-author work that was uncommitted in the worktree at review time, since committed as 99179a9 (rows T-08-128 and T-08-129); and 2a564f0 (D-73, SPEC and DECISIONS). Both landed after this review started. 99179a9 is cited in m-2 and O-3 only from its commit message and matrix rows; it was not run here.
Reviewed 2026-10-05 by spec-guardian. Read-only; only this report was written.

Build evidence: `mvn -B -ntp clean verify` on an export of d8f02f8 (`git archive` into the session scratchpad, so the shared worktree was not touched), JDK 21.0.12, Maven 3.9.16: BUILD SUCCESS, 805 tests, 0 failures, 0 errors, 0 skipped; SpotBugs reports 0 bug instances (14:28 min). JDK 25 was not run (unconfirmed here). The six new D-71 classes (`ItemScopeTest` 8, `ItemScopeSubmissionTest` 7, `ItemScopeUiTest` 6, `ItemKindTest` 3, `ItemScopeRestartTest` 2, `GrantScopeTypeTest` 1) all pass.

## Verdict: PASS WITH NOTES

There is no blocker. Every D-71 acceptance clause has an implementation that matches SPEC, and nearly every clause has a matrix row and a test. Two clauses are only partly tested (M-1, M-2), and M-1 also leaves the request form showing the kind without its icon. Nothing from D-72 or from issues #107 to #115 was implemented here.

## BLOCKER (spec violation, must be fixed)

None.

## MAJOR (missing acceptance criteria)

- **M-1** SPEC 8 line 169 ("The request screens show the item's kind with its icon"), D-71 ("The UI shows the kind, with its icon, instead of a scope type selector").
  - Implementation: the lists and the detail page render the icon (`src/main/resources/io/jenkins/plugins/batchcontrol/tags/scopeItem.jelly:18`, `l:icon` when the recorded kind has an icon class). On the request form, where the selector used to be, the kind is shown as text only: `src/main/java/io/jenkins/plugins/batchcontrol/action/GrantsSection.java:359` returns `FormValidation.ok("<display name> '<full name>'")`, with no icon.
  - Test: no test asserts an icon anywhere. T-08-122 (`ItemScopeUiTest.java:352-374`, TEST-MATRIX row T-08-122) accepts the display name as text, `title`, `tooltip` or `aria-label`, so it passes with the icon removed.
  - Fix:
    - test-author: add an icon assertion to T-08-122. For example, inside the `data-batch-control-item-kind` element there is an icon element (`svg` or `img`) for Freestyle project, Folder and Pipeline.
    - ui-dev, or the human: either show the icon in the form check answer (`FormValidation.okWithMarkup` with the escaped name), or the human states in SPEC that "request screens" means the lists and the detail page only.
- **M-2** SPEC 8 line 168 ("DELETE ... never applies to an item group that is not a job, so no window lets its holder delete a folder") and the CREATE counterpart ("CREATE applies only to a modifiable item group").
  - Tests cover these only at submission (T-08-104/108/110/113) and through the delete veto for a [CREATE, CONFIGURE] folder window (T-08-107).
  - Gap: an approved window keeps matching its item by full name (LIMITATIONS 11), so a DELETE window can come to name a folder. Example: u1 deletes job `team/x` under a DELETE window on it, then moves a folder `other/x` into `team`, which needs standing Delete on that folder (the veto otherwise neutralises it) and Create in `team`. An administrator replacing the job has the same effect.
  - What stops it today: the check-time rules `security/GrantAwareACL.java:362-366` (Delete) and `:385-389` (Create), and `security/GrantService.java:153-158` (`findActiveDeleteGrant`, used by `listener/DeleteVetoListener.java:38`). Without them, core would delete the folder's children as SYSTEM, which is the D-71 hazard.
  - No row or test exercises these rules. ARCHITECTURE section 4 (line 74) also says only that both restrictions are "enforced when the request is submitted".
  - Fix:
    - test-author: add a row. A DELETE window on job `team/x`; `team/x` is then replaced by a folder (by the administrator in the fixture); u1 holds no Delete on it, the doDelete is vetoed, and the folder and its child survive. Add the CREATE twin: a CREATE window on a folder replaced by a multibranch project confers no Create in it.
    - human: optionally, ARCHITECTURE section 4 says the restriction is also applied at check time.

## MINOR (defaults, naming, documentation)

- **m-1** The SPEC 8 line 168 rename sentence ("Configure on the item (one CONFIGURE window on it), or else both Delete on it and Create in its parent") is tested only for the Create+Delete branch (T-SEC-36/39) and for the D-35c Configure branch (T-SEC-35).
  - No row renames a pre-existing job, or a folder, through a CONFIGURE window on it.
  - LIMITATIONS 11 claims behaviour for the folder case, all untested: one MOVE record per descendant job, the descendants' pending run requests ended, and the window no longer covering the item after the rename.
  - Owner: test-author.
- **m-2** Refusal pages that offer no window ("refusal pages without dead links") are implemented but untested. None of these three paths has a row:
  - the delete-veto page for a folder, multibranch project or organization folder: no prefill link, says to ask an administrator (`ui/GrantRequiredFailure.java:73,175`, `GrantRequiredFailure/index.jelly:20-45`);
  - the move refusal for a folder: no window links (`security/MoveGuard.java:170-191`, `MoveRefusal/index.jelly:21-22`);
  - a move into the Jenkins root: no Create window suggested (d8f02f8). This path is covered after the reviewed range by T-08-128 (99179a9, not run here).
  - T-08-124 and T-SEC-72 cover only the positive (job) links. Owner: test-author.
- **m-3** Storage format (ARCHITECTURE section 5, line 106; SPEC section 3, line 248: `itemKind{descriptorId, displayName}`).
  - `model/ItemKind.java:29` also persists `iconClassName` in every grant request and grant file. It is a field that neither document lists (CLAUDE.md: no new file format on one's own).
  - Owner: human. Either list the field in SPEC section 3 and ARCHITECTURE section 5 (recommended: a stored icon survives an uninstalled plugin), or core-dev drops it and ui-dev resolves the icon from the descriptor id when rendering.
- **m-4** SPEC 8 line 169 says "Stored windows **and requests** with the earlier scope types ... are not converted". T-08-126 covers a window file only.
  - How a grant request file with `FOLDER` loads (skipped, or breaking the grants list) is untested and unconfirmed.
  - Owner: test-author.
- **m-5** The test contract (no loosened assertions) is broken in one place.
  - `JobGrantSidebarEntryTest.java:328-338` (T-UI-10/15) now also accepts `actions=CONFIGURE` in the entry link, where it used to require the exact one-parameter URL that guards e2e-06 DEF-01 (no `&` in the new-job-page event URL, note 194).
  - D-71 does not change the job entry link: `JobGrantRequestAction` still emits `scopeFullName` only. So this widening has no decision behind it, even though note 260 documents it.
  - Owner: test-author (restore the exact match), or the human (accept it in DECISIONS).
- **m-6** The `docs/DECISIONS.md:184` D-71 consequence says "moving or deleting a folder requires an administrator". SPEC line 167 says only that a folder move "cannot be authorised through permission windows".
  - The code follows SPEC: `MoveGuard` lets a non-administrator with standing Item/Delete on the folder and Create at the destination move it. LIMITATIONS 11 and 44 and README document "an administrator or standing Item/Delete".
  - The decision text is inaccurate. Owner: human (wording).
- **m-7** `src/main/resources/io/jenkins/plugins/batchcontrol/config/BatchControlGlobalConfiguration/help-changeControlEnabled.html:12-14` still says "deleting a job is the one exception".
  - Since D-71, a folder, multibranch project or organization folder can be deleted only by an administrator while change control is on, and this help does not say so. README and LIMITATIONS do.
  - Owner: ui-dev.
- **m-8** Unconfirmed and declared in note 260 (e):
  - DELETE on a Maven project (allowed) and any action on a Maven module (refused) are untested, because maven-plugin is not a test dependency.
  - T-08-115 asserts the display names Pipeline, Freestyle project and Folder, but not the SPEC examples "Multibranch Pipeline" and "Organization Folder".
- **m-9** 61 files under `e2e/` still use the removed scope type control or old values. Examples: the shared helper `e2e/browser/lib.mjs:290`, `e2e/fresh/s4-grants.mjs:20` (`select[name="scopeType"]`), and `e2e/browser/section-a.mjs:232,264,362`.
  - The form ignores a posted `scopeType`, but any browser step that selects it fails. A FOLDER-scope request that includes DELETE is now refused.
  - The planned full e2e regression needs these updated first. Owner: e2e-tester.

## Out-of-scope items

- **O-1** A sub-item of a job (a matrix configuration; a Maven module, untested) is refused at submission for every action, CONFIGURE included (`policy/GrantRequestService.java:202-206`, `action/GrantsSection.java:356`, T-08-127).
  - SPEC lists only CREATE and DELETE refusals. The rule is consistent with D-71 ("their sub-items are part of the job"), and before D-71 a JOB window on `mx/X=a` could be requested.
  - Keep it, and record it as a DECISIONS proposal so that SPEC owns it. Owner: human.
- **O-2** The check-time application of the CREATE and DELETE kind rules (see M-2) is stricter than ARCHITECTURE section 4. Keep it. It is documented in LIMITATIONS 11.
- **O-3** The root-move wording (d8f02f8, `MoveGuard.java:180-186`) is not D-71 proper: S-13 already made a root window impossible. It falls under the SPEC section 6 usability line ("what to do instead"). Keep it. It is tested since 99179a9 (T-08-128).
- **O-4** `POST /batch-control/grants/checkScopeFullName` (`GrantsSection.java:343`) is in scope: it is how the form shows the kind (D-71). It is not the autocompletion of the issue draft "autocompletion for the scope full name field". It has `@RequirePOST` and then the RequestGrant check as its first two lines, and it is closed with the grants subtree while change control is off (`GrantsSection#getTarget`).
- **D-72:** this branch carries D-72 only as documents. The shared base commits b1fa41e and 1f0fb1f amend SPEC items 5, 6 and 11, section 3 (RunRequest) and section 6 security, and ARCHITECTURE sections 2, 5 and 6.
  - There is no D-72 code or test: `parameterValues`, `maxRequestBodyBytes`, `stashedFile` and `base64File` appear nowhere in `src/main`, `src/test` or TEST-MATRIX.
  - So at this branch's head those SPEC lines are unimplemented. Merging `r6/item-scope` into `main` without `r6/file-params` would leave SPEC ahead of the code. Note for the main session (merge order).
- **Issues #107 to #115:** none implemented. Checked against the nine issue drafts in the session scratchpad; their mapping to issue numbers is assumed from order and is unconfirmed:
  - the name-restriction field toggle (the field is unchanged in `_requestForm.jelly`);
  - the idLink tooltip and the idLink CSS (`tags/idLink.jelly` untouched);
  - the run tables;
  - the parameter page include (`JobRequestAction/_form.jelly` untouched);
  - the admin tab;
  - autocompletion (no `doAutoComplete*`);
  - `tabs.jelly` invokeStatic (untouched);
  - the file carry-over (#115).
- The human-owned documents (SPEC, ARCHITECTURE, DECISIONS) changed on this branch in b1fa41e, 1f0fb1f and 96f9b52. That the owner signed off on 96f9b52 (the rename sentence) cannot be confirmed from the repository; recorded here only.

## Checks that passed

- **Acceptance coverage (D-71):** clause by clause.
  - One item, nothing else:
    - Rule: `GrantScope.includes` is an exact match (`model/GrantScope.java`).
    - Rows: T-08-100, T-08-101, T-08-11 (pre-existing child now 403), T-02-12 (folder window does not reach `f/job`), T-SEC-03, T-08-125.
  - CONFIGURE and EXTENDED_READ on the item only, and a folder's own configuration: T-08-100 (configure page, save, EXTENDED_READ; children refused).
  - CREATE applicability:
    - Rule: `GrantScope.createAppliesTo` (a `ModifiableItemGroup`, not a `Job`, not a `ComputedFolder`).
    - Refused at submission: T-08-104 (job), T-08-113 (multibranch, organization folder).
    - Creation directly inside only: T-08-102, T-08-103, T-08-112, T-02-39.
  - D-40 unchanged: T-08-103, and the rewritten D-40 rows (T-08-42/44/50, T-SEC-36/39/40) pass.
  - D-35c matched by parent:
    - Rule: `GrantScope.isParentOf`, `GrantService.findCreatingGrant`.
    - Rows: T-08-112, T-02-25, T-02-39.
  - DELETE applicability:
    - Rule: `GrantScope.deleteAppliesTo` (`instanceof Job`).
    - Job only: T-08-109.
    - Multi-configuration project allowed: T-08-114.
    - Folder refused: T-08-108 (moved to P0, opposite intent).
    - Multibranch and organization folder refused: T-08-110.
    - Delete veto: T-08-107.
  - Move:
    - SPEC line 167: T-08-111.
    - Rewritten rows: T-SEC-54 (the folder DELETE request refused first), T-SEC-58, T-09-24.
  - Kind recorded (descriptor id and display name) and copied to the grant: T-08-115, T-08-105, T-08-106.
  - Approval refused when the item is gone or its kind changed: `GrantRequestService.checkScopeAtApproval` (IllegalStateException; the request stays PENDING); rows T-08-116, T-08-117.
    - The case where the approver cannot see the item stays IllegalArgumentException (T-SEC-18).
    - The `ACL.SYSTEM2` existence lookup carries its reason and runs after `checkDecision`.
  - No scope type selector:
    - Implementation: the select and `grant-scope-type.html` are removed, and a posted `scopeType` is ignored.
    - Rows: T-08-118, T-08-105, T-UI-98, T-UI-10/13/15.
  - Prefill URL `grants/new?scopeFullName=&actions=`: T-08-119, T-08-124, T-SEC-72, T-UI-13. Old `?scopeType=` links are redirected with `scopeType` dropped (`getLegacyFormQuery`).
  - Mails: "Item kind: <display name>" replaces "Scope type" in `ops/NotificationDispatcher.grantDetails`; row T-08-123.
  - Restart: T-08-106. Earlier type not converted (window): T-08-126.
  - Help texts:
    - `grant-scope-full-name.html`, `grant-actions.html` and `grant-create-name-pattern.html` match SPEC 8 lines 154 and 168. The rename sentence matches.
    - `grant-scope-type.html` is deleted and nothing references it.
    - The exception is m-7.
  - README, README.ko and LIMITATIONS (de62e60) match D-71, except the DECISIONS wording noted in m-6.
- **Boundaries:**
  - `action` and `ui` call `policy` (`GrantRequestService.create`, `findScopeItem`) and pure `model` rules (`GrantScope.createAppliesTo`, `deleteAppliesTo`, `ItemKind.of`).
  - There is no state-transition logic in `action` and no direct store write.
  - Each commit stays within its owner's paths: 263f0a3 and d8f02f8 core-dev, 6c6269c ui-dev, 6645861 test-author, de62e60 release-manager. The documents commits change human-owned paths, see above.
- **Defaults:** D-71 adds no configuration key. SPEC section 5 and `BatchControlGlobalConfiguration` are unchanged.
- **State machine:** no new transition. A refused approval leaves the GrantRequest PENDING (T-08-116/117), and no GrantRequest transition reacts to item deletion or rename.
- **Storage:**
  - Paths `requests/grant/<id>.xml` and `grants/<id>.xml` are unchanged.
  - `scope` is `{type: ITEM, fullName}`, and `itemKind` is in both files (but see m-3).
  - There is no migration code (D-69).
- **Switch off:**
  - These return before doing anything while change control is off: `MoveGuard.check`, `DeleteVetoListener.onCheckDelete`, `FolderGrantRequestAction.getIconFileName`, `GrantAwareACL` (P-15 gate), and the grants subtree including the new check endpoint.
  - `ItemKind` is computed only in `GrantRequestService.create`, which refuses while the switch is off.
  - The listener changes (`ChangeRecording.createGrantIdFor`, the RENAME grant link) only read grants and only record; they never block.
  - `GrantAwareACL`'s constructor computes two `instanceof` flags, with no side effect.
- **Test contract (sampled from note 260):**
  - `GrantServiceTest` (T-08-11), `MatrixStrategyTest` (T-02-12), `GrantSelfGrantGuardTest` (T-02-24/38/39), `AuthorizationEntryGuardTest` (T-02-62/67/72) and `MoveChangeControlTest` (T-SEC-54/58, T-09-24).
  - Also `CreateNamePatternTest` (T-08-42/44), `CreateNameRestrictionSecurityTest` (T-SEC-36/39/40), `GrantMailContentTest` (T-13-20), `GrantUsabilityTest` (T-08-50), `RequestDialogTest` (T-UI-98), `MoveRefusalPageTest` (T-SEC-72), `GrantWindowAbuseTest` (T-RT-06) and `JobGrantSidebarEntryTest` (T-UI-10/13/14/15).
  - Withdrawn intents are turned into opposite assertions rather than deleted: T-08-11, T-02-12, T-02-24, T-02-38, T-02-39, T-SEC-54, T-08-42, T-08-108.
  - Positive assertions have guards (for example T-02-12 bob and carol; T-08-107 and T-08-111 guard twins; T-08-116 and T-08-117 approve an unchanged item). Where a fixture changed, the strength stays the same (T-13-20 "delete" becomes "configure").
  - The exception is m-5.
  - `FolderOnlyScopeTest` and `FolderOnlyScopeRestartTest` are replaced by `ItemScope*`, and every T-08-100..127 row maps to an existing method.
  - core-dev did not modify tests (d8f02f8 touches `src/main` only).

## Request: test-author — `src/test/**`, `docs/TEST-MATRIX.md`: T-08-122 asserts an icon element in the kind element (M-1)
## Request: test-author — `src/test/**`, `docs/TEST-MATRIX.md`: a row for check-time DELETE and CREATE applicability after the item is replaced by an item group or a computed folder (M-2)
## Request: test-author — `src/test/**`, `docs/TEST-MATRIX.md`: a rename through a CONFIGURE window on a job and on a folder (m-1); the no-window refusal pages for a folder delete and a folder move (m-2; the root move is T-08-128 since 99179a9); an earlier-type grant request file (m-4); restore the exact one-parameter match in T-UI-10/15, or ask the human (m-5)
## Request: ui-dev — `src/main/java/io/jenkins/plugins/batchcontrol/action/GrantsSection.java:359`: show the kind's icon in the form check answer, unless the human narrows "request screens" (M-1)
## Request: ui-dev — `src/main/resources/io/jenkins/plugins/batchcontrol/config/BatchControlGlobalConfiguration/help-changeControlEnabled.html:12-14`: say that folders, multibranch projects and organization folders can be deleted only by an administrator while change control is on (m-7)
## Request: human — `docs/SPEC.md` section 3 and `docs/ARCHITECTURE.md` section 5: list `itemKind.iconClassName`, or have core-dev drop it (m-3); `docs/DECISIONS.md:184`: "requires an administrator" becomes "cannot be authorised through permission windows" (m-6); a DECISIONS proposal for the sub-item refusal (O-1); optionally, ARCHITECTURE section 4 mentions the check-time application (M-2)
## Request: e2e-tester — `e2e/**`: replace the `scopeType` select and FOLDER/FOLDER_ONLY usage before the full e2e regression (m-9)
## Request: main session — merge `r6/item-scope` together with `r6/file-params` (D-72 documents are on this branch without their code)
