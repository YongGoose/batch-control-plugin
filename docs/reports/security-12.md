# Security Review 12 — `p1/audit-cleanup` (#21, #22, security-11 N-01/N-02)

Reviewer: security-reviewer (returned as text; persisted by the main session, condensed). 2026-09-29.

## Summary: BLOCKER 0 / HIGH 0 / MEDIUM 0 / LOW 1

## LOW
- **S-12-01** `store/BlockedAttemptAudit.java:73` (`MAX_COALESCED_KEYS = 10_000`), `TriggerBlockedAuditTest`: the coalescing map's bound and eviction, and the fail-closed path when the audit write throws, have no test. The logic is safe by inspection (eviction only ever adds a record; a failed write is logged and the run is still refused). Fix: add test rows.

## Checked and found to be fine
Coalescing cannot suppress other jobs' records or exhaust memory (LRU bound, collision-free key); TRIGGER_BLOCKED is written through the striped per-file lock and fails closed; the job-page notice shows only to Item/Read holders, mirrors the gate's own conditions and renders only constant text; the run-link rule (D-44) is applied at every call site and all templates null-guard the link; the 405 guard and paging refactors keep every permission gate; the CSV line cap counts and the screens show the same skips; CSV cell escaping unchanged; no raw output; no new path, XStream, SYSTEM2 or secret-handling surface.
