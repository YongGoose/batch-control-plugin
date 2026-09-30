# Security Review 30: the D-58c changes (`fix/def-38`)

Reviewer: security-reviewer. 2026-09-30.

Scope: `git diff 5f076b5..HEAD -- src/main` (core add031e, ui 0a9592e). Standard: HOSTING-CHECKLIST section B; D-58c (a5d2fad) and its SPEC acceptance line; security-29.

Constraint: Maven was not run and `target/` was not read, because a full build was running in the worktree. SpotBugs is therefore unconfirmed. Nothing was reproduced. The findings come from reading the source plus the upstream classes: `jenkins-core-2.568.3-sources` (`Queue#schedule2`, `Executor`, `Jenkins#getItemByFullName`), `workflow-cps-4383-sources` (`ReplayAction`, `ReplayFlowFactoryAction`, `ReplayCause`), and `pipeline-model-definition 2.2293.v6e7193cec599` (`javap` of `RestartDeclarativePipelineCause` and `RestartDeclarativePipelineAction`).

## Summary: BLOCKER 1 / HIGH 0 / MEDIUM 1 / LOW 4 / INFO 5

S-29-01 is closed for Replay, Pipeline Rebuild and rebuild-plugin Rebuild. It is **not** closed for Restart from Stage (S-30-01). An administrator's re-run of a marked run also drops the marker (S-30-02). The marker cannot be removed or avoided through REST, copy, rename, move or Replay-of-Replay. Run ids are escaped. Every `do*` method still has POST and a permission check.

## security-29 closure

| Finding | State | Basis |
|---|---|---|
| S-29-01 | partly closed | There is now a marker, a `REPLAY_UNDER_GRANT` record and a listing on both surfaces. The refusal works for Replay, Pipeline Rebuild and RebuildCause. It is a no-op for Restart from Stage (S-30-01), and an admin re-run launders the marker (S-30-02). |
| S-29-02 | narrowed | Each descendant is checked with `mayReview`. But the descendant is resolved as the reviewer, so an unreadable one counts as "gone" and is cleared (S-30-03). |
| S-29-03 | closed | `removeChanged` counts removals, the record is written only when `cleared > 0` and says whether the item is still guarded, and `isShowMarkReviewed` asks with grants off. Residual (INFO): a store failure is still logged and swallowed, and the button still shows when only an ancestor is changed (the click is then a no-op without a record). |
| S-29-04 | service closed, UI open | `doMarkReviewed` clears a listed name that no longer resolves. `message.jelly` still renders such a name without a button (S-30-06). |
| S-29-05 | closed, with a regression | No redundant mark is made under a changed parent. See S-30-04. |
| S-29-06 | closed for marking | `ReplayFlowFactoryAction` is matched too. The Restart match is still by cause name only (see S-30-01). |
| S-29-08 | closed | The notice shows only when the job is not changed, and the block is hidden while change control is off. |
| S-29-09 | closed | `HttpResponses.notFound()`, with nothing reflected. |

## BLOCKER

- **[S-30-01] `ApprovalQueueDecisionHandler.java` `sourceRun` (the `RESTART_CAUSE_CLASS` branch): the Restart from Stage source is never resolved, so the D-58c refusal does not apply to it.** `RestartDeclarativePipelineCause` has no `getOriginalNumber`. Its getter is `getOriginRunNumber()`. Its `getOriginal()` dereferences the transient `run` field, which `onAddedTo` sets only once the cause is attached to a Run. `RestartDeclarativePipelineAction` builds the cause and calls `scheduleBuild2` straight away, so at `shouldSchedule` time `run` is null. `getOriginal()` then throws an NPE, `invokeQuietly` returns null, and `refuseMarkedSource` returns false. Any Item/Build holder can therefore restart a stage of a marked run after the review. That includes the former grant holder, since `RestartDeclarativePipelineAction` checks only `Item.BUILD`. The restart re-executes the replayed script, including top-level code such as `properties([authorizationMatrix(...)])`, and nothing guards the item any more. This is the exact S-29-01 escalation. The marking side is unaffected, because it matches the cause by class name. T-02-75/76 exercise Replay only, which is why this was not caught. Basis: B (privilege escalation; D-58c, SPEC D-58c line "Restart from Stage ... is refused"). Fix direction: for `RESTART_CAUSE_CLASS`, read `getOriginRunNumber` (keep `getOriginalNumber` for `ReplayCause`). As a second source, read the `originRunId` of `RestartFlowFactoryAction` in the submission's actions (`Run.fromExternalizableId`). Fail closed: when a non-administrator's submission carries a re-run cause or flow action that is recognised but whose source cannot be resolved, refuse and record it instead of passing. Test (add `pipeline-model-definition` in test scope): *Given* bob replays Declarative job `D#1` under a CONFIGURE window as `D#2`, the window ends and an administrator marks `D` reviewed; *when* alice (Item/Build) posts Restart from Stage of `D#2`; *then* no build is scheduled, alice gets the plain message, and a refusal record names `D#2`.

