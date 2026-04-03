# Arc Reactor MCP 통합 감사 문서

> 마지막 업데이트: 2026-04-03
> 기준: 현재 저장소 코드 경로 점검
> 범위: Arc Reactor가 MCP 서버를 등록, 연결, 보호, 프록시하는 방식

---

## 1. 한 줄 요약

Arc Reactor의 MCP 기능은 “외부 MCP 서버를 런타임에 등록하고, 그 서버의 도구를 Arc Reactor 에이전트가 자기 도구처럼 쓰게 만드는 기능”이다.

중요한 점은 세 가지다.

- 단순 설정 파일 하드코딩이 아니라 REST API 기반 런타임 등록이 중심이다.
- 현재 실질적으로 지원하는 전송은 `SSE`와 `STDIO`다. `HTTP` transport는 등록 단계에서 거부된다.
- Arc Reactor는 upstream MCP admin 기능을 일부 “직접 구현”하는 게 아니라, `access-policy`, `preflight`, `swagger catalog`는 upstream admin API를 프록시하는 구조다.

---

## 2. 어디에 쓰이는가

사용자 입장에서 MCP는 별도 화면 기능이라기보다 “에이전트가 쓸 수 있는 도구 목록이 늘어나는 방식”으로 체감된다.

- 예:
  - Jira/Confluence/Bitbucket/Swagger 같은 외부 MCP 서버를 연결
  - Arc Reactor가 그 서버의 도구를 ReAct 루프에서 사용
  - 관리자는 Arc Reactor admin API로 서버를 등록/연결/보안 제어

즉, MCP는 채팅 기능의 대체가 아니라 “채팅 엔진의 외부 도구 확장 계층”이다.

---

## 3. 지원 전송 방식

| 전송 | 현재 상태 | 의미 | 주의점 |
|------|-----------|------|--------|
| `STDIO` | 지원 | 로컬 프로세스를 띄워 MCP 서버와 표준입출력으로 통신 | 허용된 명령어 화이트리스트 적용 |
| `SSE` | 지원 | 원격 MCP 서버와 SSE transport로 연결 | private/reserved address 차단 가능 |
| `HTTP` | 미지원 | streamable HTTP 류 transport | 등록 시 바로 거부 |

근거:

- [McpServerController.kt](/Users/jinan/ai/arc-reactor/arc-web/src/main/kotlin/com/arc/reactor/controller/McpServerController.kt)
- [McpModels.kt](/Users/jinan/ai/arc-reactor/arc-core/src/main/kotlin/com/arc/reactor/mcp/model/McpModels.kt)
- [McpConnectionSupport.kt](/Users/jinan/ai/arc-reactor/arc-core/src/main/kotlin/com/arc/reactor/mcp/McpConnectionSupport.kt)

---

## 4. 관리자가 할 수 있는 일

### 4.1 서버 수명주기 관리

| 기능 | API | 실제 동작 |
|------|-----|-----------|
| 서버 목록 조회 | `GET /api/mcp/servers` | 등록된 서버와 상태 목록 조회 |
| 서버 등록 | `POST /api/mcp/servers` | 서버 저장, allowlist 검사, 선택적 auto-connect |
| 서버 상세 조회 | `GET /api/mcp/servers/{name}` | 설정, 상태, 로딩된 도구 목록 확인 |
| 서버 수정 | `PUT /api/mcp/servers/{name}` | 설정 변경 후 필요 시 재연결 |
| 서버 삭제 | `DELETE /api/mcp/servers/{name}` | 연결 해제 후 스토어에서 제거 |
| 수동 연결 | `POST /api/mcp/servers/{name}/connect` | 즉시 연결 시도 |
| 수동 해제 | `POST /api/mcp/servers/{name}/disconnect` | 연결 해제 |

중요한 제약:

- 쓰기 작업은 사실상 admin 권한이 필요하다.
- 등록은 성공해도 security allowlist에 막히면 runtime에 안 올라갈 수 있다.
- `autoConnect=true`여도 연결 자체가 실패할 수 있으며, 그 경우 등록은 남고 상태는 실패할 수 있다.

---

## 5. 에이전트 쪽에서 실제로 어떻게 쓰이나

### 5.1 런타임 흐름

