# jenkinsci Hosting Readiness

Empirical readiness report for submitting `batch-control` to the Jenkins project
hosting process.

| | |
|---|---|
| Verification date | **2026-09-26** (all URLs below fetched on this date) |
| Repository under review | `https://github.com/YongGoose/batch-control-plugin` (public, not a fork, default branch `main` — verified via GitHub API on 2026-09-26) |
| Branch inspected | `handoff/phase-4-5-continuation` |
| Requirements checked | **60** |
| PASS | **36** |
| FAIL | **18** |
| WARNING | **1** |
| UNKNOWN | **4** |
| INFO | **1** |

> **Verification note.** No Maven build was run while preparing this document
> (a concurrent full build was in progress). Every row whose verdict depends on
> a build is marked `UNKNOWN — build verification required`, never assumed to
> pass.

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
| `io.jenkins.tools.bom:bom-2.568.x` | `7093.v37de7b_4a_8a_4f` (`lastUpdated 20260925171701`) | `7046.v43536164769c` | **behind** |

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
| B6 | `<developers>` must be **removed** — "This information is fetched from this repository on the update site." | RPU `MavenVerifier.checkDevelopersTag` | **FAIL** | `pom.xml:27-33` | Delete the whole `<developers>` block |
| B7 | `<url>` must equal `https://github.com/jenkinsci/${project.artifactId}-plugin` | RPU `MavenVerifier.checkUrl` | **FAIL** | `pom.xml:18` is `https://github.com/${gitHubRepo}`, and `pom.xml:45` sets `gitHubRepo` = `YongGoose/batch-control-plugin` | Replace with the literal jenkinsci form |
| B8 | `<scm><connection>` must be HTTPS | RPU `MavenVerifier.checkSoftwareConfigurationManagementField` | PASS | `pom.xml:36` | — |
| B9 | `<scm><developerConnection>` present | same | PASS | `pom.xml:37` | — |
| B10 | `<scm><url>` present | same | PASS | `pom.xml:39` | — |
| B11 | `<scm><tag>` present | same | PASS | `pom.xml:38` = `${scmTag}` | — |
| B12 | SCM URLs should point at the jenkinsci repository — the bot's own sample is `scm:git:https://github.com/jenkinsci/${project.artifactId}-plugin.git` | RPU `MavenVerifier` SCM message text (sample only; presence/scheme is what is enforced) | **FAIL** | `pom.xml:36-39` resolve to `YongGoose/...` via `pom.xml:45` | Drop the `gitHubRepo` property and use the jenkinsci form everywhere |
| B13 | `<repository>`/`<pluginRepository>` for repo.jenkins-ci.org must use `https://` | RPU `MavenVerifier.checkRepositories` / `checkPluginRepositories` | PASS | `pom.xml:114-126` | — |
| B14 | Properties `java.level`, `maven.compiler.source/target/release` must be absent | RPU `MavenVerifier.checkProperties` | PASS | absent from `pom.xml:42-48` | — |
| B15 | Property `hpi.strictBundledArtifacts` = `true` | RPU `MavenVerifier.checkProperties` | **FAIL** | not in `pom.xml:42-48` | Add it |
| B16 | Property `ban-commons-lang-2.skip` = `false` | same | **FAIL** | not present | Add it |
| B17 | Property `ban-deprecated-stapler.skip` = `false` | same | **FAIL** | not present | Add it |
| B18 | Property `ban-junit4-imports.skip` = `false` | same | **FAIL** | not present | Add it |
| B19 | Property `banObsoleteDependencyOverrides.skip` = `false` | same | **FAIL** | not present | Add it |
| B20 | Plugin BOM imported and its `artifactId` in sync with the baseline (`bom-<baseline>.x`) | RPU `MavenVerifier.checkDependencyManagement` | PASS | `pom.xml:54` = `bom-${jenkins.baseline}.x` → `bom-2.568.x` | — |
| B21 | BOM version must equal the latest released BOM for that line | same | **FAIL** | `pom.xml:55` = `7046.v43536164769c`; latest on 2026-09-26 = `7093.v37de7b_4a_8a_4f` | Bump, and re-check on submission day |
| B22 | No explicit `<version>` on dependencies the BOM manages | same | PASS | `pom.xml:62-112` — no dependency declares a version | — |
| B23 | No banned third-party dependency in `compile` scope (must use the API plugin instead) | RPU `MavenVerifier.checkDependencies` + `banned-dependencies.lst` | PASS | compile deps are only `structs` (`pom.xml:63-66`) and `cloudbees-folder` (`pom.xml:67-70`); neither is in the banned list | — |

