# MCP 통합 가이드

> **핵심 파일:** `McpManager.kt`, `McpModels.kt`, `McpServerStore.kt`, `McpServerController.kt`, `McpStartupInitializer.kt`
> 이 문서는 Arc Reactor의 MCP (Model Context Protocol) 통합을 설명합니다.

## MCP란?

Model Context Protocol은 AI 에이전트가 외부 도구와 데이터 소스에 연결하는 표준 프로토콜입니다.

**MCP가 해결하는 문제:**
- 도구를 코드로 직접 구현하지 않고, 외부 MCP 서버에서 동적으로 로드
- 하나의 MCP 서버를 여러 에이전트/프레임워크에서 공유
- 표준화된 도구 호출 인터페이스

```
Arc Reactor Agent
    │
    ├── Local Tools (@Tool 어노테이션)
    │
    └── MCP Tools (외부 서버에서 동적 로드)
            │
            ├── filesystem MCP 서버 → 파일 읽기/쓰기
            ├── database MCP 서버  → SQL 쿼리
            └── slack MCP 서버     → 메시지 전송
```

## 지원 트랜스포트

| 트랜스포트 | 통신 방식 | 설정 필드 | 상태 |
|------------|-----------|-----------|------|
| **STDIO** | 로컬 프로세스 stdin/stdout | `command`, `args` | 구현 완료 |
| **SSE** | Server-Sent Events over HTTP | `url` | 구현 완료 |
| **HTTP** | Streamable HTTP | `url` | MCP SDK 미지원 (예약) |

### STDIO — 로컬 프로세스

가장 일반적인 방식입니다. MCP 서버를 로컬 프로세스로 실행하고 stdin/stdout으로 통신합니다.

```kotlin
McpServer(
    name = "filesystem",
    transportType = McpTransportType.STDIO,
    config = mapOf(
        "command" to "npx",
        "args" to listOf("-y", "@modelcontextprotocol/server-filesystem", "/data")
    )
)
```

### SSE — 원격 서버

원격에서 실행 중인 MCP 서버에 HTTP SSE로 연결합니다.

```kotlin
McpServer(
    name = "remote-db",
    transportType = McpTransportType.SSE,
    config = mapOf(
        "url" to "http://mcp-server.internal:3000/sse"
    )
)
```

## 핵심 모델

### McpServer — 서버 설정

```kotlin
data class McpServer(
    val id: String = UUID.randomUUID().toString(), // 고유 ID (자동 생성)
    val name: String,                              // 서버 고유 이름 (식별자)
    val description: String? = null,               // 설명
    val transportType: McpTransportType,           // STDIO | SSE | HTTP
    val config: Map<String, Any> = emptyMap(),     // 트랜스포트별 설정
    val version: String? = null,                   // 서버 버전 (기본: "1.0.0")
    val autoConnect: Boolean = false,              // 시작 시 자동 연결
    val createdAt: Instant = Instant.now(),        // 생성 시각
    val updatedAt: Instant = Instant.now()         // 최종 수정 시각
)
```

**config 필드 (트랜스포트별):**

| 트랜스포트 | 필수 필드 | 설명 |
|------------|-----------|------|
| STDIO | `command: String` | 실행할 명령어 (예: `"npx"`, `"node"`) |
| STDIO | `args: List<String>` (선택) | 명령어 인자 |
| SSE | `url: String` | SSE 엔드포인트 URL |

### McpServerStatus — 연결 상태

```kotlin
enum class McpServerStatus {
    PENDING,       // 등록됨, 연결 전
    CONNECTING,    // 연결 중
    CONNECTED,     // 연결됨 (도구 로드 완료)
    DISCONNECTED,  // 연결 해제됨
    FAILED,        // 연결 실패
    DISABLED       // 비활성화됨
}
```

**상태 전이:**

```
register() → PENDING
connect()  → CONNECTING → CONNECTED
                        → FAILED (에러 시) → [자동 재연결] → CONNECTED
disconnect() → DISCONNECTED
ensureConnected() → CONNECTED (FAILED/DISCONNECTED 상태에서 온디맨드 재연결)
```