1. 관리자가 MCP 서버를 등록한다.
2. `McpManager`가 runtime registry에 서버를 올린다.
3. 연결되면 해당 서버의 도구 목록을 읽어 `ToolCallback` 형태로 캐시한다.
4. Arc Reactor 에이전트는 로컬 도구와 MCP 도구를 함께 후보 도구로 본다.
5. ReAct 루프에서 MCP 도구를 호출하면 실제 원격/로컬 MCP 서버로 호출이 나간다.

추가로 알아둘 점:

- 서로 다른 MCP 서버가 같은 도구 이름을 내놓으면 모두 노출되는 것이 아니라, 정해진 우선순위에 따라 하나만 살아남고 나머지는 숨겨질 수 있다.
- MCP 도구 호출 실패는 프레임워크 밖으로 예외를 그대로 던지기보다 `"Error: ..."` 형태의 도구 결과로 변환되는 경향이 있다.

근거:

- [McpManager.kt](/Users/jinan/ai/arc-reactor/arc-core/src/main/kotlin/com/arc/reactor/mcp/McpManager.kt)
- [McpToolCallback.kt](/Users/jinan/ai/arc-reactor/arc-core/src/main/kotlin/com/arc/reactor/mcp/McpToolCallback.kt)
- [ArcReactorHookAndMcpConfiguration.kt](/Users/jinan/ai/arc-reactor/arc-core/src/main/kotlin/com/arc/reactor/autoconfigure/ArcReactorHookAndMcpConfiguration.kt)

### 5.2 시작 시 복원

저장소에 서버 정의가 남아 있으면 시작 시 다시 로드할 수 있다.

- `initializeFromStore()`
- `autoConnect=true`인 서버는 시작 시 연결 시도
- 단, security allowlist에 막히면 로드는 건너뛴다

즉, “등록 정보는 남아 있는데 이번 기동에서는 정책상 막혀서 안 붙는” 상태가 가능하다.

---

## 6. 보안과 정책

### 6.1 Arc Reactor 내부 보안 정책

| 정책 | 의미 | 적용 위치 |
|------|------|-----------|
| 허용 서버 이름 allowlist | 특정 이름의 서버만 runtime에 허용 | `McpSecurityPolicy`, `McpManager` |
| 최대 도구 출력 길이 | MCP 도구 출력 잘라내기 상한 | `McpConnectionSupport` |
| STDIO 허용 명령어 | 로컬 MCP 서버 실행 파일 화이트리스트 | `McpSecurityConfig.allowedStdioCommands` |
| private address 차단 | 내부/메타데이터 주소 차단 | SSE 연결 시 |

세부 동작:

- allowlist가 비어 있으면 모두 허용
- 동적 보안 정책은 `/api/mcp/security`로 수정 가능
- 정책 수정 후 `McpManager.reapplySecurityPolicy()`가 호출된다

### 6.2 STDIO 보안

STDIO는 “아무 로컬 명령이나 실행”이 아니라 제한이 있다.

- 명령어 화이트리스트
- 경로 포함 명령어 거부
- `..` 경로 순회 패턴 거부
- 제어 문자 검증

즉, `npx`, `node`, `python`, `uvx`, `docker` 같은 허용 집합 기반이다.

### 6.3 SSE 보안

SSE는 URL 검증과 SSRF 방어가 붙는다.

- 잘못된 스킴/URL 거부
- private/reserved address 차단 가능
- 클라우드 메타데이터 주소 차단

운영상 `allowPrivateAddresses=true`를 무심코 켜면 내부망 접근 리스크가 커진다.

---

## 7. Arc Reactor가 직접 제공하는 MCP admin 표면

### 7.1 보안 정책 관리

`/api/mcp/security`

- 현재 effective policy 조회
- 저장된 policy 조회
- config default 조회
- policy 수정
- 저장된 policy 삭제 후 config default로 복원

이건 Arc Reactor 자신의 MCP 보안 정책이다.

### 7.2 upstream access policy 프록시

`/api/mcp/servers/{name}/access-policy`

이건 Arc Reactor 내부 policy가 아니라, 등록된 MCP 서버의 admin API가 제공하는 `access-policy` 엔드포인트를 대신 호출해 주는 프록시다.

필요 조건:

- `config.adminUrl` 또는 admin URL 계산 가능
- `config.adminToken`
- 필요한 경우 HMAC secret

즉, upstream이 이 admin contract를 구현하지 않으면 Arc Reactor가 access policy 기능을 대신 구현해 주는 것은 아니다.

