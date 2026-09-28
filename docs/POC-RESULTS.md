# PoC 결과

- 실행 환경: Jenkins 2.568.3 (parent POM `org.jenkins-ci.plugins:plugin:6.2236.v12dd4c483242`, BOM `bom-2.568.x:7046.v43536164769c`), Java 21, JUnit 5 `@WithJenkins`
- 실행 명령: `poc/` 디렉터리에서 `mvn -ntp test` — **22개 테스트 전부 통과** (실패 0, 스킵 1은 하네스가 주입하는 InjectedTest의 기본 스킵)
- PoC 코드 위치: `poc/src/main/java/io/jenkins/plugins/batchcontrol/poc/`, 테스트: `poc/src/test/java/io/jenkins/plugins/batchcontrol/poc/`

## 요약

| 가정 | 결과(통과/조건부/실패) | 근거 테스트 |
|---|---|---|
| A. QueueDecisionHandler가 모든 실행 경로를 가로챈다 | **통과** (6개 경로 전부 차단 + 마커 통과 확인) | `AssumptionAQueueBlockTest` (7개 메서드) |
| B. ItemListener.onCheckDelete가 UI·REST·CLI 삭제를 거부한다 | **통과** (3개 경로 + 프로그래밍 경로) | `AssumptionBDeleteVetoTest` (4개 메서드) |
| C. 위임형 AuthorizationStrategy로 임시 권한 부여·만료 | **통과** (Matrix 코드 검증) / Role Strategy는 **조건부** (문서 조사 결과 아래 참고) | `AssumptionCTemporaryGrantTest` (4개 메서드) |
| D. 차단 시 사용자 안내 표시(조용한 실패 회피) | **조건부** (WebClient HTML 단언으로 검증 완료, 브라우저 육안 확인은 Phase 5로 이월) | `AssumptionDGuidanceTest` (3개 메서드) |

## 가정 A — QueueDecisionHandler 실행 경로 차단

- 결과: **통과**
- 검증 방법: `PocQueueDecisionHandler`(`Queue.QueueDecisionHandler` 구현)가 지정 잡의 스케줄 시도를 차단하고, 차단 시점에 관찰한 Cause 목록을 기록. 각 경로마다 별도 테스트 메서드로 (1) `Jenkins.get().getQueue().getItems()`가 빈 배열인지, (2) 빌드 번호가 늘지 않았는지, (3) 핸들러가 실제로 호출되어 차단했는지(기록 존재) 세 가지를 모두 단언.
  1. UI 빌드 버튼 — 잡 페이지의 `Build Now` 앵커를 HtmlUnit으로 클릭 (`uiBuildButtonIsBlocked`)
  2. `POST /job/X/build` (`restBuildIsBlocked`)
  3. `POST /job/X/buildWithParameters` (`restBuildWithParametersIsBlocked`)
  4. CLI `build` — `CLICommandInvoker` (`cliBuildIsBlocked`)
  5. Pipeline Replay — `ReplayAction.run(...)` 반환값 null 확인 (`pipelineReplayIsBlocked`)
  6. 상위 Pipeline의 `build` 스텝 (`upstreamBuildStepIsBlocked`)
  7. 역방향 확인: 승인 마커 `PocMarkerAction`을 실은 투입은 통과되어 빌드 성공 (`markerActionPassesThrough`)
