# batch-control 기능 스펙 (v1.0 — 확정)

이 문서는 모든 에이전트의 단일 기준입니다. 각 항목의 "수용 기준"은 테스트로 검증 가능한 문장으로 썼습니다.

## 0. 용어

| 용어 | 정의 |
|---|---|
| 요청자 (Requester) | 실행 요청 또는 임시 권한 요청을 올리는 사용자 |
| 결재자 (Approver) | 요청을 승인/반려하는 사용자. 플러그인 설정의 결재자 목록에 등록되어 있어야 함 |
| 실행 요청 (RunRequest) | 특정 잡을 특정 파라미터로 실행해 달라는 요청 |
| 권한 요청 (GrantRequest) | 특정 범위·행위·시간의 임시 권한을 달라는 요청 (JIT) |
| 권한 부여 (Grant) | 승인된 권한 요청의 결과. 만료 시각을 가짐 |
| 실행 기록 (RunRecord) | 모든 빌드의 실행 결과 요약 |
| 오류 건 (Incident) | FAILURE/UNSTABLE 결과에서 자동 생성되는 처리 대상 |
| 변경 기록 (ChangeRecord) | 잡 생성·수정·삭제·이름변경·이동의 기록 |

## 1. 범위

- **MVP (1차 릴리스)**: 항목 1~12
- **2차**: 항목 13~15
- **범위 밖**: 관리자(Overall/Administer)의 우회 차단, 디스크 직접 수정 탐지, 배치 애플리케이션 내부의 단계 재개(restart), Pipeline 스크립트 소스(Git) 변경 통제

## 2. 기능 스펙

### 공통 기반

**1. 전역 스위치**
플러그인 설치만으로는 아무 동작도 하지 않고, 관리자가 켜야 활성화됩니다.
행위별(실행 통제 / 변경 통제)로 따로 켜고 끌 수 있으며, 스위치 변경 자체도 이력에 남깁니다.
- 수용 기준: 설치 직후 기존 잡의 빌드·설정·삭제가 이전과 동일하게 동작한다.
- 수용 기준: 실행 통제만 켜면 변경 통제 관련 UI·차단은 나타나지 않는다(반대도 동일).
- 수용 기준: 스위치 on/off 시 ChangeRecord(type=CONFIG_TOGGLE, 사용자, 시각, 이전/이후 값)가 남는다.

**2. 권한 체계**
`BatchControl/Request`, `BatchControl/Approve`, `BatchControl/RequestGrant`, `BatchControl/ViewHistory`, `BatchControl/Manage` 권한을 정의합니다.
Matrix와 Role 기반 권한 전략에 자동으로 노출되고, 관리자(Overall/Administer)는 본인 요청을 스스로 결재할 수 있습니다.
- 수용 기준: 각 권한이 Matrix Authorization 설정 화면에 "Batch Control" 그룹으로 표시된다.
- 수용 기준: `Manage`가 없는 사용자는 전역 설정·결재자 목록을 바꿀 수 없다.
- 수용 기준: 관리자 자가 결재 시 ApprovalRecord에 `selfApproved=true`가 기록된다.
- 전역 옵션 `allowAdminSelfApproval` 기본값 true. false면 관리자도 직무 분리 적용.

**3. 결재자 지정**
플러그인 설정에 결재 가능자 목록(사용자 ID)을 등록하고, 요청자는 그중 한 명을 골라 요청합니다.
결재 전까지 요청자가 결재자를 바꿀 수 있고, 변경 내역도 요청 이력에 남습니다.
- 수용 기준: 목록에 없는 사용자를 결재자로 지정하면 요청 생성이 거부된다.
- 수용 기준: 결재 시점에 결재자가 `Approve` 권한을 잃었으면 결재가 거부된다(목록 등재 + 권한 보유 둘 다 필요).
- 수용 기준: 요청자 본인을 결재자로 지정할 수 없다(관리자 자가 결재 허용 시 관리자 예외).
- 수용 기준: 결재자 변경 시 요청 이력에 (이전 결재자, 새 결재자, 변경자, 시각)이 남는다.

**4. 이력 저장소**
요청·결재·권한·실행·변경·오류를 빌드와 독립된 저장소(`$JENKINS_HOME/batch-control/`)에 기록해, 빌드가 삭제되어도 남습니다.
컨트롤러 재시작 후에도 대기 중인 요청과 유효한 권한 부여가 유지됩니다.
- 수용 기준: 빌드 보관 정책으로 빌드가 삭제된 뒤에도 해당 RunRecord와 관련 요청이 조회된다.
- 수용 기준: PENDING 요청이 있는 상태에서 재시작하면 요청이 그대로 PENDING으로 복구된다.
- 수용 기준: 승인됐지만 큐 투입 전 재시작된 요청은 재시작 후 자동으로 큐에 투입된다(중복 투입 없음).
- 수용 기준: 기록은 추가 전용이다. 수정·삭제 API가 없다(보관 기간 만료 삭제 제외).

