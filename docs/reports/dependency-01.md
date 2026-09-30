# Dependency Audit 01: plugin dependencies and co-installed plugins

Branch `docs/dependency-audit` from `main` at 6c317a9. 2026-09-30. Read-only on `src/`, `pom.xml` and `.github`.

Commands run in the worktree:

- `mvn -ntp dependency:tree`: exit 0
- `mvn -ntp validate`: exit 0
- `mvn -ntp package -DskipTests`: exit 0, `target/batch-control.hpi`
- `mvn -ntp test -DforkCount=2 -Dtest='OptionalDependencyWithout*Test,EmailNotificationWithoutMailerTest,StrategyCascTest,PluginInteractionRebuildTest'`: 8 tests, 0 failures, 0 errors
- `https://updates.jenkins.io/current/update-center.actual.json` (fetched 2026-09-30, current core weekly 2.584), to get each plugin's current release and its `requiredCore`
- `javap` on the upstream jars in `~/.m2`, plus role-strategy 918 and configuration-as-code 2130 downloaded to scratch, to check signatures and `@Restricted`

## Summary: HIGH 0 / MEDIUM 3 / LOW 6. Nothing to fix now.

The dependency set is clean. The hpi bundles only its own jar. Every enforcer rule passes, and so does the access-modifier checker. Every current release of every integrated plugin runs on the 2.568.3 baseline. The design for optional plugins holds: each compile-time optional plugin sits behind `@Extension(optional = true)` or a holder class that is linked only after a `getPlugin` check. Every other plugin is matched by class-name string.

The real risks are version-related:

- The floors on optional plugins are recent. An older installed copy stops batch-control from loading.
- The name-matched and reflective paths depend on upstream class and method names. Most of them fail closed, but one fails open: the "replayed under a grant" marking.

## 1. Direct dependency tree

`jenkins.baseline` 2.568, `jenkins.version` 2.568.3 (pom.xml:36-37). BOM `bom-2.568.x` 7093.v37de7b_4a_8a_4f (pom.xml:54). Parent `plugin` 6.2236.v12dd4c483242.

| Dependency | Scope in pom | Resolved version | BOM-managed | Manifest |
|---|---|---|---|---|
| cloudbees-folder | compile, **required** | 6.1106.v3a_d9a_6d2465e | yes | required |
| ionicons-api | runtime, **required** | 94.vcc3065403257 | yes | required |
| matrix-auth | compile, optional | 3.3 | yes | `resolution:=optional` |
| role-strategy | compile, optional | 898.vc050ed2424ca_ | yes | optional |
| configuration-as-code | compile, optional | 2121.v86fe99d4b_b_a_b_ | yes | optional |
| mailer | compile, optional | 534.v1b_36f5864073 | yes | optional |
| rebuild | compile, optional | 338.va_0a_b_50e29397 | yes | optional |
| mock-javamail | test | 2.2 | **no** (pinned, pom.xml:71-73) | - |
| customize-build-now | test | 53.v64eb_d7d017d2 | **no** (pinned, pom.xml:192-193) | - |
| workflow-job, workflow-cps, workflow-basic-steps, workflow-multibranch, pipeline-build-step, pipeline-model-definition, job-dsl, parameterized-trigger, build-token-root, naginator, lockable-resources, throttle-concurrents, authorize-project, jobConfigHistory | test | see tree | yes | - |
| jenkins-core 2.568.3, servlet-api, commons-logging | provided | - | parent | - |

The built manifest has `Plugin-Dependencies: rebuild:338...;resolution:=optional, ionicons-api:94..., configuration-as-code:2121...;resolution:=optional, cloudbees-folder:6.1106..., mailer:534...;resolution:=optional, matrix-auth:3.3;resolution:=optional, role-strategy:898...;resolution:=optional`. This matches README.md:167-169 ("cloudbees-folder and ionicons-api, both required"). Both required dependencies are used: `com.cloudbees.hudson.plugins.folder.*` is imported in 9 main classes, and `plugin-ionicons-api` symbols appear in 13 Jelly places. No unused plugin dependency was found, which covers hosting checklist item A "no unused plugins".

## 2. Optional integrations

### 2a. Detection mechanism and absence behaviour

