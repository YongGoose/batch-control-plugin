# jenkinsci Hosting Readiness

Empirical readiness report for submitting `batch-control` to the Jenkins project
hosting process. It answers *do we meet the requirements*. For *what to do, in
what order, on the day*, use [`HOSTING-REQUEST.md`](HOSTING-REQUEST.md).

| | |
|---|---|
| Verification date | **2026-09-26** (all URLs below fetched on this date) |
| Last updated | **2026-09-26** — preparation pass, then the G3 README replacement, then `CONTRIBUTING.md` + the README link + `.github/PULL_REQUEST_TEMPLATE.md`, then the JUnit 5 migration (R1/B18/E4), the owner answers to Q1/Q5/Q8 and the README rewrite; see *Applied changes* below |
| Repository under review | `https://github.com/YongGoose/batch-control-plugin` (public, not a fork, default branch `main` — verified via GitHub API on 2026-09-26) |
| Branch inspected | `handoff/phase-4-5-continuation` |
| Requirements checked | **65** |
| PASS | **56** |
| FAIL | **0** |
| WARNING | **1** |
| UNKNOWN | **2** |
| INFO | **2** |
| NOT APPLICABLE | **4** |

> **Count correction (2026-09-26).** The first revision of this header said
> "60 requirements / 36 PASS / 1 INFO". The checklist in section 2 actually has
> 65 rows (A 8, B 23, C 17, D 8, E 9) and two INFO rows (D8, E9), so the header
> totals were wrong. The numbers above are recounted from the table.

> **Count update (G3 pass, 2026-09-26; superseded by the note below).** C10 moved FAIL → PASS when `README.md`
> was replaced with plugin documentation, so PASS 54 → 55 and FAIL 5 → 4. The
> four remaining FAIL rows are all in the CD block (D1, D4, D5, D6) and exist
> only if form field 6 is answered `Yes` — see **G10**.

> **No count change (CONTRIBUTING pass, 2026-09-26).** `CONTRIBUTING.md` now
> exists, which clears §4 recommendation 2 and the relocation half of **Q8**.
> The header totals are unchanged on purpose: `CONTRIBUTING.md` is a
> *recommendation* from the documentation guide, not one of the 65 checklist rows,
> so no row's verdict moves.

> **Count update (owner answers + JUnit 5 migration, 2026-09-26).** Two changes,
> and between them they clear every FAIL.
>
> **Q5 is answered `No`,** so the four CD rows (D1, D4, D5, D6) are not
> requirements for this submission at all. They move FAIL → **N/A** rather than
> PASS, because nothing about them was fixed; the `.mvn/` files and `cd.yaml`
> still do not exist and do not need to. CD can be enabled later by a follow-up
> PR to repository-permissions-updater, at which point these four become live
> again — see **G10**. D3 (the `${revision}${changelist}` warning) stays a
> WARNING for the same reason it always was: it is advice, and it only bites once
> CD is on.
>
> **The test suite is on JUnit 5,** so B18 is no longer "PASS by the bot, red in
> the build" but simply PASS, and E4 moves UNKNOWN → PASS. PASS 55 → 56,
> UNKNOWN 3 → 2, FAIL 4 → 0. See **R1**.
>
> The two remaining UNKNOWN rows are E1 and E2, both of which need the owner and
> neither of which anyone else can close.

> **Verification note.** No Maven build was run *by the author of this document*.
> The E4 result recorded below (195 tests, 0 failures, SpotBugs
> `BugInstance size is 0`, `BUILD SUCCESS`, with `ban-junit4-imports.skip=false`
> in place) was reported by the orchestrating session on 2026-09-26 and is
> recorded here with that attribution, not as an independent verification. It
> must be re-run on the final pre-submission tree in any case — see §6.7 step 6.

### Applied changes (preparation pass, 2026-09-26)

Owner-approved writes to `pom.xml`, `Jenkinsfile` and new files. CD-related
items were deliberately **not** touched — see G10.

| Gap | Change | Where |
|---|---|---|
| G1 | `LICENSE` created — full MIT text, `Copyright (c) 2026 Yongjun Hong` | `LICENSE:1-21` |
| G2 | windows configuration `jdk: 17` → `jdk: 21` | `Jenkinsfile:5` |
| G4 | `<developers>` block deleted | `pom.xml` (was `:27-33`) |
| G5 | `<url>` and all three `<scm>` URLs rewritten to the literal jenkinsci form; `gitHubRepo` property removed | `pom.xml:18`, `:27-32`, properties |
| G6 | five enforcement properties added | `pom.xml:39-43` |
| G7 | `.github/CODEOWNERS` created | `.github/CODEOWNERS:1` |
| G8 | `.github/dependabot.yml` created — `maven` weekly, `github-actions` monthly | `.github/dependabot.yml:1-11` |
| G9 | plugin BOM `7046.v43536164769c` → `7093.v37de7b_4a_8a_4f` | `pom.xml:51` |
| G3 | `README.md` replaced — the Korean development-kit guide is gone, replaced by English plugin documentation (problem statement, the two controls, what run control does and does not stop, requirements, installation, four-step configuration, screen guide, limitations, roadmap, contributing, security reporting, license) | `README.md` |
| G3 follow-up / §4 rec. 2 / Q8 (first half) | `CONTRIBUTING.md` created — the contributor material G3 removed, rewritten in English for contributors rather than transcribed: build/test with expected results, repository layout, document system + identifier glossary, test conventions, `e2e/`, known pitfalls, PR rules, and one context section on the agent orchestration | `CONTRIBUTING.md` |
| §4 rec. 2 (rest) | README *Contributing* section rewritten to link `CONTRIBUTING.md`, keeping only `mvn clean verify` + `mvn hpi:run`; `.github/PULL_REQUEST_TEMPLATE.md` created with the five checks from `CONTRIBUTING.md` §7 plus the issue and contract anchor | `README.md` *Contributing*, `.github/PULL_REQUEST_TEMPLATE.md` |
| R1 / B18 / E4 | `src/test/**` migrated to JUnit 5 in stages, then `ban-junit4-imports.skip` restored to `false`; the enforcer rule now passes and so does the full build | commits `aa2b344`, `abebb3e`, `71ba3f0`, `c8f63a0`; `pom.xml:42` |
| C10 (second pass) | `README.md` cut roughly in half and rewritten in prose after the owner found it too long and mechanical; the exhaustive limitation list moved to a new `docs/LIMITATIONS.md`, and `README.ko.md` rewritten as Korean prose rather than a translation | `README.md`, `README.ko.md`, `docs/LIMITATIONS.md` |

**Not done, by instruction:** G10 (all CD files and the `changelist` value — the
Yes/No decision is made immediately before submission).

---

## 1. Submission process

### 1.1 Where and what to submit

The process is a **GitHub issue in `jenkins-infra/repository-permissions-updater`**,
using the `🏠 Hosting request` issue template. There is no Jira ticket and no
separate hosting repository in the current process.

Steps, as documented:

1. "Review the preparation steps and make sure they're satisfied."
2. "Make sure your plugin follows the plugin naming convention outlined in the
   style guides."
3. "Have a public repository containing the plugin source code on GitHub."
4. "Log in to GitHub and create a new issue in the repository-permissions-updater
   repository" with the hosting-request template.
5. "A member of the Hosting team will review your request within a few days. If
   any changes are requested, please implement them."
6. Once approved, the repository is **forked into `jenkinsci`** and the
   submitter must **delete the original repository** (to break the fork
   relationship).
7. Create a `Jenkinsfile` so the plugin builds on ci.jenkins.io.
8. Request upload/release permissions per the repository-permissions-updater
   README.
9. Categorize and document the plugin on the plugin site.
10. Optionally configure Jira autolink references in GitHub repository settings.

An automated bot ("Jenkins Hosting Checker") comments on the issue with a list
of findings. Severity semantics, quoted from the bot source:

> "It appears you have some issues with your hosting request. Please see the
> list below and correct all issues marked Required. Your hosting request will
> not be approved until these issues are corrected. Issues marked with Warning
> or Info are just recommendations and will not stall the hosting process."

A re-check is triggered "by editing your hosting request or by commenting
`/hosting re-check`". A human reviewer then "will check over things that I am
not able to check (code review, README content, etc)".

Sources:
- <https://www.jenkins.io/doc/developer/publishing/requesting-hosting/> (2026-09-26)
- <https://www.jenkins.io/doc/developer/publishing/preparation/> (2026-09-26)
- <https://github.com/jenkins-infra/repository-permissions-updater/blob/master/src/main/java/io/jenkins/infra/repository_permissions_updater/hosting/HostingChecker.java> (2026-09-26)

### 1.2 Form fields (verbatim from the issue template)

Source: <https://github.com/jenkins-infra/repository-permissions-updater/blob/master/.github/ISSUE_TEMPLATE/1-hosting-request.yml> (fetched 2026-09-26)

