# Red-Team 보고서 01 (Phase 2 — 설계 단계 공격)

- 작성: red-team 서브에이전트, 2026-09-20, 브랜치 `phase-2-matrix`
- 대상 문서: `docs/SPEC.md` v1.0, `docs/ARCHITECTURE.md`, `docs/DECISIONS.md`, `docs/POC-RESULTS.md`, `docs/TEST-MATRIX.md`
- 공격자 모델: 내부자. 보유 권한 = `BatchControl/Request`, `BatchControl/RequestGrant`, `Item/Read`, 통제 off 잡에 대한 `Job/Build`. **관리자(Overall/Administer) 아님** — 관리자 우회는 범위 밖(SPEC 1, ARCH 7).
- 코드는 없음(Phase 2). "설계상 방어" 판정은 SPEC/ARCHITECTURE 문언과 POC 결과만 근거로 한다. "테스트되는가"는 `docs/TEST-MATRIX.md` 시드 행 기준.

## 요약 (카테고리별 개수)

| 카테고리 | 시나리오 | 개수 |
|---|---|---|
| 1. 실행 우회 | RT-01, RT-02, RT-03 | 3 |
| 2. 권한 창 악용 | RT-04, RT-05, RT-06 | 3 |
| 3. 직무 분리 우회 | RT-07, RT-08, RT-09 | 3 |
| 4. 이력 오염 | RT-10, RT-11, RT-12, RT-13 | 4 |
| 5. 동시성 | RT-14, RT-15, RT-16, RT-17 | 4 |
| 6. 가용성 | RT-18, RT-19, RT-20 | 3 |

가장 위험하다고 보는 5건(실질 갭 가능성 높음): **RT-01, RT-05, RT-11, RT-13, RT-17**. (각 한 줄 요약은 맨 아래 "가장 위험한 시나리오" 절)

---

## 1. 실행 우회

### RT-01 상위 잡 연쇄(Upstream)로 승인 우회
- 전제: 실행 통제 on, 보호 잡 B(`approvalRequired=true`). 공격자가 통제 off 잡 A에 대해 `Job/Build` 및 설정 변경 능력(직접 Configure 또는 RT-05의 권한 창)을 가짐. B의 `blockUpstream` 미설정(기본값).
- 절차:
  1. 잡 A에 "빌드 후 B 트리거"(또는 Pipeline `build 'B'` 스텝)를 구성한다.
  2. A를 수동 실행한다(A는 통제 off라 통과).
  3. A가 B를 호출하면 Cause는 `UpstreamCause`/`BuildUpstreamCause` → SPEC 6 기본 정책 "Upstream 통과"로 B가 승인 없이 실행된다.
- 기대되는 취약 결과: 결재 절차를 전혀 거치지 않고 보호 잡 B가 원하는 파라미터로 실행됨(파라미터를 A에서 넘길 수 있음).
- 설계상 방어가 있는가: **있음(부분) / 사실상 기본 개방** — SPEC 6은 `blockUpstream=true`와 `allowedUpstreamJobs` 화이트리스트를 제공한다. 그러나 **기본값이 통과**이므로 잡별로 명시 설정하지 않으면 무방비. POC A에서 `BuildUpstreamCause`가 `instanceof UpstreamCause`로 잡히는 것은 확인됨(차단은 가능). `allowedUpstreamJobs` 미설정 시 "임의의 상위 잡 허용"인지, "빈 목록이면 전부 차단"인지 SPEC 문언이 불명확(SPEC 6: "목록이 있으면 그 잡만 통과" — 없을 때 동작 미정).
- 테스트되는가: TEST-MATRIX T-06-05는 `blockUpstream=true`일 때 차단만 검증. **기본값(통과)에서의 우회, `allowedUpstreamJobs` 빈/미설정 동작은 테스트 없음.**
- 제안 테스트: Given 보호 잡 B(blockUpstream 미설정)와 통제 off 잡 A가 A→B 트리거로 연결됨, When A를 수동 실행, Then B가 승인 없이 실행된다(현 설계상 우회 성공을 문서화하는 회귀 테스트 + 정책 결정 후 갱신).

### RT-02 승인 마커(ApprovedRunAction) 바인딩·재사용
- 전제: 실행 통제 on. 공격자는 요청/결재 흐름과 마커 통과 규칙을 이해함(ARCH 2·6: `ApprovedRunAction`이 큐에 있으면 무조건 통과).
- 절차:
  1. 자신의 잡 X에 대해 정상 요청 → 승인받아 마커가 붙은 투입이 통과되는 것을 확인.
  2. 동일 마커/Cause가 (a) 특정 requestId, (b) 특정 잡 fullName, (c) 특정 파라미터 집합, (d) 1회성(nonce)에 바인딩되어 있는지 시험한다. 재큐(재시작 복구 경로, rebuild 등)로 같은 마커가 두 번 통과되는지 관찰.