| Plugin | How it is detected | Absent: behaviour | Absent: test |
|---|---|---|---|
| matrix-auth | Compile-time. `BatchControlMatrixAuthorizationStrategy.DescriptorImpl` is `@Extension(optional = true)` (security/BatchControlMatrixAuthorizationStrategy.java:113). `GrantViolationGuard` and `.Baseline` are optional (listener/GrantViolationGuard.java:85, 653), and `Baseline` also checks `getPlugin("matrix-auth")` (:666). Migration goes through `StrategyMigration.pluginActive` (security/StrategyMigration.java:36) and then calls the holder `MatrixStrategies`, which returns core types only. No non-optional class links a matrix-auth type (checked with grep). | The strategy variant, the property guard and the matrix JCasC configurator are not loaded. The role path works. | **yes**: `OptionalDependencyWithoutMatrixAuthTest` (`omitPlugins("matrix-auth")`), passed |
| role-strategy | Compile-time. `BatchControlRoleBasedAuthorizationStrategy.DescriptorImpl` is optional (:139). The holder `RoleStrategies` is called only behind `pluginActive("role-strategy")`. | The variant is not offered. The matrix path works. | **yes**: `OptionalDependencyWithoutRoleStrategyTest`, passed |
| configuration-as-code | Compile-time. `BatchControlMatrixCascConfigurator` and `BatchControlRoleBasedCascConfigurator` are `@Extension(optional = true)` (security/...:28, :25). | The configurators are not loaded. The case "JCasC present, matrix-auth absent" is covered indirectly, because the without-matrix-auth test keeps JCasC on the classpath. | **no** dedicated without-JCasC test |
| mailer | Compile-time. `MailNotifier` is `@Extension(optional = true)` (ops/MailNotifier.java:26). The global configuration checks `getPluginManager().getPlugin("mailer")` (config/BatchControlGlobalConfiguration.java:516). | The option is hidden and requests work. | **yes**: `EmailNotificationWithoutMailerTest`, passed |
| rebuild | Compile-time. `ApprovalRebuildValidator extends RebuildValidator` is `@Extension(optional = true)` (policy/ApprovalRebuildValidator.java:24). The queue matches `com.sonyericsson.rebuild.RebuildCause` by name (queue/ApprovalQueueDecisionHandler.java:113). | The validator is not loaded, and the name match never hits. | **no** without-rebuild test |
| workflow-cps (Replay, Pipeline Rebuild) | Name match: `ReplayCause`, `ReplayFlowFactoryAction` (ApprovalQueueDecisionHandler.java:84-85, 186-187). Reflection: `getOriginalNumber` and `isRebuilt` (:298, :815, :922). `Class.forName("...cps.CpsThread", false, uberClassLoader)` (listener/BuildLogNotice.java:47), which catches `ReflectiveOperationException`, `RuntimeException` and `LinkageError`. | Every path degrades to "not a replay" or "no log line". There is no linkage. | **no**: workflow-cps is always on the test classpath. Safe by construction (strings only), not by test. |
| pipeline-model-definition (Restart from Stage) | Name match: `RestartDeclarativePipelineCause` and `RestartFlowFactoryAction` (:137-138, :266-267). Reflection: `getOriginRunNumber`, `getOriginRunId`, and a fallback that reads the private field `originRunId` via `setAccessible` (:302, :326-329, :339-347). | Same as workflow-cps. | **no** (same reason) |
| naginator | Name match on `NaginatorCause` (:88). Reflection on `getSourceBuildNumber` (:814). | Not matched. | **no** (safe by construction) |
| build-token-root | Name match on `BuildRootAction` among the Stapler ancestors (:106-107, :661). | Not matched. Core's `/build` token path is handled separately. | **no** (safe by construction) |
| authorize-project | Core API only: `QueueItemAuthenticatorProvider` and `QueueItemAuthenticatorConfiguration` (security/SystemBuildCheck.java:131, ops/BatchControlStrategyMonitor.java:131). | Core types. There is nothing to be absent. | not needed |
| customize-build-now, parameterized-trigger, lockable-resources, throttle-concurrents, jobConfigHistory, job-dsl | Not referenced in `src/main`. Test-only interaction coverage (`PluginInteraction*Test`). | Nothing. | not needed |
| core CLI | `hudson.cli.BuildCommand$CLICause` by name (:110). | - | - |

Plugins without an absence test: **configuration-as-code** and **rebuild**, both compile-time optional. There is also no **all optional plugins absent** boot, meaning only cloudbees-folder, ionicons-api and their dependencies installed. workflow-cps, pipeline-model-definition, naginator and build-token-root are touched only through strings and guarded reflection, so they cannot cause linkage errors. An absence test for them adds little.

