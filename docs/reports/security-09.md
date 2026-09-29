# Security Review 09 — lane B (`p1/policy`: #19, #24, #26)

Reviewer: security-reviewer (returned as text; persisted by the main session, condensed). 2026-09-29.

## Summary: BLOCKER 0 / HIGH 0 / MEDIUM 1 / LOW 1
#24 and #26 are clean and well covered. #19 is correct for the web form; the direct-setter path has one new failure mode.

## MEDIUM
- **S-01** `config/BatchControlGlobalConfiguration.java:122-192` (`setRunControlEnabled`, `setChangeControlEnabled`, `persistSwitches`): the switch setters now write `config.xml` synchronously and throw `UncheckedIOException` when called directly on the live singleton (JCasC `Attribute.setValue`, script console). JCasC rethrows as `ConfiguratorException`, so a transient write failure during a boot-time JCasC apply can abort Jenkins startup, which was impossible before. Untested. Fix: an owner decision between degrading (log, no throw) and documented fail-loud; pin the chosen behaviour with a test, including a JCasC apply against a broken config directory.

## LOW
- **S-02** `config/BatchControlGlobalConfiguration.java:159-173`: the setters rely on parameter/field shadowing to tell the new value from the current one; qualify field reads with `this.` and test that toggling one switch leaves the other untouched in memory, in the toggle record and on disk.

## Checked and found to be fine
SYSTEM2 in `submitApproved` is entered only after the approval decision and only looks up and schedules the job (parameter reconstruction inside the block is required by #26); a deleted job leaves the request APPROVED for expiry/recovery; Discover-only and no-permission approvers reach EXECUTED with one build while a non-designated approver is still refused; Item/Build is checked as the requester in `RunRequestService#create`, the single construction point for run requests (form and incident rerun), so a rerun re-checks the current caller; the #19 form path writes a detached candidate first and applies nothing on failure; saves and setters share one monitor; no lock-order inversion with GrantService; volatile switch fields; the candidate targets the same `config.xml` (verified in `Descriptor` bytecode); storage format unchanged.