- 기대되는 취약 결과: 마커가 "승인됨"만 표시하고 요청/잡/파라미터/횟수에 묶이지 않으면, 한 번 승인된 투입이 다른 파라미터로 재실행되거나 중복 실행될 수 있음.
- 설계상 방어가 있는가: **불명확** — SPEC 5는 "승인 후 파라미터를 바꾸는 경로가 없다"를 요구하고, ARCH 6은 승인 투입이 저장 파라미터 그대로임을 명시한다. 그러나 `ApprovedRunAction`이 requestId·잡·파라미터에 바인딩되고 **단일 사용(consumed)**임을 명시한 문언이 없다. 일반 사용자는 임의 Action을 큐에 주입할 수 없어(REST/UI 경로 없음) 외부 재생 난이도는 높지만, 내부 재큐 경로(RT-17)에서 재사용 위험이 남는다.
- 테스트되는가: T-05-01(파라미터 일치), T-04-02(재시작 시 정확히 1회) 존재. **마커의 잡/파라미터 바인딩과 단일 사용은 직접 테스트 없음.**
- 제안 테스트: Given 잡 X 승인 마커가 붙은 큐 아이템, When 같은 마커를 잡 Y 또는 다른 파라미터로 투입 시도, Then 통과되지 않는다(마커는 requestId+jobFullName+params에 바인딩되고 1회 소비된다).

### RT-03 승인 대기·승인 후 잡 이름 변경 스왑(TOCTOU)
- 전제: RunRequest는 잡을 `jobFullName`(문자열)로 참조(SPEC 3장 데이터 모델). 공격자가 변경 통제 권한 창(Grant, RENAME/CONFIGURE)이나 통제 off 잡에 대한 rename 능력 보유.
- 절차:
  1. 무해한 잡 A에 대해 요청을 올려 승인을 받는다(APPROVED, jobFullName=A).
  2. 승인 후 큐 투입 전(또는 재시작 복구 대기 중) A를 A2로 rename하고, 위험한 잡 B를 A로 rename(스왑)한다.
  3. 투입 시 `jobFullName=A`로 해석되어 실제로는 (구)B가 승인 파라미터로 실행된다.
- 기대되는 취약 결과: 결재자가 검토·승인한 대상과 다른 잡이 실행됨. 이름 문자열 참조의 late-binding 취약.
- 설계상 방어가 있는가: **불명확** — SPEC/ARCH 어디에도 승인 시점 잡 신원(ItemGroup+상대명, 또는 Item의 안정 식별자)을 고정한다는 문언이 없다. 데이터 모델이 `jobFullName` 문자열만 저장. rename은 변경 통제로 기록은 되지만(SPEC 9) 실행 대상 재바인딩 자체를 막지는 않음.
- 테스트되는가: 없음.
- 제안 테스트: Given 잡 A에 대한 APPROVED 요청, When A를 rename하고 다른 잡을 A로 rename한 뒤 투입, Then 투입이 거부되거나 원래 잡 신원에만 실행된다(이름 변경 시 요청 무효 또는 안정 ID 바인딩).

---

## 2. 권한 창 악용

### RT-04 FOLDER 범위 prefix 오판
- 전제: 공격자가 `team/batch` FOLDER 범위 CONFIGURE Grant를 승인받음. 인접 폴더 `team/batch-x`(또는 `team/batch-secret`)에 접근 시도.
- 절차: 권한 창 동안 `team/batch-x/someJob`의 config POST를 시도한다.
- 기대되는 취약 결과: 단순 `startsWith("team/batch")` 검사라면 `team/batch-x`가 범위 안으로 오판되어 인접 폴더 잡을 수정.
- 설계상 방어가 있는가: **있음** — ARCH 4 "scope.type==FOLDER면 fullName이 폴더 경로로 시작하는지" + `/` 경계 검사. POC C `folderScopeBoundary`에서 `team/batch` Grant가 `team/batch-other`에 새지 않음을 실제 검증(경계 오판 방지 확인).
- 테스트되는가: **있음** — POC 검증됨 + TEST-MATRIX T-SEC-03(unit, prefix 오판 방지). 본개발 코드에 동일 단언이 이식되는지만 확인 필요.
- 제안 테스트: Given `team/batch` FOLDER Grant, When `team/batch-x/job` 수정 시도, Then 403(경로 경계는 `/` 단위로만 일치).

