# Security Review 07 — hosting-review/plugin-interaction (#20, #34, #36), src/main only

Reviewer: security-reviewer (returned as text; persisted by the main session). Date: 2026-09-28.

## Summary: BLOCKER 0 / HIGH 0 / MEDIUM 2 / LOW 2

## MEDIUM
- [S-01] `queue/ApprovalQueueDecisionHandler.java:257-266` (`retryAwareCauses`): a `Cause.UpstreamCause` whose upstream project is the job itself is unwrapped as if it were a retry, so a job that legitimately triggers itself (`build job: env.JOB_NAME, wait: false`) is refused as approval re-use even with `blockUpstream=false`, contradicting SPEC item 6 / D-16. Fail-closed, not a bypass. Fix: drop the same-job unwrap; a same-job UpstreamCause follows the normal upstream policy. Regression test: an approved run of X that triggers X reaches the queue when `blockUpstream=false`.
- [S-02] `listener/ConfigNormalizer.java:34-35, :104`: the `plugin="..."` stripper is not anchored to attribute boundaries and can match inside another attribute's value (for example `value='cmd plugin="1.0" tail'`), so an edit confined to such a substring would normalise equal and go unrecorded. No shipping field found that stores free text as a raw attribute value. Fix: tokenise attributes and strip only an attribute whose name is exactly `plugin`. Regression test: two bodies differing only inside such a value produce one CONFIGURE record with a diff.

## LOW
- [S-03] Naginator automatic retry of an approved run is refused (T-06-34). Policy question; settled by SPEC item 6 (a retry is judged by the causes of the build it retries).
- [S-04] `listener/ConfigSnapshotListener.java:75-77`: skipping every save of a computed folder's child also skips a user's direct `POST config.xml` to that child, whose edit takes effect until the next re-index and is never recorded.

## Checked and found to be fine
- Naginator retry-cause handling matches naginator 1.556 bytecode (automatic retry keeps the original causes next to a new NaginatorCause; manual retry swaps the UserIdCause). A class-name mismatch degrades to an extra unclassified cause, never a silent pass.
- Approval-marker consumption is unaffected; no new double-consumption path.
- The `<actions>` drop is scoped to the root's direct child; comments, CDATA and element text are never fed to the attribute regex.
- Lock striping keeps one item's snapshot, diff and write atomic; FileStore's own lock prevents corruption on a stripe collision.