- 발견한 제약:
  - 경로별 실제 Cause 클래스(핸들러가 관찰한 값):
    | 경로 | Cause |
    |---|---|
    | UI 버튼, `/build`, `/buildWithParameters` | `hudson.model.Cause$UserIdCause` |
    | CLI `build` | `hudson.cli.BuildCommand$CLICause` (UserIdCause의 하위 타입 — 힌트대로 확인됨) |
    | Replay | `Cause$UserIdCause` + `org.jenkinsci.plugins.workflow.cps.replay.ReplayCause` |
    | `build` 스텝 | `org.jenkinsci.plugins.workflow.support.steps.build.BuildUpstreamCause` (**`Cause.UpstreamCause`의 하위 클래스** — `instanceof UpstreamCause` 판정으로 잡힘) |
  - **조용히 차단(`return false`)하면 정말 조용하다**: REST POST는 HTTP 200으로 정상 응답하고, CLI `build`는 exit 0에 출력이 전혀 없다. 즉 SPEC 6의 "조용한 실패 금지"를 지키려면 `return false`만으로는 부족하고 `Failure` throw(가정 D 참고)나 별도 안내 장치가 필수다.
  - `build` 스텝 차단 시 **상위 잡이 FAILURE로 끝난다** (`ERROR: Failed to trigger build of downstream`). `wait: false`여도 큐 투입 실패 시점에 상위 스텝이 AbortException을 던진다. SPEC 6에서 Upstream은 기본 통과이므로 실사용 영향은 `blockUpstream=true`인 잡에 한정되지만, "상위 잡 실패"라는 부수 효과를 문서·안내 문구에 명시해야 한다.
  - Pipeline Replay용 `build` 스텝은 `workflow-basic-steps`가 아니라 **`org.jenkins-ci.plugins:pipeline-build-step`** 플러그인에 있다. 테스트 의존성에 추가해야 경로 6을 검증할 수 있다(poc/pom.xml에 반영함).
- 설계 수정 제안: 없음 (설계 유지). 단, Cause 분류 로직은 `BuildUpstreamCause`처럼 하위 타입이 존재하므로 클래스 동일성 비교가 아닌 `instanceof` 기반이어야 한다.

## 가정 B — onCheckDelete 삭제 거부

- 결과: **통과**
- 검증 방법: `PocDeleteVetoListener`(`ItemListener#onCheckDelete`에서 `hudson.model.Failure` throw). 세 경로 모두 삭제가 거부되고 잡이 그대로 남아 있음을 단언.
  1. UI — 잡 페이지 Delete 액션이 보내는 것과 동일한 `POST /job/X/doDelete` (`uiDeleteIsRejected`): **HTTP 400** + 응답 페이지에 Failure 메시지 표시 확인
  2. REST — `DELETE /job/X/` (`restHttpDeleteIsRejected`): **HTTP 500**으로 거부
  3. CLI — `delete-job` (`cliDeleteJobIsRejected`): exit 1, stderr에 `ERROR: Unexpected exception occurred while performing delete-job command.` + Failure 메시지가 포함된 스택트레이스
  4. 프로그래밍 경로 — `Item.delete()`가 `Failure`를 그대로 전파 (`programmaticDeleteIsRejected`)
- 발견한 제약:
  - 거부는 세 경로 모두 확실하지만 **표현 품질이 경로마다 다르다**: POST `doDelete`는 400 + 메시지 페이지(양호), HTTP DELETE는 500(스택트레이스성 응답), CLI는 "Unexpected exception" 문구 뒤 스택트레이스 안에 메시지가 묻힌다. 통제 기능 자체는 성립하나 REST/CLI 사용자 경험은 거칠다.
  - 스택트레이스로 호출 경로 확인: `AbstractItem.delete()` → `ItemListener.checkBeforeDelete()` → `onCheckDelete()`. 즉 core가 공식 지원하는 거부 지점이다(Jenkins 2.107+).
- 설계 수정 제안: 없음 (설계 유지). CLI/REST 메시지 품질은 알려진 제약으로 README에 기재 권고.

## 가정 C — 위임형 AuthorizationStrategy 임시 권한