### RT-05 권한 창 안에서 cron/트리거를 심어 만료 후 영구 무승인 실행
- 전제: 공격자가 `team/batch` FOLDER 범위 CREATE/CONFIGURE Grant(예: 30분)를 승인받음. 실행 통제 on.
- 절차:
  1. 권한 창 동안 범위 안에 새 잡 J를 생성(신규 잡 `approvalRequired` 기본값 = false)한다.
  2. J에 `TimerTrigger`(cron) 또는 SCM 트리거를 설정한다. SPEC 6: cron은 기본 통과(`blockTimer` 미설정).
  3. 권한 창이 만료된다. 이후에도 J의 cron이 계속 발화 → 승인·권한 없이 원하는 배치가 영구 실행됨.
  - 변형: 기존 통제 잡 B의 파라미터 기본값/스크립트를 권한 창 동안 바꿔 두고(SPEC 9로 기록은 되지만), cron 통과 경로로 실행되게 한다.
- 기대되는 취약 결과: 임시 권한이 만료돼도 그 창에서 심은 자동 트리거가 통제(승인)를 영구 우회. 변경은 기록되나(grantId 연결) 실행 통제는 걸리지 않음.
- 설계상 방어가 있는가: **없음/불명확** — SPEC 8은 만료 시각 후 "권한 검사"를 거부할 뿐, 창 동안 심은 트리거의 사후 실행은 다루지 않는다. 신규 잡의 `approvalRequired` 기본값이 false(SPEC 5 잡 설정)라 새 잡은 자동으로 무통제. cron 기본 통과(SPEC 6)와 결합되면 지속 우회가 성립.
- 테스트되는가: 없음. (T-08 계열은 만료 시 권한 거부만 검증)
- 제안 테스트: Given 30분 CONFIGURE/CREATE Grant, When 창 동안 cron 트리거를 가진 잡을 생성/설정하고 창 만료, Then cron 실행이 승인 통제를 우회하지 않는다(또는 권한 창 내 생성 잡은 `approvalRequired`/`blockTimer` 기본 활성).

### RT-06 만료 직전 시작한 단일-검사 다중 변경 작업
- 전제: 공격자가 CONFIGURE Grant를 보유, 만료 임박. Job DSL seed job 또는 폴더 일괄 작업을 사용.
- 절차: 만료 수초 전에 다수 잡을 생성/수정하는 작업(예: Job DSL seed 1회 실행)을 시작한다. 권한 검사가 작업 진입 시 1회만 일어나고, 실제 개별 잡 쓰기는 만료 이후 진행되는지 관찰.
- 기대되는 취약 결과: 만료 이후에도 "이미 통과한 검사"에 편승해 다수 변경이 완료됨. 또는 단일 config 저장이 만료 1초 전 검사 통과 후 만료 후 write 완료.
- 설계상 방어가 있는가: **불명확** — ARCH 4/D-08은 "검사 시점 시각 비교"로 만료를 즉시 반영한다고 하나, 하나의 논리적 작업이 여러 잡을 쓸 때 잡마다 재검사되는지, Job DSL의 SYSTEM 컨텍스트 실행 시 grant 검사가 사용자 권한으로 걸리는지 명시가 없다. 실무 영향은 "이미 통과한 단건"에 한정될 가능성이 높아 위험도는 중간.
- 테스트되는가: T-08-03(31분 후 config POST 403)은 새 요청 기준. **진행 중 다건 작업의 만료 중간 차단은 테스트 없음.**
- 제안 테스트: Given 만료 임박 CONFIGURE Grant, When 만료 경계에 걸치는 Job DSL/일괄 변경 실행, Then 만료 시각 이후 write는 잡 단위로 거부된다.

---

## 3. 직무 분리 우회

