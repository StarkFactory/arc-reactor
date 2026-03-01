# arc-web

## 개요

`arc-web`은 Arc Reactor의 HTTP 레이어를 제공합니다. 채팅(표준 및 스트리밍 SSE), 세션 관리, Persona 관리, 프롬프트 템플릿 관리, MCP 서버 등록, RAG 문서 관리, 인증을 위한 REST 엔드포인트를 노출합니다. 또한 보안 헤더, 선택적 CORS, 테넌트 컨텍스트 해석, 전역 예외 처리기를 제공합니다.

`arc-web`은 런타임에 `arc-core`에 의존합니다. Slack 봇, gRPC 서비스, CLI 도구 등 HTTP가 필요 없는 통합을 구축하는 팀이 웹 레이어 없이 `arc-core`만 포함할 수 있도록 별도 모듈로 분리되어 있습니다.

모든 컨트롤러는 Spring 빈으로 등록되며, `@ConditionalOnMissingBean` 또는 자동 구성 비활성화를 통해 커스텀 구현으로 대체할 수 있습니다.

---

## 핵심 컴포넌트

| 클래스 | 역할 | 패키지 |
|---|---|---|
| `ChatController` | `POST /api/chat`와 `POST /api/chat/stream` | `controller` |
| `MultipartChatController` | `POST /api/chat/multipart` — 파일 업로드 및 멀티모달 | `controller` |
| `SessionController` | 세션 목록, 상세, 내보내기, 삭제; 모델 목록 | `controller` |
| `PersonaController` | 시스템 프롬프트 Persona CRUD | `controller` |
| `PromptTemplateController` | 버전 관리 프롬프트 템플릿 관리 | `controller` |
| `McpServerController` | 동적 MCP 서버 등록 및 라이프사이클 | `controller` |
| `AuthController` | JWT 등록/로그인/프로필 조회 | `controller` |
| `DocumentController` | 벡터 스토어 문서 추가/검색/삭제 (RAG) | `controller` |
| `FeedbackController` | 좋아요/싫어요 피드백 수집 | `controller` |
| `IntentController` | Intent 레지스트리 CRUD | `controller` |
| `SchedulerController` | 동적 cron 작업 관리 | `controller` |
| `ToolPolicyController` | 런타임 Tool 정책 관리 | `controller` |
| `OutputGuardRuleController` | 동적 Output Guard 규칙 관리 | `controller` |
| `ApprovalController` | Human-in-the-Loop 승인 워크플로우 | `controller` |
| `PromptLabController` | Prompt Lab 실험 관리 | `controller` |
| `OpsDashboardController` | 운영 대시보드 (메트릭, 이상 감지) | `controller` |
| `GlobalExceptionHandler` | `@RestControllerAdvice` — 표준화된 오류 응답 | `controller` |
| `SecurityHeadersWebFilter` | 모든 HTTP 응답에 보안 헤더 추가 | `autoconfigure` |
| `CorsSecurityConfiguration` | 선택적 CORS 필터 | `autoconfigure` |
| `ApiVersionContractWebFilter` | `X-Api-Version` 헤더를 통한 API 버전 계약 적용 | `autoconfigure` |
| `TenantContextResolver` | JWT 속성 또는 `X-Tenant-Id` 헤더에서 테넌트 ID 해석 | `controller` |
| `AdminAuthSupport` | 공유 `isAdmin()`, `forbiddenResponse()` 헬퍼 | `controller` |
| `ArcReactorWebAutoConfiguration` | 웹 레이어 자동 구성 진입점 | `autoconfigure` |

---

## 설정

웹 관련 프로퍼티는 `arc-core`와 동일한 `arc.reactor.*` 네임스페이스를 공유합니다.

### 보안 헤더 (`arc.reactor.security-headers`)

| 프로퍼티 | 기본값 | 설명 |
|---|---|---|
| `enabled` | `true` | 모든 응답에 표준 보안 헤더 추가 |

활성화 시 적용되는 헤더:

