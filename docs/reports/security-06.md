# Security Review 06

Re-review of `git diff 4a531d2..HEAD -- src/main` against security-05.md and D-35d.

## Summary: BLOCKER 0 / HIGH 0 / MEDIUM 0 / LOW 1

## S-01..S-09 verdicts
- S-01 (BLOCKER, inheritance bypass): FIXED. `GrantAwareACL.hasPermission2` (security/GrantAwareACL.java:126-133) now evaluates the delegate with `withoutGrants(...)`, so an ancestor's own D-35c/scope conferral can never leak into a descendant's inherited answer; only the outermost (checked-item) layer consults grants, and FOLDER-scope grants still match by path there. Decision in `GrantViolationGuard` is now permission-based (`onlyFromGrant = !hasPermissionWithoutGrants && item.hasPermission2`, listener/GrantViolationGuard.java:145-150), matching D-35d(1). `findCreatingGrant` also gained the S-09 identity guard.
- S-02 (BLOCKER, SYSTEM saves): ACCEPTED-BY-RULING (D-35d(2)). Guard still returns on SYSTEM/anonymous (GrantViolationGuard.java:118-122); `BatchControlStrategyMonitor.isBuildAuthenticatorMissing()` + jelly now warn when no QueueItemAuthenticator is configured (ops/BatchControlStrategyMonitor.java:88-97, message.jelly). No further code fix expected per ruling.
- S-03 (BLOCKER, optional-dependency NoClassDefFoundError): FIXED. `StrategyMigration` no longer references `BatchControlMatrixAuthorizationStrategy`/`BatchControlRoleBasedAuthorizationStrategy` directly; it gates on `pluginActive(shortName)` then calls new core-typed holders `MatrixStrategies`/`RoleStrategies` (security/StrategyMigration.java:45-80), which are the only classes whose linking touches the optional plugin types. `BatchControlAuthorizationStrategy.readResolve()` wraps the conversion in `catch (LinkageError | RuntimeException)` as a second line of defense (security/BatchControlAuthorizationStrategy.java:59-83).
- S-04 (HIGH, silent widening on global-matrix conversion): FIXED per ruling D-35d(3). `fromLegacyDelegate` returns null for `isPerItemWidening` (global matrix), installing it unwrapped; `doMigrate`/monitor now expose `isPerItemWidening()` and the jelly shows an explicit warning paragraph before the button (ops/BatchControlStrategyMonitor.java:120-127, message.jelly). Requires an explicit admin POST; `@RequirePOST`+`checkPermission(ADMINISTER)` intact (BatchControlStrategyMonitor.java:141-142,157-158).
- S-05 (MEDIUM, stale-baseline resurrection): FIXED. Baseline now prefers the on-disk config snapshot (`snapshotPropertyXml`, GrantViolationGuard.java:325-354) and, per the D-35d amendment, `withoutAdditions` (GrantViolationGuard.java:180-230) only strips entries present in `now` but absent from `before`; entries missing from `now` are never re-added (confirmed by trace: `kept` is built solely by iterating `now`'s entries).
- S-06 (LOW, fail-open on restore failure): FIXED. Both the compare failure and the apply failure now call `appendViolation(...)` with a "FAILED" detail (GrantViolationGuard.java:129-142, 157-168) instead of only logging. No dedicated monitor raised (minor gap, consistent with LOW).
- S-07 (LOW, root-ACL-only scan): unchanged, now documented as best-effort in the class javadoc (ops/ConfigureWithoutGrantMonitor.java:34-38). Basis: checklist item 9, accepted design limitation.
- S-08 (LOW, design-doc drift): not addressed by this diff (doc-only item, ARCHITECTURE.md untouched in this diff); no security effect, as before.
- S-09 (LOW, created-item replay via delete+recreate): FIXED. New `security/ItemIdentity.of(File)` records a file-key/creation-time marker; `Grant.hasCreated(name, Supplier)` (model/Grant.java:132-142) requires it to match; `GrantService.pruneCreatedItems` + `CreatedItemGrantListener.onLoaded` drop stale records on reload/startup.

## Regression checks requested
- GrantAwareACL parent evaluation with grants off: traced the matrix-auth nested-wrapper chain (getACL(Job)/getACL(AbstractItem) each wrap `super.getACL(...)`, which recurses into the overridden AbstractItem overload for ancestors). `withoutGrants` is a nestable ThreadLocal counter with try/finally, so ancestor GrantAwareACL instances correctly see `suspended()==true` at every depth and skip only their own `grantConfers`; the outermost (checked item) layer's own check runs unsuspended before delegating, so FOLDER-scope and item-own D-35c grants are not lost ("disappear"), and no path double-consults grants (all `return true`s are mutually exclusive: own-item check first, then one suspended delegate call). Role-strategy's `getACL(Job)` forwards to `getACL(AbstractItem)` directly (no nested wrap), so this concern is matrix-specific and is handled. No regression found.
- Optional-dependency split: `MatrixStrategies`/`RoleStrategies` (new, core-typed-only signatures) correctly isolate the eager-verification chain; `StrategyMigration` itself only ever references these two safe holders. Confirmed no leftover direct references to the plugin subclasses remain in `StrategyMigration`.
- Created-item identity: `ItemIdentity.of` fails closed (returns null on missing/unreadable dir → `hasCreated` denies when a recorded identity can't be reproduced); grants persisted before S-09 (no recorded identity) fall back to name-only matching — an accepted, narrowing transitional gap, not a new hole.

All call-site signatures (`findCreatingGrant`, `findConfigureGrant`, `recordCreatedItem`) were grepped for consistency; no stale 2-arg call sites remain.

## LOW
- [S-06 residual] No administrative monitor is raised on a restore/compare failure, only a ChangeRecord. Fix direction unchanged from security-05.

## Checked and found to be fine
- doMigrate/doRevert: `@RequirePOST` + `checkPermission(Jenkins.ADMINISTER)` as first two lines, unchanged (ops/BatchControlStrategyMonitor.java:141-142, 157-158).
- Redirect (`sameOriginPath`): unchanged, still rejects `//`/`\\` and host.

## Unconfirmed
- SpotBugs report: not read (target/ owned by a concurrent Maven build per instructions).
- Dynamic/integration confirmation of S-01..S-09 (e.g. GrantSelfGrantGuardTest, StrategyMigrationTest, StrategyUpgradeTest actually passing): tests exist and target the right scenarios by name, but were not executed (Maven not run per instructions).

## No BLOCKER or HIGH remains open. S-02 is an accepted, documented residual risk (D-35d ruling); everything else in security-05 is fixed or was already a documented LOW.