### RT-07 친화 결재자로 갈아타기(결재자 쇼핑)
- 전제: SPEC 3 — 요청자는 결재 전까지 결재자를 변경 가능. 결재자 목록에 여러 명 등록.
- 절차: 요청을 올린 뒤, 공모/느슨한 결재자가 온라인일 때로 결재자를 반복 변경해 승인을 받아낸다.
- 기대되는 취약 결과: 실질적으로 "가장 승인해 줄 사람"을 골라 통과. 다만 지정 대상은 모두 정식 결재자.
- 설계상 방어가 있는가: **있음(부분)** — 지정 대상은 목록 등재 + 결재 시점 Approve 권한 보유가 강제(SPEC 3), 요청자 본인 지정 금지, 결재자 변경은 요청 이력에 (이전/새/변경자/시각) 기록. 두 명 원칙(2-person)은 유지됨. 그러나 **변경 횟수·빈도 제한이 없어** 결재자 쇼핑과 사후 은폐성 낮은 담합을 막지는 못함(탐지는 가능, 예방은 아님).
- 테스트되는가: T-03-01/02(잘못된 결재자 지정 거부)만. **결재자 변경 이력 기록, 변경 후 승인 흐름은 테스트 없음.**
- 제안 테스트: Given 다수 결재자, When 요청자가 결재자를 여러 번 변경 후 승인, Then 각 변경이 approverChanges에 (from,to,by,at)로 남는다(예방이 아닌 감사 보장).

### RT-08 요청자·결재자 동일인의 다계정(sock puppet)
- 전제: 한 사람이 두 계정(u1=요청자, u2=결재자, u2는 목록 등재 + Approve 권한) 보유.
- 절차: u1로 요청 → u2로 로그인해 승인.
- 기대되는 취약 결과: 실제로는 1인 자가 결재이나 플러그인은 서로 다른 사용자 ID로 인식해 통과. `selfApproved=false`로 기록.
- 설계상 방어가 있는가: **없음(구조적 한계)** — 플러그인은 계정↔사람 매핑을 알 수 없다. SPEC은 사용자 ID 동일성만 검사(요청자≠결재자). 이는 IdP 계정 위생·결재자 목록 관리의 조직 통제 영역. 위험도는 계정 관리 수준에 의존.
- 테스트되는가: 검증 불가(설계 범위 밖). 문서화 권장.
- 제안 테스트: (조직 통제) — SPEC/README에 "결재자 계정은 실인(實人) 1:1, 공용/부계정 금지"를 알려진 한계로 명시. 자동 테스트 대상 아님.

### RT-09 결재자 목록의 stale/비활성/권한상실 계정
- 전제: 결재자 목록에 과거 등록됐으나 퇴사·비활성·Approve 권한 상실한 계정이 남아 있음.
- 절차: 요청자가 해당 계정을 결재자로 지정하려 시도. 또는 해당 계정이 여전히 로그인 가능하면 승인 시도.
- 기대되는 취약 결과: 유령 결재자를 통한 통과, 또는 지정 자체가 막히지 않아 요청이 영원히 PENDING(가용성).
- 설계상 방어가 있는가: **있음(부분)** — SPEC 3: "결재 시점에 Approve 권한을 잃었으면 결재 거부(목록 등재 + 권한 보유 둘 다 필요)". 따라서 권한 상실 계정으로는 승인 불가. 그러나 **목록에서 자동 제거는 없어** 지정은 가능하고, 결재 단계에서만 실패 → 요청이 PENDING에 갇힐 수 있음(만료로는 회수됨, SPEC 7). 목록 정리 책임은 관리자(governance).
- 테스트되는가: 없음(권한 상실 결재자 거부 시나리오 미기재).
- 제안 테스트: Given 목록 등재됐으나 Approve 권한 없는 결재자에게 배정된 PENDING 요청, When 해당 결재자가 승인 시도, Then 거부(목록 등재만으로는 불충분).

---

## 4. 이력 오염

### RT-10 사유·파라미터·잡 이름의 저장형 XSS/HTML 주입
- 전제: 공격자가 요청 사유, 파라미터 값, (권한 창에서) 잡 이름에 임의 문자열 입력 가능. 이 값들은 요청/결재/대시보드/오류 화면에 렌더링됨.
- 절차: 사유에 `<script>...</script>` 또는 `<img src=x onerror=...>` 등을 넣어 요청 생성. 결재자·감사자가 화면을 열면 스크립트 실행 시도.
- 기대되는 취약 결과: 결재자 세션에서 스크립트 실행(승인 클릭잭킹/CSRF 유발), 이력 화면 오염.
- 설계상 방어가 있는가: **불명확** — CLAUDE.md 코드 규약 "사용자 입력이 HTML 출력에 들어가는 곳은 반드시 이스케이프"가 있음. Jelly 기본 이스케이프에 의존하나, `<st:out>` vs raw, 툴팁/CSV/JS 컨텍스트별 처리, 파라미터 값 렌더링 등 구체 명시가 없음. 설계 원칙은 있으나 구현·테스트 보장 미확인.
- 테스트되는가: 없음(XSS 전용 행 없음).
- 제안 테스트: Given 사유/파라미터/잡이름에 HTML·스크립트 페이로드, When 결재·대시보드 화면 렌더링, Then 페이로드가 이스케이프되어 텍스트로 표시(실행/삽입 안 됨).