- 결과: **통과** (Matrix 코드 검증) / Role Strategy는 **조건부**
- 검증 방법: `PocDelegatingAuthorizationStrategy`가 `GlobalMatrixAuthorizationStrategy`를 delegate로 감싸고, `PocGrantStore`가 주입된 `java.time.Clock`으로 만료를 판정(시스템 시각 변경 없음, 타이머 없음).
  - `grantConfigureThenDenyAtExpiry`: 부여 전 거부 → 부여 즉시 허용 → 부여 안 한 행위(DELETE)는 계속 거부 → 만료 1초 전 허용 → **만료 시각 정각부터 즉시 거부** → 만료 후 거부
  - `delegateBehaviorIsPreserved`: Grant가 하나도 없으면 delegate(Matrix)와 완전히 동일하게 동작(허용·거부 모두)
  - `jobScopeGrantIsExactMatchOnly`: JOB 범위 grant는 정확 일치만 — `team-job` grant가 `team-job2`에 새지 않음
  - `folderScopeBoundary`: FOLDER 범위 `team/batch` grant가 `team/batch/jobA`에는 적용되고 **`team/batch-other`에는 적용되지 않음**(`/` 경계 검사). CREATE는 폴더 자신의 ACL에서 판정됨을 확인(범위 폴더에서 허용, 상위 폴더에서 거부)