| # | Field | Type | Required | Description as written in the template |
|---|---|---|---|---|
| 1 | **Repository URL** | input | yes | "URL of the repository to host in the jenkinsci organization" — placeholder `https://github.com/your-org/your-repo-name` |
| 2 | **New Repository Name** | input | yes | "Name of the repository in the jenkinsci organization on GitHub, plugins should end with `-plugin`, libraries should start with `lib-`. Should be all lowercased." |
| 3 | **Description** | textarea | yes | "Describe what you want hosted here. Explain how it's different from other components that may be considered similar to this one." |
| 4 | **GitHub users to have commit permission** | textarea | yes | placeholder `@user1 / @user2 / @user3` |
| 5 | **Jenkins project users to have release permission** | textarea | yes | "The Jenkins project has it's own identity system, users can sign up at https://accounts.jenkins.io. All users *must* sign in to [Jira](https://issues.jenkins.io) and [Artifactory](https://repo.jenkins-ci.org/). The user(s) listed must NOT be mentioned. The Jenkins identity system account name is handled independently of the GitHub user handle." |
| 6 | **Automated release via GitHub Actions (recommended)** | dropdown (`Yes` / `No`) | yes | "See https://www.jenkins.io/doc/developer/publishing/releasing-cd/" |

Additional field rules enforced by the bot (`HostingFieldVerifier`), on field 2:
"It must match the artifactId (with -plugin added) from your pom.xml." / "It must
end in -plugin if hosting request is for a Jenkins plugin." / "It must be all
lowercase." / "It must NOT contain \"Jenkins\"." / "It must use hyphens ( - )
instead of spaces or camel case."

> **Consequence of field 6.** Answering `Yes` switches on an extra block of
> `REQUIRED` checks (`MavenVerifier.checkAutomaticReleasesSettings` and
> `RequiredFilesVerifier.checkFilesForCD`). Section 2.D below lists them. As of
> today we fail 4 of them, so the answer to field 6 is an owner decision with a
> concrete cost attached.

### 1.3 Automated requirement constants (authoritative values)

Source: <https://github.com/jenkins-infra/repository-permissions-updater/blob/master/src/main/java/io/jenkins/infra/repository_permissions_updater/hosting/Requirements.java> (fetched 2026-09-26)

```java
public static final Version LOWEST_PARENT_POM_VERSION = new Version(6, "2211.v27f680c93c53");
public static final Version PARENT_POM_WITH_JENKINS_VERSION = new Version(2);
public static final Version LOWEST_JENKINS_VERSION = new Version(2, 541, 3);
public static final List<Integer> ALLOWED_JDK_VERSIONS = List.of(21, 25);
```

Other hard limits from `MavenVerifier`: `MAX_LENGTH_OF_ARTIFACT_ID = 37`,
`MAX_LENGTH_OF_GROUP_ID_PLUS_ARTIFACT_ID = 100` (both compared with `>=`, so the
value must be strictly less).

Live registry values checked on 2026-09-26:

| Artifact | Latest released | Our value | Note |
|---|---|---|---|
| `org.jenkins-ci.plugins:plugin` | `6.2236.v12dd4c483242` (`lastUpdated 20260916122448`) | `6.2236.v12dd4c483242` | up to date |
| `io.jenkins.tools.bom:bom-2.568.x` | `7093.v37de7b_4a_8a_4f` (`lastUpdated 20260925171701`) | `7093.v37de7b_4a_8a_4f` | up to date (bumped 2026-09-26; metadata re-fetched the same day and `<latest>` = `<release>` = this value) |

Sources: `https://repo.jenkins-ci.org/artifactory/public/org/jenkins-ci/plugins/plugin/maven-metadata.xml`,
`https://repo.jenkins-ci.org/artifactory/public/io/jenkins/tools/bom/bom-2.568.x/maven-metadata.xml`
(both 2026-09-26). The bot compares the BOM against `<latest>` at check time, so
this is a moving target — refresh it immediately before submitting.

---

## 2. Requirement checklist

Legend: **PASS** / **FAIL** / **WARN** / **UNKNOWN** / **INFO**.
Source column: `RPU` = the Hosting Checker source in
`jenkins-infra/repository-permissions-updater` (fetched 2026-09-26); doc URLs are
listed in section 1 and section 6.

### A. Naming and identity

| # | Requirement | Source | Status | Evidence | Action |
|---|---|---|---|---|---|
| A1 | `<groupId>` must be `io.jenkins.plugins` | RPU `MavenVerifier.checkGroupId` | PASS | `pom.xml:11` | — |
| A2 | `artifactId` all lowercase, hyphen-separated | RPU `MavenVerifier.checkArtifactId`; style guide | PASS | `pom.xml:12` = `batch-control` | — |
| A3 | `artifactId` must not contain "jenkins" | RPU `MavenVerifier.checkArtifactId` | PASS | `pom.xml:12` | — |
| A4 | `artifactId` length < 37 | RPU `MavenVerifier` | PASS | `pom.xml:12`, 13 chars | — |
| A5 | `groupId` + `artifactId` length < 100 | RPU `MavenVerifier` | PASS | 19 + 13 = 32 chars | — |
| A6 | `artifactId` == "New Repository Name" minus `-plugin` | RPU `MavenVerifier.checkArtifactId` | PASS | `batch-control` vs `batch-control-plugin` | — |
| A7 | New repository name ends with `-plugin`, all lowercase, hyphens, no "Jenkins" | RPU `HostingFieldVerifier` | PASS | planned name `batch-control-plugin`; current repo already named this | — |
| A8 | `<name>` present and must not contain "Jenkins" | RPU `MavenVerifier.checkName` | PASS | `pom.xml:16` = `Batch Control` | — |

### B. `pom.xml` metadata and dependency policy

| # | Requirement | Source | Status | Evidence | Action |
|---|---|---|---|---|---|
| B1 | Parent `groupId` = `org.jenkins-ci.plugins`, `artifactId` = `plugin` | RPU `MavenVerifier.checkParentInfoAndJenkinsVersion` | PASS | `pom.xml:5-6` | — |
| B2 | Parent version ≥ `6.2211.v27f680c93c53` | RPU `Requirements.LOWEST_PARENT_POM_VERSION` | PASS | `pom.xml:7` = `6.2236.v12dd4c483242`, also the current latest | Re-confirm latest right before submitting |
| B3 | Property `jenkins.baseline` must be defined | RPU `MavenVerifier.checkProperties` | PASS | `pom.xml:46` = `2.568` | — |
| B4 | `jenkins.version` ≥ `2.541.3` | RPU `Requirements.LOWEST_JENKINS_VERSION` | PASS | `pom.xml:47` = `${jenkins.baseline}.3` → `2.568.3` | — |
| B5 | `<licenses>` block present | RPU `MavenVerifier.checkLicenses` | PASS | `pom.xml:20-25` (MIT) | — |
| B6 | `<developers>` must be **removed** — "This information is fetched from this repository on the update site." | RPU `MavenVerifier.checkDevelopersTag` | PASS | block deleted 2026-09-26; `grep -c developer pom.xml` now matches only `<developerConnection>` | — |
| B7 | `<url>` must equal `https://github.com/jenkinsci/${project.artifactId}-plugin` | RPU `MavenVerifier.checkUrl` | PASS | `pom.xml:18` = `https://github.com/jenkinsci/${project.artifactId}-plugin`, literal jenkinsci form | — |
| B8 | `<scm><connection>` must be HTTPS | RPU `MavenVerifier.checkSoftwareConfigurationManagementField` | PASS | `pom.xml:28` | — |
| B9 | `<scm><developerConnection>` present | same | PASS | `pom.xml:29` | — |
| B10 | `<scm><url>` present | same | PASS | `pom.xml:31` | — |
| B11 | `<scm><tag>` present | same | PASS | `pom.xml:30` = `${scmTag}` | — |
| B12 | SCM URLs should point at the jenkinsci repository — the bot's own sample is `scm:git:https://github.com/jenkinsci/${project.artifactId}-plugin.git` | RPU `MavenVerifier` SCM message text (sample only; presence/scheme is what is enforced) | PASS | `pom.xml:28-31` now use the jenkinsci form verbatim; the `gitHubRepo` property is gone (`grep -rn gitHubRepo` over the repo: 0 hits) | — |
| B13 | `<repository>`/`<pluginRepository>` for repo.jenkins-ci.org must use `https://` | RPU `MavenVerifier.checkRepositories` / `checkPluginRepositories` | PASS | `pom.xml:110-122` | — |
| B14 | Properties `java.level`, `maven.compiler.source/target/release` must be absent | RPU `MavenVerifier.checkProperties` | PASS | absent from `pom.xml:34-44` | — |
| B15 | Property `hpi.strictBundledArtifacts` = `true` | RPU `MavenVerifier.checkProperties` | PASS (bot) / see **R1** | `pom.xml:39` | Build verification required |
| B16 | Property `ban-commons-lang-2.skip` = `false` | same | PASS | `pom.xml:40`; static scan found **0** commons-lang 2 references (see **R1**) | — |
| B17 | Property `ban-deprecated-stapler.skip` = `false` | same | PASS | `pom.xml:41`; static scan found **0** v1 Stapler references (see **R1**) | — |
| B18 | Property `ban-junit4-imports.skip` = `false` | same | **PASS** | `pom.xml:42` = `false`, and `src/test` holds **0** references to non-Jupiter `org.junit.*`. All 35 test classes import `org.junit.jupiter` — 34 tracked plus `SecretParameterMaskingTest`, which is untracked on this branch and so does not show in a diff against `HEAD`; it is Jupiter-based too. The enforcer rule `check-junit-imports` runs in `generate-test-sources` and passes (see **R1**) | — |
| B19 | Property `banObsoleteDependencyOverrides.skip` = `false` | same | PASS | `pom.xml:43`; no dependency declares a version, so there is nothing to override (see **R1**) | — |
| B20 | Plugin BOM imported and its `artifactId` in sync with the baseline (`bom-<baseline>.x`) | RPU `MavenVerifier.checkDependencyManagement` | PASS | `pom.xml:50` = `bom-${jenkins.baseline}.x` → `bom-2.568.x` | — |
| B21 | BOM version must equal the latest released BOM for that line | same | PASS | `pom.xml:51` = `7093.v37de7b_4a_8a_4f`, which was `<latest>` and `<release>` in the live `maven-metadata.xml` on 2026-09-26 | Re-check on submission day — moving target |
| B22 | No explicit `<version>` on dependencies the BOM manages | same | PASS | `pom.xml:58-108` — no dependency declares a version | — |
| B23 | No banned third-party dependency in `compile` scope (must use the API plugin instead) | RPU `MavenVerifier.checkDependencies` + `banned-dependencies.lst` | PASS | compile deps are only `structs` (`pom.xml:59-62`) and `cloudbees-folder` (`pom.xml:63-66`); neither is in the banned list | — |