### RT-11 CSV 인젝션(수식 주입)
- 전제: SPEC 12 — 실행 기록/오류/변경/요청 이력에 CSV 내보내기 제공. 공격자는 사유·파라미터 값을 임의로 제어.
- 절차: 사유 또는 파라미터 값을 `=cmd|'/c calc'!A1`, `+HYPERLINK(...)`, `@SUM(...)`, `-2+3` 등 수식 트리거 문자로 시작하도록 입력. 감사자가 CSV를 Excel/LibreOffice로 열면 수식 실행.
- 기대되는 취약 결과: 감사자 워크스테이션에서 수식/명령 실행, 데이터 유출(HYPERLINK 콜백), 감사 산출물이 공격 벡터가 됨.
- 설계상 방어가 있는가: **없음** — SPEC/ARCH/CLAUDE.md 어디에도 CSV 필드 무해화(선행 `= + - @ TAB CR` 이스케이프/따옴표 강제)를 언급하지 않음. `CsvExport`(ARCH action)에 대한 규칙 부재.
- 테스트되는가: 없음 — T-E2E-04는 행 수·열 존재만 검증하고 인젝션은 다루지 않음.
- 제안 테스트: Given 사유가 `=1+1`로 시작하는 요청, When 요청 이력 CSV 내보내기, Then 해당 셀이 수식으로 해석되지 않도록 접두 무해화(예: `'` 프리픽스 또는 셀 인용)된다.

### RT-12 잡 이름의 `/`·`..`·초장문 → 경로 탈출/파일 오염
- 전제: 저장소가 `snapshots/<jobFullName 인코딩>.xml`, 잡 이름 파생 파일명을 사용(ARCH 5).
- 절차: (권한 창에서) 잡 이름에 `../`, `%2F`, 매우 긴 문자열, 제어문자를 넣어 생성/이름변경 후 config 저장을 유발해 스냅숏/변경 파일 경로 계산을 공격.
- 기대되는 취약 결과: 저장소 밖 경로 쓰기, 다른 잡 스냅숏 덮어쓰기(디프 왜곡), 파일명 길이 초과로 쓰기 실패(기록 누락).
- 설계상 방어가 있는가: **있음** — ARCH 5: `/`→`%2F` 인코딩 + 디코딩 시 `..` 경로 탈출 검증 명시.
- 테스트되는가: **있음** — T-SEC-04(unit, `../x` 스냅숏 경로 계산 시 예외). 다만 초장문 이름·제어문자·유니코드 정규화는 미커버.
- 제안 테스트: Given 잡 이름 `../evil` 및 255자 초과 이름, When 스냅숏/변경 파일 경로 계산, Then 경로 탈출은 예외로 차단되고 장문 이름은 안전하게 인코딩/절단된다.

### RT-13 오류 건 콘솔 logTail의 비밀값 누출
- 전제: SPEC 11 — Incident에 콘솔 로그 마지막 100줄 발췌 저장(ARCH: `Run.getLog(100)`). ViewHistory 보유자가 조회 가능.
- 절차: 실패하는 빌드가 토큰·비밀번호·커넥션 문자열을 콘솔에 출력하도록 만든다(스크립트 echo, 스택트레이스, 서드파티 도구 출력). Incident 생성 시 그 100줄이 그대로 저장·표시됨.
- 기대되는 취약 결과: SPEC은 파라미터/`Secret`은 마스킹(SPEC 보안, 저장소 절)하지만 **콘솔 로그 원문은 마스킹 대상이 아님**. Password 파라미터가 아닌 경로로 콘솔에 노출된 비밀이 이력에 평문 저장되어 ViewHistory 권한자에게 유출.
- 설계상 방어가 있는가: **없음/불명확** — SPEC의 마스킹은 파라미터·`Secret` 타입 저장에 한정. logTail은 `Run.getLog(100)` 원문. Jenkins 코어의 로그 마스킹(`ConsoleLogFilter`/credentials-binding)이 이미 적용된 텍스트라면 일부 가려지나, 플러그인 차원의 추가 마스킹 명시 없음.
- 테스트되는가: T-11-01은 "logTail 100줄 이하"만 검증. **비밀 마스킹 여부 미검증.**
- 제안 테스트: Given 콘솔에 비밀 토큰을 출력하는 실패 빌드, When Incident logTail 저장, Then 알려진 비밀 패턴/`Secret` 값이 마스킹되거나 최소한 이 한계가 문서화된다.

