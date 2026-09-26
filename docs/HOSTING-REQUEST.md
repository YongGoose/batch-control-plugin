# jenkinsci Hosting Request — Submission Procedure

This is the **run book**: what to do, in what order, on the day the hosting
request is actually filed. It is not the readiness report — whether we *meet* the
requirements is answered in [`HOSTING-READINESS.md`](HOSTING-READINESS.md), and
nothing from that document is repeated here except where it must be pasted into a
form.

| | |
|---|---|
| Written / all URLs re-verified | **2026-09-26** |
| Repository to submit | `https://github.com/YongGoose/batch-control-plugin` (default branch `main`) |
| Target repository name | `batch-control-plugin` |
| Automated release (form field 6) | **No** (owner decision, 2026-09-26) |
| Readiness verdict at that date | 0 `FAIL`; the only open rows are the two that need the owner — see readiness §5 |

Everything marked `<OWNER: …>` is a value only the owner can supply. Everything
else is a confirmed value or a confirmed procedure; anything that could not be
confirmed is marked **unverified** in place.

---

## Step 0 — Owner prerequisites (do these days before, not on the day)

The accounts.jenkins.io account exists. What the bot checks is **not** that the
account exists, but that it has *signed in* to two separate systems. From
`JenkinsProjectUserVerifier.java`, both failures are `REQUIRED`, i.e. they block
approval:

> "The following usernames in 'Jenkins project users to have release permission'
> need to log into [Artifactory](https://repo.jenkins-ci.org/): … (reports are
> re-synced hourly, wait to re-check for a bit after logging in)"