## MEDIUM

- **[S-30-02] `ApprovalQueueDecisionHandler#markReplayUnderGrant` / `refuseMarkedSource`: the marker is not carried on to re-runs, so an administrator's routine re-run of a marked run launders the script.** Administrators are exempt from the refusal, as SPEC requires. But the new run is marked only when the submitter is a grant-only user. An administrator who clicks Pipeline Rebuild or Restart from Stage on a marked run (neither shows the script), for example to re-run a failed nightly, gets a new run with the same replayed script and no marker. After the review, any Item/Build holder can re-run that new run, and the planted script runs unguarded. Nothing warns the administrator. The marker is an `InvisibleAction`, the run page shows nothing, and both listings disappear once the item is reviewed. D-58c's own rationale (the replayed script is not what a reviewer sees) applies to these clicks too. Basis: B (privilege escalation that needs an uninformed administrator action; D-58c). Fix direction: when the source run is marked, add a `ReplayUnderGrantAction` to the new run whoever submits it. Keep the original user and grant, and add the propagating user. Write a record for it. Optionally, show a visible badge on marked runs to users who may re-run. Propagating the marker extends the SPEC line, so it needs a DECISIONS note (Request below). Test: *Given* `J#2` is marked; *when* an administrator uses Pipeline Rebuild of `J#2` (giving `J#3`), then alice (Item/Build) rebuilds `J#3`; *then* `J#3` carries the marker and alice's submission is refused and recorded.

## LOW

- **[S-30-03] `GrantService#markReviewed` resolves the entries below the item with `Jenkins.getItemByFullName`, which returns null when the caller lacks Item/Read (`Jenkins.java:3165`), and `below == null` counts as clearable.** A reviewer with native Configure on `F` and no Read on a non-inheriting `F/J` (for example, one whose Read on it came only from the ended grant) clears `F/J`. That is the self-review S-29-02 was meant to close. It is unreachable today for the same reason S-29-02 was: the non-admin entry point is attached to `Job`s only. Fix direction: resolve the item without the viewer's ACL filter (inside `ACL.as2(ACL.SYSTEM2)`, with the reason in a comment, after the reviewer's own check), then apply `mayReview(below, auth)`. Clear an unresolvable name only for an administrator. Test: *Given* `F/J` does not inherit, carol has native Configure on `F` only and no Read on `F/J`, and `F/J` is changed; *when* `markReviewed(F)` runs as carol; *then* `F/J` stays changed.
- **[S-30-04] `GrantViolationGuard` (the S-29-05 skip): an item created under a grant inside a changed folder gets no entry of its own, so it loses its guard when moved out.** `relocateChanged` moves only the entries at or below the moved name. It re-marks only for grants that are still active and cover the old name. If the creating grant has ended, moving `F/new` out of `F` leaves it unguarded, and its content was written entirely under the grant. Fix direction: in `relocateChanged`, when the old location had a changed ancestor and the new one does not, mark the new name with that ancestor's grant id. Alternatively, skip only when the ancestor is marked by the same grant, and keep the entry otherwise.
- **[S-30-05] `GrantService#markedRuns` cost and completeness.** A cache miss scans up to 100 builds per job, loading each `build.xml`. A folder entry scans up to 50 jobs. The monitor calls it for each of up to 50 listed items, so the first render can load up to 250,000 runs. `computeIfAbsent` holds a `ConcurrentHashMap` bin lock during that disk I/O, so `noteMarkedRun` from `RunListener#onStarted` for a key in the same bin blocks a build start. The cache is never evicted on delete or rename, so a recreated job with the same name lists stale ids. The listing silently omits marked runs older than 100 builds, or in the 51st job and beyond. The refusal is unaffected, because it reads the run's own marker. Fix direction: put a global budget on each render (for example 1,000 runs) and show "more may exist" when it is hit. Scan outside `compute` (`get`, then scan, then `putIfAbsent`). Evict on `ItemListener#onDeleted`/`onLocationChanged` and on `RunListener#onDeleted`.
- **[S-30-06] `BatchControlStrategyMonitor/message.jelly`: a listed name that no longer resolves still gets no "Mark as reviewed" button** (S-29-04 UI part). The endpoint now accepts it, so only a hand-made POST can clear it. Fix direction: render the same form in the `<j:otherwise>` branch.

## INFO

