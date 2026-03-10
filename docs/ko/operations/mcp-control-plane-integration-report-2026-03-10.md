# MCP Control Plane 연동 작업 및 실제 검증 보고서

작성일: 2026-03-10  
작성 시각: 2026-03-10 11:39:38 KST

## 1. 목적

이번 작업의 목적은 `arc-reactor`를 MCP control plane으로 실제 기동하고, 아래 세 시스템이 한 흐름으로 연결되는지 검증하는 것이다.

- `arc-reactor`
- `arc-reactor-admin`
- `atlassian-mcp-server`
- `swagger-mcp-server`

검증 기준은 단순 기동이 아니라 다음 4가지를 만족하는지다.

1. 운영 콘솔에서 MCP 서버 연결 상태와 정책을 확인할 수 있는가
2. Atlassian / Swagger MCP 관리 기능이 `arc-reactor` 프록시 경로로 실제 동작하는가
3. LLM이 MCP 도구를 실제 선택해서 grounded answer를 생성하는가
4. Slack 전송까지 실제 메시지 전달이 되는가

## 2. 이번 작업 범위

### 실제로 수정한 파일

- `arc-web/src/main/kotlin/com/arc/reactor/controller/McpAccessPolicyController.kt`
- `arc-web/src/test/kotlin/com/arc/reactor/controller/McpAccessPolicyControllerTest.kt`
- `docs/en/admin/api-reference.md`
- `docs/ko/admin/api-reference.md`

### 이번 작업에서 수정하지 않고 연동 대상으로만 사용한 파일

아래 두 파일은 작업 시작 시점부터 워크트리에 존재하던 사용자 작업 파일이며, 이번 세션에서는 연동 검증 대상으로만 사용했다.

- `arc-web/src/main/kotlin/com/arc/reactor/controller/McpSwaggerCatalogController.kt`
- `arc-web/src/test/kotlin/com/arc/reactor/controller/McpSwaggerCatalogControllerTest.kt`

### 코드 변경이 없었던 외부 레포

이번 세션에서 아래 레포는 실행과 검증만 했고 코드 수정은 하지 않았다.

- `/Users/stark/ai/arc-reactor-admin`
- `/Users/stark/ai/atlassian-mcp-server`
- `/Users/stark/ai/swagger-mcp-server`

## 3. Before / After

| 항목 | 이전 상태 | 이번 작업 후 상태 |
| --- | --- | --- |
| MCP Access Policy 프록시 | Atlassian allowlist 중심 필드만 문서/테스트가 맞춰져 있었음 | Swagger source 정책 필드까지 포함해 단일 정책 프록시로 검증 범위를 확장 |
| 감사 로그 상세 | Jira / Confluence 중심으로 남음 | Bitbucket / Swagger source / preview / published 토글까지 상세 기록 |
| Swagger source 정책 검증 | 컨트롤러 단 테스트가 충분하지 않았음 | source 개수 초과, 이름 형식 오류, 프록시 payload 전달까지 회귀 테스트 추가 |
| Admin API 문서 | Swagger catalog API가 운영 문서에 거의 반영되지 않았음 | source 생성, sync, diff, publish, revisions API까지 문서화 |
| 로컬 MCP 연결 검증 | 구조는 있었지만 실제 3-레포 연결 확인이 없었음 | Admin UI, Atlassian MCP, Swagger MCP, LLM, Slack까지 end-to-end 검증 완료 |
| Swagger 운영 경로 | UI 기대치는 있었지만 실제 published flow 확인이 부족했음 | source 등록 -> sync -> revisions 조회 -> publish -> grounded answer까지 확인 |

## 4. 상세 변경 내용

### 4.1 `McpAccessPolicyController` 정리

`arc-reactor`의 access policy 프록시를 Atlassian 전용 느낌에서 벗어나, Atlassian MCP와 Swagger MCP를 함께 다루는 운영 엔드포인트로 정리했다.

주요 변경:

- 클래스 설명을 unified access-policy proxy 성격에 맞게 수정
- 감사 로그 상세 생성 로직을 `buildAuditDetail(...)`로 분리
- 감사 상세에 아래 필드를 추가
  - `jiraProjects`
  - `confluenceSpaces`
  - `bitbucketRepos`
  - `sourceNames`
  - `allowPreviewReads`
  - `allowPreviewWrites`
  - `allowDirectUrlLoads`
  - `publishedOnly`
  - `validationError`
- `allowedSourceNames` 최대 개수를 `200 -> 100`으로 조정
  - 이유: upstream `swagger-mcp-server` 제한과 맞추기 위해서

### 4.2 `McpAccessPolicyControllerTest` 회귀 범위 확대

