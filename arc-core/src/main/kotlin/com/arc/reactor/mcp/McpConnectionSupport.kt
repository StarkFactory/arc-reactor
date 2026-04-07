package com.arc.reactor.mcp

import com.arc.reactor.mcp.model.McpServer
import com.arc.reactor.mcp.model.McpTransportType
import com.arc.reactor.tool.ToolCallback
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport
import io.modelcontextprotocol.client.transport.ServerParameters
import io.modelcontextprotocol.client.transport.StdioClientTransport
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper
import io.modelcontextprotocol.spec.McpSchema
import mu.KotlinLogging
import java.net.InetAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * 주어진 호스트가 프라이빗, 예약됨, 또는 해석 불가능한 주소로 해석되는지 확인한다.
 * 루프백, 사이트 로컬, 링크 로컬, 멀티캐스트, 클라우드 메타데이터 주소를 차단하여
 * SSRF 공격을 방지한다.
 *
 * WHY: 외부 사용자가 MCP SSE URL로 내부 네트워크 주소를 지정하면
 * 서버가 내부 서비스에 접근하는 SSRF 취약점이 발생한다.
 * 이 검증으로 내부/메타데이터 주소를 사전에 차단한다.
 *
 * @param host 확인할 호스트명
 * @return 프라이빗/예약 주소이면 true
 */
fun isPrivateOrReservedAddress(host: String?): Boolean {
    if (host.isNullOrBlank()) return true
    return try {
        val addr = InetAddress.getByName(host)
        addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress ||
            addr.isMulticastAddress || isCloudMetadataAddress(addr)
    } catch (_: Exception) {
        true // 해석 불가능한 호스트는 차단 처리
    }
}

/**
 * 클라우드 프로바이더 메타데이터 서비스 주소(AWS, GCP, Azure)를 감지한다.
 * 이 엔드포인트는 인스턴스 자격증명을 노출하므로 SSRF 방지를 위해 차단해야 한다.
 */
private fun isCloudMetadataAddress(addr: InetAddress): Boolean {
    val ip = addr.hostAddress
    return ip == "169.254.169.254" || // AWS/GCP/Azure 메타데이터 (IPv4)
        ip == "fd00:ec2::254"         // AWS IMDSv2 (IPv6)
}

/**
 * MCP 연결 핸들 — 클라이언트와 해당 도구 콜백을 함께 보관한다.
 *
 * @param client MCP 동기 클라이언트
 * @param tools 서버에서 로딩된 도구 콜백 목록
 */
internal data class McpConnectionHandle(
    val client: McpSyncClient,
    val tools: List<ToolCallback>
)

/**
 * MCP 전송 및 도구 탐색 지원 클래스.
 *
 * STDIO, SSE, HTTP 전송 연결을 처리하고, 연결된 서버에서 도구 목록을 로딩한다.
 *
 * ## 보안 검증
 * - STDIO: 명령어 화이트리스트, 경로 순회 검증, 제어 문자 검증
 * - SSE: URL 스킴 검증, 프라이빗 주소 차단 (SSRF 방지)
 * - HTTP: MCP SDK 0.17.2에서 미지원
 *
 * WHY: 연결 설정의 보안 검증과 전송 프로토콜 처리를 DefaultMcpManager에서 분리하여
 * 관심사를 분리하고 테스트를 용이하게 한다.
 *
 * @param connectionTimeoutMs 연결 타임아웃 (밀리초)
 * @param maxToolOutputLengthProvider 도구 출력 최대 길이 제공 함수
 * @param allowPrivateAddresses 프라이빗 주소 허용 여부
 * @param allowedStdioCommandsProvider STDIO 허용 명령어 집합 제공 함수
 * @param onConnectionError 연결 오류 발생 시 호출되는 콜백 (서버 이름)
 * @param meterRegistry Micrometer 레지스트리 (선택). null이면 연결 메트릭을 기록하지 않는다.
 */
