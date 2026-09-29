# Security Review 11 — re-review of the security-10 fixes (`p1/storage`)

Reviewer: security-reviewer (returned as text; persisted by the main session, condensed). 2026-09-29.

## Summary: BLOCKER 0 / HIGH 0 / MEDIUM 2 / LOW 0

## Verdicts
S-01, S-07, S-10, S-11 MOOT by D-43 (the read fallback, snapshot migration and month rename/merge are deleted, not disabled); S-02 FIXED (member tables grow; rejected lines are re-parsed with json-lib); S-03 FIXED (period-scoped cap, early exit keyed on append time, notices link a complete CSV); S-04 PARTIAL (see N-01, N-02); S-05 FIXED (two concurrent exports, permit released in `finally`); S-06 FIXED (incident index pre-filter); S-08 FIXED by removal; S-09 FIXED (`executedAt` counts as activity). SpotBugs 0.

## MEDIUM
- **N-01** `store/FileStore.java:951-983` (`parseLines`, used by `listRunRecords`/`listChangeRecords` for `runs.csv`/`changes.csv`): no line-length cap, so an oversized parameter or detail line is still materialised whole on the CSV path, the path the truncation notice now sends users to. Fix: apply `ReverseLineReader.MAX_LINE_BYTES` there too, counting and skipping the same way.
- **N-02** `store/FileStore.java:1093-1104`, `store/RecordPage.java`: a skipped oversized line is only logged; `RecordPage` has no count for it, so screens look complete while the CSV (without a cap) disagrees. Fix: carry a dropped/oversized count in `RecordPage` and show it (or set `truncated`).
