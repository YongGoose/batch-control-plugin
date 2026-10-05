# Security Review 38: re-check of "no rename through a window at all" (D-71c; closure of security-36)

Reviewer: security-reviewer. 2026-10-05.

Scope: `git diff 3ab2c18..HEAD -- src/main` on `r6/item-scope` (HEAD `4d25a94`), the closure of
security-36 S-36-01..04 under D-71c. Core `5c9174f` (decoded rename detection
`NewItemName.webMethodOf`; rename refusal for every item in `GrantAwareACL`, including the
DELETE+CREATE path and the restricted-CREATE path; unsaved-unbinding retry; post-register identity
re-check), ui `ba2e2e2`/`5c84136` (wording). Standard: HOSTING-CHECKLIST section B, CLAUDE.md code
rules, DECISIONS D-71/D-71a/D-71b/D-71c, D-40a, D-35c, D-59, D-73, SPEC item 8 (lines 167-171).
Severity follows SECURITY-* conventions, calibrated against security-08 S-01, security-34 S-34-01
and security-36 S-36-01/S-36-02.

Verification. On a `git archive HEAD` copy under the scratchpad (never in the worktree, where the
gate runs; probes never committed): `scratchpad/sec38/src/test/java/io/jenkins/plugins/batchcontrol/
Sec38ProbeTest.java` (matrix) and `Sec38RoleProbeTest.java` (role-strategy), log
`scratchpad/sec38-probe.log`, 7 tests, 0 failures. Quoted lines below are their output. Core
2.568.3, cloudbees-folder, branch-api and job-dsl checked in bytecode with `javap`; Stapler
2088.2093 (`TokenList`, `NameBasedDispatcher`, `Stapler.canonicalPath`, `AncestorImpl`)
disassembled. SpotBugs/full gate not re-run here; the caller reports the full gate green (844/0 on
JDK 21+25).

## Summary: BLOCKER 1 / HIGH 0 / MEDIUM 0 / LOW 2

D-71c closes the variants security-36 S-36-01 named (percent-encoded `confirm%52ename`,
`%63onfirmRename`; the deprecated `doRename`; a trailing slash; the `//`, `/./`, `/../` leading
forms). A plain or percent-encoded rename of any item -- a folder under matrix, a folder or job
under role-strategy, a job created through a restricted CREATE window, a DELETE+CREATE pair -- is
now refused with HTTP 400 and recorded. Legitimate renames (native Configure, native Delete+Create,
administrator) still work, and nothing fires while change control is off.

But `NewItemName.webMethodOf` still takes the **last** token of the path, while core dispatches the
rename method at a **non-terminal** token and ignores whatever follows. Appending one extra segment
(`.../confirmRename/x`, `.../doRename/x`, `.../confirm%52ename/x`, `.../confirmRename//x`,
`.../confirmRename/%2E`) makes the detector read the trailing token instead of the method, so the
window confers Configure (or the DELETE+CREATE pair answers), and core renames. This is the same
class of fail-open S-36-01 raised, through a canonicalisation the fix did not cover. It reopens
S-36-01, the role-strategy S-34-01/S-36-02 escalation, and the D-40a / security-08 S-01 restriction
bypass. So D-71c ruling (2) ("encoded forms cannot bypass it") is not met, and S-36-01 is **not
closed**.

Closure of security-36:
- **S-36-01: not closed.** Percent-encoding, `doRename`, trailing slash and the leading `//`/`.`/`..`
  forms are closed (Probe P1). A trailing extra path segment is not (S-38-01).
- **S-36-02: closed except through S-38-01.** The plain and percent-encoded job rename, and
  `doRename`, are refused; the role holder keeps only the window's Configure during the window, not
  Build/Workspace (ROLE-JOB). The trailing-segment bypass reopens the full escalation.
- **S-36-03: addressed (analysis-only residual).** The retry set, `unbindGrant`, `saveUnbinding`,
  the cache-load and `replaceInCache` guards, the periodic retry and the post-register `stillAtName`
  re-check are all present and sound under the service monitor. Inode reuse remains unprobed
  (APFS does not reuse at once), as in security-36.
