# Security Review 05

Scope: `git diff main...hosting-review/authz-subclass -- src/main pom.xml` (issue #30, D-35a/b/c).
Read-only review. Maven was not run because a build was already using `target/`. Parent behaviour
was checked by disassembling the jars in `~/.m2`: matrix-auth 3.3, role-strategy
898.vc050ed2424ca_, jenkins-core 2.568.3.

## Summary: BLOCKER 3 / HIGH 1 / MEDIUM 1 / LOW 4

## BLOCKER (grounds for hosting rejection)

- [S-01] `listener/GrantViolationGuard.java:95-98` (with `model/Grant.java:118-120`,
  `security/GrantAwareACL.java:166-172`, `security/BatchControlMatrixAuthorizationStrategy.java:66-75`).
  **Problem: D-35b can be bypassed through inheritance from a folder created under a grant.**
  - Setup: the user holds a Create grant on folder `S` and creates (or copies) folder `S/F`. The grant records `S/F`.
  - Why the child gets Configure: a job `S/F/J` has no authorization property (the copy stripped it, or another user created it). Its ACL is matrix-auth's `getACL(ItemGroup)`, which calls `F.getACL()`. That is a `GrantAwareACL` for `S/F`, and it confers Configure through `findCreatingGrant`. So the user holds Item/Configure on `S/F/J`, and only through the grant.
  - Why the guard misses it: it asks `findConfigureGrant(user, "S/F/J")`. That uses an exact-name `hasCreated` lookup and finds no CONFIGURE action, so it returns null. The guard then treats the save as "not made possible by a grant".
  - Result: a POST of `S/F/J/config.xml` with an `AuthorizationMatrixProperty` naming the user stays in place. The user gets a permanent entry: privilege escalation.
  - Basis: checklist B (permission logic); D-35b.
  - Fix direction: decide "grant-made-possible" by permission and not by grant lookup. After restoring `before`, if `item.hasPermission2(auth, CONFIGURE)` is true with grants and `hasPermissionWithoutGrants` is false, revert. Find the grant to name in the record by walking the item's ancestors. Also consider whether D-35c Read/Configure should reach children the holder did not create (SPEC wording).
  - Regression test:
    - Given: the Batch Control matrix; bob has only a Create grant on `S`; bob copies folder `S/src` (which contains job `j`) to `S/F`.
    - When: bob POSTs `S/F/j/config.xml` with an authorization property granting himself Item/Configure.
    - Then: the property is reverted, a `GRANT_VIOLATION` record is written, and after the grant expires bob has no Configure on `S/F/j`.

- [S-02] `listener/GrantViolationGuard.java:90-93`.
  **Problem: the guard skips every save made as SYSTEM, so a Pipeline `properties` step writes a permanent entry.**
  - Setup: without Authorize Project, builds run as SYSTEM. A user with a Configure grant on a Pipeline job edits its (sandboxed) script to `properties([authorizationMatrix(['USER:hudson.model.Item.Configure:bob', ...])])`. The `authorizationMatrix` symbol is on `AuthorizationMatrixProperty$DescriptorImpl` in matrix-auth 3.3, and the `properties` step is allowed in the sandbox.
  - What happens: the next build (manual, or timer/SCM) saves the job as SYSTEM, and the guard returns early. The entry is permanent and unrecorded. The same applies to a Job DSL seed job the user can configure.
  - Basis: checklist B (privilege escalation); D-35b, "a grant holder cannot turn a temporary Configure into a permanent authorization entry".
  - Fix direction:
    - When the save happens inside a build (`Executor.currentExecutor() != null`, or a CPS thread) and the authentication is SYSTEM, treat any change of the authorization property on an item covered by an active CONFIGURE (or created-item) grant as a violation. Revert it unless the job's last configuration edit came from a user with native Configure.
    - At minimum, record every such change, and state in LIMITATIONS that Authorize Project is required.
  - Regression test:
    - Given: a Pipeline job `p`; bob has only a Configure grant on `p`.
    - When: bob sets the script to `properties([authorizationMatrix(['USER:hudson.model.Item.Configure:bob'])])` and `p` is built as SYSTEM.
    - Then: after the build, `p` has no property entry for bob and a `GRANT_VIOLATION` record exists.

- [S-03] `security/StrategyMigration.java:45-76` (callers: `security/BatchControlAuthorizationStrategy.java:54,61`,
  `ops/BatchControlStrategyMonitor.java:71,95,118`).
  **Problem: `StrategyMigration` fails to link unless both matrix-auth and role-strategy are installed.**
  - Cause: `toBatchControl` and `fromLegacyDelegate` return `BatchControlMatrixAuthorizationStrategy` or `BatchControlRoleBasedAuthorizationStrategy` where the declared return type is the class `AuthorizationStrategy`. To check that assignment, the bytecode verifier loads both subclasses, and with them their parents from the optional plugins, when `StrategyMigration` is linked. It does not wait for the branch to run.
  - Reproduction: JDK 17 HotSpot, same shape. Calling an unrelated static method threw `NoClassDefFoundError` for the missing superclass.
  - Effects:
    - (a) Upgrade from the legacy wrapper on a matrix-auth-only instance (the common case): `readResolve` throws. `Jenkins.authorizationStrategy` is an XStream critical field, so Jenkins does not start.
    - (b) The monitor's `it.migratable` and `doMigrate`/`doRevert` throw on single-plugin instances.
  - Basis: ARCHITECTURE section 4 ("a missing plugin never breaks class loading"); pom `<optional>`.
  - Fix direction:
    - Keep matrix-auth and role-strategy references out of any class that loads unconditionally. For example, make each subclass's `copyOf` return `AuthorizationStrategy` and put the dispatch behind `Class.forName`/`ExtensionList` lookups, or split the code into per-plugin holder classes that return `Object`.
    - Add a test harness run without each optional plugin.
  - Regression test:
    - Given: JenkinsRule without role-strategy (and a second run without matrix-auth); config.xml holds the legacy wrapper around a project matrix.
    - When: Jenkins starts, and an administrator opens `/manage` with change control on.
    - Then: Jenkins boots with `BatchControlMatrixAuthorizationStrategy`, and the monitor page renders without `NoClassDefFoundError`.

## HIGH

- [S-04] `security/StrategyMigration.java:66-71`, `security/BatchControlAuthorizationStrategy.java:61`.
  **Problem: converting a legacy wrapper around `GlobalMatrixAuthorizationStrategy` into a project-matrix subclass silently widens authorization.**
  - The global matrix ignores `getACL(Job|AbstractItem|Node)`. After the conversion:
    - every stale job, folder and agent `AuthorizationMatrixProperty` becomes effective;
    - every user with native Item/Configure can write ACL entries on that item (D-35b only covers grant holders);
    - matrix-auth's creator listeners start adding permanent entries.
  - This is mandated by SPEC item 2 ("a wrapper around the global matrix strategy becomes the Batch Control matrix strategy"). The code matches the spec; the risk is at spec level.
  - Basis: checklist B (privilege change without an administrator's action); CLAUDE.md "does not change existing Jenkins behaviour".
  - Fix direction (needs a human decision): either install the plain global matrix unwrapped and let the monitor explain, or convert and raise an administrative monitor that lists the items and agents whose properties became effective.
  - Regression test:
    - Given: a legacy wrapper around the global matrix; job `j` has a stale property granting carol Item/Configure.
    - When: Jenkins reloads.
    - Then: carol has no Configure on `j` unless the administrator confirms.

## MEDIUM

- [S-05] `listener/GrantViolationGuard.java:85-87,100,111`.
  **Problem: the baseline can go stale, and restoring it can resurrect permissions an administrator removed.**
  - Cause: the baseline is refreshed only by `SaveableListener`, `onCreated` and `onLoaded`. `AbstractItem.doReload()` (per-item "Reload from disk", core 2.568.3) fires neither `SaveableListener` nor `ItemListener`.
  - Scenario: an administrator removes an entry on disk (or through SCM-synced config) and reloads the item. The next save by a grant holder, even one that only edits the description, sees `now != before`. The guard restores the stale baseline, which brings back the removed entry, and writes a `GRANT_VIOLATION` blaming the grant holder. It is a false positive that reverts a legitimate administrator edit, and the same class of problem as PoC-5 row 12.
  - Basis: D-35b; checklist B (unrecorded or incorrect state change).
  - Fix direction: revert only what the save added. Remove entries (and inheritance changes) present in `now` but not in `before`, and never re-add entries missing from `now`. Also refresh the baseline in `ItemListener.onUpdated`.
  - Regression test:
    - Given: job `j` whose property grants carol Build; an administrator removes carol on disk and calls `j/reload`.
    - When: bob, who has a Configure grant, saves `j` with only a description change.
    - Then: carol still has no entry, and no `GRANT_VIOLATION` is recorded.

## LOW

- [S-06] `listener/GrantViolationGuard.java:105-109`: if the restore fails, the guard logs and returns, so the violating change stays (fail-open). Fix direction: record a `GRANT_VIOLATION` with a "restore failed" detail, and raise a monitor.
- [S-07] `ops/ConfigureWithoutGrantMonitor.java:125-141`: the scan uses only the root ACL. Per-item native Configure (matrix properties, now effective; role-strategy item roles) is not detected. Fix direction: document the monitor as best effort, or sample item properties.
- [S-08] Design drift. ARCHITECTURE section 4 says `getRootACL` and `getACL(ItemGroup)` are wrapped, but:
  - the role-based subclass leaves `getRootACL` unwrapped (`security/BatchControlRoleBasedAuthorizationStrategy.java:38-41`);
  - the matrix subclass does not override `getACL(ItemGroup)`.

  There is no security effect: matrix-auth's `getACL(ItemGroup)` returns `item.getACL()` or `getRootACL()`, and both are wrapped, and the root carries no grant scope (S-13). Fix direction: update ARCHITECTURE, or wrap for uniformity.
- [S-09] `security/GrantService.java:205-215`: D-35c created-item records follow only listener events. If an item is deleted on disk and then reloaded, the record survives. An item created later under the same name by someone else then answers Read/Configure for the holder until the window ends. Fix direction: record the created item's identity (for example, its creation timestamp on disk) and check it.

## Checked and found to be fine

- getACL overloads (item 1):
  - matrix: the parent overrides `getRootACL`, `getACL(Job)`, `getACL(AbstractItem)`, `getACL(Node)` and `getACL(ItemGroup)`. The first four are wrapped. `getACL(ItemGroup)` delegates to wrapped ACLs (javap).
  - role: `Job` routes to the wrapped `AbstractItem` overload; `Computer` and `Node` are wrapped.
  - Core defaults (View, Cloud, User) route through these.
- Kill switch and no active grant: `GrantAwareACL.java:158` returns false before the walk; with no active grant every decision is the delegate's (`GrantAwareACL.java:111`).
- config.xml POST, CLI update-job and REST: `AbstractItem.updateByXml` calls `SaveableListener.fireOnChange` (javap), so the D-35b guard sees them (subject to S-01/S-02).
- createItem payload, CLI create-job, copy, folder copy with children: `Baseline.onCreated` strips the property (`GrantViolationGuard.java:212-231`); `onCopied` goes through `onCreated`. Listener order is recorder (1000), then stripper (999), then matrix-auth (0).
- Web methods: `doMigrate`/`doRevert` have `@RequirePOST` and `checkPermission(Jenkins.ADMINISTER)` as their first lines (`BatchControlStrategyMonitor.java:90-92,113-115`); the forms use POST with a crumb.
- Redirect: `sameOriginPath` (`BatchControlStrategyMonitor.java:148-165`) follows only the raw path under the context path, rejects `//` and `\`, and drops the host, so there is no open redirect.
- Entry copy: `copyEntries` copies every global matrix entry; `roleMaps` copies the Global, Project and Slave maps plus the permission templates. Per-item properties are untouched.
- Legacy wrapper with a null delegate: loads as deny-all except SYSTEM (`BatchControlAuthorizationStrategy.java:50-58`, `GrantAwareACL.denyAll`).
- XStream converter (item 4): reads only `<permission>` text, resolves through `Permission.fromId`, instantiates no user-named type, and marshals no grant data.
- JCasC (item 4): delegates to the parent configurators and then copies. Grants are not part of the strategy model, so YAML cannot inject them.
- Optional extensions: the matrix, role and JCasC classes and `GrantViolationGuard` are `@Extension(optional = true)`; `ConfigureWithoutGrantMonitor` uses reflection only. The exception is S-03.
- ACL.SYSTEM2 (item 6): the diff contains no switch to SYSTEM2, only equality checks.
- Concurrency: `GrantService` mutations are synchronized; the guard serializes compare, restore and baseline update on `LOCK`.
- SpotBugs: unconfirmed (Maven not run, `target/` in use by a concurrent build).

## Request: docs/DECISIONS.md (humans) — decide S-04 (conversion of a global-matrix wrapper) and whether D-35c Configure should reach children not created by the holder (S-01)
## Request: docs/LIMITATIONS.md (release-manager) — until S-02 is fixed, document that a Configure grant on a Pipeline or Job DSL job running as SYSTEM can write authorization entries; recommend Authorize Project
## Request: src/test/** (test-author) — add the Given/When/Then tests above, including a harness run without role-strategy and without matrix-auth (S-03)