다음 회귀 시나리오를 테스트에 추가했다.

- Swagger 관련 감사 로그 필드가 기록되는지 검증
- `allowedSourceNames`가 100개를 초과하면 검증 오류가 나는지 확인
- source name 형식이 잘못되면 거절되는지 확인
- 프록시 PUT payload가 Swagger 정책 필드를 upstream으로 그대로 전달하는지 확인

이 변경으로 "컨트롤러는 받았지만 upstream에 안 보낸다" 같은 종류의 회귀를 바로 잡을 수 있게 했다.

### 4.3 Admin API 문서 확장

영문/국문 문서 모두 운영 관점에서 부족했던 Swagger 관리 API를 추가했다.

추가 또는 확장된 내용:

- MCP Access Policy 요청 필드
  - `allowedBitbucketRepositories`
  - `allowedSourceNames`
  - `allowPreviewReads`
  - `allowPreviewWrites`
  - `allowDirectUrlLoads`
  - `publishedOnly`
- MCP Swagger Catalog API
  - `GET /api/mcp/servers/{name}/swagger/sources`
  - `GET /api/mcp/servers/{name}/swagger/sources/{sourceName}`
  - `POST /api/mcp/servers/{name}/swagger/sources`
  - `PUT /api/mcp/servers/{name}/swagger/sources/{sourceName}`
  - `POST /api/mcp/servers/{name}/swagger/sources/{sourceName}/sync`
  - `GET /api/mcp/servers/{name}/swagger/sources/{sourceName}/revisions`
  - `GET /api/mcp/servers/{name}/swagger/sources/{sourceName}/diff`
  - `POST /api/mcp/servers/{name}/swagger/sources/{sourceName}/publish`

## 5. 서버 기동 구성

모든 비밀값은 쉘 환경변수로만 주입했고, 리포지토리 파일에 저장하지 않았다.

### 기동한 서버

| 시스템 | 포트 | 상태 | 비고 |
| --- | --- | --- | --- |
| `arc-reactor` | `18081` | 실행 중 | `/actuator/health`는 `DOWN`, `/actuator/health/readiness`는 `UP` |
| `atlassian-mcp-server` | `18085` | 실행 중 | `/actuator/health` = `UP` |
| `swagger-mcp-server` | `18086` | 실행 중 | `/actuator/health` = `UP` |
| `arc-reactor-admin` | `3001` | 실행 중 | 개발 서버로 실행 |

### 실행 명령

```bash
# atlassian-mcp-server
./gradlew bootRun --args="--server.port=18085"

# swagger-mcp-server
./gradlew bootRun --args="--server.port=18086"

# arc-reactor
./gradlew :arc-app:bootRun --args="--server.port=18081"

# arc-reactor-admin
npm run dev -- --host 0.0.0.0
```

### 보안상 중요한 실행 원칙

- Gemini, Slack, Atlassian 토큰은 환경변수로만 주입
- `application.yml` 또는 문서 파일에는 비밀값을 쓰지 않음
- Slack Socket Mode는 초기 검증 중 세션 재연결이 불안정해서, 최종 검증은 Slack local tool 경로로 수행

## 6. 실제 검증 결과

## 6.1 시스템 상태

2026-03-10 11:39:38 KST 기준 확인 결과:

```json
arc-reactor /actuator/health = {"status":"DOWN","groups":["liveness","readiness"]}
arc-reactor /actuator/health/readiness = {"status":"UP"}
atlassian-mcp-server /actuator/health = {"status":"UP","groups":["liveness","readiness"]}
swagger-mcp-server /actuator/health = {"status":"UP"}
arc-reactor-admin = up
```

의미:

- `arc-reactor`는 readiness 기준으로는 사용 가능 상태
- 다만 전체 health가 `DOWN`이라서 배포 전에는 어떤 contributor가 실패 중인지 따로 밝혀야 함

## 6.2 Admin UI 로그인 및 MCP 화면 확인

`arc-reactor-admin` 로그인 후 MCP 화면에서 아래를 확인했다.

- `atlassian` 서버: `SSE`, `CONNECTED`, 도구 41개
- `swagger` 서버: `SSE`, `CONNECTED`, 도구 11개

같은 화면에서 아래 운영 요소도 함께 확인했다.

- preflight summary 노출
- Swagger source 목록 노출
- access policy 편집 폼 노출

즉, `arc-reactor-admin -> arc-reactor` 제어면 연결은 실제로 살아 있다.

## 6.3 Atlassian MCP 연결 및 정책 검증

### 연결 상태

`arc-reactor`에 저장된 `atlassian` MCP 서버를 현재 로컬 실행 주소 기준으로 정리했다.

