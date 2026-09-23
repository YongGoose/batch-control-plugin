# 테스트 매트릭스

test-author가 소유한다. Phase 2에서 SPEC의 모든 수용 기준을 행으로 옮기고, red-team 시나리오를 병합한다.

## 형식

| ID | SPEC | 계층 | 우선순위 | Given | When | Then | 테스트 파일 |
|---|---|---|---|---|---|---|---|

- ID: `T-<SPEC번호>-<일련>` (예: T-06-03). red-team 출신은 `T-RT-<일련>`. 전역 설정(5절)은 `T-CFG-*`, 비기능 보안(6절)·교차 보안은 `T-SEC-*`, 브라우저 E2E는 `T-E2E-*`.
- 계층: `unit`(Jenkins 불필요) / `integration`(JenkinsRule) / `e2e`(브라우저·REST, Phase 5)
- 우선순위: P0(릴리스 차단) / P1(릴리스 전 수정) / P2(다음 릴리스)
- 차단 단언 공통 기준: 큐 비어 있음 + `getNextBuildNumber()` 불변 + `waitUntilNoActivity()` 후 빌드 없음.

## 매트릭스 (Phase 2 — SPEC 1~12 수용 기준 전체)

| ID | SPEC | 계층 | 우선순위 | Given | When | Then | 테스트 파일 |
|---|---|---|---|---|---|---|---|
| T-01-01 | 1 | integration | P0 | 플러그인 설치, 스위치 모두 off, approvalRequired=true인 잡 | Build Now | 빌드가 정상 실행된다 | GlobalSwitchTest |
| T-01-02 | 1 | integration | P0 | runControlEnabled=false | 관리자가 true로 변경 | ChangeRecord(CONFIG_TOGGLE, admin, false→true)가 남는다 | GlobalSwitchTest |
| T-01-03 | 1 | integration | P0 | 스위치 모두 off | 잡 설정 변경 + 잡 삭제 | 이전과 동일하게 성공한다(차단 없음) | GlobalSwitchTest |
| T-01-04 | 1 | integration | P1 | runControlEnabled=true, changeControlEnabled=false | 잡 설정 변경·삭제 + 잡 페이지 조회 | 변경 통제 차단 없음(성공), 변경 통제 관련 UI가 나타나지 않는다 | GlobalSwitchTest |
| T-01-05 | 1 | integration | P1 | changeControlEnabled=true, runControlEnabled=false, approvalRequired=true 잡 | Build Now + 잡 페이지 조회 | 빌드 실행됨(실행 차단 없음), Request Run 등 실행 통제 UI가 나타나지 않는다 | GlobalSwitchTest |
| T-01-06 | 1 | integration | P1 | runControlEnabled=true | 관리자가 false로 변경 | ChangeRecord(CONFIG_TOGGLE, admin, true→false)가 남는다 | GlobalSwitchTest |
| T-02-01 | 2 | integration | P0 | Manage 없는 사용자 | 전역 설정 POST | 403 | PermissionsTest |
| T-02-02 | 2 | integration | P1 | Matrix Authorization 전략 활성 | 권한 설정 화면 조회 | "Batch Control" 그룹에 Request/Approve/RequestGrant/ViewHistory/Manage 5종이 표시된다 (DOM 존재 기준, 시각 확인은 Phase 5) | PermissionsTest |
| T-02-03 | 2 | integration | P0 | 관리자(Overall/Administer), allowAdminSelfApproval=true(기본) | 본인 요청을 본인이 승인 | 승인 성공, 요청에 selfApproved=true 기록 | RunRequestServiceTest |
| T-02-04 | 2 | integration | P0 | allowAdminSelfApproval=false | 관리자가 본인 요청 승인 시도 | 거부된다(직무 분리 적용), 상태 PENDING 유지 | RunRequestServiceTest |
| T-02-05 | 2 | integration | P1 | Manage 권한 있는 사용자 | 전역 설정·결재자 목록 POST | 200, 저장된다 | PermissionsTest |
| T-03-01 | 3 | integration | P0 | approvers=[a1], 요청자 u1 | u1이 결재자 u2 지정 | 요청 생성 거부 | RunRequestServiceTest |
| T-03-02 | 3 | integration | P0 | 요청자 u1 | u1이 결재자 u1 지정 | 거부 (관리자 아님) | RunRequestServiceTest |
| T-03-03 | 3 | integration | P0 | approvers=[a1], a1 지정 PENDING 요청, 이후 a1의 Approve 권한 회수 | a1이 승인 시도 | 결재 거부 (목록 등재 + 권한 보유 둘 다 필요) | RunRequestServiceTest |
| T-03-04 | 3 | integration | P1 | approvers=[a1,a2], 결재자 a1인 PENDING 요청 | 요청자가 결재자를 a2로 변경 | 성공, 요청 이력에 (a1, a2, 변경자, 시각)이 남는다 | RunRequestServiceTest |
| T-03-05 | 3 | integration | P1 | 이미 결재된(APPROVED) 요청 | 요청자가 결재자 변경 시도 | 거부 (결재 전까지만 변경 가능) | RunRequestServiceTest |
| T-03-06 | 3 | integration | P1 | 관리자, allowAdminSelfApproval=true | 관리자가 본인을 결재자로 지정해 요청 | 요청 생성 성공 (관리자 예외) | RunRequestServiceTest |
| T-04-01 | 4 | integration | P0 | PENDING 요청 1건 | Jenkins 재시작(JenkinsSessionRule) | PENDING 그대로 복구 | StoreDurabilityTest |
| T-04-02 | 4 | integration | P0 | APPROVED, 큐 투입 전 | 재시작 | 재시작 후 정확히 1회 투입 | RestartRecoveryTest |
| T-04-03 | 4 | integration | P0 | 승인 요청으로 실행 완료된 빌드 #N, RunRecord 존재 | 빌드 #N 삭제(보관 정책 상당) | RunRecord와 관련 요청이 여전히 조회된다 | StoreDurabilityTest |
| T-04-04 | 4 | integration | P1 | 저장된 RunRecord·ChangeRecord | 수정·삭제 HTTP 엔드포인트 호출 시도 | 404 또는 405 — 수정·삭제 API가 존재하지 않는다(추가 전용) | RunRequestWebTest |
| T-05-01 | 5 | integration | P0 | 승인 대상 잡, 파라미터 {DATE=2026-09-01} | 요청→승인 | 실행된 빌드 파라미터 == {DATE=2026-09-01} | RunRequestServiceTest |
| T-05-02 | 5 | integration | P0 | 요청 폼 | 사유 빈 값 | 거부 | RunRequestServiceTest |
| T-05-03 | 5 | integration | P0 | APPROVED 요청 | 저장된 파라미터 변경 엔드포인트/폼 접근 시도 | 존재하지 않음(404/405) — 바꾸려면 새 요청 필요 | RunRequestWebTest |
| T-05-04 | 5 | integration | P0 | PENDING 요청, 지정 결재자 | 사유 빈 값으로 반려 POST | 반려 거부, 상태 PENDING 유지 | RunRequestServiceTest |
| T-05-05 | 5 | integration | P0 | 요청→승인→실행 완료 | 빌드의 Cause·Action 조회 | 요청 ID, 요청자, 결재자가 Cause와 빌드 Action으로 표시된다 | RunRequestServiceTest |
| T-05-06 | 5 | integration | P1 | 전역 approvers=[a1,a2], JobProperty 잡별 결재자 제한=[a1] | a2를 결재자로 지정해 요청 | 요청 생성 거부 | RunRequestServiceTest |
| T-06-01 | 6 | integration | P0 | 통제 on, approvalRequired | POST /job/X/build | 큐 비어 있음, nextBuildNumber 불변, 빌드 없음 | QueueBlockTest |
| T-06-02 | 6 | integration | P0 | 동일 | POST /job/X/buildWithParameters | 큐 비어 있음, nextBuildNumber 불변, 빌드 없음 | QueueBlockTest |
| T-06-03 | 6 | integration | P0 | 동일 | CLI build X | 큐 비어 있음, nextBuildNumber 불변, 빌드 없음 | QueueBlockTest |
| T-06-04 | 6 | integration | P0 | 동일, Pipeline 잡 | Replay | 큐 비어 있음, nextBuildNumber 불변, 빌드 없음 | QueueBlockTest |
| T-06-05 | 6 | integration | P0 | 동일, 상위 잡 Y가 build 스텝으로 X 호출, blockUpstream=true | Y 실행 | X 큐 진입 없음, X nextBuildNumber 불변 | QueueBlockTest |
| T-06-06 | 6 | integration | P0 | 동일, TimerTrigger | cron 발화 | 실행됨 | QueueBlockTest |
| T-06-07 | 6 | integration | P0 | 동일, blockTimer=true | cron 발화 | 차단 — 큐 비어 있음, 빌드 없음(무인 Cause는 조용히 거부 + 로그) | QueueBlockTest |
| T-06-08 | 6 | integration | P0 | 승인된 요청 | 플러그인 투입 | 실행됨 | QueueBlockTest |
| T-06-09 | 6 | integration | P0 | 통제 on, approvalRequired | 잡 페이지의 빌드 버튼(Build Now 앵커) 클릭 (WebClient) | 큐 비어 있음, nextBuildNumber 불변, 빌드 없음 | QueueBlockTest |
| T-06-10 | 6 | integration | P0 | 통제 on, approvalRequired 잡 X, blockUpstream 기본값(false), 상위 잡 Y가 build 스텝으로 X 호출 | Y 실행 | X 실행됨 (UpstreamCause 기본 통과) | QueueBlockTest |
| T-06-11 | 6 | integration | P0 | blockUpstream=true, allowedUpstreamJobs=[Y] | Y가 build 스텝으로 X 호출 | X 실행됨 (허용 목록 통과) | QueueBlockTest |
| T-06-12 | 6 | integration | P0 | blockUpstream=true, allowedUpstreamJobs=[Y], 상위 잡 Z | Z가 build 스텝으로 X 호출 | X 큐 진입 없음, 상위 잡 Z는 FAILURE로 종료(PoC 확인 부수 효과) | QueueBlockTest |
| T-06-13 | 6 | integration | P0 | 통제 on, approvalRequired | POST /job/X/build (사용자 유래 Cause) | HTTP 400 + 응답 본문에 "승인 필요" 안내와 요청 화면 링크 표시 (조용한 실패 금지) | RunRequestWebTest |
| T-06-14 | 6 | integration | P0 | 동일 | CLI build X | exit code 1, stderr에 승인 필요 안내 메시지 포함 | RunRequestWebTest |
| T-06-15 | 6 | integration | P1 | 통제 on, approvalRequired (Freestyle과 Pipeline 각각) | 잡 페이지 HTML 조회 (WebClient) | "Build Now" 문자열 없음, "Request Run" 사이드바 링크 렌더링 | RunRequestWebTest |
| T-06-16 | 6 | integration | P0 | 통제 on, approvalRequired=false인 잡 | POST /job/X/build | 빌드 정상 실행 (통제 대상 아닌 잡은 영향 없음) | QueueBlockTest |
| T-07-01 | 7 | integration | P0 | pendingTimeoutHours=1, PENDING 요청 | 시각을 2시간 후로 (Clock 주입) + PeriodicWork 실행 | EXPIRED, 이력에 남는다 | ExpiryAndCancelTest |
| T-07-02 | 7 | integration | P0 | PENDING 요청, 요청자 u1 | u2가 취소 | 403 | ExpiryAndCancelTest |
| T-07-03 | 7 | integration | P0 | approvedRunTimeoutMinutes=60, APPROVED 후 큐 투입되지 않음 | Clock을 61분 후로 + 만료 판정 실행 | EXPIRED, 이력에 남는다 | ExpiryAndCancelTest |
| T-07-04 | 7 | integration | P0 | APPROVED 미투입 요청, 정지 상태로 61분 이상 경과 | 재시작(JenkinsSessionRule) 후 복구 | 복구 시점 기준으로 판정되어 정상 투입된다(EXPIRED 아님 — 재시작 복구 지연 예외) | RestartRecoveryTest |
| T-07-05 | 7 | integration | P0 | PENDING 요청, 요청자 u1 | u1이 취소 POST | 상태 CANCELLED, 이력에 남는다 | ExpiryAndCancelTest |
| T-07-06 | 7 | integration | P1 | PENDING 요청, Manage 권한자 m1(요청자 아님) | m1이 취소 POST | 상태 CANCELLED | ExpiryAndCancelTest |
| T-07-07 | 7 | integration | P0 | APPROVED 요청, 요청자 u1 | u1이 취소 시도 | 거부 (취소는 PENDING 상태에서만 가능) | ExpiryAndCancelTest |
| T-08-01 | 8 | integration | P0 | Matrix: u1은 Item/Read만. Grant(u1, JOB X, CONFIGURE, 30분) 승인 | u1이 X config POST | 200, 저장됨 | GrantServiceTest |
| T-08-02 | 8 | integration | P0 | 동일 | u1이 잡 Y config POST | 403 | GrantServiceTest |
| T-08-03 | 8 | integration | P0 | 동일, Clock을 31분 후로 | u1이 X config POST | 403 (만료 후 첫 권한 검사부터 거부, 타이머 의존 없음) | GrantServiceTest |
| T-08-04 | 8 | integration | P0 | 활성 Grant | 재시작(만료 전) | 여전히 유효 | GrantRestartTest |
| T-08-05 | 8 | integration | P0 | 활성 Grant | Manage 권한자가 revoke | 즉시 403, ChangeRecord(GRANT_REVOKE) | GrantServiceTest (service) + GrantWebTest (POST endpoint) |
| T-08-06 | 8 | integration | P1 | 변경 통제 on, u3에게 Item/Configure 직접 부여 | 관리 화면 | AdministrativeMonitor 경고 표시 | GrantMonitorsTest (isActivated 기준, 비고 17) |
| T-08-07 | 8 | integration | P0 | 활성 Grant(30분), 정지 상태로 31분 이상 경과 | 재시작 후 u1이 X config POST | 403 (재시작 후 만료 시각이 지난 Grant는 즉시 없음) | GrantRestartTest |
| T-08-08 | 8 | integration | P1 | Role Strategy가 전역 권한 전략으로 선택됨 | 관리 화면 조회 | JIT 변경 통제 미지원 안내 AdministrativeMonitor 표시 | GrantMonitorsTest (isActivated 기준, 비고 17) |
| T-08-09 | 8 | integration | P0 | maxGrantMinutes=240(기본) | durationMinutes=300으로 권한 요청 생성 | 요청 생성 거부 | GrantServiceTest |
| T-08-10 | 8 | integration | P0 | Grant(u1, JOB X, [CONFIGURE]) 활성 | u1이 X 삭제 시도 | 거부 (부여하지 않은 행위는 계속 거부) | GrantServiceTest (delete-veto 포함) |
| T-08-11 | 8 | integration | P0 | Grant(u1, FOLDER "team/batch", [CREATE, CONFIGURE]) 활성 | u1이 폴더 내 잡 생성·설정 / 폴더 밖 잡 생성 시도 | 폴더 내 성공, 폴더 밖 거부 | GrantServiceTest |
| T-08-12 | 8 | integration | P1 | pendingTimeoutHours=1, PENDING GrantRequest | Clock 2시간 후 + PeriodicWork 실행 | GrantRequest가 EXPIRED로 바뀐다 | GrantServiceTest |
| T-08-13 | 8 | integration | P0 | RequestGrant 권한 없는 사용자 | 권한 요청 생성 POST | 403 | GrantWebTest (비고 18) |
| T-09-01 | 9 | integration | P0 | 잡 X | UI로 설정 변경 | ChangeRecord(CONFIGURE, diff 포함) | ChangeRecordTest |
| T-09-02 | 9 | integration | P0 | 잡 X | POST config.xml | ChangeRecord(CONFIGURE) | ChangeRecordTest |
| T-09-03 | 9 | integration | P0 | 잡 X, 활성 Grant g1 | 설정 변경 | ChangeRecord.grantId == g1 | ChangeRecordTest |
| T-09-04 | 9 | integration | P0 | 비밀 파라미터 기본값 변경 | 설정 변경 | diff에 비밀값 없음, 마스킹 | ChangeRecordTest |
| T-09-05 | 9 | integration | P1 | 잡 X | CLI update-job | ChangeRecord(CONFIGURE) | ChangeRecordTest |
| T-09-06 | 9 | integration | P1 | Job DSL 시드 잡 | DSL 실행으로 잡 수정 | ChangeRecord(CONFIGURE) | ChangeRecordTest |
| T-09-07 | 9 | integration | P0 | 기록 활성(어느 스위치든 on) | 잡 생성, 잡 삭제 | ChangeRecord(CREATE)와 ChangeRecord(DELETE)가 각각 남고 user·at 정확 | ChangeRecordTest |
| T-09-08 | 9 | integration | P1 | 잡 X, 폴더 F | X 이름변경, X를 F로 이동 | ChangeRecord(RENAME), ChangeRecord(MOVE) | ChangeRecordTest |
| T-09-09 | 9 | integration | P1 | 활성 Grant가 없는 사용자(관리자) | 설정 변경 | ChangeRecord.grantId == null, "권한 부여 없는 변경"으로 조회된다 | ChangeRecordTest |
| T-09-10 | 9 | integration | P1 | changeControlEnabled=false, runControlEnabled=true | 잡 설정 변경 | ChangeRecord가 남는다 (기록은 어느 스위치든 켜지면 활성) | ChangeRecordTest |
| T-09-11 | 9 | integration | P2 | 두 스위치 모두 off | 잡 설정 변경 | ChangeRecord가 남지 않는다 | ChangeRecordTest |
| T-10-01 | 10 | integration | P0 | Freestyle 1회, Pipeline 1회 실행 | 대시보드 조회 | 2건, causeType 정확 | RunRecordListenerTest |
| T-10-02 | 10 | integration | P1 | 실행 중 빌드 | u1이 중단 | RunRecord.abortedBy == u1 | RunRecordListenerTest |
| T-10-03 | 10 | integration | P1 | USER/TIMER/UPSTREAM/APPROVED_REQUEST/SCM 원인으로 각각 실행 + 기타 Cause 1건 | RunRecord 조회 | causeType이 각각 USER/TIMER/UPSTREAM/APPROVED_REQUEST/SCM으로, 기타는 OTHER로 분류된다 | RunRecordListenerTest |
| T-10-04 | 10 | integration | P0 | 승인 요청으로 실행된 빌드 | RunRecord와 요청 조회 | causeType=APPROVED_REQUEST, runRequestId로 요청 상세에 연결, 요청의 executedRunId 설정 | RunRecordListenerTest |
| T-10-05 | 10 | integration | P0 | ViewHistory 없는 사용자 | 대시보드 GET | 403 | RunRecordListenerTest |
| T-10-06 | 10 | e2e | P2 | 8일 전 실행 1건 + 최근 실행 60건 | 대시보드 기본 화면 | 최근 7일 건만 표시, 페이지당 50건 페이징 | Phase 5 (e2e) |
| T-10-07 | 10 | integration | P2 | Multibranch 하위 잡 | 브랜치 잡 실행 | RunRecord 기록됨, 실행 통제는 적용되지 않음(기록만 — SPEC 6절 호환) | RunRecordListenerTest (approximated, note 22) |
| T-11-01 | 11 | integration | P0 | cron 잡이 FAILURE | 완료 | Incident OPEN 생성, logTail 100줄 이하 | IncidentTest |
| T-11-02 | 11 | integration | P0 | Incident i1 | 재실행 요청→승인→SUCCESS | i1.resolvedByRunId 설정, 상태는 OPEN 유지 | IncidentTest |
| T-11-03 | 11 | integration | P0 | incidentResults=[FAILURE] | UNSTABLE 완료 | Incident 없음 | IncidentTest |
| T-11-04 | 11 | integration | P1 | OPEN Incident | u1이 ACKNOWLEDGED(코멘트) → u2가 RESOLVED(코멘트) | 각 전이에 사용자·시각·코멘트가 transitions에 남는다 | IncidentTest |
| T-11-05 | 11 | integration | P1 | RESOLVED Incident | OPEN 또는 ACKNOWLEDGED로 되돌리기 시도 | 거부 (역방향 전이 없음), RESOLVED 상태에서 코멘트 추가는 가능 | IncidentTest (note 24) |
| T-11-06 | 11 | integration | P1 | 파라미터 {DATE=2026-09-01} 빌드의 FAILURE로 생성된 Incident i1 | i1에서 "재실행 요청" | 원래 파라미터가 채워진 RunRequest 생성, incidentId=i1 연결, i1.rerunRequestIds에 추가 | IncidentTest |
| T-11-07 | 11 | integration | P2 | incidentResults=[FAILURE, UNSTABLE, ABORTED] | ABORTED 완료 | Incident 생성 | IncidentTest |
| T-12-01 | 12 | integration | P0 | ViewHistory 없는 사용자 | GET /batch-control/history | 403 | HistoryWebTest |
| T-12-02 | 12 | integration | P1 | retentionMonths=1, 3개월 전 runs 파일 | RetentionPeriodicWork | 파일 삭제 + ChangeRecord(RETENTION) | HistoryWebTest |
| T-12-03 | 12 | integration | P1 | 실행 기록·오류 건·변경 기록·요청 이력 데이터 각각 존재 | 각 화면에서 기간 필터 적용 + CSV 내보내기 | 4종 모두 필터 결과 정확, CSV 다운로드 성공 | HistoryWebTest |
| T-12-04 | 12 | integration | P1 | 한 달간 실행 SUCCESS 2 / FAILURE 1 / UNSTABLE 1, 요청 승인 1 / 반려 1, Incident OPEN 1 / RESOLVED 1 | 월별 집계 조회 | 실행 수·성공/실패/불안정 수·오류 OPEN/RESOLVED 수·승인/반려 수가 모두 정확 | HistoryWebTest (note 23) |
| T-12-05 | 12 | integration | P0 | ViewHistory 없는 사용자 | CSV 내보내기 엔드포인트 호출 (4종 각각) | 403 | HistoryWebTest |
| T-CFG-01 | 5절 | integration | P1 | 신규 설치 (설정 저장 이력 없음) | 전역 설정 조회 | 기본값이 SPEC 5절 표와 일치: runControlEnabled=false, changeControlEnabled=false, approvers=[], allowAdminSelfApproval=true, pendingTimeoutHours=72, approvedRunTimeoutMinutes=60, grantDurationOptions=[15,30,60], maxGrantMinutes=240, incidentResults=[FAILURE,UNSTABLE], retentionMonths=24 | GlobalConfigDefaultsTest |
| T-CFG-02 | 5절 | integration | P1 | 전역 설정을 기본값과 다르게 저장 | 재시작 | 저장한 값 유지 | GlobalConfigDefaultsTest |
| T-CFG-03 | 5절 | integration | P2 | 전역 설정 폼 | pendingTimeoutHours에 음수/0 등 잘못된 값 저장 시도 | 거부되거나 기존 값 유지 (임의 값으로 저장되지 않음) | GlobalConfigDefaultsTest |
| T-SEC-01 | 6 | integration | P0 | 결재자 | GET /batch-control/requests/<id>/approve | 405 또는 거부 (POST만) | RunRequestWebTest |
| T-SEC-02 | 5 | integration | P0 | Approve 권한 없는 사용자 | POST approve | 403 | RunRequestWebTest |
| T-SEC-03 | 8 | unit | P0 | scope=FOLDER "team/batch" | item "team/batch-other" | 범위 밖 판정 (prefix 오판 방지) | PathCodecTest |
| T-SEC-04 | 4 | unit | P0 | 잡 이름 "../x", 제어문자 포함 이름, 255자 초과 초장문 이름 (RT-12 확장) | 스냅숏·변경 파일 경로 계산 | "../x"는 예외(경로 탈출 차단), 제어문자·초장문 이름은 안전하게 인코딩되어 기록 누락·타 잡 파일 덮어쓰기가 없다 | PathCodecTest |
| T-SEC-05 | 6절 | integration | P0 | 인증된 사용자, CSRF crumb 없음 | 상태 변경 POST (요청 생성·승인 등) | 403 (crumb 필수) | RunRequestWebTest |
| T-SEC-06 | 6절 | integration | P0 | 인증된 사용자 | GET으로 상태 변경 엔드포인트 호출 (reject, cancel, revoke, Incident 전이, 스위치 변경) | 각각 405 또는 거부 (모든 상태 변경은 POST + 권한 체크) | RunRequestWebTest (reject·cancel) + GrantWebTest (revoke) + HistoryWebTest (incident transitions, switch toggle — note 25) |
| T-SEC-07 | 6절 | integration | P0 | Password 파라미터를 가진 잡 | 요청→승인→실행 후 요청 상세·RunRecord·CSV 조회 | 비밀값이 어디에도 평문으로 노출되지 않는다 (마스킹 저장) | pending P-03 decision |
| T-SEC-08 | 6절 (S-01/P-09) | integration | P0 | Request holders u2 (no Item/Read on jobA) and u3 (Item/Read on jobA), u1's request on jobA, designated approver a1, MANAGE m1 | each GETs the requests list and u1's request detail URL | u2: list silently filtered (no id, no reason text), detail 404 (same as nonexistent); u3, a1, m1: visible (list contains id, detail 200) | SecurityRegressionTest |
| T-SEC-09 | 6절 (S-01/P-09) | integration | P0 | RequestGrant holders g1 (own request+grant), g2 (nothing own, Item/Read everywhere), g3 (own request+grant), approver a1, MANAGE m1 | each GETs the grants screen; g2 GETs g1's grant-request detail URL | g2: no foreign request/grant id listed, foreign detail 404; g1: own request+grant only (not g3's); a1: both assigned requests; m1: everything incl. both active grants | SecurityRegressionTest |
| T-SEC-10 | 11 (S-06) | integration | P1 | user with ViewHistory+Request but NO Item/Read on the failed job, OPEN incident | POST incidents/&lt;id&gt;/rerun | 403; no RunRequest created, incident.rerunRequestIds stays empty | SecurityRegressionTest |
| T-SEC-11 | 5 (S-07) | integration | P1 | user with Item/Read only (no BatchControl/Request), approval-required job | GET /job/X/batch-control/ | 403; a Request holder still gets 200 | SecurityRegressionTest |
| T-SEC-12 | 8 (S-03) | integration | P0 | RequestGrant holder | GrantRequestService.create with scope fullName "" (JOB and FOLDER types) | rejected (IllegalArgumentException/Failure) — root-scope grants are not supported; nothing stored | SecurityRegressionTest |
| T-SEC-13 | 8 (S-11) | integration | P2 | a BatchControlAuthorizationStrategy instance | construct another BatchControlAuthorizationStrategy with it as delegate | IllegalArgumentException (self-nesting guard) | SecurityRegressionTest |
| T-SEC-14 | 8 (S-05) | integration | P2 | change control on, wrapper over matrix delegate, non-admin with direct Item/Configure | isActivated() twice in a row; then toggle changeControlEnabled off/on | back-to-back calls agree; cached result flips promptly after each toggle (cache invalidation) | SecurityRegressionTest |
| T-SEC-15 | 2, 5 (6절) | integration | P0 | run control on, approval-required job, approvers=[a1]; user u1 with Jenkins/Read + Item/Read but NO BatchControl/Request | POST `job/X/batch-control/submit` with a valid crumb and a complete valid form (reason + approver=a1), bypassing the UI form u1 cannot load (T-SEC-11 covers the GET form only) | 403; no RunRequest created (store unchanged); no build started and getNextBuildNumber() unchanged (no silent no-op) | SecurityRegressionTest |
| T-E2E-01 | 5,6 | e2e | P0 | requester/approver 계정 | requester 요청 → approver 승인 | 빌드 실행, 대시보드에 요청 ID 연결 표시 | |
| T-E2E-02 | 6 | e2e | P0 | approvalRequired 잡 | requester가 사이드바 확인 | "Build Now" 없음, "Request Run" 있음 | |
| T-E2E-03 | 8 | e2e | P0 | requester | 권한 요청→승인→설정 화면 | 저장 성공, 만료 후 저장 403 안내 | |
| T-E2E-04 | 12 | e2e | P1 | 실행 10건 | CSV 내보내기 | 10행 + 헤더, 파라미터 열 포함 | |
| T-E2E-05 | 5 | e2e | P1 | approvalRequired 잡(최근 실행 이력 있음), 요청자·사유·파라미터를 담은 PENDING 요청 | approver가 결재 화면 접속 | 잡 이름·요청자·사유·파라미터 원본·해당 잡의 최근 실행 결과가 한 화면에 함께 표시된다 | |
| T-E2E-06 | 8 | e2e | P1 | CONFIGURE Grant가 승인됐다가 만료된 requester, 잡 설정 화면을 열어 둔 상태 | 설정 저장 시도 | 권한 창 만료 안내가 표시되고 권한 재요청 링크가 보인다 | |
| T-E2E-07 | 10 | e2e | P1 | 승인 요청으로 실행된 빌드 1건(대시보드에 APPROVED_REQUEST 행 표시) | 대시보드에서 해당 행의 요청 링크 클릭 | 해당 요청 상세 화면으로 이동한다(요청 ID 일치) | |
| T-E2E-08 | 3 | e2e | P1 | 실행 통제 on, 전역 설정 approvers가 빈 목록, approvalRequired 잡 (R-1 관련) | requester가 실행 요청 화면 접속 | 결재자 목록이 비어 있다는 경고가 화면에 표시된다 | |
| T-RT-01 | 6 | integration | P0 | 통제 on, 보호 잡 B(approvalRequired, blockUpstream=true, allowedUpstreamJobs 미설정/빈 목록), 통제 off 잡 A가 build 스텝으로 B 호출 | A 실행 | B 큐 진입 없음 — 빈/미설정 허용 목록은 "전부 차단"으로 해석된다 (SPEC 보강 필요: R-1 — 미설정 시 동작과 Upstream 기본 개방 정책 명확화) | RequestIntegrityTest |
| T-RT-02 | 6 | integration | P0 | Job X's approved-run marker (ApprovedRunAction) already consumed by one queue submission | Re-submission of the same marker/equivalent Cause to another job Y, or re-queue/rebuild of the same job | Not admitted — the marker is bound to requestId+jobFullName and consumed by exactly one queue submission (D-23/R-8; wording aligned in S3, note 20) | RequestIntegrityTest |
| T-RT-03 | 5 | integration | P0 | 잡 A에 대한 APPROVED 요청(큐 투입 전) | A를 A2로 rename, 다른 잡 B를 A로 rename(스왑)한 뒤 투입 시점 도달 | 투입이 거부되거나 원래 잡 신원에만 실행된다 — 결재자가 검토한 잡과 다른 잡은 절대 실행되지 않는다 (SPEC 보강 필요: R-6) | RequestIntegrityTest |
| T-RT-05 | 8 | integration | P0 | 실행 통제 on, u1에게 FOLDER "team/batch" [CREATE,CONFIGURE] Grant(30분) 활성 | u1이 권한 창 안에서 cron(TimerTrigger) 잡 J 생성, Clock을 만료 후로 이동, cron 발화 | 창 만료 후 J의 cron 실행이 승인 통제를 우회하지 않는다(예: 권한 창 내 생성 잡은 approvalRequired/blockTimer 기본 활성) (SPEC 보강 필요: R-2) | GrantWindowAbuseTest (비고 19) |
| T-RT-06 | 8 | integration | P0 | 만료 임박 CONFIGURE Grant(Clock 제어), 다수 잡을 쓰는 일괄 변경 작업(Job DSL seed 상당) | 만료 경계에 걸치도록 일괄 변경 실행 | 만료 시각 이후의 write는 잡 단위로 재검사되어 각각 거부된다(진입 시 1회 검사에 편승 불가) | GrantWindowAbuseTest |
| T-RT-07 | 3 | integration | P1 | approvers=[a1,a2,a3], 결재자 a1인 PENDING 요청 | 요청자가 결재자를 a1→a2→a3로 연속 변경 후 a3가 승인 | 각 변경이 approverChanges에 (from,to,by,at)로 빠짐없이 남고, 최종 결재자 a3의 승인만 유효하다(감사 추적 보장) | RunRequestServiceTest |
| T-RT-10 | 6절 | integration | P1 | 사유·파라미터 값·(권한 창) 잡 이름에 `<script>`·`<img onerror>` 페이로드를 담은 요청 | 요청 상세·결재 화면·대시보드 렌더링(WebClient) | 페이로드가 이스케이프되어 텍스트로만 표시된다 — 스크립트 실행·태그 삽입 없음 (SPEC 보강 필요: R-3 상당의 출력 무해화 수용 기준 없음 — CLAUDE.md 규약만 존재) | XssEscapingTest (note 27) |
| T-RT-11 | 12 | integration | P1 | 사유가 `=1+1`, 파라미터 값이 `+HYPERLINK(...)`·`@SUM(...)`·`-2+3`으로 시작하는 요청·실행 기록 | 4종 CSV 내보내기 | 해당 셀이 수식으로 해석되지 않도록 무해화된다(선행 `= + - @` 이스케이프/`'` 프리픽스/인용) (SPEC 보강 필요: R-3) | HistoryWebTest (note 26) |
| T-RT-13 | 11 | integration | P1 | 콘솔에 비밀 토큰 문자열을 출력하고 FAILURE로 끝나는 빌드 | Incident 생성 후 logTail 조회 | Secret 값·알려진 비밀 패턴이 logTail에 평문으로 남지 않는다(마스킹) (SPEC 보강 필요: R-4 — 마스킹 규칙 추가 또는 한계 문서화 결정) | IncidentTest |
| T-RT-14 | 5 | integration | P0 | PENDING 요청 1건, 승인 POST 2건 준비 | 두 승인 POST를 동시에 실행 | 정확히 1건만 APPROVED가 되고 빌드는 정확히 1회만 투입된다(상태 전이 compare-and-set) (SPEC 보강 필요: R-5) | RequestConcurrencyTest |
| T-RT-15 | 7 | integration | P0 | PENDING 요청, 요청자의 취소 POST와 결재자의 승인 POST 준비 | 동시에 실행 | 둘 중 하나만 성립한다(선착 CAS). 취소가 이기면 어떤 빌드도 투입되지 않고, CANCELLED 요청에 executedRunId가 남는 등의 상태 혼합이 없다 (SPEC 보강 필요: R-5) | RequestConcurrencyTest |
| T-RT-16 | 7 | integration | P0 | approvedRunTimeoutMinutes 경계의 APPROVED 요청 | 큐 투입 시도와 ExpiryPeriodicWork 만료 처리를 동시에 진행 | 투입 직전 시각 재확인(check-at-submit)으로 만료 요청은 절대 투입되지 않고, 상태는 EXPIRED/EXECUTED 중 정확히 하나로 수렴한다 (SPEC 보강 필요: R-5) | ExpiryAndCancelTest (비고 13 근사) |
| T-RT-17 | 4 | integration | P0 | 승인 후 scheduleBuild2 완료·onStarted 미도달(executedRunId=null) 상태에서 재시작(JenkinsSessionRule) | 복구 실행 | 요청당 빌드가 정확히 1회만 존재한다 — 재시작 후 복원된 큐 항목과 복구 재투입이 requestId 기반 idempotency로 중복 제거된다 (SPEC 보강 필요: R-5, R-8) | RestartRecoveryTest (비고 11 근사) |
| T-RT-18 | 6절 | integration | P2 | Request/RequestGrant 권한만 가진 사용자 | 단시간에 대량(N건) RunRequest·GrantRequest 생성 POST 반복 | 사용자당 PENDING 상한/rate limit으로 거부되거나, 목록 조회·재시작 복구 성능이 한계 내로 유지된다 (SPEC 보강 필요: R-7) | deferred (D-22) |
| T-RT-19 | 6절 | integration | P1 | 수 MB급 사유·파라미터 값을 담은 요청 생성 POST | 요청 생성 | 필드별 크기 상한으로 거부되거나 안전하게 절단 저장된다(요청 파일·인메모리 캐시·RunRecord 비대화 방지) (SPEC 보강 필요: R-7) | RequestIntegrityTest |
| T-RT-20 | 9 | integration | P2 | 활성 CONFIGURE Grant | 동일 잡 config를 짧은 시간에 수백 회 반복 저장(REST config.xml POST 루프) | 변경 기록(diff/스냅숏)이 누락 없이 baseline 정합을 유지하고, 기록 파이프라인이 다른 잡의 기록을 블로킹하지 않는다 (SPEC 보강 필요: R-7) | GrantWindowAbuseTest (bounded: 30 rapid POSTs, 비고 19) |

