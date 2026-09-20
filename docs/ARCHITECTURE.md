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
| 승인 투입 | `ParameterizedJobMixIn.scheduleBuild2(0, ParametersAction, CauseAction(ApprovedCause), ApprovedRunAction)` | 요청 저장 파라미터 그대로 |
| 삭제 차단 | `ItemListener#onCheckDelete(Item)` → `throw new Failure(...)` | 변경 통제 on + 활성 Grant(DELETE) 없으면 거부 |
| 변경 기록 | `ItemListener#onCreated/onUpdated/onDeleted/onRenamed/onLocationChanged` | 사후 훅. 현재 인증 `Jenkins.getAuthentication2()` 기록 |
| 설정 diff | `SaveableListener#onChange(Saveable, XmlFile)` + 직전 스냅숏 보관 | 스냅숏은 `snapshots/<jobFullName>.xml`에 최신 1개만 |
| 임시 권한 | `hudson.security.AuthorizationStrategy`(위임형) + `hudson.security.ACL` | 4절 참고 |
| 권한 정의 | `hudson.security.PermissionGroup`, `hudson.security.Permission` | `Item` 스코프. `Manage`는 `Jenkins.ADMINISTER` implied |
| 요청/결재/대시보드 화면 | `hudson.model.RootAction`(전역), `hudson.model.Action` + `TransientActionFactory<Job>`(잡별) | Jelly 뷰 |
| Build Now 대체 | `TransientActionFactory<Job>` + `AlternativeUiTextProvider` | 승인 대상 잡만 |
| 실행 기록 | `hudson.model.listeners.RunListener#onStarted/onCompleted/onFinalized` | `Run` 기준이라 Freestyle·Pipeline 공통 |
| 중단자 | `jenkins.model.InterruptedBuildAction` | 있으면 `abortedBy` |
| 오류 등록 | `RunListener#onCompleted`에서 `Result` 확인 | 로그 tail은 `Run.getLog(100)` |
| 만료·보관 정리 | `hudson.model.PeriodicWork` (1분 주기) / `AsyncPeriodicWork`(일 1회 보관 정리) | 만료 판정의 원본은 시각 비교, 주기 작업은 상태 갱신·정리용 |
| 재시작 복구 | `@Initializer(after = InitMilestone.JOB_CONFIG_ADAPTED)` | APPROVED 미투입 요청 재투입, 만료 처리 |
| 관리 경고 | `hudson.model.AdministrativeMonitor` | 권한 부여 없이 Configure를 가진 사용자 감지 |
| 전역 설정 | `jenkins.model.GlobalConfiguration` | JCasC는 2차 |

## 3. 패키지 구조

```
io.jenkins.plugins.batchcontrol
├── model/        RunRequest, GrantRequest, Grant, RunRecord, Incident, ChangeRecord, enums
├── store/        Store 인터페이스 + FileStore 구현 (XStream/JSONL), 잠금, 보관 정리
├── config/       BatchControlGlobalConfiguration, BatchControlJobProperty
├── security/     BatchControlPermissions, BatchControlAuthorizationStrategy(위임형), GrantService
├── policy/       ApprovalPolicy(누가 결재 가능한가), RunRequestService, GrantRequestService (상태 전이의 유일한 진입점)
├── queue/        ApprovalQueueDecisionHandler, ApprovedRunAction, ApprovedCause
├── listener/     ItemChangeListener, ConfigSnapshotListener, RunRecordListener, DeleteVetoListener
├── ops/          IncidentService, ExpiryPeriodicWork, RetentionPeriodicWork, StartupRecovery, ConfigureWithoutGrantMonitor
├── action/       JobRequestAction(잡별 요청 화면), RunRequestRootAction, GrantRootAction, DashboardRootAction, IncidentRootAction, HistoryRootAction, CsvExport
└── ui/           뷰 모델, 페이징, 필터 파서
```

소유권: `model, store, config, security, policy, queue, listener, ops` → core-dev / `action, ui, resources` → ui-dev.
경계 규칙: `action`은 `policy`·`store`의 공개 메서드만 호출한다. 상태 전이 로직을 `action`에 두지 않는다.

## 4. 위임형 권한 전략

