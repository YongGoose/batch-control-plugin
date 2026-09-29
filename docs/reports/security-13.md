# Security Review 13 — #15 activation approval (`p1/activation`)

Reviewer: security-reviewer (returned as text; persisted by the main session, condensed). 2026-09-29. Paths under `src/main/java/io/jenkins/plugins/batchcontrol/`.

## Summary: BLOCKER 1 / HIGH 2 / MEDIUM 3 / LOW 5

## BLOCKER
- **S-13-01** `queue/ApprovalQueueDecisionHandler.java:106`: `if (property == null || !property.isApprovalRequired()) return true;` runs before the timer and upstream steps, so a Configure holder who clears `approvalRequired` (and the block switches) or removes the property puts a job into service with no ACTIVATE request, surviving the window's expiry. Fix: apply activation to unattended causes of every non-computed job while run control is on, regardless of `approvalRequired` (needs a decision; amends T-06a-07 / note 91).

## HIGH
- **S-13-02** `queue/ApprovalQueueDecisionHandler.java:225-235`: SCM and unclassified causes skip the activation gate (SCM polling, push webhooks, possibly Generic Webhook Trigger, possibly parameterized-scheduler) — an unattended start path for a new job. Matches SPEC 6a's "timer and upstream" wording; a scope gap. Fix: activation for every non-human cause.
- **S-13-03** `policy/ActivationService.java:593-595`, `ApprovalQueueDecisionHandler.java:246`: a Create holder creates a multibranch project whose Jenkinsfile has `triggers { cron(...) }`; every branch job is an exempt computed child and runs indefinitely. A non-computed job cannot be placed into a computed folder. Fix: activation on the nearest ComputedFolder ancestor.

## MEDIUM
- **S-13-04** `ActivationService.java:90-108, 597-605, 512-524`: `isActivated` fills the cache after an unlocked read, so a HOLD or delete in between leaves a stale `true`; `onJobCreated` clears the cache only when a state file exists, so a re-created job inherits it. Fix: always reset on create; race-safe fill (generation counter).
- **S-13-05** `ActivationService.java:602-605, 519-522, 483`, `listener/ActivationItemListener.java:35-66`: a failed state-file delete keeps the file and the cached `true` (fails open). Fix: reset the cache first; on failure write an explicit not-activated state.
- **S-13-06** `ActivationService.java:554-584, 512-524`, `ops/ActivationSeeding.java:30-39`: jobs created under run control have no persisted state, so a retried seeding (after a failure before the marker, or a deleted marker) activates them as `upgrade`. Fix: persist an explicit `activated=false` at creation.

## LOW
- **S-13-07** `ActivationService.java:187-278`: a stale pending ACTIVATE can undo an approved HOLD. Fix: invalidate the job's other pending requests on approval.
- **S-13-08** `ops/MailNotifier.java:89-125`, `NotificationDispatcher`: notifications do not say ACTIVATE or HOLD.
- **S-13-09** `store/FileStore.java` (loadActivationState), `ActivationItemListener`: activation bound to the full name only; missed item events or a restored directory let a new job inherit an old activation. Fix: record an identity config cannot write (root dir creation time).
- **S-13-10** `ActivationService.java:526`, `model/ActivationState.java:22,25`: an `uncontrolled` activation writes no record when change control is off; `upgrade`/`uncontrolled` can collide with real user ids. Fix: always record; use non-user values such as `(upgrade)`.
- **S-13-11** `ops/ActivationSeeding.java:30`, `ActivationService.java:562`: seeding relies on the initializer running as SYSTEM. Fix: `ACL.as2(ACL.SYSTEM2)` with a reason comment.

## Checked and found to be fine
Five new web methods each `@RequirePOST` + permission first; CSRF crumbs; `?action=` is validated and re-checked; decision authority (designated set, Approve, global list, self-approval rule, jobApprovers at decision time); first decision wins under one lock; cancel and changeApprover limited correctly; P-09 visibility and 404s; config write paths cannot set activation (only ActivationService writes state); copy starts not activated; rename/move and delete handling; restart does not re-seed; paths via PathCodec/resolveUnder; XStream types are plain; Jelly escaped; CSV escaping; no new secrets or SYSTEM2 switches.
