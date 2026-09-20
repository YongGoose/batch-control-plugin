# 개발 워크플로우 — Phase별 에이전트 배정

메인 세션(오케스트레이터)은 이 문서 순서대로 진행합니다. 각 Phase는 "입력 → 에이전트 → 산출물 → 게이트"로 구성됩니다. 게이트에 🧑 표시가 있으면 사람 확인 후에만 다음으로 갑니다.

전체 흐름:
```
P0 셋업 → P1 PoC 🧑 → P2 테스트 매트릭스 🧑 → P3 구현(슬라이스 4개) → P4 보안 리뷰 → P5 E2E → P6 시범 운영 🧑 → P7 공개·신청 🧑
```

---

## Phase 0. 프로젝트 셋업 (메인 세션 직접, 30분)

**작업**
1. `mvn -U archetype:generate -Dfilter="io.jenkins.archetypes:"`로 `empty-plugin` 생성. artifactId `batch-control`, groupId `io.jenkins.plugins`, package `io.jenkins.plugins.batchcontrol`.
2. 생성된 파일을 이 키트 디렉터리에 합친다(키트의 CLAUDE.md, docs/, .claude/ 유지).
3. pom.xml: 부모 POM 최신, `jenkins.version` 최신 LTS, 의존성 `workflow-job`, `workflow-cps`(테스트용), `matrix-auth`(테스트용), `cloudbees-folder`, `structs`. 테스트 스코프에 `jenkins-test-harness`.
4. `Jenkinsfile`에 `buildPlugin()`.
5. `mvn -q clean verify` 통과 확인. `git init`, 첫 커밋.
6. `docs/STATUS.md`에 Phase 0 완료 기록.

**게이트**: `mvn clean verify` 녹색.

**프롬프트**
```
docs/WORKFLOW.md의 Phase 0을 수행해. CLAUDE.md의 규칙을 먼저 읽고, 완료되면 docs/STATUS.md를 갱신하고 결과를 보고해.
```

---

## Phase 1. PoC — 설계 가정 검증 (poc-engineer, 2~3일)

**목적**: 본개발 전에 다음 세 가정을 실제 Jenkins에서 확인한다. 실패하면 설계로 돌아간다.

| # | 가정 | 검증 방법 |
|---|---|---|
| A | `QueueDecisionHandler`가 UI, REST(build/buildWithParameters), CLI build, Pipeline Replay, `build` 스텝 경로를 모두 가로챈다 | `JenkinsRule` 테스트로 5개 경로 각각 차단 확인 |
| B | `ItemListener.onCheckDelete`가 UI·REST·CLI 삭제를 모두 거부한다 | 3개 경로 테스트 |
| C | 위임형 `AuthorizationStrategy`가 Matrix와 Role 전략 위에서 임시 Item/Configure·Create·Delete를 부여하고, 만료 시각 경과 후 즉시 거부한다 | Matrix로 테스트, Role은 문서 조사로 호환성 확인 |
| D | 차단 시 사용자에게 안내를 보여줄 수 있다(조용한 실패 회피) | `hpi:run`으로 수동 확인 + 스크린샷 |

**입력**: `docs/SPEC.md` 6·8번, `docs/ARCHITECTURE.md` 2·4절
**쓰기 경로**: `poc/`(별도 Maven 모듈 또는 브랜치 `phase-1-poc`), `docs/POC-RESULTS.md`
**산출물**: `docs/POC-RESULTS.md` — 가정별 결과(통과/실패/조건부), 근거 테스트 파일명, 발견한 제약, 설계 수정 제안

**게이트 🧑**: 사람이 POC-RESULTS를 읽고 "설계 유지" 또는 DECISIONS 수정 결정. A·B·C 중 하나라도 실패면 Phase 3 진입 금지.

**프롬프트**
```
poc-engineer 서브에이전트에게 docs/WORKFLOW.md Phase 1을 위임해. 가정 A~D를 순서대로 검증하고 docs/POC-RESULTS.md를 작성하게 해. 끝나면 결과 요약을 보고하고 내 확인을 기다려.
```

---

## Phase 2. 테스트 매트릭스 (test-author + red-team, 병렬, 1일)

**목적**: 구현 전에 검증 기준을 확정한다. 이 매트릭스가 이후 모든 테스트의 원천이다.

**작업**
- test-author: `docs/SPEC.md`의 모든 수용 기준을 `docs/TEST-MATRIX.md`의 행으로 옮긴다(Given/When/Then, 계층, 우선순위). 부정 경로를 항목마다 최소 1개 포함.
- red-team: SPEC과 ARCHITECTURE만 보고 "이 설계를 깨뜨릴 방법"을 찾아 `docs/reports/red-team-01.md`에 시나리오로 적는다. 동시성, 경계 시각, 이름 변경·이동, 폴더 범위 탈출, 파라미터 변조, 권한 창 악용 등.
- 메인 세션: red-team 시나리오 중 유효한 것을 test-author에게 넘겨 매트릭스에 추가.