### C. Required repository files

| # | Requirement | Source | Status | Evidence | Action |
|---|---|---|---|---|---|
| C1 | `Jenkinsfile` exists, contains exactly one `buildPlugin(...)` call, parameterized, with a `configurations` list where every entry has `platform` and `jdk` | RPU `RequiredFilesVerifier.validateJenkinsFile` | PASS (structure) | `Jenkinsfile:1-7` | — |
| C2 | Every `jdk` value must be in `[21, 25]` | RPU `Requirements.ALLOWED_JDK_VERSIONS` | PASS | `Jenkinsfile:4-5` — both configurations now `jdk: 21` | — |
| C3 | `.gitignore` must exclude `target` | RPU `RequiredFilesVerifier.checkGitignore` | PASS | `.gitignore:1` = `target/` | — |
| C4 | `.gitignore` must exclude `work` | same | PASS | `.gitignore:2` = `work/` | — |
| C5 | `.github/workflows/jenkins-security-scan.yml` (or `.yaml`) must exist | RPU `RequiredFilesVerifier.checkSecurityScan` | PASS | `.github/workflows/jenkins-security-scan.yml:1-22`, pinned to `jenkins-infra/jenkins-security-scan@da7438f…` (v2) | — |
| C6 | `.github/CODEOWNERS` must exist and contain the exact line `* @jenkinsci/<new-repo-name>-developers` | RPU `RequiredFilesVerifier.checkCodeOwners` | PASS | `.github/CODEOWNERS:1` = `* @jenkinsci/batch-control-plugin-developers` | — |
| C7 | A dependency-update bot config must exist: one of `.github/dependabot.yml(.yaml)`, `renovate.json`, `.github/renovate.json`, `.github/workflows/updatecli.yml(.yaml)` | RPU `RequiredFilesVerifier.checkDependencyBot` | PASS | `.github/dependabot.yml:1-11` — `maven` weekly + `github-actions` monthly | — |
| C8 | A license file must exist in the repository and be detectable by GitHub | RPU `GitHubVerifier.checkLicense` | PASS (pending push) | `LICENSE:1-21`, full MIT text, `Copyright (c) 2026 Yongjun Hong`, matching the MIT declaration at `pom.xml:20-25` | GitHub's `license` field only populates after the commit is pushed — re-check the API response before submitting |
| C9 | A README must exist and be detectable by GitHub | RPU `GitHubVerifier.checkReadme` | PASS | `README.md` exists | — |
| C10 | README must be usable plugin documentation — reviewed by a human, not the bot ("README content") | Hosting Checker message text; <https://www.jenkins.io/doc/developer/publishing/documentation/> | **PASS** (content replaced, then rewritten for length and tone, 2026-09-26; the verdict itself stays a human judgement) | `README.md` is English plugin documentation: what problem it solves, the two controls, what run control does and does not stop, requirements, installation, configuration in four steps (including the `Batch Control (wrapping)` strategy step that `docs/reports/e2e-01.md:423-431` found undocumented), a screen guide, the limitations that change a decision, roadmap, comparison, contributing, security reporting, license. The full limitation list lives in `docs/LIMITATIONS.md`, and `README.ko.md` is a Korean companion with the English kept canonical. Length was set against real jenkinsci READMEs — see **G3** | none known |
| C11 | `src/main/resources/index.jelly` should describe the plugin | `docs/HOSTING-CHECKLIST.md:17`; archetype convention (no automated check found in RPU — **source not confirmed as a bot requirement**) | PASS | `src/main/resources/index.jelly:1-8`, English, `escape-by-default='true'` | — |
| C12 | No inline `<style>` in any `src/main/resources/**.jelly` | RPU `JellyVerifier` (`INLINE_STYLE`, REQUIRED) | PASS | grep over all 17 `.jelly` files: no `<style` | — |
| C13 | No inline `<script>` JavaScript in Jelly | RPU `JellyVerifier` (`INLINE_SCRIPT`, REQUIRED) | PASS | no `<script` in any `.jelly` | — |
| C14 | No inline event attributes (`onclick` etc.) in Jelly | RPU `JellyVerifier` (WARNING / REQUIRED for taglib `onclick`) | PASS | no `onclick`/`onchange`/`onkeyup` in any `.jelly` | — |
| C15 | No legacy `checkUrl` without `checkDependsOn` | RPU `JellyVerifier` (`LEGACY_CHECK_URL`, REQUIRED) | PASS | no `checkUrl` in any `.jelly` | — |
| C16 | `target/` and `work/` must never appear in the commit history | RPU `GitHubVerifier.checkUnwantedFiles` | PASS | `git log --all --name-only` across all branches: zero paths under `target/` or `work/` | — |
| C17 | Repository must be public, must not be a fork of a jenkinsci repository, and must have no existing forks inside jenkinsci | RPU `GitHubVerifier.checkForkedFromJenkinsCi` / `checkForkedIntoJenkinsCi`; preparation doc | PASS | GitHub API 2026-09-26: `"private": false`, `"fork": false`, `"visibility": "public"`; forks list empty | — |

### D. Continuous delivery block — applies only if form field 6 is answered `Yes`

| # | Requirement | Source | Status | Evidence | Action |
|---|---|---|---|---|---|
> **Q5 is answered `No` (owner, 2026-09-26),** so this whole block is out of
> scope for the first submission. The four rows that were FAIL are marked
> **N/A**: nothing was fixed, they simply are not requirements while field 6 is
> `No`. If CD is enabled later by a follow-up PR to
> repository-permissions-updater, every row here becomes live again and D3's
> advice starts to matter.

| D1 | Property `changelist` must be exactly `999999-SNAPSHOT` | RPU `MavenVerifier.checkAutomaticReleasesSettings`; <https://www.jenkins.io/doc/developer/publishing/releasing-cd/> | **N/A** (field 6 = `No`) | `pom.xml:36` = `-SNAPSHOT`, unchanged | Set to `999999-SNAPSHOT` if CD is ever enabled |
| D2 | `<version>` must contain `${changelist}` | same | PASS | `pom.xml:13` = `${revision}${changelist}` | — |
| D3 | `${revision}${changelist}` as the version is discouraged | RPU, severity WARNING | **WARN** (advice only, and inert while field 6 = `No`) | `pom.xml:13` | Prefer `${changelist}` (fully automated) or `${revision}.${changelist}` per the CD doc, if CD is ever enabled |
| D4 | `.mvn/extensions.xml` must exist | RPU `RequiredFilesVerifier.checkFilesForCD` | **N/A** (field 6 = `No`) | no `.mvn/` directory | Copy from `jenkinsci/archetypes/common-files/.mvn/extensions.xml` if CD is ever enabled |
| D5 | `.mvn/maven.config` must exist and contain the line `-Dchangelist.format=%d.v%s` | same | **N/A** (field 6 = `No`) | no `.mvn/` directory | Copy the archetype file if CD is ever enabled; the CD doc also lists `-Pmight-produce-incrementals` |
| D6 | `.github/workflows/cd.yaml` (or `.yml`) must exist | same | **N/A** (field 6 = `No`) | `.github/workflows/` contains only the security scan | `curl … https://raw.githubusercontent.com/jenkinsci/.github/master/workflow-templates/cd.yaml` if CD is ever enabled |
| D7 | `.github/release-drafter.y*ml` and `.github/workflows/release-drafter.y*ml` must **not** exist | same | PASS | neither exists | — |
| D8 | After hosting, a PR to repository-permissions-updater must add `cd: enabled: true` to `permissions/plugin-batch-control.yml`, then `MAVEN_TOKEN` / `MAVEN_USERNAME` appear as repository secrets | <https://www.jenkins.io/doc/developer/publishing/releasing-cd/> | INFO (post-approval) | n/a | Part of step E in `docs/HOSTING-CHECKLIST.md` |

### E. Human review, build health, and code hygiene