and the same sentence again for [Jira](https://issues.jenkins.io).

- [ ] Sign in at <https://issues.jenkins.io> with the accounts.jenkins.io
      credentials, at least once, and confirm you reach a logged-in page.
- [ ] Sign in at <https://repo.jenkins-ci.org/> with the same credentials,
      separately. One login does **not** cover the other.
- [ ] Write down the **exact username string** as accounts.jenkins.io shows it.
      It goes in field 5 **without** a leading `@`, and it is unrelated to the
      GitHub handle.

Notes worth knowing:

- The Artifactory half is only checked when field 6 is `No`
  (`if (!request.isEnableCD() && …)`). We answer `No`, so it **is** checked.
- The source says reports re-sync **hourly**. Doing both logins a day or more
  ahead removes the timing question entirely; doing them minutes before
  submitting will produce a `REQUIRED` finding that then clears itself on the
  next re-check.
- The same Artifactory login is checked a *second* time, after approval, on the
  auto-created permissions PR ("The user(s) listed in the permissions file may
  not have logged in to Artifactory yet, check the PR status").

---

## Step 1 — Last checks on the submission day

Only things that can have changed since the readiness pass. Do not re-walk the
checklist.

- [ ] **Plugin BOM is the current latest.** The bot fetches the latest released
      BOM live and raises `REQUIRED` if ours is behind: "The bom version `%s` of
      `%s` should be updated to the latest version `%s`". This is a moving
      target — the BOM was last released the day before this document was
      written.
      ```bash
      curl -s https://repo.jenkins-ci.org/artifactory/public/io/jenkins/tools/bom/bom-2.568.x/maven-metadata.xml | grep -E '<latest>|<release>'
      ```
      Compare with `<version>` of `bom-${jenkins.baseline}.x` in `pom.xml`.
      On 2026-09-26 both were `7093.v37de7b_4a_8a_4f` — in sync.
- [ ] **Parent POM is not behind.** Same idea, lower stakes (the bot only
      enforces a *minimum*, currently `6.2211.v27f680c93c53`), but a stale parent
      invites a human comment.
      ```bash
      curl -s https://repo.jenkins-ci.org/artifactory/public/org/jenkins-ci/plugins/plugin/maven-metadata.xml | grep -E '<latest>|<release>'
      ```
      On 2026-09-26 both were `6.2236.v12dd4c483242`, matching `pom.xml`.
- [ ] **The build is green on the final tree**, after any version bump above:
      `mvn -q clean verify` — tests pass and SpotBugs reports no bugs. A version
      bump changes the dependency tree, so a build from before the bump does not
      count.
- [ ] **The Jenkins Security Scan workflow is green on `main`.**
      `.github/workflows/jenkins-security-scan.yml` runs on pushes to `main`;
      check the Actions tab after the push in step 2. Note that
      **ci.jenkins.io does not build this repository yet** — it only starts once
      the repository is in `jenkinsci`, so the `Jenkinsfile` is not exercised by
      anything before then.
- [ ] **`LICENSE` and `README.md` are on the default branch of the public
      repository.** The bot does not read the files; it asks the GitHub API
      (`repo.getLicense()`, `repo.getReadme()`). Both return nothing for a file
      that exists only on a feature branch or only locally, and each produces a
      `REQUIRED` finding. Verify from outside the working copy:
      ```bash
      curl -s https://api.github.com/repos/YongGoose/batch-control-plugin | grep -E '"license"|"default_branch"|"fork"'
      curl -s -o /dev/null -w '%{http_code}\n' https://api.github.com/repos/YongGoose/batch-control-plugin/readme
      ```
      `license` must not be `null`, `fork` must be `false`, and the readme
      request must return `200`.
- [ ] **No `jenkinsci` fork of this repository exists, and this repository is not
      itself a fork of a `jenkinsci` one.** Both are `REQUIRED` findings. True
      today; it only becomes false if someone forks it in the meantime.

---

## Step 2 — Push everything to the default branch

The bot reads **only the default branch** of the repository named in field 1.
Nothing may remain on a feature branch.

```bash
git switch main
git merge --ff-only <the branch being submitted>
git push origin main
```

Two of the checks above (GitHub's license detection, and Dependabot actually
activating) only become true after this push. Re-run the two `curl` commands in
step 1 *after* pushing, not before.

---

## Step 3 — Open the hosting request

Open the template directly:

<https://github.com/jenkins-infra/repository-permissions-updater/issues/new?template=1-hosting-request.yml>

Six fields, all required. Paste the blocks below.

### Field 1 — Repository URL

```
https://github.com/YongGoose/batch-control-plugin
```

No trailing `.git` and no `http://` — each is its own `REQUIRED` finding.

### Field 2 — New Repository Name

```
batch-control-plugin
```

Must equal the `artifactId` plus `-plugin`, all lowercase, hyphens, no
"jenkins". `batch-control` + `-plugin` satisfies all of it.

### Field 3 — Description

The template asks two things in one box: what it is, and "how it's different
from other components that may be considered similar to this one". The second
half is what a human reviewer reads first.

```
Batch Control adds run approval, just-in-time job-change permissions, and an
append-only audit history to Jenkins instances that are operated as a batch
execution manager rather than as a CI server.

Three capabilities, all off by default — installing the plugin changes nothing
until an administrator enables each switch independently:

1. Run approval. A manual run of a protected job becomes a request, and the job
   does not start until a registered approver approves it. Timer and upstream
   triggers can be blocked separately, with an allow-list of upstream jobs.
2. Just-in-time change permission. Changing a protected job's configuration
   requires an approved, time-boxed grant scoped to a set of actions. When the
   window expires the permission disappears, with no administrator having to
   revoke it.
3. Audit history. Every run, every configuration change, every approval
   decision and every failure is recorded independently of build-log retention,
   and failures are raised as incidents that have to be closed.

Five permissions (BatchControl/Request, Approve, RequestGrant, ViewHistory,
Manage) are exposed to the matrix- and role-based authorization strategies.

How this differs from similar components:

- Pipeline `input` step — a gate inside a run that has already started, usable
  only from Pipeline, with no record that outlives the build. Batch Control
  gates the start of a run for any job type and keeps the record independent of
  build retention.
- Job StrongAuthSimple — unmaintained for well over a decade, written against
  Jenkins 1.x, with no Pipeline support, no audit history and no time-boxed
  permissions. There is no code worth adopting, which is why this is a new
  plugin rather than an adoption request.
- Audit Trail / Audit Log — they record what happened. They have no concept of a
  request, an approver, an approval decision, or a permission that expires.
  Batch Control is a control plane whose audit history is a by-product.
- Matrix / Role Strategy authorization — static permissions. Batch Control wraps
  the configured strategy to add permissions that exist only inside an approved
  window, and delegates every other decision to it.
- Commercial batch schedulers — this is the gap the plugin targets: teams
  already running their batch workload on Jenkins have no free-software option
  for approval and audit.

Tested with JenkinsRule integration tests plus a Docker-based end-to-end suite.

<OWNER: optionally one sentence on real-world use — see readiness Q7. Delete
this line if you do not want to disclose it.>
```

Before pasting, re-read the four comparison claims. They come from internal
design notes (`DECISIONS.md` D-01/D-02), not from a fresh survey, and a reviewer
who maintains one of those plugins will notice a stale claim. The
"unmaintained for well over a decade" phrasing above is deliberately looser than
the exact figure in the internal note, which was not re-verified.

### Field 4 — GitHub users to have commit permission

```
<OWNER: GitHub handles, with @, one per line. Almost certainly just @YongGoose.>
```

Each must be a real GitHub **user** account — an organization here is a
`REQUIRED` finding. These handles become maintainers of the
`batch-control-plugin Developers` team created at approval time.

### Field 5 — Jenkins project users to have release permission

```
<OWNER: accounts.jenkins.io username(s), one per line, NO @ prefix. This is the
identity from step 0, not the GitHub handle.>
```

The template itself says the listed users "must NOT be mentioned" — do not write
them as `@name`. An empty list is a `REQUIRED` finding.

### Field 6 — Automated release via GitHub Actions (recommended)

```
No
```

Decided by the owner on 2026-09-26. Answering `Yes` instead would switch on an
extra block of `REQUIRED` checks (a `${changelist}` version, `.mvn/` files, a CD
workflow) that this repository does not satisfy today — see readiness G10. CD can
be enabled later by a follow-up PR to repository-permissions-updater.

---

## Step 4 — What happens after you submit

1. **The bot comments**, opening with "Hello from your friendly Jenkins Hosting
   Checker". If it found nothing, it says so and labels the issue
   `bot-check-complete`.
2. **If it found something**, each line is prefixed `Required`, `Warning` or
   `Info`, and the issue is labelled `needs-fix`. Only `Required` blocks:
   "Your hosting request will not be approved until these issues are corrected.
   Issues marked with Warning or Info are just recommendations and will not stall
   the hosting process."
3. **Fix, then re-trigger.** Either edit the issue body, or post a comment whose
   entire body is exactly:
   ```
   /hosting re-check
   ```
   The workflow condition is an exact string comparison
   (`github.event.comment.body == '/hosting re-check'`), so **any extra word or
   trailing sentence in that comment means nothing runs**. Post explanations as a
   separate comment. A 👍 reaction on your comment is the acknowledgement that
   the job started.
   If the finding was a Jira/Artifactory login, wait out the hourly re-sync
   before re-checking.
4. **Then a human reviews.** The bot's own words: a member of the hosting team
   "will check over things that I am not able to check (code review, README
   content, etc)". Expect questions on the README and on the differentiation in
   field 3 — that is the part no automated check covers.
5. **Approval** is a hosting-team member commenting `/hosting host`. Only members
   of the `hosting` team can use it; anyone else gets a 👎 and a refusal comment.
   You cannot approve your own request.

---

## Step 5 — After approval

The `/hosting host` run does a lot automatically. Check what it did rather than
redoing it.

**Done for you, by the bot:**

- The repository is forked into `jenkinsci` and renamed, ending up at
  `https://github.com/jenkinsci/batch-control-plugin`.
- On the fork: issues enabled, wiki disabled, homepage set to
  `https://plugins.jenkins.io/batch-control/`, and two autolinks created —
  `JENKINS-` and `SECURITY-`. The "configure Jira autolink references" step in
  the jenkins.io hosting page is therefore already done; just verify it.
- A team `batch-control-plugin Developers` is created with **admin** on the
  repository, containing the field-4 handles.
- A PR is opened against repository-permissions-updater adding
  `permissions/plugin-batch-control.yml` (name `batch-control`, github
  `jenkinsci/batch-control-plugin`, path `io/jenkins/plugins/batch-control`, the
  field-5 developers, issues → GitHub). **Releasing is not possible until that PR
  is merged.**
- The hosting issue is closed.

**Yours to do, in this order:**

- [ ] **Accept the invitation to the `jenkinsci` organization**
      (<https://github.com/jenkinsci>). Do this first: the code that builds the
      team can only make you a *maintainer* of it if you are already an org
      member, so accepting late can leave you as a plain member of your own
      plugin's team. Check the team afterwards.
- [ ] **Check the permissions PR** for your accounts.jenkins.io username, and
      watch its status — it fails if that user has still not logged into
      Artifactory. Add any further release users by editing the file on that PR.
- [ ] **Salvage anything you need out of the original repository before deleting
      it.** This is irreversible and there is no migration path:
      - GitHub only transfers issues "between repositories owned by the same user
        or organization account". `YongGoose` and `jenkinsci` are different
        owners, so **issues cannot be transferred**; they are destroyed with the
        repository.
      - Pull requests cannot be transferred at all. Open PRs must be re-opened
        by hand against the `jenkinsci` repository from a fresh fork.
      - Anything else living only in the GitHub UI — releases, wiki, Actions
        history, Discussions — goes too. Only git history and files survive, via
        the fork.
- [ ] **Delete the original repository.** The bot's comment links straight at it:
      `https://github.com/YongGoose/batch-control-plugin/settings?confirm_delete=yes`,
      under Danger Zone. The purpose is that "the jenkinsci organization
      repository is the definitive source for the code".
      **If other forks of your repository exist**, do *not* delete it — instead
      use **"Leave fork network"** in the Danger Zone of the *new jenkinsci*
      repository. Check for forks first:
      ```bash
      curl -s https://api.github.com/repos/YongGoose/batch-control-plugin | grep '"forks_count"'
      ```
- [ ] **Re-point the local clone** (do this before deleting, so you notice a
      mistake while the old remote still exists):
      ```bash
      git remote set-url origin https://github.com/jenkinsci/batch-control-plugin.git
      git remote -v
      git fetch origin && git status
      ```
      Also update any remaining hard-coded `YongGoose` URLs in the repository —
      `pom.xml` and the SCM block already use the `jenkinsci` form, so this is
      mainly docs and workflow badges.
      ```bash
      grep -rn "YongGoose" --exclude-dir=.git .
      ```
- [ ] **Confirm ci.jenkins.io picked the repository up** and that the
      `Jenkinsfile` build is green on both linux and windows JDK 21. This is the
      first time that file is ever executed.
- [ ] **Add GitHub topics** for the plugin site. Only topics on the
      [allowed-github-topics allowlist](https://github.com/jenkins-infra/update-center2/blob/master/resources/allowed-github-topics.properties)
      are shown as plugin labels.
- [ ] **Release**, following
      <https://www.jenkins.io/doc/developer/publishing/releasing/>. The plugin
      site picks the release up and renders `README.md` as the landing page,
      which "can take up to a few hours".

---

## Step 6 — Regressions to watch for

Every one of these passes today and every one is a `REQUIRED` finding if a later
change reintroduces it. Worth a glance before any push to `main`, and before a
re-check.

| Do not | Bot's words | Why it is tempting |
|---|---|---|
| Re-add `<developers>` to `pom.xml` | "Please remove the `developers` tag from your pom.xml. This information is fetched from this repository on the update site." | Every Maven tutorial has one, and IDE POM generators add it back. |
| Put a `<version>` on a plugin dependency the BOM already manages | "The dependency `%s` is covered by the bom. The version should be removed." | The obvious fix when a dependency resolves to an unexpected version. |
| Let the BOM version drift behind the latest release | "The bom version `%s` of `%s` should be updated to the latest version `%s`" | It needs no action from us to go stale — it is compared against whatever is newest at check time. |
| Change the `Jenkinsfile` JDK away from 21 or 25 | allowed list is exactly `List.of(21, 25)` | A newer or an older JDK both look reasonable in isolation. |
| Drop `jenkins.version` below the floor (currently `2.541.3`) or stop deriving the BOM artifactId from `jenkins.baseline` | "please update `<jenkins.version>…` to at least …" / "Please define the property `jenkins.baseline` and use this property in `<jenkins.version>${jenkins.baseline}.3</jenkins.version>` and the artifactId of the bom." | Pinning a baseline by hand during a debugging session. |
| Add a dependency that has an API-plugin replacement | "The dependency `%s` should be replaced with a dependency to the api plugin `%s`" — the list is [`banned-dependencies.lst`](https://github.com/jenkins-infra/repository-permissions-updater/blob/master/banned-dependencies.lst) | Adding a plain library dependency is the path of least resistance. |
| Commit anything under `target/` or `work/` — ever, in any commit | "Please remove the `target` folder and also rewrite the git history to never have contained any files in that folder." | One `git add -A` with a broken `.gitignore` is enough, and the only fix is history rewriting. The history is clean as of 2026-09-26. |
| Loosen the four enforcement properties in `pom.xml` (`ban-commons-lang-2.skip`, `ban-deprecated-stapler.skip`, `ban-junit4-imports.skip`, `banObsoleteDependencyOverrides.skip`, all `false`) | — | Flipping one back to `true` is the fastest way to make a red build green. It also removes the guard that the bot's advice is enforced locally. |

---

## Sources

All fetched or re-fetched on **2026-09-26**.

| What | URL |
|---|---|
| Hosting process, and the post-approval step list | <https://www.jenkins.io/doc/developer/publishing/requesting-hosting/> |
| The six form fields, verbatim | <https://github.com/jenkins-infra/repository-permissions-updater/blob/master/.github/ISSUE_TEMPLATE/1-hosting-request.yml> |
| Bot comment wording, severity semantics, `/hosting re-check`, `/hosting host` | `.../hosting/HostingChecker.java` |
| Jira + Artifactory login requirement, hourly re-sync, CD exemption | `.../hosting/JenkinsProjectUserVerifier.java` |
| License / README / fork / `target`,`work` checks | `.../hosting/GitHubVerifier.java` |
| POM checks: developers, BOM version, managed dependency versions, baseline, banned dependencies | `.../hosting/MavenVerifier.java` |
| Allowed JDKs, minimum parent POM and Jenkins version | `.../hosting/Requirements.java` |
| Exact-string trigger for `/hosting re-check` | `.github/workflows/hosting-comment-checker.yml` |
| `/hosting host` restricted to the `hosting` team | `.github/workflows/hosting-comment-hoster.yml` |
| What approval actually does: fork, rename, team, autolinks, homepage, permissions PR, delete-original wording, "Leave fork network" | `.../hosting/Hoster.java` |
| Issues transfer only within the same owner | <https://docs.github.com/en/issues/tracking-your-work-with-issues/administering-issues/transferring-an-issue-to-another-repository> |
| Plugin site landing page, GitHub topics as labels, allowlist file | <https://www.jenkins.io/doc/developer/publishing/documentation/> |
| Latest parent POM / plugin BOM | `https://repo.jenkins-ci.org/artifactory/public/org/jenkins-ci/plugins/plugin/maven-metadata.xml`, `.../io/jenkins/tools/bom/bom-2.568.x/maven-metadata.xml` |
| Releasing a plugin | <https://www.jenkins.io/doc/developer/publishing/releasing/> |

The `.../hosting/*.java` paths are under
`jenkins-infra/repository-permissions-updater/src/main/java/io/jenkins/infra/repository_permissions_updater/`
on `master`.

### Unverified

- **How long a human review takes.** The documentation says only "within a few
  days". No service level was found.
- **Whether the hosting team ever asks for changes after `/hosting host`.**
  Nothing in the sources describes a path back from a closed, hosted request.
- **Transferring pull requests.** GitHub's documentation covers issues only and
  says nothing about pull requests; no mechanism was found. Treated above as
  "not possible", which is the safe reading but is not a quoted statement.
- **Whether deleting the original repository breaks anything on the jenkinsci
  fork.** The bot instructs you to delete it, so presumably not, but no source
  was found that states it explicitly for a fork whose parent is gone.