**쓰기 경로**: test-author → `docs/TEST-MATRIX.md` / red-team → `docs/reports/red-team-01.md`
**산출물**: 완성된 `docs/TEST-MATRIX.md` (ID, 스펙 항목, 계층[unit/integration/e2e], 우선순위[P0/P1/P2], Given/When/Then)

**게이트 🧑**: **사람이 매트릭스를 직접 읽는다.** 여기서 빠진 시나리오는 이후 모든 층에서 빠진다. 추가할 것을 적고 승인.

**프롬프트**
```
Phase 2를 시작해. test-author와 red-team을 병렬로 위임해. test-author는 SPEC의 수용 기준 전부를 TEST-MATRIX로 변환하고, red-team은 설계를 깨뜨릴 시나리오를 docs/reports/red-team-01.md에 작성해. 둘 다 끝나면 red-team 시나리오 중 유효한 것을 test-author에게 넘겨 매트릭스에 병합시키고, 최종 매트릭스 요약(총 행 수, P0 수, 항목별 분포)을 보고한 뒤 내 확인을 기다려.
```

---

## Phase 3. 구현 (core-dev, ui-dev, test-author, spec-guardian; 슬라이스 4개, 1~2주)

슬라이스마다 같은 루프를 돈다:

```
1. test-author: 해당 슬라이스의 매트릭스 행 → JenkinsRule 테스트 작성 (src/test). 실패 상태로 커밋.
2. core-dev: 테스트를 통과시키는 구현 (자기 소유 패키지). ui-dev와 병렬.
3. ui-dev: 화면·Action 구현. core-dev의 policy/store 공개 API 사용.
4. mvn clean verify 녹색.
5. spec-guardian: 이 슬라이스 diff가 SPEC과 맞는지 검토 → docs/reports/spec-review-S<n>.md
6. 지적 사항 수정 → 슬라이스 종료, 커밋.
```

병렬 규칙: 1은 2·3보다 먼저 시작하되, 2·3은 1이 끝나기 전에 시작해도 된다(테스트가 실패하는 상태에서 구현 시작). 2와 3은 동시에 돌린다(경로가 겹치지 않음). 5는 4 이후.

| 슬라이스 | 범위 (SPEC 항목) | core-dev | ui-dev |
|---|---|---|---|
| S1 기반 | 1, 2, 4 | model, store(FileStore), config(GlobalConfiguration, JobProperty), security(Permissions) | 전역 설정 화면, 잡 설정 섹션 |
| S2 실행 통제 | 3, 5, 6, 7 | policy(RunRequestService, ApprovalPolicy), queue, ops(ExpiryPeriodicWork, StartupRecovery) | JobRequestAction(요청 폼, Build Now 대체), RunRequestRootAction(대기함, 승인/반려) |
| S3 변경 통제 | 8, 9 | security(AuthorizationStrategy, GrantService), policy(GrantRequestService), listener(ItemChange, ConfigSnapshot, DeleteVeto), ops(Monitor) | GrantRootAction(요청·결재·활성 권한·회수), 변경 기록 화면 |
| S4 운영 | 10, 11, 12 | listener(RunRecordListener), ops(IncidentService, RetentionPeriodicWork) | Dashboard, Incident 화면, History·집계·CSV |

**게이트(슬라이스마다)**: `mvn clean verify` 녹색 + spec-guardian 보고서에 "차단(BLOCKER)" 없음.
**게이트(Phase 종료)**: P0 테스트 전부 통과, P1 90% 이상.

**프롬프트 (슬라이스 1 예시, S2~S4는 번호만 바꿈)**
```
Phase 3 슬라이스 S1을 시작해. 순서:
1) test-author에게 TEST-MATRIX에서 SPEC 1,2,4번 행을 골라 src/test에 JenkinsRule 테스트를 작성하게 해. src/main은 읽지 말라고 명시해.
2) test-author가 테스트 파일 목록을 보고하면 core-dev와 ui-dev를 병렬로 위임해. core-dev는 model/store/config/security, ui-dev는 전역 설정 화면과 잡 설정 섹션. 각자 ARCHITECTURE.md의 패키지 경계를 지키게 해.
3) mvn clean verify가 녹색이 되면 spec-guardian에게 S1 diff 검토를 맡겨 docs/reports/spec-review-S1.md를 받아.
4) BLOCKER가 있으면 해당 소유 에이전트에게 수정시키고 다시 3).
5) 완료 후 STATUS.md 갱신, 요약 보고.
```

---

## Phase 4. 보안 자체 심사 (security-reviewer, 1~2일)

**작업**
1. security-reviewer: `docs/HOSTING-CHECKLIST.md`의 보안 항목 기준으로 `src/main` 전수 검토 → `docs/reports/security-01.md`. 심각도(BLOCKER/HIGH/MEDIUM/LOW), 파일:라인, 수정 방향.
2. 메인 세션: 지적 사항을 소유 에이전트(core-dev/ui-dev)에게 배분해 수정. test-author에게 회귀 테스트 추가 요청(권한 없는 호출 403, GET으로 상태 변경 시도 405 등).
3. release-manager: `.github/workflows/jenkins-security-scan.yml`(Jenkins 프로젝트 공식 보안 스캔 액션) 추가. SpotBugs는 `mvn verify`에 포함됨.
4. security-reviewer 재검토 → `security-02.md`.

