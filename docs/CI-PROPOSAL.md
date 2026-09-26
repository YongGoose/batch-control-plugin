# CI proposal — protecting existing behaviour against external contributions

**Status:** proposal, awaiting owner approval. No workflow file has been created.
**Author:** release-manager agent, 2026-09-26.
**Scope:** `Jenkinsfile`, `.github/workflows/**`, `.github/dependabot.yml`, and the
Maven properties that a compatibility matrix would need. Nothing outside
`docs/CI-PROPOSAL.md` was modified to write this.

---

## 0. The question, and the two things it can mean

The owner's requirement:

> Once this is open source, other people can open pull requests. We need CI that
> checks that the previously working code still works. Look at what other plugins
> do, decide on a best practice, and propose it.

"The previously working code still works" has two distinct readings, and they need
different answers. Both are covered below.

* **(a) Behavioural regression.** Does an incoming pull request break behaviour
  that works today? This is a question about *when and where our 195 tests run* —
  specifically whether they run on a pull request opened from someone else's fork.
* **(b) Jenkins core compatibility.** We are pinned to `jenkins.version` 2.568.3.
  Does the plugin still work on other core versions — older LTS lines, newer LTS
  lines, weeklies? This is a question about *what we build against*.

Reading (a) is the urgent one and is almost free to fix. Reading (b) turns out to
be mostly a *non-obligation downwards* and a *scheduled check upwards*; §3.2
explains why, and it is the one place where this proposal will likely differ from
the owner's intuition.

---

## 1. What was surveyed

Every file below was read directly from the repository, not recalled. Nine
`jenkinsci` repositories plus the two pieces of shared infrastructure they all
delegate to.