| 헤더 | 값 |
|---|---|
| `X-Content-Type-Options` | `nosniff` |
| `X-Frame-Options` | `DENY` |
| `Content-Security-Policy` | `default-src 'self'` |
| `X-XSS-Protection` | `0` |
| `Referrer-Policy` | `strict-origin-when-cross-origin` |
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains` |

### CORS (`arc.reactor.cors`)

| 프로퍼티 | 기본값 | 설명 |
|---|---|---|
| `enabled` | `false` | CORS 필터 (선택적 활성화) |
| `allowed-origins` | `[http://localhost:3000]` | 허용되는 출처 |
| `allowed-methods` | `[GET, POST, PUT, DELETE, OPTIONS]` | 허용되는 HTTP 메서드 |
| `allowed-headers` | `[*]` | 허용되는 요청 헤더 |
| `allow-credentials` | `false` | 쿠키/Authorization 헤더 허용 |
| `max-age` | `3600` | Preflight 캐시 유효 시간(초) |

### 인증 (`arc.reactor.auth`)

| 프로퍼티 | 기본값 | 설명 |
|---|---|---|
| `jwt-secret` | (비어 있음) | JWT 서명 시크릿 (최소 32바이트, 필수) |
| `default-tenant-id` | `default` | JWT 토큰에 포함되는 기본 tenant claim |
| `public-actuator-health` | `false` | `/actuator/health`를 public path에 추가 |

`JwtAuthWebFilter`가 `Authorization: Bearer <token>` 헤더를 검증하고 exchange에 사용자 ID와 역할 속성을 설정합니다. `arc.reactor.auth.jwt-secret`이 비어 있거나 짧으면 런타임 기동이 실패합니다.

### 멀티모달 (`arc.reactor.multimodal`)

| 프로퍼티 | 기본값 | 설명 |
|---|---|---|
| `enabled` | `true` | 멀티파트 파일 업로드 엔드포인트 |
| `max-file-size-bytes` | `10485760` | 파일당 최대 크기 (10MB) |
| `max-files-per-request` | `5` | 멀티파트 요청당 최대 파일 수 |

### API 버전 계약

| 프로퍼티 | 기본값 | 설명 |
|---|---|---|
| `arc.reactor.api-version.enabled` | `true` | API 버전 계약 적용 |
| `arc.reactor.api-version.current` | `v1` | 현재 API 버전 |
| `arc.reactor.api-version.supported` | (current) | 지원되는 버전 목록 (쉼표 구분) |

---

## 확장 지점

### 컨트롤러 재정의

모든 컨트롤러는 자동 구성을 통해 등록된 일반 Spring `@RestController` 빈이므로, 같은 이름의 빈을 선언하거나 자동 구성 클래스를 제외하여 대체할 수 있습니다.

```kotlin
@RestController
@RequestMapping("/api/chat")
@Primary
class MyCustomChatController(
    agentExecutor: AgentExecutor,
    properties: AgentProperties
) : ChatController(agentExecutor, properties = properties) {

    @PostMapping("/v2")
    suspend fun chatV2(@RequestBody request: MyChatRequest, exchange: ServerWebExchange): ChatResponse {
        // 커스텀 라우팅 로직
    }
}
```

### WebFilter — 커스텀 요청 필터

`WebFilter` 빈을 등록하여 컨트롤러 전에 모든 요청을 가로채세요.

```kotlin
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class ApiKeyWebFilter : WebFilter {

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val apiKey = exchange.request.headers.getFirst("X-API-Key")
        if (apiKey != expectedKey) {
            exchange.response.statusCode = HttpStatus.UNAUTHORIZED
            return exchange.response.setComplete()
        }
        return chain.filter(exchange)
    }
}
```

`SecurityHeadersWebFilter`는 `Ordered.HIGHEST_PRECEDENCE + 1`에서 실행되므로, 더 높은 순서 번호를 가진 필터는 그 이후에 실행됩니다.

### 테넌트 해석

`TenantContextResolver`는 다음 우선순위로 테넌트 ID를 해석합니다.

1. `resolvedTenantId` exchange 속성 (JWT 필터가 설정)
2. `tenantId` exchange 속성 (레거시)
3. `X-Tenant-Id` 요청 헤더 (`^[a-zA-Z0-9_-]{1,64}$` 형식 검증)

테넌트 컨텍스트가 없으면 요청이 HTTP 400으로 거부됩니다.

### 관리자 권한 검사

쓰기 작업을 수행하는 컨트롤러는 `AdminAuthSupport.kt`의 `isAdmin(exchange)`를 호출합니다.

```kotlin
// AdminAuthSupport.kt
fun isAdmin(exchange: ServerWebExchange): Boolean {
    val role = exchange.attributes[JwtAuthWebFilter.USER_ROLE_ATTRIBUTE] as? UserRole
    return role == UserRole.ADMIN
}
```

`role`이 없으면 fail-close로 비관리자 처리됩니다.

항상 공유 `isAdmin()` 함수를 사용하세요. 커스텀 컨트롤러에서 이 로직을 복제하지 마세요.

### 시스템 프롬프트 해석

`ChatController`는 다음 우선순위로 시스템 프롬프트를 해석합니다.

1. `personaId` → `PersonaStore` 조회
2. `promptTemplateId` → 활성 `PromptVersion` 내용
3. `request.systemPrompt` → 직접 재정의
4. 기본 Persona (`isDefault=true`) from `PersonaStore`
5. 하드코딩된 폴백: `"You are a helpful AI assistant. You can use tools when needed. Answer in the same language as the user's message."`

### 커스텀 AuthProvider

기본 JWT + 데이터베이스 인증을 대체하려면:

```kotlin
@Bean
@Primary
fun authProvider(): AuthProvider = MyLdapAuthProvider()
```

`arc-core`의 `AuthProvider` 인터페이스를 구현하세요.

```kotlin
interface AuthProvider {
    fun authenticate(email: String, password: String): User?
    fun getUserById(id: String): User?
}
```

---

## API 레퍼런스

### 채팅

| 메서드 | 경로 | 설명 | 인증 필요 |
|---|---|---|---|
| `POST` | `/api/chat` | 표준 채팅 (전체 응답) | 아니오 |
| `POST` | `/api/chat/stream` | 스트리밍 채팅 (SSE) | 아니오 |
| `POST` | `/api/chat/multipart` | 파일 첨부 멀티파트 채팅 | 아니오 |

**ChatRequest 필드:**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `message` | `String` | 예 | 사용자 입력 (최대 50,000자) |
| `model` | `String` | 아니오 | 기본 LLM 공급자 재정의 |
| `systemPrompt` | `String` | 아니오 | 직접 시스템 프롬프트 (최대 10,000자) |
| `personaId` | `String` | 아니오 | 시스템 프롬프트에 사용할 Persona |
| `promptTemplateId` | `String` | 아니오 | 시스템 프롬프트에 사용할 템플릿 |
| `userId` | `String` | 아니오 | 사용자 식별자 |
| `metadata` | `Map<String, Any>` | 아니오 | `sessionId`, `channel` 등 전달 (최대 20개) |
| `responseFormat` | `TEXT\|JSON\|YAML` | 아니오 | 구조화된 출력 형식 |
| `responseSchema` | `String` | 아니오 | 구조화된 출력을 위한 JSON Schema |
| `mediaUrls` | `List<MediaUrlRequest>` | 아니오 | URL 기반 미디어 첨부 |

**SSE 이벤트 타입** (스트리밍 엔드포인트):

| 이벤트 | 데이터 | 설명 |
|---|---|---|
| `message` | 텍스트 청크 | LLM 토큰 |
| `tool_start` | Tool 이름 | Tool 실행 시작 |
| `tool_end` | Tool 이름 | Tool 실행 완료 |
| `error` | 오류 메시지 | 오류 발생 |
| `done` | (비어 있음) | 스트림 완료 |

### 세션

| 메서드 | 경로 | 설명 | 인증 필요 |
|---|---|---|---|
| `GET` | `/api/sessions` | 모든 세션 목록 | 아니오 (인증 시 사용자별 필터링) |
| `GET` | `/api/sessions/{sessionId}` | 세션 메시지 조회 | 아니오 (인증 시 소유자 확인) |
| `GET` | `/api/sessions/{sessionId}/export?format=json` | JSON으로 내보내기 | 아니오 |
| `GET` | `/api/sessions/{sessionId}/export?format=markdown` | Markdown으로 내보내기 | 아니오 |
| `DELETE` | `/api/sessions/{sessionId}` | 세션 삭제 | 아니오 (인증 시 소유자 확인) |
| `GET` | `/api/models` | 사용 가능한 LLM 공급자 목록 | 아니오 |

### Persona

| 메서드 | 경로 | 설명 | 인증 필요 |
|---|---|---|---|
| `GET` | `/api/personas` | 모든 Persona 목록 | 아니오 |
| `GET` | `/api/personas/{personaId}` | ID로 Persona 조회 | 아니오 |
| `POST` | `/api/personas` | Persona 생성 | 관리자 |
| `PUT` | `/api/personas/{personaId}` | Persona 수정 | 관리자 |
| `DELETE` | `/api/personas/{personaId}` | Persona 삭제 | 관리자 |

### 프롬프트 템플릿

| 메서드 | 경로 | 설명 | 인증 필요 |
|---|---|---|---|
| `GET` | `/api/prompt-templates` | 템플릿 목록 | 아니오 |
| `GET` | `/api/prompt-templates/{id}` | 버전 포함 템플릿 조회 | 아니오 |
| `POST` | `/api/prompt-templates` | 템플릿 생성 | 관리자 |
| `PUT` | `/api/prompt-templates/{id}` | 템플릿 메타데이터 수정 | 관리자 |
| `DELETE` | `/api/prompt-templates/{id}` | 템플릿 삭제 | 관리자 |
| `POST` | `/api/prompt-templates/{id}/versions` | 버전 생성 | 관리자 |
| `PUT` | `/api/prompt-templates/{id}/versions/{vid}/activate` | 버전 활성화 | 관리자 |
| `PUT` | `/api/prompt-templates/{id}/versions/{vid}/archive` | 버전 아카이브 | 관리자 |

### MCP 서버

| 메서드 | 경로 | 설명 | 인증 필요 |
|---|---|---|---|
| `GET` | `/api/mcp/servers` | 상태 포함 모든 서버 목록 | 아니오 |
| `POST` | `/api/mcp/servers` | 서버 등록 | 관리자 |
| `GET` | `/api/mcp/servers/{name}` | Tool 포함 서버 상세 | 아니오 |
| `PUT` | `/api/mcp/servers/{name}` | 서버 설정 수정 | 관리자 |
| `DELETE` | `/api/mcp/servers/{name}` | 서버 연결 해제 및 제거 | 관리자 |
| `POST` | `/api/mcp/servers/{name}/connect` | 서버 연결 | 관리자 |
| `POST` | `/api/mcp/servers/{name}/disconnect` | 서버 연결 해제 | 관리자 |

### 인증

| 메서드 | 경로 | 설명 | 인증 필요 |
|---|---|---|---|
| `POST` | `/api/auth/register` | 신규 사용자 등록 | 아니오 |
| `POST` | `/api/auth/login` | 로그인 및 JWT 발급 | 아니오 |
| `GET` | `/api/auth/me` | 현재 사용자 프로필 조회 | JWT |
| `POST` | `/api/auth/change-password` | 비밀번호 변경 | JWT |

### 문서 (`arc.reactor.rag.enabled=true` 필요)

| 메서드 | 경로 | 설명 | 인증 필요 |
|---|---|---|---|
| `POST` | `/api/documents` | 벡터 스토어에 문서 추가 | 관리자 |
| `POST` | `/api/documents/batch` | 문서 일괄 추가 | 관리자 |
| `POST` | `/api/documents/search` | 유사도 검색 | 아니오 |
| `DELETE` | `/api/documents` | ID로 문서 삭제 | 관리자 |

---

## 코드 예시

### 표준 채팅 호출

```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "서울의 날씨는 어떤가요?",
    "metadata": { "sessionId": "session-123", "channel": "web" }
  }'
```

응답:

```json
{
  "content": "현재 서울의 날씨는...",
  "success": true,
  "toolsUsed": ["get_weather"],
  "errorMessage": null
}
```

### 스트리밍 채팅 (SSE)

```bash
curl -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message": "양자 컴퓨팅을 설명해주세요"}' \
  --no-buffer
```

Kotlin 클라이언트 예시:

```kotlin
val client = WebClient.create("http://localhost:8080")

client.post()
    .uri("/api/chat/stream")
    .bodyValue(ChatRequest(message = "양자 컴퓨팅을 설명해주세요"))
    .retrieve()
    .bodyToFlux(ServerSentEvent::class.java)
    .subscribe { event ->
        when (event.event()) {
            "message" -> print(event.data())
            "tool_start" -> println("\n[Tool: ${event.data()}]")
            "done" -> println("\n[완료]")
        }
    }
```

### 파일 업로드 (멀티모달)

```bash
curl -X POST http://localhost:8080/api/chat/multipart \
  -F "message=이 이미지에는 무엇이 있나요?" \
  -F "files=@photo.png"
```

### MCP 서버 등록

```bash
# SSE 전송 방식
curl -X POST http://localhost:8080/api/mcp/servers \
  -H "Content-Type: application/json" \
  -d '{
    "name": "my-mcp-server",
    "transportType": "SSE",
    "config": { "url": "http://localhost:8081/sse" },
    "autoConnect": true
  }'

# STDIO 전송 방식
curl -X POST http://localhost:8080/api/mcp/servers \
  -H "Content-Type: application/json" \
  -d '{
    "name": "fs-server",
    "transportType": "STDIO",
    "config": { "command": "npx", "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"] }
  }'
```

---

## 자주 발생하는 실수

**모든 403 응답은 `ErrorResponse` 본문을 포함해야 합니다.** `ResponseEntity.status(403).build()`는 사용하지 마세요. 항상 `forbiddenResponse()`를 사용하거나 본문을 명시적으로 생성하세요. 빈 403 응답 본문은 일관된 오류 구조를 기대하는 API 클라이언트를 망가뜨립니다.

**`isAdmin()` 로직을 복제하지 마세요.** `AdminAuthSupport.kt`의 공유 `isAdmin(exchange)` 함수만 사용하세요. 이 로직을 복제하면 불일치가 발생하고 보안 취약점이 생길 수 있습니다.

**`SessionController`는 세션 전용 `sessionForbidden()` 함수를 사용합니다.** 이는 세션별 오류 메시지를 위한 의도적인 설계이며, 일반 `forbiddenResponse()`와는 다릅니다.

**스트리밍 엔드포인트는 구조화된 출력 형식을 지원하지 않습니다.** `POST /api/chat/stream`에서 `responseFormat = JSON` 또는 `YAML`을 요청하면 오류 청크가 반환됩니다. 스트리밍은 텍스트 토큰 전용입니다.

**MCP HTTP 전송 방식은 지원되지 않습니다.** `transportType: HTTP`로 MCP 서버를 등록하려 하면 HTTP 400이 반환됩니다. `SSE` 또는 `STDIO`를 사용하세요.

**테넌트 ID 형식은 엄격하게 검증됩니다.** `X-Tenant-Id` 헤더는 `^[a-zA-Z0-9_-]{1,64}$` 형식을 따라야 합니다. 유효하지 않은 형식의 요청은 HTTP 400으로 거부됩니다.

**멀티파트 파일 업로드는 DoS에 대비한 보호 기능이 있습니다.** `MultipartChatController`는 스트리밍 중에 (바이트가 완전히 메모리에 로드되기 전에) 파일당 크기 제한을 적용합니다. `max-file-size-bytes`를 매우 높게 설정하면 메모리에 미치는 영향을 충분히 이해한 후 진행하세요.

**인증은 필수입니다.** 모든 런타임 환경에서 유효한 JWT 시크릿을 제공하세요.