**게이트**: BLOCKER/HIGH 0건. 보안 스캔 워크플로 파일 존재.

**프롬프트**
```
Phase 4를 시작해. security-reviewer에게 src/main 전체를 HOSTING-CHECKLIST 보안 기준으로 검토시켜 docs/reports/security-01.md를 받아. 지적 사항을 소유 에이전트에게 배분해 수정하고, test-author에게 각 지적에 대한 회귀 테스트를 추가시켜. release-manager에게 jenkins-security-scan 워크플로를 추가시켜. 재검토 후 BLOCKER/HIGH 0건이면 보고해.
```

---

## Phase 5. E2E 시나리오 (e2e-tester, 2~3일)

**전제**: Docker 설치, Claude Code에 Playwright MCP 연결.

**작업**
1. e2e-tester: `e2e/docker-compose.yml`(Jenkins LTS + 플러그인 `.hpi` 마운트 + Matrix 권한 + 사용자 3명 `requester`/`approver`/`admin` + 샘플 잡 3개(파라미터 Freestyle, Pipeline, cron 잡) 초기화 스크립트) 작성.
2. `docs/TEST-MATRIX.md`의 계층=e2e 행을 브라우저로 수행. 시나리오마다 스크린샷 저장(`e2e/screenshots/<id>.png`).
3. REST 경로는 `e2e/scripts/*.sh`(curl + crumb)로 병행.
4. `docs/reports/e2e-01.md`: 시나리오별 PASS/FAIL, 스크린샷 링크, UX 문제(스펙 위반은 아니지만 쓰기 불편한 것) 별도 절.
5. FAIL과 UX 문제를 소유 에이전트에게 배분 → 수정 → 재실행 `e2e-02.md`.

**게이트**: e2e P0 전부 PASS. UX 문제는 사람이 판단.

**프롬프트**
```
Phase 5를 시작해. e2e-tester에게 docker-compose 환경을 구성하고 TEST-MATRIX의 e2e 행을 Playwright로 수행시켜 docs/reports/e2e-01.md를 받아. FAIL은 소유 에이전트에게 배분해 수정하고 재실행시켜. UX 문제 목록은 나에게 보고하고 판단을 기다려.
```

---

## Phase 6. 시범 운영 (사람, 1주) 🧑

- `.hpi`를 사내 Jenkins에 설치, 실행 통제부터 켜서 실제 배치 2~3개에 적용.
- 실제 결재자가 써 보게 하고 불편 사항 수집.
- 수집한 항목을 메인 세션에 전달 → 소유 에이전트에게 배분 → 수정 → Phase 4·5 축소 재실행.

**게이트 🧑**: 사람이 "공개 가능" 판단.

---

## Phase 7. 공개 준비와 호스팅 신청 (release-manager, 2~3일 + 심사 대기)

**작업**
1. release-manager: `docs/HOSTING-CHECKLIST.md` 전 항목 점검 → `README.md`(영어: 목적, 배치 환경 문맥, 설치, 설정, 스크린샷, 알려진 제약, `input` 스텝·Job StrongAuthSimple과의 차이), `LICENSE`(MIT), `CHANGELOG.md`, pom 메타데이터(licenses, developers, scm, url), `Jenkinsfile`.
2. release-manager: `docs/HOSTING-REQUEST.md`에 호스팅 요청 이슈 본문 초안(영어) 작성. 항목: 저장소 URL, 새 저장소명 `batch-control-plugin`, 설명, GitHub 사용자, Jenkins 계정, 이슈 트래커(GitHub), 기존 플러그인과의 차이.
3. 사람: 개인 GitHub에 푸시, `jenkins-infra/repository-permissions-updater`에 이슈 등록.
4. 봇 지적 → 메인 세션이 소유 에이전트에게 배분 → 수정 → `/hosting re-check`.
5. 승인 → jenkinsci 포크 → 원본 삭제 → CD(JEP-229) 설정(release-manager가 `.github/workflows/cd.yml` 작성) → 첫 릴리스.

**게이트 🧑**: 업데이트 센터에서 설치 가능.

**프롬프트**
```
Phase 7을 시작해. release-manager에게 HOSTING-CHECKLIST 전 항목을 점검시키고 README, LICENSE, CHANGELOG, pom 메타데이터, docs/HOSTING-REQUEST.md를 작성하게 해. 체크리스트에서 미충족 항목이 있으면 소유 에이전트에게 배분해. 완료되면 요청서 초안을 나에게 보여줘.
```

---

## 운영 규칙

- 한 Phase 안에서 막히면 3회까지 자체 해결 시도 후 사람에게 보고한다.
- 에이전트 보고서에 "요청: <경로>"가 있으면 메인 세션이 소유 에이전트에게 전달한다.
- STATUS.md는 매 위임 전후에 갱신한다. 형식은 STATUS.md 상단 참고.