- **S-36-04: closed.** The three help texts, the approval-page notice and the New Item summary name
  core's "Delete on it plus Create in its parent" path; `ItemIdentity` says the per-item reload
  needs only Item/Configure and that the gap is file-system access. Residual wording in D-71b is
  human-owned (S-38-03, LOW).

## BLOCKER (grounds for hosting rejection)

- [S-38-01] `security/NewItemName.java:296-319` (`webMethodOf`), `:275-285` (`endpoint`),
  `:155-167` (`forRename`), `:174-182` (`forRenameIn`); consumed by
  `security/GrantAwareACL.java:456-460` (`renameOfThis`), `:394`, `:429-430`, `:591-595`.
  **Rename detection takes the last path token, but core dispatches the rename method at a
  non-terminal token and ignores the rest, so a trailing segment makes the detector miss the rename
  while core performs it. The window confers Configure (or the D-35c Configure, or a DELETE+CREATE
  pair) and the item is renamed, with no record.** `doConfirmRename`/`doDoRename` return an
  `HttpResponse` and read `newName` from the query, so they consume no path token; Stapler matches
  the method at its token and discards any token after it (`NameBasedDispatcher`,
  verified on 2.568.3 by the renames below). `webMethodOf` percent-decodes and canonicalises the
  path (fixing S-36-01's encoding variants), but it still returns the **last** token, so
  `confirmRename/x` resolves to `x`, `forRename` returns `null` ("not a rename"), `grantConfers`
  confers the window's Configure, and `createConfers`'s `forRenameIn` guard (`:591`) is likewise
  skipped. Probe results (matrix, u1 with one CONFIGURE window on folder `eN`):
  - Closed (refused + recorded): `confirmRename`, `confirmRename/`, `%63onfirmRename`,
    `confirm%52ename`, `confirm%52ename/`, `/confirmRename`, `./confirmRename`, `confirmRename/x/..`,
    `confirmRename/.`, `x/../confirmRename`, `confirmRename/confirmRename` -> each **HTTP 400,
    violations+1, folder name unchanged**. `doRename`, `do%52ename`, `confirmrename` (lowercase),
    `confirmRename%2F` -> **404** (no such method on a folder / not a method core accepts).
  - **Open (renamed, no record):** `confirmRename/extra` -> **HTTP 302, folder now 'e12x',
    violations+0**. `confirmRename/extra/` -> **302, 'e13x'**. `confirm%52ename/extra` -> **302,
    'e14x'**. `confirmRename//extra` -> **302, 'e20x'**. `confirmRename/%2E` -> **302, 'e21x'**
    (`%2E` is not dropped as `.` because canonicalisation runs on the still-encoded segment, then
    the token decodes to `.`, which becomes the last token).
  - Role-strategy, item role `sandbox.*` (Read, Configure, Build, Workspace) for u1 (fixture of
    T-08-132): folder window on `opsN` holding `opsN/prod`: `confirmRename`, `confirm%52ename`,
    `%63onfirmRename` -> 400, +1, Configure on `opsN/prod` stays **false** (closed); but
    `confirmRename/extra` -> **302, 'sandbox5', Configure on sandbox5/prod = true** and
    `confirm%52ename/x` -> **302, 'sandbox6', true** -- S-34-01 reopened. Job window on `deployN`:
    `confirmRename`, `confirm%52ename`, `doRename` -> 400, +1, Configure=true(window) Build=false
    Workspace=false; but `confirmRename/extra` -> **302, 'sandbox-deploy3',
    Configure=Build=Workspace=true** and `doRename/x` -> **302, 'sandbox-deploy4', all true** --
    the 30-minute window becomes standing role permissions (S-36-02 reopened).
  - D-40a / security-08 S-01: u1's CREATE window on `f` restricted to `/tmp-.*/`, job `f/tmp-1`
    created through it (D-35c Configure). `confirmRename?newName=prod1`, `doRename?newName=prod2`,
    `confirm%52ename?newName=prod3` and even `confirmRename?newName=tmp-2` -> 400, +1 (closed; the
    total D-71c refusal now also catches a name that matches the restriction); but
    `confirmRename/extra?newName=prod4` -> **302, 'f/prod4'** (outside the restriction),
    `doRename/extra?newName=prod5` -> **302, 'f/prod5'**, `confirm%52ename/x?newName=tmp-3` -> **302,
    'f/tmp-3'**. No record.
  - DELETE+CREATE (core's second rule through windows): u1 CREATE window on `f`, DELETE windows on
    `f/a` and `f/b`. `confirmRename` -> 400, +1; `confirmRename/extra` -> **302, 'f/b2'** (renamed).
  Basis: B (a state change the authorisation layer refuses goes through anyway; the attempt is not
  recorded). SPEC item 8 lines 167-168; D-71c rulings (1) and (2); D-40a. Rated BLOCKER as
  security-08 S-01 and S-36-01 were: the D-40a part needs one restricted window, the role part one
  window plus a name-pattern role, and both are standing privilege outliving the window. Not newly
  introduced by this diff: the last-token model predates it (old `endpoint()` also took the last
  segment); the diff fixed the encoding/`doRename` siblings but left this one, so S-36-01 is still
  open.
  Fix direction (core-dev): stop modelling the web method as the last token. Identify it as the
  **first** token after the renamed-item ancestor, which is where Stapler dispatches it and which a
  trailing segment cannot move. `forRename`/`forRenameIn` already resolve the item with
  `req.findAncestorObject(Item.class)`; take that ancestor's `getRestOfUrl()` (or
  `Ancestor.getNextToken(..)`), split off its first `/`-token and percent-decode it with
  `TokenList.decode`, and compare that to `confirmRename`/`doRename`/`checkNewName`. Then
  `confirmRename/x`, `doRename/x`, `confirmRename//x` and `confirmRename/%2E` all resolve to the
  rename method. Keep the canonical `..`/`.`/empty handling for the leading forms. Also note two
  core-blocked forms the detector still does not recognise but core happens to stop (so no rename):
  `confirmRename;jsessionid=abc` -> core 400 via SuspiciousRequestFilter, and
  `confirmRename/%2E%2E` -> core 500 (TokenList rejects a `..` token); both give violations+0 (the
  plugin records nothing). After the fix they should be refused and recorded by the plugin, not
  left to core's error path. Add unit tests for `webMethodOf`/the new resolver against every variant
  above.
  Regression test:
  - Given the T-08-130 fixture (u1's CONFIGURE window on folder `ops` holding `ops/prod`), the
    T-08-132 role-strategy fixture (item role `sandbox.*`, job window on `deploy`), and the D-40a
    fixture (restricted CREATE window on `f`, job `f/tmp-1` created through it), When u1 POSTs with a
    crumb `job/ops/confirmRename/x?newName=sandbox2`, `job/ops/confirm%52ename/x?newName=sandbox2`,
    `job/ops/confirmRename//x?newName=sandbox2`, `job/ops/confirmRename/%2E?newName=sandbox2`,
    `job/deploy/confirmRename/x?newName=sandbox-deploy`, `job/deploy/doRename/x?newName=sandbox-deploy`
    and `job/f/job/tmp-1/confirmRename/x?newName=prod`, Then each gets HTTP 4xx, the item keeps its
    name, u1 gains no standing permission (Configure/Build on `ops/prod`, `deploy` or `f/prod`
    after the window is revoked is false), and a GRANT_VIOLATION names u1.

## HIGH
None.

## MEDIUM
None.

## LOW

- [S-38-02] `security/GrantAwareACL.java:541-562` (`recordRename`) with
  `*/BlockedAttemptAudit.java` (`record`, `MAX_TRACKED_KEYS = 512`, 1-minute cooldown).
  **The GRANT_VIOLATION merge key for a refused rename includes the attacker-controlled new name
  (`"rename " + target + " " + newName`), and the new name is stored verbatim in the record detail.**
  Probe P8: 5 POSTs with the same new name -> 1 record (coalesced, correct); 20 distinct new names
  -> 20 records; a 6000-character new name -> HTTP 400 with a 6221-character detail. So a holder of
  one CONFIGURE window can append one GRANT_VIOLATION per distinct new name and, by cycling names,
  keep writing (bounded to 512 live keys, LRU-evicted, then one more per evicted key per minute).
  This is the same accepted shape as a refused restricted CREATE (D-40) and refused move (D-73),
  where the attempted name is likewise attacker-controlled and part of the key; it only applies once
  the user holds a window (a no-window rename returns before recording, `refuseRename` `used == null`
  at `:491`). Basis: B (audit-file growth), D-73. Not a new regression; flagged for symmetry. Fix
  direction, optional: cap the stored/keyed new name length (e.g. first 256 chars + length), as
  elsewhere, so a long name cannot inflate the record. Test: Given a CONFIGURE window and a
  2 MB new-name POST, Then the recorded detail is bounded and the key is the truncated name.

- [S-38-03] `security/ItemIdentity.java:50-55` javadoc and DECISIONS D-71b text.
  **S-36-04 (2) carried over.** The `ItemIdentity` javadoc now says the per-item reload needs only
  Item/Configure and that the residual gap is "file-system access to `$JENKINS_HOME` ... which is
  administrator territory", which is accurate. The matching sentence in D-71b (DECISIONS, human-
  owned) still frames the gap as needing file-system access and Administer; it should say
  "file-system access" alone for consistency. Basis: B (what requester/approver are told). Owner:
  human (DECISIONS). No code change.

## Checked and found to be fine
- Decoded detection of the S-36-01 variants (Probe P1, ROLE-FOLDER): `confirm%52ename`,
  `%63onfirmRename`, `confirm%52ename/`, a trailing `/`, `doRename`, and the leading `//`, `/./`,
  `/../` forms are all resolved to the rename method, refused with HTTP 400 and recorded with one
  GRANT_VIOLATION. `webMethodOf` reproduces Stapler's `canonicalPath` (drop empty and `.`, apply
  `..`) then `TokenList.decode`; a malformed escape falls back to the raw token (Stapler would not
  dispatch it either). Basis: D-71c (2); verified against Stapler 2088.2093 bytecode.
