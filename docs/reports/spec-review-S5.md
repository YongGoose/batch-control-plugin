# Spec Review S5

Scope: `main...integration/e2e-2` at 7a7747d (six hosting-review PR branches, 2026-10-02, plus `docs/hosting-review-decisions-oct`). The decisions checked are D-35e, D-35f, D-35f(a), D-38a, D-59 and D-60, against SPEC items 2, 5, 6, 8 and section 6, and ARCHITECTURE sections 3 to 5. Also checked: the reviewer checklist (19 items) and the earlier-round spot checks.
Reviewed 2026-10-03 by spec-guardian. Read-only; nothing else was modified.

Branch completeness: every PR branch head is contained in integration except `fix/new-job-ui` 21a9661 (LIMITATIONS 47, see m-1).

## Verdict: BLOCKED

There is one blocker (B-1), a usability spec violation that comes from the D-38a permission-scope change. Everything else is PASS, or a note listed below.

## BLOCKER (spec violation, must be fixed)

- **B-1** `src/main/java/io/jenkins/plugins/batchcontrol/action/JobRequestAction.java:348`, `ui/SectionAccess.java:81-87`, `action/BatchControlRootAction.java` (`isVisible`/`getUrlName`) and `action/RequestsSection.java:46`. The criterion violated is SPEC section 6 usability: "no link leads to a 404 or 403 page", together with SPEC item 5 and D-38a, "Request ... per job or folder".
  - What happened: D-38a made `BatchControl/Request` item-scoped (`security/BatchControlPermissions.java:46-48`, `PermissionScope.ITEM`). The run request form and `RunRequestService.create` now check it on the job. Every other Request-gated surface still checks it on `Jenkins`.
  - What the user sees: take a user whose Request comes only from a job or folder (exactly the T-05-25 user `fr`). That user submits successfully and is then redirected to `/batch-control/requests/<id>/`. That URL answers **404**, because the root action is absent without a Jenkins-level Batch Control permission. The user can never see the request, cancel it, or change its approvers (`action/RequestItem.java:316`, `:376`, both Jenkins-level).
  - Activation: requests stay Jenkins-level (`policy/ActivationService.java:297`, `action/JobActivationForm.java:159`, `action/JobActivationNoticeAction.java:122`, `action/ComputedFolderActivationAction.java:86`). But the activation form now lives under a URL space that job-level holders can open. SPEC 6a says activation uses "the existing BatchControl/Request permission plus Item/Read on the job", so SPEC 6a and SPEC 5 now apply different scopes to the same permission.
  - Test gap: T-05-25 (`RequestFolderScopeTest`) does not see the 404 because `ApproverFormFixtures.java:60` disables redirects and only the stored id is asserted.
  - Status: confirmed by code reading, not executed.
  - Fix, either (a) or (b), for the human/owner to choose:
    - (a) Make the Batch Control root action and the requests and activations sections reachable for a user who holds Request on some item and owns the request. Check requester actions (cancel, change approver) on the request's job. Then check activation on the job, as SPEC 6a's wording suggests.
    - (b) Keep Request JENKINS-scoped (revert the scope change) and drop T-05-25. This needs D-38a's "per job or folder" sentence re-read, so it is a human decision.

## MAJOR (missing acceptance criteria)

- **M-1** The per-item warning before a global-matrix conversion lost its SPEC line and its only test. Reviewer checklist #2 is a MUST that was promised in reply 5964339480.
  - SPEC: the SPEC item 8 Implementation line rewritten for D-35e dropped "and the monitor offers the conversion with a warning that per-item properties become effective" (D-35d(3)). No SPEC line requires the warning any more.
  - Test: T-02-36 was withdrawn whole (StrategyUpgradeTest), but only its wrapper premise was D-35e. Its assertion that the monitor warns per-item properties become effective (old `StrategyUpgradeTest.java:243`) has no successor. No test in `src/test` covers `ops/BatchControlStrategyMonitor/message.jelly:26-31` (confirmationLink) or `:46-53` ("Before you install") with a plain `GlobalMatrixAuthorizationStrategy` installed.
  - Behaviour: still present in the code, and seen in e2e-06 S3-02.
  - Owners: human (SPEC line), test-author (row and test).

## MINOR (defaults, naming, documentation)

