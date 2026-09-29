# Security Review 14 — re-review of the #15 fixes (`p1/activation`, 24f8104..HEAD)

Reviewer: security-reviewer (returned as text; persisted by the main session, condensed). 2026-09-29.

## Summary: BLOCKER 1 / HIGH 1 / MEDIUM 0 / LOW 1
All eleven security-13 findings are FIXED (S-13-01..11, with file:line evidence in the review).

## BLOCKER
- **S-14-01** `queue/ApprovalQueueDecisionHandler.java:194-198, 266-276`: on a job whose `approvalRequired` is false (or with no property), `hasMarker(actions) || isHumanSubmission(effective)` returns true before the activation checks, and `effective` is the retry-aware cause list that keeps the retried build's original `UserIdCause`/`ApprovedCause`. An automatic retry (naginator) of an old manual build therefore runs a non-activated job unattended; the marker is never consumed on that branch. Pinned as "expected" by `PluginInteractionNaginatorTest#t_06_35` and `PluginInteractionRebuildTest#t_06_26`. Fix: apply the retry discipline and marker consumption regardless of `approvalRequired`.

## HIGH
- **S-14-02** `action/ComputedFolderActivationAction/summary.jelly`, `action/JobActivationNoticeAction/jobMain.jelly`: the activation carrier's `fullDisplayName` and state are rendered even when the viewer lacks Item/Read on it (only the link is guarded). Reachable with folder-scoped strategies granting Read on a descendant only. Fix: show name and state only with Read on the carrier; otherwise a neutral notice.

## LOW
- **S-14-03** `listener/ActivationItemListener.java:32-43`, `policy/ActivationService.java:602-612`: a leftover state under the name of a newly created non-subject computed folder is not deleted (inert, since the gate never reads it). Fix: delete for any non-subject item.

## Checked and found to be fine
Manual builds on non-approval jobs unaffected; computed-folder ancestor walk (nested, inside plain folders); seeding and D-45 use the same subject predicate; cache generation counter and identity binding; first decision wins and `invalidatePending` under one lock; the five web methods; `ApprovalPolicy.itemForPolicy` SYSTEM2 use; list visibility.
