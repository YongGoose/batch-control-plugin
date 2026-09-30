# Security Review 32: check of part 7 (`fix/def-39-41`, head ffbf5e7)

Reviewer: security-reviewer. 2026-09-30.

Scope: `git diff main..HEAD -- src/main` (da767db, 20afb68 for DEF-40/41; 2b6fd56, ffbf5e7 for DEF-39). Standard: HOSTING-CHECKLIST section B, D-58b/D-58c, CLAUDE.md. Maven was not run and `target/` was not read, because a full build was running in the worktree. SpotBugs is **unconfirmed**.

## Summary: BLOCKER 0 / HIGH 0 / MEDIUM 0 / LOW 1 / INFO 3

The diff adds no web method, no `ACL.SYSTEM2` switch, no file path and no raw output. Hiding Rebuild does not weaken any refusal. The 403 link is gated like the page it points to. One record is still untruthful: the non-person branch of the unresolved-source refusal (S-32-01).

## BLOCKER
None.

## HIGH
None.

## MEDIUM
None.

## LOW
- [S-32-01] `queue/ApprovalQueueDecisionHandler.java:251-254`: DEF-40 made the person branch of `refuseMarkedSource` say `unresolvedSource` and "could not be identified". The non-person branch (SYSTEM, anonymous) was left alone, so it still records `switch=replayedUnderGrant` with "build #? - the run's script was replayed under a permission window" when the source is unresolved. The audit record then asserts a fact that was never established. Basis: B, record truthfulness (DEF-40 intent). Fix: use the same `unresolved ? ... : ...` choice of text and switch in `recordTriggerBlocked`. Test: Given change control is on, When a SYSTEM-authenticated submission carries a `ReplayFlowFactoryAction` and no replay cause, Then it is refused and the `TRIGGER_BLOCKED` record has `switch=unresolvedSource` and no "replayed under a permission window".

## INFO
- [S-32-02] `listener/ReplayUnderGrantListener.java:36-39`: the inherited-marker detail says the source run's "script was replayed under a permission window". In a chain (run #3 re-runs administrator re-run #2 of grant-replayed #1), #2 was not itself replayed under a window. Fix: "run #N, which carries the replay-under-grant mark". Wording only; the user id and run number are not user-controlled free text, and records render escaped.
- [S-32-03] `ui/SelfGrantRevertedFailure.java:47-55`, `SelfGrantRevertedFailure/index.jelly:21-29`: (a) For a non-Job item (a folder), the text still points to "its Batch Control page", which does not exist there. The link is correctly withheld. (b) The text says "a user with Configure permission", but `GrantService#markReviewed` needs Configure held natively, not from a window. The last sentence only partly covers this. Fix: show the job-page sentence only when the item is a `Job`, and say "Configure held directly, not from a permission window". Wording only; there is no disclosure.
- [S-32-04] `policy/ApprovalRebuildValidator.java:35`: `markedForViewer` runs before the `req == null` check, so non-web callers pay for a permission lookup and an action scan whose result is discarded. Fix: move the `req == null` return first. Cost is negligible (see below).

## Checked and found to be fine
- The refusal is unchanged. `refuseMarkedSource` runs first in `shouldSchedule` and does not depend on the validator. The validator still exempts a POST to `.../<n|lastXBuild>/rebuild(/...)` (`targetsRebuild`), so a direct call reaches the queue gate, which refuses and records it. CLI and API (no Stapler request) get `false`, so the action stays visible and the gate still refuses. Administrators are judged by effective `ADMINISTER` in both places, which is consistent.
- Switch off: `markedForViewer` returns `false` while change control is off, so validator behaviour is exactly as on main (CLAUDE.md "no change while the switch is off").
- Persisted-action read. `Run#getActions()` returns the run's own persisted list and does not consult `TransientActionFactory`, so `RebuildActionFactory` -> validator -> `getAction(Class)` cannot recurse. The queue handler's `getAction(ReplayUnderGrantAction.class)` now reaches the validator, which only calls `getActions()`, so that chain ends too. The marker is always persisted: it is added as a queue action and copied onto the build. Cost is one global config read, one ACL check and a linear scan of a few actions per page render. `getActions()` is iterated read-only (copy-on-write list), with no mutation.
- 403 escaping. Both jelly files keep `escape-by-default='true'`. `${it.itemFullName}` and `href="${rootURL}/${reviewUrl}"` are escaped, `item.getUrl()` is core's raw-encoded URL, and none of the 32 jelly files contains `<j:out` or `escapeXml="false"`. The non-HTML answer is `text/plain` with `nosniff`.
- 403 link and name disclosure. `getReviewPageUrl` resolves through `Visibility.findVisibleItem` (Item/Read, Discover-only is treated as missing) and requires `Job` plus global `BatchControl/Request`. That is exactly the gate of `JobRequestAction#getUrlName`, so the link never points to a page the viewer cannot open. The item name is shown only to the user whose POST just saved that item, who therefore holds Configure on it, so no new name is revealed.
- Monitor text (`message.jelly`): constant text only, and the page is still Administer-gated.
- Records carry no unescaped user input. The new text concatenates the job full name, the user id and run numbers. All three are rendered with escape-by-default (`ChangesSection`, `HistorySection`), and none starts a cell (text begins "Run #" / "Blocked"), so there is no CSV formula lead.
- Record truthfulness (apart from S-32-01 and S-32-02). The inherited marker names the actual submitter (`auth.getName()`) and the source run number of the same job (`sourceRun` resolves through `job.getBuildByNumber`). The person refusal now names the rule that fired (`replayedUnderGrant` or `unresolvedSource`).
- XStream: the new `Integer inheritedFrom` in `ReplayUnderGrantAction` is a boxed primitive, and old builds load as `null`, which falls back to the old wording. No dangerous type is involved.
- Web methods, `ACL.SYSTEM2`, secrets, file paths, AuthorizationStrategy and locking: not touched by this diff (grep of the diff: no `do[A-Z]`, `SYSTEM2`, `Secret`, `new File`/`resolve(`, or `synchronized`).
- SpotBugs: **unconfirmed** (Maven was not run, per the instruction).

## Request
- `src/main/java/io/jenkins/plugins/batchcontrol/queue/ApprovalQueueDecisionHandler.java` (core-dev): S-32-01; S-32-04 in `policy/ApprovalRebuildValidator.java`; S-32-02 in `listener/ReplayUnderGrantListener.java`.
- `src/main/java/io/jenkins/plugins/batchcontrol/ui/SelfGrantRevertedFailure.java` and its `index.jelly` (ui-dev): S-32-03.