### C. Required repository files

| # | Requirement | Source | Status | Evidence | Action |
|---|---|---|---|---|---|
| C1 | `Jenkinsfile` exists, contains exactly one `buildPlugin(...)` call, parameterized, with a `configurations` list where every entry has `platform` and `jdk` | RPU `RequiredFilesVerifier.validateJenkinsFile` | PASS (structure) | `Jenkinsfile:1-7` | — |
| C2 | Every `jdk` value must be in `[21, 25]` | RPU `Requirements.ALLOWED_JDK_VERSIONS` | **FAIL** | `Jenkinsfile:5` uses `jdk: 17` for the windows configuration | Change 17 → 21 (or 25) |
| C3 | `.gitignore` must exclude `target` | RPU `RequiredFilesVerifier.checkGitignore` | PASS | `.gitignore:1` = `target/` | — |
| C4 | `.gitignore` must exclude `work` | same | PASS | `.gitignore:2` = `work/` | — |
| C5 | `.github/workflows/jenkins-security-scan.yml` (or `.yaml`) must exist | RPU `RequiredFilesVerifier.checkSecurityScan` | PASS | `.github/workflows/jenkins-security-scan.yml:1-22`, pinned to `jenkins-infra/jenkins-security-scan@da7438f…` (v2) | — |
| C6 | `.github/CODEOWNERS` must exist and contain the exact line `* @jenkinsci/<new-repo-name>-developers` | RPU `RequiredFilesVerifier.checkCodeOwners` | **FAIL** | `.github/` contains only `workflows/` — no CODEOWNERS | Create `.github/CODEOWNERS` with `* @jenkinsci/batch-control-plugin-developers` |
| C7 | A dependency-update bot config must exist: one of `.github/dependabot.yml(.yaml)`, `renovate.json`, `.github/renovate.json`, `.github/workflows/updatecli.yml(.yaml)` | RPU `RequiredFilesVerifier.checkDependencyBot` | **FAIL** | none of the six paths exists | Add `.github/dependabot.yml` (or the archetype's `.github/renovate.json`) |
| C8 | A license file must exist in the repository and be detectable by GitHub | RPU `GitHubVerifier.checkLicense` | **FAIL** | no `LICENSE`/`COPYING`/`NOTICE` in the repo root; GitHub API returns `"license": null` for the repo (checked 2026-09-26) | Add `LICENSE` with the full MIT text |
| C9 | A README must exist and be detectable by GitHub | RPU `GitHubVerifier.checkReadme` | PASS | `README.md` exists | — |
| C10 | README must be usable plugin documentation — reviewed by a human, not the bot ("README content") | Hosting Checker message text; <https://www.jenkins.io/doc/developer/publishing/documentation/> | **FAIL** | `README.md:1-46` is the Claude Code development-kit guide, in Korean, listing agent definitions and phase prompts. No what/why, no installation, no configuration, no usage. Independently found by e2e testing: `docs/reports/e2e-01.md:418-421` | Replace — see gap **G3** |
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
| D1 | Property `changelist` must be exactly `999999-SNAPSHOT` | RPU `MavenVerifier.checkAutomaticReleasesSettings`; <https://www.jenkins.io/doc/developer/publishing/releasing-cd/> | **FAIL** | `pom.xml:44` = `-SNAPSHOT` | Set to `999999-SNAPSHOT` |
| D2 | `<version>` must contain `${changelist}` | same | PASS | `pom.xml:13` = `${revision}${changelist}` | — |
| D3 | `${revision}${changelist}` as the version is discouraged | RPU, severity WARNING | **WARN** | `pom.xml:13` | Prefer `${changelist}` (fully automated) or `${revision}.${changelist}` per the CD doc |
| D4 | `.mvn/extensions.xml` must exist | RPU `RequiredFilesVerifier.checkFilesForCD` | **FAIL** | no `.mvn/` directory | Copy from `jenkinsci/archetypes/common-files/.mvn/extensions.xml` |
| D5 | `.mvn/maven.config` must exist and contain the line `-Dchangelist.format=%d.v%s` | same | **FAIL** | no `.mvn/` directory | Copy the archetype file; CD doc also lists `-Pmight-produce-incrementals` |
| D6 | `.github/workflows/cd.yaml` (or `.yml`) must exist | same | **FAIL** | `.github/workflows/` contains only the security scan | `curl … https://raw.githubusercontent.com/jenkinsci/.github/master/workflow-templates/cd.yaml` |
| D7 | `.github/release-drafter.y*ml` and `.github/workflows/release-drafter.y*ml` must **not** exist | same | PASS | neither exists | — |
| D8 | After hosting, a PR to repository-permissions-updater must add `cd: enabled: true` to `permissions/plugin-batch-control.yml`, then `MAVEN_TOKEN` / `MAVEN_USERNAME` appear as repository secrets | <https://www.jenkins.io/doc/developer/publishing/releasing-cd/> | INFO (post-approval) | n/a | Part of step E in `docs/HOSTING-CHECKLIST.md` |

### E. Human review, build health, and code hygiene

| # | Requirement | Source | Status | Evidence | Action |
|---|---|---|---|---|---|
| E1 | Description must explain "how it's different from other components that may be considered similar to this one" | Issue template field 3 | **UNKNOWN** | Material exists (`docs/DECISIONS.md:7` D-01, `:9` D-02) but no submission text has been reviewed by the owner | Use the section 6 draft, owner to confirm |
| E2 | GitHub users to have commit permission | Issue template field 4 | **UNKNOWN** | Owner data | Owner supplies |
| E3 | Jenkins project (accounts.jenkins.io) users with release permission must have signed into Jira **and** Artifactory | Issue template field 5; RPU `JenkinsProjectUserVerifier` | **UNKNOWN** | Owner data; not verifiable from the repository | Owner creates/confirms the account and logs into both (bot reports re-sync hourly) |
| E4 | `mvn clean verify` green, SpotBugs clean | `docs/HOSTING-CHECKLIST.md:37,43`; human code review | **UNKNOWN — build verification required** | Not run (concurrent build in progress) | Run `mvn -q clean verify` before submitting |
| E5 | No Korean strings in code or resources | `CLAUDE.md:45`; `docs/HOSTING-CHECKLIST.md:45` | PASS | Unicode Hangul scan over `src/`: 0 matching files | — |
| E6 | No hardcoded credentials | `docs/HOSTING-CHECKLIST.md:28`; Jenkins security docs | PASS | grep for `password =`/`secret =`/`apiKey`/`token =` string literals over `src/main/java`: 0 hits | — |
| E7 | No unexpected outbound network calls | Jenkins security review practice | PASS | grep over `src/main/java` for `HttpURLConnection`, `HttpClient`, `openStream`, `okhttp`, `Socket(`, `java.net.URL`: the only match is `import java.net.URLEncoder` in `src/main/java/io/jenkins/plugins/batchcontrol/ui/FilterParser.java:6`, which is string encoding, not I/O | — |
| E8 | No bundled third-party code or binaries in the plugin | <https://www.jenkins.io/doc/developer/plugin-development/dependencies-and-class-loading/#bundling-third-party-libraries> (referenced by RPU `hpi.strictBundledArtifacts` message) | PASS | `src/` contains only `.java`, `.jelly`, `.properties`, `.html`; no jars, no minified JS, no vendored sources. (The `e2e/screenshots/*.jpg` images are test evidence outside `src/`.) | — |
| E9 | Maintainers must handle security reports through the private SECURITY Jira project, keep fixes embargoed until release, and coordinate release timing with the security team | <https://www.jenkins.io/security/for-maintainers/> | INFO (ongoing obligation) | n/a | Owner acknowledges; identity comes from the release-permission metadata in repository-permissions-updater |

---

## 3. Blocking gaps

Ordered by how hard they block. G1–G8 are `REQUIRED` bot findings: the hosting
request cannot be approved while any of them stands.

### G1 — No license file in the repository *(REQUIRED, C8)*

The bot calls `repo.getLicense()`; GitHub currently reports `"license": null`.
The Jenkins project also states plainly that the license must be declared "both
in the pom.xml file, as well as a LICENSE file in your repository", and
recommends MIT.

- **What:** create `LICENSE` at the repository root with the **full MIT text**
  (not a reference), copyright year `2026`, holder per **Q2**.
- **Where:** `/LICENSE`.
- **Why it is more than paperwork:** `pom.xml:20-25` already claims MIT, so
  today the repository advertises a license it does not carry.

### G2 — Jenkinsfile uses a disallowed JDK *(REQUIRED, C2)*

`Requirements.ALLOWED_JDK_VERSIONS` is `List.of(21, 25)`. `Jenkinsfile:5`
requests `jdk: 17`, which produces:
"Invalid version `17` for `jdk`. `jdk` must be one of [21, 25] in the
`buildPlugin` call in the Jenkinsfile."

- **What:** change the windows configuration from `jdk: 17` to `jdk: 21`, so both
  rows read 21 (optionally add a `jdk: 25` row).
- **Where:** `Jenkinsfile:5`.

### G3 — README is the development-kit document, not plugin documentation *(human review, C10)*

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

### G4 — `<developers>` must be removed from `pom.xml` *(REQUIRED, B6)*

Message: "Please remove the `developers` tag from your pom.xml. This information
is fetched from this repository on the update site."

- **What:** delete `pom.xml:27-33` entirely. Maintainer identity comes from the
  release permissions in repository-permissions-updater, not the POM.

### G5 — `<url>` and the SCM URLs point at the personal repository *(REQUIRED, B7; also B12)*

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

### G6 — Five required POM properties are missing *(REQUIRED, B15–B19)*

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

### G7 — `.github/CODEOWNERS` is missing *(REQUIRED, C6)*

- **What:** create `.github/CODEOWNERS` containing the exact line
  `* @jenkinsci/batch-control-plugin-developers`.
- **Note:** that GitHub team does not exist yet — it is created when the
  repository is forked into jenkinsci. The bot only string-matches the line, so
  adding it before approval is correct.

### G8 — No dependency-update bot configuration *(REQUIRED, C7)*

- **What:** add one of `.github/dependabot.yml`, `.github/dependabot.yaml`,
  `renovate.json`, `.github/renovate.json`,
  `.github/workflows/updatecli.yml(.yaml)`. The archetype ships
  `.github/renovate.json`. If CD is enabled, the CD documentation additionally
  asks for a `github-actions` ecosystem entry with a monthly schedule in
  `.github/dependabot.yml`.

### G9 — Plugin BOM is behind the latest release *(REQUIRED, B21)*

`pom.xml:55` pins `7046.v43536164769c`; the latest `bom-2.568.x` on 2026-09-26 is
`7093.v37de7b_4a_8a_4f`.

- **What:** bump, then re-check on the submission day — the bot always compares
  against `<latest>` in the live `maven-metadata.xml`, so this row can go red
  again without any change on our side.
- **Caution:** a BOM bump changes resolved dependency versions — **build
  verification required**.

### G10 — CD block, only if field 6 is answered `Yes` *(REQUIRED, D1/D4/D5/D6)*

If the owner answers `Yes` (the template calls it "recommended"), four more
REQUIRED findings appear today: `changelist` is `-SNAPSHOT` instead of
`999999-SNAPSHOT` (`pom.xml:44`), and `.mvn/extensions.xml`,
`.mvn/maven.config` (with `-Dchangelist.format=%d.v%s`) and
`.github/workflows/cd.yaml` are all absent. Plus the `${revision}${changelist}`
warning (D3).

- **Decision required — Q5.** Answering `No` removes all of G10 from the
  first review; CD can be enabled later by a follow-up PR to
  repository-permissions-updater.

### G11 — Build health unverified *(E4)*

`mvn clean verify` was deliberately not run for this report. The hosting team
does read the ci.jenkins.io build, and G6/G9 both risk breaking it. Run
`mvn -q clean verify` on the final pre-submission tree and treat a red build as
blocking.

---

## 4. Non-blocking recommendations

1. **`CHANGELOG.md` is absent.** The documentation page recommends "Use GitHub
   Releases or create a CHANGELOG file". Not a bot requirement. With CD, GitHub
   Releases are generated, so a `CHANGELOG.md` is optional — but a
   `Keep a Changelog` file starting at `Unreleased` costs nothing.
2. **`CONTRIBUTING.md` and a pull-request template** are listed as recommended
   by the documentation guide. Absent today.
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

| # | Question | Why it must be a human decision |
|---|---|---|
| **Q1** | Confirm the final plugin id and display name: `batch-control` / `Batch Control`, repository `batch-control-plugin`. | "The ID cannot be changed after the first release; Jenkins would consider it a different plugin." All naming checks (A1–A8) currently pass with these values, but the decision is irreversible. |
| **Q2** | License: keep MIT? And the exact copyright line for `LICENSE` — year and holder name (personal name, or an employer if this was written on company time). | `pom.xml:20-25` already says MIT. The holder string cannot be guessed, and an employer may own the copyright. |
| **Q3** | GitHub accounts to receive commit permission (field 4). Is `@YongGoose` the only one? | Owner data. |
| **Q4** | The accounts.jenkins.io username(s) for release permission (field 5) — and confirmation that each has signed into both <https://issues.jenkins.io> and <https://repo.jenkins-ci.org/>. Note: the template says these "must NOT be mentioned" (no `@`), and the Jenkins account name is independent of the GitHub handle. | The bot fails the request if any listed user has not logged into Jira and Artifactory. Re-sync is hourly, so this should be done days before submitting. |
| **Q5** | Field 6: answer `Yes` or `No` to automated release via GitHub Actions? | `Yes` is labelled "recommended" but adds the four REQUIRED items in G10. `No` is switchable later. |
| **Q6** | Should the maintainer email in the request differ from `dev.yongjunh@gmail.com`? | Security reports are routed to release-permission holders; the address must be one that is actually monitored. |
| **Q7** | Has the plugin been piloted on a real in-house Jenkins? If so, may it be mentioned (anonymously) in the Description? | `docs/HOSTING-CHECKLIST.md:55` asks for it; it materially helps human review, but disclosing internal usage is the owner's call. Phase 5 in `docs/WORKFLOW.md` is the pilot, and `docs/STATUS.md` should be consulted for whether it has happened. |
| **Q8** | Where should the development-kit README content move (e.g. `docs/DEV-KIT.md`), and should the `.claude/` directory ship in the hosted repository at all? | The fork carries everything. A reviewer will see the agent definitions. Not a rule violation, but a presentation choice. |

---

## 6. Draft submission

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
<OWNER: Yes | No — see Q5. Answering Yes adds the four REQUIRED CD items in
 gap G10, which are all unmet today.>
```

### 6.7 Pre-submission sequence

1. Resolve G1, G2, G4, G5, G6, G7, G8, G9 (and G10 if field 6 = `Yes`).
2. Resolve G3 (README replacement — separate work item).
3. Re-check the live latest `bom-2.568.x` version and the latest parent POM
   version on the submission day.
4. Confirm the accounts.jenkins.io user(s) have logged into Jira and
   Artifactory (allow an hour for the hourly re-sync).
5. Run `mvn -q clean verify` — green, SpotBugs clean (G11).
6. Push everything to the default branch of
   `https://github.com/YongGoose/batch-control-plugin`. The bot reads the
   repository's default branch, so nothing may be left on a feature branch.
7. Open the hosting request issue.
8. Read the bot comment. If it reports findings, fix and comment
   `/hosting re-check`.
9. After approval: accept the jenkinsci invitation, delete the original
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
- **Baseline recommendation text.** The choosing-a-baseline page was read on
  2026-09-26 and listed 2.541.3 / 2.555.3 as currently recommended with 2.568.1
  as the newer alternative; it also stated the update centre minimum as 2.516
  (weekly) / 2.516.2 (LTS). These values rotate — re-read the page rather than
  trusting this paragraph if the baseline is ever revisited.
- **`mvn clean verify` result, SpotBugs count, and the effect of enabling the
  three `ban-*` enforcement properties (G6).** Not executed. **Build
  verification required.**
