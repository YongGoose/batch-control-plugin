# Security Review 40

Scope: `git diff 76a2826..d696d5d -- src/main` (round-6 follow-ups since security-39), branch
`r6/sec40` at `d696d5d`. Every claim below comes from reading the code. No new runtime probes were
run in this round.

## Summary: BLOCKER 0 / HIGH 0 / MEDIUM 0 / LOW 2 / INFO 2

## BLOCKER (grounds for hosting rejection)

None.

## HIGH

None.

## MEDIUM

None.

## LOW

- [S-40-01] `policy/MissedInvalidations.java` (memory only), `policy/RunRequestService.java:104-111`,
  `:1126-1178` (`recoverUnderQueueLock`, which resolves the job by name only), `policy/ActivationService.java:76-78`.
  **A D-21 invalidation that was missed because the request file could not be read is forgotten at
  restart. After the restart, the request can run on (or be approved for) a different job that
  now has the old name.** Sequence: an APPROVED (or PENDING) request names job `J`. `J` is renamed
  or moved while the request file is unreadable or unwritable. `invalidateForJob` keeps the
  invalidation only in memory, and the queue item is cancelled, but `queueCancelledAt` may not be
  written either (`unsavedQueueCancels` is also memory only). Then Jenkins restarts, the file can
  be read again, and a user with Item/Create creates a new `J`. `recoverApprovedRequests` then
  resubmits the approved run on the new `J`. A PENDING request shows up as an ordinary request for
  the new `J`. This needs a storage failure at the moment of the rename plus a restart, so it is
  LOW (in the class of storage failures that LIMITATIONS covers). The class javadoc states the
  restart behaviour, but LIMITATIONS does not mention it.
  Fix direction (core-dev): at startup recovery and at approval, treat an open request as
  invalidated if the change log has a `RENAME`/`MOVE`/`DELETE` record for its `jobFullName` dated
  after the request was created. The records are durable and are written by `ItemChangeListener`.
  Otherwise, record the job's identity in the request. Fallback: release-manager documents the
  case in LIMITATIONS.
  Regression test: Given an APPROVED request on `J` whose file cannot be read when `J` is renamed
  to `J2`, When the service is reset (simulated restart), a new `J` is created and
  `recoverApprovedRequests` runs, Then no build of the new `J` is scheduled and the request ends
  INVALIDATED.

- [S-40-02] `listener/ConfigSnapshotListener.java:353-365` (`isCreationSave`: an innermost
  `BUILDING_FRAMES` frame classifies any saved item as a creation save, with no identity check;
  only `ANNOUNCING_FRAMES` checks `isAnnounced(item)`).
  **A save of another, already registered item made synchronously inside
  `createProjectFromXML`/`createProject`/`copy` updates its snapshot and writes no CONFIGURE
  record.** Inside these frames, code that the creator controls runs: XStream unmarshalling of the
  posted XML, `onLoad`/`setOwner` of properties, and `onCreatedFromScratch`. I found no path in
  core or the bundled dependencies that saves a different item there, so this is defence in depth
  and depends on other plugins. Fix direction: for a building frame, require that `item` is the
  object being created. For example, mark the new item (or its parent and name) in the
  `ItemGroupMixIn` path, the way `ANNOUNCED` does, or accept a building frame only for an item
  that `isRegistered` reports as just added and that had no snapshot before. Regression test:
  Given recording on and existing job `A` with a snapshot, When a test `JobProperty` whose
  `setOwner` saves `A` with a changed description is part of an XML used to create `B`, Then a
  CONFIGURE record for `A` exists.

## INFO

- [S-40-03] `listener/UnsavedItemWrites.java:45`: `merge` joins texts with `" and "` unless the
  new text equals the whole joined value. With alternating texts ("the activation lock" and
  "the removal of ...") added repeatedly while saves keep failing, the value grows with each
  event. This is bounded by the number of fail-closed events during a storage outage, so it has
  no practical impact. Fix: keep a `Set<String>` per item.
- [S-40-04] Known window, documented in the code (`ConfigSnapshotListener.refresh` javadoc): a
  seed or refresh that runs between core writing `config.xml` and `SaveStart` marking the save can
  store the new configuration as the baseline. That save then compares equal and is not recorded.
  The window is microseconds wide, a non-admin cannot trigger it (it requires an admin switch-on
  or startup), and it applies only to an item without a current snapshot. Accepted as documented.

