# 설계 결정 기록

형식: `D-번호 | 결정 | 이유 | 대안과 기각 사유`. 에이전트는 이 파일을 수정하지 않고, 변경 제안은 맨 아래 "제안" 절에 추가만 한다.

## 확정

**D-01 | 대상은 CI가 아니라 배치 실행 관리** | Jenkins를 Spring Batch 등 배치 실행기로 쓰는 환경이 국내에 많고, 그 영역의 승인·감사 대안이 상용(Control-M 등)뿐이라 빈틈이 명확함. | CI 배포 승인: `input` 스텝, GitHub Environments, ServiceNow와 중복.

**D-02 | 신규 플러그인으로 신청, Job StrongAuthSimple은 입양하지 않음** | 13년 미관리, Jenkins 1.x 코드, Pipeline·감사·JIT 없음. 이어받을 코드가 없음. | 입양: 심사는 쉬우나 실질적 이점 없음. 요청서에 차이점 명시로 대응.

**D-03 | 실행 통제는 큐 진입 차단(QueueDecisionHandler)** | 모든 실행 경로가 큐를 거쳐 한 지점에서 차단 가능. 문서화된 확장 포인트. | Pipeline `input` 스텝 방식: Freestyle 미지원, 잡 실행 자체를 막지 못함.

**D-04 | 결재자는 그룹 정의가 아니라 명시적 사용자 목록 + 요청자가 1명 선택** | LDAP/AD 환경에서 "권한 있는 사용자 전체 목록"을 뽑는 표준 API가 없음. 목록 등재 + 결재 시점 권한 확인으로 이중 검증. | 그룹 기반 자동 목록: 구현 불가능한 환경이 있음. 다단계 결재선: 2차.

**D-05 | 관리자 자가 결재 허용(기본값)** | 관리자는 어차피 우회 가능하므로 막는 대신 기록한다. 직무 분리가 필수인 조직을 위해 옵션으로 끌 수 있게 함. | 관리자도 강제 분리: 소규모 팀 사용성 저하.

**D-06 | 변경 통제는 대행이 아니라 JIT 임시 권한** | 수정·생성은 거부 훅이 없어 대행이 필요했으나, 대행은 권한 상승(SYSTEM 적용 시 스크립트 자동 승인 등) 위험과 초안 폴더·diff 화면 등 복잡도가 큼. JIT는 사용자가 자기 계정으로 직접 작업하므로 기존 안전장치가 그대로 동작. | 대행(초안 폴더): 복잡·위험. 사전 내용 검토는 포기하고 사후 diff 추적으로 보완.

**D-07 | 상태 변경(활성/비활성)은 수정과 한 묶음** | Jenkins 코어에서 enable/disable이 Item/Configure 권한에 묶여 있고 분리 요청(JENKINS-27051)이 미해결. | 별도 행위로 정의: 권한 체계상 불가.

**D-08 | 시간 만료는 검사 시점 시각 비교** | 타이머 불필요, 재시작에 강함, 즉시성 보장. 주기 작업은 상태 갱신·알림용 보조. | 스케줄러로 회수: 재시작·지연 시 취약.

**D-09 | 오류 등록 기본값은 FAILURE + UNSTABLE, ABORTED 제외** | ABORTED는 대부분 의도적 중단이라 노이즈. 대시보드에는 중단자 표시로 충분. 설정으로 추가 가능. | 셋 다 포함: 월말 집계 오염.

**D-10 | 오류 건은 자동 등록, 사람은 처리만** | 수동 등록은 누락을 만들어 월말 집계 신뢰성을 해침. | 수동 등록: 기각.

**D-11 | 저장소는 JENKINS_HOME 파일, 월별 분할** | 의존성 없음, 백업 자동 포함, 심사 무난. 월 단위 조회 요구와 맞음. 저장소 인터페이스를 두어 2차에 외부 DB 구현체 추가. | H2: 드라이버 플러그인 미관리, 파일 호환 이력. 외부 DB 필수: 소규모 팀 진입장벽.

**D-12 | 설치만으로 동작 변경 없음(전역 스위치 기본 off)** | Jenkins 생태계 관례, 심사 기준, cron 배치 중단 사고 방지. | 기본 on: 기각.

**D-13 | 기록은 통제와 독립** | 통제를 끄더라도 감사 기록은 가치가 있음. 어느 스위치든 켜지면 모든 기록 활성. | 스위치별 기록: 기각.

