# Security Review 28: the per-item authorization guard (part 6, `fix/def-38`)

Reviewer: security-reviewer. 2026-09-30.

Scope: `git diff main..HEAD -- src/main` (D-58a guard in `listener/GrantViolationGuard`, `CreatedItemGrantListener`, `model/Grant#changedItems`, `GrantService`, `BuildLogNotice`; the strategy monitor list; FD-14, FD-15, FD-16, UX-18, UX-19, DD-05; role form checks for scan alerts 31-33).

Standard: HOSTING-CHECKLIST section B; DECISIONS D-58, D-58a (1)-(5); ARCHITECTURE 5 "Grant file fields"; SPEC D-58/D-58a line; security-27.

Constraint: Maven was not run and `target/` was not read (a full build was running there), so SpotBugs is unconfirmed. Every finding comes from reading source; none was reproduced. The Jenkins behaviour cited was checked against `jenkins-core-2.568.3-sources.jar`, `workflow-cps-4383` (bytecode), `workflow-multibranch-842` (bytecode), `role-strategy-898` (bytecode), `cloudbees-folder-6.1106` and `jenkins-war-2.568.3`.

## Summary: BLOCKER 3 / HIGH 0 / MEDIUM 2 / LOW 4 / INFO 7

The per-item rule closes every security-27 finding it was meant to close (see the table below). Three bypasses remain. In each one, the holder uses only what a CONFIGURE window gives and ends up with a permanent entry. One is an off-by-cap in the S-27-02 fix. One is a script planted through Replay, which never saves the item. One is the "changed under a grant" state, which does not reach the children of a changed folder or multibranch project.

## BLOCKER

- **[S-28-01] `GrantViolationGuard.java:621` (`apply`): at most 100 job authorization properties are removed, so the 101st stays and becomes the effective one.** `Job.removeProperty(Class)` removes the first match per call (core `Job.java:596-604`), and matrix-auth reads `getProperty(Class)`, which is also the first match. Take a save that leaves 101 or more `AuthorizationMatrixProperty` instances, for example a `config.xml` POST, CLI `update-job` or Job DSL `updateByXml` payload, or a `properties` step, which does not de-duplicate. `propertyXml` compares the union correctly. But `apply` stops after 100 removals and appends the narrowed property *after* the survivor, so the survivor's entries are what the ACL uses. The same `apply` serves D-35b, D-35c `stripPayload` and the creation strip. In the D-35b path the survivor also gives the holder native Configure. That makes `onlyFromGrant` false, so "the change stands". Basis: B (privilege escalation). Fix direction: in the `BulkChange`, remove every instance by iterating a copy of `getAllProperties()` with `removeProperty(JobProperty)`. After the commit, assert `propertyCount(item) <= 1`, and record a failed restore if the assertion fails. Test: *Given* bob holds a CONFIGURE window on job `J`; *when* he POSTs a `config.xml` with 101 authorization properties, the last one giving `USER:bob` Job/Configure; *then* `J` has exactly one authorization property, bob has no native Configure on `J`, and one GRANT_VIOLATION is recorded. Add the same row for a `createItem` payload (D-35c) and for a creation inside a guarded folder.

- **[S-28-02] Replay through the window plants a script without a save, so the job is never marked (`updateChangedState`, l.147-165).** In workflow-cps, `ReplayAction.REPLAY` is `impliedBy Item.CONFIGURE`, and `GrantAwareACL` walks `impliedBy`, so a CONFIGURE grant allows Replay. A replayed script is stored with the run, not in the job's config, so no `onChange` fires and `markChanged` is never called. Once the window ends, the job is unguarded. Three things can then run the planted script: a replayed run still running (a `sleep` or `input` before `properties([authorizationMatrix(...)])`); Pipeline's own Rebuild on that run, which needs only Item/Build and replays the run's stored script; and Declarative "Restart from Stage". Each writes the holder's entry and keeps it. This applies only to jobs that do not require run approval (the queue gate refuses Replay on `approvalRequired` jobs). Basis: B (privilege escalation). Fix direction: when a run is queued with a `ReplayCause` and the replaying user's Replay comes only from a grant, mark the job changed under that grant (`QueueListener`/`RunListener`). Also keep a job guarded while any run replayed under a grant can still run or be rebuilt. Or refuse Rebuild and Restart from Stage of such runs after the window. LIMITATIONS 35 must say that a review has to cover runs replayed under the grant. Test: *Given* bob holds a CONFIGURE window on Pipeline job `J` without `approvalRequired`; *when* he replays a run with a script that writes `USER:bob` Job/Configure after the window ends; *then* the entry is removed, GRANT_VIOLATION is recorded, and `J` is listed on the strategy monitor.