### 실행 통제

**5. 실행 요청과 결재**
승인 대상 잡은 파라미터와 사유를 입력해 실행을 요청하고, 결재자가 파라미터 원본을 확인한 뒤 승인 또는 반려합니다.
승인되면 요청 당시 파라미터 그대로 자동 실행되며, 반려 시에는 사유가 필수입니다.
- 수용 기준: 사유가 비어 있으면 요청 생성이 거부된다.
- 수용 기준: 승인 후 실행된 빌드의 파라미터가 요청 시 저장된 파라미터와 정확히 일치한다.
- 수용 기준: 승인 후 파라미터를 바꾸는 경로가 없다. 바꾸려면 새 요청을 만들어야 한다.
- 수용 기준: 반려 사유가 비어 있으면 반려가 거부된다.
- 수용 기준: 실행된 빌드에는 요청 ID, 요청자, 결재자가 Cause와 빌드 Action으로 표시된다.
- 잡 단위 설정(JobProperty): `approvalRequired`(bool), 잡별 결재자 목록 제한(선택).

**6. 실행 경로 차단**
UI, REST API, CLI 등 승인 없는 수동 실행은 모두 큐 진입 단계에서 차단합니다.
cron 정기 실행과 상위 잡 연쇄 실행은 통과가 기본이며, 잡별로 조정할 수 있습니다.
- 수용 기준: 실행 통제 on + `approvalRequired` 잡에서 다음 경로가 모두 큐에 들어가지 않는다: 빌드 버튼, `/job/X/build` POST, `/job/X/buildWithParameters`, CLI `build`, Pipeline Replay, `build` 스텝으로 호출된 상위 잡(정책이 통과로 설정되지 않은 경우).
- 수용 기준: 승인된 요청의 투입(플러그인 내부 Cause 포함)은 통과된다.
- 수용 기준: TimerTrigger(cron) Cause는 기본 통과, 잡 설정 `blockTimer=true`면 차단.
- 수용 기준: UpstreamCause는 기본 통과, 잡 설정 `blockUpstream=true`면 차단. `allowedUpstreamJobs` 목록이 있으면 그 잡만 통과.
- 수용 기준: 차단 시 사용자에게 "승인 필요" 안내와 요청 화면 링크가 표시된다(조용한 실패 금지).
- 수용 기준: 승인 대상 잡의 사이드바에서 "Build Now"가 "Request Run"으로 대체된다.

**7. 요청 만료와 취소**
승인 대기 요청은 설정된 기간이 지나면 자동 만료되고, 요청자는 결재 전까지 취소할 수 있습니다.
승인 후 일정 시간 안에 실행되지 않은 건도 무효 처리해 오래된 승인으로 실행되는 것을 막습니다.
- 전역 설정: `pendingTimeoutHours`(기본 72), `approvedRunTimeoutMinutes`(기본 60).
- 수용 기준: 만료 시 상태가 EXPIRED로 바뀌고 이력에 남는다.
- 수용 기준: APPROVED 상태에서 `approvedRunTimeoutMinutes` 안에 큐 투입이 안 되면 EXPIRED가 된다(재시작 복구 지연은 예외로 허용: 복구 시점 기준으로 판정).
- 수용 기준: 취소는 요청자 본인 또는 `Manage` 권한자만 가능하고, PENDING 상태에서만 가능하다.

### 변경 통제

