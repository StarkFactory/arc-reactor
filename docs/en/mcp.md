# MCP Integration Guide

> **Key files:** `McpManager.kt`, `McpModels.kt`
> This document explains Arc Reactor's MCP (Model Context Protocol) integration.

## What is MCP?

Model Context Protocol is a standard protocol for AI agents to connect to external tools and data sources.

**Problems MCP solves:**
- Dynamically load tools from external MCP servers instead of implementing them directly in code
- Share a single MCP server across multiple agents/frameworks
- Standardized tool invocation interface

```
Arc Reactor Agent
    |
    +-- Local Tools (@Tool annotation)
    |
    +-- MCP Tools (dynamically loaded from external servers)
            |
            +-- filesystem MCP server -> file read/write
            +-- database MCP server   -> SQL queries
            +-- slack MCP server      -> send messages
```

## Supported Transports

| Transport | Communication | Config Fields | Status |
|-----------|---------------|---------------|--------|
| **STDIO** | Local process stdin/stdout | `command`, `args` | Implemented |
| **SSE** | Server-Sent Events over HTTP | `url` | Implemented |
| **HTTP** | Streamable HTTP | `url` | Not supported by MCP SDK (reserved) |

### STDIO -- Local Process

The most common approach. Runs an MCP server as a local process and communicates via stdin/stdout.

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

### SSE -- Remote Server

Connects to a remotely running MCP server via HTTP SSE.

```kotlin
McpServer(
    name = "remote-db",
    transportType = McpTransportType.SSE,
    config = mapOf(
        "url" to "http://mcp-server.internal:3000/sse"
    )
)
```

## Core Models

### McpServer -- Server Configuration

```kotlin
data class McpServer(
    val name: String,                          // Unique server name (identifier)
    val description: String? = null,           // Description
    val transportType: McpTransportType,       // STDIO | SSE | HTTP
    val config: Map<String, Any> = emptyMap(), // Transport-specific configuration
    val version: String? = null,               // Server version (default: "1.0.0")
    val autoConnect: Boolean = false           // Auto-connect on registration
)
```

**config fields (per transport):**

| Transport | Required Fields | Description |
|-----------|-----------------|-------------|
| STDIO | `command: String` | Command to execute (e.g., `"npx"`, `"node"`) |
| STDIO | `args: List<String>` (optional) | Command arguments |
| SSE | `url: String` | SSE endpoint URL |

### McpServerStatus -- Connection Status

```kotlin
enum class McpServerStatus {
    PENDING,       // Registered, not yet connected
    CONNECTING,    // Connection in progress
    CONNECTED,     // Connected (tools loaded)
    DISCONNECTED,  // Disconnected
    FAILED,        // Connection failed
    DISABLED       // Disabled
}
```

**State transitions:**

```
register() -> PENDING
connect()  -> CONNECTING -> CONNECTED
                         -> FAILED (on error)
disconnect() -> DISCONNECTED
```

## McpManager Interface

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

## DefaultMcpManager Implementation

### Internal Structure

```kotlin
class DefaultMcpManager(
    private val connectionTimeoutMs: Long = 30_000
) : McpManager, AutoCloseable {

    // Registered server configurations
    private val servers = ConcurrentHashMap<String, McpServer>()

    // Connected MCP clients
    private val clients = ConcurrentHashMap<String, McpSyncClient>()

    // Loaded tool cache
    private val toolCallbacksCache = ConcurrentHashMap<String, List<ToolCallback>>()

    // Per-server status
    private val statuses = ConcurrentHashMap<String, McpServerStatus>()

    // Per-server Mutex (ensures connect/disconnect atomicity)
    private val serverMutexes = ConcurrentHashMap<String, Mutex>()
}
```

### Concurrency Safety

- **ConcurrentHashMap**: Used for all internal maps. Thread-safe reads and writes.
- **Per-server Mutex**: Ensures that connect/disconnect operations on the same server do not run concurrently.

```kotlin
private fun mutexFor(serverName: String): Mutex =
    serverMutexes.getOrPut(serverName) { Mutex() }

// Both connect() and disconnect() are protected by Mutex
mutexFor(serverName).withLock {
    // connect/disconnect logic
}
```

### Connection Flow