**D-14 | 코드·테스트 작성 세션 분리** | 같은 세션이 코드와 테스트를 짜면 같은 오해를 공유해 잘못된 코드가 통과함. 테스트는 SPEC에서만 도출. | 단일 에이전트: 기각.

**D-15 | 이름 `batch-control`** | 목적(배치 실행 관리)을 드러내 검색성 좋음. CI용 승인과 구분. | ops-approval, job-gatekeeper: 후보였음.

**D-16 | Upstream 정책 명확화 (R-1)** | `blockUpstream=true` + `allowedUpstreamJobs` 빈/미설정 = 모든 상위 잡 차단(빈 목록은 미설정과 동일), 목록 있으면 그 잡만 통과. `blockUpstream=false` = 전부 통과. 빈 목록의 해석 모호성이 red-team RT-01에서 우회 경로로 지적됨. | 빈 목록을 blockUpstream과 무관하게 전부 차단으로 해석: 기본 개방 원칙(D-12)과 기존 잡 호환성 훼손으로 기각.

**D-17 | 권한 창 내 생성 잡은 approvalRequired 기본 true (R-2 경량)** | 실행 통제 on일 때 활성 Grant 안에서 생성된 잡에 자동 적용. 권한 창에 cron 잡을 심어 만료 후 무승인 실행하는 우회(RT-05) 차단. | 권한 창 내 트리거 설정 자체 금지: 정상 업무(배치 잡 생성) 저해로 기각. 완전 방치: 우회 허용으로 기각.

**D-18 | 출력 무해화 수용 기준 명문화 (R-3)** | CSV 셀 `= + - @` 선행 시 `'` 프리픽스, 모든 화면에서 사용자 입력 이스케이프. HOSTING-CHECKLIST 규약을 SPEC 수용 기준으로 승격해 테스트 근거 확보(RT-10/11). | 체크리스트 규약만 유지: 매트릭스 행의 SPEC 근거 부재로 기각.

**D-19 | logTail 마스킹은 비밀 파라미터·Secret 평문 한정 (R-4)** | Incident logTail 저장 시 해당 빌드의 비밀 파라미터 값과 Secret 평문만 마스킹, 그 외 콘솔 노출 비밀은 한계로 문서화(RT-13). | 범용 비밀 패턴 탐지: 오탐·누락 불가피, 잘못된 안전감 유발로 기각.

**D-20 | 상태 전이 원자성 + check-at-submit + idempotency (R-5)** | 요청 상태 전이는 CAS, 동시 승인·승인/취소 경합은 1건만 성립, 큐 투입 직전 만료 재확인, 복구 재투입은 requestId 기반 중복 제거(RT-14~17). | 파일 락 없는 낙관 재작성: 경합 시 상태 파손으로 기각.

**D-21 | 대상 잡 rename/move 시 요청 INVALIDATED (R-6)** | PENDING/APPROVED 요청의 대상 잡이 rename/move되면 INVALIDATED로 종료하고 이력에 남김. 결재자가 검토한 잡과 다른 잡이 실행되는 스왑 공격(RT-03) 차단. | 잡 신원(UUID) 추적으로 계속 유효 유지: 결재 시점과 다른 맥락(경로·상위 폴더 권한)에서 실행될 위험으로 기각.

**D-22 | 크기 상한만 MVP, rate limit은 2차 (R-7 부분)** | 사유 4,000자, 문자열 파라미터 값 개당 10,000자 초과 시 요청 생성 거부(RT-19). 요청 rate limit·PENDING 상한은 2차. | 즉시 rate limit 도입: 정책 튜닝 비용 대비 MVP 가치 낮아 연기.

**D-23 | 승인 마커는 requestId 바인딩 + 1회 소비 (R-8)** | 마커는 요청 ID에 묶이고 큐 투입 1회로 소비, 재사용(재큐·rebuild)은 차단·기록(RT-02/17). | 무상태 마커: 재사용·재큐 공격 허용으로 기각.

## 제안 (에이전트가 추가, 사람이 판정)

**P-08 | Monthly summary counting semantics (spec-review-S4 MAJOR 2)** | The live summary (HistorySection.summarize, serves T-12-04) counts: ACKNOWLEDGED incidents in NEITHER incidentsOpen NOR incidentsResolved; approved-then-EXPIRED/INVALIDATED requests in NEITHER requestsApproved NOR requestsRejected. SPEC 12 only names the counter categories without defining these edge attributions. Options: (a) bless the current semantics in SPEC (ACK = its own implicit bucket; expired approvals uncounted), (b) count ACKNOWLEDGED under open (unresolved) and approved-then-expired under approved. T-12-04's fixture exercises neither edge. | Status: awaiting human decision

