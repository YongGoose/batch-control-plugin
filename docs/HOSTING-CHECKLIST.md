# jenkinsci 호스팅 체크리스트

release-manager와 security-reviewer가 사용한다. 출처: jenkins.io 플러그인 호스팅 가이드, repository-permissions-updater Hosting Checker, Jenkins 플러그인 보안 가이드.

## A. 자동 검사 (Hosting Checker) 대응

- [ ] `pom.xml` `groupId` = `io.jenkins.plugins`
- [ ] `artifactId` = `batch-control` ("jenkins" 미포함, `-plugin`으로 끝나지 않음)
- [ ] 저장소 이름 = `batch-control-plugin`
- [ ] 부모 POM `org.jenkins-ci.plugins:plugin` 최신 버전
- [ ] `jenkins.version`이 지원 중인 LTS
- [ ] `<licenses>` MIT 명시, 루트에 `LICENSE` 파일(MIT 전문)
- [ ] `<developers>`에 Jenkins 커뮤니티 계정 ID
- [ ] `<scm>`, `<url>` 정확
- [ ] `README.md` 존재, 기능 설명·설치·설정 포함
- [ ] `Jenkinsfile`에 `buildPlugin()`
- [ ] `src/main/resources/index.jelly`에 플러그인 설명
- [ ] 의존성에 사용하지 않는 플러그인 없음
- [ ] 릴리스 버전이 아닌 `${revision}${changelist}` 방식(CD 준비)

## B. 보안 (security-reviewer 검토 기준)

- [ ] 상태를 바꾸는 모든 `do*` 메서드에 `@RequirePOST` (또는 `@POST`)
- [ ] 모든 `do*` 메서드 첫 부분에 `checkPermission(...)`. 잡 스코프면 잡의 ACL, 전역이면 `Jenkins.get()`
- [ ] `doFill*Items`, `doCheck*` 메서드에도 권한 체크 (정보 노출 방지)
- [ ] Jelly에서 사용자 입력 출력 시 `${h.escape(...)}` 또는 `<j:out escapeXml="true">`. `escapeXml="false"` 사용 금지
- [ ] `ACL.SYSTEM2` 전환 지점마다 주석으로 이유, 전환 전 요청자·결재자 권한 확인 완료
- [ ] 비밀값(`Secret`, PasswordParameterValue)을 로그·이력·diff에 평문 저장하지 않음
- [ ] 사용자 입력이 파일 경로에 들어가는 곳: 인코딩 + `..` 차단 + 루트 밖 접근 검증
- [ ] CSV 내보내기: 셀 값이 `=`, `+`, `-`, `@`로 시작하면 앞에 `'` 추가 (CSV injection)
- [ ] XStream 역직렬화: 모델 클래스는 `Serializable`이 아닌 단순 POJO, 알 수 없는 타입 거부
- [ ] 외부 입력으로 XML 파싱 없음 (있다면 XXE 방지 설정)
- [ ] 세션·CSRF: Jenkins crumb 표준 폼 사용, 커스텀 AJAX는 crumb 헤더 포함
- [ ] 권한 정의는 `Permission` 객체. 문자열 비교로 사용자 ID 판정하는 권한 로직 없음
- [ ] 위임형 AuthorizationStrategy가 delegate 없이 설정되면 안전 기본값(모든 권한 거부, 관리자 제외)
- [ ] `@Restricted(NoExternalUse.class)`를 공개 API가 아닌 클래스에 부착
- [ ] SpotBugs 경고 0 (`mvn verify`)
- [ ] `.github/workflows/jenkins-security-scan.yml` 존재

## C. 품질

- [ ] `JenkinsRule` 테스트가 SPEC P0 전부 커버
- [ ] `mvn clean verify` 녹색
- [ ] 스위치 off 상태에서 기존 동작 무변경 테스트(T-01-01) 통과
- [ ] 한국어 문자열이 코드·리소스에 없음 (Messages.properties 영어, 필요 시 `Messages_ko.properties` 별도)

## D. 요청서 (docs/HOSTING-REQUEST.md)

- [ ] 원본 저장소 URL
- [ ] 새 저장소명 `batch-control-plugin`
- [ ] 한 문단 설명: 배치 실행 관리 환경, 실행 승인 + JIT 변경 권한 + 감사 이력
- [ ] 기존 플러그인과의 차이: `input` 스텝(파이프라인 내부 게이트, 잡 실행 자체 미통제, 잡 간 이력 없음), Job StrongAuthSimple(13년 미관리, Pipeline·이력·JIT 없음), Audit Trail/Audit Log(요청·승인 개념 없음, 기록만)
- [ ] 커밋 권한 GitHub 사용자, 릴리스 권한 Jenkins 계정
- [ ] 이슈 트래커: GitHub Issues
- [ ] 사내 시범 운영 사실 (있으면)

## E. 승인 후

- [ ] jenkinsci 조직 초대 수락
- [ ] 원본 저장소 삭제 (포크 관계 해소)
- [ ] `.github/workflows/cd.yml` (JEP-229) + `.github/release-drafter.yml`
- [ ] 첫 릴리스 태그 → 업데이트 센터 확인