---

## 5. 동시성

### RT-14 두 결재자 동시 승인 → 중복 투입
- 전제: 요청자가 결재자를 바꾸는 짧은 창, 또는 관리자 자가 결재 + 지정 결재자가 동시에 승인 클릭. ARCH: 상태 전이는 `RunRequestService`가 유일 진입점, 저장소 단위 `ReentrantLock`.
- 절차: 같은 requestId에 대해 두 승인 POST를 거의 동시에 보낸다.
- 기대되는 취약 결과: read(PENDING) → schedule → write(APPROVED) 구간이 원자적이지 않으면 두 번 `scheduleBuild2`가 호출되어 중복 빌드. 또는 APPROVED가 두 번 기록.
- 설계상 방어가 있는가: **불명확** — ARCH는 저장소 쓰기 잠금은 명시하나, "PENDING인지 확인 → 승인 → 투입"이 단일 임계구역(compare-and-set)인지 명시하지 않음. 상태 머신은 PENDING→APPROVED 1회를 의도하나 동시성 보장 문언 없음.
- 테스트되는가: 없음(동시성 행 부재).
- 제안 테스트: Given PENDING 요청, When 두 승인 POST 동시 실행, Then 정확히 1회만 APPROVED가 되고 빌드는 1회만 투입된다.

### RT-15 승인과 취소 동시
- 전제: 요청자는 PENDING에서 취소 가능(SPEC 7), 결재자는 승인 시도.
- 절차: 요청자 취소 POST와 결재자 승인 POST를 동시에 보낸다.
- 기대되는 취약 결과: 취소된 요청에 대해 빌드가 투입되거나(승인이 취소를 앞지름), CANCELLED와 APPROVED가 뒤섞인 일관성 깨짐, executedRunId가 CANCELLED 요청에 남음.
- 설계상 방어가 있는가: **불명확** — SPEC 7은 "취소는 PENDING에서만"을 명시하나 승인과의 경합 순서·원자성은 미정.
- 테스트되는가: 없음.
- 제안 테스트: Given PENDING 요청, When 취소와 승인을 동시에, Then 둘 중 하나만 성립하고(선착 CAS) 취소가 이기면 어떤 빌드도 투입되지 않는다.

### RT-16 만료 주기 작업과 상태 전이의 경합
- 전제: `ExpiryPeriodicWork`(1분 주기)와 승인/취소가 같은 순간 실행(ARCH ops). `approvedRunTimeoutMinutes` 경계.
- 절차: 요청이 만료 경계에 있을 때 주기 작업의 EXPIRED 기록과 사용자의 APPROVE/큐 투입이 동시에 진행되게 한다.
- 기대되는 취약 결과: EXPIRED로 표시됐는데 빌드는 투입됨(만료 승인으로 실행 — SPEC 7이 막으려는 바로 그 상황), 또는 APPROVED가 곧바로 EXPIRED로 덮여 정상 요청이 사라짐. 파일 last-writer-wins로 한쪽 갱신 유실.
- 설계상 방어가 있는가: **불명확** — D-08/ARCH는 "만료 판정 원본은 검사 시점 시각 비교, 주기 작업은 상태 갱신용"이라 원본은 안전하다는 취지이나, 투입 직전 만료 재확인(check-at-submit)이 명시되지 않음.
- 테스트되는가: T-07-01은 주기 작업 단독 만료만. **경합 미검증.**
- 제안 테스트: Given approvedRunTimeout 경계의 APPROVED 요청, When 큐 투입 시도와 만료 처리가 동시에, Then 투입 직전 시각 재확인으로 만료된 요청은 절대 투입되지 않는다.

