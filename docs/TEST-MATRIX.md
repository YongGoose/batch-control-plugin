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
| T-01-01 | 1 | integration | P0 | 플러그인 설치, 스위치 모두 off, approvalRequired=true인 잡 | Build Now | 빌드가 정상 실행된다 | |
| T-01-02 | 1 | integration | P0 | runControlEnabled=false | 관리자가 true로 변경 | ChangeRecord(CONFIG_TOGGLE, admin, false→true)가 남는다 | |
| T-01-03 | 1 | integration | P0 | 스위치 모두 off | 잡 설정 변경 + 잡 삭제 | 이전과 동일하게 성공한다(차단 없음) | |
| T-01-04 | 1 | integration | P1 | runControlEnabled=true, changeControlEnabled=false | 잡 설정 변경·삭제 + 잡 페이지 조회 | 변경 통제 차단 없음(성공), 변경 통제 관련 UI가 나타나지 않는다 | |
| T-01-05 | 1 | integration | P1 | changeControlEnabled=true, runControlEnabled=false, approvalRequired=true 잡 | Build Now + 잡 페이지 조회 | 빌드 실행됨(실행 차단 없음), Request Run 등 실행 통제 UI가 나타나지 않는다 | |
| T-01-06 | 1 | integration | P1 | runControlEnabled=true | 관리자가 false로 변경 | ChangeRecord(CONFIG_TOGGLE, admin, true→false)가 남는다 | |
| T-02-01 | 2 | integration | P0 | Manage 없는 사용자 | 전역 설정 POST | 403 | |
| T-02-02 | 2 | integration | P1 | Matrix Authorization 전략 활성 | 권한 설정 화면 조회 | "Batch Control" 그룹에 Request/Approve/RequestGrant/ViewHistory/Manage 5종이 표시된다 | |
| T-02-03 | 2 | integration | P0 | 관리자(Overall/Administer), allowAdminSelfApproval=true(기본) | 본인 요청을 본인이 승인 | 승인 성공, 요청에 selfApproved=true 기록 | |
| T-02-04 | 2 | integration | P0 | allowAdminSelfApproval=false | 관리자가 본인 요청 승인 시도 | 거부된다(직무 분리 적용), 상태 PENDING 유지 | |
| T-02-05 | 2 | integration | P1 | Manage 권한 있는 사용자 | 전역 설정·결재자 목록 POST | 200, 저장된다 | |
| T-03-01 | 3 | integration | P0 | approvers=[a1], 요청자 u1 | u1이 결재자 u2 지정 | 요청 생성 거부 | |
| T-03-02 | 3 | integration | P0 | 요청자 u1 | u1이 결재자 u1 지정 | 거부 (관리자 아님) | |
| T-03-03 | 3 | integration | P0 | approvers=[a1], a1 지정 PENDING 요청, 이후 a1의 Approve 권한 회수 | a1이 승인 시도 | 결재 거부 (목록 등재 + 권한 보유 둘 다 필요) | |
| T-03-04 | 3 | integration | P1 | approvers=[a1,a2], 결재자 a1인 PENDING 요청 | 요청자가 결재자를 a2로 변경 | 성공, 요청 이력에 (a1, a2, 변경자, 시각)이 남는다 | |
| T-03-05 | 3 | integration | P1 | 이미 결재된(APPROVED) 요청 | 요청자가 결재자 변경 시도 | 거부 (결재 전까지만 변경 가능) | |
| T-03-06 | 3 | integration | P1 | 관리자, allowAdminSelfApproval=true | 관리자가 본인을 결재자로 지정해 요청 | 요청 생성 성공 (관리자 예외) | |
| T-04-01 | 4 | integration | P0 | PENDING 요청 1건 | Jenkins 재시작(JenkinsSessionRule) | PENDING 그대로 복구 | |
| T-04-02 | 4 | integration | P0 | APPROVED, 큐 투입 전 | 재시작 | 재시작 후 정확히 1회 투입 | |
| T-04-03 | 4 | integration | P0 | 승인 요청으로 실행 완료된 빌드 #N, RunRecord 존재 | 빌드 #N 삭제(보관 정책 상당) | RunRecord와 관련 요청이 여전히 조회된다 | |
| T-04-04 | 4 | integration | P1 | 저장된 RunRecord·ChangeRecord | 수정·삭제 HTTP 엔드포인트 호출 시도 | 404 또는 405 — 수정·삭제 API가 존재하지 않는다(추가 전용) | |
| T-05-01 | 5 | integration | P0 | 승인 대상 잡, 파라미터 {DATE=2026-09-01} | 요청→승인 | 실행된 빌드 파라미터 == {DATE=2026-09-01} | |
| T-05-02 | 5 | integration | P0 | 요청 폼 | 사유 빈 값 | 거부 | |
| T-05-03 | 5 | integration | P0 | APPROVED 요청 | 저장된 파라미터 변경 엔드포인트/폼 접근 시도 | 존재하지 않음(404/405) — 바꾸려면 새 요청 필요 | |
| T-05-04 | 5 | integration | P0 | PENDING 요청, 지정 결재자 | 사유 빈 값으로 반려 POST | 반려 거부, 상태 PENDING 유지 | |
| T-05-05 | 5 | integration | P0 | 요청→승인→실행 완료 | 빌드의 Cause·Action 조회 | 요청 ID, 요청자, 결재자가 Cause와 빌드 Action으로 표시된다 | |
| T-05-06 | 5 | integration | P1 | 전역 approvers=[a1,a2], JobProperty 잡별 결재자 제한=[a1] | a2를 결재자로 지정해 요청 | 요청 생성 거부 | |
| T-06-01 | 6 | integration | P0 | 통제 on, approvalRequired | POST /job/X/build | 큐 비어 있음, nextBuildNumber 불변, 빌드 없음 | |
| T-06-02 | 6 | integration | P0 | 동일 | POST /job/X/buildWithParameters | 큐 비어 있음, nextBuildNumber 불변, 빌드 없음 | |
| T-06-03 | 6 | integration | P0 | 동일 | CLI build X | 큐 비어 있음, nextBuildNumber 불변, 빌드 없음 | |
| T-06-04 | 6 | integration | P0 | 동일, Pipeline 잡 | Replay | 큐 비어 있음, nextBuildNumber 불변, 빌드 없음 | |
| T-06-05 | 6 | integration | P0 | 동일, 상위 잡 Y가 build 스텝으로 X 호출, blockUpstream=true | Y 실행 | X 큐 진입 없음, X nextBuildNumber 불변 | |
| T-06-06 | 6 | integration | P0 | 동일, TimerTrigger | cron 발화 | 실행됨 | |
| T-06-07 | 6 | integration | P0 | 동일, blockTimer=true | cron 발화 | 차단 — 큐 비어 있음, 빌드 없음(무인 Cause는 조용히 거부 + 로그) | |
| T-06-08 | 6 | integration | P0 | 승인된 요청 | 플러그인 투입 | 실행됨 | |
| T-06-09 | 6 | integration | P0 | 통제 on, approvalRequired | 잡 페이지의 빌드 버튼(Build Now 앵커) 클릭 (WebClient) | 큐 비어 있음, nextBuildNumber 불변, 빌드 없음 | |
| T-06-10 | 6 | integration | P0 | 통제 on, approvalRequired 잡 X, blockUpstream 기본값(false), 상위 잡 Y가 build 스텝으로 X 호출 | Y 실행 | X 실행됨 (UpstreamCause 기본 통과) | |
| T-06-11 | 6 | integration | P0 | blockUpstream=true, allowedUpstreamJobs=[Y] | Y가 build 스텝으로 X 호출 | X 실행됨 (허용 목록 통과) | |
| T-06-12 | 6 | integration | P0 | blockUpstream=true, allowedUpstreamJobs=[Y], 상위 잡 Z | Z가 build 스텝으로 X 호출 | X 큐 진입 없음, 상위 잡 Z는 FAILURE로 종료(PoC 확인 부수 효과) | |
| T-06-13 | 6 | integration | P0 | 통제 on, approvalRequired | POST /job/X/build (사용자 유래 Cause) | HTTP 400 + 응답 본문에 "승인 필요" 안내와 요청 화면 링크 표시 (조용한 실패 금지) | |
| T-06-14 | 6 | integration | P0 | 동일 | CLI build X | exit code 1, stderr에 승인 필요 안내 메시지 포함 | |
| T-06-15 | 6 | integration | P1 | 통제 on, approvalRequired (Freestyle과 Pipeline 각각) | 잡 페이지 HTML 조회 (WebClient) | "Build Now" 문자열 없음, "Request Run" 사이드바 링크 렌더링 | |
| T-06-16 | 6 | integration | P0 | 통제 on, approvalRequired=false인 잡 | POST /job/X/build | 빌드 정상 실행 (통제 대상 아닌 잡은 영향 없음) | |
| T-07-01 | 7 | integration | P0 | pendingTimeoutHours=1, PENDING 요청 | 시각을 2시간 후로 (Clock 주입) + PeriodicWork 실행 | EXPIRED, 이력에 남는다 | |
| T-07-02 | 7 | integration | P0 | PENDING 요청, 요청자 u1 | u2가 취소 | 403 | |
| T-07-03 | 7 | integration | P0 | approvedRunTimeoutMinutes=60, APPROVED 후 큐 투입되지 않음 | Clock을 61분 후로 + 만료 판정 실행 | EXPIRED, 이력에 남는다 | |
| T-07-04 | 7 | integration | P0 | APPROVED 미투입 요청, 정지 상태로 61분 이상 경과 | 재시작(JenkinsSessionRule) 후 복구 | 복구 시점 기준으로 판정되어 정상 투입된다(EXPIRED 아님 — 재시작 복구 지연 예외) | |
| T-07-05 | 7 | integration | P0 | PENDING 요청, 요청자 u1 | u1이 취소 POST | 상태 CANCELLED, 이력에 남는다 | |
| T-07-06 | 7 | integration | P1 | PENDING 요청, Manage 권한자 m1(요청자 아님) | m1이 취소 POST | 상태 CANCELLED | |
| T-07-07 | 7 | integration | P0 | APPROVED 요청, 요청자 u1 | u1이 취소 시도 | 거부 (취소는 PENDING 상태에서만 가능) | |
| T-08-01 | 8 | integration | P0 | Matrix: u1은 Item/Read만. Grant(u1, JOB X, CONFIGURE, 30분) 승인 | u1이 X config POST | 200, 저장됨 | |
| T-08-02 | 8 | integration | P0 | 동일 | u1이 잡 Y config POST | 403 | |
| T-08-03 | 8 | integration | P0 | 동일, Clock을 31분 후로 | u1이 X config POST | 403 (만료 후 첫 권한 검사부터 거부, 타이머 의존 없음) | |
| T-08-04 | 8 | integration | P0 | 활성 Grant | 재시작(만료 전) | 여전히 유효 | |
| T-08-05 | 8 | integration | P0 | 활성 Grant | Manage 권한자가 revoke | 즉시 403, ChangeRecord(GRANT_REVOKE) | |
| T-08-06 | 8 | integration | P1 | 변경 통제 on, u3에게 Item/Configure 직접 부여 | 관리 화면 | AdministrativeMonitor 경고 표시 | |
| T-08-07 | 8 | integration | P0 | 활성 Grant(30분), 정지 상태로 31분 이상 경과 | 재시작 후 u1이 X config POST | 403 (재시작 후 만료 시각이 지난 Grant는 즉시 없음) | |
| T-08-08 | 8 | integration | P1 | Role Strategy가 전역 권한 전략으로 선택됨 | 관리 화면 조회 | JIT 변경 통제 미지원 안내 AdministrativeMonitor 표시 | |
| T-08-09 | 8 | integration | P0 | maxGrantMinutes=240(기본) | durationMinutes=300으로 권한 요청 생성 | 요청 생성 거부 | |
| T-08-10 | 8 | integration | P0 | Grant(u1, JOB X, [CONFIGURE]) 활성 | u1이 X 삭제 시도 | 거부 (부여하지 않은 행위는 계속 거부) | |
| T-08-11 | 8 | integration | P0 | Grant(u1, FOLDER "team/batch", [CREATE, CONFIGURE]) 활성 | u1이 폴더 내 잡 생성·설정 / 폴더 밖 잡 생성 시도 | 폴더 내 성공, 폴더 밖 거부 | |
| T-08-12 | 8 | integration | P1 | pendingTimeoutHours=1, PENDING GrantRequest | Clock 2시간 후 + PeriodicWork 실행 | GrantRequest가 EXPIRED로 바뀐다 | |
| T-08-13 | 8 | integration | P0 | RequestGrant 권한 없는 사용자 | 권한 요청 생성 POST | 403 | |
| T-09-01 | 9 | integration | P0 | 잡 X | UI로 설정 변경 | ChangeRecord(CONFIGURE, diff 포함) | |
| T-09-02 | 9 | integration | P0 | 잡 X | POST config.xml | ChangeRecord(CONFIGURE) | |
| T-09-03 | 9 | integration | P0 | 잡 X, 활성 Grant g1 | 설정 변경 | ChangeRecord.grantId == g1 | |
| T-09-04 | 9 | integration | P0 | 비밀 파라미터 기본값 변경 | 설정 변경 | diff에 비밀값 없음, 마스킹 | |
| T-09-05 | 9 | integration | P1 | 잡 X | CLI update-job | ChangeRecord(CONFIGURE) | |
| T-09-06 | 9 | integration | P1 | Job DSL 시드 잡 | DSL 실행으로 잡 수정 | ChangeRecord(CONFIGURE) | |
| T-09-07 | 9 | integration | P0 | 기록 활성(어느 스위치든 on) | 잡 생성, 잡 삭제 | ChangeRecord(CREATE)와 ChangeRecord(DELETE)가 각각 남고 user·at 정확 | |
| T-09-08 | 9 | integration | P1 | 잡 X, 폴더 F | X 이름변경, X를 F로 이동 | ChangeRecord(RENAME), ChangeRecord(MOVE) | |
| T-09-09 | 9 | integration | P1 | 활성 Grant가 없는 사용자(관리자) | 설정 변경 | ChangeRecord.grantId == null, "권한 부여 없는 변경"으로 조회된다 | |
| T-09-10 | 9 | integration | P1 | changeControlEnabled=false, runControlEnabled=true | 잡 설정 변경 | ChangeRecord가 남는다 (기록은 어느 스위치든 켜지면 활성) | |
| T-09-11 | 9 | integration | P2 | 두 스위치 모두 off | 잡 설정 변경 | ChangeRecord가 남지 않는다 | |
| T-10-01 | 10 | integration | P0 | Freestyle 1회, Pipeline 1회 실행 | 대시보드 조회 | 2건, causeType 정확 | |
| T-10-02 | 10 | integration | P1 | 실행 중 빌드 | u1이 중단 | RunRecord.abortedBy == u1 | |
| T-10-03 | 10 | integration | P1 | USER/TIMER/UPSTREAM/APPROVED_REQUEST/SCM 원인으로 각각 실행 + 기타 Cause 1건 | RunRecord 조회 | causeType이 각각 USER/TIMER/UPSTREAM/APPROVED_REQUEST/SCM으로, 기타는 OTHER로 분류된다 | |
| T-10-04 | 10 | integration | P0 | 승인 요청으로 실행된 빌드 | RunRecord와 요청 조회 | causeType=APPROVED_REQUEST, runRequestId로 요청 상세에 연결, 요청의 executedRunId 설정 | |
| T-10-05 | 10 | integration | P0 | ViewHistory 없는 사용자 | 대시보드 GET | 403 | |
| T-10-06 | 10 | e2e | P2 | 8일 전 실행 1건 + 최근 실행 60건 | 대시보드 기본 화면 | 최근 7일 건만 표시, 페이지당 50건 페이징 | |
| T-10-07 | 10 | integration | P2 | Multibranch 하위 잡 | 브랜치 잡 실행 | RunRecord 기록됨, 실행 통제는 적용되지 않음(기록만 — SPEC 6절 호환) | |
| T-11-01 | 11 | integration | P0 | cron 잡이 FAILURE | 완료 | Incident OPEN 생성, logTail 100줄 이하 | |
| T-11-02 | 11 | integration | P0 | Incident i1 | 재실행 요청→승인→SUCCESS | i1.resolvedByRunId 설정, 상태는 OPEN 유지 | |
| T-11-03 | 11 | integration | P0 | incidentResults=[FAILURE] | UNSTABLE 완료 | Incident 없음 | |
| T-11-04 | 11 | integration | P1 | OPEN Incident | u1이 ACKNOWLEDGED(코멘트) → u2가 RESOLVED(코멘트) | 각 전이에 사용자·시각·코멘트가 transitions에 남는다 | |
| T-11-05 | 11 | integration | P1 | RESOLVED Incident | OPEN 또는 ACKNOWLEDGED로 되돌리기 시도 | 거부 (역방향 전이 없음), RESOLVED 상태에서 코멘트 추가는 가능 | |
| T-11-06 | 11 | integration | P1 | 파라미터 {DATE=2026-09-01} 빌드의 FAILURE로 생성된 Incident i1 | i1에서 "재실행 요청" | 원래 파라미터가 채워진 RunRequest 생성, incidentId=i1 연결, i1.rerunRequestIds에 추가 | |
| T-11-07 | 11 | integration | P2 | incidentResults=[FAILURE, UNSTABLE, ABORTED] | ABORTED 완료 | Incident 생성 | |
| T-12-01 | 12 | integration | P0 | ViewHistory 없는 사용자 | GET /batch-control/history | 403 | |
| T-12-02 | 12 | integration | P1 | retentionMonths=1, 3개월 전 runs 파일 | RetentionPeriodicWork | 파일 삭제 + ChangeRecord(RETENTION) | |
| T-12-03 | 12 | integration | P1 | 실행 기록·오류 건·변경 기록·요청 이력 데이터 각각 존재 | 각 화면에서 기간 필터 적용 + CSV 내보내기 | 4종 모두 필터 결과 정확, CSV 다운로드 성공 | |
| T-12-04 | 12 | integration | P1 | 한 달간 실행 SUCCESS 2 / FAILURE 1 / UNSTABLE 1, 요청 승인 1 / 반려 1, Incident OPEN 1 / RESOLVED 1 | 월별 집계 조회 | 실행 수·성공/실패/불안정 수·오류 OPEN/RESOLVED 수·승인/반려 수가 모두 정확 | |
| T-12-05 | 12 | integration | P0 | ViewHistory 없는 사용자 | CSV 내보내기 엔드포인트 호출 (4종 각각) | 403 | |
| T-CFG-01 | 5절 | integration | P1 | 신규 설치 (설정 저장 이력 없음) | 전역 설정 조회 | 기본값이 SPEC 5절 표와 일치: runControlEnabled=false, changeControlEnabled=false, approvers=[], allowAdminSelfApproval=true, pendingTimeoutHours=72, approvedRunTimeoutMinutes=60, grantDurationOptions=[15,30,60], maxGrantMinutes=240, incidentResults=[FAILURE,UNSTABLE], retentionMonths=24 | |
| T-CFG-02 | 5절 | integration | P1 | 전역 설정을 기본값과 다르게 저장 | 재시작 | 저장한 값 유지 | |
| T-CFG-03 | 5절 | integration | P2 | 전역 설정 폼 | pendingTimeoutHours에 음수/0 등 잘못된 값 저장 시도 | 거부되거나 기존 값 유지 (임의 값으로 저장되지 않음) | |
| T-SEC-01 | 6 | integration | P0 | 결재자 | GET /batch-control/requests/<id>/approve | 405 또는 거부 (POST만) | |
| T-SEC-02 | 5 | integration | P0 | Approve 권한 없는 사용자 | POST approve | 403 | |
| T-SEC-03 | 8 | unit | P0 | scope=FOLDER "team/batch" | item "team/batch-other" | 범위 밖 판정 (prefix 오판 방지) | |
| T-SEC-04 | 4 | unit | P0 | 잡 이름 "../x" | 스냅숏 경로 계산 | 예외 (경로 탈출 차단) | |
| T-SEC-05 | 6절 | integration | P0 | 인증된 사용자, CSRF crumb 없음 | 상태 변경 POST (요청 생성·승인 등) | 403 (crumb 필수) | |
| T-SEC-06 | 6절 | integration | P0 | 인증된 사용자 | GET으로 상태 변경 엔드포인트 호출 (reject, cancel, revoke, Incident 전이, 스위치 변경) | 각각 405 또는 거부 (모든 상태 변경은 POST + 권한 체크) | |
| T-SEC-07 | 6절 | integration | P0 | Password 파라미터를 가진 잡 | 요청→승인→실행 후 요청 상세·RunRecord·CSV 조회 | 비밀값이 어디에도 평문으로 노출되지 않는다 (마스킹 저장) | |
| T-E2E-01 | 5,6 | e2e | P0 | requester/approver 계정 | requester 요청 → approver 승인 | 빌드 실행, 대시보드에 요청 ID 연결 표시 | |
| T-E2E-02 | 6 | e2e | P0 | approvalRequired 잡 | requester가 사이드바 확인 | "Build Now" 없음, "Request Run" 있음 | |
| T-E2E-03 | 8 | e2e | P0 | requester | 권한 요청→승인→설정 화면 | 저장 성공, 만료 후 저장 403 안내 | |
| T-E2E-04 | 12 | e2e | P1 | 실행 10건 | CSV 내보내기 | 10행 + 헤더, 파라미터 열 포함 | |