### 2b. Version sensitivity (what we rely on upstream)

`access-modifier-checker:1.35:enforce` passed during `package`. That covers every compile-time reference to matrix-auth, role-strategy, configuration-as-code, mailer and rebuild (subclasses included) against the BOM versions. It **does not** cover reflective or name-matched targets. `javap -v` shows the following for those targets on the BOM versions:

| Target | Kind | Public, not `@Restricted` | If renamed upstream |
|---|---|---|---|
| `ReplayCause#getOriginalNumber`, `#isRebuilt` | reflection | yes | The source number becomes UNKNOWN, so the re-run is refused for non-admins (fail closed, S-30-01). `isRebuilt` falls back to "Replay" (record label only). |
| `RestartDeclarativePipelineCause#getOriginRunNumber` | reflection | yes | Falls back to `RestartFlowFactoryAction#getOriginRunId`, then to the private field, then UNKNOWN, which fails closed. This getter was the past incident. |
| `RestartFlowFactoryAction.originRunId` (private field) | `setAccessible` | **private** field | Fallback only. It is harmless while the public getter exists. |
| `NaginatorCause#getSourceBuildNumber` | reflection | yes | The record loses the source build number (`""`). |
| `CpsThread.current()`, `CpsFlowExecution#getOwner`, `FlowExecutionOwner#getExecutable/#getListener` | reflection | yes (the `@Restricted` members of `CpsFlowExecution` are `iota`, `iotaStr`, `getNextScriptName`, `suspendAll`, none of which is used) | The build-log notice line is silently missing (FINE log). |
| `AuthorizationContainer#getAllPermissionEntries`, `PermissionEntry#getType/#getSid` (matrix-auth) | reflection (ops/ConfigureWithoutGrantMonitor.java:389, 418, 451, 471) | yes (default interface methods) | The advisory monitor lists nothing. Enforcement is unaffected. role-strategy has no such method, so the monitor already contributes nothing for it, by design (S-18-01). |
| class names `ReplayCause`, `ReplayFlowFactoryAction`, `RestartDeclarativePipelineCause`, `RestartFlowFactoryAction`, `NaginatorCause`, `RebuildCause`, `BuildRootAction` | string match | - | See finding M-2. |
| `RebuildValidator#isApplicable(Run)` (rebuild) | subclass | yes (public extension point) | Compile-time checked. Behaviour also depends on the URL shape `.../<n>/rebuild` (ApprovalRebuildValidator.java:69-73) and on the plugin's transient action factory calling the validator. That re-entrancy was the past recursion incident; it is now avoided by reading only persisted actions (:57-63). |

Newer upstream releases checked:

- **role-strategy 918** (current, `requiredCore` 2.568.3) against BOM 898: `javap -p` of `RoleBasedAuthorizationStrategy`, its `ConverterImpl`, `Role`, `RoleMap`, `PermissionTemplate`, `PermissionEntry` and `AuthorizationType` is identical, apart from one new private method (`compilePatternOrSendError`). The subclass and the converter stay binary compatible.
- **configuration-as-code 2130** (current) against BOM 2121: `Configurator`, `Attribute`, `ConfigurationContext`, `ConfiguratorException` and `CNode` are identical.
- matrix-auth, mailer, rebuild, cloudbees-folder, workflow-cps, pipeline-model-definition, naginator and build-token-root: the BOM version **is** the current release.

## 3. Required core against each integrated plugin's core floor

Our floor is 2.568.3. For each plugin, the table lists the `Jenkins-Version` of the version we build against and the `requiredCore` of the current release:

| Plugin | BOM version requires | Current release | Current requires |
|---|---|---|---|
| cloudbees-folder | 2.528.1 | 6.1106 (same) | 2.528.1 |
| ionicons-api | 2.504.1 | 94 (same) | 2.504.1 |
| matrix-auth | 2.528.3 | 3.3 (same) | 2.528.3 |
| role-strategy | 2.559 | 918 | **2.568.3** (equals our floor) |
| configuration-as-code | 2.541.1 | 2130 | 2.541.1 |
| mailer | 2.504.3 | 534 (same) | 2.504.3 |
| rebuild | 2.479.1 | 338 (same) | 2.479.1 |
| workflow-cps | 2.528.3 | 4383 (same) | 2.528.3 |
| workflow-job | 2.568.1 | 1602 (same) | 2.568.1 |
| pipeline-model-definition | 2.504.3 | same | 2.504.3 |
| naginator / build-token-root / authorize-project | 2.492.3 / 2.479.3 / 2.479.3 | same | same |
| customize-build-now / jobConfigHistory / lockable-resources / throttle-concurrents / parameterized-trigger | 2.516.3 / 2.541.3 / 2.541.3 / 2.479.3 / 2.479.3 | same | same |

