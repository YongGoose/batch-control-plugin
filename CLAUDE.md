# batch-control-plugin — Claude Code 프로젝트 규칙

이 저장소는 Jenkins 플러그인 `batch-control`(Jenkins를 배치 실행 관리 도구로 쓰는 환경을 위한 실행·변경 승인과 감사 이력 플러그인)입니다. 목표는 jenkinsci 공식 조직 호스팅입니다.

## 단일 기준

- 기능의 정답은 `docs/SPEC.md`입니다. 코드와 스펙이 다르면 코드가 틀린 것입니다.
- 스펙을 바꿔야 한다고 판단되면 코드를 고치지 말고 `docs/DECISIONS.md`에 제안을 적고 사람에게 묻습니다.
- 확장 포인트와 패키지 구조는 `docs/ARCHITECTURE.md`를 따릅니다.
- 진행 상태는 `docs/STATUS.md` 한 곳에서만 관리합니다.

## 메인 세션(오케스트레이터)의 역할

메인 세션은 코드를 직접 작성하지 않습니다. 다음만 합니다.

1. `docs/WORKFLOW.md`의 현재 Phase를 확인한다.
2. 해당 Phase의 서브에이전트에게 작업을 위임한다. 위임 시 반드시 "읽을 문서", "쓸 수 있는 경로", "완료 산출물"을 명시한다.
3. 산출물이 돌아오면 게이트 조건을 확인하고 `docs/STATUS.md`를 갱신한다.
4. 게이트에 "사람 확인"이 있으면 멈추고 사람에게 보고한다.

병렬 실행 가능한 작업(예: core-dev와 test-author)은 동시에 위임하되, 같은 경로에 쓰는 에이전트를 동시에 돌리지 않는다.

## 경로 소유권 (충돌 방지의 핵심)

| 경로 | 쓰기 가능 에이전트 |
|---|---|
| `docs/STATUS.md` | 메인 세션 |
| `docs/SPEC.md`, `docs/ARCHITECTURE.md`, `docs/DECISIONS.md` | 사람만 (에이전트는 제안만) |
| `poc/**`, `docs/POC-RESULTS.md` | poc-engineer |
| `src/main/java/io/jenkins/plugins/batchcontrol/{model,store,policy,security,queue,listener,config,ops}/**` | core-dev |
| `src/main/java/io/jenkins/plugins/batchcontrol/{action,ui}/**`, `src/main/resources/**` | ui-dev |
| `src/test/**`, `docs/TEST-MATRIX.md` | test-author |
| `e2e/**`, `docs/reports/e2e-*.md` | e2e-tester |
| `docs/reports/security-*.md` | security-reviewer |
| `docs/reports/red-team-*.md` | red-team |
| `docs/reports/spec-review-*.md` | spec-guardian |
| `src/main/webapp/help/**` | ui-dev |
| `pom.xml`, `README.md`, `CONTRIBUTING.md`, `Jenkinsfile`, `CHANGELOG.md`, `LICENSE`, `.github/**`, `docs/HOSTING-REQUEST.md`, `docs/HOSTING-READINESS.md` | release-manager |
| `docs/HANDOFF.md` | 메인 세션 |

자기 소유가 아닌 경로를 고쳐야 하면 고치지 말고 산출물 보고서에 "요청: <경로> <내용>"으로 적는다. 메인 세션이 소유 에이전트에게 전달한다.

## 코드 규약

- Java 17, Maven. 부모 POM은 `org.jenkins-ci.plugins:plugin` 최신 버전, `jenkins.version`은 plugin BOM이 지원하는 최신 LTS 라인.
- `groupId`: `io.jenkins.plugins`, `artifactId`: `batch-control`, 패키지: `io.jenkins.plugins.batchcontrol`.
- 모든 산출물은 영어로 작성한다: 코드, 주석, 커밋 메시지, README, 설계 문서(docs/), 리포트, GitHub 이슈·PR (2026-09-20 사람 지시 — Jenkins는 글로벌 사용자 대상). 기존 한국어 문서는 소급 번역하지 않는다(사람이 별도 지시할 때만). 사용자와의 대화는 한국어.
- 상태를 바꾸는 모든 Stapler 웹 메서드(`do*`)는 `@RequirePOST` + 권한 체크가 첫 두 줄이다. 예외 없음.
- 사용자 입력이 파일 경로, 잡 이름, HTML 출력에 들어가는 곳은 반드시 검증/이스케이프한다.
- `ACL.SYSTEM2`로 전환하는 코드는 이유를 주석으로 남기고, 전환 전에 요청자·결재자 권한 체크가 끝나 있어야 한다.
- 새 기능은 전역 스위치가 꺼진 상태에서 기존 Jenkins 동작을 바꾸지 않는다.
- 저장 형식은 `docs/ARCHITECTURE.md`의 저장소 절을 따른다. 임의로 새 파일 형식을 만들지 않는다.

## 자주 쓰는 명령

```bash
mvn -q clean verify            # 컴파일 + 테스트 + SpotBugs
mvn -q test -Dtest=ClassName   # 단일 테스트
mvn hpi:run                    # 로컬 Jenkins (http://localhost:8080/jenkins)
mvn -q clean package -DskipTests && ls target/*.hpi
```

## 테스트 독립성 규칙

test-author는 `src/main`을 읽지 않는다. 테스트는 `docs/SPEC.md`와 `docs/TEST-MATRIX.md`에서만 도출한다. core-dev는 테스트를 통과시키기 위해 테스트를 수정하지 않는다. 테스트가 잘못됐다고 판단되면 보고서에 적고 사람이 판단한다.

## 커밋과 브랜치

- Phase마다 브랜치: `phase-1-poc`, `phase-3-impl`, ... 게이트 통과 후 `main`에 머지.
- 커밋 메시지는 Conventional Commits (`feat:`, `fix:`, `test:`, `docs:`, `chore:`).
- 에이전트는 커밋만 하고 푸시는 사람이 한다.
