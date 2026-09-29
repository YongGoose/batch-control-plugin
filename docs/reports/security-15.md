# Security Review 15 — re-check of the security-14 fixes (`p1/activation`, 6bd6367..HEAD)

Reviewer: security-reviewer (returned as text; persisted by the main session, condensed). 2026-09-29.

## Summary: BLOCKER 0 / HIGH 0 / MEDIUM 1 / LOW 0
- **S-14-01 FIXED:** `queue/ApprovalQueueDecisionHandler.java:118-132` consumes the marker on every job; `isHumanSubmission` (`:263-276`) returns false for an automatic retry (`isAutomaticRetry`, `:279-281`). T-06-35, T-06-54, T-06-55 pin it.
- **S-14-02 FIXED:** `ComputedFolderActivationAction/summary.jelly:14-25`, `JobActivationForm/index.jelly:18,62-71`, `JobActivationNoticeAction/jobMain.jelly:20-31` gate the carrier's name and state behind Item/Read.
- **S-14-03 FIXED:** `ActivationItemListener.java:32-36`, `ActivationService.java:605-611` delete stale state for any non-subject item.

## MEDIUM
- **S-15-01** `ApprovalQueueDecisionHandler.java:263-276`: `isHumanSubmission` classifies by cause type only. A `UserIdCause` built under SYSTEM carries `userId=null`; a Replay or CLI build run as SYSTEM, or a retry-style plugin other than naginator that keeps the old user cause, is still classified human and bypasses activation on jobs that do not require approval. Needs code already running in the controller. Fix: count a cause as human only if it names a resolvable user and the submitting authentication is not SYSTEM; add a test row.