### RT-17 재시작 복구 중복 투입(schedule↔onStarted 갭)
- 전제: SPEC 4 — "승인됐지만 큐 투입 전 재시작된 요청은 재시작 후 자동 투입, 중복 없음". `executedRunId`는 `RunListener.onStarted`에서 기록(ARCH 6).
- 절차: 승인 후 `scheduleBuild2` 호출은 됐으나 빌드가 아직 시작되지 않아 `onStarted` 미도달(따라서 `executedRunId=null`)인 순간에 컨트롤러가 크래시/재시작한다.
- 기대되는 취약 결과: 복구 로직이 `executedRunId==null`을 "미투입"으로 판단 → 재투입. 그런데 큐에 이미 있던 항목이 재시작 후 복원되면 **동일 요청이 2회 실행**. "중복 없음" 보장이 깨짐.
- 설계상 방어가 있는가: **불명확** — SPEC 4는 중복 없음을 요구하나, 판별 근거(`executedRunId` 유무)가 schedule↔onStarted 사이 크래시에 취약. 큐 항목과 요청의 idempotency 키(마커의 requestId, RT-02) 매칭으로 중복 제거한다는 설계 문언이 없음.
- 테스트되는가: T-04-02는 "재시작 후 정확히 1회 투입"을 요구하나 **크래시 타이밍(schedule 직후, onStarted 직전)은 명시되지 않아** 이 코너를 커버하는지 불확실.
- 제안 테스트: Given 승인 후 scheduleBuild2 완료·onStarted 미도달 상태에서 재시작, When 복구 실행, Then 요청당 빌드는 정확히 1회만 존재한다(idempotency 키로 중복 제거).

---

## 6. 가용성

### RT-18 대량 요청으로 저장소/메모리 폭증
- 전제: 공격자는 `BatchControl/Request`, `RequestGrant` 보유. 요청 1건 = `requests/run/<id>.xml` 파일 1개, PENDING은 최대 `pendingTimeoutHours`(기본 72h) 유지.
- 절차: 스크립트로 수만~수십만 건의 RunRequest/GrantRequest를 생성(POST 반복).
- 기대되는 취약 결과: 디렉터리 파일/inode 폭증, PENDING 목록 메모리 상주(GrantService는 활성 grant를 메모리 맵 유지 — ARCH 4), 요청/결재 목록 렌더링 지연, 백업/재시작 복구 시간 폭증.
- 설계상 방어가 있는가: **없음** — SPEC/ARCH에 사용자당 요청 rate limit, 동시 PENDING 상한, 총량 제한이 없음. 만료(72h)로 결국 정리되나 그 전까지 누적 방어 부재.
- 테스트되는가: 없음.
- 제안 테스트: Given 한 사용자가 단시간에 N건 요청 생성, When N이 임계 초과, Then 사용자당 PENDING 상한/rate limit으로 거부되거나 성능 저하가 한계 내로 유지된다.

### RT-19 초대형 파라미터·사유 값
- 전제: RunRequest.parameters(Map)와 reason은 사용자 입력, XStream XML로 저장(ARCH 5). 읽기는 월 파일을 메모리로 로드해 필터(ARCH 5).
- 절차: 수 MB~수십 MB의 단일 파라미터/사유 값을 담은 요청을 생성. 반복 시 저장 파일과 인메모리 캐시가 비대화.
- 기대되는 취약 결과: 개별 요청 파일 비대, 목록/캐시 로드 시 힙 압박(OOM 가능), 대시보드/이력 조회 지연. RunRecord도 parameters를 담으므로 실행 기록 JSONL도 오염.
- 설계상 방어가 있는가: **없음** — SPEC/ARCH에 파라미터/사유 길이·크기 상한 없음. 비밀 마스킹은 있으나 크기 제한과 무관.
- 테스트되는가: 없음.
- 제안 테스트: Given 사유/파라미터에 초대형 문자열, When 요청 생성, Then 필드별 크기 상한으로 거부되거나 안전하게 절단·기록된다.

### RT-20 권한 창 내 설정 저장 폭주로 diff/스냅숏 IO 폭발
- 전제: 공격자가 짧은 CONFIGURE Grant를 승인받음. `SaveableListener#onChange`가 매 저장마다 diff 계산 + `changes/diff/<id>.patch` + `changes/YYYY-MM.jsonl` append + 스냅숏 재작성(ARCH 2·5, 최신 1개 보관).
- 절차: 권한 창(예: 15분) 동안 한 잡의 config를 초당 수 회 반복 저장(REST config.xml POST 루프)한다.
- 기대되는 취약 결과: diff 패치 파일 다량 생성, 변경 JSONL 급팽창, 저장소 잠금(ARCH: 저장소 단위 `ReentrantLock`) 경합으로 다른 기록 지연/블로킹 → 컨트롤러 전반의 이력 기록 스톨. 부가로 스냅숏이 "최신 1개"라 폭주 중 baseline이 계속 바뀌어 diff 왜곡(이력 오염 겸함).
- 설계상 방어가 있는가: **없음/불명확** — 변경 저장 빈도 제한이나 diff 저장 상한이 SPEC/ARCH에 없음. 저장소 전역 락은 기록 정합엔 유리하나 폭주 시 단일 병목이 됨.
- 테스트되는가: 없음.
- 제안 테스트: Given 활성 CONFIGURE Grant, When 짧은 시간에 동일 잡 config를 수백 회 저장, Then diff/스냅숏 기록이 전체 기록 파이프라인을 블로킹하지 않고 diff baseline이 일관되게 유지된다.