**P-07 | GrantRequest approver-change: SPEC §2 item 3 vs §3 model conflict (spec-review-S3 MINOR)** | SPEC item 3 says a requester may change the approver on any pending request, but the §3 data model gives `approverChanges[]` to RunRequest only, and the GrantRequest implementation has no approver-change API/history. Options: (a) declare item 3 approver-change as run-request-only in SPEC (document), (b) add approverChanges + changeApprover to GrantRequest for symmetry. | Status: awaiting human decision

**P-06 | RequestGrant permission enforced at HTTP layer only (core-dev S3)** | `GrantRequestService.create` does not check BatchControl/RequestGrant itself; the permission is enforced in `GrantsSection.doCreate` (verified by T-08-13). This is asymmetric with `RunRequestService.create`, which checks REQUEST in the service. The S3 test contract requires this (tests create grant requests as users without RequestGrant through the service API). Options: (a) accept the asymmetry and document that the service API is @Restricted internal, (b) align by adding the check to the service and adjusting tests. | Status: awaiting human decision

**P-04 | Where to record blocked marker re-use (spec-review-S2 MINOR 3)** | D-23 says re-use of an approved-run marker is "blocked and recorded", but the record location is undefined in SPEC; the current implementation only logs a JUL warning. Candidates: (a) a ChangeRecord-like audit entry (new type MARKER_REUSE_BLOCKED), (b) a field on the RunRequest (e.g. reuseAttempts), (c) keep JUL logging + document. Matrix note 15 reserved the T-RT-02 assertion pending this decision. | Status: awaiting human decision

**P-05 | Sync SPEC §3 model table with D-20/21/23 implementation fields (spec-review-S2 MINOR 4)** | RunRequest gained queuedAt (consumption ticket), expiryBase (recovery-time expiry basis), invalidationReason — legitimate vehicles for D-20/D-21/D-23 but absent from the SPEC §3 data model table. Proposal: add them to SPEC §3 (human-owned edit). | Status: awaiting human decision

**P-03 | 비밀(Password) 파라미터의 요청·재실행 처리 (ui-dev S2)** | 요청 파라미터가 `Map<String,String>`으로 서비스에 전달되므로 타입 정보가 소실되어, 저장 계층의 타입 기반 마스킹이 작동할 수 없음. 현재 구현: ui-dev가 액션에서 sensitive 값(`ParameterValue.isSensitive()`/Secret)을 `********`로 마스킹 후 전달 — 평문은 어디에도 저장되지 않으나, **비밀 파라미터를 가진 잡은 승인 실행 시 원본 비밀값을 재현할 수 없음**(T-05-01은 비민감 파라미터에서만 성립). 대안: ① 현행 유지 + 한계 문서화(README) ② 요청 모델에 `List<ParameterValue>`를 병행 저장(Secret은 XStream 암호화 저장, 표시만 마스킹)해 재현 보장 — 구현 비용 중간, 보안 검토 필요. | 상태: 사람 판정 대기

**P-02 | 권한 함의 구조·스코프 문서화 (spec-review-S1 MINOR)** | 구현은 REQUEST/APPROVE/REQUEST_GRANT/VIEW_HISTORY가 MANAGE에 함의되고 MANAGE는 ADMINISTER에 함의되는 구조이며, 5종 모두 `PermissionScope.JENKINS`다. ① 이 함의 구조를 SPEC 2에 명문화할지, ② ARCHITECTURE §2의 "Item 스코프" 기술을 "JENKINS 스코프"로 정정할지 사람 판정 필요. 실질 위험은 낮음(SPEC 3의 결재자 이중 검증이 방어). | 상태: 대기

**P-01 | 2차: Role Strategy용 JIT 구현체(임시 역할 부여 API 활용)** | Phase 1 PoC에서 Role Strategy를 delegate로 래핑하면 권한 판정은 정상이나 역할 관리 화면(`getInstance()`/`persistChanges()`의 전역 전략 instanceof 검사)이 동작 불능임을 확인. C-2 결정에 따라 MVP는 Matrix 계열만 지원하고 제약을 문서화하며, Role Strategy 지원은 2차에서 임시 역할 부여 API 활용으로 검토. | 상태: 사람 승인으로 등록됨 (2026-09-20, Phase 1 게이트)