## Closure of security-39 findings

- S-39-01 closed: `FileStore.java:246` refuses non-`PathCodec.isId` ids and `.values` in any letter
  case (`endsWithIgnoreCase`).
- S-39-02 closed: `GrantService.register` (`:1069-1084`) verifies `stillAtItsName` (the
  `deletedItems` weak map plus a SYSTEM identity lookup) under the monitor and revokes with
  `REVOKED_ITEM_DELETED`. Rename and create events compare names case-insensitively
  (`sameName`) and use `ownItemStillAt`.
- S-39-03 closed: `applyRecordedEnds` (`:1781-1820`) reads back to the oldest open window's grant
  time with no count cap. A failed or truncated read fails closed (`endUnconfirmed`).
  `isStartupEnd` requires user SYSTEM plus the exact `UNCONFIRMED_DETAIL` suffix. Every other
  SYSTEM `GRANT_REVOKE` detail ends in a fixed, different suffix, so a user-chosen item name
  cannot forge the stop marker.
- S-39-04 closed: every grant screen renders through `ScopeDisplay` (`GrantsSection/index.jelly:63,
  115,172`, `GrantRequestItem/index.jelly:35`, the renew form `_requestForm.jelly:43`), and
  `GRANT_EXPIRING` uses `recipientMayRead`.
- S-39-05 closed: `DeletionAttribution.remember` (`:51-68`) purges stale and own entries before the
  SYSTEM return.

## Checked and found to be fine

- Web methods: the diff adds or changes no `do*` method (grep of added lines). Existing ones are unchanged.
- New elevation sites, each with its reason in a comment and no user decision made under it:
  `ConfigSnapshotListener.isRegistered:396-400` (an identity lookup only; on failure it returns
  "registered", so the save is recorded); `SnapshotSeeding:168,208-212` (stores baselines only,
  started by the admin-only global configuration submit or JCasC/startup);
  `NotificationDispatcher.recipientMayRead:318-334` (narrows to the recipient and fails closed
  for an unknown user, a realm failure or Discover-only access); `GrantService.itemAt:1104-1113`
  (identity comparison only, after the approver's or core's checks).
- Unapproved runs: both `approve` methods check the missed-invalidation map under the service
  lock, after the permission check and before APPROVED. If `invalidate` fails, the exception
  propagates and the request stays PENDING. Queued runs of a moved job whose request missed the
  invalidation are cancelled (`RequestInvalidationListener:67`, folder children through their own
  events).
- Memory/CPU: `MissedInvalidations` holds at most 64 entries per id, and the number of ids is
  bounded by unreadable open files. The `unsavedCreatedItems`/`unsavedChangedItems` maps are keyed
  by grant id. `deletedItems` and `UnsavedItemWrites.PENDING` are weak and keyed by item. `SAVING`
  is decremented in `finally` (core catches exceptions per listener). Seeding runs one queued
  background pass (`WAITING` CAS) and gives up early when superseded or switched off. The stack
  walk runs only for saves that would write a record.
- Recording during a switch-on: a save of a pending item is recorded without a diff
  (STALE_BASELINE), not dropped. A guard save of an item with an unsaved fail-closed change uses
  the in-memory baseline (`GrantViolationGuard:128`, `failedRemovalOutcome:1102`), so a payload
  property cannot come back from the stale snapshot.
- D-75 texts: the `scopeItem.properties` notes are fixed and escaped (`escape-by-default`) and name no
  location. The history (ViewHistory, an audit role) still shows followed names in GRANT_REVOKE
  details, which is by design.
- SpotBugs on `d696d5d` sources: `total_bugs='0'` (`target/spotbugsXml.xml` from the scratch build of
  identical `src/main`).

## NOT checked

- No runtime probes in this round (S-40-01/02 are from reading the code only).
- `FileStore`/`Store`/`RecordPage`/`JsonLineScanner` paging changes and `RecordLookup` were checked
  only for path handling (ids through `PathCodec`), not for paging correctness.
- `ExpiryPeriodicWork`, `IncidentService`, `BatchControlStrategyMonitor` diffs: skimmed only.
- Full test suite was not rerun.

## Request

- core-dev: S-40-01 (durable invalidation check at recovery and approval), S-40-02 (identity check
  for building frames), S-40-03 (optional).
- release-manager: if S-40-01 is not fixed, add it to `docs/LIMITATIONS.md` (storage-failure section).
