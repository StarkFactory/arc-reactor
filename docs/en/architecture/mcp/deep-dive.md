# MCP Integration Guide

> **Key files:** `McpManager.kt`, `McpModels.kt`, `McpServerStore.kt`, `McpServerController.kt`, `McpStartupInitializer.kt`
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
            +-- issue tracker MCP server -> ticket search/update
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
    val id: String = UUID.randomUUID().toString(), // Unique ID (auto-generated)
    val name: String,                              // Unique server name (identifier)
    val description: String? = null,               // Description
    val transportType: McpTransportType,           // STDIO | SSE | HTTP
    val config: Map<String, Any> = emptyMap(),     // Transport-specific configuration
    val version: String? = null,                   // Server version (default: "1.0.0")
    val autoConnect: Boolean = false,              // Auto-connect on startup
    val createdAt: Instant = Instant.now(),        // Creation timestamp
    val updatedAt: Instant = Instant.now()         // Last update timestamp
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
                         -> FAILED (on error) -> [auto-reconnection] -> CONNECTED
disconnect() -> DISCONNECTED
ensureConnected() -> CONNECTED (on-demand reconnect for FAILED/DISCONNECTED)
```

## McpManager Interface

```kotlin
interface McpManager {
    fun register(server: McpServer)
    suspend fun unregister(serverName: String)        // Disconnect + remove from store
    suspend fun connect(serverName: String): Boolean
    suspend fun disconnect(serverName: String)
    fun getAllToolCallbacks(): List<ToolCallback>
    fun getToolCallbacks(serverName: String): List<ToolCallback>
    fun listServers(): List<McpServer>
    fun getStatus(serverName: String): McpServerStatus?
    suspend fun ensureConnected(serverName: String): Boolean  // On-demand reconnect
    suspend fun initializeFromStore()                  // Load from store + auto-connect
}
```

## DefaultMcpManager Implementation

### Internal Structure

```kotlin
class DefaultMcpManager(
    private val connectionTimeoutMs: Long = 30_000,
    private val securityConfig: McpSecurityConfig = McpSecurityConfig(),
    private val store: McpServerStore? = null          // Optional persistence
) : McpManager, AutoCloseable {

    // Runtime server cache (fallback when no store)
    private val servers = ConcurrentHashMap<String, McpServer>()

    // Connected MCP clients
    private val clients = ConcurrentHashMap<String, McpSyncClient>()

    // Loaded tool cache
    private val toolCallbacksCache = ConcurrentHashMap<String, List<ToolCallback>>()

    // Per-server status
    private val statuses = ConcurrentHashMap<String, McpServerStatus>()

    // Per-server Mutex (ensures connect/disconnect atomicity)
    private val serverMutexes = ConcurrentHashMap<String, Mutex>()

    // Duplicate MCP tool warning dedup cache (avoid repeated logs)
    private val duplicateToolWarningKeys = ConcurrentHashMap.newKeySet<String>()
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
   +-- Allowlist check (reject if not in securityConfig.allowedServerNames)
   +-- Save to servers, status = PENDING
   +-- Persist to store if available (skip if already exists)

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
   +-- deduplicateCallbacksByName(toolCallbacksCache)
       +-- Iterate servers in sorted name order
       +-- Keep first callback for each tool name
       +-- Drop duplicates and log warning once per kept/dropped pair
   +-- Return deduplicated callbacks from cache

4. disconnect(serverName)
   +-- Acquire Mutex
   +-- client.closeGracefully() -- Graceful shutdown
   |   +-- On failure: client.close() -- Forced shutdown
   +-- Remove from cache
   +-- status = DISCONNECTED
   +-- Release Mutex

5. unregister(serverName) -- NEW
   +-- disconnectInternal() -- Disconnect if connected
   +-- Remove from servers, statuses, mutexes
   +-- Delete from store if available

6. initializeFromStore() -- NEW
   +-- Load all servers from store
   +-- Register each into runtime cache
   +-- Auto-connect servers with autoConnect=true
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
    val allCallbacks = deduplicateCallbacks(toolCallbacks + mcpToolCallbacks()) // ToolCallback + MCP tools
    val selectedCallbacks = toolSelector?.select(userPrompt, allCallbacks) ?: allCallbacks
    val wrappedCallbacks = selectedCallbacks.map { ArcToolCallbackAdapter(it) }
    return (localToolInstances + wrappedCallbacks).take(maxToolsPerRequest)
}
```

`mcpToolCallbacks` is injected as a `{ mcpManager.getAllToolCallbacks() }` lambda. Since it is called on every request, adding or removing MCP servers at runtime is reflected immediately.
`getAllToolCallbacks()` returns callbacks deduplicated by tool name across servers. For collisions, the callback from the lexicographically first server name is kept.

### Auto-Configuration

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

When PostgreSQL is available (`-Pdb=true`), `JdbcMcpServerStore` is registered as `@Primary`, overriding the in-memory default.

## Usage Examples

### Option 1: REST API (Recommended)

```bash
# Register via API
curl -X POST http://localhost:8080/api/mcp/servers \
  -H "Content-Type: application/json" \
  -d '{"name":"filesystem","transportType":"STDIO","config":{"command":"npx","args":["-y","@modelcontextprotocol/server-filesystem","/data"]}}'

