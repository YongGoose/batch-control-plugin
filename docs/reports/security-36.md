# Security Review 36: re-check of item-bound windows (D-71a, D-71b; closure of security-34)

Reviewer: security-reviewer. 2026-10-05.

Scope: `git diff 2aa730a..HEAD -- src/main` (28 files; core 8fad604, ui f59fb41 and 813aa90; HEAD
38502ad on `r6/item-scope`), plus the rename paths that the new refusal depends on, wherever they
are in the codebase. Standard: HOSTING-CHECKLIST section B, CLAUDE.md code rules, DECISIONS D-71,
D-71a, D-71b, D-73 (with D-35c, D-40a, D-59), SPEC item 8 (lines 167-171). Severity follows the
SECURITY-* conventions, calibrated against security-08 S-01 and security-34 S-34-01.

Verification. Everything ran on `git archive HEAD` copies under the scratchpad, never in the
worktree, where a Maven gate is running. The probes were never committed. They are
`<scratchpad>/sec36/src/test/java/io/jenkins/plugins/batchcontrol/Sec36ProbeTest.java`,
`Sec36Probe2Test.java`, `Sec36RoleProbeTest.java` and `Sec36Role2ProbeTest.java`, with logs in
`<scratchpad>/sec36-probe{,2,3}.log`. The quoted lines below are their output. The seven D-71a/D-71b
test classes ran in the same copy: `ItemBindingEventsTest`, `ItemGroupRefusalPageTest`,
`ItemIdentityBindingTest`, `ItemIdentityRoleStrategyTest`, `ItemScopeCheckTimeTest`,
`ItemScopeRestartTest` and `ItemScopeUiTest`. All 34 tests passed, 0 failures. SpotBugs ran as
`mvn -q -B -o -DskipTests verify` on a second copy (JDK 21): exit 0, `total_bugs='0'`, 204 classes.
Core 2.568.3, cloudbees-folder 6.1106, branch-api, script-security and job-dsl were checked in
bytecode with `javap`.

## Summary: BLOCKER 1 / HIGH 1 / MEDIUM 0 / LOW 2

The identity binding works. Swaps, moves, deletion with re-creation, replacement by another kind
and copies over a stale name never carry a window to another item. Every item event unbinds for
good. The stored scope is canonical. The group notice is in place.

The new refusal of window-only folder renames is weaker. It does not compare the request with the
web method Stapler actually runs. It compares the raw, still percent-encoded last segment of the
URI with the string `confirmRename`, and when that comparison misses, it confers Configure.
`POST job/ops/confirm%52ename?newName=sandbox2` therefore renames a folder that the plain URL
refuses, and writes no record. The same detection also guards the D-40a restriction on renames,
and it does not know core's `doRename` endpoint at all. Through either way in, a job created under a
name-restricted CREATE window can be renamed to any name. That reopens security-08 S-01 (S-36-01).
Separately, the reasoning that made D-71a refuse folder renames also applies to the windowed job
itself. Under role-strategy, renaming the job into one of the holder's item-role patterns turns a
30-minute window into standing role permissions on that job (S-36-02).

Closure of security-34:
- **S-34-01: not closed.** The matrix multi-window swap is closed by the identity binding (Probe
  A). The role-strategy variant is closed only for the literal URL `confirmRename`. The
  percent-encoded endpoint reopens it (S-36-01, ROLE probe).
- **S-34-02: closed.** The approval page carries the item-group notice for requester and
  designated approvers, and the form description and both help texts no longer say "covers
  nothing" (T-08-138 passes).
- **S-34-03: closed.** Probe B: replacement by a Folder, and by a new Freestyle job of the same
  kind, both give nothing.
- **S-34-04: closed.** Probe C: `ops/`, `OPS`, `/ops`, ` ops ` and `ops//` are all stored as `ops`,
  and the approved windows confer on `ops`.