- **[S-28-03] The "changed under a grant" state does not reach descendants (`GrantService#isGuardedItem`, `hasChanged` is an exact-name match).** A changed folder's configuration shapes what its children run. On a multibranch or organisation folder, the branch source or Jenkinsfile path decides the script of every existing branch job. On a regular folder, a folder-level (untrusted) Pipeline library can shadow a step through `vars/`. After the window, only the folder itself is guarded. Existing children, such as `MB/main`, are covered neither by an active grant nor by `changedItems`. So a Jenkinsfile the holder controls can run `properties([authorizationMatrix(bob ...)])` on them and the entry is kept. New children are guarded (`guardCreationInGuardedFolder`), but existing ones are not. D-58a (1) guards only "every item whose configuration was changed", so this is also a spec gap. Basis: B (privilege escalation). Fix direction: treat an item as guarded when it *or any ancestor* is in `changedItems`, mirroring how coverage already reaches ancestors. A review of the folder then clears the folder only, as now, and each child stays guarded until the folder is reviewed. Proposal for DECISIONS below. Test: *Given* bob changed the branch source of multibranch project `MB` under a window that has now ended, and `MB/main` exists; *when* a build of `MB/main` writes `USER:bob` Job/Configure; *then* it is removed and GRANT_VIOLATION names `MB/main`. Add the same row for a folder `F` and its existing child `F/J`.

## MEDIUM

- **[S-28-04] Lock-order inversion between `GrantViolationGuard.LOCK` and the item monitor can deadlock every item save on the controller.** `AbstractItem#updateByXml` (core l.929-954) fires `onChange` without holding the item's monitor. The guard takes `LOCK`, and `apply` then needs the item's monitor (`save()` is `synchronized`). Meanwhile a build's `properties` step holds the same monitor in `Job.save()` and waits for `LOCK`. Once the two threads deadlock, every later job or folder save on the instance blocks on `LOCK`. `Baseline#onCreated` does the same for inner items (`LOCK` taken first, then each child's monitor). The holder can provoke it: a looping `properties` build plus a widening `config.xml` POST. The pattern existed before (D-35b), but D-58a makes the revert reachable by any saver. Basis: B (availability; authenticated DoS). Fix direction: one order everywhere, `synchronized (item) { synchronized (LOCK) { ... } }` in `onChange`. In `onCreated`, collect the children under `LOCK` and apply outside it, or use per-item locks. Test: *Given* a guarded job; *when* 50 concurrent `config.xml` POSTs with a widening race a build that loops `properties`; *then* all of them finish within a timeout.

- **[S-28-05] The review counts any HTTP save by an administrator or a native Configure holder (l.137, l.159-161).** Many HTTP saves are not a configuration review, for example Disable/Enable (`makeDisabled`), Submit description, or another plugin's action that calls `save()` during the request. Each of them clears the state with no look at the script. LIMITATIONS 35 tells the reviewer to "check the Pipeline script before saving", but the review can happen by accident. Basis: B (the guard ends without an informed decision). Fix direction: count only `configSubmit` and `config.xml` POST as a review. Better: add an explicit "Mark reviewed" POST on the monitor (`@RequirePOST` plus `checkPermission(ADMINISTER)` as its first two lines) that writes a change record. Proposal for DECISIONS below. Test: *Given* `J` is changed under a grant; *when* an administrator disables and re-enables `J`, or edits its description; *then* `J` is still guarded and still listed.

## LOW