- **m-1** `docs/LIMITATIONS.md` item 47 (the owner accepted E2E-1 DEF-02: core's build button comes first and green, Request Run second) exists only on `fix/new-job-ui` 21a9661 and is not in integration. LIMITATIONS 44-47 therefore cannot all be checked here: 44 (D-59 cost), 45 (rebuild plugin) and 46 (D-35e boot failure) match the behaviour, and 47 is absent. Checklist #18 is implemented as `Group.FIRST_IN_APP_BAR` + `Semantic.BUILD` (`JobRequestAction.java:298-307`), but the observed order is the one LIMITATIONS 47 documents. Owner: release-manager / merge.
- **m-2** The D-59 text says "the `batch-control-strategy` monitor lists Item/Move among the change permissions". In the implementation (and SPEC item 8, line 153), the standing-permission monitor does this instead (`ops/ConfigureWithoutGrantMonitor.java:77-79,327-331`, `message.jelly:25`). `BatchControlStrategyMonitor` does not mention Move. The code follows SPEC; the decision text is inaccurate. Owner: human (DECISIONS wording).
- **m-3** D-38a "the approver notification states...". The code also adds the notice on `APPROVERS_CHANGED` (`ops/NotificationDispatcher.java:100-103`), but only `REQUEST_CREATED` is tested (T-05-23/24). Owner: test-author.
- **m-4** The refusal page now links the item for readers, and gives no link to discover-only users (`security/MoveRefusal.java:53-61`, `MoveRefusal/index.jelly:13-15`; commits 430e094 and 9290cfe, labelled D-59). Neither D-59 nor SPEC mentions it, and `MoveRefusalPageTest` (T-SEC-63) asserts neither the link nor its absence. Owner: test-author (row), or a DECISIONS note.
- **m-5** No automated test covers checklist #4 (the "Project-based" display name), #8 (no leading `<p>` in the alerts of MoveRefusal, ApprovalRequiredFailure and the monitors), #12 (`l:job-subpage` on the new job page), #18 (app bar group and semantic) or #19 (destructive buttons). Their evidence is e2e-06 only (S3-01, S4c, S5a-c, DEF-03 fixed in b853c8f). This is acceptable under the checklist's "E2E" evidence rule, and is listed here so nobody reads it as unit-covered.
- **m-6** SPEC item 2 does not say that `BatchControl/Request` can be assigned per job or folder (scope ITEM). This is a visible change in the matrix and role UIs, and it is implied only by D-38a's wording. Owner: human (SPEC line).
- **m-7** Earlier-round spot check: "no doIndex" does not fully hold. `action/ActiveGrantsSection.java:34,70` still has two GET-only redirect `doIndex` methods. Both are pre-existing on main and unchanged here. Backlog.

## NIT

- **n-1** LIMITATIONS numbering is out of order: 45 sits under "Activation and other plugins' buttons" ahead of 42 and 43, and 46 follows 44 under "Moving items".
- **n-2** LIMITATIONS 9: the claim "Jenkins will not load it next to an older role-strategy" (an optional dependency with a minimum version) is unconfirmed. No test covers it.
- **n-3** The D-35e line "A saved configuration of the withdrawn wrapper is not converted" has no test row. LIMITATIONS 46 documents boot failure instead. Unconfirmed in a test; acceptable.

## Out-of-scope items

- PR6 adds a tab bar (`tags/tabs.jelly`, `ui/SectionTabs.java`), open-item badges, overview alerts, and the root action as `ModelObjectWithContextMenu`. These were reviewer "nice to have" items. No SPEC line or DECISIONS entry records the badge counting rule ("awaiting your decision" for designated approvers, otherwise own pending). Recommend a DECISIONS proposal so that SPEC owns the rule. Rows T-UI-40..46 exist.
- `RequestItem` item-scope and `X-Dialog-Title`, the detailed move message (E2E-1 UX-2), and `jenkins-table--small` are presentation details inside existing SPEC lines. No action.

## Checks that passed

- **Acceptance coverage:**
  - D-59: implemented by `security/MoveGuard.java` and `ChangeControlledRelocationHandler.java`, with the grant-layer path in `GrantAwareACL.java:151-162`. Tested by T-SEC-53..63: refusal changes nothing, recorded once, name restriction applied, administrator exempt, switch off, monitor, browser page.
  - D-60: `ui/RequestRunPrefill.java` and `ApprovalRequiredFailure.java`, with `queue/ApprovalQueueDecisionHandler.java` passing the submitted values. Tested by T-06-59 and T-06-89..96: sensitive values never carried, nothing stored, 2000-character bound, escaping. Re-runs do not pre-fill.
  - D-38a: `policy/RunRequestService.java:127-129,203-209`, with the detail page and notification notice. Tested by T-05-07..13, T-05-19 and T-05-21..25. User-facing "needs Job/Build" texts are gone (`help-approvalRequired.html:6`, `ApprovalRequiredFailure/index.jelly:32`).
  - D-35e: the class, the load conversion, `fromLegacyDelegate`/`emptyMatrix` and the S-04 unwrapping are removed.
  - D-35f: the pom pins role-strategy 918.v91e5468d8db_2. Save paths are tested by T-02-88..95, the descriptor surface by T-02-96..99, and T-02-17 is rewritten.
  - D-35f(a): `checkName` and `checkSidName` are re-implemented (`RoleSidChecks.java`, MIT attribution at :32), each with `@RequirePOST` plus `checkAnyPermission` as its first two lines. Tested by T-02-100..107.
- **Switch off:** `ChangeControlledRelocationHandler.applicability` returns SKIP. `MoveGuard.check` returns null before any lookup. The grant-layer path only reads the request. The Move entry in the standing-permission monitor applies only while change control is on. The D-60 redirect happens only when the run-control gate refuses. Nothing blocks or records while the switches are off.
- **State machine:** there are no new transitions. MoveGuard and the pre-fill change no request state; RequestRunPrefill reads GET requests only.
- **Storage:** there is no new file or format. The move refusal uses the existing `BlockedAttemptAudit` GRANT_VIOLATION record. ARCHITECTURE section 3 and section 4 are updated for MoveGuard, the removed upgrade path and the D-35f monitor wording.
- **Boundaries:** `action`/`ui` read only through `Store` list methods and service calls, and no state transition was added to `action`. The only new Stapler web methods are `doCheckSidName` (POST plus a permission check) and the read-only `doContextMenu`.
- **Defaults:** SPEC section 5 is unchanged and the code defaults are unchanged.
- **Tests:**
  - Only StrategyUpgradeTest's 5 methods (T-02-26..29, T-02-36) were removed; every other modified class keeps or grows its `@Test` count.
  - Inverted or rewritten assertions (T-05-07..13, T-05-19, T-02-17, T-02-42/43) follow D-38a, D-35e and D-35f, not code convenience.
  - New tests import only public plugin types and state that they were written from SPEC, TEST-MATRIX or the coordinator's brief.
  - TEST-MATRIX rows and notes 183-194 exist for all new behaviour, except m-3 and m-4.
- **README:** the permission table (Request: `Item/Build` not required), the role-strategy 918 row and text, and the display names "Batch Control: Project-based Matrix Authorization Strategy" and "Batch Control: Role-Based Strategy" all match `Messages.properties:10-11` and the pom.

## Unconfirmed

- `mvn clean verify` was not run: the integration worktree's `target/` was in use by another agent at review time (a partial surefire run started 12:32). Whether the suite is green and SpotBugs reports 0 is unconfirmed here.
- B-1 is confirmed by code reading only.
- Which agent authored each commit (path ownership per agent) was not checked.

## Requests

- Request: `src/main/java/io/jenkins/plugins/batchcontrol/{action,ui}/**` (ui-dev) and `policy/ActivationService.java` (core-dev): fix B-1 with option (a) or (b); the human chooses.
- Request: `src/test/**`, `docs/TEST-MATRIX.md` (test-author):
  - follow the post-submit redirect in T-05-25 and assert the requester can open, cancel and re-designate their request (B-1);
  - add a T-02-36 successor: plain global matrix plus change control on, so the monitor warns before install and the confirmation is required (M-1);
  - add an APPROVERS_CHANGED notice row (m-3);
  - add rows for the refusal-page item link and its absence for discover-only users (m-4).
- Request: `docs/SPEC.md` (human): restore the per-item conversion warning as an acceptance line (M-1) and state Request's per-job/folder scope in item 2 (m-6).
- Request: `docs/DECISIONS.md` (human): correct D-59's monitor name (m-2). Record the PR6 tab, badge and context-menu rule as a decision (out of scope).
- Request: `docs/LIMITATIONS.md` (release-manager): merge 21a9661 (item 47) into the integration/PR set (m-1) and renumber or reorder items 42-47 (n-1).
