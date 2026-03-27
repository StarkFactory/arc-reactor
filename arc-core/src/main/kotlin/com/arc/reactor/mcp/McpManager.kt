package com.arc.reactor.mcp

import com.arc.reactor.agent.config.McpReconnectionProperties
import com.arc.reactor.mcp.model.McpServer
import com.arc.reactor.mcp.model.McpServerStatus
import com.arc.reactor.support.throwIfCancellation
import com.arc.reactor.tool.ToolCallback
import io.micrometer.core.instrument.MeterRegistry
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
 * MCP (Model Context Protocol) 서버 매니저 인터페이스.
 *
 * MCP 서버의 등록, 연결, 연결 해제, 도구 콜백 관리를 담당한다.
 *
 * WHY: MCP 서버 수명주기를 단일 인터페이스로 추상화하여
 * 에이전트 실행기가 MCP 연결 세부사항을 알 필요 없이 도구를 사용할 수 있게 한다.
 *
 * @see DefaultMcpManager 기본 구현
 * @see McpConnectionSupport 전송 연결 지원
 * @see McpReconnectionCoordinator 백그라운드 재연결 스케줄러
 */
interface McpManager {
    /** MCP 서버를 레지스트리에 등록한다 (연결은 별도) */
    fun register(server: McpServer)

    /** 런타임 서버 설정을 동기화한다 (스토어에서 로딩된 서버용) */
    fun syncRuntimeServer(server: McpServer)

    /** MCP 서버를 등록 해제하고 연결을 끊는다 */
    suspend fun unregister(serverName: String)

    /** MCP 서버에 연결한다 */
    suspend fun connect(serverName: String): Boolean

    /** MCP 서버 연결을 끊는다 */
    suspend fun disconnect(serverName: String)

    /** 모든 연결된 서버의 도구 콜백을 반환한다 */
    fun getAllToolCallbacks(): List<ToolCallback>

    /** 특정 서버의 도구 콜백을 반환한다 */
    fun getToolCallbacks(serverName: String): List<ToolCallback>

    /** 등록된 모든 서버 목록을 반환한다 */
    fun listServers(): List<McpServer>

    /** 서버의 현재 상태를 반환한다 */
    fun getStatus(serverName: String): McpServerStatus?

    /** 연결이 끊어진 서버에 대해 온디맨드 재연결을 시도한다 */
    suspend fun ensureConnected(serverName: String): Boolean

    /** 스토어에서 서버 목록을 로딩하고 autoConnect=true인 서버에 연결한다 */
    suspend fun initializeFromStore()

    /** 보안 정책 변경 후 허용 목록을 재적용한다 */
    fun reapplySecurityPolicy() {}
}

/**
 * MCP 보안 설정.
 *
 * @param allowedServerNames MCP 서버 이름 허용 목록. 빈 집합 = 모두 허용.
 * @param maxToolOutputLength 잘라내기 전 도구 출력 최대 문자 수.
 * @param allowedStdioCommands STDIO 전송에 허용된 명령어 집합.
 *
 * @see McpSecurityPolicyProvider 동적 보안 정책 제공자
 */
data class McpSecurityConfig(
    val allowedServerNames: Set<String> = emptySet(),
    val maxToolOutputLength: Int = DEFAULT_MAX_TOOL_OUTPUT_LENGTH,
    val allowedStdioCommands: Set<String> = DEFAULT_ALLOWED_STDIO_COMMANDS
) {
    companion object {
        /** MCP 도구 출력의 기본 최대 문자 수 */
        const val DEFAULT_MAX_TOOL_OUTPUT_LENGTH = 50_000

        /**
         * MCP 서버용으로 알려진 안전한 STDIO 실행 파일의 기본 집합.
         * WHY: 임의 명령 실행을 방지하기 위해 화이트리스트 방식으로 허용 명령을 제한한다.
         */
        val DEFAULT_ALLOWED_STDIO_COMMANDS: Set<String> = setOf(
            "npx", "node", "python", "python3", "uvx", "uv",
            "docker", "deno", "bun"
        )
    }
}