- 발견한 제약:
  - **위임형 전략은 모든 `getACL` 오버로드를 위임해야 한다.** PoC는 `getRootACL`/`getACL(AbstractItem)`/`getACL(Job)`만 감쌌지만, `AuthorizationStrategy`의 기본 구현은 `getACL(Computer)`/`getACL(Node)`/`getACL(View)` 등을 `getRootACL()`로 흘려보낸다. delegate가 이 오버로드들을 재정의하는 전략(Role Strategy가 대표적: `getACL(Computer)`, `getACL(Node)` 재정의)이라면, 래퍼가 이들을 위임하지 않을 경우 에이전트·뷰 권한 동작이 바뀐다. 본개발 `BatchControlAuthorizationStrategy`는 전 오버로드 위임 필수.
  - **Role Strategy 호환성 (코드 검증 아님, 소스·문서 조사)**: 결론 — *권한 판정 위임은 가능하나, Role Strategy 자체 관리 화면이 깨진다*.
    - `RoleBasedAuthorizationStrategy`는 `public class RoleBasedAuthorizationStrategy extends AuthorizationStrategy`(non-final)이고 `getRootACL()`, `getACL(Job)`, `getACL(AbstractItem)`, `getACL(Computer)`, `getACL(Node)`, `getGroups()`와 공개 생성자를 제공하므로 **delegate 인스턴스로 감싸 ACL 판정을 위임하는 것 자체는 가능**하다.
    - 그러나 `RoleBasedAuthorizationStrategy.getInstance()`는 `Jenkins.getAuthorizationStrategy() instanceof RoleBasedAuthorizationStrategy`일 때만 인스턴스를 돌려주고 아니면 null을 반환하며, 역할 관리 화면(`doAssignSubmit`, `doTemplatesSubmit`)과 `persistChanges()`도 같은 instanceof 검사를 한다. 즉 우리의 위임형 전략이 전역 전략으로 설치되면 **Role Strategy의 "Manage Roles / Assign Roles" 화면과 REST/CLI 역할 관리 API가 동작하지 않는다** (권한 판정은 계속 동작).
    - 근거: [role-strategy-plugin 소스 `RoleBasedAuthorizationStrategy.java`](https://github.com/jenkinsci/role-strategy-plugin/blob/master/src/main/java/com/michelin/cio/hudson/plugins/rolestrategy/RoleBasedAuthorizationStrategy.java), [javadoc](https://javadoc.jenkins.io/plugin/role-strategy/com/michelin/cio/hudson/plugins/rolestrategy/RoleBasedAuthorizationStrategy.html)
- 설계 수정 제안 (DECISIONS 제안 형식):
  - **제안 C-1**: ARCHITECTURE 4절의 위임 명세에 `getACL(Computer)`, `getACL(Node)`, `getACL(View)` 등 전 오버로드 위임을 명시한다. (근거: 위 제약. 누락 시 Role/Matrix의 에이전트 권한 동작 변경)
  - **제안 C-2**: Role Strategy 지원 수준을 결정해야 한다. 선택지 — (a) "권한 판정은 지원하되 역할 편집은 래핑 해제 후 수행"을 알려진 제약으로 문서화(MVP 권장), (b) role-strategy 플러그인에 delegate-인식 개선을 upstream 기여, (c) MVP는 Matrix만 공식 지원으로 명시. 사람 결정 필요.

## 가정 D — 차단 시 사용자 안내

- 결과: **조건부** (기능 검증은 전부 성공, 육안 확인만 미완)
- 검증 방법: 역할 정의서의 원계획(`mvn hpi:run` + 브라우저 스크린샷)은 브라우저 사용 불가 환경이라 **JenkinsRule WebClient HTML 단언으로 대체**했다. **Phase 5(E2E)에서 실제 브라우저 화면과 스크린샷으로 재확인해야 한다.**
  1. `blockedManualPostShowsFailureMessage`: 핸들러가 `Failure("Approval required: ...")`를 throw하면 차단된 `POST /build` 응답이 **HTTP 400**이 되고 응답 페이지 본문에 안내 메시지가 그대로 표시됨. 큐 비어 있음·빌드 없음 동시 확인.
  2. `buildNowRelabeledAndSidebarActionAdded`: `hudson.util.AlternativeUiTextProvider`로 사이드바 "Build Now" 라벨이 "Request Approval to Run"으로 대체되고("Build Now" 문자열이 페이지에서 사라짐), `TransientActionFactory<Job>`가 추가한 안내 액션("Batch Control Guidance (PoC)")의 사이드바 링크(`poc-request-run`)가 렌더링됨.
  3. `cliBlockedShowsFailureMessage`: CLI도 exit 1 + stderr에 안내 메시지가 노출됨(조용한 실패 아님).
- 발견한 제약:
  - `Failure` throw 시 UI/REST 응답은 400 + 메시지 페이지로 양호하다. 반면 CLI는 `ERROR: Unexpected exception occurred while performing build command.` 뒤 스택트레이스 안에 메시지가 보이는 형태라 **보이긴 하지만 깔끔하지 않다**.
  - `AlternativeUiTextProvider`는 `hudson.util` 패키지다(`jenkins.util` 아님). Freestyle과 Pipeline을 모두 덮으려면 `AbstractProject.BUILD_NOW_TEXT`와 `ParameterizedJobMixIn.BUILD_NOW_TEXT` 두 메시지 키를 모두 처리해야 한다.
- 설계 수정 제안 (DECISIONS 제안 형식):
  - **제안 D-1**: 큐 차단의 기본 동작을 "false 반환"이 아니라 **사용자 유래 Cause에 한해 `Failure`(안내 문구 + 요청 화면 링크) throw**로 확정한다. 가정 A에서 확인했듯 false 반환만으로는 REST 200/CLI exit 0의 완전한 조용한 실패가 되어 SPEC 6 수용 기준을 만족할 수 없다. cron(Timer)·Upstream 등 비대화형 Cause 차단(`blockTimer`/`blockUpstream`)은 throw 대신 조용히 거부하고 기록만 남기는 편이 안전한지 Phase 3에서 함께 결정 필요.

## 다음 단계 권고

1. **Phase 2 진입 가능**: A·B·C 모두 통과. 게이트 조건(하나라도 실패 시 진입 금지) 충족.
2. Phase 3 구현 시 반영할 것: (a) Cause 분류는 instanceof 기반(`BuildUpstreamCause` 등 하위 타입), (b) 차단 시 Failure throw 기본(제안 D-1), (c) 위임형 전략은 전 `getACL` 오버로드 위임(제안 C-1), (d) `build` 스텝 차단이 상위 잡 FAILURE를 유발한다는 사실을 잡 설정 도움말·README에 명시.
3. Role Strategy 지원 수준(제안 C-2)은 사람 결정 후 SPEC/ARCHITECTURE에 반영.
4. Phase 5 E2E에서 가정 D의 화면(차단 안내 페이지, Build Now 대체, 사이드바 안내 링크)을 실제 브라우저 + 스크린샷으로 재확인하고, REST/CLI 거부 메시지 품질(B·D의 스택트레이스 노출)을 UX 항목으로 점검.

## 요청

- 요청: `docs/ARCHITECTURE.md` 4절 — 위임형 전략 명세에 `getACL(Computer)`/`getACL(Node)`/`getACL(View)` 등 **전 오버로드 위임**을 추가 (제안 C-1). 사람 승인 필요.
- 요청: `docs/ARCHITECTURE.md` 2절·6절 — 실행 차단 항목의 "false 반환 + 안내"를 "사용자 유래 Cause는 `Failure` throw로 안내"로 갱신 (제안 D-1). 사람 승인 필요.
- 요청: `docs/DECISIONS.md` — 제안 C-2(Role Strategy 지원 수준: 문서화/업스트림 기여/Matrix만 공식 지원)를 결정 항목으로 등록. 사람 결정 필요.
- 요청: `pom.xml` (release-manager 소유) — Phase 3에서 `build` 스텝 관련 테스트를 작성하려면 루트 pom 테스트 의존성에 `org.jenkins-ci.plugins:pipeline-build-step` 추가 필요 (`workflow-basic-steps`에는 `build` 스텝이 없음).
- 요청: `docs/STATUS.md` (메인 세션 소유) — Phase 1 완료 기록 갱신.

---

## PoC-5 authorization mechanism (#30)

Question from D-35 and issue #30: which mechanism keeps matrix-auth and role-strategy per-item
configuration working while no grant outlives its window, with the least coupling.

- Environment: Jenkins 2.568.3, BOM `bom-2.568.x:7093.v37de7b_4a_8a_4f` (same as root pom): matrix-auth 3.3,
  role-strategy 898.vc050ed2424ca_, cloudbees-folder 6.1106.v3a_d9a_6d2465e, configuration-as-code 2121.v86fe99d4b_b_a_b_.
  Run on Java 26.0.2 (no JDK 21 on the PoC machine; nothing here is JDK-specific).
- Command: in `poc/`, `mvn test -Dtest='Poc5*Test'`: **30 tests, all pass**. Each test asserts the behaviour
  it names, including the leaks. A passing "leak" test means the leak was reproduced.
- Code: `poc/src/main/java/io/jenkins/plugins/batchcontrol/poc/auth/`, tests: `poc/src/test/java/io/jenkins/plugins/batchcontrol/poc/auth/`.
  - W = `PocWrapperStrategy`: same shape as the current `BatchControlAuthorizationStrategy` (all overloads delegate). The type checks are `instanceof`, so any non-subclass wrapper gives the same results.
  - A = `PocGrantMatrixStrategy extends ProjectMatrixAuthorizationStrategy`, `PocGrantRoleStrategy extends RoleBasedAuthorizationStrategy` (neither class is final).
  - B = `PocNativeGrants`: writes a ledger line, then the native entry (job `AuthorizationMatrixProperty` entry, or item role `bc-<id>` assigned to the user). `sweep()` removes it again, and an `@Initializer(after=JOB_CONFIG_ADAPTED, before=COMPLETED)` runs a sweep at boot.
- All `poc/src/main` code passes the `access-modifier-checker`, so no `@Restricted` API of the other plugins is used.

### Summary table

Test classes: `Poc5BaselineWrapperTest` (W), `Poc5OptionASubclassTest` (A), `Poc5OptionBNativeTest` (B), `Poc5RestartTest` (restart/crash, A and B).

| # | Scenario | W: current wrapper | A: subclass | B: native entries |
|---|---|---|---|---|
| 1 | matrix-auth job/folder/agent property configurable (descriptor `isApplicable`, section on the configure page) | **fail** `wrapper_matrix_folderJobAgentPropertiesNotConfigurable` | pass `subclass_matrix_typeChecksPassAndPropertiesEffective` | pass (plain strategy stays installed) |
| 2 | Existing per-item property effective and kept by a UI save of the job | **fail**: it takes effect, but a job UI save **drops it silently** `wrapper_matrix_existingJobPropertyEffectiveButDroppedByUiSave` | pass `subclass_matrix_uiSavesKeepPropertyAndSubclass` | pass |
| 3 | role-strategy Manage/Assign Roles link, `RoleBasedAuthorizationStrategy.getInstance()` (REST API, pipeline steps) | **fail** `wrapper_role_itemRolesEffectiveButManagementAndApisGone` | pass `subclass_role_typeChecksPassAndGrantLayered` | pass |
| 4 | role-strategy item and agent roles effective | pass (same W test) | pass | pass |
| 5 | role-strategy pattern-based root Item/Create + `RoleBasedProjectNamingStrategy` | **fail**: Create lost, name rule not enforced `wrapper_role_patternBasedCreateAndNamingStrategyBroken` | pass `subclass_role_typeChecksPassAndGrantLayered` | pass |
| 6 | Mechanism survives the other plugin's own save path | pass | matrix pass (security form uses `create()` hook). **role fail**: Manage Roles save installs plain `RoleBasedAuthorizationStrategy`, so open grants stop working (fail-safe) `subclass_role_manageRolesSaveReplacesSubclass` | role: a stale Manage Roles form ends an open window early `native_role_staleManageRolesFormDropsOpenWindow` |
| 7 | config.xml persistence / restart | pass | pass **only with our own XStream converter**. Without it, `Jenkins.save()` throws (first run). `subclass_matrix_xmlRoundTripKeepsClassAndEntries`, `subclass_role_xmlRoundTrip`, `Poc5RestartTest.subclass_matrix_restartKeepsSubclassEntriesAndItemProperty`, `subclass_role_restartKeepsSubclassAndRoles` | pass |
| 8 | JCasC export/apply | (not measured) | pass **only with our own configurator**. Without it: "Configuration-as-Code can't handle type", not exported. `subclass_matrix_cascExportAndApply`, `subclass_role_cascExportAndApply` | pass |
| 9 | Expiry precision | exact, judged at check time | exact `subclass_matrix_grantLayeredOnPerItemAclAndExpires` | **only at the next sweep**: the entry still answers after expiry `native_matrix_grantAndSweep_keepsAdminEntriesAndPreExisting` |
| 10 | Window expired while Jenkins was down | pass (nothing written into the auth config; main plugin `GrantService` judges by clock) | pass (same) | pass with the boot sweep `native_matrix_expiredWhileDown_removedBeforeCompletedInit`, `native_role_expiredWhileDown_removedAtBoot` |
| 11 | Crash between "add permission" and "record it" | n/a: one write, the grant record *is* the permission | n/a | record-first: pass `native_matrix_crashAfterRecordBeforeAdd_isHarmless`. add-first: **permanent leak** `native_matrix_crashAfterAddBeforeRecord_leaksPermanently` |
| 12 | Concurrent admin edit of the same matrix/roles | no interaction | no interaction | **matrix fail**: a configure page opened during the window and saved after the sweep **brings back the expired entry for good, and nothing records it** `native_matrix_staleAdminFormResurrectsExpiredEntry`. Opened before and saved during: window ends early `native_matrix_staleAdminFormDropsOpenWindow`. Role: one stale page alone is safe, both pages in order resurrect it `native_role_staleFormsAfterExpiryDoNotResurrect`. Rebuild-and-replace also loses a concurrent `doAssignSubmit` (source: it mutates the instance fetched at its start) |
| 13 | config.xml churn / audit clarity | none | none | 3 job saves per grant+expiry. The grant is written into the job's config.xml (job-config-history, SCM sync, Batch Control's own change records all see it as a config change) `native_matrix_configChurnIsVisible`. Role: 2 full `config.xml` rewrites |
| 14 | Grantee with a temporary Item/Configure writes itself a permanent matrix entry (POST `config.xml`) | **fail** `wrapper_matrix_granteeCanSelfGrantThroughConfigXml` | **fail** `subclass_matrix_granteeCanSelfGrantPermanently` | fail (same matrix-auth path, not tested separately) |
| 15 | matrix-auth `ItemListenerImpl` gives the creator permanent Read/Configure on a job created in a Create-only window | not triggered (its `instanceof` fails) | **fail** `subclass_matrix_creatorAutoGrantOutlivesCreateWindow` | fail (plain strategy, same listener, not tested separately) |
| 16 | Migration from an existing matrix-auth configuration | n/a | one call, reversible, per-item properties untouched `subclass_matrix_migrationFromExistingMatrixIsOneCall` | none needed |

