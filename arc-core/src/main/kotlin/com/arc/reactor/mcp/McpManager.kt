package com.arc.reactor.mcp

import com.arc.reactor.agent.config.McpReconnectionProperties
import com.arc.reactor.mcp.model.McpServer
import com.arc.reactor.mcp.model.McpServerStatus
import com.arc.reactor.support.throwIfCancellation
import com.arc.reactor.tool.ToolCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * MCP (Model Context Protocol) server manager.
 */
interface McpManager {
    fun register(server: McpServer)

    fun syncRuntimeServer(server: McpServer)

    suspend fun unregister(serverName: String)

    suspend fun connect(serverName: String): Boolean

    suspend fun disconnect(serverName: String)

    fun getAllToolCallbacks(): List<ToolCallback>

    fun getToolCallbacks(serverName: String): List<ToolCallback>

    fun listServers(): List<McpServer>

    fun getStatus(serverName: String): McpServerStatus?

    suspend fun ensureConnected(serverName: String): Boolean

    suspend fun initializeFromStore()

    fun reapplySecurityPolicy() {}
}

/**
 * MCP security configuration.
 *
 * @param allowedServerNames Allowlist of MCP server names. Empty = allow all.
 * @param maxToolOutputLength Maximum characters in tool output before truncation.
 */
data class McpSecurityConfig(
    val allowedServerNames: Set<String> = emptySet(),
    val maxToolOutputLength: Int = 50_000
)

/**
 * Default MCP manager implementation.
 *
 * Responsibilities:
 * - runtime registry of servers/status/tool-callbacks
 * - connection orchestration and lifecycle
 * - store synchronization delegation
 * - reconnection scheduling delegation
 */
