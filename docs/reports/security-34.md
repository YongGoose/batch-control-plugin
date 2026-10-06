# Security Review 34: one-item permission windows (D-71, branch `r6/item-scope`)

Reviewer: security-reviewer. 2026-10-05.

Scope: `git diff cb5ad5d..HEAD -- src/main` (31 files; core 263f0a3 + d8f02f8, ui 6c6269c; HEAD de62e60).
Standard: HOSTING-CHECKLIST section B, CLAUDE.md code rules, DECISIONS D-71 (with D-35c, D-40, D-58b,
D-59, P-11), SPEC item 8. Severity follows the SECURITY-* conventions, calibrated against
security-33 S-33-01 (a grant combination where each step needs an approved window was MEDIUM).

Verification: SpotBugs ran on a `git archive HEAD` copy (`mvn -q -B -DskipTests verify`, JDK 21,
exit 0, `total_bugs='0'`). The full test suite was not run here, because other agents are editing
`src/test` in this worktree. Three JenkinsRule probes ran in a scratch copy only. They were never
committed (`<scratchpad>/sec34-build/src/test/java/io/jenkins/plugins/batchcontrol/Sec34ProbeTest.java`).
Their output is quoted below.

## Summary: BLOCKER 0 / HIGH 1 / MEDIUM 1 / LOW 2

The grant layer itself now matches one item. Every lookup compares the scope with the item's full
name exactly (`GrantScope.includes`). CREATE is answered only on the ACL of a regular folder
(`createApplies`). DELETE is answered only on a `Job` (`deleteApplies`, `findActiveDeleteGrant`, the
delete veto). D-35c matches by parent. The Jenkins root never carries a window. No path through
`GrantAwareACL` confers anything on a child, a nested folder, the root or an item created
elsewhere. The new web method, the new `ACL.SYSTEM2` switch, the approval refusals, the rendering of
`ItemKind` and the links/redirects are clean.

The gap is that windows are bound to a name, not to an item. A CONFIGURE window on a folder lets
its holder rename that folder (core's rule, accepted by D-71). Renaming a folder renames everything
inside it, so permissions matched by name are moved onto other items. Those are the holder's other
windows and, under role-strategy, the holder's own name-pattern roles. A probe confirmed it: three
approved windows, none naming the job `ops/prod`, gave its holder Configure on that job
(S-34-01). Separately, a folder's own configuration reaches its children (inherited settings,
indexing of computed folders). So "covers nothing else, not even the items inside a folder" is
stronger than what the window guarantees (S-34-02).

## BLOCKER
None.