| # | Requirement | Source | Status | Evidence | Action |
|---|---|---|---|---|---|
| E1 | Description must explain "how it's different from other components that may be considered similar to this one" | Issue template field 3 | **UNKNOWN** | Material exists (`docs/DECISIONS.md:7` D-01, `:9` D-02) but no submission text has been reviewed by the owner | Use the section 6 draft, owner to confirm |
| E2 | GitHub users to have commit permission | Issue template field 4 | **UNKNOWN** | Owner data | Owner supplies |
| E3 | Jenkins project (accounts.jenkins.io) users with release permission must have signed into Jira **and** Artifactory | Issue template field 5; RPU `JenkinsProjectUserVerifier` | PASS (owner-confirmed 2026-09-26) | Owner states the accounts.jenkins.io account is created and needs no further verification | The literal username string for field 5 is still owner-supplied when the form is filled |
| E4 | `mvn clean verify` green, SpotBugs clean | `docs/HOSTING-CHECKLIST.md:37,43`; human code review | **PASS** (reported by the orchestrating session 2026-09-26, not verified by this document's author) | Full `mvn clean verify` with all five enforcement properties on: **195 tests, 0 failures**, SpotBugs `BugInstance size is 0`, `BUILD SUCCESS`. The test count is identical class by class to the pre-migration suite, with 0 `@Disabled` and 0 deletions | Re-run on the final pre-submission tree (§6.7 step 6) |
| E5 | No Korean strings in code or resources | `CLAUDE.md:45`; `docs/HOSTING-CHECKLIST.md:45` | PASS | Unicode Hangul scan over `src/`: 0 matching files | — |
| E6 | No hardcoded credentials | `docs/HOSTING-CHECKLIST.md:28`; Jenkins security docs | PASS | grep for `password =`/`secret =`/`apiKey`/`token =` string literals over `src/main/java`: 0 hits | — |
| E7 | No unexpected outbound network calls | Jenkins security review practice | PASS | grep over `src/main/java` for `HttpURLConnection`, `HttpClient`, `openStream`, `okhttp`, `Socket(`, `java.net.URL`: the only match is `import java.net.URLEncoder` in `src/main/java/io/jenkins/plugins/batchcontrol/ui/FilterParser.java:6`, which is string encoding, not I/O | — |
| E8 | No bundled third-party code or binaries in the plugin | <https://www.jenkins.io/doc/developer/plugin-development/dependencies-and-class-loading/#bundling-third-party-libraries> (referenced by RPU `hpi.strictBundledArtifacts` message) | PASS | `src/` contains only `.java`, `.jelly`, `.properties`, `.html`; no jars, no minified JS, no vendored sources. (The `e2e/screenshots/*.jpg` images are test evidence outside `src/`.) | — |
| E9 | Maintainers must handle security reports through the private SECURITY Jira project, keep fixes embargoed until release, and coordinate release timing with the security team | <https://www.jenkins.io/security/for-maintainers/> | INFO (ongoing obligation) | n/a | Owner acknowledges; identity comes from the release-permission metadata in repository-permissions-updater |

---

## 3. Blocking gaps

Ordered by how hard they block. G1–G8 are `REQUIRED` bot findings: the hosting
request cannot be approved while any of them stands.

**Status after the 2026-09-26 preparation pass:**

| Gap | Status |
|---|---|
| G1 license file | **RESOLVED** |
| G2 Jenkinsfile JDK | **RESOLVED** |
| G3 README | **RESOLVED** — English plugin documentation, later rewritten for length and tone, with `docs/LIMITATIONS.md` and `README.ko.md` alongside it |
| G4 `<developers>` | **RESOLVED** |
| G5 `<url>` / SCM | **RESOLVED** |
| G6 five POM properties | **RESOLVED**; it opened a build blocker (JUnit 4 imports) which is itself now cleared — see **R1** |
| G7 CODEOWNERS | **RESOLVED** |
| G8 dependency bot | **RESOLVED** |
| G9 BOM version | **RESOLVED** as of 2026-09-26; re-check on submission day |
| G10 CD block | **NOT APPLICABLE** — Q5 answered `No`; nothing applied, nothing needed |
| G11 build health | **RESOLVED** — `mvn clean verify` green with all five properties on; re-run on the final tree |

Nothing in this section blocks the request any more. What is left is owner input,
not engineering: **E1** (the Description wording) and **E2** (the GitHub accounts
for commit permission).

### G1 — No license file in the repository *(REQUIRED, C8)* — RESOLVED

The bot calls `repo.getLicense()`; GitHub currently reports `"license": null`.
The Jenkins project also states plainly that the license must be declared "both
in the pom.xml file, as well as a LICENSE file in your repository", and
recommends MIT.

- **What:** create `LICENSE` at the repository root with the **full MIT text**
  (not a reference), copyright year `2026`, holder per **Q2**.
- **Where:** `/LICENSE`.
- **Why it is more than paperwork:** `pom.xml:20-25` already claims MIT, so
  today the repository advertises a license it does not carry.
- **Done 2026-09-26.** `LICENSE:1-21` carries the full MIT text with
  `Copyright (c) 2026 Yongjun Hong` (personal, owner-confirmed — **Q2** closed).
  The first line reads `MIT License`, which is what GitHub's license detection
  keys on. One residual: the API's `license` field stays `null` until the commit
  is pushed, so C8 only truly clears after the push.

### G2 — Jenkinsfile uses a disallowed JDK *(REQUIRED, C2)* — RESOLVED

`Requirements.ALLOWED_JDK_VERSIONS` is `List.of(21, 25)`. `Jenkinsfile:5`
requests `jdk: 17`, which produces:
"Invalid version `17` for `jdk`. `jdk` must be one of [21, 25] in the
`buildPlugin` call in the Jenkinsfile."

- **What:** change the windows configuration from `jdk: 17` to `jdk: 21`, so both
  rows read 21 (optionally add a `jdk: 25` row).
- **Where:** `Jenkinsfile:5`.
- **Done 2026-09-26.** `Jenkinsfile:5` now reads `[platform: 'windows', jdk: 21]`;
  `Jenkinsfile:4` was already 21. No `jdk: 25` row was added (recommendation 7
  stays open).

### G3 — README is the development-kit document, not plugin documentation *(human review, C10)* — RESOLVED

`README.md:1-46` is titled "batch-control-plugin 개발 키트" and documents the
Claude Code agent kit: which files the kit contains, how to copy it into an empty
directory, and a table of the nine sub-agents and their write scopes. It is in
Korean. It contains no description of what the plugin does for a user, no
installation section, and no configuration procedure. The e2e run recorded the
same defect independently — `docs/reports/e2e-01.md:418-421`: "the instruction to
'follow README and verify the document is right' could not be executed — there is
nothing to follow."

After hosting, this file becomes the plugin's landing page on plugins.jenkins.io
("a landing page for the Plugin site"), so a human reviewer will reject it.

- **What (not done in this task — README replacement is a separate work item):**
  replace `README.md` with English user documentation containing, at minimum:
  1. One-sentence summary, then *Why* (Jenkins used as a batch execution
     manager; approval and audit requirements).
  2. **Features** in user language, staying inside `docs/SPEC.md` section 1 MVP
     scope (items 1–12); SPEC items 13–15 go under a separate "Roadmap" heading.
  3. **Installation**, including the dependency reality found in e2e: the `.hpi`
     manifest only requires `structs` and `cloudbees-folder`, but the five
     permissions cannot be assigned without a matrix/role-style authorization
     strategy (`docs/reports/e2e-01.md`, documentation defect 3).
  4. **Configuration** — the global switches are off by default; and the step
     that is currently documented nowhere user-visible: JIT change control does
     nothing unless the administrator selects the plugin's wrapping
     authorization strategy with the real strategy as delegate
     (`docs/reports/e2e-01.md`, documentation defect 2). Plus the permission
     table, approver registration.
  5. **Usage** — requester and approver flows; screenshots are available under
     `e2e/screenshots/`.
  6. **Comparison with existing plugins** (see the G-note in section 6.3).
  7. **Known limitations**, carrying forward the items the review agents asked
     for: S-08 (unclassified queue causes pass the approval gate by default) and
     S-09 (null-delegate admin lockout and its `config.xml` recovery path) —
     `docs/reports/security-02.md:395-397`; `ViewHistory` is not scoped per P-09
     — `docs/reports/security-02.md:71`; secret-masking scope per D-19 and its
     detection limit — `docs/reports/spec-review-S4.md:46`; folder-rename
     children produce MOVE records — `docs/reports/spec-review-S3.md:71`;
     approver accounts must be 1:1 with real people, shared accounts forbidden —
     `docs/reports/red-team-01.md:107`; plus `docs/ARCHITECTURE.md` section 7.
  8. **Contributing**, **License**.
- **Where:** `README.md`. The development-kit content should move somewhere else
  (for example `docs/DEV-KIT.md`) rather than be deleted, since it documents the
  working method.

**Done 2026-09-26.** `README.md` is now 576 lines of English plugin
documentation. What it contains, and where each part is sourced from:

| Section | Source |
|---|---|
| The problem; the two controls (run control vs. change control) | `docs/SPEC.md` §2 items 5–9, `docs/DECISIONS.md` D-01/D-03/D-06 |
| **What is blocked and what is not** — a path-by-path table (UI / REST / CLI / Replay blocked; timer / upstream / SCM / unclassified pass; approval bound to one request and consumed once; queue-entry-only blocking) | `docs/SPEC.md` §2 item 6 acceptance criteria, D-23, D-25, D-27, D-30 |
| Features (MVP items 1–12 only; 13–15 moved to *Roadmap*) | `docs/SPEC.md` §2 |
| Requirements, Installation | `pom.xml:37-38` (baseline 2.568 / `jenkins.version` 2.568.3), `pom.xml:64-71` (`structs`, `cloudbees-folder`), `Jenkinsfile` (JDK 21), `e2e/plugins.txt` and `e2e/Dockerfile` |
| Configuration — four steps, the second being **select `Batch Control (wrapping)`** with the real strategy as delegate | `docs/ARCHITECTURE.md` §4, `e2e/init.groovy.d/00-security.groovy`, the defect at `docs/reports/e2e-01.md:423-431`; field labels read from the Jelly forms and `Messages.properties`, defaults from `docs/SPEC.md` §5 |
| Screen guide | section display names in `src/main/java/.../action/*.java`, CSV names in `action/HistorySection.java:146-203` |
| Known limitations — 29 numbered items in 6 groups | `docs/ARCHITECTURE.md` §7, D-19, D-31/32/33, `docs/TEST-MATRIX.md` notes 6 and 12, `docs/reports/security-01.md`, `security-02.md`, `red-team-01.md`, `spec-review-S3.md`, `spec-review-S4.md`, `docs/reports/e2e-02.md:403-440` |
| Security reporting | <https://www.jenkins.io/security/reporting/> (fetched 2026-09-26): SECURITY Jira project, `specific-plugin` component, plugin named in the summary, `jenkinsci-cert@googlegroups.com` as the fallback |

Deliberately **not** in the README, so that no unverifiable claim reaches the
landing page:

- **No performance figures.** `docs/SPEC.md` §6 states "7-day dashboard query
  under 2 seconds at 5,000 runs/day (local)", but note 7 of
  `docs/TEST-MATRIX.md` excludes it from the regression matrix and no measurement
  exists. **Not measured — omitted.**
- **No supported-version range.** Only the compiled baseline (2.568.3 or newer)
  is stated. Nothing was tested against an older or newer core.
- **No claims about the maintenance state of other plugins.** The comparison
  section keeps only structural facts (the `input` step runs inside a started
  build; audit plugins have no request/approver/expiry concept; matrix and role
  strategies are static). The "unmaintained for about thirteen years" figure in
  §6.3 of this document is an internal note and was kept out of the README.
- **No screenshots.** `e2e/screenshots/` is test evidence, not documentation
  assets.
- **Nothing about the development kit, agents or orchestration** — contributor
  material, not user material. The README's *Contributing* section points only at
  `docs/SPEC.md`, `docs/DECISIONS.md`, `docs/ARCHITECTURE.md` and
  `e2e/README.md`.

**Follow-up — RESOLVED 2026-09-26 as `CONTRIBUTING.md`.** The contributor text
that used to be `README.md:1-46` was removed from `README.md` by the G3 pass and
survived only in git history (`git show 64725de^:README.md`). Option 1 below was
taken: **`CONTRIBUTING.md` now exists at the repository root**, which also clears
§4 recommendation 2.

1. **`CONTRIBUTING.md`** at the repository root, since `docs/HOSTING-READINESS.md`
   §4 recommendation 2 already wants that file to exist and the documentation
   guide lists it as recommended. The kit content — how the phases run, which
   agent owns which path, what a human must do at each gate — *is* the
   contribution process for this repository. The README's *Contributing* section
   would then link to it. **← chosen.**
2. ~~**`docs/DEV-KIT.md`**, the destination already named in Q8, if the owner would
   rather keep `CONTRIBUTING.md` short and conventional (build, test, PR
   expectations) and leave the multi-agent method as a separate document.~~
   Not taken; no separate dev-kit document exists.

What `CONTRIBUTING.md` contains, and how it differs from the old README text: it
was **rewritten for contributors, not transcribed**, and translated to English.
Sections: build and test (JDK 21 / Maven 3.9.16, the four commands, and what a
healthy run looks like — about 195 tests, 0 failures, `BugInstance size is 0`,
with the count flagged as moving while the JUnit 5 migration is in flight),
repository layout, the document system plus a glossary of the identifier prefixes
(`D-nn`, `P-nn`, `T-05-02`, `T-CFG`, `T-SEC`, `T-RT`, `T-OS`, `T-UI`, `T-E2E`,
`RT-nn`, `S-nn`, S1..S4, "falsifiability guard"), the test conventions (matrix row
first, derive from SPEC not from `src/main`, failing test first, never loosen an
assertion, always add a false-positive guard — citing note 42 and the two rows
that passed while measuring nothing), the `e2e/` environment and `e2e/scripts/`,
the pitfalls (§6 — now the canonical list, with `docs/TEST-MATRIX.md` notes 41/42
behind two of them; it absorbed what used to be `docs/HANDOFF.md` §6, which is why
that file could be deleted on 2026-09-26), PR
conventions and the gate, and finally one short section placing the agent
orchestration as context rather than as a requirement for contributors — where
`.claude/` is mentioned in a single line.

Deliberately **not** carried over from the old README, because it is stale rather
than merely verbose: the "development kit — copy this into an empty directory and
run `claude`" framing (the repository is a plugin now, not a starter kit), the
"paste the Phase 0 prompt" bootstrap, the per-phase "what a human must do" list
(Phases 1–4 are closed), the `phase-N-*` branch instructions (those branches are
history), and the per-agent write-scope table (`CLAUDE.md` is authoritative and
the README copy was already out of date). The document index was not copied
either — it omitted `HOSTING-READINESS.md` and `docs/reports/` and still called
the test matrix an "initial seed".

**Still open: the second half of Q8** — whether `.claude/` ships in the hosted
repository at all. `CONTRIBUTING.md` assumes it does (it points at the directory
for context); if the owner decides to strip it, that one paragraph needs a small
edit. The README link is **done (2026-09-26, owner-approved follow-up)**: `README.md`'s
*Contributing* section now points at `CONTRIBUTING.md` as the guide and keeps only
`mvn clean verify` and `mvn hpi:run` — the two commands someone who has just
cloned the repository runs to see whether it builds. Everything else that used to
be inline there (single-class runs, expected figures, environment, `e2e/`, PR
expectations) is in `CONTRIBUTING.md` and reached by link, so the two files cannot
drift apart. `.github/PULL_REQUEST_TEMPLATE.md` was added in the same pass
(§4 recommendation 2).

### G4 — `<developers>` must be removed from `pom.xml` *(REQUIRED, B6)* — RESOLVED

Message: "Please remove the `developers` tag from your pom.xml. This information
is fetched from this repository on the update site."

- **What:** delete `pom.xml:27-33` entirely. Maintainer identity comes from the
  release permissions in repository-permissions-updater, not the POM.
- **Done 2026-09-26.** Block deleted. The maintainer name and address
  (`Yongjun Hong` / `dev.yongjunh@gmail.com`) now live only in the hosting
  request and in `LICENSE`, which is where the process expects them.

### G5 — `<url>` and the SCM URLs point at the personal repository *(REQUIRED, B7; also B12)* — RESOLVED

`MavenVerifier.checkUrl` interpolates `<url>` and requires it to equal exactly
`https://github.com/jenkinsci/<artifactId>-plugin`. Ours resolves to
`https://github.com/YongGoose/batch-control-plugin` because of the `gitHubRepo`
property at `pom.xml:45`.

- **What:** drop the `gitHubRepo` property and write the jenkinsci form
  literally, so that:
  - `<url>` = `https://github.com/jenkinsci/${project.artifactId}-plugin`
  - `<scm><connection>` = `scm:git:https://github.com/jenkinsci/${project.artifactId}-plugin.git`
  - `<scm><developerConnection>` = `scm:git:https://github.com/jenkinsci/${project.artifactId}-plugin.git`
  - `<scm><url>` = `https://github.com/jenkinsci/${project.artifactId}-plugin`
  - `<scm><tag>` = `${scmTag}` (already correct, `pom.xml:38`)
- **Where:** `pom.xml:18`, `:35-40`, `:45`.
- **Note:** the URLs will be wrong for the *current* repository until the fork
  into jenkinsci happens. That is expected — the bot checks the destination
  form, not the origin.
- **Done 2026-09-26.** All four URLs use the literal
  `jenkinsci/${project.artifactId}-plugin` form (`pom.xml:18`, `:28`, `:29`,
  `:31`); `<tag>` is unchanged at `${scmTag}` (`pom.xml:30`). The `gitHubRepo`
  property was deleted and `grep -rn gitHubRepo` over the whole repository now
  returns nothing, so no other file depended on it.

### G6 — Five required POM properties are missing *(REQUIRED, B15–B19)* — RESOLVED (and see R1)

`MavenVerifier.checkProperties` requires all five, each with an exact value:

```xml
<hpi.strictBundledArtifacts>true</hpi.strictBundledArtifacts>
<ban-commons-lang-2.skip>false</ban-commons-lang-2.skip>
<ban-deprecated-stapler.skip>false</ban-deprecated-stapler.skip>
<ban-junit4-imports.skip>false</ban-junit4-imports.skip>
<banObsoleteDependencyOverrides.skip>false</banObsoleteDependencyOverrides.skip>
```

- **Where:** the `<properties>` block, `pom.xml:42-48`.
- **Caution:** these are real enforcement switches, not cosmetics. Turning the
  three `ban-*` checks on can fail the build if the code uses deprecated Stapler
  or `javax.servlet` classes, commons-lang 2, or JUnit 4 imports. This must be
  verified with a build — **build verification required** — and any failure is
  a code change owned by core-dev / test-author, not by this document.
- **Done 2026-09-26.** All five are present with the exact required values at
  `pom.xml:39-43`. The caution above turned out to be justified for exactly one
  of the three `ban-*` switches, `ban-junit4-imports`, which blocked the build
  until the test suite was migrated to JUnit 5; that is now done and all five
  switches pass. See **R1**.

### G7 — `.github/CODEOWNERS` is missing *(REQUIRED, C6)* — RESOLVED

- **What:** create `.github/CODEOWNERS` containing the exact line
  `* @jenkinsci/batch-control-plugin-developers`.
- **Note:** that GitHub team does not exist yet — it is created when the
  repository is forked into jenkinsci. The bot only string-matches the line, so
  adding it before approval is correct.
- **Done 2026-09-26.** `.github/CODEOWNERS:1`, single line, no trailing comment.
  Until the fork happens GitHub will show the team as unrecognised in its
  CODEOWNERS linter — expected, and not a hosting finding.

### G8 — No dependency-update bot configuration *(REQUIRED, C7)* — RESOLVED

- **What:** add one of `.github/dependabot.yml`, `.github/dependabot.yaml`,
  `renovate.json`, `.github/renovate.json`,
  `.github/workflows/updatecli.yml(.yaml)`. The archetype ships
  `.github/renovate.json`. If CD is enabled, the CD documentation additionally
  asks for a `github-actions` ecosystem entry with a monthly schedule in
  `.github/dependabot.yml`.
- **Done 2026-09-26.** `.github/dependabot.yml:1-11`, with both ecosystems:
  - `maven`, `interval: weekly`, `open-pull-requests-limit: 5` — weekly is the
    usual cadence for Jenkins plugins that use dependabot rather than renovate,
    and matches how often the plugin BOM releases (the B21 check compares
    against `<latest>` at review time, so a slower cadence would let that row go
    red on its own).
  - `github-actions`, `interval: monthly` — this is the cadence the Jenkins CD
    documentation names for the `github-actions` ecosystem, so choosing it now
    means the file needs no edit if Q5 is later answered `Yes`.

  Dependabot only starts raising PRs once the file is on the default branch, so
  nothing happens until after the push and, for the jenkinsci copy, after the
  fork.

### G9 — Plugin BOM is behind the latest release *(REQUIRED, B21)* — RESOLVED

`pom.xml:55` pins `7046.v43536164769c`; the latest `bom-2.568.x` on 2026-09-26 is
`7093.v37de7b_4a_8a_4f`.

- **What:** bump, then re-check on the submission day — the bot always compares
  against `<latest>` in the live `maven-metadata.xml`, so this row can go red
  again without any change on our side.
- **Caution:** a BOM bump changes resolved dependency versions — **build
  verification required**.
- **Done 2026-09-26.** `pom.xml:51` = `7093.v37de7b_4a_8a_4f`. The version was
  re-verified at apply time, not copied from the earlier draft: a fresh fetch of
  `https://repo.jenkins-ci.org/releases/io/jenkins/tools/bom/bom-2.568.x/maven-metadata.xml`
  reported `<latest>` = `<release>` = `7093.v37de7b_4a_8a_4f`,
  `lastUpdated 20260925171701`, and it is the last entry in `<versions>`. Picking
  `<release>` (not merely `<latest>`) is deliberate — the bot compares against a
  released BOM, and for this line the two agree.
- **Still outstanding:** the bump changes resolved versions for every managed
  dependency, so it is one of the two reasons E4 must be re-run.

### G10 — CD block *(was REQUIRED, D1/D4/D5/D6)* — NOT APPLICABLE

**Q5 is answered `No` (owner, 2026-09-26).** Field 6 will be `No`, so none of
this block is a requirement for the first review and the four rows are marked N/A
rather than PASS: nothing was fixed. `changelist` is still `-SNAPSHOT`
(`pom.xml:36`), and `.mvn/extensions.xml`, `.mvn/maven.config` and
`.github/workflows/cd.yaml` still do not exist.

This is reversible and cheap to revisit. CD is enabled after hosting by a
follow-up PR to repository-permissions-updater adding `cd: enabled: true` (D8),
and at that point all four files have to be created and D3's advice on the
version expression starts to matter. The one CD-adjacent choice already made is
the monthly `github-actions` dependabot entry from G8, which is harmless either
way.

### G11 — Build health *(E4)* — RESOLVED

The concern was that G6 (five enforcement properties) and G9 (the BOM bump) were
both applied without a build, so nobody knew whether the tree compiled. The
static scan in **R1** then showed that one of the properties definitely broke it.

**Resolved 2026-09-26.** The JUnit 5 migration cleared the enforcer rule and the
orchestrating session reports a full `mvn clean verify` at 195 tests, 0 failures,
SpotBugs `BugInstance size is 0`, `BUILD SUCCESS`, with all five properties on and
the new BOM in place. That covers both original reasons to re-run.

It still has to be run once more on the final pre-submission tree (§6.7 step 6),
because the hosting team reads the ci.jenkins.io build and because the BOM and
parent POM versions are re-checked on the submission day (§6.7 step 4).

---

## 3a. R1 — Build-breakage register for the five enforcement properties

This register began as a static scan over `src/`, done because no Maven build
could be run at the time. It has since been settled by an actual build: the
orchestrating session reports `mvn clean verify` green with all five properties
on (2026-09-26), so the "expected to pass" verdicts below are now confirmed
rather than inferred. The scan evidence is kept because it is what identified the
one genuine blocker before any build existed.

| Property | Verdict | Evidence |
|---|---|---|
| `ban-junit4-imports.skip=false` | **PASSES** (was: will fail the build) | **0** references to non-Jupiter `org.junit.*` under `src/test`; all 35 files import `org.junit.jupiter`. Resolved by the migration recorded below. |
| `ban-deprecated-stapler.skip=false` | Expected to pass | **0** references to v1 `StaplerRequest` / `StaplerResponse`; the code uses `StaplerRequest2` (13) and `StaplerResponse2` (12) throughout, and the single current-request lookup is already the v2 form — `src/main/java/io/jenkins/plugins/batchcontrol/action/HistorySection.java:220` calls `Stapler.getCurrentRequest2()`. **0** hits for `Stapler.getCurrentRequest()` / `getCurrentResponse()`. |
| `ban-commons-lang-2.skip=false` | Expected to pass | **0** references to `org.apache.commons.lang.` (excluding `lang3`) anywhere under `src/`. |
| `banObsoleteDependencyOverrides.skip=false` | Expected to pass | No dependency in `pom.xml:58-108` declares a `<version>`, so there is no override for the rule to object to. |
| `hpi.strictBundledArtifacts=true` | Expected to pass | Compile-scope dependencies are only `structs` and `cloudbees-folder`, both Jenkins plugins, so nothing should be bundled into the HPI's `WEB-INF/lib`. Cross-checks E8: `src/` contains no jars, minified JS or vendored sources. |

### R1 detail — the JUnit 4 blocker, and how it was cleared

**RESOLVED 2026-09-26** (commits `aa2b344`, `abebb3e`, `71ba3f0`, then `c8f63a0`
restoring the property). What is kept below is the part a future reader can use:
why the switch was dangerous, and the two things that made the migration itself
risky.

**Why it was a blocker rather than a nuisance.** The enforcer rule
`check-junit-imports` binds to `generate-test-sources`, which is *before* the
tests compile and run. With `ban-junit4-imports.skip=false` and a JUnit 4 suite,
the build therefore stops before executing a single test. It is not a red test
report, it is no test report at all: `mvn clean verify` fails with an enforcer
message and zero evidence about whether the plugin works. That is also why the
property could not simply be left on and dealt with later, and why removing it
from the POM was not an option either — B18 is a REQUIRED bot finding.

**The shape of the work.** 239 references to non-Jupiter `org.junit.*` across
all 34 tracked test files (35 including `BatchControlFixtures.java`, which was
untracked at the time of the scan and so invisible in a diff against `HEAD`).
The assertions were the easy half, a mechanical rename to
`org.junit.jupiter.api.Assertions`. The hard half was `org.junit.Rule`: 30
references to `JenkinsRule` and 4 to `JenkinsSessionRule`, in
`GlobalConfigDefaultsTest`, `GrantRestartTest`, `RestartRecoveryTest` and
`StoreDurabilityTest`. `JenkinsRule` becomes
`org.jvnet.hudson.test.junit.jupiter.WithJenkins` with a `JenkinsRule` parameter
injected per test method, which rewrites every method signature in the file, and
`JenkinsSessionRule` becomes `JenkinsSessionExtension` with the session bodies
rewritten. Only two files had no `@Rule` at all and could be converted purely
mechanically.

**What made it worth splitting into stages.** It was done as separate commits for
the `JenkinsRule` classes and the `JenkinsSessionRule` classes, with the
enforcement property restored last, so that a failure at any point named one
class of problem rather than all of them at once.

**The trap worth writing down.** `assertEquals` and `assertThrows` take their
message argument in a *different position* in JUnit 5 — the message moves to the
end. A JUnit 4 call whose message was left in the JUnit 4 position still
compiles: the message silently becomes the `expected` value, or the two compared
values shift. Nothing warns, and the test then either passes for the wrong reason
or fails with an assertion message that describes something other than what
actually broke. Anyone repeating this migration should diff the argument order of
every message-carrying assertion by hand rather than trusting a find-and-replace.

**Outcome.** 0 non-Jupiter `org.junit.*` references remain, 0 tests are
`@Disabled`, 0 were deleted, and the per-class test count matches the
pre-migration suite across all 34 classes. With the property back on, the
orchestrating session reports a full `mvn clean verify` at **195 tests, 0
failures, SpotBugs `BugInstance size is 0`, `BUILD SUCCESS`**. B18 and E4 are
both PASS.

---

## 4. Non-blocking recommendations

1. **`CHANGELOG.md` is absent.** The documentation page recommends "Use GitHub
   Releases or create a CHANGELOG file". Not a bot requirement. This one got
   slightly more relevant when Q5 was answered `No`: without CD nothing generates
   release notes automatically, so either releases are drafted by hand or a
   `Keep a Changelog` file starting at `Unreleased` carries them. The file costs
   nothing.
2. ~~**`CONTRIBUTING.md` and a pull-request template** are listed as recommended
   by the documentation guide. Absent today.~~ **DONE 2026-09-26.** Both exist:
   `CONTRIBUTING.md` at the repository root (build/test with expected results,
   repository layout, document system and glossary, test conventions, `e2e/`,
   known pitfalls, PR rules — see the G3 follow-up), and
   `.github/PULL_REQUEST_TEMPLATE.md`, which carries the five checks from
   `CONTRIBUTING.md` §7 (verify green + SpotBugs 0, spec-first for a behaviour
   change, matrix row + failing-test-first + false-positive guard for new
   behaviour, a real browser look at any `src/main/resources/**` change since
   Jelly compiles only at runtime, and no security disclosure in a public PR)
   plus the issue and contract anchor. `README.md`'s *Contributing* section now
   links to `CONTRIBUTING.md` and keeps only the two commands a fresh clone needs.
3. **Prefer `<version>${changelist}</version>`** over `${revision}${changelist}`
   to clear the D3 warning, if CD is enabled.
4. **GitHub topics** — the documentation guide suggests applying topics for
   discoverability (`jenkins-plugin`, and domain topics).
5. **Security-scan workflow trigger** currently fires on pushes to `main`
   (`.github/workflows/jenkins-security-scan.yml:5-7`). That matches the repo's
   default branch, so it is fine; just confirm it after the jenkinsci fork.
6. **No license headers in source files** — 0 of 65 files under `src/main/java`
   contain a `Copyright` line. No official requirement was found for per-file
   headers in the hosting checks (**source not confirmed**), and many hosted
   plugins omit them; listed only so the choice is deliberate.
7. **Consider adding a `jdk: 25` build configuration** — allowed by
   `ALLOWED_JDK_VERSIONS` and useful forward coverage.
8. **Baseline choice.** `jenkins.version` 2.568.3 clears the 2.541.3 minimum.
   The baseline guidance page listed 2.541.3 and 2.555.3 as "currently
   recommended", with 2.568.1 as the newer alternative — so 2.568.3 is newer
   than recommended but valid, and `bom-2.568.x` exists. Trade-off: fewer
   reachable users, more recent APIs. No action needed for hosting.

---

## 5. Open questions for the owner

The owner answered **Q1**, **Q2**, **Q4**, **Q5** and **Q8** on 2026-09-26; those
are struck through below. Three remain: **Q3**, **Q6** and **Q7**. None of them
gates any work, and none is one of the 65 checklist rows — Q3 is the literal value
for form field 4, Q6 is an email address, Q7 is a disclosure choice about the
Description text.

In checklist terms the only rows that are not PASS, N/A, WARN or INFO are **E1**
(the "how is this different" wording in the Description) and **E2** (the GitHub
accounts to receive commit permission, i.e. Q3). Both need the owner.

| # | Question | Why it must be a human decision |
|---|---|---|
| ~~**Q1**~~ | ~~Confirm the final plugin id and display name: `batch-control` / `Batch Control`, repository `batch-control-plugin`.~~ **RESOLVED 2026-09-26** — owner confirmed `batch-control` is kept. The decision is irreversible after the first release ("Jenkins would consider it a different plugin"), and all naming checks A1–A8 pass with these values. | — |
| ~~**Q2**~~ | ~~License: keep MIT? And the exact copyright line for `LICENSE`.~~ **RESOLVED 2026-09-26** — owner confirmed **MIT**, holder **`Yongjun Hong`** (personal, not an employer), year **2026**. Applied at `LICENSE:1-3`, consistent with `pom.xml:20-25`. | — |
| **Q3** | GitHub accounts to receive commit permission (field 4). Is `@YongGoose` the only one? | Owner data. |
| ~~**Q4**~~ | ~~The accounts.jenkins.io account for release permission, and confirmation it has signed into Jira and Artifactory.~~ **RESOLVED 2026-09-26** — owner confirmed the accounts.jenkins.io account is created and needs no separate verification (E3 → PASS). The literal username string still has to be typed into field 5 at submission time, without an `@`, and it is independent of the GitHub handle. | — |
| ~~**Q5**~~ | ~~Field 6: answer `Yes` or `No` to automated release via GitHub Actions?~~ **RESOLVED 2026-09-26** — owner answered **`No`**. G10 therefore drops out of the first review and D1/D4/D5/D6 become N/A. CD stays available later through a follow-up PR to repository-permissions-updater (D8). | — |
| **Q6** | Should the maintainer email in the request differ from `dev.yongjunh@gmail.com`? | Security reports are routed to release-permission holders; the address must be one that is actually monitored. |
| **Q7** | Has the plugin been piloted on a real in-house Jenkins? If so, may it be mentioned (anonymously) in the Description? | `docs/HOSTING-CHECKLIST.md:55` asks for it; it materially helps human review, but disclosing internal usage is the owner's call. Phase 5 in `docs/WORKFLOW.md` is the pilot, and `docs/STATUS.md` should be consulted for whether it has happened. |
| ~~**Q8**~~ | ~~Where should the development-kit README content move, and should the `.claude/` directory ship in the hosted repository at all?~~ **RESOLVED 2026-09-26.** The content was rewritten for contributors into **`CONTRIBUTING.md`** at the repository root, so it is no longer only in git history. And `.claude/` **does** ship: the owner decided to keep it, three skills were added (`5966360`), and `CLAUDE.md`, `docs/WORKFLOW.md` and `.claude/agents/**` were translated to English (`7281863`), so a reviewer or a forker can actually read the tooling instead of finding untranslated internal notes. `CONTRIBUTING.md` §8 points at `.claude/` and stays correct as written. | — |

---

## 6. Draft submission

> **The operative copy is [`HOSTING-REQUEST.md`](HOSTING-REQUEST.md).** That
> document is the run book to follow on submission day — the pasteable field
> values, the pre-submission checks, the bot flow and the post-approval steps.
> The draft below stays here as the record of how each value was derived; if the
> two ever disagree, `HOSTING-REQUEST.md` is what gets pasted.

Fill the template at
<https://github.com/jenkins-infra/repository-permissions-updater/issues/new?template=1-hosting-request.yml>.
Every `<OWNER: …>` marker is a value that must be supplied or confirmed by a
human. Nothing below is invented.

### 6.1 Field 1 — Repository URL

```
https://github.com/YongGoose/batch-control-plugin
```

*(No trailing `.git` — the bot rejects that: "check that you do not have .git at
the end, GitHub API doesn't support this".)*

### 6.2 Field 2 — New Repository Name

```
batch-control-plugin
```

### 6.3 Field 3 — Description

> Batch Control adds run approval, just-in-time job-change permissions, and an
> append-only audit history to Jenkins instances that are operated as a batch
> execution manager rather than as a CI server.
>
> Three capabilities, all off by default — installing the plugin changes nothing
> until an administrator enables each switch independently:
>
> 1. **Run approval.** A manual run of a protected job becomes a request. The
>    job does not start until a registered approver approves it. Timer and
>    upstream triggers can be blocked separately, with an allow-list of upstream
>    jobs.
> 2. **Just-in-time change permission.** Changing a protected job's
>    configuration requires an approved, time-boxed grant, scoped to a set of
>    actions. When the window expires, the permission disappears without an
>    administrator having to revoke it.
> 3. **Audit history.** Every run, every configuration change
>    (create/update/delete/rename/move), every approval decision, and every
>    failure is recorded independently of build-log retention, and failures are
>    raised as incidents that have to be closed.
>
> Five permissions (`BatchControl/Request`, `Approve`, `RequestGrant`,
> `ViewHistory`, `Manage`) are exposed to the matrix- and role-based
> authorization strategies.
>
> **How this differs from similar components:**
>
> - **Pipeline `input` step** — a gate *inside* a Pipeline run. The run has
>   already started, only Pipeline jobs can use it, freestyle jobs cannot, and
>   there is no record that outlives the build. Batch Control gates the *start*
>   of a run for any job type and keeps the record independent of build
>   retention.
> - **Job StrongAuthSimple** — unmaintained for about thirteen years, written
>   against Jenkins 1.x, with no Pipeline support, no audit history, and no
>   time-boxed permissions. There is no code worth adopting, which is why this
>   is a new plugin rather than an adoption request.
> - **Audit Trail / Audit Log** — they record what happened. They have no
>   concept of a request, an approver, an approval decision, or a permission
>   that expires. Batch Control is a control plane whose audit history is a
>   by-product.
> - **Matrix / Role Strategy authorization** — static permissions. Batch Control
>   wraps the configured strategy to add permissions that exist only inside an
>   approved window, and delegates every other decision to it.
> - **Commercial batch schedulers (Control-M and similar)** — this is the gap
>   the plugin targets: teams already running their batch workload on Jenkins
>   have no free-software option for approval and audit, so they either buy a
>   scheduler or go without.
>
> Source: <OWNER: confirm — see Q7, whether to mention the in-house pilot>
>
> Tested with `JenkinsRule` integration tests plus a Docker-based end-to-end
> suite; reports live in `docs/reports/`.

> **Owner check before pasting.** Every claim about the four comparison targets
> comes from `docs/DECISIONS.md:7` (D-01) and `:9` (D-02), which are internal
> design notes. Re-read the current state of those plugins before submitting —
> a reviewer who maintains one of them will notice a stale claim, and "thirteen
> years" is a figure from an internal note, not something re-verified here on
> 2026-09-26.

### 6.4 Field 4 — GitHub users to have commit permission

```
<OWNER: GitHub handles with @, one per line — see Q3. Likely @YongGoose>
```

### 6.5 Field 5 — Jenkins project users to have release permission

```
<OWNER: accounts.jenkins.io usernames, one per line, NO @ prefix — see Q4.
 Each must have signed into https://issues.jenkins.io and
 https://repo.jenkins-ci.org/ before the bot re-checks.>
```

### 6.6 Field 6 — Automated release via GitHub Actions (recommended)

```
No
```

Owner-confirmed 2026-09-26 (Q5). This is why D1/D4/D5/D6 are N/A and G10 is out
of scope. CD can be turned on after hosting through the D8 follow-up PR.

### 6.7 Pre-submission sequence

1. ~~Resolve G1, G2, G4, G5, G6, G7, G8, G9.~~ **Done 2026-09-26.**
   ~~G10 remains, and only applies if field 6 = `Yes` (Q5).~~ **G10 is out of
   scope**: Q5 = `No`.
2. ~~Resolve G3 (README replacement), relocate the development-kit content, link
   `CONTRIBUTING.md` from the README, and settle the `.claude/` half of Q8.~~
   **All done 2026-09-26.** The README was subsequently rewritten for length and
   tone, with the full limitation list split into `docs/LIMITATIONS.md` and a
   Korean companion at `README.ko.md`.
3. ~~**Migrate `src/test/**` to JUnit 5** — required by the
   `ban-junit4-imports.skip=false` property added in step 1.~~ **Done
   2026-09-26** (`aa2b344`, `abebb3e`, `71ba3f0`, `c8f63a0`). See **R1** for the
   two traps worth knowing if it is ever repeated.
4. Re-check the live latest `bom-2.568.x` version and the latest parent POM
   version on the submission day. **Both are moving targets** — the BOM in
   particular has already been bumped once for this submission (G9), so treat the
   value in `pom.xml` as stale until it is re-fetched on the day.
5. ~~Confirm the accounts.jenkins.io user(s) have logged into Jira and
   Artifactory.~~ **Owner-confirmed 2026-09-26** (Q4).
6. Run `mvn -q clean verify` on the final tree — green, SpotBugs clean (G11).
   Last known result: 195 tests, 0 failures, `BugInstance size is 0`,
   `BUILD SUCCESS`. Re-run anyway, because step 4 can change the dependency tree.
7. Push everything to the default branch of
   `https://github.com/YongGoose/batch-control-plugin`. The bot reads the
   repository's default branch, so nothing may be left on a feature branch.
   Two checks only become true after this push: GitHub's license detection (C8)
   and dependabot activation (C7).
8. Open the hosting request issue.
9. Read the bot comment. If it reports findings, fix and comment
   `/hosting re-check`.
10. After approval: accept the jenkinsci invitation, delete the original
    repository, then follow section E of `docs/HOSTING-CHECKLIST.md`.

---

## 7. Source register

| What | URL | Confirmed |
|---|---|---|
| Hosting request process | <https://www.jenkins.io/doc/developer/publishing/requesting-hosting/> | 2026-09-26 |
| Preparation / license & documentation duty | <https://www.jenkins.io/doc/developer/publishing/preparation/> | 2026-09-26 |
| Issue template (all six fields) | <https://github.com/jenkins-infra/repository-permissions-updater/blob/master/.github/ISSUE_TEMPLATE/1-hosting-request.yml> | 2026-09-26 |
| Hosting Checker driver & severity wording | `.../hosting/HostingChecker.java` in jenkins-infra/repository-permissions-updater | 2026-09-26 |
| Requirement constants (parent POM, Jenkins version, JDK list) | `.../hosting/Requirements.java` | 2026-09-26 |
| All POM checks | `.../hosting/MavenVerifier.java` | 2026-09-26 |
| Required-file and Jenkinsfile checks | `.../hosting/RequiredFilesVerifier.java` | 2026-09-26 |
| Jelly CSP checks | `.../hosting/JellyVerifier.java` | 2026-09-26 |
| GitHub-side checks (license, readme, forks, target/work in history) | `.../hosting/GitHubVerifier.java` | 2026-09-26 |
| Request-field checks | `.../hosting/HostingFieldVerifier.java` | 2026-09-26 |
| Jenkins account checks | `.../hosting/JenkinsProjectUserVerifier.java` | 2026-09-26 |
| Banned dependency → API plugin mapping | <https://github.com/jenkins-infra/repository-permissions-updater/blob/master/banned-dependencies.lst> | 2026-09-26 |
| Naming conventions (id, display name, groupId) | <https://www.jenkins.io/doc/developer/publishing/style-guides/> | 2026-09-26 |
| Baseline policy and recommended versions | <https://www.jenkins.io/doc/developer/plugin-development/choosing-jenkins-baseline/> | 2026-09-26 |
| CD (JEP-229) setup | <https://www.jenkins.io/doc/developer/publishing/releasing-cd/> | 2026-09-26 |
| Documentation requirements / plugin site landing page | <https://www.jenkins.io/doc/developer/publishing/documentation/> | 2026-09-26 |
| Security guidance for plugin developers | <https://www.jenkins.io/doc/developer/security/> | 2026-09-26 |
| Security obligations for maintainers | <https://www.jenkins.io/security/for-maintainers/> | 2026-09-26 |
| Latest parent POM | `https://repo.jenkins-ci.org/artifactory/public/org/jenkins-ci/plugins/plugin/maven-metadata.xml` | 2026-09-26 |
| Latest plugin BOM for 2.568.x | `https://repo.jenkins-ci.org/artifactory/public/io/jenkins/tools/bom/bom-2.568.x/maven-metadata.xml` | 2026-09-26 |
| Our repository's GitHub metadata | `https://api.github.com/repos/YongGoose/batch-control-plugin` | 2026-09-26 |

### 7.1 Items where no official source was confirmed

State these as unverified rather than as requirements:

- **Branch naming.** No rule was found in any hosting document or in the Hosting
  Checker about the name of the default branch. The bot reads the repository's
  default branch, which is `main` here. **Source not confirmed** for any
  stricter requirement.
- **Per-file license headers.** No hosting check and no requirement page was
  found that mandates a copyright header in each source file. **Source not
  confirmed.**
- **`src/main/resources/index.jelly`.** Required by our own
  `docs/HOSTING-CHECKLIST.md:17` and produced by the plugin archetype, but no
  automated check for it was found in the Hosting Checker sources.
  **Source not confirmed as a hosting requirement** (it is still the right thing
  to have — it is what Jenkins shows on the Plugin Manager page).
- **`CHANGELOG.md`, `CONTRIBUTING.md`, PR template, GitHub topics.**
  Recommended by the documentation guide, not enforced. Listed as non-blocking.
  (`CONTRIBUTING.md` and `.github/PULL_REQUEST_TEMPLATE.md` exist as of
  2026-09-26; `CHANGELOG.md` and the GitHub topics are still absent — still
  non-blocking.)
- **Baseline recommendation text.** The choosing-a-baseline page was read on
  2026-09-26 and listed 2.541.3 / 2.555.3 as currently recommended with 2.568.1
  as the newer alternative; it also stated the update centre minimum as 2.516
  (weekly) / 2.516.2 (LTS). These values rotate — re-read the page rather than
  trusting this paragraph if the baseline is ever revisited.
- **`mvn clean verify` result, SpotBugs count, and the effect of enabling the
  three `ban-*` enforcement properties (G6).** Not executed by the author of this
  document. The result recorded at E4 and R1 (195 tests, 0 failures, SpotBugs
  `BugInstance size is 0`, `BUILD SUCCESS`) comes from the orchestrating session
  on 2026-09-26. It is a real build, but it is second-hand here, and it must be
  re-run on the final pre-submission tree in any case (§6.7 step 6).
