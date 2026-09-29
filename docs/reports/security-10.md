# Security Review 10 — lane A (`p1/storage`: #13, #17, #18, #25)

Reviewer: security-reviewer (returned as text; persisted by the main session, condensed). 2026-09-29, HEAD c541352.

## Summary: BLOCKER 0 / HIGH 1 / MEDIUM 2 / LOW 8

## HIGH
- **S-01** `store/FileStore.java:304-319` (legacy read fallback in `loadConfigSnapshot`), `:357-402` (`migrateLegacySnapshots` on every restart), consumed by `listener/GrantViolationGuard.java:112-114` and `listener/ConfigSnapshotListener.java:111-116`: after #25 nothing writes the legacy `prefix-sha256` form, so a file at `legacyShortened(N)` can only be another item M's plain encoding. Orphaned (M deleted or renamed while recording was off, or M's folder failed to load), it is still read as N's baseline and moved into N's name at every restart. M's author controls its content, including a matrix property, so a grant holder's additions already present in the plant survive the D-35b guard unrecorded, and N's CONFIGURE diff is computed against attacker content. Fix: remove the read fallback; make the migration one-shot.

## MEDIUM
- **S-02** `store/JsonLineScanner.java:26,80-82,169-171`: `MAX_MEMBERS = 64` rejects run records with more than 64 parameters, so they vanish from every paged screen while CSV and summaries still count them; a Configure holder can hide a job's runs by adding parameters. Fix: grow on demand, and re-parse any scanner-rejected line with json-lib before skipping it.
- **S-03** `store/FileStore.java:1106-1128` and the four section notices: the 50,000-record cap counts every line before the period/job/user filter, so at SPEC 6 scale the oldest ~100,000 records of a month are unreachable on any screen, and the notice's advice to narrow by job or user cannot work. Fix: count only records inside the period (check timestamps from raw bytes), or at least point the notice to the CSV export.

## LOW
- **S-04** `store/ReverseLineReader.java:126-140`, `FileStore.java:461-474, 650-657`: unbounded line length (a huge parameter value re-allocated on every view). Cap lines (e.g. 1 MiB) and skip.
- **S-05** `ui/FilterParser.java`, `action/HistorySection.java` CSV: no span cap any more; one GET from 1970 reads the whole store (memory bounded, CPU not). Limit concurrent exports.
- **S-06** `FileStore.java:591-594`: incidents page parses each incident XML before filtering. Pre-filter on index fields.
- **S-07** `FileStore.java:378-383`: the migration can delete a live item's own current snapshot at every restart if it is named after `decode(legacyShortened(N))`, so its next change is unrecorded. Fixed by the same change as S-01.
- **S-08** `ops/StartupRecovery.java:74`: `jenkins.allItems()` relies on the init reactor running as SYSTEM; use `Items.allItems2(ACL.SYSTEM2, …)` with a reason comment.
- **S-09** `store/EntityIndex.java:69-78`: lastActivity ignores execution time, so retention can delete an EXECUTED request a kept RunRecord still references; a later replay is then refused as unknown (log only) instead of MARKER_REUSE_BLOCKED. Include execution time.
- **S-10** `FileStore.java:810-815, 666-668`: month-bucket merge is not atomic (duplicates after a crash); patches written under non-ASCII digits never expire.
- **S-11** `FileStore.java:384-393`: check-then-move race in the migration.

## Checked and found to be fine
No new `do*`; Jelly additions are static text; SYSTEM2 used only as an `allItems2` argument; no new secret flows; the PathCodec current form cannot collide or traverse and every access goes through `resolveUnder`; the raw-bytes scanner cannot be shifted by crafted strings and agrees with json-lib; filters can only hide, never reveal; per-line failures are caught; partial trailing lines are rejected; lock striping has no lost appends, deadlocks or torn files; the request index is consistent with disk and rebuilt at startup; retention keeps open requests, active grants and referenced grant requests; locale independence holds.