## BLOCKER (grounds for hosting rejection)
- [S-36-01] `security/NewItemName.java:150-164` (`forRename`), `:216-218` (`isRenameEndpoint`),
  `:233-242` (`endpoint` from the raw `getRequestURI()`), used by `security/GrantAwareACL.java:388-403`,
  `:429-441` (`renameOfGroup`, D-71a (2)) and `:598-626` (`renameUnderRestriction`, D-40a).
  **Rename detection fails open. A rename that the endpoint string does not recognise confers the
  window's (or D-35c's) Configure, and core's `AbstractItem#doCheckNewName` then allows the
  rename.** Stapler decodes each path token before it dispatches, so `confirm%52ename`,
  `%63onfirmRename` and `confirm%52ename/` all run `doConfirmRename`. `endpoint()` compares the
  undecoded segment, so `forRename` returns `null` ("not a rename"). Core's
  `@RequirePOST @Deprecated Job#doDoRename` (`/doRename`, which delegates to `doConfirmRename`) is
  not in the list at all. Probe results:
  - D-71a (2), matrix strategy, u1 holding one CONFIGURE window on folder `eN`:
    `job/e1/confirmRename/` gives HTTP 400 and +1 GRANT_VIOLATION.
    `job/e2/%63onfirmRename` gives **302, folder now 'e2x'**, no record.
    `job/e3/confirm%52ename` gives **302, 'e3x'**, no record.
    `job/e10/confirm%52ename/` gives **302, 'e10x'**, no record.
    `//`, `/./`, `view/all/...` and an encoded item name are all refused and recorded (correct).
    `;` is rejected by core's SuspiciousRequestFilter.
  - The role-strategy variant of S-34-01 (fixture of T-08-132: item role `sandbox.*` with
    Configure for u1, one CONFIGURE window on `ops`): `ROLE confirmRename -> HTTP 400 folder now
    ops violations+1`, then `ROLE confirm%52ename -> HTTP 302 folder now sandbox2 job now
    sandbox2/prod violations+1` and `ROLE after: Configure on sandbox2/prod = true`. The role
    covers the job formerly `ops/prod` for good, which is exactly what D-71a (2) was decided to
    prevent.
  - D-40a, the reopened security-08 S-01: u1's CREATE window on `f` restricted to `/tmp-.*/`, and
    `f/tmp-1` created through it (D-35c Configure). `confirmRename?newName=prod1` gives HTTP 400.
    `doRename?newName=prod2` gives **302, job now 'f/prod2'**.
    `confirm%52ename?newName=prod3` gives **302, 'f/prod3'**.
  - D-35c folder: u1 creates folder `f/sub` through a CREATE window. The plain rename gives 400,
    `confirm%52ename` gives **302, now f/sub3**.
  Only the rename detection fails open. With an encoded `createItem`, `checkJobName` or `move`,
  the context is UNKNOWN, so a restricted grant confers nothing (fail-safe). The relocation handler
  decides a move again regardless of the URL (Probe M: `mov%65` gives 403).
  Basis: B (a state change that the authorisation layer refuses goes through anyway; a refused
  attempt is not recorded). SPEC item 8 lines 167-168 and D-40a. Rated BLOCKER as security-08 S-01
  was: the D-40a part needs one restricted window and nothing else, and it is that same bypass
  again. The D-71a part on its own would be HIGH, as S-34-01 was. Not introduced by this diff: the
  D-40a part has been open since D-40a. The diff added the D-71a part on top of the same detection.
  Fix direction (core-dev):
  (1) Derive the endpoint from the decoded path, as Stapler dispatches it: percent-decode the last
  segment of the path, or take it from the container-decoded `getPathInfo()`. Strip trailing `/`
  as now.
  (2) Add `doRename` to `isRenameEndpoint` and `isWebChangeOperation`, so it is refused and
  explained like `confirmRename`.
  (3) Unit-test `endpoint()` and `forRename()` against encoded variants.
  (4) Optional defence in depth that does not depend on the URL: in an `ItemListener.onRenamed`,
  record GRANT_VIOLATION when change control is on, the renamer lacks standing Configure, standing
  Delete plus Create in the parent, and Administer, and the item is an item group or was created
  under a restricted window. That cannot veto the rename, but it makes any further bypass visible.
  Regression tests:
  - Given the T-08-130 fixture with u1's CONFIGURE window on folder `ops`, and the T-08-132 fixture
    for role-strategy, When u1 POSTs with crumb `job/ops/confirm%52ename?newName=sandbox2`,
    `job/ops/%63onfirmRename?newName=sandbox2` and `job/ops/confirm%52ename/?newName=sandbox2`,
    Then each gets HTTP 4xx, `ops` and `ops/prod` keep their names, u1 holds no Configure on
    `ops/prod`, and a GRANT_VIOLATION names u1.
  - Given u1's CREATE window on `f` restricted to `/tmp-.*/` and the job `f/tmp-1` created through
    it, When u1 POSTs `job/f/job/tmp-1/doRename?newName=prod2` and
    `job/f/job/tmp-1/confirm%52ename?newName=prod3`, Then both get 4xx, the job keeps its name,
    and each attempt is recorded as GRANT_VIOLATION.