### 7.3 upstream preflight 프록시

`/api/mcp/servers/{name}/preflight`

- upstream admin API의 readiness/preflight를 대신 호출
- 결과를 그대로 전달
- 일부 요약을 admin audit detail에 남김

이것도 “Arc Reactor 내부 health”가 아니라 “upstream 준비 상태 점검 프록시”다.

### 7.4 Swagger catalog 프록시

`/api/mcp/servers/{name}/swagger/sources*`

지원되는 lifecycle:

- source list / get
- create / update
- sync trigger
- revisions list
- diff
- publish

역시 upstream admin API가 이 contract를 구현해야 실제 의미가 있다.

---

## 8. 연결 안정성

### 8.1 재연결

`McpReconnectionCoordinator`가 실패 서버에 대해 백그라운드 재연결을 건다.

- exponential backoff
- jitter
- max attempts
- 같은 서버에 중복 job 방지

즉, 실패했다고 끝이 아니라 자동 복구를 시도한다.

### 8.2 health pinger

`arc.reactor.mcp.health.enabled=true`면 `McpHealthPinger`가 돈다.

- CONNECTED 서버를 주기적으로 검사
- 상태는 CONNECTED인데 tool callbacks가 비어 있으면 끊긴 것으로 보고 재연결 시도

이건 “조용히 죽은 MCP 연결”을 잡기 위한 운영 보조 기능이다.

### 8.3 tool availability precheck

`McpToolAvailabilityChecker`는 requested tool names를 검사해서

- available
- degraded
- unavailable

로 나눈다.

목적은 죽은 MCP 도구를 LLM이 계속 호출하려는 고스트 루프를 줄이는 것이다.

단, 이 health/availability 계층은 “진짜 upstream end-to-end 성공 보장”과는 다르다.

- health pinger는 admin API readiness를 검사하지 않는다.
- Actuator health는 cached status 중심이다.
- 실제 도구 호출 성공은 별도다.

---

## 9. 저장과 복원

| 데이터 | 저장소 | 설명 |
|--------|--------|------|
| MCP 서버 정의 | `McpServerStore` | 등록된 서버 정보 |
| MCP 보안 정책 | `McpSecurityPolicyStore` | allowlist, output length 등 |

둘 다 in-memory 또는 JDBC 구현이 가능하다.

즉, 운영 환경에서 재기동 복원과 다중 인스턴스 일관성이 중요하면 JDBC 계열 구성이 더 적합하다.

---

## 10. 중요한 운영 포인트

- MCP는 기본적으로 “REST API 등록”이 기준이다. `application.yml` 하드코딩 중심 기능이 아니다.
- `HTTP` transport는 현재 지원 기능처럼 보이면 안 된다. 등록 단계에서 거부된다.
- allowlist가 비어 있으면 전부 허용이므로, 운영 환경에서는 명시적 allowlist가 더 안전하다.
- `access-policy`, `preflight`, `swagger catalog`는 Arc Reactor 고유 기능이 아니라 upstream admin API 프록시다.
- admin proxy URL 검증은 SSE runtime URL 검증과 동일하지 않다. 특히 `adminUrl`은 등록/수정 경로에서 더 보수적으로 검증되지 않을 수 있어 운영자가 별도 검증해야 한다.
- 동적 MCP security policy는 `allowedServerNames`와 `maxToolOutputLength` 중심이다. `allowedStdioCommands`는 동일 수준의 REST/JDBC 동적 관리 범위에 완전히 포함되지 않는다.
- `adminUrl`을 명시하지 않으면 SSE URL에서 `/sse`를 제거한 값을 admin base URL로 추정한다. upstream admin API가 다른 위치에 있으면 프록시 호출은 실패한다.
- STDIO는 편하지만 로컬 프로세스를 띄우므로 운영 환경에서는 실행 파일 관리와 권한 관리가 중요하다.
- SSE는 원격 연결이 쉬운 대신 URL 검증과 SSRF 방어 정책을 반드시 점검해야 한다.
- 재연결과 health pinger는 편의 기능이지, 완전한 HA 보장을 의미하지는 않는다.

---

## 11. 대표 테스트 근거

MCP 영역은 테스트도 비교적 넓게 존재한다.