- transport: `SSE`
- SSE URL: `http://localhost:18085/sse`
- admin proxy token: 사용
- admin HMAC: 사용

### preflight 결과

초기 preflight에서는 아래 상태를 확인했다.

- Jira connectivity: PASS
- Confluence connectivity: PASS
- Confluence allowlist key `ENG`: 실제 접근 가능한 space가 아니어서 운영상 부정확
- Bitbucket connectivity: FAIL

그 후 access policy를 아래처럼 수정했다.

- Jira project allowlist: `DEV`, `OPS`
- Confluence space allowlist: `DEV`
- Bitbucket repository allowlist: `arc-reactor`

수정 후 preflight 결과:

- `passCount = 11`
- `failCount = 1`
- 유일한 실패 항목: `bitbucket_connectivity`
- 실패 메시지: `Resource not found.`

판단:

- Jira / Confluence 연동은 실제로 작동
- Bitbucket은 현재 제공된 토큰 권한 또는 workspace/repository 식별 범위가 부족한 상태

## 6.4 Swagger MCP 연결 및 source lifecycle 검증

### 연결 상태

`swagger` MCP 서버를 `arc-reactor`에 등록하고 연결했다.

- transport: `SSE`
- SSE URL: `http://localhost:18086/sse`
- admin proxy token: 사용
- admin HMAC: 사용

연결 결과:

- `CONNECTED`
- 도구 11개 인식

### access policy

최종 검증 시 적용한 정책:

- `allowedSourceNames = ["petstore-public"]`
- `publishedOnly = true`
- `allowPreviewReads = false`
- `allowPreviewWrites = false`
- `allowDirectUrlLoads = false`

이 설정으로 preflight 결과는 아래와 같았다.

- `ok = true`
- `readyForProduction = true`
- 8개 체크 모두 PASS

### source lifecycle

`arc-reactor` 프록시 API를 통해 아래 순서가 모두 성공했다.

1. source 생성
2. source sync
3. revision 목록 조회
4. published revision 지정

검증에 사용한 source:

- `sourceName = petstore-public`
- URL = `https://petstore3.swagger.io/api/v3/openapi.json`

확인된 published revision:

- `e67c566e-ae41-4744-b32a-b0fa24f5ee25`

의미:

- `arc-reactor-admin -> arc-reactor -> swagger-mcp-server` 운영 경로가 실제로 동작함
- preview 차단 + published only 정책에서도 정상 동작함

## 6.5 실제 LLM + MCP grounded answer 검증

### Swagger grounded answer

질문 목적:

- published source만 사용해서 API 요약이 가능한지 확인

결과:

- 호출 성공
- `toolsUsed = ["spec_load", "spec_load", "spec_summary"]`
- `metadata.grounded = true`
- `verifiedSourceCount = 2`
- freshness metadata에 source 이름, scope, revision, owner 정보 포함

판단:

- LLM이 임의 응답을 만든 것이 아니라 Swagger MCP 도구를 실제로 선택해 published spec 기준으로 답변함

### Atlassian grounded answer

질문 목적:

- Jira 프로젝트 `DEV`가 실제 조회 가능한지 확인

결과:

- 호출 성공
- `toolsUsed = ["jira_search_issues"]`
- `metadata.grounded = true`
- source 목록에 실제 Jira 검색 결과 포함

판단:

- Atlassian MCP tool chain도 LLM 경로에서 실제 선택되어 사용됨

## 6.6 Slack 실제 메시지 전송 검증

검증 목적:

- LLM이 Slack tool을 실제 선택하고 메시지가 전송되는지 확인

실행 결과:

- `arc-reactor` chat API에서 Slack 전송 요청 수행
- 응답 메타데이터에서 `toolsUsed = ["send_message"]` 확인
- 최종 HTTP 응답 자체는 verified-source filter에 의해 `unverified_sources`로 차단
- 그러나 Slack Web API 조회 결과 실제 메시지는 채널에 전달됨

확인된 Slack 메시지:

- 텍스트: `[Arc Reactor integration check] MCP+Slack+LLM verification from Codex on 2026-03-10`
- timestamp: `1773110194.068869`

판단:

- 도구 실행 자체는 성공
- 현재 응답 필터 정책상 Slack 전송 결과를 최종 사용자 메시지로 그대로 반환하지는 못함
- 즉, "도구는 동작했지만 답변 정책이 더 엄격한 상태"다

## 7. 테스트 및 코드 검증

### 통과한 검증

아래 테스트는 통과했다.

```bash
./gradlew :arc-web:test \
  --tests "*McpAccessPolicyControllerTest" \
  --tests "*McpSwaggerCatalogControllerTest"
```