## HIGH
- [S-36-02] `security/GrantAwareACL.java:429-441` (`renameOfGroup` applies only to
  `GrantScope.isNonJobGroup`, `model/GrantScope.java:131-133`). Also D-71a ruling (2), "Renaming a
  job with a CONFIGURE window stays allowed", and LIMITATIONS 33.
  **Under role-strategy, a CONFIGURE window on a job lets its holder rename that job into one of
  their own item-role patterns. The time-limited window then becomes standing role permissions on
  the job, including permissions nobody approved.** D-71a refused folder renames because "permissions
  matched by full name are [carried along]: under role-strategy the holder's own item roles match
  full names by pattern ... long after the window ended" (LIMITATIONS 33). The same holds for the
  renamed job itself. Probe ROLE2 (Batch Control role-strategy variant, default project naming
  strategy): u1 has item role `sandbox.*` (Read, Configure, Build, Workspace) and an approved
  CONFIGURE window on the top-level job `deploy`. `ROLE2 before: Configure deploy=true Build=false
  Workspace=false`, then `ROLE2 confirmRename job -> HTTP 302 job now sandbox-deploy`, then
  `ROLE2 after: Configure=true Build=true Workspace=true`. After every window is revoked:
  `Configure=true Build=true`. The approver granted Configure on `deploy` for 30 minutes. The
  holder keeps Configure, and gains Build and Workspace, with no end date. The rename is recorded
  as RENAME and the window ends, so it is visible after the fact. The same works with the D-35c
  Configure on an item created through an unrestricted CREATE window. Role-strategy's own project
  naming strategy, if the administrator enables it, admits names in patterns where the holder has
  Create, which a sandbox role usually has.
  Basis: B (privilege beyond what was approved, and beyond the window's duration). SPEC item 8:
  windows end, and D-71a "a window confers something only on the very item it was approved for".
  Rated HIGH like the role-strategy variant of S-34-01: it needs one approved window plus a
  standing name-pattern role. Matrix-auth is not affected: per-item properties stay with the item,
  and inheritance follows the folder hierarchy, not names. MEDIUM is defensible if the owner
  treats role-strategy patterns without its naming strategy as the administrator's responsibility.
  Fix direction (owner decision first: it amends D-71a ruling (2)):
  (a) While change control is on, a window's Configure, D-35c's included, never confers a rename of
  any item, job or group. Renaming then needs standing Configure, core's Delete plus Create path,
  or an administrator. This is symmetric with folders, removes the pattern problem, and turns most
  of the D-40a rename logic into a plain refusal. Decide whether a DELETE window plus a CREATE
  window, under core's second path, may still rename. That would be consistent with D-59 moves.
  (b) A narrower option: apply (a) only while the role-strategy variant is the installed strategy.
  (c) At minimum: state on the approval page of a CONFIGURE request that the window lets its holder
  rename the job, and add the role-strategy consequence to LIMITATIONS 33.
  Test: Given the role-strategy variant, item role `sandbox.*` (Read, Configure, Build) for u1, and
  u1's approved CONFIGURE window on the top-level job `deploy`, When u1 POSTs
  `job/deploy/confirmRename?newName=sandbox-deploy`, Then under (a) it gets HTTP 4xx, `deploy`
  keeps its name, and one GRANT_VIOLATION names u1. In every variant, once the window is revoked,
  u1 holds neither Configure nor Build on the job approved as `deploy`.
  Owner: human (D-71a), then core-dev, ui-dev and release-manager (LIMITATIONS 33).

## MEDIUM
None.

## LOW
- [S-36-03] `policy/GrantRequestService.java:399-426` and `security/GrantService.java:928-934`
  (`register`), `:1060-1083` (`unbindWhere`).
  **Two edge cases in keeping the binding. Each needs a file system that reuses inode numbers.**
  (i) Approval race. `approve` reads the identity (`:402`), then writes the request file (`:422`),
  then registers the grant (`:426`). Item events do not take that lock. Suppose the item is deleted
  (onDeleted unbinds: the grant is not registered yet) and another item is created at the name
  (onCreated unbinds: still nothing) inside that window of a few milliseconds. The grant is then
  registered bound to the deleted directory's identity. On ext4 or xfs the new directory often gets
  the same inode, and the window confers on the new item.
  (ii) An unbinding whose file write fails clears only the in-memory copy (`:1069`). Later events
  skip that grant because `cached.getItemIdentity() == null` (`:1065`), so the write is never
  retried. After a restart, `unbindMissingItems` unbinds only windows whose item no longer exists.
  An item created at the name in the meantime, with a reused inode, matches the stale identity in
  the file. The log line "where it is unbound again" (`:1079`) is not guaranteed then.
  Both need inode reuse, plus either a deletion and re-creation by someone else within milliseconds
  of an approval, or a store write failure and a restart while the window is open. Basis: B
  (binding integrity), D-71b. Not probed: APFS does not reuse inode numbers at once. This is
  analysis.
  Fix direction: (i) after `register`, re-resolve the scope name as SYSTEM, and clear the new grant's
  identity unless the item there is the same object as the approved `item`. onDeleted fires before
  the name is freed and onCreated fires after the new item is in place, so a grant that still
  matches the same object after registration is seen by every later event. (ii) Keep failed
  unbindings in a retry set, written again on the next event or by the expiry periodic work, or
  revoke the grant when the write fails.
  Test: Given an approval during which the item is deleted and re-created under the same name
  (injected between the identity read and registration), Then the registered window confers
  nothing on the new item. Given a store that fails the unbinding write once, When another item
  is created at the name and Jenkins restarts, Then the window confers nothing.
  Owner: core-dev.
- [S-36-04] `src/main/webapp/help/grant-actions.html:10`, `help/grant-scope-full-name.html:29`,
  `config/BatchControlGlobalConfiguration/help-changeControlEnabled.html:20`,
  `GrantRequestItem/index.jelly:78`, and the D-71b text in DECISIONS and `ItemIdentity` javadoc.
  **Two statements are less exact than the code.**
  (1) The help texts and the group notice say that renaming a folder "needs an administrator (or
  standing Configure permission on it)". Core's second path also renames it: standing Delete on
  the folder plus Create in its parent, and that Create may come from a CREATE window under its
  name restriction (`GrantAwareACL.java:480-486` deliberately leaves this path to core).
  LIMITATIONS 33 states this correctly.
  (2) D-71b and the `ItemIdentity` javadoc say that the remaining gap needs "file-system access
  and Administer". The per-item reload (`AbstractItem#doReload`, CLI `reload-job`) needs only
  Item/Configure, which a folder window confers, and it keeps the child objects (`ChildLoader`), so
  cached identities stay. File-system access remains the real precondition, so the gap is still
  administrator territory.
  Basis: B (what requester and approver are told). Fix: name core's Delete plus Create path in the
  three texts and the notice, and say "file-system access" without "Administer" in D-71b and the
  javadoc.
  Owner: ui-dev, human (DECISIONS), core-dev (javadoc).

