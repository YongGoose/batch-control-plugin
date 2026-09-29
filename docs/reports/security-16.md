# Security Review 16 — PR #45 (`fix/e2e-run3`, e2e-03 part-1 defects)

Reviewer: security-reviewer (returned as text; persisted by the main session, condensed). 2026-09-29.

## Summary: BLOCKER 0 / HIGH 0 / MEDIUM 0 / LOW 1

## LOW
- **S-16-01** `ui/ApprovalRequiredFailure.java:60` (`getJob()`): relies on `getItemByFullName`'s own Read/Discover enforcement rather than catching `AccessDeniedException`. Unreachable in practice (the refusal is thrown on the same request that core already gated on Build + Read; the job name is server-captured). Defensive test suggested: a viewer without Item/Read gets no job and no "Back to job" link.

## Checked and found to be fine
No new web methods, XStream types or SYSTEM2 switches; all touched Jelly is escape-by-default with no raw output; the refusal page and notice use the plugin's global Request permission plus Item/Read consistently; the standing-permission monitor is reachable only by administrators (AdministrativeMonitor default); `ApprovalRequiredFailure` is thrown only for genuine human submissions on the submitting request thread, never on the queue maintenance thread; new TRIGGER_BLOCKED kinds stay within the coalescing bound; CONFIGURE-to-grant linking is scoped to the acting user and the created item's identity; CSV cells are still escaped.