# List servers
curl http://localhost:8080/api/mcp/servers

# Disconnect
curl -X POST http://localhost:8080/api/mcp/servers/filesystem/disconnect
```

### Option 2: Startup Recovery from Store

After servers are registered via REST API, their metadata is persisted in `McpServerStore`.
On startup, Arc Reactor restores them from the store and auto-connects entries with `autoConnect=true`.

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

// Unregister (disconnect + remove from store)
runBlocking { mcpManager.unregister("github") }
```

## Server Persistence -- McpServerStore

MCP server configurations can be persisted for recovery across restarts.

### Interface

```kotlin
interface McpServerStore {
    fun list(): List<McpServer>
    fun findByName(name: String): McpServer?
    fun save(server: McpServer): McpServer
    fun update(name: String, server: McpServer): McpServer?
    fun delete(name: String)
}
```

### Implementations

| Implementation | Storage | When Used |
|----------------|---------|-----------|
| `InMemoryMcpServerStore` | ConcurrentHashMap | Default (data lost on restart) |
| `JdbcMcpServerStore` | PostgreSQL | When `-Pdb=true` build flag is set |

`JdbcMcpServerStore` is auto-configured with `@Primary` when a DataSource is available, following the same pattern as `JdbcMemoryStore` and `JdbcPersonaStore`.

### Database Schema (Flyway V7)

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

The `config` column stores transport-specific configuration as JSON text (serialized via ObjectMapper).

## Startup Recovery (Store-Driven)

MCP server registration is handled via REST API (`/api/mcp/servers`).
At startup, Arc Reactor restores previously registered servers from `McpServerStore`.

### Startup Flow (McpStartupInitializer)

```
ApplicationReadyEvent
  |
  v
1. mcpManager.initializeFromStore()
   +-- Load all servers from store
   +-- Register each into runtime cache
   +-- Auto-connect servers with autoConnect=true
```

## REST API -- Dynamic Server Management

Built-in REST API for managing MCP servers at runtime. Authentication is required for all endpoints, and ADMIN role is required for all operations.

### Endpoints

| Method | Path | Description | Auth |
|--------|------|-------------|------|
| `GET` | `/api/mcp/servers` | List all servers with status | Admin |
| `POST` | `/api/mcp/servers` | Register + auto-connect | Admin |
| `GET` | `/api/mcp/servers/{name}` | Server details + tool list | Admin |
| `PUT` | `/api/mcp/servers/{name}` | Update config | Admin |
| `DELETE` | `/api/mcp/servers/{name}` | Disconnect + remove | Admin |
| `POST` | `/api/mcp/servers/{name}/connect` | Connect | Admin |
| `POST` | `/api/mcp/servers/{name}/disconnect` | Disconnect (keep config) | Admin |