**8. 임시 권한 요청 (JIT)**
잡 생성·수정·삭제가 필요하면 대상 범위(잡 또는 폴더), 행위(CREATE/CONFIGURE/DELETE 중 다중 선택), 시간, 사유를 지정해 권한을 요청하고 결재를 받습니다.
승인되면 그 시간 동안 본인 계정으로 직접 작업하며, 만료 시각이 지나면 즉시 회수됩니다.
- 전역 설정: `grantDurationOptions`(기본 15, 30, 60분), `maxGrantMinutes`(기본 240).
- 수용 기준: 승인 즉시 요청자가 지정 범위에서 지정 행위의 Jenkins 권한(Item/Create, Item/Configure, Item/Delete)을 얻는다.
- 수용 기준: 지정 범위 밖 잡에는 권한이 생기지 않는다.
- 수용 기준: 만료 시각 경과 후 첫 권한 검사부터 거부된다(타이머 의존 없음).
- 수용 기준: 활성 권한이 있는 상태에서 재시작해도 만료 전이면 유지, 만료 후면 즉시 없음.
- 수용 기준: `Manage` 권한자는 활성 권한을 즉시 회수(revoke)할 수 있고 이력에 남는다.
- 수용 기준: 변경 통제 on 상태에서, 권한 부여 없이 Item/Configure·Create·Delete를 가진 사용자가 있으면 관리 화면에 경고(AdministrativeMonitor)가 표시된다.
- 수용 기준: Role Strategy가 전역 권한 전략으로 선택된 경우 관리 화면에 JIT 변경 통제 미지원 안내(AdministrativeMonitor)가 표시된다.
- 구현: 기존 권한 전략을 감싸는 위임형 AuthorizationStrategy. 관리자가 전역 보안 설정에서 선택.

**9. 변경 자동 기록**
생성·수정·삭제·이름변경·이동은 경로와 무관하게 누가·언제·무엇을 바꿨는지 자동으로 기록됩니다.
설정 변경은 변경 전후 config.xml diff를 남기고, 어떤 권한 부여 건에서 이뤄진 작업인지 연결합니다.
- 수용 기준: UI, REST(`config.xml` POST), CLI, Job DSL 경로의 변경이 모두 ChangeRecord로 남는다.
- 수용 기준: CONFIGURE 변경에 unified diff가 저장된다(비밀값은 마스킹).
- 수용 기준: 변경 시각에 변경자의 활성 Grant가 있으면 `grantId`가 연결되고, 없으면 `grantId=null`로 남아 "권한 부여 없는 변경"으로 조회된다.
- 수용 기준: 변경 통제 스위치가 꺼져 있어도 실행 통제가 켜져 있으면 변경 기록은 남는다(기록은 어느 스위치든 켜지면 활성).

### 운영 관리

**10. 실행 기록 대시보드**
모든 잡의 실행을 잡, 실행 원인, 사용자, 파라미터, 결과, 소요 시간과 함께 한 화면에서 봅니다.
중단된 실행은 누가 중단했는지 표시하고, 승인 건과 실행 결과가 한 줄로 이어집니다.
- 수용 기준: Freestyle과 Pipeline 빌드가 모두 기록된다.
- 수용 기준: 실행 원인이 USER / TIMER / UPSTREAM / APPROVED_REQUEST / SCM / OTHER로 분류된다.
- 수용 기준: ABORTED 빌드에 중단 사용자가 표시된다(Jenkins가 기록한 경우).
- 수용 기준: APPROVED_REQUEST 실행은 요청 ID로 요청 상세에 연결된다.
- 기본 화면: 최근 7일, 페이지당 50건.

**11. 오류 자동 등록과 처리**
FAILURE, UNSTABLE 결과는 사람 개입 없이 오류 건으로 자동 등록되고, 담당자가 확인·조치·완료 상태를 관리합니다.
오류 건에서 바로 재실행을 요청할 수 있고, 재실행이 성공하면 원래 건에 자동으로 연결됩니다.
- 전역 설정: `incidentResults`(기본 [FAILURE, UNSTABLE]; ABORTED 추가 가능).
- 수용 기준: 실행 원인과 무관하게(cron 포함) 해당 결과면 Incident가 생성된다.
- 수용 기준: Incident 상태는 OPEN → ACKNOWLEDGED → RESOLVED, 각 전이에 사용자·시각·코멘트가 남는다.
- 수용 기준: Incident에서 "재실행 요청" 시 원래 파라미터가 채워진 RunRequest가 생성되고 `incidentId`가 연결된다.
- 수용 기준: 연결된 재실행이 SUCCESS면 Incident에 `resolvedByRunId`가 자동 기록된다(상태 자동 변경은 하지 않음; 사람이 RESOLVED 처리).
- 수용 기준: 콘솔 로그 마지막 100줄이 Incident에 발췌 저장된다.

**12. 조회와 집계**
기간, 잡, 사용자, 결과, 처리 상태로 필터링해 조회하고 월 단위 집계 화면을 제공합니다.
감사·보고용으로 CSV 내보내기를 지원하며, 보관 기간은 전역 설정으로 정합니다.
- 전역 설정: `retentionMonths`(기본 24).
- 수용 기준: 실행 기록, 오류 건, 변경 기록, 요청 이력 각각에 기간 필터와 CSV 내보내기가 있다.
- 수용 기준: 월별 집계에 실행 수, 성공/실패/불안정 수, 오류 건 OPEN/RESOLVED 수, 요청 승인/반려 수가 나온다.
- 수용 기준: `ViewHistory` 권한이 없으면 모든 조회 화면과 CSV가 403이다.
- 수용 기준: 보관 기간 지난 월 파일은 주기 작업이 삭제하고, 삭제 사실을 ChangeRecord(type=RETENTION)로 남긴다.

