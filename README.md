# batch-control-plugin 개발 키트

Jenkins 플러그인 `batch-control`을 Claude Code 멀티 에이전트로 개발하기 위한 문서·에이전트 정의 묶음입니다.

## 구성

```
CLAUDE.md                 Claude Code 프로젝트 규칙 (오케스트레이터 행동 규칙, 소유권, 명령어)
docs/SPEC.md              확정 기능 스펙 (모든 에이전트의 단일 기준)
docs/ARCHITECTURE.md      확장 포인트 매핑, 패키지 구조, 저장소 형식, 상태 머신
docs/DECISIONS.md         설계 결정 기록 (왜 이렇게 했는지)
docs/WORKFLOW.md          단계별 진행 순서, 에이전트 배정, 게이트, 실행 프롬프트
docs/TEST-MATRIX.md       테스트 매트릭스 (초기 시드, test-author가 완성)
docs/HOSTING-CHECKLIST.md jenkinsci 호스팅 요건 체크리스트
docs/STATUS.md            진행 상태 (오케스트레이터가 갱신)
.claude/agents/*.md       서브에이전트 정의 9종
```

## 적용 방법

1. 빈 디렉터리를 만들고 이 키트의 내용을 그대로 복사합니다. (`.claude/` 숨김 폴더 포함)
2. 디렉터리에서 `claude`를 실행합니다.
3. 첫 프롬프트로 `docs/WORKFLOW.md`의 "Phase 0" 프롬프트를 그대로 붙여 넣습니다.
4. 이후 각 Phase의 게이트에서 사람이 확인하는 항목만 직접 보고 다음 Phase 프롬프트를 넣습니다.

## 사람이 반드시 직접 하는 것

- Phase 1 종료: `docs/POC-RESULTS.md` 읽고 설계 유지/수정 결정
- Phase 2 종료: `docs/TEST-MATRIX.md` 읽고 빠진 시나리오 추가 (가장 중요)
- Phase 5: 사내 Jenkins 시범 운영
- Phase 6: 호스팅 요청 이슈 등록과 리뷰어 대응

## 에이전트 한눈에 보기

| 에이전트 | 역할 | 쓰기 권한 범위 |
|---|---|---|
| (메인 세션) | 오케스트레이터. 작업 분배, 게이트 판정, STATUS 갱신 | docs/STATUS.md만 |
| poc-engineer | 설계 가정 검증 PoC | poc/, docs/POC-RESULTS.md |
| spec-guardian | 변경이 SPEC과 맞는지 검토 (읽기 전용) | docs/reports/spec-review-*.md |
| core-dev | 도메인·저장소·권한·큐·리스너 구현 | src/main/java (action/ui 제외) |
| ui-dev | 화면(Jelly), Action, 대시보드 구현 | src/main/java/.../action, .../ui, src/main/resources |
| test-author | SPEC과 매트릭스만 보고 JenkinsRule 테스트 작성 | src/test, docs/TEST-MATRIX.md |
| red-team | 설계를 깨뜨릴 시나리오 발굴 (읽기 전용) | docs/reports/red-team-*.md |
| security-reviewer | Jenkins 보안 심사 기준으로 코드 리뷰 (읽기 전용) | docs/reports/security-*.md |
| e2e-tester | Docker Jenkins + 브라우저로 시나리오 실행 | e2e/, docs/reports/e2e-*.md |
| release-manager | pom, README, Jenkinsfile, 호스팅 요청서 | pom.xml, README.md, Jenkinsfile, CHANGELOG.md, docs/HOSTING-REQUEST.md |