/**
 * 기본 MCP 매니저 구현.
 *
 * 책임:
 * - 서버/상태/도구 콜백의 런타임 레지스트리 관리
 * - 연결 조율 및 수명주기 관리
 * - 스토어 동기화 위임
 * - 재연결 스케줄링 위임
 *
 * ## MCP 서버 수명주기
 * 1. register() — 서버를 레지스트리에 등록 (상태: PENDING)
 * 2. connect() — 전송 연결 수립 + 도구 로딩 (상태: CONNECTING -> CONNECTED/FAILED)
 * 3. 도구 호출 중 연결 오류 감지 시 → handleConnectionError() → 재연결 스케줄링
 * 4. disconnect() — 정상적 연결 해제 (상태: DISCONNECTED)
 * 5. unregister() — 레지스트리에서 제거
 *
 * WHY: MCP 서버 수명주기의 모든 복잡성을 한 곳에서 관리한다.
 * 서버별 뮤텍스로 동시 연결/해제 충돌을 방지하고,
 * 스냅샷 캐싱으로 도구 목록 조회 성능을 최적화한다.
 *
 * @param connectionTimeoutMs 연결 타임아웃 (밀리초)
 * @param securityConfig 정적 보안 설정
 * @param securityConfigProvider 동적 보안 설정 제공자 (정적 설정보다 우선)
 * @param store 서버 설정 영속 스토어 (선택)
 * @param reconnectionProperties 재연결 설정
 * @param allowPrivateAddresses 프라이빗 주소 허용 여부 (SSRF 방지 관련)
 * @param meterRegistry Micrometer 레지스트리 (선택). null이면 연결 메트릭을 기록하지 않는다.
 * @see McpConnectionSupport 전송 연결 처리
 * @see McpReconnectionCoordinator 백그라운드 재연결
 * @see McpStoreSync 스토어 동기화
 */
