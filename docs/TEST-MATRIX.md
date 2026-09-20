# 테스트 매트릭스

test-author가 소유한다. Phase 2에서 SPEC의 모든 수용 기준을 행으로 옮기고, red-team 시나리오를 병합한다. 아래는 형식과 시드 행이다.

## 형식

| ID | SPEC | 계층 | 우선순위 | Given | When | Then | 테스트 파일 |
|---|---|---|---|---|---|---|---|

- ID: `T-<SPEC번호>-<일련>` (예: T-06-03). red-team 출신은 `T-RT-<일련>`.
- 계층: `unit`(Jenkins 불필요) / `integration`(JenkinsRule) / `e2e`(브라우저·REST, Phase 5)
- 우선순위: P0(릴리스 차단) / P1(릴리스 전 수정) / P2(다음 릴리스)

## 시드 행 (test-author가 확장)

| ID | SPEC | 계층 | 우선순위 | Given | When | Then | 테스트 파일 |
|---|---|---|---|---|---|---|---|
| T-01-01 | 1 | integration | P0 | 플러그인 설치, 스위치 모두 off, approvalRequired=true인 잡 | Build Now | 빌드가 정상 실행된다 | |
| T-01-02 | 1 | integration | P0 | runControlEnabled=false | 관리자가 true로 변경 | ChangeRecord(CONFIG_TOGGLE, admin, false→true)가 남는다 | |
| T-02-01 | 2 | integration | P0 | Manage 없는 사용자 | 전역 설정 POST | 403 | |
| T-03-01 | 3 | integration | P0 | approvers=[a1], 요청자 u1 | u1이 결재자 u2 지정 | 요청 생성 거부 | |
| T-03-02 | 3 | integration | P0 | 요청자 u1 | u1이 결재자 u1 지정 | 거부 (관리자 아님) | |
| T-04-01 | 4 | integration | P0 | PENDING 요청 1건 | Jenkins 재시작(JenkinsSessionRule) | PENDING 그대로 복구 | |
| T-04-02 | 4 | integration | P0 | APPROVED, 큐 투입 전 | 재시작 | 재시작 후 정확히 1회 투입 | |
| T-05-01 | 5 | integration | P0 | 승인 대상 잡, 파라미터 {DATE=2026-09-01} | 요청→승인 | 실행된 빌드 파라미터 == {DATE=2026-09-01} | |
| T-05-02 | 5 | integration | P0 | 요청 폼 | 사유 빈 값 | 거부 | |
| T-06-01 | 6 | integration | P0 | 통제 on, approvalRequired | POST /job/X/build | 큐 비어 있음 | |
| T-06-02 | 6 | integration | P0 | 동일 | POST /job/X/buildWithParameters | 큐 비어 있음 | |
| T-06-03 | 6 | integration | P0 | 동일 | CLI build X | 큐 비어 있음 | |
| T-06-04 | 6 | integration | P0 | 동일, Pipeline 잡 | Replay | 큐 비어 있음 | |
| T-06-05 | 6 | integration | P0 | 동일, 상위 잡 Y가 build 스텝으로 X 호출, blockUpstream=true | Y 실행 | X 큐 진입 없음 | |
| T-06-06 | 6 | integration | P0 | 동일, TimerTrigger | cron 발화 | 실행됨 | |
| T-06-07 | 6 | integration | P0 | 동일, blockTimer=true | cron 발화 | 차단 | |
| T-06-08 | 6 | integration | P0 | 승인된 요청 | 플러그인 투입 | 실행됨 | |
| T-07-01 | 7 | integration | P0 | pendingTimeoutHours=1, PENDING 요청 | 시각을 2시간 후로 (Clock 주입) + PeriodicWork 실행 | EXPIRED | |
| T-07-02 | 7 | integration | P0 | PENDING 요청, 요청자 u1 | u2가 취소 | 403 | |
| T-08-01 | 8 | integration | P0 | Matrix: u1은 Item/Read만. Grant(u1, JOB X, CONFIGURE, 30분) 승인 | u1이 X config POST | 200, 저장됨 | |
| T-08-02 | 8 | integration | P0 | 동일 | u1이 잡 Y config POST | 403 | |
| T-08-03 | 8 | integration | P0 | 동일, Clock을 31분 후로 | u1이 X config POST | 403 | |
| T-08-04 | 8 | integration | P0 | 활성 Grant | 재시작(만료 전) | 여전히 유효 | |
| T-08-05 | 8 | integration | P0 | 활성 Grant | Manage 권한자가 revoke | 즉시 403, ChangeRecord(GRANT_REVOKE) | |
| T-08-06 | 8 | integration | P1 | 변경 통제 on, u3에게 Item/Configure 직접 부여 | 관리 화면 | AdministrativeMonitor 경고 표시 | |
| T-09-01 | 9 | integration | P0 | 잡 X | UI로 설정 변경 | ChangeRecord(CONFIGURE, diff 포함) | |
| T-09-02 | 9 | integration | P0 | 잡 X | POST config.xml | ChangeRecord(CONFIGURE) | |
| T-09-03 | 9 | integration | P0 | 잡 X, 활성 Grant g1 | 설정 변경 | ChangeRecord.grantId == g1 | |
| T-09-04 | 9 | integration | P0 | 비밀 파라미터 기본값 변경 | 설정 변경 | diff에 비밀값 없음, 마스킹 | |
| T-10-01 | 10 | integration | P0 | Freestyle 1회, Pipeline 1회 실행 | 대시보드 조회 | 2건, causeType 정확 | |
| T-10-02 | 10 | integration | P1 | 실행 중 빌드 | u1이 중단 | RunRecord.abortedBy == u1 | |
| T-11-01 | 11 | integration | P0 | cron 잡이 FAILURE | 완료 | Incident OPEN 생성, logTail 100줄 이하 | |
| T-11-02 | 11 | integration | P0 | Incident i1 | 재실행 요청→승인→SUCCESS | i1.resolvedByRunId 설정, 상태는 OPEN 유지 | |
| T-11-03 | 11 | integration | P0 | incidentResults=[FAILURE] | UNSTABLE 완료 | Incident 없음 | |
| T-12-01 | 12 | integration | P0 | ViewHistory 없는 사용자 | GET /batch-control/history | 403 | |
| T-12-02 | 12 | integration | P1 | retentionMonths=1, 3개월 전 runs 파일 | RetentionPeriodicWork | 파일 삭제 + ChangeRecord(RETENTION) | |
| T-SEC-01 | 6 | integration | P0 | 결재자 | GET /batch-control/requests/<id>/approve | 405 또는 거부 (POST만) | |
| T-SEC-02 | 5 | integration | P0 | Approve 권한 없는 사용자 | POST approve | 403 | |
| T-SEC-03 | 8 | unit | P0 | scope=FOLDER "team/batch" | item "team/batch-other" | 범위 밖 판정 (prefix 오판 방지) | |
| T-SEC-04 | 4 | unit | P0 | 잡 이름 "../x" | 스냅숏 경로 계산 | 예외 (경로 탈출 차단) | |
| T-E2E-01 | 5,6 | e2e | P0 | requester/approver 계정 | requester 요청 → approver 승인 | 빌드 실행, 대시보드에 요청 ID 연결 표시 | |
| T-E2E-02 | 6 | e2e | P0 | approvalRequired 잡 | requester가 사이드바 확인 | "Build Now" 없음, "Request Run" 있음 | |
| T-E2E-03 | 8 | e2e | P0 | requester | 권한 요청→승인→설정 화면 | 저장 성공, 만료 후 저장 403 안내 | |
| T-E2E-04 | 12 | e2e | P1 | 실행 10건 | CSV 내보내기 | 10행 + 헤더, 파라미터 열 포함 | |