### Register a Server

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

Response (201 Created):
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

### List Servers

```bash
curl http://localhost:8080/api/mcp/servers
```

### Get Server Details (with tool list)

```bash
curl http://localhost:8080/api/mcp/servers/swagger-agent
```

Response:
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

### Connect / Disconnect

```bash
# Connect
curl -X POST http://localhost:8080/api/mcp/servers/swagger-agent/connect

# Disconnect (keeps config, can reconnect later)
curl -X POST http://localhost:8080/api/mcp/servers/swagger-agent/disconnect

# Remove entirely
curl -X DELETE http://localhost:8080/api/mcp/servers/swagger-agent
```

## Auto-Reconnection

When an MCP server connection fails, `DefaultMcpManager` can automatically retry with exponential backoff. This is enabled by default.

### Configuration

```yaml
arc:
  reactor:
    mcp:
      reconnection:
        enabled: true           # Enable auto-reconnection (default: true)
        max-attempts: 5         # Max retry attempts before giving up
        initial-delay-ms: 5000  # Initial backoff delay
        multiplier: 2.0         # Backoff multiplier
        max-delay-ms: 60000     # Maximum backoff delay
```

### How It Works

```
connect() fails
  |
  v
scheduleReconnection()
  |-- Check: reconnection.enabled?  No -> return
  |-- Check: already reconnecting?  Yes -> return (prevent duplicates)
  |
  v
Background coroutine (Dispatchers.IO + SupervisorJob)
  |
  for attempt in 1..maxAttempts:
    |-- Calculate delay: min(initialDelay * multiplier^(attempt-1), maxDelay)
    |-- Add jitter: ±25% randomization
    |-- delay(...)
    |-- Check: server unregistered or already connected? -> exit
    |-- connect(serverName)
    |   |-- Success -> exit (reconnected!)
    |   |-- Failure -> continue to next attempt
    |
  All attempts exhausted -> log warning, give up
```

### On-Demand Reconnection

When a tool call targets a disconnected/failed server, `ensureConnected()` attempts a single synchronous reconnect before the call:

```kotlin
// Called before MCP tool execution
suspend fun ensureConnected(serverName: String): Boolean
```

- Returns `true` if server is CONNECTED (already or after reconnect)
- Returns `false` if reconnection is disabled, server is in PENDING/CONNECTING state, or reconnect fails

### Thread Safety

- `ConcurrentHashMap.newKeySet<String>()` tracks which servers have active reconnection tasks
- `CoroutineScope(Dispatchers.IO + SupervisorJob())` isolates reconnection failures
- Per-server `Mutex` ensures connect/disconnect atomicity (same as before)
- `close()` cancels all background reconnection tasks

## Shutdown Handling

`DefaultMcpManager` implements `AutoCloseable`:

```kotlin
override fun close() {
    reconnectScope.cancel()        // Cancel all background reconnection tasks
    reconnectingServers.clear()
    for (serverName in clients.keys.toList()) {
        disconnectInternal(serverName)  // non-suspend (avoids runBlocking)
    }
    servers.clear()
    statuses.clear()
    serverMutexes.clear()
}
```

**Note:** `close()` first cancels the reconnection scope, then calls `disconnectInternal()` directly (no Mutex, non-suspend). `AutoCloseable.close()` cannot be a `suspend fun`. It is called automatically during Spring context shutdown.

## Known Limitations

| Limitation | Description | Workaround |
|------------|-------------|------------|
| HTTP transport not supported | Streamable HTTP not included in MCP SDK 0.17.2 path used here | Use SSE |
| Synchronous client | Uses `McpSyncClient` (async not supported) | May block within coroutines |
| Tool cache | Tools loaded only once at connection time | Refreshed on reconnection |
| ImageContent | Converted to text placeholder | Requires extension for multimodal support |