A user on 2.568.3 hits no core conflict. Every floor we impose, and every current release, installs on 2.568.3. role-strategy 918 sits exactly at our floor, so the next role-strategy release may require a newer core. That is not a conflict for us: a user on 2.568.x stays on role-strategy 918, which meets our floor of 898.

## 4. Convergence and bundled jars

- `validate` and `package` run the enforcer rules: `RequireUpperBoundDeps` **passed**, `BannedDependencies` (x2) passed, `BanObsoleteDependencyOverrides` passed, `EnforceBytecodeVersion` passed, `RequireReleaseDeps` passed. The only warning is `RequireJavaVersion` "a Java LTS release is recommended": the local JDK is 26 (`Build-Jdk-Spec: 26`). CI builds on 21 (Jenkinsfile). `dependencyConvergence` is not among the parent POM's rules. `requireUpperBoundDeps` is the Jenkins equivalent, and it passed.
- The "Failed to build parent project" warning comes from a locally cached `maven-hpi-plugin` POM whose origin repository is not declared in this build (`repo.jenkins-ci.org` versus `incrementals`). It is a local-cache artifact, not a project problem.
- **`WEB-INF/lib` holds only `batch-control.jar`** (571,435 bytes, `unzip -l target/batch-control.hpi`). No third-party jar is bundled. `hpi.strictBundledArtifacts=true` (pom.xml:38) makes a future accidental bundle fail the build. Every library comes through plugin dependencies, so nothing can clash with other plugins' copies.
- The mailer pulls in `jakarta-mail-api`/angus-mail transitively, as a plugin dependency, and only when mailer is installed. `mock-javamail` 2.2 is test scope only.

## 5. Plugin compatibility testing and hosting

- The jenkinsci hosting checklist (hosting guide plus the repository-permissions-updater Hosting Checker, mirrored in docs/HOSTING-CHECKLIST.md section A) does **not** require plugin-compat-tester. It checks: groupId and artifactId, a recent parent POM, a supported LTS `jenkins.version`, the licence, `buildPlugin()` in the Jenkinsfile on JDK 21/25, a README, and no unused dependencies. Everything dependency-related in that list is met (BOM used, baseline 2.568.3 on the current LTS line, no unused plugins, strict bundling).
- PCT becomes relevant only if batch-control is later added to `jenkinsci/bom`. The BOM repository then runs PCT with batch-control's tests against every BOM line, which requires tests that pass with the other plugins at their BOM versions. The `PluginInteraction*Test` suite (9 classes) and the absence tests are already PCT-style checks on the BOM line.
- Dependabot (`.github/dependabot.yml`, maven, weekly) bumps the BOM, so upstream renames surface as failing tests in a Dependabot PR. They surface only for users who stay on BOM versions, which leads to finding M-2.

## 6. Findings

### MEDIUM

**M-1. The version floors on optional plugins are recent. An older copy blocks loading. Document.**

- Evidence: the manifest requires matrix-auth 3.3 (released 2026-07-14; 3.2.10 is the previous release), role-strategy 898, configuration-as-code 2121, mailer 534 and rebuild 338, all marked `resolution:=optional`.
- Core behaviour, from the `hudson/PluginWrapper.java` source of jenkins-core 2.568.3, lines 997-1006: an optional dependency that is installed and **older** than the declared version raises `versionDependencyError`, and the plugin fails to load (`failed_to_load_plugin`). A site on matrix-auth 3.2.x that uploads the hpi through Deploy Plugin therefore gets batch-control disabled until it upgrades matrix-auth. The Plugin Manager's Available tab offers the upgrade as a needed dependency, so the normal install path is fine.
- Recommendation: document the optional floors. `Request: README.md` in the Requirements section (README.md:165-169): state that if matrix-auth, role-strategy, configuration-as-code, mailer or rebuild is installed, it must be at least the listed version, or batch-control will not load. Lowering the floors below the BOM (explicit older versions in pom.xml) is possible but is not recommended now: `BanObsoleteDependencyOverrides` and the BOM discourage it, and the compile-time subclasses of matrix-auth and role-strategy would need re-verification.