## 비고 (전제와 요청 사항)

1. **Clock 주입 API 필요.** T-07-01/03/04, T-08-03/07/12, T-12-02는 시간 경과를 실제 대기 없이 검증해야 한다(`Thread.sleep` 금지). SPEC의 "타이머 의존 없음, 검사 시점 시각 비교" 문장을 근거로, 플러그인이 `java.time.Clock`을 주입받고 테스트에서 고정/이동 가능한 교체 진입점을 제공한다고 전제한다. → 요청: core-dev에 테스트용 Clock 교체 API.
2. **PeriodicWork 수동 트리거.** 만료 판정(ExpiryPeriodicWork)·보관 정리(RetentionPeriodicWork)는 테스트에서 `doRun()` 상당을 직접 호출할 수 있다고 전제한다(1분/1일 주기 대기 불가).
3. **재시작 + 시간 경과 조합**(T-07-04, T-08-07)은 JenkinsSessionRule 두 세션 사이에서 Clock을 미래로 설정할 수 있어야 성립한다(1번 전제의 연장).
4. **cron 발화**(T-06-06/07)는 실제 crontab 대기 대신 TimerTrigger Cause를 실은 스케줄 호출로 발화를 재현한다.
5. **테스트 의존성 필요** (pom.xml은 release-manager 소유): `org.jenkins-ci.plugins:pipeline-build-step` (T-06-05/10/11/12, PoC 보고서에서 기요청), `job-dsl` (T-09-06), `role-strategy` (T-08-08), `cloudbees-folder` (T-08-11, T-09-08), `matrix-auth` (권한 시나리오 전반), `workflow-multibranch` 또는 `branch-api` (T-10-07).
6. **T-06-12 부수 효과**: build 스텝 차단 시 상위 잡이 FAILURE로 끝나는 것은 PoC에서 확인된 Jenkins 동작이며, 이 행은 그 동작을 회귀 고정한다(문서화 대상).
7. **성능 요구**(6절: 하루 5,000 실행 규모에서 최근 7일 조회 2초 이내)는 자동 회귀 매트릭스에서 제외하고 Phase 5에서 로컬 수동 측정으로 확인한다.
8. **T-RT-\* 확정 (R-1~R-8 SPEC 채택 완료).** `T-RT-<nn>`의 `<nn>`은 `docs/reports/red-team-01.md`의 RT 번호와 일치시켰다(추적성). 번호가 비는 곳(04, 08, 09, 12)은 아래 "red-team 시나리오 제외 사유" 절에 있다. 사람이 red-team 제안 R-1~R-8을 전부 SPEC에 채택했다(D-16~D-23으로 SPEC v1.0 수용 기준에 반영됨). 따라서 **모든 T-RT 행의 Then은 이제 현행 SPEC 수용 기준에서 직접 도출된 확정 행이다.** 행 안에 남아 있는 `(SPEC 보강 필요: R-n)` 표기는 채택 이전의 이력 주석이며, 해당 R-n이 D-16~D-23으로 SPEC에 반영되었다는 근거 표시로 읽는다(행 문언은 추적성을 위해 유지). R-n 없는 T-RT 행(T-RT-06, T-RT-07)은 원래부터 현행 SPEC 문언(8절 "만료 시각 경과 후 첫 권한 검사부터 거부", 3절 결재자 변경 이력)에서 직접 도출했다.
9. **ID 규칙 보충**: 전역 설정 기본값(SPEC 5절 표)은 항목 5(실행 요청)와 번호가 겹치지 않도록 `T-CFG-*`, 비기능 보안(6절)은 `T-SEC-*`를 쓴다. 기존 시드의 T-SEC-01~04는 SPEC 열이 관련 항목 번호를 가리키던 것을 그대로 유지했다. T-SEC-04는 RT-12의 실질 엣지(제어문자·초장문 이름)를 흡수해 확장했다(행 추가 없음).
10. **동시성 행 재현 방법**(T-RT-14/15/16): 2-스레드 동시 POST(ExecutorService + CyclicBarrier 상당)로 경합을 재현하고, 결과 단언은 상태·빌드 수로만 한다. T-RT-16은 Clock 이동 + PeriodicWork 수동 트리거(비고 1·2 전제)와 조합한다.
11. **T-RT-17 전제**: "scheduleBuild2 완료·onStarted 미도달" 크래시 타이밍을 재현하려면 큐 투입 직후 세션을 종료할 수 있는 훅(예: QuietDown 상태에서 투입 후 세션 재시작)이 필요하다. 재현이 불가능하면 최소한 T-04-02에 이 타이밍 명시를 추가한 통합 테스트로 근사한다. → 요청: core-dev에 복구 로직의 idempotency 키 설계 공유.
12. **RT-08 문서화 요청**: 결재자 계정은 실인(實人) 1:1, 공용·부계정 금지를 알려진 한계로 README에 명시. → 요청: `README.md` (release-manager 소유) 알려진 한계 절 추가.
13. **T-RT-16 근사 (S2)**: 테스트에서 호출 가능한 공개 "재투입" 경로가 없어(투입은 approve() 내부에서 일어남), 경합을 "만료 시각이 이미 지난 PENDING 요청에 대한 approve()(=공개 투입 경로) vs ExpiryPeriodicWork.doRun()" 2-스레드 barrier 경합으로 재현했다. 단언은 D-20의 불변식 그대로: 만료 요청은 절대 투입되지 않고(빌드 0, executedRunId=null) 최종 상태는 EXPIRED 하나로 수렴.
14. **T-RT-17 근사 채택 (S2, 비고 11 이행)**: QuietDown 상태에서 approve로 큐 투입(scheduleBuild2 완료) 후 빌드 시작 전에 JenkinsSessionRule 세션을 종료하는 방식으로 "scheduleBuild2 완료·onStarted 미도달" 갭을 재현했다. 재시작 후 복원 큐 항목 + 복구 재투입의 중복 제거를 "요청당 빌드 정확히 1건"으로 단언.
15. **T-RT-02 '재사용 차단 기록' 단언 유보 (S2)**: 마커 재사용 차단 자체(동일 잡 재큐·타 잡 재투입 모두 불가, 빌드 수 불변)는 단언했으나, "차단 사실이 기록된다"의 기록 위치(로그인지 ChangeRecord인지)가 SPEC에 미정의라 단언하지 않았다. 기록 위치가 확정되면 단언 추가.
16. **APPROVED-미투입 상태 만들기 (S2 전제)**: T-07-03/07-04/04-02/RT-03(approved 변형)은 `Jenkins.doQuietDown()`으로 빌드 시작을 막은 채 approve하고 필요 시 `Queue.clear()`로 큐 항목을 제거해 "APPROVED이나 큐 미투입" 상태를 만든다. 전제: approve()는 스케줄 결과와 무관하게 APPROVED 상태를 기록하며, 큐 투입 실패/유실 시에도 예외로 상태를 되돌리지 않는다.