- **[S-28-06] A lost mark fails open.** The item's `config.xml` is written before `markChanged` persists the grant. A crash in between, or a store error (only logged at WARNING, l.163 and `GrantService#markChanged`), leaves a holder's save unmarked. The item is unguarded once the window ends. Fix: log SEVERE and raise the strategy monitor on a failed mark. At startup, reconcile from the CONFIGURE change records made by grant holders during an active window.
- **[S-28-07] Rename/move race.** Core renames under the item monitor but calls `fireLocationChange` after releasing it (AbstractItem l.453). A save under the new name in that gap is judged before `relocateChanged` has run, so it is unguarded. The snapshot is also written under the new name. `Job.checkRename` and cloudbees-folder's `renameBlocker` refuse renames while builds run, so only a build that starts in the gap can race. Fix: in `onLocationChanged`, re-run the widening check for every relocated item against its pre-move baseline.
- **[S-28-08] Marking and review are judged on the post-save ACL (l.153).** If comparing fails or the revert fails, or if a guarded item has no baseline (`before == null`), a widened native entry for the saver counts: their HTTP save skips the mark, and with no baseline it clears the state as the review. So a holder can review their own item only in these failure cases. Otherwise, no path was found. Fix: judge `nativeConfigure` against the baseline property. Treat no baseline on a guarded item as a violation, and never as a review.
- **[S-28-09] Delete and re-create drops the state.** `forgetChanged` runs on delete. An identity with Item/Delete and Item/Create, typically a Job DSL seed job running as SYSTEM (the case D-50a warns about), can re-create a guarded name after the window with any entries. Fix: keep the name marked for a grace period after deletion, or apply the creation strip to marked names. Otherwise document it in LIMITATIONS 35.

## INFO

- **[S-28-10] Role form checks (alerts 31-33): `checkAnyPermission(SYSTEM_READ, ITEM_ROLES_ADMIN, AGENT_ROLES_ADMIN)` is adequate.** Upstream role-strategy 898 gates `doCheckName`'s realm lookup on `SYSTEM_READ` (it returns `ok` without a lookup otherwise), `doCheckForWhitespace` on `ITEM_ROLES_ADMIN`/`AGENT_ROLES_ADMIN`, and `doCheckPattern` on nothing. SystemRead can already open the read-only Manage/Assign Roles pages, which list every sid. The wrapper reveals nothing beyond that, and `doCheckPattern` only compiles the pattern and never matches with it.
- **[S-28-11]** The `SelfGrantRevertedFailure` text (ui-dev) still describes D-58's principal rule ("a user who holds or recently held a window"). Under D-58a, any widening on a guarded item gets this answer, including an entry for an unrelated user, so the text misleads.
- **[S-28-12]** `IncidentItem/index.jelly` (the non-rerun block): when the viewer cannot read the job, `${it.jobUrl}` is null, so the link points to `${rootURL}/` under the job's name. The name is already shown on the page, so nothing leaks. UX only.
- **[S-28-13]** `ApprovalRebuildValidator#targetsRebuild` matches on a substring, so a POST re-render under an item named `rebuild` does not withhold the link. UI only; the queue gate still refuses and records the rebuild.
- **[S-28-14]** D-58a (2) says "the CLI over any transport" is guarded, but an administrator's `-http` CLI runs on a Stapler request thread and gets the (3) exemption. The code is consistent with (3). The wording should say so.
- **[S-28-15]** S-27-16 is still open: `holdsActiveGrant` compares ids with `String#equals`, not `IdStrategy`. D-58a catches what this misses.
- **[S-28-16]** Grant scopes still stay on the old name (existing behaviour). After the holder renames `F` to `G`, an item created later under the name `F` falls under the active grant. `relocateChanged` keeps `G` guarded, but the new `F` is covered too.

## security-27 closure

| Finding | State | Basis |
|---|---|---|
| S-27-01 `anonymous` | closed | `narrowToBaseline` drops every added entry for any sid |
| S-27-02 second property | **reopened in part** | union compare and `count > 1` are fine; `apply` caps at 100 (S-28-01) |
| S-27-03 rename | closed (race: S-28-07) | `relocateChanged` carriers; core fires `onLocationChanged` for every descendant (ItemListener l.259-266), so moved children are marked too |
| S-27-04 inheritance | closed | a class change is undone unless it goes to `NonInheriting`; a removed property is restored |
| S-27-05 creation | closed | `guardCreationInGuardedFolder` in `onCreated`, which also covers copies and multibranch children |
| S-27-06 group lookup | moot | no principal lookup any more (`GuardedPrincipals` deleted) |
| S-27-07 30-day lapse | closed, new gap S-28-02 | state lasts until the review; retention keeps the grant file |
| S-27-08 unguarded principals | closed | any sid |
| S-27-09 realm under LOCK | moot | no realm call under `LOCK` (new lock issue: S-28-04) |
| S-27-10 log fallback | closed | `BuildLogNotice` writes only from the saving CPS thread of that job |
| S-27-11 control characters | closed | `safe()` replaces control characters and caps each entry; `describe` caps at 20 |
| S-27-12 CLI transport | closed by D-58a, wording: S-28-14 | a WebSocket or SSH CLI save has no Stapler request, so it is guarded even for an administrator |
| S-27-13, -14 | still true | no change |
| S-27-15 administrator impact | narrowed | only guarded items |
| S-27-16 | open (INFO S-28-15) | unchanged |