internal class McpConnectionSupport(
    private val connectionTimeoutMs: Long,
    private val maxToolOutputLengthProvider: () -> Int,
    private val allowPrivateAddresses: Boolean = false,
    private val allowedStdioCommandsProvider: () -> Set<String> = {
        McpSecurityConfig.DEFAULT_ALLOWED_STDIO_COMMANDS
    },
    private val onConnectionError: (serverName: String) -> Unit = {},
    private val meterRegistry: MeterRegistry? = null
) {

    companion object {
        /** 탭(0x09)과 개행(0x0A)을 제외한 0x20 미만의 제어 문자를 매칭한다. */
        private val UNSAFE_CONTROL_CHAR_REGEX = Regex("[\\x00-\\x08\\x0B-\\x1F]")

        internal const val METRIC_CONNECTION_ATTEMPTS = "arc.mcp.connection.attempts"
        internal const val METRIC_CONNECTION_LATENCY = "arc.mcp.connection.latency"

        /**
         * SSE 연결용 공유 HttpClient.Builder.
         *
         * WHY: 매 연결 시도마다 새 HttpClient를 생성하면 TCP 소켓이 누적되어
         * TIME_WAIT 소켓 고갈(15,000+)이 발생한다. 공유 빌더를 사용하여
         * 커넥션 풀을 재사용하고 소켓 누적을 방지한다.
         */
        private val SHARED_HTTP_CLIENT_BUILDER: java.net.http.HttpClient.Builder =
            java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
    }

    /**
     * MCP 서버에 대한 전송 연결을 열고 도구를 로딩한다.
     *
     * 연결 시도 횟수와 소요 시간을 Micrometer 메트릭으로 기록한다.
     *
     * @param server MCP 서버 설정
     * @return 연결 핸들, 또는 연결 실패 시 null
     */
    fun open(server: McpServer): McpConnectionHandle? {
        val startNanos = System.nanoTime()
        val client = when (server.transportType) {
            McpTransportType.STDIO -> connectStdio(server)
            McpTransportType.SSE -> connectSse(server)
            McpTransportType.HTTP -> connectHttp(server)
        }
        val elapsedNanos = System.nanoTime() - startNanos
        val success = client != null

        recordConnectionAttempt(server.name, success)
        if (success) {
            recordConnectionLatency(server.name, elapsedNanos)
        }

        if (client == null) return null

        val tools = loadToolCallbacks(client, server.name)
        return McpConnectionHandle(client = client, tools = tools)
    }

    /** 연결 시도 카운터를 기록한다. */
    private fun recordConnectionAttempt(serverName: String, success: Boolean) {
        meterRegistry?.let { registry ->
            Counter.builder(METRIC_CONNECTION_ATTEMPTS)
                .tag("server", serverName)
                .tag("success", success.toString())
                .register(registry)
                .increment()
        }
    }

    /** 연결 소요 시간을 기록한다. */
    private fun recordConnectionLatency(serverName: String, elapsedNanos: Long) {
        meterRegistry?.let { registry ->
            Timer.builder(METRIC_CONNECTION_LATENCY)
                .tag("server", serverName)
                .register(registry)
                .record(elapsedNanos, TimeUnit.NANOSECONDS)
        }
    }

    /**
     * MCP 클라이언트 연결을 안전하게 닫는다.
     * 우아한 종료(graceful shutdown)를 시도하고 실패 시 강제 종료한다.
     */
    fun close(serverName: String, client: McpSyncClient) {
        try {
            client.closeGracefully()
        } catch (e: Exception) {
            logger.warn(e) { "$serverName 의 우아한 종료 실패, 강제 종료 시도" }
            try {
                client.close()
            } catch (closeEx: Exception) {
                logger.error(closeEx) { "$serverName 의 강제 종료도 실패" }
            }
        }
    }

    /**
     * STDIO 전송으로 연결한다.
     * 명령어와 인자를 보안 검증한 후 로컬 프로세스를 시작한다.
     */
    private fun connectStdio(server: McpServer): McpSyncClient? {
        val command = server.config["command"] as? String ?: run {
            logger.warn { "STDIO 전송에는 config에 'command'가 필요: ${server.name}" }
            return null
        }
        val rawArgs = server.config["args"] as? List<*> ?: emptyList<Any>()
        val nonStringArgs = rawArgs.filterNot { it is String || it == null }
        if (nonStringArgs.isNotEmpty()) {
            logger.warn {
                "STDIO args에 비-String 요소 ${nonStringArgs.size}개 감지 (서버: ${server.name}). " +
                    "타입: ${nonStringArgs.map { it?.javaClass?.simpleName }}. 해당 요소는 무시됨."
            }
        }
        val args = rawArgs.filterIsInstance<String>()

        // 보안 검증: 명령어 화이트리스트 및 경로 순회 검증
        if (!validateStdioCommand(command, server.name)) return null
        // 보안 검증: 인자 내 제어 문자 검증
        if (!validateStdioArgs(args, server.name)) return null

        var transport: StdioClientTransport? = null
        var client: McpSyncClient? = null
        return try {
            val params = ServerParameters.builder(command)
                .args(*args.toTypedArray())
                .build()

            transport = StdioClientTransport(params, JacksonMcpJsonMapper(ObjectMapper()))

            client = McpClient.sync(transport)
                .requestTimeout(Duration.ofMillis(connectionTimeoutMs))
                .initializationTimeout(Duration.ofMillis(connectionTimeoutMs))
                .clientInfo(McpSchema.Implementation(server.name, server.version ?: "1.0.0"))
                .build()

            client.initialize()
            client  // 성공 시 transport는 client가 소유
        } catch (e: Exception) {
            logger.error(e) { "${server.name}의 STDIO 전송 생성 실패" }
            // client와 transport 모두 정리 — 리소스 누수 방지
            try { client?.close() } catch (_: Exception) { /* 최선의 정리 시도 */ }
            if (client == null) {
                // client 생성 전 실패 시 transport 직접 정리
                try { transport?.close() } catch (_: Exception) { /* 최선의 정리 시도 */ }
            }
            null
        }
    }

    /**
     * STDIO 명령어를 허용 목록과 대조하고 경로 순회 패턴을 거부한다.
     *
     * @param command 실행할 명령어
     * @param serverName 서버 이름 (로깅용)
     * @return 유효하면 true
     */
    internal fun validateStdioCommand(
        command: String,
        serverName: String
    ): Boolean {
        // 경로 순회 패턴("..")을 거부한다
        if (command.contains("..")) {
            logger.warn {
                "서버 '$serverName'의 STDIO 명령어에 경로 순회 포함: $command"
            }
            return false
        }

        // 절대/상대 경로가 포함된 명령어를 거부한다 — PATH 기반 명령어만 허용
        // WHY: basename만 검증하면 /tmp/evil/npx 같은 절대 경로로 우회 가능
        if (command.contains("/") || command.contains("\\")) {
            logger.warn {
                "서버 '$serverName'의 STDIO 명령어에 경로 포함: $command — " +
                    "PATH 기반 명령어만 허용됩니다 (예: npx, node)"
            }
            return false
        }

        // 명령어가 허용 목록에 있는지 확인한다
        val allowed = allowedStdioCommandsProvider()
        if (command !in allowed) {
            logger.warn {
                "서버 '$serverName'의 STDIO 명령어 '$command'가 " +
                    "허용 목록에 없음. 허용: $allowed"
            }
            return false
        }
        return true
    }

    /**
     * STDIO 인자에서 null 바이트와 제어 문자를 거부한다.
     *
     * WHY: 제어 문자가 포함된 인자는 명령어 인젝션에 악용될 수 있다.
     *
     * @param args 검증할 인자 목록
     * @param serverName 서버 이름 (로깅용)
     * @return 유효하면 true
     */
    internal fun validateStdioArgs(
        args: List<String>,
        serverName: String
    ): Boolean {
        for (arg in args) {
            if (UNSAFE_CONTROL_CHAR_REGEX.containsMatchIn(arg)) {
                logger.warn {
                    "서버 '$serverName'의 STDIO 인자에 " +
                        "안전하지 않은 제어 문자 포함"
                }
                return false
            }
        }
        return true
    }

    /**
     * SSE(Server-Sent Events) 전송으로 연결한다.
     * URL 스킴 검증과 SSRF 방지를 위한 프라이빗 주소 검증을 수행한다.
     */
    private fun connectSse(server: McpServer): McpSyncClient? {
        val url = server.config["url"] as? String ?: run {
            logger.warn { "SSE 전송에는 config에 'url'이 필요: ${server.name}" }
            return null
        }

        val parsed = try {
            URI(url)
        } catch (e: Exception) {
            logger.warn { "서버 '${server.name}'의 잘못된 SSE URL: $url" }
            return null
        }

        // HTTP/HTTPS만 허용
        if (!parsed.isAbsolute || (parsed.scheme != "http" && parsed.scheme != "https")) {
            logger.warn { "SSE URL은 절대 경로 http/https여야 함: '${server.name}': $url" }
            return null
        }

        // SSRF 방지: 프라이빗/예약 주소 차단
        if (isPrivateAddress(parsed.host)) {
            logger.warn { "SSE URL이 프라이빗/예약 주소로 해석됨: '${server.name}'" }
            return null
        }

        var transport: HttpClientSseClientTransport? = null
        var client: McpSyncClient? = null
        return try {
            transport = HttpClientSseClientTransport.builder(parsed.toString())
                .clientBuilder(
                    SHARED_HTTP_CLIENT_BUILDER
                        .connectTimeout(Duration.ofMillis(connectionTimeoutMs))
                )
                .build()

            client = McpClient.sync(transport)
                .requestTimeout(Duration.ofMillis(connectionTimeoutMs))
                .initializationTimeout(Duration.ofMillis(connectionTimeoutMs))
                .clientInfo(McpSchema.Implementation(server.name, server.version ?: "1.0.0"))
                .build()

            client.initialize()
            client  // 성공 시 transport는 client가 소유
        } catch (e: Exception) {
            logger.error(e) { "${server.name}의 SSE 전송 생성 실패" }
            // client와 transport 모두 정리 — 리소스 누수 방지
            try { client?.close() } catch (_: Exception) { /* 최선의 정리 시도 */ }
            if (client == null) {
                // client 생성 전 실패 시 transport 직접 정리
                try { transport?.close() } catch (_: Exception) { /* 최선의 정리 시도 */ }
            }
            null
        }
    }

    /**
     * Streamable HTTP 전송은 MCP SDK 0.17.2에서 사용할 수 없다.
     */
    private fun connectHttp(server: McpServer): McpSyncClient? {
        logger.warn {
            "HTTP (Streamable) 전송은 MCP SDK 0.17.2에서 아직 지원되지 않음. " +
                "서버 '${server.name}'에 SSE 전송을 대신 사용하세요"
        }
        return null
    }

    /** 프라이빗 주소 허용 설정을 고려하여 확인한다 */
    private fun isPrivateAddress(host: String?): Boolean {
        if (allowPrivateAddresses) return false
        return isPrivateOrReservedAddress(host)
    }

    /**
     * MCP 서버에서 도구 콜백 목록을 로딩한다.
     * 각 MCP 도구를 Arc Reactor의 McpToolCallback으로 래핑한다.
     */
    private fun loadToolCallbacks(client: McpSyncClient, serverName: String): List<ToolCallback> {
        return try {
            val toolsResult = client.listTools()
            val tools = toolsResult.tools()
            logger.info { "$serverName 에서 ${tools.size}개 도구 로딩됨" }

            tools.map { tool ->
                McpToolCallback(
                    client = client,
                    name = tool.name(),
                    description = tool.description() ?: "",
                    mcpInputSchema = tool.inputSchema(),
                    maxOutputLength = maxToolOutputLengthProvider(),
                    onConnectionError = { onConnectionError(serverName) }
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "$serverName 에서 도구 로딩 실패" }
            emptyList()
        }
    }
}
