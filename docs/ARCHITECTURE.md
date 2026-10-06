# batch-control 아키텍처

## 1. 원칙

- Jenkins 코어를 건드리지 않는다. 공개 확장 포인트와 공개 API만 쓴다.
- 통제는 두 방식뿐이다: (a) 확장 포인트로 **거부**, (b) 위임형 권한 전략으로 **임시 허용**. 플러그인이 사용자 대신 잡을 만들거나 고치는 "대행"은 하지 않는다.
- 기록은 이벤트 리스너에서 **항상** 남긴다. 기록은 통제와 독립이다.
- 시간 만료는 타이머가 아니라 **검사 시점의 시각 비교**로 판정한다.

## 2. 확장 포인트 매핑

| 기능 | 확장 포인트 / API | 비고 |
|---|---|---|
| 잡별 승인 설정 | `hudson.model.JobProperty` + `JobPropertyDescriptor` | `approvalRequired`, `blockTimer`, `blockUpstream`, `allowedUpstreamJobs` |
| 실행 차단 | `hudson.model.Queue.QueueDecisionHandler#shouldSchedule(Task, List<Action>)` | `CauseAction`으로 원인 분류. 승인 투입은 `ApprovedRunAction`(마커)으로 통과. 차단 시 사용자 유래 Cause(UserIdCause·CLI·REST)는 `Failure` throw로 안내, Timer·SCM 등 무인 Cause는 `return false` + 로그 |
| 승인 투입 | `ParameterizedJobMixIn.scheduleBuild2(0, ParametersAction, CauseAction(ApprovedCause), ApprovedRunAction)` | The `ParametersAction` holds the request's stored `ParameterValue` objects unchanged, original secrets and files included (D-72) |
| Approved run in the queue (D-72b (7)) | `hudson.model.queue.QueueListener#onLeft(Queue.LeftItem)` (`ApprovedRunQueueListener`) | An approved run's queue item that was cancelled marks the request `queueCancelledAtMillis`, deletes its values file and is never resubmitted by startup recovery |
| Temporary parameter files (D-72, D-74 (2)) | core `FileParameterValue.CancelledQueueListener` and file-parameters `StashedFileParameterValue.CancelledQueueListener`, called with a synthetic cancelled `Queue.LeftItem` (`ParameterFiles`) | For values that will never reach the queue (a request that ended without a run, a submission refused before a request was stored, a person's own build refused by the queue gate, rerun values that could not be completed), the files are disposed of the way each parameter type disposes of them for a cancelled queue item. Neither type exposes its temporary file or a method that deletes it; its public listener for cancelled queue items is the only supported cleanup, so Batch Control calls exactly those two listeners (nothing enters the queue, no private field is read) |
| 삭제 차단 | `ItemListener#onCheckDelete(Item)` → `throw new Failure(...)` | 변경 통제 on + 활성 Grant(DELETE) 없으면 거부 |
| 변경 기록 | `ItemListener#onCreated/onDeleted/onRenamed/onLocationChanged` | 사후 훅. 현재 인증 `Jenkins.getAuthentication2()` 기록 |
| 설정 diff | `SaveableListener#onChange(Saveable, XmlFile)` + 직전 스냅숏 보관 | 스냅숏은 `snapshots/<jobFullName>.xml`에 최신 1개만 |
| 임시 권한 | `hudson.security.AuthorizationStrategy`(위임형) + `hudson.security.ACL` | 4절 참고 |
| 권한 정의 | `hudson.security.PermissionGroup`, `hudson.security.Permission` | `PermissionScope.JENKINS` 스코프(전역 권한). `Manage`는 `Jenkins.ADMINISTER` implied |
| 요청/결재/대시보드 화면 | `hudson.model.RootAction`(전역), `hudson.model.Action` + `TransientActionFactory<Job>`(잡별) | Jelly 뷰 |
| Build Now 대체 | `TransientActionFactory<Job>` + `AlternativeUiTextProvider` | 승인 대상 잡만 |
| 실행 기록 | `hudson.model.listeners.RunListener#onFinalized` (실행 기록은 여기서만 생성) | `Run` 기준이라 Freestyle·Pipeline 공통 |
| 중단자 | `jenkins.model.InterruptedBuildAction` | 있으면 `abortedBy` |
| 오류 등록 | `RunListener#onFinalized`에서 `Result` 확인 | 로그 tail은 `Run.getLog(100)` |
| 만료·보관 정리 | `hudson.model.PeriodicWork` (1분 주기) / `PeriodicWork`(일 1회 보관 정리) | 만료 판정의 원본은 시각 비교, 주기 작업은 상태 갱신·정리용 |
| 재시작 복구 | `@Initializer(after = InitMilestone.JOB_CONFIG_ADAPTED)` | APPROVED 미투입 요청 재투입, 만료 처리 |
| 관리 경고 | `hudson.model.AdministrativeMonitor` | 권한 부여 없이 Configure를 가진 사용자 감지 |
| 전역 설정 | `jenkins.model.GlobalConfiguration` | JCasC는 2차 |

## 3. 패키지 구조

```
io.jenkins.plugins.batchcontrol
├── model/        RunRequest, GrantRequest, Grant, RunRecord, Incident, ChangeRecord, enums
├── store/        Store 인터페이스 + FileStore 구현 (XStream/JSONL), 잠금, 보관 정리
├── config/       BatchControlGlobalConfiguration, BatchControlJobProperty
├── security/     BatchControlPermissions, BatchControlMatrixAuthorizationStrategy / BatchControlRoleBasedAuthorizationStrategy (D-35a), GrantService, MoveGuard (D-59)
├── policy/       ApprovalPolicy(누가 결재 가능한가), RunRequestService, GrantRequestService (상태 전이의 유일한 진입점)
├── queue/        ApprovalQueueDecisionHandler, ApprovedRunAction, ApprovedCause
├── listener/     ItemChangeListener, ConfigSnapshotListener, RunRecordListener, DeleteVetoListener
├── ops/          IncidentService, ExpiryPeriodicWork, RetentionPeriodicWork, StartupRecovery, ConfigureWithoutGrantMonitor
├── action/       BatchControlRootAction(전역 /batch-control/) + RequestsSection, GrantsSection, ActiveGrantsSection, DashboardSection, IncidentsSection, HistorySection, ChangesSection, JobRequestAction(잡별 요청 화면), JobGrantRequestAction
└── ui/           뷰 모델, 페이징, 필터 파서
```

소유권: `model, store, config, security, policy, queue, listener, ops` → core-dev / `action, ui, resources` → ui-dev.
경계 규칙: `action`은 `policy`·`store`의 공개 메서드만 호출한다. 상태 전이 로직을 `action`에 두지 않는다.

## 4. Grant-aware authorization strategies (D-35a; replaces the delegating wrapper)

```
BatchControlMatrixAuthorizationStrategy extends ProjectMatrixAuthorizationStrategy
BatchControlRoleBasedAuthorizationStrategy extends RoleBasedAuthorizationStrategy
  - getRootACL() / getACL(Job|AbstractItem|ItemGroup|Computer|...):
        new GrantAwareACL(super.getACL(...), item)
  - own Descriptor (listed on the security page), XStream converter, JCasC configurator
  - the parent's instanceof checks pass, so per-item properties and role pages keep working

GrantAwareACL extends ACL            (unchanged logic)
  - hasPermission2(auth, perm):
      if auth == SYSTEM: return true
      if item != null and auth is not anonymous and changeControlEnabled:
          for p = perm; p != null; p = p.impliedBy:
              if p.enabled and GrantAction.fromPermission(p) != null
                 and GrantService.hasActiveGrant(auth.getName(), item, p):   # exact full name of the window's item (D-71, D-74)
                  return true
      return parentACL.hasPermission2(auth, perm)
```

- Every `getACL` overload the parent overrides is wrapped, so the parent's own per-item logic runs first underneath the grant layer. The root ACL carries no grant scope, so a subclass may leave `getRootACL` unwrapped, and matrix-auth's `getACL(ItemGroup)` resolves to an already wrapped item or root ACL (security-05 S-08).
- Following the item (D-74, replaces the D-71a/D-71b identity binding): a grant matches its item by exact full name. Item events keep it correct: when an item is renamed or moved (by an administrator or a user with their own permissions — no window allows a rename, D-71c), the windows naming it and the items below a renamed folder are updated to the new full names, and so are D-35c created-item records; deleting an item ends (revokes, reason "its item was deleted") the windows naming it or anything below it; creating an item at a window's name ends that window; at startup, windows whose item no longer exists end. Ending a window marks it ended in memory first, then appends the GRANT_REVOKE record, then rewrites the grant file; a failed write is retried (every later grant write, item events, the periodic work), and at startup a window whose file still says it is open but which has a GRANT_REVOKE record is ended again from that record. Registration re-checks, under the service's lock and after the approver's checks, that the approved item object is still at its name; otherwise the window ends at once (D-71c (3), D-75). A window that cannot follow its item for certain (its grant file cannot be written with the new name, another item is at the old name again when the event is handled, or another item has just taken the name it gives) ends with a GRANT_REVOKE record instead of keeping a stale name; D-35c records are updated in memory first and their write is retried. The startup re-end reads the GRANT_REVOKE records since the oldest open window was granted, bounded by time and not by a record count; if they cannot all be read — including a line inside that range that is torn, damaged or too long — every open window ends ("its state could not be confirmed at startup"); a complete record of another type does not count, and a later start stops reading at the GRANT_REVOKE records of a start that failed closed, so a damaged line is read back once. The screens read grants through the same service as the permission checks; a followed name is shown only to a viewer who may read the item (D-75). Remaining gap: an item replaced on disk outside Jenkins followed by a reload.
- Expiry: `hasActiveGrant` checks `expiresAt > now && revokedAt == null`. No timer, nothing written into the other plugin's data.
- Scope (D-71, replaces D-65): a grant names exactly one item (scope type `ITEM`, a job or a folder of any kind) and matches only that item's exact full name, so it confers nothing on any other item, including the items inside a folder. CONFIGURE, and EXTENDED_READ through the `impliedBy` walk, is answered on the item's own ACL. Core checks CREATE on the parent's ACL, so a CREATE grant, which can exist only on a modifiable item group (a regular folder, not a job or a computed folder), admits creation directly inside that folder only, never in a nested folder. A DELETE grant can exist only on a job (an item that is a `Job`, including multi-configuration and Maven projects), never on an item group that is not a job (folder, multibranch project, organization folder). Both restrictions are enforced when the request is submitted, from the item's kind, and again in every permission check (a window answers CREATE only on a regular folder and DELETE only on a job).
- Self-grant guard (D-35b): a `SaveableListener` restores an item's authorization property changed by a user whose Configure comes only from a grant, and records `GRANT_VIOLATION`.
- Upgrade: none. The withdrawn generic wrapper `BatchControlAuthorizationStrategy` is removed without a load-time conversion; the plugin was never released (D-35e).
- Migration: a security-page action copies a plain matrix-auth or role-strategy configuration into the subclass and back.
- Monitors: "change control is on but the installed strategy is not a Batch Control strategy" (for example a plain strategy installed on the security page or by JCasC; role-strategy 927+ keeps the subclass on its own saves, D-35f, D-35g).
- With no active grant the subclass behaves exactly like its parent.
- matrix-auth and role-strategy are optional dependencies; each subclass is an `@Extension(optional = true)` in its own class so a missing plugin never breaks class loading.
- file-parameters is an optional dependency too (D-74 (2)): every reference to its classes is isolated in `store/FileParametersSupport`, which checks that the plugin is installed before touching them, so `stashedFile`/`base64File` support is absent, not broken, without it.

## 5. 저장소 (FileStore)

```
$JENKINS_HOME/batch-control/
├── config.xml                    전역 설정 (GlobalConfiguration 표준 위치는 $JENKINS_HOME/io.jenkins...xml, 이 파일은 사용 안 함)
├── requests/run/<id>.xml         RunRequest (XStream). 상태 변경 시 파일 전체 재작성 (원자적: tmp → rename)
├── requests/run/<id>.values.xml  RunRequestValues: the typed ParameterValues, written once at submission (D-74)
├── requests/grant/<id>.xml       GrantRequest
├── grants/<id>.xml               Grant
├── runs/YYYY-MM.jsonl            RunRecord, 월별 append-only
├── incidents/<id>.xml            Incident (상태 전이가 있으므로 XML)
├── incidents/index/YYYY-MM.jsonl 월별 인덱스 (id, runId, jobFullName, result, createdAt)
├── changes/YYYY-MM.jsonl         ChangeRecord, 월별 append-only. diff는 changes/diff/<id>.patch
└── snapshots/<jobFullName 인코딩>.xml   직전 config.xml (diff 계산용, 최신 1개)
```

- ID: `yyyyMMdd-HHmmss-<6자리 랜덤>` (파일명 안전, 시간순 정렬 가능).
- 쓰기: 저장소 단위 `ReentrantLock`. JSONL append는 `Files.write(APPEND)` 후 flush. An append first ends a torn last line (a file that does not end with a line end, or whose last byte cannot be read, gets one; at worst a blank line, which readers skip), so a damaged line never swallows the next record.
- Unreadable entity files (an XML file that cannot be read or parsed) are skipped with a warning in listings and in the grant cache; a skipped grant confers nothing.
- Reads (#13): list screens page newest-first by streaming month files from the end and stop after the page window or at most 50,000 scanned records (`RecordPage.truncated`, and the screen asks the user to narrow the filter). Diffs are read only for the rows shown. Month counters for summaries are kept in memory and updated from what was appended since the last read. An in-memory index of requests and grants is built once per session at startup; the expiry, recovery and invalidation scans load only open requests. All of this is derived state, never persisted, and rebuilt on restart.
- Locks (#18): one lock per file stripe (64 stripes by path hash) instead of a single store lock. Retention deletes one file at a time under that file's lock, so the queue gate and build completion wait behind at most one write.
- Names (#17, #25): month bucket names and ids use `Locale.ROOT` ASCII digits and the plugin clock's zone. A shortened item file name is `prefix~sha256`; `encode` writes `~` as `%7E`, so a shortened name never equals a plain encoding. Pre-release file names (the old shortened form, non-ASCII month digits) are neither read nor migrated (D-43).
- Retention also deletes closed requests and ended grants older than the first kept month.
- Grant file fields (D-35c, D-58a): besides its window, a grant keeps two optional lists of item full names, `createdItems` (items created through it) and `changedItems` (items whose configuration was changed under it and not yet reviewed). Both are written only when non-empty, so older files load unchanged. `changedItems` follows renames and moves (the entries of items below a renamed or moved folder follow in each item's own event), loses an item when it is deleted or reviewed (an HTTP save by a native Item/Configure or Overall/Administer holder), and retention never deletes a grant file whose `changedItems` is non-empty. A run replayed under a grant carries an invisible marker action saved in its own `build.xml` by Jenkins (D-58c); Batch Control adds no file for it.
- Run request file fields (D-72): `parameterValues` holds the submitted `ParameterValue` objects as XStream writes them, as core does in `build.xml`; a `PasswordParameterValue` and any other `Secret` field are written in Jenkins' encrypted form, never as plaintext. `parameters` is the masked string map derived once at submission, and it is the only form in which a request's parameters are displayed or written elsewhere (screens, CSV, history); run records and incidents mask the build's own values with the same rule (D-72b (8)). Where the typed values are stored is replaced by the next bullet (D-74). The approved build is scheduled with `parameterValues` unchanged. File content stays where each parameter type keeps it (core `$JENKINS_HOME/fileParameterValueFiles/`, file-parameters `stashedFileParameterValueFiles/`, Base64 inside the request's values file); Batch Control copies none of it. When a request ends without a run (REJECTED, CANCELLED, EXPIRED, INVALIDATED, or the approved run could not be queued) Batch Control disposes of those temporary files; once the approved run is queued, the queue and the build own them.
- Run request files (D-74, replaces the D-72b layout): `requests/run/<id>.xml` holds the request without typed values (status, ticket, masked `parameters`, …). The typed values are in `requests/run/<id>.values.xml`, root `io.jenkins.plugins.batchcontrol.model.RunRequestValues` (`requestId`, `values` = the submitted `ParameterValue` objects as XStream writes them, Secrets encrypted), written once at submission after `<id>.xml` (each via a temporary file and an atomic move; if the values file cannot be written, `<id>.xml` is deleted); there is no values file for a request without parameters. Listings, badges, the index and periodic work read only `<id>.xml`; approve, submit, startup recovery and disposal read the values file; a request with parameters whose values file is missing or unreadable cannot be approved or run. The values file is deleted when the approved run starts, when the approved run's queue item is cancelled (the request stays APPROVED, D-72b (7)), or when the request ends (after the end state is saved); retention deletes it with the request. Optional `queueCancelledAtMillis` records that an approved run's queue item was cancelled.
- Incident file: optional `runTimestampMillis` (the failed build's `Run#getTimeInMillis()`); a rerun reuses a build's values only when that build still has this timestamp (D-72b(6)). Incidents stored without it cannot confirm their build, so their rerun opens the form (unreleased plugin, D-69).
- Secret masking (D-72): Batch Control writes no plaintext secret. In every textual form (the request's `parameters` map, run records, CSV, incidents, diffs) a sensitive value (`ParameterValue#isSensitive()`, `hudson.model.PasswordParameterValue`, `Secret`) is `********`, and a file value appears only as `[file] <original file name>`, never its content, Base64 or a server path.
- Grant request and grant file fields (D-71): `scope` is `{type: ITEM, fullName}`; `itemKind` records the item's kind at submission (descriptor id, display name and icon class name), and a grant copies it from its request. Files with the earlier scope types `JOB`, `FOLDER` or `FOLDER_ONLY` are not converted (D-69).
- 보관: `retentionMonths` 초과 월 파일 삭제 + ChangeRecord(RETENTION).
- 잡 이름 인코딩: `/` → `%2F`, 기타 URL-safe 인코딩. 디코딩 시 경로 탈출(`..`) 검증.

### Activation store (#15, D-39)

```
$JENKINS_HOME/batch-control/
  activations/<encoded job full name>.xml   ActivationState: activated, activatedBy, activatedAt, requestId,
                                            deactivatedBy, deactivatedAt
  activations/.schema                       marker: existing jobs were seeded as activated (written once)
  activation-requests/<id>.xml              ActivationRequest: id, jobFullName, action (ACTIVATE|HOLD), reason,
                                            requester, approvers[], decidedBy, status, createdAt, decidedAt,
                                            decisionComment, approverChanges[]
```

- A job whose directory marker (identity) cannot be read counts as not activated (fail closed, SPEC 6a).
- `policy.ActivationService#isActivated(Job)` is the single read the queue gate uses for timer and upstream causes; it is ANDed with `blockTimer`/`blockUpstream`.
- Activation state is written only by an approved ACTIVATION request (or the one-time seeding), never by job configuration, so no config write path can activate a job.
- Rename/move relocates the state file; deletion removes it. The file name uses `PathCodec` like snapshots.

## 6. 요청 흐름

```
[실행 요청]
사용자 → JobRequestAction(POST /job/X/batch-control/submit)
  → 권한 Request 확인 → RunRequestService.create(사유, 파라미터, 결재자)
  → 검증(결재자 목록, 자가 지정 금지, 사유 필수) → FileStore 저장(PENDING)
  (D-72) After the permission check and before Batch Control reads the form, the declared body size
  is checked against maxRequestBodyBytes (default 100 MB). Core may already have parsed a multipart
  body into its temporary upload directory before any plugin code runs (URL dispatch under a job
  reads request parameters); the cap guarantees that nothing is created or kept in JENKINS_HOME, and
  the instance-wide upload limit is Stapler's FILEUPLOAD_MAX_SIZE system property (D-72a). Second stage
  (D-74 (2)): after the form is read and before anything is stored, the size the request would keep
  (uploaded parts read plus stored texts; base64File counted as its Base64 text) is checked against
  the same cap; over it → 413, nothing kept. The stored request holds the typed ParameterValues and the
  masked display map derived from them once.

[결재]
결재자 → BatchControlRootAction → RequestItem(POST /batch-control/requests/<id>/approve)
  → 권한 Approve 확인 + 지정 결재자 일치 확인 + (자가 결재 정책)
  → RunRequestService.approve → APPROVED 저장
  → scheduleBuild2(파라미터, ApprovedCause, ApprovedRunAction)
  → QueueDecisionHandler: ApprovedRunAction 있으면 통과, 요청에 executedRunId 기록 (RunListener.onStarted에서)

[차단]
누구든 Build Now → QueueDecisionHandler.shouldSchedule
  → 실행 통제 off 또는 approvalRequired=false → 통과
  → CauseAction 분석: ApprovedRunAction 있음 → 통과 / Timer & !blockTimer → 통과 / Upstream 정책 → 통과 or 차단
  → Upstream 분류는 instanceof Cause.UpstreamCause (build 스텝의 Cause는 BuildUpstreamCause로 UpstreamCause의 하위 클래스)
  → 차단 시: 사용자 유래 Cause(UserIdCause·CLI·REST) → Failure throw로 안내(요청 화면 링크 포함)
             Timer·SCM 등 무인 Cause → false 반환 + 로그
```

## 7. 알려진 제약 (문서에 명시할 것)

- 관리자(Overall/Administer)는 모든 통제를 우회할 수 있다. 플러그인은 관리자 행위도 기록만 한다.
- 디스크에서 직접 고친 뒤 "Reload Configuration from Disk"를 하면 CONFIGURE 변경 기록이 남지 않는다(`onLoaded`만 호출됨).
- Multibranch/Organization Folder 하위 잡은 자동 생성되므로 변경 통제 대상이 아니다. 실행 기록과 오류 등록은 된다.
- 잡 설정 화면의 "저장"은 가로챌 수 없으므로 변경 통제는 권한 전략 기반이다. 권한 전략을 이 플러그인의 위임형으로 바꾸지 않으면 변경 통제는 동작하지 않으며, 그 경우 관리 화면에 경고가 표시된다.
- `build` 스텝으로 호출된 보호 잡이 차단되면 상위 잡은 FAILURE로 끝난다(`wait: false`여도 동일). (Phase 1 PoC 발견 사항)
- JIT 변경 통제는 Matrix 계열 권한 전략에서만 지원한다. Role Strategy에서는 실행 통제와 기록만 동작하며, Role Strategy가 선택된 경우 AdministrativeMonitor로 안내한다. (C-2 결정: MVP는 제약 문서화)