---

## 가장 위험한 시나리오 (오케스트레이터용, 각 한 줄)

1. **RT-01** — Upstream 기본 통과 정책: 통제 off 잡에서 보호 잡을 트리거하면 승인 없이 실행됨(기본값 개방, `allowedUpstreamJobs` 미설정 동작 불명확).
2. **RT-05** — 권한 창 동안 cron 트리거를 심으면 창 만료 후에도 승인 통제를 영구 우회(신규 잡 approvalRequired 기본 false + cron 기본 통과).
3. **RT-13** — Incident logTail(콘솔 100줄)은 마스킹 대상이 아니라 콘솔에 노출된 비밀이 이력에 평문 저장·유출.
4. **RT-11** — CSV 내보내기에 수식 무해화가 없어 사유/파라미터로 감사자 워크스테이션 CSV 인젝션 가능.
5. **RT-17** — 승인 후 scheduleBuild2 직후·onStarted 전 크래시 시 executedRunId=null 판정으로 재시작 복구가 중복 투입할 수 있음.

(차순위: RT-14~16 동시성 원자성 미명세, RT-18/19 요청 플러딩·초대형 값 DoS, RT-03 잡 이름 스왑 TOCTOU)

## SPEC/ARCHITECTURE 변경이 필요해 보이는 제안 (문서는 사람 소유 — 제안만)

- **제안 R-1 (RT-01)**: SPEC 6에서 `allowedUpstreamJobs` 미설정 시 기본 동작을 명확화하고, 보호 잡의 Upstream 기본값을 "차단(화이트리스트만 통과)"으로 뒤집는 것을 검토. 최소한 "기본 통과"의 우회 위험을 잡 설정 도움말에 명시.
- **제안 R-2 (RT-05)**: 권한 창(CREATE/CONFIGURE) 동안 생성/변경된 잡의 트리거(cron/SCM)에 대한 정책 추가 — 예: 권한 창 내 신규 잡은 `approvalRequired`/`blockTimer` 기본 활성, 또는 트리거 추가를 별도 검토 대상으로.
- **제안 R-3 (RT-11)**: SPEC 12 CSV 내보내기에 필드 무해화(선행 `= + - @` 및 탭/개행 이스케이프) 요구사항을 수용 기준으로 추가.
- **제안 R-4 (RT-13)**: SPEC 11 logTail 저장에 비밀 마스킹 규칙(또는 명시적 한계 문서화)을 추가. 현재 마스킹은 파라미터/`Secret`에만 한정됨.
- **제안 R-5 (RT-14~17)**: 상태 전이(approve/cancel/expire/투입/복구)의 원자성(compare-and-set)과 투입 직전 만료 재확인, 요청 단위 idempotency 키를 ARCH 6/SPEC 4에 명세.
- **제안 R-6 (RT-03)**: RunRequest의 잡 참조를 이름 문자열 late-binding이 아니라 승인 시점 잡 신원(안정 식별자 또는 rename 시 요청 무효)으로 고정하도록 SPEC 3장 데이터 모델·SPEC 5 명세.
- **제안 R-7 (RT-18/19/20)**: 비기능 요구(SPEC 6)에 사용자당 요청 rate limit/PENDING 상한, 파라미터·사유 크기 상한, 변경 저장 폭주 방어를 추가.
- **제안 R-8 (RT-02)**: `ApprovedRunAction` 마커가 requestId+jobFullName+파라미터에 바인딩되고 단일 소비됨을 ARCH 2·6에 명시.

## test-author에게 전달 요청 (TEST-MATRIX 병합용)

위 각 시나리오의 "제안 테스트" 한 줄을 `T-RT-<일련>` 행으로 병합 권고. 특히 방어가 **없음/불명확**인 RT-01, RT-02, RT-03, RT-05, RT-10, RT-11, RT-13, RT-14, RT-15, RT-16, RT-17, RT-18, RT-19, RT-20은 P0/P1 후보. 이미 방어가 **있고 테스트되는** RT-04(T-SEC-03)·RT-12(T-SEC-04)는 본개발 코드 이식 확인용 회귀로 충분.