## McpManager 인터페이스

```kotlin
interface McpManager {
    fun register(server: McpServer)
    suspend fun unregister(serverName: String)        // 연결 해제 + 스토어에서 제거
    suspend fun connect(serverName: String): Boolean
    suspend fun disconnect(serverName: String)
    fun getAllToolCallbacks(): List<ToolCallback>
    fun getToolCallbacks(serverName: String): List<ToolCallback>
    fun listServers(): List<McpServer>
    fun getStatus(serverName: String): McpServerStatus?
    suspend fun ensureConnected(serverName: String): Boolean  // 온디맨드 재연결
    suspend fun initializeFromStore()                  // 스토어에서 로드 + 자동 연결
}
```

## DefaultMcpManager 구현

### 내부 구조

```kotlin
class DefaultMcpManager(
    private val connectionTimeoutMs: Long = 30_000,
    private val securityConfig: McpSecurityConfig = McpSecurityConfig(),
    private val store: McpServerStore? = null          // 선택적 영속화
) : McpManager, AutoCloseable {

    // 런타임 서버 캐시 (store 없을 때 폴백)
    private val servers = ConcurrentHashMap<String, McpServer>()

    // 연결된 MCP 클라이언트
    private val clients = ConcurrentHashMap<String, McpSyncClient>()

    // 로드된 도구 캐시
    private val toolCallbacksCache = ConcurrentHashMap<String, List<ToolCallback>>()

    // 서버별 상태
    private val statuses = ConcurrentHashMap<String, McpServerStatus>()

    // 서버별 Mutex (connect/disconnect 원자성 보장)
    private val serverMutexes = ConcurrentHashMap<String, Mutex>()
}
```

### 동시성 안전

- **ConcurrentHashMap**: 모든 내부 맵에 사용. 다중 스레드에서 안전한 읽기/쓰기
- **Per-server Mutex**: 같은 서버에 대한 connect/disconnect가 동시에 실행되지 않도록 보장

```kotlin
private fun mutexFor(serverName: String): Mutex =
    serverMutexes.getOrPut(serverName) { Mutex() }

// connect()와 disconnect() 모두 Mutex로 보호
mutexFor(serverName).withLock {
    // 연결/해제 로직
}
```

### 연결 흐름

```
1. register(McpServer)
   ├── 허용 목록 확인 (securityConfig.allowedServerNames에 없으면 거부)
   ├── servers에 저장, 상태 = PENDING
   └── store가 있으면 영속화 (이미 존재하면 skip)

2. connect(serverName)
   ├── Mutex 획득
   ├── 상태 = CONNECTING
   ├── 트랜스포트 생성 (STDIO/SSE)
   │   ├── STDIO: ServerParameters → StdioClientTransport → McpClient.sync()
   │   └── SSE: HttpClientSseClientTransport → McpClient.sync()
   ├── client.initialize() — MCP 핸드셰이크
   ├── loadToolCallbacks() — 도구 목록 요청 및 캐시
   ├── 상태 = CONNECTED
   └── Mutex 해제

3. getAllToolCallbacks()
   └── toolCallbacksCache.values.flatten() — 캐시에서 즉시 반환

4. disconnect(serverName)
   ├── Mutex 획득
   ├── client.closeGracefully() — 정상 종료
   │   └── 실패 시 client.close() — 강제 종료
   ├── 캐시 제거
   ├── 상태 = DISCONNECTED
   └── Mutex 해제

5. unregister(serverName) — 신규
   ├── disconnectInternal() — 연결 해제
   ├── servers, statuses, mutexes에서 제거
   └── store에서 삭제

6. initializeFromStore() — 신규
   ├── store에서 전체 서버 로드
   ├── 런타임 캐시에 등록
   └── autoConnect=true인 서버 자동 연결
```

### 도구 로드

```kotlin
private fun loadToolCallbacks(client: McpSyncClient, serverName: String): List<ToolCallback> {
    val toolsResult = client.listTools()       // MCP 프로토콜: tools/list 요청
    val tools = toolsResult.tools()

    return tools.map { tool ->
        McpToolCallback(
            client = client,
            name = tool.name(),
            description = tool.description() ?: "",
            mcpInputSchema = tool.inputSchema()
        )
    }
}
```

