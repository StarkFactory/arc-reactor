package com.arc.reactor.mcp.model

/**
 * MCP 서버 정보
 */
data class McpServer(
    val name: String,
    val description: String? = null,
    val transportType: McpTransportType,
    val config: Map<String, Any> = emptyMap(),
    val version: String? = null,
    val autoConnect: Boolean = false
)

/**
 * MCP 전송 타입
 */
enum class McpTransportType {
    /** Standard I/O (로컬 프로세스) */
    STDIO,

    /** Server-Sent Events */
    SSE,

    /** HTTP REST */
    HTTP
}

/**
 * MCP 서버 상태
 */
enum class McpServerStatus {
    /** 등록됨, 연결 전 */
    PENDING,

    /** 연결 중 */
    CONNECTING,

    /** 연결됨 */
    CONNECTED,

    /** 연결 해제됨 */
    DISCONNECTED,

    /** 연결 실패 */
    FAILED,

    /** 비활성화됨 */
    DISABLED
}