class DefaultMcpManager(
    private val connectionTimeoutMs: Long = DEFAULT_CONNECTION_TIMEOUT_MS,
    private val securityConfig: McpSecurityConfig = McpSecurityConfig(),
    private val securityConfigProvider: () -> McpSecurityConfig = { securityConfig },
    private val store: McpServerStore? = null,
    private val reconnectionProperties: McpReconnectionProperties = McpReconnectionProperties(),
    private val allowPrivateAddresses: Boolean = false,
    private val meterRegistry: MeterRegistry? = null
) : McpManager, AutoCloseable {

    /** 서버 이름 → 서버 설정 매핑 */
    private val servers = ConcurrentHashMap<String, McpServer>()
    /** 서버 이름 → MCP 클라이언트 매핑 */
    private val clients = ConcurrentHashMap<String, io.modelcontextprotocol.client.McpSyncClient>()
    /** 서버 이름 → 도구 콜백 목록 캐시 */
    private val toolCallbacksCache = ConcurrentHashMap<String, List<ToolCallback>>()
    /** 서버 이름 → 현재 상태 매핑 */
    internal val statuses = ConcurrentHashMap<String, McpServerStatus>()
    /** 서버별 동시 접근 방지 뮤텍스 */
    private val serverMutexes = ConcurrentHashMap<String, Mutex>()
    /** 중복 도구 경고를 한 번만 출력하기 위한 키 집합 */
    private val duplicateToolWarningKeys = ConcurrentHashMap.newKeySet<String>()
    /** 전체 도구 콜백 스냅샷 캐시 — 무효화 시 null로 설정 */
    @Volatile
    private var allToolCallbacksSnapshot: List<ToolCallback>? = null
    /** 스냅샷 동기화를 위한 락 */
    private val toolCallbacksSnapshotLock = Any()

    /** 재연결 코루틴 스코프 — SupervisorJob으로 개별 실패가 전체에 전파되지 않음 */
    private val reconnectScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    /** 스토어 동기화 헬퍼 */
    private val storeSync = McpStoreSync(store)
    /** 전송 연결 및 도구 탐색 지원 */
    private val connectionSupport = McpConnectionSupport(
        connectionTimeoutMs = connectionTimeoutMs,
        maxToolOutputLengthProvider = { currentSecurityConfig().maxToolOutputLength },
        allowPrivateAddresses = allowPrivateAddresses,
        allowedStdioCommandsProvider = { currentSecurityConfig().allowedStdioCommands },
        onConnectionError = { serverName -> handleConnectionError(serverName) },
        meterRegistry = meterRegistry
    )
    /** 백그라운드 재연결 코디네이터 */
    private val reconnectionCoordinator = McpReconnectionCoordinator(
        scope = reconnectScope,
        properties = reconnectionProperties,
        statusProvider = { serverName -> statuses[serverName] },
        serverExists = { serverName -> servers.containsKey(serverName) },
        reconnectAction = { serverName -> connect(serverName) }
    )

    /** 서버 이름에 대한 뮤텍스를 가져오거나 생성한다 */
    private fun mutexFor(serverName: String): Mutex = serverMutexes.getOrPut(serverName) { Mutex() }

    /**
     * 현재 보안 설정을 반환한다. 동적 제공자가 실패하면 정적 폴백을 사용한다.
     * WHY: 운영 중 보안 정책 저장소 접근 실패 시에도 기본 보안을 유지하기 위함.
     */
    private fun currentSecurityConfig(): McpSecurityConfig {
        return runCatching { securityConfigProvider() }
            .getOrElse {
                logger.warn(it) { "동적 MCP 보안 설정 로딩 실패, 정적 폴백 사용" }
                securityConfig
            }
    }

    /**
     * 서버 이름이 보안 허용 목록에 있는지 확인한다.
     * 허용 목록이 비어있으면 모든 서버를 허용한다.
     */
    private fun allowedBySecurity(serverName: String): Boolean {
        val allowed = currentSecurityConfig().allowedServerNames
        return allowed.isEmpty() || serverName in allowed
    }

    /**
     * 보안 허용 목록 체크 + 거부 시 로깅을 통합한 헬퍼.
     * @return true면 허용, false면 거부 (로깅 완료)
     */
    private fun requireSecurityApproval(serverName: String, operation: String): Boolean {
        if (allowedBySecurity(serverName)) return true
        logger.warn { "MCP $operation 이(가) 허용 목록에 의해 거부됨: $serverName" }
        return false
    }

    override fun register(server: McpServer) {
        if (!requireSecurityApproval(server.name, "등록")) return

        logger.info { "MCP 서버 등록: ${server.name}" }
        servers[server.name] = server
        statuses[server.name] = McpServerStatus.PENDING
        storeSync.saveIfAbsent(server)
    }

    override fun syncRuntimeServer(server: McpServer) {
        if (!requireSecurityApproval(server.name, "런타임 동기화")) return

        servers[server.name] = server
        statuses.putIfAbsent(server.name, McpServerStatus.PENDING)
        logger.info { "MCP 런타임 설정 동기화 완료: ${server.name}" }
    }

    override suspend fun unregister(serverName: String) {
        disconnectInternal(serverName)
        servers.remove(serverName)
        statuses.remove(serverName)
        serverMutexes.remove(serverName)
        reconnectionCoordinator.clear(serverName)
        storeSync.delete(serverName)
        logger.info { "MCP 서버 등록 해제: $serverName" }
    }

    /**
     * 스토어에서 MCP 서버 목록을 로딩하고 autoConnect=true인 서버에 연결한다.
     * 허용 목록에 없는 서버는 건너뛴다.
     */
    override suspend fun initializeFromStore() {
        val storeServers = storeSync.loadAll()
        if (storeServers.isEmpty()) {
            logger.debug { "스토어에 MCP 서버 없음" }
            return
        }

        logger.info { "스토어에서 ${storeServers.size}개 MCP 서버 로딩" }
        for (server in storeServers) {
            if (!requireSecurityApproval(server.name, "스토어 로딩")) continue
            servers[server.name] = server
            statuses[server.name] = McpServerStatus.PENDING

            if (server.autoConnect) {
                try {
                    connect(server.name)
                } catch (e: Exception) {
                    e.throwIfCancellation()
                    logger.warn(e) { "MCP 서버 '${server.name}' 자동 연결 실패" }
                }
            }
        }
    }

    /**
     * MCP 서버에 연결한다.
     *
     * 연결 과정:
     * 1. 기존 클라이언트가 있으면 닫는다 (리소스 누수 방지)
     * 2. 상태를 CONNECTING으로 변경한다
     * 3. 전송 연결을 수립하고 도구를 로딩한다
     * 4. 성공 시 CONNECTED, 실패 시 FAILED + 재연결 스케줄링
     *
     * 서버별 뮤텍스로 동일 서버에 대한 동시 연결 시도를 방지한다.
     */
    override suspend fun connect(serverName: String): Boolean {
        val server = servers[serverName] ?: run {
            logger.warn { "MCP 서버를 찾을 수 없음: $serverName" }
            return false
        }

        return mutexFor(serverName).withLock {
            try {
                logger.info { "MCP 서버 연결 중: $serverName" }

                // 리소스 누수 방지를 위해 기존 클라이언트를 먼저 닫는다.
                // toolCallbacksCache는 유지 — 새 연결 성공까지 stale 도구를 제공하여 간헐적 불가용 방지.
                clients.remove(serverName)?.let { oldClient ->
                    connectionSupport.close(serverName, oldClient)
                }

                statuses[serverName] = McpServerStatus.CONNECTING

                val handle = connectionSupport.open(server)
                if (handle == null) {
                    statuses[serverName] = McpServerStatus.FAILED
                    reconnectionCoordinator.schedule(serverName)
                    return@withLock false
                }

                clients[serverName] = handle.client
                toolCallbacksCache[serverName] = handle.tools
                invalidateAllToolCallbacksSnapshot()
                statuses[serverName] = McpServerStatus.CONNECTED
                reconnectionCoordinator.clear(serverName)
                logger.info { "MCP 서버 연결 완료: $serverName (도구 ${handle.tools.size}개)" }
                true
            } catch (e: Exception) {
                e.throwIfCancellation()
                logger.error(e) { "MCP 서버 연결 실패: $serverName" }
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

    /** 내부 연결 해제 — 뮤텍스 없이 호출 가능 (호출자가 동기화 보장) */
    private fun disconnectInternal(serverName: String) {
        logger.info { "MCP 서버 연결 해제: $serverName" }
        reconnectionCoordinator.clear(serverName)

        clients.remove(serverName)?.let { client ->
            connectionSupport.close(serverName, client)
        }

        toolCallbacksCache.remove(serverName)
        invalidateAllToolCallbacksSnapshot()
        statuses[serverName] = McpServerStatus.DISCONNECTED
    }

    /**
     * [McpToolCallback]에서 도구 호출 중 연결 오류가 감지되면 호출된다.
     * 오래된 클라이언트를 제거하고 재연결을 스케줄링한다 (블로킹 없음).
     *
     * WHY: 도구 호출 실패를 즉시 감지하여 자동 복구를 시작한다.
     * 이를 통해 일시적 네트워크 장애에서 자동으로 회복할 수 있다.
     */
    internal fun handleConnectionError(serverName: String) {
        if (statuses[serverName] != McpServerStatus.CONNECTED) return
        logger.warn { "MCP 도구 호출 중 연결 오류 감지: '$serverName' — FAILED로 표시하고 재연결 스케줄링" }
        clients.remove(serverName)?.let { client ->
            connectionSupport.close(serverName, client)
        }
        // WHY: 재연결이 완료될 때까지 마지막으로 알려진 도구 목록을 유지한다.
        // 도구 콜백을 즉시 제거하면 재연결 사이 윈도우에서 도구가 간헐적으로
        // 사라지는 불안정 현상이 발생한다. 개별 도구 호출은 이미 "Error: ..." 문자열을
        // 반환하므로 stale 콜백이 남아 있어도 안전하다.
        statuses[serverName] = McpServerStatus.FAILED
        reconnectionCoordinator.schedule(serverName)
    }

    /**
     * 보안 정책 변경 후 허용 목록을 재적용한다.
     *
     * 허용 목록에서 제외된 서버는 런타임에서 퇴출하고,
     * 새로 허용된 서버를 스토어에서 로딩하여 등록한다.
     */
    override fun reapplySecurityPolicy() {
        // 허용 목록에서 제외된 서버를 퇴출한다
        val blocked = servers.keys.filterNot(::allowedBySecurity)
        blocked.forEach { serverName ->
            logger.info { "허용 목록 변경으로 MCP 서버 퇴출: $serverName" }
            disconnectInternal(serverName)
            servers.remove(serverName)
            statuses.remove(serverName)
            serverMutexes.remove(serverName)
        }

        // 새로 허용된 서버를 스토어에서 로딩한다
        storeSync.loadAll()
            .filter(::shouldLoadStoredServer)
            .filterNot { servers.containsKey(it.name) }
            .forEach { server ->
                logger.info { "새로 허용된 MCP 서버를 런타임에 로딩: ${server.name}" }
                servers[server.name] = server
                statuses[server.name] = McpServerStatus.PENDING
                if (server.autoConnect) {
                    reconnectScope.launch {
                        try {
                            connect(server.name)
                        } catch (e: Exception) {
                            e.throwIfCancellation()
                            logger.warn(e) { "새로 허용된 MCP 서버 '${server.name}' 자동 연결 실패" }
                        }
                    }
                }
            }
    }

    /** 스토어 서버가 로딩 대상인지 확인한다 */
    private fun shouldLoadStoredServer(server: McpServer): Boolean = allowedBySecurity(server.name)

    /**
     * 연결이 끊어진 서버에 대해 온디맨드 재연결을 시도한다.
     * FAILED 또는 DISCONNECTED 상태이고 재연결이 활성화된 경우에만 시도한다.
     */
    override suspend fun ensureConnected(serverName: String): Boolean {
        val status = statuses[serverName]
        if (status == McpServerStatus.CONNECTED) return true
        if (status != McpServerStatus.FAILED && status != McpServerStatus.DISCONNECTED) return false
        if (!reconnectionProperties.enabled) return false

        logger.info { "MCP 서버 온디맨드 재연결 시도: $serverName" }
        return connect(serverName)
    }

    /**
     * 모든 연결된 서버의 도구 콜백을 중복 제거하여 반환한다.
     *
     * WHY: 여러 서버에 동일 이름의 도구가 있을 수 있다. 서버 이름의 알파벳 순으로
     * 먼저 등록된 서버의 도구를 유지하고 뒤의 것은 무시한다. 이 결과를 스냅샷으로
     * 캐싱하여 매 요청마다 중복 제거 연산을 반복하지 않는다.
     */
    override fun getAllToolCallbacks(): List<ToolCallback> {
        allToolCallbacksSnapshot?.let { return it }
        synchronized(toolCallbacksSnapshotLock) {
            allToolCallbacksSnapshot?.let { return it }
            val snapshot = deduplicateCallbacksByName(toolCallbacksCache) { toolName, keptServer, droppedServer ->
                val warningKey = "$toolName|$keptServer|$droppedServer"
                if (duplicateToolWarningKeys.add(warningKey)) {
                    logger.warn {
                        "MCP 도구 이름 중복 감지: '$toolName'. " +
                            "'$keptServer' 유지, '$droppedServer' 무시."
                    }
                }
            }
            allToolCallbacksSnapshot = snapshot
            return snapshot
        }
    }

    override fun getToolCallbacks(serverName: String): List<ToolCallback> {
        return toolCallbacksCache[serverName] ?: emptyList()
    }

    /** 스토어가 있으면 스토어 목록을, 없으면 런타임 레지스트리를 반환한다 */
    override fun listServers(): List<McpServer> {
        return storeSync.listOr(servers.values)
    }

    override fun getStatus(serverName: String): McpServerStatus? {
        return statuses[serverName]
    }

    /** 매니저를 닫고 모든 연결을 해제한다 */
    override fun close() {
        logger.info { "MCP 매니저 닫기, 모든 서버 연결 해제" }
        reconnectScope.cancel()
        reconnectionCoordinator.clearAll()
        for (serverName in clients.keys.toList()) {
            disconnectInternal(serverName)
        }
        servers.clear()
        statuses.clear()
        serverMutexes.clear()
    }

    /** 전체 도구 콜백 스냅샷 캐시를 무효화한다 */
    private fun invalidateAllToolCallbacksSnapshot() {
        allToolCallbacksSnapshot = null
    }

    companion object {
        /** MCP 서버 연결 기본 타임아웃 (밀리초) */
        const val DEFAULT_CONNECTION_TIMEOUT_MS = 30_000L
    }
}

/**
 * 여러 서버의 도구 콜백을 이름 기준으로 중복 제거한다.
 *
 * 서버 이름을 알파벳 순으로 처리하여, 먼저 오는 서버의 도구가 우선된다.
 * 중복 발견 시 [onDuplicate] 콜백이 호출된다.
 *
 * WHY: 에이전트는 도구 이름으로 도구를 식별하므로, 동일 이름의 도구가
 * 여러 서버에 존재하면 하나만 유지해야 한다. 결정적(deterministic)인 결과를
 * 위해 서버 이름 순으로 우선순위를 정한다.
 *
 * @param toolCallbacksByServer 서버별 도구 콜백 맵
 * @param onDuplicate 중복 발견 시 호출되는 콜백 (도구명, 유지서버, 무시서버)
 * @return 중복 제거된 도구 콜백 목록
 */
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