17. **AdministrativeMonitor rows covered via activation checks (S3)**: T-08-06 and T-08-08 are asserted through `AdministrativeMonitor.isActivated()` (plus enabled/registered), not by scraping /manage HTML. Visual display of the warnings is deferred to the Phase 5 e2e pass (same convention as the T-02-02 note). T-08-06 additionally asserts the negative states (admin-only delegate, and change control off) so the warning cannot fire spuriously.
18. **Grant creation endpoint assumed (S3)**: the fixed S3 endpoint contract defines `batch-control/grants/` GET list, POST `grants/<id>/approve|reject|cancel` and POST `grants/active/<grantId>/revoke`, but names no creation endpoint. T-08-13 (creation POST without RequestGrant → 403) assumes POST `batch-control/grants/create`. → Request: ui-dev to expose grant request creation at `batch-control/grants/create` (or report the actual path so the test is adjusted).
19. **T-RT-05 assertion scope (D-17) and T-RT-20 bounded version (S3)**: T-RT-05 asserts exactly what SPEC 8/D-17 promises — a job created inside an active grant window automatically carries `approvalRequired=true`, so a MANUAL run is blocked after (and independent of) window expiry; timer runs still follow the SPEC 6 timer policy (default pass), so no cron block is asserted. T-RT-20 (P2) is implemented as a bounded regression: 30 rapid `config.xml` POSTs under an active CONFIGURE grant must leave ≥30 CONFIGURE records whose diffs chain against the previous baseline (rev-(n-1) → rev-n), plus one interleaved record of another job (pipeline not blocked). The "hundreds of saves" load variant stays a Phase 5 manual measurement (note 7).
20. **T-RT-02 wording aligned to D-23 (S3, spec-review-S2 carry-over)**: the row previously said the marker binds to "requestId+job+parameters"; SPEC/D-23 bind the marker to requestId (and thereby the request's job) only — no user-facing path attaches arbitrary parameters to a marker. The row's When/Then were rewritten to requestId+jobFullName binding. The existing RequestIntegrityTest assertions (re-queue and cross-job re-submission both blocked) already match this binding; no test change was needed.
21. **Grant restart rows split into GrantRestartTest (S3)**: T-08-04/07 need JenkinsSessionRule, which cannot share a class with the plain JenkinsRule used by GrantServiceTest — same split as RestartRecoveryTest in S2.
22. **T-10-07 approximated (S4)**: the multibranch project is a real `WorkflowMultiBranchProject`, but its branch comes from `SingleSCMSource` over `NullSCM` (no git fixture is available among the test dependencies, so a real SCM indexing run would be brittle). Branch builds may end FAILURE (NullSCM checks out no Jenkinsfile); the row asserts that a completion of any result is recorded for `mb/main` and that, with run control on, a manual user-cause run of the branch child is not blocked. The control-exemption is asserted only in the child's natural state (no approval property is injected — a computed child cannot be configured that way through any product path). If `SingleSCMSource` discovery behaves differently in the resolved scm-api version, re-approximate with the matrix owner.
23. **T-12-04 summary contract assumption (S4)**: the fixed S4 endpoint contract says `batch-control/history/summary?month=YYYY-MM` "returns counts" without naming a format. The test pins a machine-readable JSON body with keys `runs`, `success`, `failure`, `unstable`, `incidentsOpen`, `incidentsResolved`, `requestsApproved`, `requestsRejected` (whitespace-tolerant `"key": n` matching). → Request: ui-dev to serve the summary as JSON with exactly these keys, or report the actual field names so the test is adjusted.
24. **T-11-05 comment storage assumption (S4)**: SPEC's Incident model has `transitions` and no separate comment list, so `IncidentService.addComment(id, comment)` on a RESOLVED incident is asserted to append an entry to `transitions` (comment + by recorded, status unchanged RESOLVED).
25. **T-SEC-06 switch-toggle GET sub-case (S4)**: there is no plugin-owned toggle endpoint — switches change only through the Jenkins global config POST (S1-covered). The test asserts the real invariants: GET `/configure` renders (200) without flipping either switch, and GET `/configSubmit` is rejected (>=400, Jenkins core `@RequirePOST`) leaving both switch values unchanged.
26. **T-RT-11 assertion shape (S4, D-18)**: the reason (`=1+1`) is a cell of its own in requests.csv, so it is asserted strictly (present and every occurrence preceded by `'`). The parameter payloads must be present in runs.csv; across all four CSVs every payload occurrence is additionally checked to never sit at a raw cell start (position 0 / after a delimiter / after an opening quote) unescaped — occurrences embedded mid-cell (e.g. a `key=value` aggregate) are not formula-injectable and pass, which is exactly the D-18 criterion (cells *starting* with `= + - @`).
27. **T-RT-10 coverage shape (S4 close)**: reason (`<script>alert(...)</script>`) and parameter value (`<img src=x onerror=...>`) payloads are asserted on three render surfaces — request detail (escaped forms present, unescaped absent), dashboard (escaped parameter present after the approved build ran, unescaped absent) and request list (no unescaped payload, no injected DOM element; presence not required there since SPEC does not pin the list's columns). A `CollectingAlertHandler` additionally asserts that no injected script executed on any fetched page. The row's job-name payload variant is unreachable by design: Jenkins core `checkGoodName` rejects `<`/`>` in item names, so no such job can be created. Observed at run time: Jelly's default expression escaping neutralizes `<` (as `&lt;`) and `&` but leaves `>` raw — that is safe (no element can open without a live `<`), so the test accepts both `&lt;script&gt;alert` and `&lt;script>alert` as the escaped form; the no-raw-payload, DOM and alert-handler assertions are unconditional.
28. **P-09 visibility reconciliation audit (Phase 4)**: after the S-01/P-09 visibility model landed, every existing web test was audited against it. NO existing row's Then changed meaning and no fixture needed an extra permission: every actor that reads a request/grant is its requester, its designated approver, a MANAGE/ADMINISTER holder, or holds Item/Read on the target job (e.g. RunRequestWebTest's u2 holds Item/Read everywhere, so T-SEC-02's 403 still isolates the missing Approve permission; GrantWebTest's readers are the requester u1, approver a1, m1 or admin; IncidentTest's rerun caller u1 holds Item/Read; QueueBlockTest's job-page fetches run unsecured or as Request holders). The new visibility rules themselves are covered by T-SEC-08/09 (and S-06/S-07 by T-SEC-10/11).

## red-team 시나리오 제외 사유 (red-team-01, 매트릭스 행 미추가)

| RT | 제외 사유 |
|---|---|
| RT-04 (FOLDER prefix 오판) | 기존 행과 중복: T-SEC-03(unit, `team/batch` vs `team/batch-other` 경계 판정)과 T-08-11(integration, 폴더 안/밖 통합 검증)이 동일 공격을 커버. POC C에서도 검증됨. 새 엣지 없음. |
| RT-08 (다계정 자가 결재) | 자동 테스트 불가(설계 범위 밖). 플러그인은 계정↔실인 매핑을 알 수 없다 — 조직 통제(IdP 계정 위생) 영역. 비고 12의 README 문서화 요청으로 대체. |
| RT-09 (권한 상실 결재자) | 기존 행과 중복: T-03-03이 "목록 등재 + Approve 권한 상실 → 결재 거부"를 그대로 커버. 부수 위험(요청 PENDING 고착)은 T-07-01(pendingTimeout 만료)로 회수 검증됨. 지정 시점 차단은 SPEC이 요구하지 않음(결재 시점 검사만 명시). |
| RT-12 (잡 이름 경로 탈출·초장문) | 기존 행 확장으로 흡수: 경로 탈출(`../`)은 T-SEC-04가 이미 커버, 시나리오가 추가한 실질 엣지(제어문자·255자 초과 이름)를 T-SEC-04의 Given/Then에 병합했다. 별도 행 불필요. |

## 요약 (Phase 2 최종 — red-team 병합 후)

- **총 행 수: 135** (SPEC 도출 111 + T-RT 16 + Phase 4 security regressions T-SEC-08..14 + coverage-gap row T-SEC-15)
- **우선순위**: P0 84 / P1 42 / P2 9
- **계층**: unit 2 / integration 124 / e2e 9
- **ID 그룹별 분포**: T-01 6, T-02 5, T-03 6, T-04 4, T-05 6, T-06 16, T-07 7, T-08 13, T-09 11, T-10 7, T-11 7, T-12 5, T-CFG 3, T-SEC 15, T-E2E 8, T-RT 16
- **T-RT 우선순위**: P0 9 (T-RT-01/02/03/05/06/14/15/16/17), P1 5 (T-RT-07/10/11/13/19), P2 2 (T-RT-18/20)
- **red-team 제안 채택 확정(비고 8, D-16~D-23 반영)**: R-1→T-RT-01, R-2→T-RT-05, R-3→T-RT-10(준용)·T-RT-11, R-4→T-RT-13, R-5→T-RT-14/15/16/17, R-6→T-RT-03, R-7→T-RT-18/19/20, R-8→T-RT-02/17