## Checked and found to be fine
- Identity binding (S-34-01 matrix, S-34-03).
  - Every grant lookup in `src/main` is the item form: `GrantAwareACL`, `MoveGuard`, the
    listeners, `ApprovalQueueDecisionHandler` and `FolderCreateWindowAction` (grep). `isBoundTo`
    compares the recorded identity and kind (`GrantService.java:118-134`).
  - Probe A: three windows (`sandbox`, `ops`, `sandbox/prod`). u1's own rename gives 400 and one
    GRANT_VIOLATION. An immediate repeat is merged (D-73). After an administrator's swap: `u1
    Configure on sandbox/prod (was ops/prod) = false, Delete=false, POST config.xml = 403`.
    Renaming back restores nothing (all three are unbound).
  - Probe B: replacement by another kind and by the same kind both give `false`.
  - Probe H: an administrator's move swap and a delete followed by a copy over the name both give
    `false`.
- Event ordering (bytecode 2.568.3, cloudbees-folder 6.1106).
  - `AbstractItem.delete` runs `performDelete`, then `SaveableListener.fireOnDeleted`, then
    `parent.onDeleted`. `Jenkins.onDeleted` and `AbstractFolder.onDeleted` call
    `ItemListener.fireOnDeleted` before `Map.remove`, so nothing can be re-created under the name
    first.
  - Rename and move fire `fireLocationChange` for the item and every descendant.
  - `ComputedFolder` child observers fire `onCreated`.
  - A copy reaches `onCreated` through `onCopied`'s default.
  - `onLoaded` fires only in the `Jenkins` constructor, never on reload, as documented.
  - `ItemIdentityListener` (ordinal -1000) runs after the change-recording listeners, so the
    records still link the window.
- Identity cache.
  - Caffeine weak keys compare by identity. The item and its descendants are forgotten on
    `onRenamed` and `onLocationChanged`, and everything at startup. `null` is never cached, with a
    10-minute TTL on the plugin clock as backstop.
  - An entry kept after deletion belongs to an object that is no longer reachable, and its window
    is already unbound.
  - Without file-system access, nothing changes the identity of the same item object without an
    event.
- Windows (NTFS) identity is the creation time in milliseconds. Collisions and NTFS tunnelling
  cannot be used through Jenkins, because every rename, move, delete, create or copy at the name
  unbinds. What remains is event-less change on disk (the D-71b gap).
- Inode reuse after deletion: closed by the onDeleted unbinding (event before the name is freed),
  by onCreated/onLocationChanged at the name for event-less deletions, and by
  `unbindMissingItems` at startup. The reload-without-event gap needs file-system access
  (S-36-04 (2)).
- D-35c by parent and identity, and CREATE on a renamed folder (Probe G). After an
  administrator's rename `f`→`f2`, Create is `false`. After renaming back, still `false`
  (unbound for good). `findCreatingGrant` also requires the CREATE window to be bound to the
  parent (`GrantService.java:361-366`).
- Move into or out of a window's folder: D-59 now uses windows bound to the item and to the
  destination (`MoveGuard.java:97,119,346`). Encoded `mov%65` endpoints give 403, fail-safe,
  because the relocation handler decides again (Probe M).
- Other rename paths for item groups:
  - Core has no rename CLI command (jar listing).
  - cloudbees-folder has no endpoint of its own (javap of `AbstractFolder`, `Folder` and
    `ComputedFolder`; `AbstractFolder.doConfigSubmit` reads no name).
  - A config.xml POST keeps the directory name.
  - script-security does not whitelist `renameTo`.
  - Job DSL `renameJobMatching` renames only `Job`s (`getAllItems(Job.class)`), never folders.
  - Moving a folder needs standing Delete, which no window confers on a group.
  - Only `confirmRename`/`doRename` and their encoded forms remain (S-36-01).
- GRANT_VIOLATION and the D-73 key: the canonical refusal is HTTP 400 with one record. The key is
  type + `rename-group <target> <newName>` + user (`GrantAwareACL.java:523-524`,
  `BlockedAttemptAudit.java:219`), merged per minute and bounded at 512 keys, the same shape as
  refused moves. A validation request (`checkNewName`) records nothing. Its message is escaped
  (`&#039;` in the probe).