- **[S-30-07]** `refuseMarkedSource` exempts administrators with the grant-inclusive `hasPermission2(ADMINISTER)`. This is safe, because no grant confers Administer (`GrantAwareACL` l.284). For consistency with `mayReview`, `GrantLayer.hasPermissionWithoutGrants` is preferable.
- **[S-30-08]** An anonymous user with Item/Build who re-runs a marked run is treated as not a person: the refusal is quiet (`TRIGGER_BLOCKED`, rate-limited) and shows no message. `isHumanSubmission` elsewhere counts anonymous as a person.
- **[S-30-09]** A rebuild-plugin Rebuild of a marked run is refused, although it runs the saved configuration: `ReplayFlowFactoryAction` nulls its script after use and is not copied. This matches the SPEC. It over-blocks, harmlessly.
- **[S-30-10]** `refuseMarkedSource` may load the source run from disk (`getBuildByNumber`) under the Queue lock. This happens only for submissions that carry a re-run cause, and loads one run. Same shape as S-29-07.
- **[S-30-11]** A replayed script can remove its own marker only through `currentBuild.rawBuild`, which needs a script approval equivalent to administrator rights. Runs replayed before D-58c carry no marker (pre-release data).

## Marker removal and avoidance (checked)

- REST or UI edit of `build.xml`: there is no run `config.xml` endpoint. A non-admin can change only the description, display name and keep-log flag, or delete the run. Deleting it removes the run itself.
- Copy job: builds are not copied. Rename or move: builds move with the job, and the source is looked up by number in the current job (`ReplayCause#getOriginalNumber`, `RebuildCause#getUpstreamBuild`).
- Replay-of-Replay and Pipeline Rebuild: the new `ReplayCause` names the marked run, so both are refused. A copied `ReplayCause` in a Rebuild is ignored, because the last re-run cause is the one used.
- Restart from Stage of an unmarked run whose own source is marked: this happens only when an administrator made that run. See S-30-02. Restart from Stage of a marked run: S-30-01.
- Delivery: `Queue#schedule2` copies the list before the handlers run, so `actions.add` sticks. `ReplayFlowFactoryAction#shouldSchedule` returns true (no coalescing). `Executor` copies the queue actions onto the run, and `InvisibleAction` is persisted.
- Change control off: the refusal is off by design (CLAUDE.md global switch). The markers remain.

## Legitimate use

The refusal applies only when the source run of this submission carries the marker. Administrators and SYSTEM pass. Unrelated runs are not affected: timers, upstream triggers, Build Now, and re-runs of unmarked runs never reach it. A non-admin cannot restart a failed stage of a marked run, as the SPEC requires.

## Checked and found to be fine

- B-1/2 web methods: all 45 `public ... do*` were listed. Every state-changing one has `@RequirePOST`/`@POST` and a permission check first. The ones without POST are GET-only (`refuseNonGet`), a servlet filter, or `PeriodicWork#doRun`. The diff adds no `do*`. It changes `BatchControlStrategyMonitor#doMarkReviewed`, which still starts with `checkPermission(ADMINISTER)`. The stale path clears only a name that is currently listed.
- Escaping: both Jelly files are `escape-by-default='true'`, and `${r.id}` is escaped. `RunLinks.runUrl` applies `Util.rawEncode` to each segment, and the number is parsed as an int. B-4 grep for `escapeXml="false"|<j:out`: 0 hits.
- Information disclosure: the job page lists marked runs only inside `showMarkReviewed` (native Configure or Administer). `getMarkedRuns` can be reached through Stapler, but `Row` has no view and no `@Exported`, so the answer is 404. `BatchControlRootAction#markedRuns(String)` is not a Stapler route and is used only by the Administer-only monitor.
- `ACL.SYSTEM2`: the diff adds only an equality test (`person`). There is no switch to SYSTEM.
- Secrets, paths, CSV: untouched (grep over the diff).
- XStream: `ReplayUnderGrantAction` holds two `String`s. `REPLAY_UNDER_GRANT` is a new enum constant. There is no new file format (ARCHITECTURE: the marker lives in `build.xml`).
- Concurrency: `markReviewed` resolves and checks permissions outside the `GrantService` monitor, then `removeChanged` runs synchronized. A mark that lands in between for an already-listed name is removed with it, which is the same effect as before. No new lock order was found.
- SpotBugs (B-15): unconfirmed (Maven not run, `target/` not read).

## Request: src/main/java/io/jenkins/plugins/batchcontrol/queue/** (core-dev) S-30-01 (`getOriginRunNumber`, `RestartFlowFactoryAction`, fail closed), S-30-02 (propagate the marker once decided).
## Request: src/main/java/io/jenkins/plugins/batchcontrol/{security,listener}/** (core-dev) S-30-03, S-30-04, S-30-05.
## Request: src/main/resources/** (ui-dev) S-30-06; optional visible badge on marked runs (S-30-02).
## Request: docs/DECISIONS.md (humans) S-30-02: a re-run of a marked run is itself marked, whoever submits it.
## Request: src/test/** (test-author) the S-30-01 row with the real pipeline-model-definition plugin; the S-30-02 and S-30-03 rows as given above.