**M-2. The "replayed under a grant" marking fails open if upstream renames a Pipeline class. Document, and add a canary test.**

- Evidence: `isScriptRerun` (ApprovalQueueDecisionHandler.java:193-206) recognises a Replay, a Pipeline Rebuild or a Restart from Stage only by the four class-name strings at :84-85, :137-138, :186-187 and :266-267.
- Consequence of a rename: an accepted replay by a grant holder without native Item/Configure is no longer marked (`markReplayUnderGrant`, :147). The grant's "changed under a grant" state and the D-58c re-run lock would then be silently lost. The run-control path fails closed instead: a person's Replay still carries a `UserIdCause` and is refused as an unapproved manual run, only under a different record kind. So does the source lookup, where UNKNOWN leads to a refusal.
- Detection: the current tests (QueueBlockTest, AuthorizationEntryGuardTest) would catch a rename, but only when the BOM that contains it is merged. A site running newer workflow-cps or pipeline-model-definition than the BOM we tested would not be protected.
- Recommendation: a small test (`Request: src/test/** (test-author)`) that `Class.forName`s every class-name constant the plugin matches, through the plugin manager's uber class loader, and resolves every reflectively called method. That turns a rename into a named failure on the Dependabot PR. Also add a LIMITATIONS entry (`Request: docs/LIMITATIONS.md (release-manager)`): the re-run classification is tested against the plugin versions of the BOM line in pom.xml.

**M-3. No absence test for configuration-as-code, for rebuild, or for "all optional plugins absent". Document, and add tests later.**

- Evidence: only `OptionalDependencyWithoutMatrixAuthTest`, `OptionalDependencyWithoutRoleStrategyTest` and `EmailNotificationWithoutMailerTest` exist. Both configurators and `ApprovalRebuildValidator` link plugin classes and rely on `@Extension(optional = true)` alone.
- Code review finds no non-optional reference to them. The risk is regression: a future non-optional class that links them would go unnoticed.
- Recommendation: `Request: src/test/** (test-author)`. Add a `RealJenkinsExtension().omitPlugins("configuration-as-code")` boot, an `omitPlugins("rebuild")` boot, and one minimal boot that omits matrix-auth, role-strategy, configuration-as-code, mailer and rebuild together. Each boot checks `/manage` and one gated Build Now.

### LOW

**L-1. README claims "Tested against role-strategy 918", but the unit tests run on 898. Document.**

README.md:283-284. The 918 behaviour was verified in e2e (the image installs the latest release, e2e/plugins.txt), while `mvn verify` uses the BOM's 898. The binary API is identical (section 2b), so there is no functional risk. `Request: README.md`: say "e2e-tested against role-strategy 918; unit-tested against the BOM version".

**L-2. Reads the private field `RestartFlowFactoryAction.originRunId`. Document.**

ApprovalQueueDecisionHandler.java:326-329 and :339-347. This is a fallback behind the public `getOriginRunId()`, and any failure ends in UNKNOWN, which is a refusal. It is acceptable. It should be the first thing removed if pipeline-model-definition ever drops the field.

**L-3. `CpsThread.current()` is public but internal-flavoured Pipeline API. Document.**

BuildLogNotice.java:47-49. On failure only a log line is lost (FINE). No action.

**L-4. The rebuild integration depends on the rebuild plugin's URL (`/<n>/rebuild`) and on its action factory consulting validators. Document.**

ApprovalRebuildValidator.java:69-73. If the URL or the factory contract changes, the queue gate still refuses and records the build. Only the UX (a hidden button) degrades. `PluginInteractionRebuildTest` passed (3 tests).

**L-5. Two test dependencies are not BOM-managed. Document.**

`mock-javamail` 2.2 and `customize-build-now` 53.v64eb_d7d017d2 (pom.xml:71-73, 192-193) are pinned by hand, with comments. Dependabot covers them. They are test scope, so they have no user impact.

**L-6. The monitor finds no role-strategy holders, by design. Document.**

The standing-permission scan uses `getAllPermissionEntries`, which is matrix-auth only (ConfigureWithoutGrantMonitor.java:386-406). This is already stated in-code (S-18-01). It is worth one line in LIMITATIONS if it is not already there.

### Nothing to fix now

- HIGH: none.
- No bundled jars, no convergence failure, no core conflict on 2.568.3.
- No `@Restricted` upstream member is used, either at compile time (access-modifier-checker passed) or reflectively (javap checks above).