- Kind-icon markup (`GrantsSection.java:366-386`, `ui/KindIcon.java`):
  - The descriptor id (attribute) and "display name 'full name'" are escaped with `Util.escape`,
    the same function `FormValidation.ok(String)` uses.
  - The icon is accepted only as `symbol-[A-Za-z0-9][A-Za-z0-9_-]*` with an optional
    `plugin-[A-Za-z0-9][A-Za-z0-9_.-]*`. No `/` is possible, so `Symbol.get` loads only core or
    plugin symbol resources, and the classes are a constant.
  - The check still runs as the caller, so a Discover-only user gets "No such item".
  - T-08-139 (hostile job name) passes.
- `WindowBinding` and the group notice:
  - Items are resolved as the viewer (`Visibility.findVisibleItem` catches the Discover
    `AccessDeniedException`). For an item the viewer cannot read, the recorded identity decides.
    Only users who already see the grant (P-10) see the state. There is no existence oracle beyond
    the grant record.
  - The notice is shown to the requester and designated approvers, with fixed text.
- Legacy scope types: `isBoundTo` is false for any type other than ITEM, `GrantScope.includes` is
  false, and approval of such a request is refused (`GrantRequestService` `checkScopeAtApproval`)
  before any binding. T-08-145 passes.
- Approval binding: the identity is read fresh from disk inside the service lock, after
  `require`, the PENDING check, `checkDecision` and `checkScopeAtApproval`. A `null` identity, or a
  name that is not canonical, is refused.