MCP 서버의 `tools/list` 엔드포인트를 호출하여 사용 가능한 도구 목록을 가져옵니다. 각 도구는 `McpToolCallback`으로 래핑되어 Arc Reactor의 `ToolCallback` 인터페이스와 호환됩니다.

## McpToolCallback — MCP 도구 래퍼

MCP 도구를 Arc Reactor의 `ToolCallback` 인터페이스로 래핑합니다.

```kotlin
class McpToolCallback(
    private val client: McpSyncClient,
    override val name: String,
    override val description: String,
    private val mcpInputSchema: McpSchema.JsonSchema?
) : ToolCallback {

    override val inputSchema: String
        get() = mcpInputSchema?.let {
            jacksonObjectMapper().writeValueAsString(it)
        } ?: """{"type":"object","properties":{}}"""

    override suspend fun call(arguments: Map<String, Any?>): Any? {
        val request = McpSchema.CallToolRequest(name, arguments)
        val result = client.callTool(request)

        return result.content().joinToString("\n") { content ->
            when (content) {
                is McpSchema.TextContent -> content.text()
                is McpSchema.ImageContent -> "[Image: ${content.mimeType()}]"
                is McpSchema.EmbeddedResource -> "[Resource: ${content.resource().uri()}]"
                else -> content.toString()
            }
        }
    }
}
```

**콘텐츠 타입 처리:**
- `TextContent` → 텍스트 그대로 반환
- `ImageContent` → `[Image: mime/type]` 플레이스홀더
- `EmbeddedResource` → `[Resource: uri]` 플레이스홀더

## 에이전트와의 통합

### 도구 수집 흐름

`SpringAiAgentExecutor`가 도구를 수집할 때:

```kotlin
private fun selectAndPrepareTools(userPrompt: String): List<Any> {
    val localToolInstances = localTools.toList()          // @Tool 어노테이션 도구
    val allCallbacks = toolCallbacks + mcpToolCallbacks() // ToolCallback + MCP 도구
    val selectedCallbacks = toolSelector?.select(userPrompt, allCallbacks) ?: allCallbacks
    val wrappedCallbacks = selectedCallbacks.map { ArcToolCallbackAdapter(it) }
    return (localToolInstances + wrappedCallbacks).take(maxToolsPerRequest)
}
```

`mcpToolCallbacks`는 `{ mcpManager.getAllToolCallbacks() }` 람다로 주입됩니다. 매 요청마다 호출되므로, 런타임 중에 MCP 서버를 추가/제거하면 즉시 반영됩니다.

### 자동 구성

```kotlin
// ArcReactorAutoConfiguration.kt

@Bean
@ConditionalOnMissingBean
fun mcpServerStore(): McpServerStore = InMemoryMcpServerStore()

@Bean
@ConditionalOnMissingBean
fun mcpManager(
    properties: AgentProperties,
    mcpServerStore: McpServerStore
): McpManager = DefaultMcpManager(
    securityConfig = McpSecurityConfig(
        allowedServerNames = properties.mcp.security.allowedServerNames,
        maxToolOutputLength = properties.mcp.security.maxToolOutputLength
    ),
    store = mcpServerStore
)

@Bean
fun mcpStartupInitializer(
    properties: AgentProperties,
    mcpManager: McpManager,
    mcpServerStore: McpServerStore
): McpStartupInitializer = McpStartupInitializer(properties, mcpManager, mcpServerStore)

@Bean
fun agentExecutor(..., mcpManager: McpManager, ...): AgentExecutor =
    SpringAiAgentExecutor(
        ...,
        mcpToolCallbacks = { mcpManager.getAllToolCallbacks() },
        ...
    )
```

PostgreSQL을 사용할 때 (`-Pdb=true`), `JdbcMcpServerStore`가 `@Primary`로 등록되어 인메모리 기본값을 대체합니다.