## HIGH
- [S-34-01] `security/GrantAwareACL.java:362-369` with `model/GrantScope.java:70-83` (exact-name
  match), reached through core `AbstractItem#doCheckNewName` (rename = Item/Configure on the item).
  **A folder rename that a CONFIGURE window allows moves the folder's descendants under new
  full names, and every name-matched permission then lands on different items.**
  Probe A (matrix strategy, change control on, u1 has no Configure of their own). u1 holds three
  approved windows, each harmless on its own: CONFIGURE on folder `sandbox`, CONFIGURE on folder
  `ops`, CONFIGURE on job `sandbox/prod` (u1's own sandbox job). u1 then does the following:
  `POST job/sandbox/confirmRename newName=sandbox-old` gives 302. `POST job/ops/confirmRename
  newName=sandbox` gives 302. `POST config.xml` of the job formerly `ops/prod`, now `sandbox/prod`,
  gives **200** and the description becomes "planted". Before the renames the same POST gave 403.
  Output: `PROBE-A before: u1 Configure on ops/prod = false, POST config.xml = 403` …
  `PROBE-A after: u1 Configure on sandbox/prod = true, POST config.xml = 200`.
  The same swap re-points a DELETE window, so the holder can delete a job nobody approved for
  deletion. It also re-points a CREATE window, so the holder can create in a folder that was not
  approved, and the D-35c Configure then applies to what they create there. The swapped job keeps
  its activation (D-59a relocks moves, not renames), so an edited script runs unattended.
  Under role-strategy (analysis, not probed), item roles are regular expressions on full names. A
  holder with a standing role such as `sandbox.*` and a single CONFIGURE window on folder `ops` can
  rename `ops` to `sandbox2`. They then hold that role's permissions on everything that was inside
  `ops`, and keep them after the window ends. The project naming strategy is consulted on rename,
  but role-strategy's strategy admits names where the holder has Create, which a sandbox role
  usually gives.
  Every step is recorded (RENAME, one MOVE per descendant, the CONFIGURE linked to the window), and
  D-58 guard state follows renames, so the attack is visible after the fact but not prevented.
  Basis: B (permission conferred outside the window's item, unauthorised state change through a
  grant combination). SPEC item 8 and D-71 say "confers nothing on any other item". The form says
  "It covers nothing else, not even the items inside a folder" (`GrantsSection/_requestForm.jelly:47`).
  LIMITATIONS 11 and 33 describe the name binding and the folder rename separately, but not this
  combination. Rated HIGH, not MEDIUM as for the similar S-33-01, for two reasons. The holder needs
  no standing Move or Delete, only CONFIGURE windows that D-71 presents as not reaching children.
  The role-strategy variant needs one window, and the gained access outlives it. It is not BLOCKER,
  because every step needs an approved window or a standing role. If the owner treats multi-window
  combinations as the approvers' responsibility and leaves role-strategy name patterns out of
  scope, MEDIUM is defensible.
  Fix direction (owner decision first, it amends D-71's "renaming follows core's rule"):
  (a) While change control is on, a rename of an **item group** (folder of any kind) whose Configure
  comes only from a window confers nothing. Mirror D-40a: `NewItemName.forRename(itemFullName)` is
  already available in `grantConfers`. Answer the rename with a plain refusal and record
  `GRANT_VIOLATION`. This makes renaming a folder an administrator's (or a standing Configure
  holder's) act, as D-71 already does for deleting and moving a folder. (b) Additionally or
  alternatively, bind each grant to the item's identity at approval (`ItemIdentity`, as S-09 did
  for D-35c records), so that no rename, move or re-creation can re-point it. (b) alone does not
  close the role-strategy variant. At minimum, show on the approval page of a CONFIGURE request for
  a folder that it allows renaming the folder and everything in it.
  Test: Given the matrix strategy, folders `ops` (job `ops/prod`, description "base") and
  `sandbox` (job `sandbox/prod`), and u1 with approved CONFIGURE windows on `ops`, `sandbox` and
  `sandbox/prod`, When u1 POSTs `job/sandbox/confirmRename?newName=sandbox-old`, then
  `job/ops/confirmRename?newName=sandbox`, then the config.xml of the job now at `sandbox/prod`,
  Then the job formerly `ops/prod` keeps description "base" and u1 holds no Configure on it. With
  fix (a), the first rename is already refused with HTTP 4xx, no item is renamed, and one
  `GRANT_VIOLATION` names u1. A second case uses role-strategy with item role `sandbox.*`
  (Configure) for u1 and one CONFIGURE window on folder `ops`. When u1 renames `ops` to
  `sandbox2`, it is refused.
  Owner: human (D-71 amendment), then core-dev (`security/**`), ui-dev, release-manager (LIMITATIONS 11/33).

## MEDIUM
- [S-34-02] `policy/GrantRequestService.java:224-236` (CONFIGURE is accepted on every item kind),
  `GrantRequestItem/index.jelly:148`, `help/grant-actions.html:4`, `help/grant-scope-full-name.html:11`,
  `GrantsSection/_requestForm.jelly:47`.
  **A CONFIGURE window on an item group still reaches its children through the group's own
  configuration.** D-71 confers no permission on the children, and that holds. But the UI tells
  requester and approver that a folder window covers "the folder's settings, not the items inside
  it", and the following effects are not stated (analysis, not probed: neither pipeline-groovy-lib
  nor multibranch is on the test classpath):
  (1) Settings inherited by every item below. A folder-level Pipeline library marked "Load
  implicitly" (pipeline-groovy-lib `FolderLibraries`, sandboxed but running inside each job's build
  with that job's credentials and agents) changes what every Pipeline in the folder runs. The same
  holds for folder properties and environment that children read, and for the authorization
  property, which D-58b already guards.
  (2) Computed folders. Editing the sources, filters or orphaned-item strategy of a Multibranch
  Pipeline or Organization Folder makes the next indexing (started by the save) create, or delete
  as SYSTEM, the generated children with their build history. The delete veto lets SYSTEM through.
  This is the same "children deleted as SYSTEM without checking them" reason for which D-71
  refuses DELETE on item groups. It now arrives through CONFIGURE.
  This is Jenkins' own meaning of Configure on a folder, and LIMITATIONS 6 says generated children
  are not change-controlled. The defect is that the decision screen promises more than the window
  guarantees, on exactly the axis the hosting reviewer objected to (round 6: breadth of a folder
  window). Basis: B (approver informed about what the window allows), D-71 consistency.
  Fix direction: state it on the approval page of a CONFIGURE request whose item is a group. For
  example: "A folder's settings apply to the items inside it (for example implicitly loaded Pipeline
  libraries). Reconfiguring a multibranch project or organization folder can create or delete its
  generated items." Correct the two help texts and the form description, and add the point to
  LIMITATIONS 11. If the owner wants the stronger property, a DECISIONS entry could refuse CONFIGURE
  on computed folders, or require an explicit acknowledgement by the approver.
  Test: Given a pending CONFIGURE request for folder `ops` (and one for a multibranch project),
  When a designated approver opens its detail page, Then the page carries the item-group notice.
  For a CONFIGURE request on a job it carries no such notice.
  Owner: ui-dev (`action/**`, `src/main/resources/**`, `src/main/webapp/help/**`), release-manager (LIMITATIONS), human if restricting.

