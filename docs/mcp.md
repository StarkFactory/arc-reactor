# MCP 통합 가이드

> **핵심 파일:** `McpManager.kt`, `McpModels.kt`
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
    val name: String,                          // 서버 고유 이름 (식별자)
    val description: String? = null,           // 설명
    val transportType: McpTransportType,       // STDIO | SSE | HTTP
    val config: Map<String, Any> = emptyMap(), // 트랜스포트별 설정
    val version: String? = null,               // 서버 버전 (기본: "1.0.0")
    val autoConnect: Boolean = false           // 등록 시 자동 연결
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
                        → FAILED (에러 시)
disconnect() → DISCONNECTED
```

## McpManager 인터페이스

```kotlin
interface McpManager {
    fun register(server: McpServer)
    suspend fun connect(serverName: String): Boolean
    suspend fun disconnect(serverName: String)
    fun getAllToolCallbacks(): List<ToolCallback>
    fun getToolCallbacks(serverName: String): List<ToolCallback>
    fun listServers(): List<McpServer>
    fun getStatus(serverName: String): McpServerStatus?
}
```

## DefaultMcpManager 구현

### 내부 구조

```kotlin
class DefaultMcpManager(
    private val connectionTimeoutMs: Long = 30_000
) : McpManager, AutoCloseable {

    // 등록된 서버 설정
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
   └── servers에 저장, 상태 = PENDING

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
fun mcpManager(): McpManager = DefaultMcpManager()

@Bean
fun agentExecutor(..., mcpManager: McpManager, ...): AgentExecutor =
    SpringAiAgentExecutor(
        ...,
        mcpToolCallbacks = { mcpManager.getAllToolCallbacks() },
        ...
    )
```

## 사용 예시

### 서버 등록 및 연결

```kotlin
@Service
class McpSetup(private val mcpManager: McpManager) {

    @PostConstruct
    fun setup() {
        // 파일시스템 MCP 서버
        mcpManager.register(McpServer(
            name = "filesystem",
            transportType = McpTransportType.STDIO,
            config = mapOf(
                "command" to "npx",
                "args" to listOf("-y", "@modelcontextprotocol/server-filesystem", "/data")
            )
        ))

        // GitHub MCP 서버
        mcpManager.register(McpServer(
            name = "github",
            transportType = McpTransportType.STDIO,
            config = mapOf(
                "command" to "npx",
                "args" to listOf("-y", "@modelcontextprotocol/server-github"),
            )
        ))

        // 연결
        runBlocking {
            mcpManager.connect("filesystem")
            mcpManager.connect("github")
        }
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
```

### REST API로 MCP 서버 관리

`ChatController`에는 MCP 관리 엔드포인트가 없습니다. 필요하면 직접 추가하세요:

```kotlin
@RestController
@RequestMapping("/api/mcp")
class McpController(private val mcpManager: McpManager) {

    @PostMapping("/servers")
    suspend fun register(@RequestBody server: McpServer) {
        mcpManager.register(server)
        mcpManager.connect(server.name)
    }

    @GetMapping("/servers")
    fun listServers() = mcpManager.listServers().map {
        mapOf("name" to it.name, "status" to mcpManager.getStatus(it.name))
    }

    @DeleteMapping("/servers/{name}")
    suspend fun disconnect(@PathVariable name: String) {
        mcpManager.disconnect(name)
    }
}
```

## 종료 처리

`DefaultMcpManager`는 `AutoCloseable`을 구현합니다:

```kotlin
override fun close() {
    for (serverName in clients.keys.toList()) {
        disconnectInternal(serverName)  // non-suspend (runBlocking 회피)
    }
    servers.clear()
    statuses.clear()
    serverMutexes.clear()
}
```

**주의:** `close()`는 `disconnectInternal()`을 직접 호출합니다 (Mutex 없음, non-suspend). `AutoCloseable.close()`가 `suspend fun`일 수 없기 때문입니다. Spring 컨텍스트 종료 시 자동 호출됩니다.

## 알려진 제약사항

| 제약사항 | 설명 | 대안 |
|----------|------|------|
| HTTP 트랜스포트 미지원 | MCP SDK 0.10.0에 Streamable HTTP 미포함 | SSE 사용 |
| 동기 클라이언트 | `McpSyncClient` 사용 (비동기 미지원) | 코루틴 내에서 블로킹 가능 |
| 도구 캐시 | 연결 시 한 번만 로드 | 재연결 시 갱신됨 |
| ImageContent | 텍스트 플레이스홀더로 변환 | 멀티모달 지원 시 확장 필요 |
