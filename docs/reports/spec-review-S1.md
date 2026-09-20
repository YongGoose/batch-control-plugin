# Spec Review S1

검토 대상: 커밋 `ea742d3` (feat: S1 foundation), 테스트 커밋 `931b3e2`.
범위: SPEC 항목 1(전역 스위치), 2(권한 체계), 4(이력 저장소) + §3 데이터 모델·§5 기본값 기반.

## 판정: PASS WITH NOTES

## BLOCKER (스펙 위반, 반드시 수정)

- 없음.

## MAJOR (빠진 수용 기준 / 사람 판정 필요)

- [src/test/java/io/jenkins/plugins/batchcontrol/PermissionsTest.java:88-93, docs/TEST-MATRIX.md:26 (커밋 ea742d3)]
  구현 커밋이 test-author 소유 경로 두 곳을 수정했다. T-02-02의 검증 기준이
  "화면에 표시된다"(visible text)에서 "raw DOM에 존재한다"로 약화되었고, TEST-MATRIX 행도
  같은 커밋에서 함께 고쳐졌다. 기술적 근거(matrix-auth 3.3이 그룹 제목을 접힌 카드 내부에
  렌더링해 HtmlUnit visible text에 안 잡힘)는 코드 주석에 타당하게 적혀 있으나,
  CLAUDE.md 규칙("core-dev는 테스트를 수정하지 않는다. 테스트가 잘못됐다고 판단되면
  보고서에 적고 사람이 판단한다")상 이 변경은 사람 소급 승인이 필요하다.
  Phase 5 e2e 시각 확인이 매트릭스에 명시되어 있으므로 승인되면 수용 기준 공백은 없다.

## MINOR (기본값, 명명, 문서)

- [src/main/java/io/jenkins/plugins/batchcontrol/security/BatchControlPermissions.java:29-42]
  REQUEST/APPROVE/REQUEST_GRANT/VIEW_HISTORY가 모두 `MANAGE`에 의해 implied된다.
  SPEC 2와 ARCHITECTURE §2에는 "Manage는 Jenkins.ADMINISTER implied"만 명시되어 있고,
  나머지 4종을 Manage가 함의한다는 결정은 문서에 없다. Manage 단독 보유자(비관리자)가
  Approve 등을 자동 획득하는 과부여인데, SPEC 3의 이중 검증(결재자 목록 등재 + 권한 보유)이
  결재를 추가로 제한하므로 실질 위험은 낮다. DECISIONS 제안으로 명문화 필요.
  (SPEC 2의 "관리자는 본인 요청을 스스로 결재" 자체와는 모순 없음 — ADMINISTER→MANAGE→APPROVE
  체인으로 관리자가 Approve를 갖는 것은 스펙 의도와 일치.)
- [src/main/java/io/jenkins/plugins/batchcontrol/security/BatchControlPermissions.java:25-42]
  5종 모두 `PermissionScope.JENKINS`인데 ARCHITECTURE §2 표는 "Item 스코프"라고 기술한다.
  JENKINS 스코프면 Project-based Matrix에서 잡 단위 부여가 불가능하다. 전역 스코프가
  이 플러그인의 전역 화면(요청/결재/이력) 특성상 더 타당해 보이나 문서와 코드가 불일치 —
  어느 쪽을 고칠지 사람 결정 필요.
- [src/main/resources/io/jenkins/plugins/batchcontrol/config/BatchControlJobProperty/config.jelly]
  잡 설정 화면의 Batch Control 섹션이 전역 스위치 off 상태에서도 항상 표시된다.
  T-01-05의 "실행 통제 UI 미표시" 기준은 Request Run 링크·차단 기준으로 통과하고
  JobProperty 상시 노출은 Jenkins 관례이므로 동작 위반은 아니나, "스위치 off면 실행 통제
  관련 UI가 나타나지 않는다"를 엄격 해석하면 논쟁 여지가 있다. 정보성 기록.
- [src/main/java/io/jenkins/plugins/batchcontrol/store/FileStore.java:85]
  `monthOf`가 `ZoneId.systemDefault()`를 쓰는 반면 `Ids`는 `BatchClock.clock().getZone()`을
  쓴다. 테스트용 고정 시계(UTC)와 시스템 존이 다르면 월 경계 부근에서 ID의 날짜와 월 버킷이
  어긋날 수 있다. 스펙 규정 사항은 아니며 일관성 권고.
- [src/main/java/io/jenkins/plugins/batchcontrol/config/BatchControlGlobalConfiguration.java:70-96]
  CONFIG_TOGGLE 기록이 setter 시점(= save() 이전)에 남는다. bindJSON 후 save가 실패하는
  엣지에서 영속되지 않은 변경에 대한 토글 기록이 남을 수 있다. 정보성.

## 범위 초과 항목

- [config/BatchControlJobProperty.java] SPEC 항목 5/6의 잡 단위 설정(approvalRequired,
  blockTimer, blockUpstream, allowedUpstreamJobs, jobApprovers). S1 선언 범위(1/2/4) 밖이지만
  T-01-01/T-01-05 테스트가 존재를 요구하고, S1에서는 속성 보유만 하고 아무 동작도 하지 않는다.
  제거 대상 아님 — 유지 권장.
- [model/GrantScope.java] 항목 8 기반의 `includes()` 범위 판정 로직. §3 모델 기반 +
  T-SEC-03(경계 판정)이 요구. 유지 권장.
- [store/PathCodec.java:53-62] 250자 초과 이름의 SHA-256 단축. ARCHITECTURE §5 인코딩 규정에는
  없는 확장이나 T-SEC-04(초장문 이름)가 요구하는 안전장치. 유지 권장.

## 항목별 확인 결과

**SPEC 1 (전역 스위치)**
- 두 스위치 기본 off, 설치만으로 동작 변경 없음: S1에는 QueueDecisionHandler·리스너·
  AuthorizationStrategy가 전혀 없다(패키지: model/store/config/security만 존재).
  @Extension은 전역 설정 화면, JobProperty 설명자, 권한 그룹 등록뿐 — 빌드·설정·삭제 경로에
  개입하는 코드 없음. T-01-01/03/04/05로 검증됨.
- CONFIG_TOGGLE: 사용자(`Jenkins.getAuthentication2()`), 시각, 이전/이후 값("false -> true")이
  기록되고, 동일 값 재설정 시 early-return으로 기록이 남지 않는다(실제 변경 시에만 발생).
  load()는 XStream 필드 직접 주입이라 기동 시 허위 토글 없음. T-01-02/06으로 검증됨.

**SPEC 2 (권한 체계)**
- 5종 권한이 "Batch Control" 그룹으로 정의·등록됨(T-02-02). Manage 없는 사용자의 전역 설정
  POST는 403(T-02-01), Manage(=ADMINISTER 함의) 보유자는 저장 가능(T-02-05).
- implication 구조에 대한 문서화 공백은 MINOR 1·2번 참조.
- T-02-03/04(자가 결재 selfApproved 기록, 직무 분리)는 결재 서비스가 없는 S1에서는 검증 불가 —
  S2 이월로 매트릭스에 명시되어 있음. 예정된 이월이며 결함 아님. 모델은 준비됨
  (RunRequest.selfApproved 필드 존재).

**SPEC 4 (이력 저장소)**
- 레이아웃: `requests/run/<id>.xml`(XStream), `runs/YYYY-MM.jsonl`, `changes/YYYY-MM.jsonl` —
  ARCHITECTURE §5와 일치. grants/incidents/snapshots는 S2+ 예정으로 Store 인터페이스 주석에
  명시됨.
- 원자적 쓰기: 같은 디렉터리에 temp 생성 후 `ATOMIC_MOVE`(미지원 FS만 일반 move 폴백),
  전 쓰기 단일 ReentrantLock 직렬화. JSONL은 `Files.write(CREATE, APPEND)` 단일 호출.
- 추가 전용: Store 인터페이스에 수정·삭제 API 없음(saveRunRequest의 재작성은 상태 전이용으로
  ARCHITECTURE가 허용). 보관 정리는 후속 슬라이스 예정으로 주석에 명시.
- PathCodec: `resolveUnder`가 구분자·`.`·`..` 거부 + normalize 후 startsWith 검증,
  decode는 인코딩 알파벳 밖 문자 전부 거부(경로 탈출 문자열 위장 불가), encode는
  [A-Za-z0-9_-] 외 전 바이트 %XX 인코딩. T-SEC-03/04로 검증됨.
- ID 형식 `yyyyMMdd-HHmmss-<6자리 랜덤>`(소문자 alnum, 대소문자 무구분 FS 고려) —
  ARCHITECTURE §5와 일치.
- T-04-01(PENDING 재시작 복구), T-04-03(빌드 삭제 후 기록 조회) 통과 대상.
  T-04-02(APPROVED 재투입)·T-04-04(수정·삭제 엔드포인트 부재의 HTTP 검증)는 결재 서비스·웹
  엔드포인트가 없는 S1에서 검증 불가 — S2 이월로 매트릭스에 명시. 예정된 이월.

**SPEC §5 기본값 (10키 전수 대조)**
| 키 | SPEC | 코드(BatchControlGlobalConfiguration.java:31-40) | 일치 |
|---|---|---|---|
| runControlEnabled | false | false | O |
| changeControlEnabled | false | false | O |
| approvers | [] | new ArrayList<>() | O |
| allowAdminSelfApproval | true | true | O |
| pendingTimeoutHours | 72 | 72 | O |
| approvedRunTimeoutMinutes | 60 | 60 | O |
| grantDurationOptions | [15,30,60] | [15,30,60] | O |
| maxGrantMinutes | 240 | 240 | O |
| incidentResults | [FAILURE,UNSTABLE] | ["FAILURE","UNSTABLE"] | O |
| retentionMonths | 24 | 24 | O |

T-CFG-01/02/03 대응. 음수·0 입력은 setter가 무시하고 기존 값 유지(T-CFG-03 충족).

**§3 데이터 모델 / §4 상태 머신**
- RunRequest·RunRecord·ChangeRecord·GrantScope와 enum(RequestStatus 7종, ChangeType 8종,
  CauseType 6종)이 §3 필드 정의와 일치. GrantRequest/Grant/Incident 클래스는 S2 예정(기반만 S1).
- 상태 전이 로직 없음: `RunRequest.setStatus` 호출자가 src/main에 존재하지 않음(주석으로
  policy 서비스 전용임을 명시). §4에 없는 전이 없음.

**경계 규칙**
- action/ui 패키지 없음, policy 없음 — 위반 불가 상태로 확인. store 접근은 config의
  CONFIG_TOGGLE 기록(FileStore.appendChangeRecord)뿐이며 이는 core-dev 소유 경로 간 호출로 적법.

## 미확인

- `mvn clean verify` 16/16 green과 SpotBugs 0건은 커밋 메시지 기준이며 본 검토에서
  재실행하지 않았다.
- T-02-02의 "표시" 여부(시각적)는 Phase 5 e2e에서 확인 예정 — S1 시점 미확인.

## 요청

- 요청: (사람) 커밋 ea742d3의 test-author 소유 경로 수정(PermissionsTest.java T-02-02 검증
  기준 변경, docs/TEST-MATRIX.md 행 갱신)에 대한 소급 승인 판정. 미승인 시 test-author가
  재작성해야 한다.
- 요청: docs/DECISIONS.md 제안 등재 — (a) MANAGE가 나머지 4개 권한을 함의하는 implication
  구조, (b) 권한 스코프를 PermissionScope.JENKINS로 하는 결정(ARCHITECTURE §2의 "Item 스코프"
  기술과 불일치 해소).