## Answers to the caller's questions

- Job DSL seed or `jobDsl` step: updates are guarded (`updateByXml` fires `onChange` with no Stapler request) and creations are stripped (`onCreated`). Delete and re-create by a powerful seed: S-28-09.
- Multibranch and computed children: new children are covered; existing children of a changed parent are not (S-28-03).
- `config.xml` POST by a non-administrator: reverted with a 403 (D-48), and it is not a review. The 101-property payload is the exception (S-28-01).
- CLI `update-job` over WebSocket: guarded, never exempt, never a review.
- Rename race: S-28-07 (LOW).
- `changedItems` lost on a crash: S-28-06 (LOW).
- Item moved out of a guarded folder: marked under its new name, with every descendant (per-child `onLocationChanged`).
- A review save that widens: an administrator's is exempt by design (3). A native Configure holder's is reverted and does not count (`http && !reverted`).
- Can a holder review their own item? Not on any normal path. The failure-mode exceptions are in S-28-08. Unintended reviews: S-28-05.
- SYSTEM_READ for the role form checks: adequate (S-28-10).
- Grant file load: safe. It is read by `XStream2` (JEP-200 class filter, `RobustReflectionConverter`). A missing `changedItems` means `null`, and every accessor is null-safe. The field is written only when non-empty (`setChangedItems`), so older files load unchanged, and an older plugin reading a new file skips the unknown field. Retention skips grants with a non-empty `changedItems` and keeps their index entry.
- Web methods: see below.

## Checked and found to be fine

- Web methods (B-1/2): all 43 `public ... do[A-Z]` in `src/main/java` were listed. The six without a POST annotation are a servlet `doFilter`, two `PeriodicWork#doRun` and three read-only GET handlers (`HistorySection#doSummary`/`doDynamic`, `ActiveGrantsSection#doIndex`), all unchanged since security-27. The three role checks gained a permission check as their first statement. The diff adds or removes no `do*` method.
- Raw output (B-4): no `escapeXml="false"` or `<j:out>` in `src/main/resources`. All four changed Jelly files have `escape-by-default='true'`. The monitor lists item names escaped and links them as administrator (default `ADMINISTER` monitor permission).
- FD-14: a `FormException` now carries plain text, escaped exactly once. The apply notification uses `htmlAttributeEscape` and then a text node (`notificationBar.show`), and the error page is `Failure`/escaped Jelly.
- FD-15: typed numbers from the same Manage user are rendered into escaped attributes.
- UX-18, DD-05, FD-16: no security effect. The `isRebuilt` reflection is exception-safe. The queue gate stays authoritative.
- `ACL.SYSTEM2` (B-5): the diff adds no switch, only `equals` tests.
- Secrets, file paths, CSV (B-6/7/8): not touched. Grant files are still resolved with `PathCodec.resolveUnder`.
- XStream (B-9/10): the guard reparses only XML it serialised itself. The snapshot is read with `XMLUtils.parse` and unmarshalled per element with `Items.XSTREAM2`.
- Administrator exception: native `ADMINISTER` with the grant layer off, plus a Stapler request. Builds, CPS threads and WebSocket CLI threads have none.
- Monitor: `isActivated` is answered from the in-memory grant cache and shown only while change control is on.
- SpotBugs (B-15): unconfirmed (Maven not run, `target/` not read).

## Request: docs/DECISIONS.md (humans) amend D-58a (1): a descendant of an item in the "changed under a grant" state is guarded (S-28-03); a Replay by a grant-only user puts the job in that state (S-28-02); the review is an explicit administrator action or a `configSubmit`/`config.xml` save only (S-28-05).
## Request: src/main/java/io/jenkins/plugins/batchcontrol/{listener,security}/** (core-dev) fixes for S-28-01..04 and S-28-06..09.
## Request: src/main/java/io/jenkins/plugins/batchcontrol/{ui,action}/**, src/main/resources/** (ui-dev) S-28-11, S-28-12, and the "Mark reviewed" action if S-28-05 is taken that way.
## Request: src/test/** (test-author) rows for S-28-01..05 as given above.
## Request: docs/LIMITATIONS.md (release-manager) item 35: runs replayed under a grant (S-28-02), the review scope (S-28-05), delete and re-create (S-28-09), `-http` CLI wording (S-28-14).