## 사용 예시

### 방법 1: yml 설정 (배포 환경 권장)

```yaml
arc:
  reactor:
    mcp:
      servers:
        - name: filesystem
          transport: stdio
          command: npx
          args: ["-y", "@modelcontextprotocol/server-filesystem", "/data"]
        - name: github
          transport: stdio
          command: npx
          args: ["-y", "@modelcontextprotocol/server-github"]
```

코드 작성 불필요. 앱 시작 시 자동으로 등록 및 연결됩니다.

### 방법 2: REST API (런타임 관리)

```bash
# API로 등록
curl -X POST http://localhost:8080/api/mcp/servers \
  -H "Content-Type: application/json" \
  -d '{"name":"filesystem","transportType":"STDIO","config":{"command":"npx","args":["-y","@modelcontextprotocol/server-filesystem","/data"]}}'

# 서버 목록
curl http://localhost:8080/api/mcp/servers

# 연결 해제
curl -X POST http://localhost:8080/api/mcp/servers/filesystem/disconnect
```

### 방법 3: 코드 등록

```kotlin
@Service
class McpSetup(private val mcpManager: McpManager) {

    @PostConstruct
    fun setup() {
        mcpManager.register(McpServer(
            name = "filesystem",
            transportType = McpTransportType.STDIO,
            config = mapOf(
                "command" to "npx",
                "args" to listOf("-y", "@modelcontextprotocol/server-filesystem", "/data")
            )
        ))
        runBlocking { mcpManager.connect("filesystem") }
    }

    @PreDestroy
    fun cleanup() {
        (mcpManager as? AutoCloseable)?.close()
    }
}
```

### 런타임 서버 관리

```kotlin
// 서버 상태 확인
val status = mcpManager.getStatus("filesystem")  // CONNECTED

// 특정 서버의 도구만 조회
val fsTools = mcpManager.getToolCallbacks("filesystem")

// 모든 서버의 도구 조회
val allTools = mcpManager.getAllToolCallbacks()

// 서버 목록
val servers = mcpManager.listServers()

// 런타임 연결 해제
runBlocking { mcpManager.disconnect("github") }

// 등록 해제 (연결 해제 + 스토어에서 삭제)
runBlocking { mcpManager.unregister("github") }
```

## 서버 영속화 — McpServerStore

MCP 서버 설정을 영속화하여 재시작 후에도 복원할 수 있습니다.

### 인터페이스

```kotlin
interface McpServerStore {
    fun list(): List<McpServer>
    fun findByName(name: String): McpServer?
    fun save(server: McpServer): McpServer
    fun update(name: String, server: McpServer): McpServer?
    fun delete(name: String)
}
```

### 구현체

| 구현체 | 저장소 | 사용 시점 |
|--------|--------|-----------|
| `InMemoryMcpServerStore` | ConcurrentHashMap | 기본값 (재시작 시 데이터 손실) |
| `JdbcMcpServerStore` | PostgreSQL | `-Pdb=true` 빌드 플래그 설정 시 |

`JdbcMcpServerStore`는 DataSource가 있으면 `@Primary`로 자동 구성됩니다. `JdbcMemoryStore`, `JdbcPersonaStore`와 동일한 패턴입니다.

### 데이터베이스 스키마 (Flyway V7)

```sql
CREATE TABLE IF NOT EXISTS mcp_servers (
    id              VARCHAR(36)     PRIMARY KEY,
    name            VARCHAR(100)    NOT NULL,
    description     VARCHAR(500),
    transport_type  VARCHAR(20)     NOT NULL,
    config          TEXT            NOT NULL DEFAULT '{}',
    version         VARCHAR(50),
    auto_connect    BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_mcp_servers_name ON mcp_servers(name);
```

`config` 컬럼은 트랜스포트별 설정을 JSON 텍스트로 저장합니다 (ObjectMapper로 직렬화).

## yml 설정 — 선언적 서버 등록

`application.yml`에 MCP 서버를 선언하면 시작 시 자동으로 등록됩니다:

```yaml
arc:
  reactor:
    mcp:
      servers:
        - name: swagger-agent
          transport: sse
          url: http://localhost:8081/sse
          auto-connect: true
        - name: filesystem
          transport: stdio
          command: npx
          args: ["-y", "@modelcontextprotocol/server-filesystem", "/data"]
          description: 파일 시스템 접근
      security:
        allowed-server-names: []        # 비어있으면 모두 허용
        max-tool-output-length: 50000
```

### McpServerDefinition 속성

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `name` | String | (필수) | 서버 고유 이름 |
| `transport` | Enum | `SSE` | `STDIO`, `SSE`, `HTTP` |
| `url` | String | null | SSE/HTTP 엔드포인트 URL |
| `command` | String | null | STDIO 실행 명령어 |
| `args` | List | [] | STDIO 명령어 인자 |
| `description` | String | null | 서버 설명 |
| `auto-connect` | Boolean | true | 시작 시 자동 연결 |

### 시작 흐름 (McpStartupInitializer)

```
ApplicationReadyEvent
  │
  v
1. seedYmlServers()
   └── arc.reactor.mcp.servers의 각 서버에 대해:
       ├── name이 비어있으면 건너뜀
       ├── 스토어에 이미 존재하면 건너뜀
       ├── McpServerDefinition → McpServer 변환
       └── McpServerStore에 저장

2. mcpManager.initializeFromStore()
   ├── 스토어에서 전체 서버 로드
   ├── 런타임 캐시에 등록
   └── autoConnect=true인 서버 자동 연결
```

yml 서버는 **시드**(seed)됩니다 — 스토어에 같은 이름의 서버가 이미 있으면 (예: REST API로 수정된 경우) yml 정의를 건너뜁니다.

## REST API — 동적 서버 관리

런타임에 MCP 서버를 관리하는 내장 REST API입니다. 인증 활성화 시 쓰기 작업은 관리자(Admin) 권한이 필요합니다.

### 엔드포인트

| 메서드 | 경로 | 설명 | 권한 |
|--------|------|------|------|
| `GET` | `/api/mcp/servers` | 전체 서버 목록 (상태 포함) | 읽기 |
| `POST` | `/api/mcp/servers` | 등록 + 자동 연결 | 관리자 |
| `GET` | `/api/mcp/servers/{name}` | 상세 조회 (도구 목록 포함) | 읽기 |
| `PUT` | `/api/mcp/servers/{name}` | 설정 수정 | 관리자 |
| `DELETE` | `/api/mcp/servers/{name}` | 연결 해제 + 제거 | 관리자 |
| `POST` | `/api/mcp/servers/{name}/connect` | 연결 | 관리자 |
| `POST` | `/api/mcp/servers/{name}/disconnect` | 연결 해제 (설정 유지) | 관리자 |

### 서버 등록

```bash
curl -X POST http://localhost:8080/api/mcp/servers \
  -H "Content-Type: application/json" \
  -d '{
    "name": "swagger-agent",
    "transportType": "SSE",
    "config": { "url": "http://localhost:8081/sse" },
    "autoConnect": true
  }'
```

응답 (201 Created):
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "name": "swagger-agent",
  "transportType": "SSE",
  "autoConnect": true,
  "status": "CONNECTED",
  "toolCount": 5,
  "createdAt": 1707436800000,
  "updatedAt": 1707436800000
}
```

### 서버 목록 조회

```bash
curl http://localhost:8080/api/mcp/servers
```

### 서버 상세 조회 (도구 목록 포함)

```bash
curl http://localhost:8080/api/mcp/servers/swagger-agent
```

응답:
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "name": "swagger-agent",
  "transportType": "SSE",
  "config": { "url": "http://localhost:8081/sse" },
  "autoConnect": true,
  "status": "CONNECTED",
  "tools": ["listPaths", "getSchema", "testEndpoint"],
  "createdAt": 1707436800000,
  "updatedAt": 1707436800000
}
```

### 연결 / 해제