## LOW
- [S-34-03] `security/GrantAwareACL.java:367`, `security/GrantService.java:121-135`,
  `model/Grant.java:38` (`itemKind` stored, never consulted).
  **After approval a window matches its name, whatever item has that name.** Probe B: u1's
  approved CONFIGURE window on the Freestyle job `ops/x`. The job is deleted and a Folder `ops/x`
  is created by someone else. u1 then holds Configure on the folder
  (`PROBE-B after replace by Folder: Configure on folder ops/x = true`). The kind check of D-71
  runs only at approval. LIMITATIONS 11 (de62e60) now documents "an item that comes to have that
  name while the window is open is covered". That contradicts SPEC item 8's "confers nothing on any
  other item". Low, because the holder cannot cause the replacement alone without already holding
  what the new item would give them (D-35c or matrix-auth's creator entry). A third party, a seed
  job or an administrator has to put another item there while the window is open.
  Basis: B, SPEC/LIMITATIONS consistency. Fix direction: the grant already carries the kind, so a
  cheap hardening is to confer nothing in `GrantAwareACL` (and in `findActiveDeleteGrant` /
  `MoveGuard`) when `grant.getItemKind() != null && !grant.getItemKind().matches(item)`. Identity
  binding (S-34-01 (b)) also closes same-kind replacement. Either way the human reconciles SPEC
  with LIMITATIONS 11.
  Test: Given u1's approved CONFIGURE window on Freestyle `ops/x`, When an administrator deletes
  `ops/x` and creates Folder `ops/x`, Then u1 holds no Configure on the folder.
  Owner: human (SPEC/D-71 wording), core-dev.
- [S-34-04] `policy/GrantRequestService.java:172,195-210`: the stored scope is the string the
  requester typed, not the canonical full name of the item it resolved to.
  `Jenkins#getItemByFullName` ignores leading, trailing and doubled `/` and matches names
  case-insensitively. So `ops/` and `OPS` pass submission and approval, the form check and the kind
  show "Folder 'ops'", and the approved window confers nothing (Probe C: `'ops/' approved; Configure on ops =
  false; scopes=[ops/]`, the same for `OPS`). This fails closed, with no leak and no widening. It is
  a misleading approval: the approver is shown a name that differs from the canonical one, and the
  holder gets a window that never works. Basis: B (input validation). Fix: build the scope from
  `item.getFullName()` once the item is resolved in `checkScopeAtCreation`, or refuse a name that is
  not equal to it, with the refusal next to the field.
  Test: Given folder `ops`, When u1 submits `scopeFullName=ops/` (and `OPS`), Then the stored
  request names `ops` and the approved window confers Configure on `ops`, or the submission is
  refused next to the field.
  Owner: core-dev (`policy/**`).

## Checked and found to be fine
- Children of a folder, nested folders: `GrantScope.includes` is `fullName.equals(itemFullName)`
  (`GrantScope.java:83`). Every grant lookup (`findActiveGrant`, `findActiveGrants`,
  `findActiveCreateGrant`, `hasActiveGrant`) uses it. The delegate is still evaluated with grants
  suspended (`GrantAwareACL.java:159-160`), so matrix-auth inheritance from a folder's grant-aware
  ACL cannot carry a window to a child. The P-11 walk looks up only `Item.CREATE/CONFIGURE/DELETE`
  (identity-based `GrantAction.fromPermission`). Folders' Item/Move is `impliedBy` the generic
  `Permission.CREATE` (cloudbees-folder bytecode), so no window confers Move.
- Root: `getRootACL` builds `GrantAwareACL(delegate, (String) null)`. `checkScopeName` refuses an
  empty scope at creation and at approval. A name of `/` resolves to no item ("No such item").
  `MoveGuard.createWindowPossible` excludes the root.
- CREATE limited to a regular folder: `createAppliesTo` = `ModifiableItemGroup && !Job &&
  !ComputedFolder`, checked at submission (`checkActionsApply`), again at approval, and at runtime
  in `createConfers` against the live item. A stored or replaced grant on a job or computed folder
  therefore confers nothing. `Folder` implements `DirectlyModifiableTopLevelItemGroup`, while
  `ComputedFolder` does not implement `ModifiableItemGroup` (javap). Core's create, copy,
  `createProjectFromXML`, CLI `create-job`/`copy-job` and folder views' `createItem` all check Create
  on the ACL of the group they create in. So a window on `f` never admits `f/sub/x` or root
  creation. A copied folder's children (`Folder#onCopiedFrom` copies as the user) need Create on the
  new folder, which no window gives.
- D-35c by parent: `findCreatingGrant` and `recordCreatedItem` use `isParentOf` (parent equals the
  scope, item not the scope itself) plus the S-09 identity. A created item moved out of the folder
  loses the permission. A move or rename into the folder never records creation, because only
  `onCreated`/`onCopied` record.
- DELETE only on jobs: `deleteAppliesTo` = `instanceof Job` in `GrantAwareACL`
  (`deleteApplies`, runtime item), `findActiveDeleteGrant` (delete veto, move guard, records) and at
  submission and approval. Core `AbstractItem.delete()` (2.568.3 bytecode) runs
  `checkPermission(DELETE)`, then `ItemListener.checkBeforeDelete`, then the SYSTEM loop over
  `TopLevelItem` children, so the folder's own veto fires before any child is touched. A non-admin
  with standing Delete on a folder is refused by `DeleteVetoListener` (no window can exist).
  Matrix configurations and Maven modules are not `TopLevelItem`, so they are not in that loop.
  Residual note: a third-party `Job` that is also an `ItemGroup` of `TopLevelItem` children would
  pass `deleteAppliesTo`. None is known in core, matrix-project or maven-plugin.
- Move guard (D-59) with folders and the root: `delete` comes from the item's grant-aware ACL, so
  no window gives Delete on a folder. `create` comes from the destination ACL, so there is no window
  in the root or in computed folders. The new `noWindow` branch only changes the wording and the
  offered links (`MoveRefusal(..., false, false)`), not the decision. The message names only the
  item and a destination the mover could resolve as themselves.
- `@RequirePOST doCheckScopeFullName` (`GrantsSection.java:342-362`): the annotation plus
  `checkPermission(REQUEST_GRANT)` as the first statement, behind `getTarget()`'s
  `checkAnyPermission` and the change-control gate. `checkMethod="post"` sends the crumb. The lookup
  runs as the caller (`findScopeItem`). A missing item, an unreadable one and a Discover-only one
  (the `AccessDeniedException` is caught) all get the same `"No such item"`. Kind and full name are
  returned only for a readable item, through `FormValidation.ok/error(String)`, which escapes them.
- `ACL.SYSTEM2` in `GrantRequestService.itemExists` (`:323-326`): the reason is in a comment and the
  javadoc. It runs inside `approve` after `require`, the PENDING check and
  `ApprovalPolicy.checkDecision` (designated approver plus APPROVE, also checked first in
  `doApprove`). The context covers one `getItemByFullName`, and only a boolean leaves it. The other
  26 `SYSTEM2` lines in `src/main` are unchanged by the diff.
- Approval refusals: "gone" (`IllegalStateException`) and "not visible to the approver"
  (`IllegalArgumentException`) carry the same message. `GrantRequestItem#call` renders both through
  the same `FormErrors` path with the same status, and `approve` has no other caller. The kind is
  compared and named only for an item the approver can read. Creation refusals name the kind only
  for an item the requester can read.
- `ItemKind` persistence (B-9): a final POJO, not `Serializable`, three `String` fields, no
  converter, alias or `readResolve`. It is written through the existing `XStream2` (JEP-200 filter).
  Unreadable legacy files (`JOB`/`FOLDER`/`FOLDER_ONLY`) are skipped per file
  (`FileStore.listXmlEntities`). A `GrantScope` with a null or other type includes nothing
  (`GrantScope.java:80`), so legacy windows confer nothing, and a request without a kind cannot be
  approved. Residual: a hand-edited `<itemKind/>` without `descriptorId` would raise an NPE (500)
  at approval. Only a file-system writer can produce it.
- `tags/scopeItem.jelly`: `escape-by-default='true'`. The display name, descriptor id and full name
  are escaped. The icon comes from `TopLevelItemDescriptor#getIconClassName()` recorded server-side
  at creation (no form or databinding path sets it) and is passed to core's `l:icon`, which escapes
  it or resolves a symbol. No raw output (`grep escapeXml="false"|<j:out` over `src/main/resources`: none).
- `GrantRequiredFailure` / `GrantRequestLinks`: links are `batch-control/grants/new?scopeFullName=
  <rawEncode(resolved full name)>&actions=<enum name>`, prefixed with `${rootURL}/`, and offered only
  for an item the viewer can resolve and an action that applies. The kind is shown to the user who
  attempted the delete (who can read the item). HTTP 400, no state change. There is no open redirect.
- Legacy `scopeType`: `doCreate` ignores it. `getLegacyFormQuery` returns `null` unless
  `scopeFullName` or `from` is present, drops `scopeType`, URL-encodes every value, and the redirect
  target is the relative `new?…` behind the section gate. There is no open redirect and no
  reflected value.
- Switch off: `grantConfers` returns before the walk, `DeleteVetoListener`, `MoveGuard.check`,
  `ChangeControlledRelocationHandler`, `CreatedItemGrantListener`, the Job/Folder request entries
  and the whole `/batch-control/grants` subtree are all gated on `isChangeControlEnabled()`. The new
  `createApplies`/`deleteApplies` fields are `instanceof` tests on a required dependency
  (cloudbees-folder is not optional), so `getACL` cannot fail when the switch is off.
- Web methods (B-1, B-2): `grep "public .* do[A-Z]"` finds 45. The only new or changed one is
  `doCheckScopeFullName` (above). The others are unchanged by the diff.
- Secrets (B-6), file paths (B-7), CSV (B-8), XML parsing (B-10): the diff adds no `getPlainText`,
  `Secret`, `new File`, `Paths.get`, `resolve(` or CSV code. Notifications now carry "Item kind:
  <display name>" instead of the scope type, which is not sensitive.
- Information disclosure (B-3): the kind is shown on request pages to those who can already see the
  request (Manage, requester, designated approvers, `Visibility.canSeeGrantRequest`), next to the
  full name, as P-10 already allows. A Discover-only user learns nothing from the form check or the
  submission.
- Concurrency: the approval kind check, the state change and `GrantService.register` stay under the
  service lock. A double approval is still refused by the PENDING check.
- `@Restricted(NoExternalUse.class)` (B-14): present on `ItemKind`, `GrantScope`,
  `GrantRequestService`, `GrantRequestLinks`, `GrantRequiredFailure`.
- SpotBugs (B-15): `mvn -q -B -DskipTests verify` on a `git archive HEAD` copy (JDK 21): exit 0,
  `FindBugsSummary total_bugs='0'`, 201 classes.
- Unconfirmed: the role-strategy variant of S-34-01 and both parts of S-34-02 are analysis without
  a probe. The full test suite was not run by this review.

## Request
- Request: `docs/DECISIONS.md` (human) amend D-71 for S-34-01. Decide whether a rename of a folder
  (any kind) whose Configure comes only from a window is refused, as D-40a does for restricted
  grants and D-71 does for deleting and moving folders, and/or whether grants bind to the item's
  identity at approval. Decide for S-34-03 whether a window confers nothing once its item's kind
  (or identity) differs. Reconcile SPEC item 8 "confers nothing on any other item" with
  LIMITATIONS 11.
- Request: `src/main/java/io/jenkins/plugins/batchcontrol/security/GrantAwareACL.java` (core-dev)
  implement the S-34-01 and S-34-03 decision; `policy/GrantRequestService.java` store the canonical
  `item.getFullName()` as the scope (S-34-04).
- Request: `src/main/resources/io/jenkins/plugins/batchcontrol/action/GrantRequestItem/index.jelly`,
  `GrantsSection/_requestForm.jelly`, `src/main/webapp/help/grant-actions.html`,
  `grant-scope-full-name.html` (ui-dev) add the item-group notice on the approval page and correct
  "covers nothing else, not even the items inside a folder" / "the folder's settings, not the items
  inside it" (S-34-02). Once the owner has decided on S-34-01, also state that a CONFIGURE window on
  a folder allows renaming it.
- Request: `docs/LIMITATIONS.md` (release-manager) items 11 and 33. Name the combined effect of a
  folder rename on name-matched permissions (the holder's other windows, role-strategy item
  patterns) (S-34-01), and the reach of a folder's own settings and of computed-folder indexing
  (S-34-02).
- Request: `src/test/**`, `docs/TEST-MATRIX.md` (test-author) add the Given/When/Then rows of
  S-34-01 to S-34-04.