## 비고 (전제와 요청 사항)

1. **Clock 주입 API 필요.** T-07-01/03/04, T-08-03/07/12, T-12-02는 시간 경과를 실제 대기 없이 검증해야 한다(`Thread.sleep` 금지). SPEC의 "타이머 의존 없음, 검사 시점 시각 비교" 문장을 근거로, 플러그인이 `java.time.Clock`을 주입받고 테스트에서 고정/이동 가능한 교체 진입점을 제공한다고 전제한다. → 요청: core-dev에 테스트용 Clock 교체 API.
2. **PeriodicWork 수동 트리거.** 만료 판정(ExpiryPeriodicWork)·보관 정리(RetentionPeriodicWork)는 테스트에서 `doRun()` 상당을 직접 호출할 수 있다고 전제한다(1분/1일 주기 대기 불가).
3. **재시작 + 시간 경과 조합**(T-07-04, T-08-07)은 JenkinsSessionRule 두 세션 사이에서 Clock을 미래로 설정할 수 있어야 성립한다(1번 전제의 연장).
4. **cron 발화**(T-06-06/07)는 실제 crontab 대기 대신 TimerTrigger Cause를 실은 스케줄 호출로 발화를 재현한다.
5. **테스트 의존성 필요** (pom.xml은 release-manager 소유): `org.jenkins-ci.plugins:pipeline-build-step` (T-06-05/10/11/12, PoC 보고서에서 기요청), `job-dsl` (T-09-06), `role-strategy` (T-08-08), `cloudbees-folder` (T-08-11, T-09-08), `matrix-auth` (권한 시나리오 전반), `workflow-multibranch` 또는 `branch-api` (T-10-07).
6. **T-06-12 부수 효과**: build 스텝 차단 시 상위 잡이 FAILURE로 끝나는 것은 PoC에서 확인된 Jenkins 동작이며, 이 행은 그 동작을 회귀 고정한다(문서화 대상).
7. **성능 요구**(6절: 하루 5,000 실행 규모에서 최근 7일 조회 2초 이내)는 자동 회귀 매트릭스에서 제외하고 Phase 5에서 로컬 수동 측정으로 확인한다.
8. **T-RT-\* 행 미포함.** red-team 보고서가 병렬 작성 중이므로 이번 판에는 넣지 않았다. 2차 병합 시 `T-RT-*`로 추가하고, 제외한 시나리오는 이 절 아래에 "제외 사유"로 남긴다.
9. **ID 규칙 보충**: 전역 설정 기본값(SPEC 5절 표)은 항목 5(실행 요청)와 번호가 겹치지 않도록 `T-CFG-*`, 비기능 보안(6절)은 `T-SEC-*`를 쓴다. 기존 시드의 T-SEC-01~04는 SPEC 열이 관련 항목 번호를 가리키던 것을 그대로 유지했다.
