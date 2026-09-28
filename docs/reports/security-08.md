# Security Review 08 — D-36 notifications, D-37 approver sets, D-40 CREATE name restriction

Reviewer: security-reviewer (returned as text; persisted by the main session, condensed). Branch `hosting-review/approvers-notify-names`, 2026-09-29.

## Summary: BLOCKER 1 / HIGH 2 / MEDIUM 1 / LOW 9
D-37 is sound: first decision wins under one lock that re-reads the request, only designated members decide, the self-approval ban applies to every member at designation and decision, `changeApprovers` is requester-only on PENDING and re-checks eligibility, visibility follows the set, legacy files load. The notification layer is isolated (background thread, failures caught, plain text, CR/LF stripped from the subject, no build parameters). The D-40 name restriction can be bypassed. Every new or changed `do*` has `@RequirePOST` plus a permission check first; changed Jelly is escape-by-default.

## BLOCKER
- **S-01** `security/GrantAwareACL.java:259` (D-35c branch): the holder's Configure on an item created through a restricted Create grant allows `confirmRename` to any name (`AbstractItem.doCheckNewName` needs only Configure); `CreatedItemGrantListener.java:104` / `GrantService.relocateCreatedItem` (`GrantService.java:289`) carries the record to the new name. Only a RENAME record is written. Fix: treat such a rename as a creation under the restriction (refuse a non-matching new name, or revert and record GRANT_VIOLATION).

## HIGH
- **S-02** `security/NewItemName.java:108-118` trusts `name` on any POST aimed at the folder, so core's Create-on-parent + Delete rename branch (`confirmRename?newName=evil&name=app-1`) passes. Fix: read `name` only for `createItem`, `value` only for `checkJobName`, `newName`/`value` for `confirmRename`/`checkNewName`; otherwise UNKNOWN.
- **S-03** ReDoS: `model/CreateNamePattern.java:66` compiles any regex up to 1000 characters and matches (`:94-100`, via `model/Grant.java:97`) an unbounded, holder-controlled name, on the request thread (`GrantAwareACL.java:314`) and inside the `synchronized` `GrantService` monitor (`CreatedItemGrantListener.java:68` → `GrantService.java:142-144`), which every Item/Read check passes. Measured: `(a|a)*\1b` on 28 characters 5.8 s, doubling per character. Fix: refuse names over 255 characters, match under a deadline (or linear-time engine / reject backtracking constructs), never match under the monitor.

## MEDIUM
- **S-04** `ops/NotificationDispatcher.java:84-88` uses `Jenkins.getRootUrl()`, which falls back to the request's `Host`/`X-Forwarded-Host` when no Jenkins URL is configured; the requester's own request builds REQUEST_CREATED/APPROVERS_CHANGED, so the approvers' mail links to an attacker host. Fix: configured URL only; omit the link otherwise.

## LOW
- **S-05** `NewItemName.java:111-113` + `GrantAwareACL.java:150`: `checkJobName` keystroke validation (GET) records GRANT_VIOLATION for every prefix. Fix: refuse without a record for GET/HEAD.
- **S-06** `CreateNamePattern.java:98`, `NewItemName.java:132` trim the name, but CLI create/copy do not, so `"app-1 "` passes an exact `app-1`. Fix: match the untrimmed name.
- **S-07** `listener/CreatedItemGrantListener.java`: children a computed folder creates during indexing (SYSTEM) are not name-restricted. Document.
- **S-08** `ops/MailNotifier.java:105-106`: the multi-line reason precedes `Link:` and can forge it. Fix: link first, quote reason lines.
- **S-09** `NotificationDispatcher.java:31`: unbounded queue, no throttle for APPROVERS_CHANGED; a hung SMTP server stalls the single thread. Fix: bounded queue, drop and log.
- **S-10** `action/JobRequestAction.java:155-156`: a scripted submission without the `json` field stores no parameters, so the approved build runs with the job's defaults at build time. Fix: read raw parameter fields, or refuse when parameters are defined and none submitted.
- **S-11** (pre-existing, = #23) `policy/RunRequestService.java:279`: `changeApprovers` looks the job up as the caller; a requester without Item/Read gets null and skips `jobApprovers`. Fix: look up as SYSTEM2 after the checks.
- **S-12** `policy/ApprovalPolicy.java:123,133`: user ids compared with `String.equals`. Fix: `User.idStrategy()`.
- **S-13** `security/CliCreateContext.java:24-43`: the captured CLI name can outlive its command on a pooled thread if `handleException` throws. Fix: bind it to the command's authentication and folder.

## Checked and found to be fine
First-decision atomicity and lock order; self-approval at both points; deciders outside the set refused; changeApprover authorisation and input caps (50 entries, 256 characters); visibility; legacy load and XStream types; D-40 on form/config.xml/copy createItem, CLI, REST, Job DSL/scripts/builds (UNKNOWN), move into the folder, folder copy with children, items inside a created folder; identical parsing at submission and enforcement; notification subject encoding, recipients, no secrets, async dispatch, optional Mailer; no recipient enumeration; CSV formula escaping of the new columns; no new ACL.SYSTEM2 switch points.