```
1. register(McpServer)
   +-- Save to servers, status = PENDING

2. connect(serverName)
   +-- Acquire Mutex
   +-- status = CONNECTING
   +-- Create transport (STDIO/SSE)
   |   +-- STDIO: ServerParameters -> StdioClientTransport -> McpClient.sync()
   |   +-- SSE: HttpClientSseClientTransport -> McpClient.sync()
   +-- client.initialize() -- MCP handshake
   +-- loadToolCallbacks() -- Request and cache tool list
   +-- status = CONNECTED
   +-- Release Mutex

3. getAllToolCallbacks()
   +-- toolCallbacksCache.values.flatten() -- Return immediately from cache

4. disconnect(serverName)
   +-- Acquire Mutex
   +-- client.closeGracefully() -- Graceful shutdown
   |   +-- On failure: client.close() -- Forced shutdown
   +-- Remove from cache
   +-- status = DISCONNECTED
   +-- Release Mutex
```

### Tool Loading

```kotlin
private fun loadToolCallbacks(client: McpSyncClient, serverName: String): List<ToolCallback> {
    val toolsResult = client.listTools()       // MCP protocol: tools/list request
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

Calls the MCP server's `tools/list` endpoint to retrieve the list of available tools. Each tool is wrapped in an `McpToolCallback` for compatibility with Arc Reactor's `ToolCallback` interface.

## McpToolCallback -- MCP Tool Wrapper

Wraps an MCP tool into Arc Reactor's `ToolCallback` interface.

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

**Content type handling:**
- `TextContent` -- Returns the text as-is
- `ImageContent` -- `[Image: mime/type]` placeholder
- `EmbeddedResource` -- `[Resource: uri]` placeholder

## Integration with the Agent

### Tool Collection Flow

When `SpringAiAgentExecutor` collects tools:

```kotlin
private fun selectAndPrepareTools(userPrompt: String): List<Any> {
    val localToolInstances = localTools.toList()          // @Tool annotation tools
    val allCallbacks = toolCallbacks + mcpToolCallbacks() // ToolCallback + MCP tools
    val selectedCallbacks = toolSelector?.select(userPrompt, allCallbacks) ?: allCallbacks
    val wrappedCallbacks = selectedCallbacks.map { ArcToolCallbackAdapter(it) }
    return (localToolInstances + wrappedCallbacks).take(maxToolsPerRequest)
}
```

`mcpToolCallbacks` is injected as a `{ mcpManager.getAllToolCallbacks() }` lambda. Since it is called on every request, adding or removing MCP servers at runtime is reflected immediately.

### Auto-Configuration

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

## Usage Examples

### Server Registration and Connection

```kotlin
@Service
class McpSetup(private val mcpManager: McpManager) {

    @PostConstruct
    fun setup() {
        // Filesystem MCP server
        mcpManager.register(McpServer(
            name = "filesystem",
            transportType = McpTransportType.STDIO,
            config = mapOf(
                "command" to "npx",
                "args" to listOf("-y", "@modelcontextprotocol/server-filesystem", "/data")
            )
        ))

        // GitHub MCP server
        mcpManager.register(McpServer(
            name = "github",
            transportType = McpTransportType.STDIO,
            config = mapOf(
                "command" to "npx",
                "args" to listOf("-y", "@modelcontextprotocol/server-github"),
            )
        ))

        // Connect
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

### Runtime Server Management

```kotlin
// Check server status
val status = mcpManager.getStatus("filesystem")  // CONNECTED

// Query tools from a specific server
val fsTools = mcpManager.getToolCallbacks("filesystem")

// Query tools from all servers
val allTools = mcpManager.getAllToolCallbacks()

// List servers
val servers = mcpManager.listServers()

// Disconnect at runtime
runBlocking { mcpManager.disconnect("github") }
```

### Managing MCP Servers via REST API

`ChatController` does not include MCP management endpoints. Add them yourself if needed:

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

## Shutdown Handling

`DefaultMcpManager` implements `AutoCloseable`:

```kotlin
override fun close() {
    for (serverName in clients.keys.toList()) {
        disconnectInternal(serverName)  // non-suspend (avoids runBlocking)
    }
    servers.clear()
    statuses.clear()
    serverMutexes.clear()
}
```

**Note:** `close()` calls `disconnectInternal()` directly (no Mutex, non-suspend). This is because `AutoCloseable.close()` cannot be a `suspend fun`. It is called automatically during Spring context shutdown.

## Known Limitations

| Limitation | Description | Workaround |
|------------|-------------|------------|
| HTTP transport not supported | Streamable HTTP not included in MCP SDK 0.10.0 | Use SSE |
| Synchronous client | Uses `McpSyncClient` (async not supported) | May block within coroutines |
| Tool cache | Tools loaded only once at connection time | Refreshed on reconnection |
| ImageContent | Converted to text placeholder | Requires extension for multimodal support |