| Repository | `Jenkinsfile` (ci.jenkins.io) | `.github/workflows/` |
|---|---|---|
| [`matrix-auth-plugin`](https://github.com/jenkinsci/matrix-auth-plugin) | `buildPlugin(useContainerAgent: true, [linux/25, windows/21])` | `jenkins-security-scan.yml` — **and nothing else** |
| [`configuration-as-code-plugin`](https://github.com/jenkinsci/configuration-as-code-plugin) | `buildPlugin(useContainerAgent: true, forkCount: '0.5C', timeout: 360, [linux/25, windows/21])` | `cd.yaml`, `jenkins-security-scan.yml` |
| [`role-strategy-plugin`](https://github.com/jenkinsci/role-strategy-plugin) | `buildPlugin(useContainerAgent: true, forkCount: '0.75C', [linux/25, windows/21])` | `cd.yaml`, `jenkins-security-scan.yml` |
| [`credentials-plugin`](https://github.com/jenkinsci/credentials-plugin) | `buildPlugin(useContainerAgent: true, [linux/21, linux/25, windows/21])` | `cd.yaml`, `jenkins-security-scan.yml`, `auto-merge-safe-deps.yml`, `close-bom-if-passing.yml` |
| [`workflow-cps-plugin`](https://github.com/jenkinsci/workflow-cps-plugin) | `buildPlugin(forkCount: '1C', useContainerAgent: true, [linux/25, windows/21])` | `cd.yaml`, `jenkins-security-scan.yml`, `auto-merge-safe-deps.yml`, `close-bom-if-passing.yml`, `label-dependabot-security.yml` |
| [`git-plugin`](https://github.com/jenkinsci/git-plugin) | `buildPlugin(forkCount: '1C', useContainerAgent: false /* Docker for containerized tests */, [linux/25, windows/21])` | `jenkins-security-scan.yml`, `auto-merge-safe-deps.yml`, `close-bom-if-passing.yml`, `labeler.yml`, `release-drafter.yml` |
| [`job-dsl-plugin`](https://github.com/jenkinsci/job-dsl-plugin) | `buildPlugin(...)` | `cd.yaml`, `jenkins-security-scan.yml`, `api-viewer.yml`, `wiki.yml` (docs publishing) |
| [`jenkins-test-harness`](https://github.com/jenkinsci/jenkins-test-harness) | `buildPlugin(useContainerAgent: true, forkCount: '1C', [linux/17, windows/17, **linux/21 + `jenkins: '2.543'`**])` | — |
| [`warnings-ng-plugin`](https://github.com/jenkinsci/warnings-ng-plugin) | `buildPlugin(failFast: false, timeout: 90, [linux/21, windows/25], + checkstyle/pmd/jacoco quality gates)` | `ci.yml` (**full Maven build, 3 OS × JDK 21/25**), `ui-tests.yml` (**8 UI tests × 2 browsers, Docker**), `codeql.yml`, `dependency-check.yml` (OWASP), `jenkins-security-scan.yml`, `cd.yml`, `quality-monitor-*.yml`, `enforce-labels.yml`, `sync-labels.yml`, `assign-pr.yml`, `check-md-links.yml` |

Shared infrastructure read in full:

* [`jenkins-infra/pipeline-library` `vars/buildPlugin.groovy`](https://github.com/jenkins-infra/pipeline-library/blob/master/vars/buildPlugin.groovy) — what `buildPlugin()` actually does.
* [`jenkinsci/bom` README + CONTRIBUTING](https://github.com/jenkinsci/bom) — BOM lines and PCT policy.
* [`jenkins-infra/jenkins-security-scan`](https://github.com/jenkins-infra/jenkins-security-scan/blob/main/.github/workflows/jenkins-security-scan.yaml) — the reusable workflow we already call.
* [`jenkinsci/.github/workflow-templates/`](https://github.com/jenkinsci/.github/tree/master/workflow-templates) — the org's own recommended workflow set.
* [`jenkinsci/plugin-compat-tester`](https://github.com/jenkinsci/plugin-compat-tester) README.
* jenkins.io: [continuous integration](https://www.jenkins.io/doc/developer/publishing/continuous-integration/), [choosing a Jenkins baseline](https://www.jenkins.io/doc/developer/plugin-development/choosing-jenkins-baseline/), [requesting hosting](https://www.jenkins.io/doc/developer/publishing/requesting-hosting/), [changelog-stable](https://www.jenkins.io/changelog-stable/).
* GitHub docs: [events that trigger workflows](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows), [approving fork runs](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/approve-runs-from-forks), [Actions billing](https://docs.github.com/en/billing/concepts/product-billing/github-actions).

---

## 2. Findings

### F1 — In the Jenkins ecosystem, compile-and-test lives on ci.jenkins.io; GitHub Actions does the side jobs

Eight of the nine repositories run **no build or test at all** in GitHub Actions.
Their workflow directories contain only: the security scan, CD (JEP-229 release),
dependency-bot plumbing (`auto-merge-safe-deps`, `close-bom-if-passing`,
`label-dependabot-security`), labelling, release drafting, and documentation
publishing. The division of labour is explicit and consistent.

This is not an accident of each maintainer's taste — it is the org's stated
position. The [`jenkinsci/.github` workflow-template set](https://github.com/jenkinsci/.github/tree/master/workflow-templates)
offers exactly six templates: `auto-merge-safe-deps`, `cd`,
`close-bom-if-passing`, `crowdin`, `jenkins-security-scan`, `release-drafter`.
**There is no build/test template.** Testing is assumed to be on ci.jenkins.io.

`warnings-ng-plugin` is the one exception, and it is instructive rather than
normative: it duplicates the whole Maven build across three operating systems and
two JDKs in Actions *in addition to* ci.jenkins.io, plus an OWASP dependency scan,
plus a separate Docker-based UI-test matrix of 16 jobs. That is a plugin family
with a dedicated maintainer, several sibling repositories and its own quality
tooling. Copying it would roughly sextuple our CI surface for redundant signal.

### F2 — `buildPlugin()` gives us far more than "run the tests", for free

Reading `buildPlugin.groovy` line by line, one `buildPlugin(...)` call on
ci.jenkins.io produces, per configuration:

* `mvn clean install` with `-Dmaven.test.failure.ignore`, JaCoCo enabled on Linux;
* JUnit result publishing including failsafe and invoker reports;
* on the **first** configuration only: reference-build discovery against the
  target branch, JaCoCo coverage recording with `sourceCodeRetention: MODIFIED`,
  and `recordIssues` for Maven console, ESLint, `java()`/`javaDoc()`, **SpotBugs
  with a quality gate of `[threshold: 1, type: 'NEW', unstable: true]`**,
  Checkstyle (`TOTAL` ≥ 1 → unstable), PMD, CPD, and a FIXME/TODO task scanner;
* `failFast: true` (the default) converts an UNSTABLE result into an outright
  `error` both after tests and after static analysis, so a new SpotBugs finding
  or a test failure *fails the PR check*;
* artifact archiving of the `.hpi`, retry-on-agent-loss, Docker cleanup.

Two consequences for this proposal. First, **we must not rebuild static analysis
or coverage gating in GitHub Actions** — it would be strictly worse duplication.
Second, a "new SpotBugs issue relative to the target branch" gate on external PRs
is something we get by doing nothing, once we are in the org.

### F3 — ci.jenkins.io only exists for us after the move to `jenkinsci`

> "Jenkins builds all plugin repositories in the `jenkinsci` organization that
> have a `Jenkinsfile` in the root of the repository." … "your job will appear on
> ci.jenkins.io under the Plugins folder, and all subsequent branches and Pull
> Requests will be built."
> — [jenkins.io, Continuous Integration](https://www.jenkins.io/doc/developer/publishing/continuous-integration/)

And the hosting guide is clear that this is *not* something the hosting team does
for us beyond the fork itself:

> "We recommend you set up CI builds for your plugin in the `jenkinsci` GitHub
> organization by creating a `Jenkinsfile`."
> — [jenkins.io, Guide to Plugin Hosting](https://www.jenkins.io/doc/developer/publishing/requesting-hosting/)

Our `Jenkinsfile` already exists and already satisfies the hosting checker
(`docs/HOSTING-READINESS.md` rows C1/C2). So the moment the repository lands in
`jenkinsci`, PR builds start appearing with no action from us.

**Today, at `YongGoose/batch-control-plugin`, none of this is true.** The only
thing that runs on a pull request is the CodeQL security scan. There is no build,
no test run, no compile check. An external PR merged today would be merged blind.
That is the actual gap.

### F4 — Fork pull requests: what GitHub does and does not allow

From [GitHub's events reference](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows), verbatim:

> "The `GITHUB_TOKEN` has read-only permissions in pull requests from forked repositories."
> "With the exception of `GITHUB_TOKEN`, secrets are not passed to the runner when a workflow is triggered from a forked repository."
> "When a first-time contributor submits a pull request to a public repository, a maintainer with write access may need to approve running workflows on the pull request."

Practical consequences:

* A plain `mvn clean verify` workflow on `pull_request` **does run** on fork PRs.
  It needs no secrets and no write token, so it is unaffected by both
  restrictions. This is the cheap win.
* First-time-contributor approval means the check does not start until a
  maintainer clicks "Approve workflows to run". That is a feature, not a problem —
  it is also our defence against someone editing the workflow file itself.
* `pull_request_target` must **not** be used for anything that executes PR code.
  It runs in the base-branch context with secrets and a write token. The only
  legitimate use in this ecosystem is the org's `auto-merge-safe-deps`, which is
  scoped to dependabot's own PRs. (`warnings-ng-plugin` uses
  `pull_request_target` for its OWASP job; we should not copy that.)
* **Unverified risk:** the reusable Jenkins security scan ends with
  `github/codeql-action/upload-sarif`, and `security-events: write` cannot be
  granted to a fork PR. Whether the upload is permitted anyway through code
  scanning's PR carve-out could not be settled from the documentation. See §6, D5
  — it needs one empirical check on the first external PR, not a guess.

### F5 — GitHub Actions is free for us, so "runner minutes" is the wrong cost axis

> "GitHub Actions usage is free for self-hosted runners and for public
> repositories that use standard GitHub-hosted runners."
> — [GitHub Actions billing](https://docs.github.com/en/billing/concepts/product-billing/github-actions)

The repository is public and will stay public. So the real costs of a matrix are
**wall-clock time a contributor waits**, **the number of red checks a contributor
has to interpret**, and **our own maintenance burden** — not money. This changes
the calculus: a wide matrix is not expensive, it is *confusing and slow*. Every
row we add is another way for an external PR to go red for a reason the
contributor cannot act on.

### F6 — On core versions, the ecosystem answer is the BOM and PCT, not a per-repo matrix

`buildPlugin` does support a per-configuration Jenkins version. From
`getConfigurations` and the build stage:

```groovy
String jenkinsVersion = config.jenkins
...
if (jenkinsVersion) {
  mavenOptions += "-Djenkins.version=${jenkinsVersion} -Daccess-modifier-checker.failOnError=false"
}
```

`jenkins-test-harness` is the one surveyed repository that uses it
(`[platform: 'linux', jdk: 21, jenkins: '2.543']`), and its own comment explains
why it borrows the plugin pipeline at all: *"The only feature that relates to
plugins is allowing one to test against multiple Jenkins versions."* Note also
that `buildPlugin` archives artifacts and records static analysis **only** for the
configuration without an explicit `jenkins` version — extra version rows are test
runs, nothing more.

But none of the eight real plugins uses it. What they use instead:

* **The BOM.** [`jenkinsci/bom`'s README](https://github.com/jenkinsci/bom):
  *"A secondary purpose of this repository is to regularly perform plugin
  compatibility testing (PCT) against new or forthcoming releases of core and
  plugins."* The BOM maintains one artifact per line — currently `bom-2.555.x`,
  `bom-2.568.x`, `bom-2.580.x` and `bom-weekly` — and its CI runs the member
  plugins' own `JenkinsRule` tests against those lines. A plugin in the managed
  set gets multi-core-version testing performed *for* it, continuously, by
  somebody else's infrastructure.
* **`close-bom-if-passing.yml`** (credentials, workflow-cps, git). A dependabot
  BOM bump lands, ci.jenkins.io reports, and the reusable workflow closes the PR
  if it passed — because the BOM moves again next week anyway. This is how the
  ecosystem keeps *plugin-dependency* drift continuously tested with near-zero
  maintainer attention.
* **PCT is deliberately not run on PRs even in the BOM itself.** From
  `bom/CONTRIBUTING.md`: *"To minimize cloud resources, PCT is not run at all by
  default on pull requests, only some basic sanity checks."* The full matrix is
  opt-in per PR via a `full-test` label, with an explicit warning that it
  *"consumes a lot of cloud resources."*

If the project that exists to do compatibility testing does not run it per pull
request, we should not either.

### F7 — Our baseline is one LTS line *newer* than the documented recommendation

* [changelog-stable](https://www.jenkins.io/changelog-stable/): **2.568.3** is the
  latest LTS, released 2026-09-02. So 2.568.x is the *current* line.
* [Choosing a Jenkins baseline](https://www.jenkins.io/doc/developer/plugin-development/choosing-jenkins-baseline/):
  *"At the moment, the Jenkins releases 2.541.3 and 2.555.3 make good core
  dependencies"*; *"Do not use versions no longer supported by the update center,
  which is currently anything older than 2.516 for weekly releases, and 2.516.2
  for LTS releases"*; *"Prefer an LTS version over weekly versions."*
* `bom/CONTRIBUTING.md`: *"The developer documentation recommends the last
  releases of each of the previous two LTS baselines."*

So the convention is *current minus one or two*, and we are at *current*. This is
valid (it clears the 2.516.2 floor and `bom-2.568.x` exists) and
`docs/HOSTING-READINESS.md` §4 item 8 already records it as a deliberate
trade-off: fewer reachable users, more recent APIs. It is restated here because it
is the real lever on reading (b) — see §3.2.

### F8 — Our `pom.xml` is not shaped for cheap multi-line testing

```xml
<jenkins.baseline>2.568</jenkins.baseline>
<jenkins.version>${jenkins.baseline}.3</jenkins.version>
...
<artifactId>bom-${jenkins.baseline}.x</artifactId>
<version>7093.v37de7b_4a_8a_4f</version>
```

The BOM *artifactId* follows `jenkins.baseline`, but the BOM *version* is a
literal pinned to the 2.568.x line. Overriding `-Djenkins.version=2.555.3` alone
would therefore leave `bom-2.568.x` in place — a mismatched combination that can
fail on `RequireUpperBoundDeps` or at plugin load, producing failures that are
artefacts of the override rather than real incompatibilities.

Testing a **lower** line honestly requires a Maven profile per line, each pinning
its own `jenkins.baseline` *and* its own BOM version, kept current by hand.
Testing a **higher** core version is the easy direction: raising `jenkins.version`
while keeping the 2.568.x BOM is a supported combination, because plugins built
against an older core run on a newer one.

Also relevant: there is **no `.mvn/` directory** — `extensions.xml` and
`maven.config` do not exist, so the plugin is not "incrementalified" (JEP-305).
`buildPlugin.groovy` notes that *"all plugins currently in the BOM are
incrementalified"*, so this is a prerequisite for the BOM route, and it is the
same file set that CD would need (`docs/HOSTING-READINESS.md` G10).

---

## 3. Recommendation

One recommended plan, in three time slices. Everything in **Now** is worth doing
even if the hosting request were abandoned.

### 3.1 Now, before the move to `jenkinsci` — reading (a)

**N1. Add one GitHub Actions build workflow: `.github/workflows/build.yml`.**

* Triggers: `push` on `main`, `pull_request` (all), `workflow_dispatch`.
* One job, `ubuntu-latest`, matrix `jdk: [21, 25]`, `fail-fast: false`.
* `permissions: contents: read` only. No secrets. Nothing that a fork PR cannot do.
* Steps: `actions/checkout` → `actions/setup-java` with `cache: maven` →
  `mvn -B -ntp clean verify`.
* `concurrency: group: build-${{ github.ref }}, cancel-in-progress: true` so a
  force-push supersedes the previous run instead of queueing behind it.
* All third-party actions pinned to a commit SHA with a version comment, matching
  the style already used in `jenkins-security-scan.yml`.

This is the whole of reading (a) for the pre-transfer period: 195 tests and
SpotBugs run on every pull request, including from forks, with no secrets exposure.
JDK 25 is included because it is free in parallel and
`docs/HOSTING-READINESS.md` §4 item 7 already wants forward JDK coverage.

**N2. Enable branch protection on `main`** requiring the `build` checks to pass
and the branch to be up to date before merge. A green CI that is not a required
check does not prevent anything.

**N3. Set the fork-PR workflow policy explicitly** to "Require approval for
first-time contributors" (or stricter) in repository settings, and leave
"Send write tokens"/"Send secrets to workflows from pull requests" off.

**N4. Add `docs/CI.md`-level guidance to `CONTRIBUTING.md`** — one short section:
what runs on a PR, that the e2e suite is not run per PR and why, and that a
contributor can run `mvn -ntp clean verify` locally for the same signal. (Not a
release-manager path; raised as a Request below.)

### 3.2 On reading (b) — why downward compatibility testing is the wrong target

This is the part of the proposal that pushes back.

`jenkins.version` 2.568.3 is not merely what we happen to build against — via the
generated manifest it is our **declared minimum**. The update centre will not
offer the plugin to a controller older than that, and the plugin will refuse to
load there. We therefore do not *claim* to support 2.555.x or 2.541.x, and a test
failure on those lines would not be a bug against a promise we have made. Spending
per-PR minutes, and a hand-maintained Maven profile per line (F8), to discover
failures we are not obliged to fix is a poor trade.

The risk that actually exists runs the other way: **a future core release breaking
us.** Our approval flow leans on `Queue.QueueDecisionHandler`, `ACL`/`AccessControlled`,
`Stapler` request handling and `SaveableListener` — exactly the surfaces core
refactors touch. That is worth a scheduled check, not a per-PR gate.

So the recommendation on (b) is: **do not build a downward matrix. Add one
forward-looking scheduled check, and get into the BOM when we qualify.** If the
owner wants a wider *installed base* rather than wider test coverage, the
effective lever is lowering the baseline to 2.555 (F7) — a one-line `pom.xml`
change plus a BOM version swap — not a test matrix. Those are two different
decisions and should not be conflated; see §6, D3.

### 3.3 At the `jenkinsci` transfer — what appears by itself, and what we add

Appears automatically, no action needed (F2, F3):

* ci.jenkins.io builds every branch and every pull request, including from forks.
* Linux + Windows, JDK 21, per our existing `Jenkinsfile`.
* SpotBugs "new issues" quality gate, Checkstyle, PMD, CPD, JavaDoc, ESLint,
  task scanner, JaCoCo coverage diffed against the target branch, JUnit reporting,
  `.hpi` archiving — all with `failFast` turning UNSTABLE into a failed check.

What we add at that point:

**T1. Add `jdk: 25` (Linux) to the `Jenkinsfile`** — already recommended by
`docs/HOSTING-READINESS.md` §4 item 7, allowed by the hosting checker's
`ALLOWED_JDK_VERSIONS = [21, 25]`, and the majority pattern in the survey (six of
nine repositories build JDK 25). Then **drop JDK 25 from `build.yml`** (N1) so GHA
stays a single fast pre-check and ci.jenkins.io owns the matrix.

**T2. Add `.github/workflows/close-bom-if-passing.yml`** — the three-line
reusable-workflow call used by credentials, workflow-cps and git-plugin. It needs
ci.jenkins.io `check_run` events, so it is meaningless before the transfer. This is
what makes the existing weekly dependabot BOM bumps self-clearing instead of a
manual chore.

**T3. Decide the required checks** — with two CI systems reporting, exactly one
set should gate merges. Recommendation: make ci.jenkins.io's check required (it is
the one the hosting team and the Jenkins release process look at) and keep
`build.yml` as a fast advisory pre-check that is *also* required, since it returns
in ~20 minutes regardless of Jenkins infra queue depth. See §6, D1.

**T4. Confirm the security scan's `main` branch trigger** survives the fork
(`docs/HOSTING-READINESS.md` §4 item 5) and settle the fork-PR SARIF question
(§6, D5).

### 3.4 Later, in rough priority order

**L1. A weekly forward-compatibility job** (`.github/workflows/compat.yml`):
`schedule` (weekly) + `workflow_dispatch`, one Linux/JDK 21 job running
`mvn -B -ntp clean verify -Djenkins.version=<newest LTS> -Daccess-modifier-checker.failOnError=false`
— the flag combination `buildPlugin` itself uses for version overrides (F6). Not on
pull requests: a core-side regression is never the contributor's fault and must
never block their PR. Failures open an issue for the maintainer. Optionally a
second row against the latest weekly, accepting that it will be noisier.
*Alternative placement:* the same thing can be a third row in the `Jenkinsfile`
(`[platform: 'linux', jdk: 21, jenkins: '2.580.x']`), which is one line instead of
a file — but then it runs on every PR, which is exactly what we are trying to
avoid. Prefer the scheduled workflow.

**L2. Incrementalify** (`.mvn/extensions.xml` + `.mvn/maven.config`, JEP-305).
Prerequisite for the BOM (F8) and shared with CD (`HOSTING-READINESS.md` G10), so
it pays for itself twice.

**L3. Propose adding `batch-control` to `jenkinsci/bom`.** This is the real,
permanent answer to reading (b) — the BOM's own CI then runs our tests against
every maintained LTS line and weekly. Be realistic about timing: the stated
inclusion criteria are *"in the default list of suggested plugins / top 100 (or
250) plugins / more than 10,000 (or 1,000) users"*, plus an active maintainer able
to cut releases promptly. A newly hosted plugin meets none of the popularity bars.
`bom/CONTRIBUTING.md` does allow that *"a plugin that is not critical could be
tolerated in the managed set, as long as it poses a low maintenance burden and has
an active maintainer"* — so it is worth proposing once the plugin has real users,
and not before.

**L4. The e2e suite: scheduled, never per-PR.** `e2e/` is 22 bash scripts driving
a `docker compose` Jenkins (`e2e/docker-compose.yml`, image
`batch-control-e2e-jenkins:2.568.3`) with `curl`, asserting against scraped HTML
and JSON, with ~200 artefacts in `e2e/out/` intended for human inspection. It
needs `BC_ADMIN_PASSWORD`/`BC_APPROVER_PASSWORD`/`BC_REQUESTER_PASSWORD` — which in
CI can simply be generated per run, so **no repository secret is required** and it
*could* technically run on fork PRs. It still should not:

* HTML-scraping assertions are brittle; a Jelly or Jenkins UI change reds it for
  reasons unrelated to the PR.
* An external contributor cannot diagnose a failure whose evidence is 200 HTML
  files in a runner artifact.
* Wall clock is an estimated 20–40 minutes on top of the 20-minute build.

Recommended shape if the owner wants it automated at all: `workflow_dispatch` plus
a weekly `schedule` on `main` only, uploading `e2e/out/` as an artifact, treated as
a maintainer signal. Ubuntu runners have Docker, so no self-hosted infrastructure
is needed. A reasonable alternative is to leave it exactly as it is — a manual
pre-release gate — until it is worth hardening. See §6, D4.

**L5. Not recommended, but noted:** an OWASP `dependency-check` job in the style
of `warnings-ng-plugin`. Dependabot plus the Jenkins security scan plus the
Jenkins security team's own plugin scanning already cover this, and
dependency-check needs an NVD API key, which means a secret and therefore no fork
PR support.

---

## 4. Cost and benefit

"Runner cost" is free in money for a public repository (F5); the columns that
matter are contributor wait time and our maintenance burden.

| Item | When | Wall clock added to a PR | Recurring runner time | Maintenance burden | What it buys |
|---|---|---|---|---|---|
| **N1** `build.yml`, linux × JDK 21+25 | Now | ~20 min (two jobs in parallel) | ~40 min per PR push, free | Low — one ~25-line file; action SHA bumps arrive via the existing monthly `github-actions` dependabot entry | The only regression gate that exists before the transfer. 195 tests + SpotBugs on every fork PR |
| **N2** branch protection | Now | none | none | none | Makes N1 actually block a bad merge |
| **N3** fork-PR policy | Now | none (adds a maintainer click for new contributors) | none | none | Prevents a hostile PR from editing the workflow and running it |
| **T1** `jdk: 25` in `Jenkinsfile`; drop 25 from `build.yml` | At transfer | −0 (net: GHA halves to ~20 min, one job) | ci.jenkins.io: one more configuration | Low | JDK 25 coverage on Jenkins infra; keeps GHA lean |
| **T2** `close-bom-if-passing.yml` | At transfer | none | ~1 min per dependabot BOM PR | Near zero — a 3-line reusable call | Weekly BOM bumps close themselves; plugin-dependency drift stays tested without manual triage |
| **T3** required-check decision | At transfer | none | none | none | One unambiguous merge gate instead of two ambiguous ones |
| **L1** weekly forward-compat job | Later | none (not on PRs) | ~20 min/week | Medium — the target version is a literal that must be bumped when a new LTS line opens; expect occasional real failures needing investigation | Early warning that a core release breaks us, before users hit it |
| **L2** incrementalify | Later | none | none | Low once done | Unblocks L3 and CD |
| **L3** BOM membership | Later (gated on adoption) | none | none for us | Medium — obliges us to cut releases promptly when PCT finds something | The complete answer to (b): our tests run against every maintained LTS line and weekly, on someone else's infrastructure |
| **L4** e2e weekly | Later | none | ~30 min/week | **High** — brittle HTML assertions; expect periodic triage | Catches real-Jenkins integration breakage that `JenkinsRule` cannot see |
| *rejected* full 3-OS × 2-JDK GHA matrix | — | ~35–50 min (slowest row wins) | ~6× N1 | High — Windows/macOS `JenkinsRule` flakiness lands on us, not on Jenkins infra | Nothing ci.jenkins.io does not already give us after the transfer |
| *rejected* downward LTS matrix on PRs | — | +~20 min per line | ~20 min/line/PR | **High** — one Maven profile per line, each with its own pinned BOM version to keep current | Failures on core versions we never claimed to support (§3.2) |

Assumptions behind the numbers: 195 tests ≈ 14 min locally
(`docs/HOSTING-READINESS.md` R1/G11), plus ~3–6 min for checkout, JDK setup and
dependency resolution on a warm Maven cache; `ubuntu-latest` is 4 vCPU, so a
single-fork run is comparable to or slightly slower than local. Windows runners
run this kind of workload roughly 1.5–2× slower. These are estimates, not
measurements — the first real run should be used to correct them.

---

## 5. Alternatives considered and rejected

1. **Mirror `warnings-ng-plugin`: full Maven build across ubuntu/macos/windows ×
   JDK 21/25 in GitHub Actions.** Rejected. After the transfer this duplicates
   ci.jenkins.io (F1, F2) at six times the job count, and it relocates
   Windows/macOS `JenkinsRule` flakiness from Jenkins infra onto us. It also
   makes every external PR show six checks, five of which a contributor cannot
   interpret. `warnings-ng` can afford this; we cannot yet.

2. **Per-PR matrix over older LTS lines (2.555.3, 2.541.3).** Rejected as a
   misreading of the obligation (§3.2) and expensive in exactly the way that
   matters: it needs a hand-maintained Maven profile per line pinning both
   `jenkins.baseline` and a line-specific BOM version (F8), and it produces
   failures on cores we do not claim to support. The honest lever for reach is the
   baseline itself (D3).

3. **Run Plugin Compatibility Tester ourselves.** Rejected. PCT consumes a
   "megawar" produced by `jenkinsci/bom`; standing that up per-repository
   reimplements the BOM's job. Even the BOM does not run PCT on pull requests by
   default (F6).

4. **Run the `e2e/` suite on every pull request.** Rejected — brittleness, wall
   clock, and undiagnosable-by-contributor failures (L4). Note the objection is
   *not* about secrets: passwords can be generated per run.

5. **`pull_request_target` for anything touching PR code.** Rejected on security
   grounds: base-branch context with secrets and a write token, executing
   contributor code. Fine for dependabot-scoped `auto-merge-safe-deps`; not for us.

6. **Adding `auto-merge-safe-deps.yml` now.** Deferred, not rejected. It
   auto-approves and merges dependabot updates, which presumes a CI signal
   trustworthy enough to merge unattended. Revisit after the transfer once
   ci.jenkins.io has a track record on this repository.

7. **A hard coverage threshold as a merge gate.** Rejected. `buildPlugin` already
   records coverage against a reference build and already fails on *new* SpotBugs
   findings (F2), which is the useful version of this. An absolute percentage gate
   mostly blocks legitimate PRs that touch hard-to-test code.

8. **Self-hosted runners.** Rejected: unnecessary (public repos are free) and a
   security liability on a repository that accepts fork PRs.

9. **Doing nothing until the transfer, relying on ci.jenkins.io alone.**
   Rejected. The transfer date is not under our control, the repository is already
   public, and until then a pull request gets no build at all (F3). N1 is cheap
   enough that waiting is not worth it.

---

## 6. Decisions the owner needs to make

| # | Decision | Recommendation | Why it needs the owner |
|---|---|---|---|
| **D1** | After the transfer, which checks are *required* to merge: ci.jenkins.io, `build.yml`, or both? | Both — ci.jenkins.io as the authoritative gate, `build.yml` as the fast one | Branch-protection policy; also determines whether a Jenkins-infra outage can block all merges |
| **D2** | Does `build.yml` stay after the transfer, or is it deleted once ci.jenkins.io is live? | Keep it, trimmed to Linux/JDK 21 | Eight of nine surveyed plugins would delete it. Keeping it is a deliberate deviation for fast feedback; the owner should own that deviation |
| **D3** | Keep `jenkins.version` at 2.568.3, or lower the baseline to 2.555.3 to match the documented recommendation? | Out of scope for CI — but decide it consciously, not by default | Changes the installable user base (F7). A CI matrix is not a substitute for this decision, and `docs/HOSTING-READINESS.md` §4 item 8 already flags it |
| **D4** | Automate the `e2e/` suite weekly, or leave it manual? | Leave manual until after the first release, then weekly on `main` | High maintenance burden vs. genuine value; the owner is the one who will triage the failures |
| **D5** | The fork-PR SARIF question (F4) — verify, then decide whether to narrow the security scan's `pull_request` trigger | Verify empirically on the first external PR; narrow only if it produces noise | Cannot be settled from documentation; a wrong guess either loses scan coverage or greets every contributor with a red check |
| **D6** | Does the weekly forward-compat job (L1) target the newest LTS only, or also the latest weekly? | Newest LTS only to start | Weekly adds earlier warning at the price of more false alarms the owner has to read |

---

## 7. Requests to other path owners

* **Request:** `CONTRIBUTING.md` — add a short "What CI runs on your pull request"
  section: the checks a contributor will see, that `mvn -ntp clean verify` locally
  gives the same signal, and that `e2e/` is not part of the PR gate. (Also owned by
  release-manager per `CLAUDE.md`, so this can be done in the same pass once the
  proposal is approved — listed here so it is not forgotten.)
* **Request:** `docs/DECISIONS.md` — if D1/D2/D3 are decided, they belong there as
  a numbered decision; agents may only propose.
* **Request:** `docs/STATUS.md` — record the outcome of this proposal and which of
  N1–N4 were built.
* No request touches `src/**`.

---

## 8. What this proposal deliberately does not do

No workflow file, `Jenkinsfile` change, `pom.xml` change or `CONTRIBUTING.md`
change has been made. Per the owner's instruction, implementation waits on
approval of §3. No Maven build was run while writing this; the 195-test and
14-minute figures are taken from `docs/HOSTING-READINESS.md` and the task brief,
not measured here.