```bash
# 연결
curl -X POST http://localhost:8080/api/mcp/servers/swagger-agent/connect

# 연결 해제 (설정 유지, 나중에 재연결 가능)
curl -X POST http://localhost:8080/api/mcp/servers/swagger-agent/disconnect

# 완전 삭제
curl -X DELETE http://localhost:8080/api/mcp/servers/swagger-agent
```

## 자동 재연결

MCP 서버 연결이 실패하면, `DefaultMcpManager`가 자동으로 지수 백오프(exponential backoff)를 적용하여 재연결을 시도합니다. 기본적으로 활성화되어 있습니다.

### 설정

```yaml
arc:
  reactor:
    mcp:
      reconnection:
        enabled: true           # 자동 재연결 활성화 (기본: true)
        max-attempts: 5         # 포기 전 최대 재시도 횟수
        initial-delay-ms: 5000  # 초기 백오프 지연
        multiplier: 2.0         # 백오프 배율
        max-delay-ms: 60000     # 최대 백오프 지연
```

### 동작 원리

```
connect() 실패
  │
  v
scheduleReconnection()
  ├── 확인: reconnection.enabled?  아니오 → 종료
  ├── 확인: 이미 재연결 중?        예 → 종료 (중복 방지)
  │
  v
백그라운드 코루틴 (Dispatchers.IO + SupervisorJob)
  │
  for attempt in 1..maxAttempts:
    ├── 지연 계산: min(initialDelay * multiplier^(attempt-1), maxDelay)
    ├── 지터 추가: ±25% 무작위화
    ├── delay(...)
    ├── 확인: 서버가 삭제되었거나 이미 연결됨? → 종료
    ├── connect(serverName)
    │   ├── 성공 → 종료 (재연결 완료!)
    │   └── 실패 → 다음 시도로 계속
    │
  모든 시도 소진 → 경고 로그, 포기
```

### 온디맨드 재연결

도구 호출이 연결 해제/실패 상태의 서버를 대상으로 할 때, `ensureConnected()`가 호출 전에 단일 동기 재연결을 시도합니다:

```kotlin
// MCP 도구 실행 전 호출
suspend fun ensureConnected(serverName: String): Boolean
```

- 서버가 CONNECTED 상태이면 `true` 반환 (이미 연결됨 또는 재연결 성공)
- 재연결이 비활성화되었거나, PENDING/CONNECTING 상태이거나, 재연결 실패 시 `false` 반환

### 스레드 안전성

- `ConcurrentHashMap.newKeySet<String>()` — 활성 재연결 작업이 있는 서버 추적
- `CoroutineScope(Dispatchers.IO + SupervisorJob())` — 재연결 실패 격리
- 서버별 `Mutex` — connect/disconnect 원자성 보장 (기존과 동일)
- `close()` — 모든 백그라운드 재연결 작업 취소

## 종료 처리

`DefaultMcpManager`는 `AutoCloseable`을 구현합니다:

```kotlin
override fun close() {
    reconnectScope.cancel()        // 모든 백그라운드 재연결 작업 취소
    reconnectingServers.clear()
    for (serverName in clients.keys.toList()) {
        disconnectInternal(serverName)  // non-suspend (runBlocking 회피)
    }
    servers.clear()
    statuses.clear()
    serverMutexes.clear()
}
```

**주의:** `close()`는 먼저 재연결 스코프를 취소하고, `disconnectInternal()`을 직접 호출합니다 (Mutex 없음, non-suspend). `AutoCloseable.close()`가 `suspend fun`일 수 없기 때문입니다. Spring 컨텍스트 종료 시 자동 호출됩니다.

## 알려진 제약사항

| 제약사항 | 설명 | 대안 |
|----------|------|------|
| HTTP 트랜스포트 미지원 | MCP SDK 0.10.0에 Streamable HTTP 미포함 | SSE 사용 |
| 동기 클라이언트 | `McpSyncClient` 사용 (비동기 미지원) | 코루틴 내에서 블로킹 가능 |
| 도구 캐시 | 연결 시 한 번만 로드 | 재연결 시 갱신됨 |
| ImageContent | 텍스트 플레이스홀더로 변환 | 멀티모달 지원 시 확장 필요 |