```
BatchControlAuthorizationStrategy extends AuthorizationStrategy
  - delegate: AuthorizationStrategy   (관리자가 선택한 원래 전략, Matrix/Role 등)
  - getRootACL(): new GrantAwareACL(delegate.getRootACL(), scope=null)
  - getACL(Job|Item|...): new GrantAwareACL(delegate.getACL(item), item)
  - getGroups(): delegate.getGroups()

GrantAwareACL extends ACL
  - hasPermission2(auth, perm):
      if perm in {Item.CREATE, Item.CONFIGURE, Item.DELETE} and item != null:
          if GrantService.hasActiveGrant(auth.getName(), item.getFullName(), perm, now): return true
      return delegateACL.hasPermission2(auth, perm)
```

- 위임: `getRootACL()`뿐 아니라 **모든 `getACL` 오버로드**(Job, AbstractItem, ItemGroup, Computer, Node, View, User, Cloud 등)를 delegate에 위임한다. Role Strategy 등 다른 전략이 이 오버로드들을 재정의하기 때문이다. (Phase 1 PoC 발견 사항)
- 만료: `hasActiveGrant`가 `expiresAt > now && revokedAt == null`을 검사. 타이머 없음.
- 범위: `scope.type == FOLDER`면 `item.getFullName()`이 폴더 경로로 시작하는지, `JOB`이면 정확히 일치.
- CREATE는 폴더(ItemGroup)의 ACL에서 검사되므로 FOLDER 범위 Grant만 CREATE를 부여할 수 있다.
- 성능: GrantService는 활성 Grant를 메모리 맵에 유지(사용자 → 목록), 파일은 원본.
- JCasC/설정 화면: `delegate`를 Describable로 선택. 기존 Matrix/Role 설정은 delegate 안에 그대로 유지.
- 이 전략은 변경 통제 스위치와 무관하게 설치·선택 가능해야 하며, 활성 Grant가 없으면 delegate와 완전히 동일하게 동작한다.

## 5. 저장소 (FileStore)

```
$JENKINS_HOME/batch-control/
├── config.xml                    전역 설정 (GlobalConfiguration 표준 위치는 $JENKINS_HOME/io.jenkins...xml, 이 파일은 사용 안 함)
├── requests/run/<id>.xml         RunRequest (XStream). 상태 변경 시 파일 전체 재작성 (원자적: tmp → rename)
├── requests/grant/<id>.xml       GrantRequest
├── grants/<id>.xml               Grant
├── runs/YYYY-MM.jsonl            RunRecord, 월별 append-only
├── incidents/<id>.xml            Incident (상태 전이가 있으므로 XML)
├── incidents/index/YYYY-MM.jsonl 월별 인덱스 (id, runId, jobFullName, result, createdAt)
├── changes/YYYY-MM.jsonl         ChangeRecord, 월별 append-only. diff는 changes/diff/<id>.patch
└── snapshots/<jobFullName 인코딩>.xml   직전 config.xml (diff 계산용, 최신 1개)
```

- ID: `yyyyMMdd-HHmmss-<6자리 랜덤>` (파일명 안전, 시간순 정렬 가능).
- 쓰기: 저장소 단위 `ReentrantLock`. JSONL append는 `Files.write(APPEND)` 후 flush.
- 읽기: 월 파일을 읽어 메모리 필터. 최근 2개월은 캐시(파일 mtime으로 무효화).
- 비밀 마스킹: `hudson.model.PasswordParameterValue`와 `Secret` 타입은 `********`로 저장.
- 보관: `retentionMonths` 초과 월 파일 삭제 + ChangeRecord(RETENTION).
- 잡 이름 인코딩: `/` → `%2F`, 기타 URL-safe 인코딩. 디코딩 시 경로 탈출(`..`) 검증.

## 6. 요청 흐름

```
[실행 요청]
사용자 → JobRequestAction(POST /job/X/batch-control/request)
  → 권한 Request 확인 → RunRequestService.create(사유, 파라미터, 결재자)
  → 검증(결재자 목록, 자가 지정 금지, 사유 필수) → FileStore 저장(PENDING)

[결재]
결재자 → RunRequestRootAction(POST /batch-control/requests/<id>/approve)
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