- [McpManagerTest.kt](/Users/jinan/ai/arc-reactor/arc-core/src/test/kotlin/com/arc/reactor/mcp/McpManagerTest.kt)
- [McpIntegrationTest.kt](/Users/jinan/ai/arc-reactor/arc-core/src/test/kotlin/com/arc/reactor/mcp/McpIntegrationTest.kt)
- [McpReconnectionCoordinatorTest.kt](/Users/jinan/ai/arc-reactor/arc-core/src/test/kotlin/com/arc/reactor/mcp/McpReconnectionCoordinatorTest.kt)
- [McpHealthPingerTest.kt](/Users/jinan/ai/arc-reactor/arc-core/src/test/kotlin/com/arc/reactor/mcp/McpHealthPingerTest.kt)
- [McpToolAvailabilityCheckerTest.kt](/Users/jinan/ai/arc-reactor/arc-core/src/test/kotlin/com/arc/reactor/mcp/McpToolAvailabilityCheckerTest.kt)
- [SsrfProtectionTest.kt](/Users/jinan/ai/arc-reactor/arc-core/src/test/kotlin/com/arc/reactor/mcp/SsrfProtectionTest.kt)
- [McpStdioCommandValidationTest.kt](/Users/jinan/ai/arc-reactor/arc-core/src/test/kotlin/com/arc/reactor/mcp/McpStdioCommandValidationTest.kt)
- [McpServerControllerTest.kt](/Users/jinan/ai/arc-reactor/arc-web/src/test/kotlin/com/arc/reactor/controller/McpServerControllerTest.kt)
- [McpSecurityControllerTest.kt](/Users/jinan/ai/arc-reactor/arc-web/src/test/kotlin/com/arc/reactor/controller/McpSecurityControllerTest.kt)
- [McpAccessPolicyControllerTest.kt](/Users/jinan/ai/arc-reactor/arc-web/src/test/kotlin/com/arc/reactor/controller/McpAccessPolicyControllerTest.kt)
- [McpPreflightControllerTest.kt](/Users/jinan/ai/arc-reactor/arc-web/src/test/kotlin/com/arc/reactor/controller/McpPreflightControllerTest.kt)
- [McpSwaggerCatalogControllerTest.kt](/Users/jinan/ai/arc-reactor/arc-web/src/test/kotlin/com/arc/reactor/controller/McpSwaggerCatalogControllerTest.kt)

---

## 12. 근거 파일

- [McpServerController.kt](/Users/jinan/ai/arc-reactor/arc-web/src/main/kotlin/com/arc/reactor/controller/McpServerController.kt)
- [McpSecurityController.kt](/Users/jinan/ai/arc-reactor/arc-web/src/main/kotlin/com/arc/reactor/controller/McpSecurityController.kt)
- [McpAccessPolicyController.kt](/Users/jinan/ai/arc-reactor/arc-web/src/main/kotlin/com/arc/reactor/controller/McpAccessPolicyController.kt)
- [McpPreflightController.kt](/Users/jinan/ai/arc-reactor/arc-web/src/main/kotlin/com/arc/reactor/controller/McpPreflightController.kt)
- [McpSwaggerCatalogController.kt](/Users/jinan/ai/arc-reactor/arc-web/src/main/kotlin/com/arc/reactor/controller/McpSwaggerCatalogController.kt)
- [ArcReactorHookAndMcpConfiguration.kt](/Users/jinan/ai/arc-reactor/arc-core/src/main/kotlin/com/arc/reactor/autoconfigure/ArcReactorHookAndMcpConfiguration.kt)
- [McpManager.kt](/Users/jinan/ai/arc-reactor/arc-core/src/main/kotlin/com/arc/reactor/mcp/McpManager.kt)
- [McpConnectionSupport.kt](/Users/jinan/ai/arc-reactor/arc-core/src/main/kotlin/com/arc/reactor/mcp/McpConnectionSupport.kt)
- [McpReconnectionCoordinator.kt](/Users/jinan/ai/arc-reactor/arc-core/src/main/kotlin/com/arc/reactor/mcp/McpReconnectionCoordinator.kt)
- [McpHealthPinger.kt](/Users/jinan/ai/arc-reactor/arc-core/src/main/kotlin/com/arc/reactor/mcp/McpHealthPinger.kt)
- [McpToolAvailabilityChecker.kt](/Users/jinan/ai/arc-reactor/arc-core/src/main/kotlin/com/arc/reactor/mcp/McpToolAvailabilityChecker.kt)