### 확장 (2차)

**13. 알림**
요청 발생, 결재 완료, 권한 만료 임박, 오류 발생 시 관련자에게 알립니다.
이메일을 기본으로 하고 Slack 등은 확장 포인트로 붙입니다.

**14. REST API와 외부 연동**
요청·결재·이력 조회를 API로 제공해 사내 결재 시스템과 연동할 수 있게 합니다.
전역 설정은 JCasC, 승인 이벤트는 Audit Log 플러그인으로 내보낼 수 있게 합니다.

**15. 다단계 결재선**
단일 결재자 구조를 순차 결재선으로 확장합니다.
합의, 전결, 대결(부재 시 대리 결재)은 이후 검토 항목으로 둡니다.

## 3. 데이터 모델 (MVP)

```
RunRequest        id, jobFullName, parameters(Map), reason, requester, approver,
                  status(PENDING|APPROVED|REJECTED|CANCELLED|EXPIRED|EXECUTED),
                  createdAt, decidedAt, decisionComment, selfApproved,
                  approverChanges[{from,to,by,at}], incidentId?, executedRunId?
GrantRequest      id, scope{type: JOB|FOLDER, fullName}, actions[CREATE|CONFIGURE|DELETE],
                  durationMinutes, reason, requester, approver,
                  status(PENDING|APPROVED|REJECTED|CANCELLED|EXPIRED), createdAt, decidedAt, decisionComment
Grant             id, grantRequestId, user, scope, actions, grantedAt, expiresAt,
                  revokedAt?, revokedBy?
RunRecord         runId(jobFullName#number), jobFullName, number, causeType, user?,
                  parameters, result, startedAt, durationMs, abortedBy?, runRequestId?
Incident          id, runId, jobFullName, result, status(OPEN|ACKNOWLEDGED|RESOLVED),
                  transitions[{status,by,at,comment}], logTail, rerunRequestIds[], resolvedByRunId?
ChangeRecord      id, type(CREATE|CONFIGURE|DELETE|RENAME|MOVE|CONFIG_TOGGLE|RETENTION|GRANT_REVOKE),
                  target, user, at, grantId?, diff?, detail
```

## 4. 상태 머신

```
RunRequest:  PENDING -> APPROVED -> EXECUTED
             PENDING -> REJECTED | CANCELLED | EXPIRED
             APPROVED -> EXPIRED (approvedRunTimeout)
GrantRequest: PENDING -> APPROVED(=Grant 생성) | REJECTED | CANCELLED | EXPIRED
Grant:       ACTIVE -> EXPIRED(시각) | REVOKED(수동)
Incident:    OPEN -> ACKNOWLEDGED -> RESOLVED  (역방향 없음, RESOLVED에서 코멘트 추가는 가능)
```

## 5. 전역 설정 항목과 기본값

| 키 | 기본값 | 설명 |
|---|---|---|
| runControlEnabled | false | 실행 통제 스위치 |
| changeControlEnabled | false | 변경 통제 스위치 |
| approvers | [] | 결재 가능자 ID 목록 |
| allowAdminSelfApproval | true | 관리자 자가 결재 |
| pendingTimeoutHours | 72 | 대기 만료 |
| approvedRunTimeoutMinutes | 60 | 승인 후 미실행 만료 |
| grantDurationOptions | [15,30,60] | 권한 요청 시간 선택지(분) |
| maxGrantMinutes | 240 | 권한 상한 |
| incidentResults | [FAILURE, UNSTABLE] | 오류 등록 대상 |
| retentionMonths | 24 | 보관 기간 |

## 6. 비기능 요구

- 재시작 내구성: 4번 수용 기준.
- 성능: 하루 5,000 실행 규모에서 대시보드 최근 7일 조회가 2초 이내(로컬 기준).
- 보안: 모든 상태 변경은 POST + 권한 체크. CSRF crumb 준수. 비밀 파라미터(Password parameter)는 이력에 마스킹 저장.
- 호환: 최신 LTS 라인. Freestyle, Pipeline(WorkflowJob), Folder 지원. Multibranch는 기록만(통제 대상 아님, 문서에 명시).