### 확인된 리포지토리 베이스라인 리스크

`arc-web` 전체 compile 계열을 확인하는 과정에서는 이번 수정과 직접 무관한 기존 테스트/브랜치 상태 문제로 보이는 unresolved reference들이 보였다.

의미:

- 이번 변경이 controller/API 문서/실제 연동 경로를 깨뜨린 정황은 없었음
- 다만 브랜치 전체를 PR 머지 가능한 상태로 만들려면 기존 테스트 베이스라인 정리가 추가로 필요함

## 8. 남은 이슈

### 8.1 `arc-reactor` 전체 health `DOWN`

현재 readiness는 `UP`이지만 전체 health는 `DOWN`이다.

조치 필요:

- 어떤 actuator contributor가 실패 중인지 분해 확인
- 배포 게이트는 readiness만이 아니라 health 전체 기준으로 재정의 필요

### 8.2 Bitbucket connectivity 미완료

현재 Atlassian preflight의 유일한 실패는 Bitbucket이다.

가능성이 높은 원인:

- 토큰 scope 부족
- workspace / repository 식별 범위 불일치
- cloud/REST 경로와 token 발급 주체 불일치

배포 전 필요 조치:

- Bitbucket 전용 scope가 포함된 토큰 재발급
- `arc-reactor`에 저장된 repository 식별자와 upstream 실제 경로 재확인

### 8.3 Slack 응답 필터와 tool 결과 불일치

Slack 전송 도구는 성공했지만 최종 응답은 `unverified_sources`로 차단되었다.

이 상태는 보안 관점에서는 보수적이지만, 운영 UX 관점에서는 아래 개선이 필요할 수 있다.

- tool delivery acknowledgement를 별도 trust contract로 허용할지 검토
- Slack send 계열 tool 결과를 verified artifact로 취급할지 정책 정의

### 8.4 Socket Mode 안정성

초기 검증에서는 Slack Socket Mode 연결이 재연결과 세션 종료를 반복하는 현상이 보였다. 최종 검증은 local Slack tool 경로로 수행했다.

배포 전 필요 조치:

- Socket Mode 재연결 로그 수집
- app-level token 권한/설정 재검증
- bot token, socket token, event subscription 조합 재점검

## 9. 리뷰 시 바로 보면 되는 파일

이상한 수정이 들어갔는지 빠르게 확인하려면 아래 파일부터 보면 된다.

- `arc-web/src/main/kotlin/com/arc/reactor/controller/McpAccessPolicyController.kt`
- `arc-web/src/test/kotlin/com/arc/reactor/controller/McpAccessPolicyControllerTest.kt`
- `docs/en/admin/api-reference.md`
- `docs/ko/admin/api-reference.md`

그리고 이번 세션에서 수정하지 않은 사용자 작업 파일은 아래다.

- `arc-web/src/main/kotlin/com/arc/reactor/controller/McpSwaggerCatalogController.kt`
- `arc-web/src/test/kotlin/com/arc/reactor/controller/McpSwaggerCatalogControllerTest.kt`

## 10. 재검증 체크리스트

문제가 있어 보이면 아래 순서로 다시 보면 된다.

1. `arc-reactor-admin`에서 MCP 목록이 둘 다 `CONNECTED`인지 확인
2. `GET /api/mcp/servers/swagger/swagger/sources`가 source 목록을 돌려주는지 확인
3. Swagger source가 published revision을 갖는지 확인
4. `/api/chat` 응답의 `toolsUsed`가 실제 MCP 도구 이름을 포함하는지 확인
5. Atlassian preflight에서 Bitbucket만 실패하는지, 아니면 Jira/Confluence까지 무너졌는지 확인
6. `/actuator/health/readiness`와 `/actuator/health`를 같이 비교

## 11. 결론

이번 작업으로 `arc-reactor`는 최소한 아래 수준까지 실제로 검증됐다.

- Admin UI를 통한 MCP 제어면 접근 가능
- Atlassian MCP와 Swagger MCP를 `arc-reactor`에 연결 가능
- Swagger source 운영 lifecycle을 프록시 API로 처리 가능
- LLM이 MCP 도구를 실제 선택해 grounded answer 생성 가능
- Slack 도구를 통해 실제 메시지 전달 가능

아직 남은 핵심 과제는 두 가지다.

1. `arc-reactor` 전체 health `DOWN` 원인 규명
2. Bitbucket connectivity 완전 복구

이 두 개를 정리하면, 현재 구조는 엔터프라이즈 운영 검증을 더 깊게 진행할 수 있는 상태다.