- The total rename refusal for every item (D-71c (1)). Plain and percent-encoded renames are refused
  for: a folder under matrix (Probe P1), a folder and a job under role-strategy (ROLE-FOLDER,
  ROLE-JOB), a job created through a restricted CREATE window including a name that matches the
  restriction (Probe P3), and a DELETE+CREATE window pair (Probe P5). `renameOfThis` is consulted
  for both Item.CONFIGURE and Item.DELETE (`:429-430`), the D-35c path (`:394`) and `createConfers`'
  `forRenameIn` guard (`:591`). Basis: D-71c (1).
- Legitimate operations are not broken (Probe P6, violations+0): a native Item/Configure holder
  renames a job (302); a native holder who also holds a window renames (302, own Configure decides);
  a user with native Item/Delete on the job and Item/Create in the parent renames via core's second
  rule (302); an administrator renames a job and a folder (302); the deprecated `doRename` works for
  a native holder (302). New Item under a restricted CREATE window still works: `checkJobName`
  validates (`tmp-9` ok, `prod9` the restriction error), `createItem` creates `tmp-4` (302) and
  refuses `prod-4` (400); `checkNewName` on a created job answers 200 with the D-71c message. Basis:
  the refusal fires only when a window would otherwise have allowed the rename (`refuseRename`
  returns at `:491` when the user's own Delete+Create or Configure suffices).
- Switch off (Probe P6): with change control off, core alone decides -- own Delete+Create renames
  (302), no permission is refused (403) -- and `grantConfers` returns `Decision.NONE` before the
  walk, and `renameOfThis` is gated on `isChangeControlEnabled()` at `:206`. No existing behaviour
  changes. Basis: S-15, CLAUDE.md.
- Other rename/relocation paths. Move is re-decided by `ChangeControlledRelocationHandler`
  regardless of the URL, so an encoded or trailing-token `move` endpoint is fail-safe (security-36
  Probe M; not the URL-only path rename uses). Job DSL `renameJobMatching` -> `renameJob` renames
  only `Job`s (`getAllItems(Job.class)`) and calls `AbstractItem#checkPermission(CONFIGURE)` through
  core's `renameTo`, which goes through this ACL (javap of `JenkinsJobManagement`); core has no
  rename CLI command; cloudbees-folder's `AbstractFolder` has no rename endpoint of its own and its
  `renameTo` delegates to `AbstractItem` (javap); a config.xml POST keeps the directory name. So
  `confirmRename`/`doRename` and their URL variants remain the only non-admin rename, and S-38-01 is
  confined to them.
- S-36-03 mechanisms. `unsavedUnbindings` (a `LinkedHashSet` guarded by `this`) holds ids whose
  file write failed; `saveUnbinding` retries and removes on success; `grants()` clears the identity
  of any cached copy still in the set on load; `replaceInCache` re-clears and re-saves before a copy
  enters the cache; `retryUnsavedUnbindings` runs on every unbinding, every `register`, and the
  expiry periodic work (`ExpiryPeriodicWork` new try block). `unbindWhere`, `unbindAt`,
  `unbindMissingItems`, `unbindGrant` are all `synchronized` and clear the in-memory identity before
  the write, so a failed write never leaves a conferring window. `GrantRequestService.approve` calls
  `stillAtName` after `register` and unbinds (fail-closed, as the approver) if the approved item is
  no longer at its name. Basis: D-71c (3), B (binding integrity); inode reuse unprobed.
- Coalescing of identical attempts (Probe P8): 5 identical-name renames write 1 record; 10
  `checkNewName` validations (GET and POST) write 0 (not recordable). Basis: D-73.
- Web methods (B-1, B-2): the diff adds no `do*` method; `refuseRename`, `renameWindow`,
  `recordRename`, `stillAtName`, `retryUnsavedUnbindings`, `unbindGrant`, `saveUnbinding`,
  `webMethodOf`, `forRenameIn` are all package-private or internal, none a Stapler entry point.
  `ACL.SYSTEM2`/`ACL.as(` (B-5): the diff adds none; the new code resolves as the caller or the
  approver. Secrets (B-6), file paths (B-7), CSV (B-8), XStream (B-9), raw output (B-4): the diff
  touches none; `Grant.itemIdentity` stays a plain String; the help/jelly changes are text only,
  escape-by-default. `@Restricted(NoExternalUse.class)` (B-14) stays on `NewItemName`, `ItemIdentity`.
- Information disclosure (B, P-10): the new approval-page notices (`isShowGroupConfigureNotice`,
  `isShowNoRenameNotice`) are shown only to the request's requester and designated approvers
  (`isConfigureNoticeAudience`), with fixed item-neutral text; they add no item name or parameter.

## Request
- Request: `src/main/java/io/jenkins/plugins/batchcontrol/security/NewItemName.java` (core-dev),
  S-38-01: resolve the rename web method from the first token after the renamed-item ancestor
  (`getRestOfUrl`/`getNextToken`, decoded) instead of the last path token, so a trailing segment
  (`confirmRename/x`, `doRename/x`, `confirmRename//x`, `confirmRename/%2E`) cannot bypass it; make
  the plugin refuse and record the `;jsessionid` and `%2E%2E` forms rather than leaving them to
  core's 400/500; add `webMethodOf`/resolver unit tests for every variant in S-38-01.
- Request: `src/main/java/io/jenkins/plugins/batchcontrol/security/GrantAwareACL.java` (core-dev),
  S-38-02 (optional): cap the new-name length used in the GRANT_VIOLATION key and detail.
- Request: `docs/DECISIONS.md` (human), S-38-03: D-71b should say "file-system access" without
  "Administer" (carried over from security-36 S-36-04 (2)).
- Request: `src/test/**`, `docs/TEST-MATRIX.md` (test-author): add the S-38-01 Given/When/Then rows
  (trailing-segment and `doRename/x` variants for a folder window, the role-strategy folder and job
  fixtures, and the restricted-CREATE job), asserting HTTP 4xx, unchanged name, no standing
  permission after revocation, and one GRANT_VIOLATION per distinct attempt.