class DefaultMcpManager(
    private val connectionTimeoutMs: Long = 30_000,
    private val securityConfig: McpSecurityConfig = McpSecurityConfig(),
    private val securityConfigProvider: () -> McpSecurityConfig = { securityConfig },
    private val store: McpServerStore? = null,
    private val reconnectionProperties: McpReconnectionProperties = McpReconnectionProperties()
) : McpManager, AutoCloseable {

    private val servers = ConcurrentHashMap<String, McpServer>()
    private val clients = ConcurrentHashMap<String, io.modelcontextprotocol.client.McpSyncClient>()
    private val toolCallbacksCache = ConcurrentHashMap<String, List<ToolCallback>>()
    internal val statuses = ConcurrentHashMap<String, McpServerStatus>()
    private val serverMutexes = ConcurrentHashMap<String, Mutex>()
    private val duplicateToolWarningKeys = ConcurrentHashMap.newKeySet<String>()

    private val reconnectScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val storeSync = McpStoreSync(store)
    private val connectionSupport = McpConnectionSupport(
        connectionTimeoutMs = connectionTimeoutMs,
        maxToolOutputLengthProvider = { currentSecurityConfig().maxToolOutputLength },
        onConnectionError = { serverName -> handleConnectionError(serverName) }
    )
    private val reconnectionCoordinator = McpReconnectionCoordinator(
        scope = reconnectScope,
        properties = reconnectionProperties,
        statusProvider = { serverName -> statuses[serverName] },
        serverExists = { serverName -> servers.containsKey(serverName) },
        reconnectAction = { serverName -> connect(serverName) }
    )

    private fun mutexFor(serverName: String): Mutex = serverMutexes.getOrPut(serverName) { Mutex() }

    private fun currentSecurityConfig(): McpSecurityConfig {
        return runCatching { securityConfigProvider() }
            .getOrElse {
                logger.warn(it) { "Failed to load dynamic MCP security config, using static fallback" }
                securityConfig
            }
    }

    private fun allowedBySecurity(serverName: String): Boolean {
        val allowed = currentSecurityConfig().allowedServerNames
        return allowed.isEmpty() || serverName in allowed
    }

    override fun register(server: McpServer) {
        if (!allowedBySecurity(server.name)) {
            logger.warn { "MCP server rejected by allowlist: ${server.name}" }
            return
        }

        logger.info { "Registering MCP server: ${server.name}" }
        servers[server.name] = server
        statuses[server.name] = McpServerStatus.PENDING
        storeSync.saveIfAbsent(server)
    }

    override fun syncRuntimeServer(server: McpServer) {
        if (!allowedBySecurity(server.name)) {
            logger.warn { "MCP runtime sync rejected by allowlist: ${server.name}" }
            return
        }

        servers[server.name] = server
        statuses.putIfAbsent(server.name, McpServerStatus.PENDING)
        logger.info { "Synchronized MCP runtime config for server: ${server.name}" }
    }

    override suspend fun unregister(serverName: String) {
        disconnectInternal(serverName)
        servers.remove(serverName)
        statuses.remove(serverName)
        serverMutexes.remove(serverName)
        reconnectionCoordinator.clear(serverName)
        storeSync.delete(serverName)
        logger.info { "Unregistered MCP server: $serverName" }
    }

    override suspend fun initializeFromStore() {
        val storeServers = storeSync.loadAll()
        if (storeServers.isEmpty()) {
            logger.debug { "No MCP servers found in store" }
            return
        }

        logger.info { "Loading ${storeServers.size} MCP servers from store" }
        for (server in storeServers) {
            if (!allowedBySecurity(server.name)) {
                logger.warn { "Skipping stored MCP server rejected by allowlist: ${server.name}" }
                continue
            }
            servers[server.name] = server
            statuses[server.name] = McpServerStatus.PENDING

            if (server.autoConnect) {
                try {
                    connect(server.name)
                } catch (e: Exception) {
                    e.throwIfCancellation()
                    logger.warn(e) { "Failed to auto-connect MCP server '${server.name}'" }
                }
            }
        }
    }

    override suspend fun connect(serverName: String): Boolean {
        val server = servers[serverName] ?: run {
            logger.warn { "MCP server not found: $serverName" }
            return false
        }

        return mutexFor(serverName).withLock {
            try {
                logger.info { "Connecting to MCP server: $serverName" }
                statuses[serverName] = McpServerStatus.CONNECTING

                val handle = connectionSupport.open(server)
                if (handle == null) {
                    statuses[serverName] = McpServerStatus.FAILED
                    reconnectionCoordinator.schedule(serverName)
                    return@withLock false
                }

                clients[serverName] = handle.client
                toolCallbacksCache[serverName] = handle.tools
                statuses[serverName] = McpServerStatus.CONNECTED
                reconnectionCoordinator.clear(serverName)
                logger.info { "MCP server connected: $serverName with ${handle.tools.size} tools" }
                true
            } catch (e: Exception) {
                e.throwIfCancellation()
                logger.error(e) { "Failed to connect MCP server: $serverName" }
                statuses[serverName] = McpServerStatus.FAILED
                reconnectionCoordinator.schedule(serverName)
                false
            }
        }
    }

    override suspend fun disconnect(serverName: String) {
        mutexFor(serverName).withLock {
            disconnectInternal(serverName)
        }
    }

    private fun disconnectInternal(serverName: String) {
        logger.info { "Disconnecting MCP server: $serverName" }
        reconnectionCoordinator.clear(serverName)

        clients.remove(serverName)?.let { client ->
            connectionSupport.close(serverName, client)
        }

        toolCallbacksCache.remove(serverName)
        statuses[serverName] = McpServerStatus.DISCONNECTED
    }

    /**
     * Called by [McpToolCallback] when a tool call fails with a connection error.
     * Evicts the stale client and schedules reconnection without blocking.
     */
    internal fun handleConnectionError(serverName: String) {
        if (statuses[serverName] != McpServerStatus.CONNECTED) return
        logger.warn { "MCP connection error detected on tool call for '$serverName' — marking FAILED and scheduling reconnection" }
        clients.remove(serverName)
        toolCallbacksCache.remove(serverName)
        statuses[serverName] = McpServerStatus.FAILED
        reconnectionCoordinator.schedule(serverName)
    }

    override fun reapplySecurityPolicy() {
        val blocked = servers.keys.filterNot(::allowedBySecurity)
        blocked.forEach { serverName ->
            logger.info { "Evicting MCP server from runtime due to allowlist change: $serverName" }
            disconnectInternal(serverName)
            servers.remove(serverName)
            statuses.remove(serverName)
            serverMutexes.remove(serverName)
        }

        storeSync.loadAll()
            .filter(::shouldLoadStoredServer)
            .filterNot { servers.containsKey(it.name) }
            .forEach { server ->
                logger.info { "Loading newly allowlisted MCP server into runtime: ${server.name}" }
                servers[server.name] = server
                statuses[server.name] = McpServerStatus.PENDING
                if (server.autoConnect) {
                    reconnectScope.launch {
                        try {
                            connect(server.name)
                        } catch (e: Exception) {
                            e.throwIfCancellation()
                            logger.warn(e) { "Failed to auto-connect newly allowlisted MCP server '${server.name}'" }
                        }
                    }
                }
            }
    }

    private fun shouldLoadStoredServer(server: McpServer): Boolean = allowedBySecurity(server.name)

    override suspend fun ensureConnected(serverName: String): Boolean {
        val status = statuses[serverName]
        if (status == McpServerStatus.CONNECTED) return true
        if (status != McpServerStatus.FAILED && status != McpServerStatus.DISCONNECTED) return false
        if (!reconnectionProperties.enabled) return false

        logger.info { "On-demand reconnection attempt for MCP server: $serverName" }
        return connect(serverName)
    }

    override fun getAllToolCallbacks(): List<ToolCallback> {
        return deduplicateCallbacksByName(toolCallbacksCache) { toolName, keptServer, droppedServer ->
            val warningKey = "$toolName|$keptServer|$droppedServer"
            if (duplicateToolWarningKeys.add(warningKey)) {
                logger.warn {
                    "Duplicate MCP tool name '$toolName' detected across servers. " +
                        "Keeping '$keptServer', ignoring '$droppedServer'."
                }
            }
        }
    }

    override fun getToolCallbacks(serverName: String): List<ToolCallback> {
        return toolCallbacksCache[serverName] ?: emptyList()
    }

    override fun listServers(): List<McpServer> {
        return storeSync.listOr(servers.values)
    }

    override fun getStatus(serverName: String): McpServerStatus? {
        return statuses[serverName]
    }

    override fun close() {
        logger.info { "Closing MCP Manager, disconnecting all servers" }
        reconnectScope.cancel()
        reconnectionCoordinator.clearAll()
        for (serverName in clients.keys.toList()) {
            disconnectInternal(serverName)
        }
        servers.clear()
        statuses.clear()
        serverMutexes.clear()
    }
}

internal fun deduplicateCallbacksByName(
    toolCallbacksByServer: Map<String, List<ToolCallback>>,
    onDuplicate: (toolName: String, keptServer: String, droppedServer: String) -> Unit = { _, _, _ -> }
): List<ToolCallback> {
    if (toolCallbacksByServer.isEmpty()) return emptyList()

    val selectedByToolName = LinkedHashMap<String, Pair<String, ToolCallback>>()
    for (serverName in toolCallbacksByServer.keys.sorted()) {
        val callbacks = toolCallbacksByServer[serverName].orEmpty()
        for (callback in callbacks) {
            val existing = selectedByToolName[callback.name]
            if (existing == null) {
                selectedByToolName[callback.name] = serverName to callback
            } else {
                onDuplicate(callback.name, existing.first, serverName)
            }
        }
    }
    return selectedByToolName.values.map { it.second }
}