- Web methods (B-1, B-2): `grep "public .* do[A-Z]"` finds 45, and the diff adds or re-annotates
  none. `doCheckScopeFullName` keeps `@RequirePOST` plus `checkPermission(REQUEST_GRANT)` as its
  first statement.
- `ACL.SYSTEM2` (B-5): the diff adds or changes no `SYSTEM2`/`ACL.as2` line. The new code resolves
  as the caller or viewer. `onLoaded` relies on the startup SYSTEM context, and without it every
  window would be unbound, which fails closed.
- Secrets (B-6), file paths (B-7), CSV (B-8), XML (B-10): the diff adds none. `ItemIdentity` reads
  attributes of `item.getRootDir()` only, and no user input reaches a path.
- XStream (B-9): `Grant.itemIdentity` is a plain `String` in the existing POJO, with no converter.
- Raw output (B-4): no `escapeXml="false"` or `<j:out` in `src/main/resources`, and every Jelly
  file is escape-by-default. The only markup answer is `kindMarkup` (above).
- Switch off: `grantConfers` returns before the walk. `ItemIdentityListener` unbinds only active
  windows, and switching off revokes all of them.
- Concurrency: unbinding runs under the `GrantService` monitor, and the cached copy is cleared
  before the file write. Lookups copy the list under the monitor. A double approval is still
  refused by the PENDING check under the lock. The residual races are in S-36-03.
- `@Restricted(NoExternalUse.class)` (B-14): on `ItemIdentity`, `ItemIdentityListener`, `KindIcon`
  and `WindowBinding`.
- SpotBugs (B-15): 0 bugs, 204 classes.
- Unconfirmed: inode reuse (APFS does not reuse inode numbers at once), the S-36-03 races and NTFS
  behaviour are analysis. The full test suite was not run by this review; only the seven
  D-71a/D-71b classes were.

## Request
- Request: `src/main/java/io/jenkins/plugins/batchcontrol/security/NewItemName.java` (core-dev),
  S-36-01: derive the endpoint from the decoded path, add `doRename` to the rename and
  web-change operations, unit-test the encoded variants. Optionally record a post-hoc
  GRANT_VIOLATION in an `ItemListener.onRenamed`.
- Request: `src/main/java/io/jenkins/plugins/batchcontrol/policy/GrantRequestService.java` and
  `security/GrantService.java` (core-dev), S-36-03: re-check the binding after `register`, and
  retry (or revoke on) a failed unbinding write.
- Request: `docs/DECISIONS.md` (human), S-36-02: decide whether a window's Configure (D-35c
  included) may rename a job while change control is on (all strategies, role-strategy only, or
  documented only). S-36-04 (2): D-71b says "file-system access", not "and Administer".
- Request: `src/main/webapp/help/grant-actions.html`, `help/grant-scope-full-name.html`,
  `src/main/resources/.../BatchControlGlobalConfiguration/help-changeControlEnabled.html`,
  `.../GrantRequestItem/index.jelly` (ui-dev), S-36-04 (1): name core's Delete plus Create path.
  Depending on the S-36-02 decision, add a rename statement for CONFIGURE requests on jobs.
- Request: `docs/LIMITATIONS.md` (release-manager), item 33: the role-strategy consequence of
  renaming a job under a window (S-36-02). Item 11: the reload gap needs file-system access
  (S-36-04).
- Request: `src/test/**`, `docs/TEST-MATRIX.md` (test-author): add the Given/When/Then rows of
  S-36-01 (encoded `confirmRename` variants for a folder window and for the role-strategy fixture;
  `doRename` and `confirm%52ename` for a job created under a restricted CREATE window) and S-36-02.