### Findings

- **Baseline (question 0):** besides what #30 reports, a UI save of a job **deletes its existing
  `AuthorizationMatrixProperty`** (`DescribableList.rebuild` keeps only applicable descriptors).
  role-strategy has 18 `getAuthorizationStrategy() instanceof RoleBasedAuthorizationStrategy` call sites.
  Behind the wrapper they turn off: the management link and page (`RoleStrategyConfig`, `RoleStrategyRootAction`),
  the REST API and pipeline steps (`getInstance()`, `AbstractUserRolesStep`, `UserItemRoles`), root Item/Create through item roles
  (`RoleMap.AclImpl`), `RoleBasedProjectNamingStrategy.checkName` (now allows any name), `NamingStrategyAdministrativeMonitor`, `PermissionTemplate`, and `validateConfig` at boot.
  matrix-auth has 9 such sites: the shared property descriptor check (job, folder and node), the three creator listeners (job, folder, node), `AmbiguityMonitor` (3), `PermissionAdderImpl` and `FolderContributor`.
- **A, matrix-auth:** every type check passes. What the subclass needs:
  - A named `DescriptorImpl extends GlobalMatrixAuthorizationStrategy.DescriptorImpl` that overrides `create()`, so the security form keeps the subclass.
  - A nested `ConverterImpl`. matrix-auth's converter is `@Restricted` and checks the exact class, so the PoC re-implements the `<permission>TYPE:id:sid</permission>` format in about 30 lines of public API.
  - A JCasC `Configurator` under a new symbol (`batchControlProjectMatrix`). It looks matrix-auth's configurator up **by type at runtime** and converts to and from a plain instance, so it has no compile-time reference to restricted code.
- **A, role-strategy:** every type check passes. The public `RoleBasedAuthorizationStrategy.ConverterImpl` can be subclassed, with a copy on unmarshal. There is one hard limit: `DescriptorImpl.newInstance`, run by the Manage Roles save, always builds `new RoleBasedAuthorizationStrategy()`. It fails safe (no permission leaks, grants just stop working), but it is silent.
- **B** fails criterion 2 on its own evidence. An administrator's ordinary stale form brings back an expired permission for good, and the ledger no longer knows about it. Safety also depends on exact write ordering and on sweep frequency. It churns the item's history, and role-strategy can only be changed by rebuilding and replacing the whole strategy.

### Recommendation

**Option A (subclasses).** It is the only option that passes criterion 1 (per-item configuration
configurable and effective) and criterion 2 (no grant outlives its window: nothing grant-related
is written into another plugin's data) together. It uses only public API. Support levels:
matrix-auth fully supported. role-strategy supported with one documented limitation (row 6), plus an
administrative monitor that detects "change control on, but the installed strategy is the plain class" and offers re-installing the subclass.

Dependencies of the recommended option on other plugins (all public, checked by the access-modifier-checker):
- matrix-auth: `ProjectMatrixAuthorizationStrategy` (subclassed; `getACL(Job)`, `getACL(AbstractItem)`),
  `GlobalMatrixAuthorizationStrategy.DescriptorImpl` (`create()` hook), `GlobalMatrixAuthorizationStrategy#getGrantedPermissionEntries`/`add(Permission, PermissionEntry)`,
  `org.jenkinsci.plugins.matrixauth.PermissionEntry`, `AuthorizationType#toPrefix`/`valueOf`, the on-disk `<permission>` line format, and at runtime the JCasC configurator registered for `ProjectMatrixAuthorizationStrategy`.
- role-strategy: `RoleBasedAuthorizationStrategy` (subclassed; `getACL(AbstractItem)`, constructor `(Map<String, RoleMap>, Set<PermissionTemplate>)`, `GLOBAL`/`PROJECT`/`SLAVE`, `getGrantedRolesEntries(RoleType)`, `getPermissionTemplates()`),
  `RoleBasedAuthorizationStrategy.ConverterImpl` (subclassed), `RoleMap(SortedMap)`, `RoleType`, and at runtime the JCasC configurator registered for `RoleBasedAuthorizationStrategy`.
- JCasC (optional dependency): `io.jenkins.plugins.casc.Configurator`, `ConfigurationContext#lookupOrFail`.

**Migration path:** `PocGrantMatrixStrategy.from(existing)` copies the global matrix. Per-item
properties live on the items and need nothing. Copying back to a plain `ProjectMatrixAuthorizationStrategy` is the uninstall path.
In the product this becomes a `@RequirePOST` + Overall/Administer "Enable Batch Control on the current matrix / roles" action, and the same copy from `BatchControlAuthorizationStrategy#getDelegate()` for existing installs.

### Design change proposals (DECISIONS proposal format)

- **Proposal D-35a (amendment of D-35)** | The authorization mechanism is a Batch Control subclass per supported strategy (`ProjectMatrixAuthorizationStrategy`, `RoleBasedAuthorizationStrategy`), each with its own descriptor, XStream converter and JCasC configurator. The generic wrapper is withdrawn. Matrix-auth is fully supported. For role-strategy, a Manage Roles save reinstalls the plain class; this is detected by an administrative monitor and documented in LIMITATIONS | (B) native entries: rejected, a stale admin form resurrects an expired permission unrecorded (PoC-5 row 12), crash ordering is fragile (row 11), expiry is only at sweep time (row 9). (C) keep the wrapper: rejected by D-35 itself.
- **Proposal D-35b** | Open finding, not caused by the choice (the current wrapper has it too): matrix-auth lets anyone with Item/Configure edit that item's authorization property. A Configure grant can therefore be turned into a permanent entry (row 14). Proposed: Batch Control compares the item's `AuthorizationMatrixProperty` before and after each save by a grant holder who has no native Configure. On a change it restores the previous property and records a violation in the audit | Rejected: excluding Configure from grants (it is the main use case).
- **Proposal D-35c** | Under A, matrix-auth's creator listener gives permanent Read/Configure on items created in a Create-only window (row 15). Proposed: a Create grant also covers Configure on items created inside its scope for the window, so the listener finds the creator already holds the permission and adds nothing. Alternatively, Batch Control removes the entries the listener added when the window closes | Leaving it: rejected, it breaks criterion 2.

### Recommended next steps

1. Owner decision on D-35a..c. Then core-dev replaces `BatchControlAuthorizationStrategy` with the two subclasses and the migration action, and test-author ports rows 1–8, 14 and 15 into `src/test`.
2. Consider an upstream PR to role-strategy so that `DescriptorImpl.newInstance` keeps `oldStrategy`'s class (for example a protected factory method). That would close row 6.
3. Re-run this PoC on JDK 21 in CI (the PoC machine had only JDK 17 and 26).

### Requests

- Request: `docs/DECISIONS.md` (humans), record the D-35 amendment and D-35b/c above.
- Request: `docs/ARCHITECTURE.md` section 4 (humans), replace the delegating-wrapper design with the subclass design (descriptor + converter + JCasC configurator per strategy, and the migration action).
- Request: `docs/LIMITATIONS.md` (release-manager), add the role-strategy Manage Roles limitation (row 6) and, until D-35b is implemented, the self-grant limitation (row 14).
- Request: `pom.xml` (release-manager), role-strategy and configuration-as-code become optional (non-test) dependencies and matrix-auth a regular one, if D-35a is accepted.
- Note: `mvn verify` in `poc/` fails SpotBugs on two **pre-existing** Phase 1 fields (`PocDeleteVetoListener.vetoEnabled`, `PocQueueDecisionHandler.throwFailure`, `PA_PUBLIC_PRIMITIVE_ATTRIBUTE`). They were not changed here. The PoC-5 code is clean.
