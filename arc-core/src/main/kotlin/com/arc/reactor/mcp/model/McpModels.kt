package com.arc.reactor.mcp.model

import java.time.Instant
import java.util.UUID

/**
 * MCP 서버 설정 — MCP 서버의 연결 및 메타데이터 정보.
 *
 * @param id 고유 식별자 (UUID)
 * @param name 서버 고유 이름 (레지스트리 키로 사용)
 * @param description 서버 설명
 * @param transportType 전송 프로토콜 유형 (STDIO, SSE, HTTP)
 * @param config 전송별 설정 (예: SSE의 "url", STDIO의 "command"/"args")
 * @param version MCP 서버 버전
 * @param autoConnect 시작 시 자동 연결 여부
 * @param createdAt 생성 시각
 * @param updatedAt 마지막 수정 시각
 * @see McpTransportType 전송 유형
 * @see com.arc.reactor.mcp.McpManager MCP 서버 수명주기 관리
 */
data class McpServer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String? = null,
    val transportType: McpTransportType,
    val config: Map<String, Any> = emptyMap(),
    val version: String? = null,
    val autoConnect: Boolean = false,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)

/**
 * MCP 전송 유형
 *
 * WHY: MCP 프로토콜이 지원하는 3가지 전송 방식을 열거한다.
 * HTTP(Streamable)는 MCP SDK 0.17.2에서 아직 지원하지 않는다.
 */
enum class McpTransportType {
    /** 표준 입출력 (로컬 프로세스) — 로컬 MCP 서버에 사용 */
    STDIO,

    /** Server-Sent Events — 원격 MCP 서버에 사용 */
    SSE,

    /** HTTP REST — MCP SDK 0.17.2에서 미지원 */
    HTTP
}

/**
 * MCP 서버 상태 — 서버의 현재 연결 수명주기 상태.
 *
 * 상태 전이: PENDING -> CONNECTING -> CONNECTED (또는 FAILED)
 * 재연결: FAILED -> CONNECTING -> CONNECTED (또는 FAILED)
 * 수동 해제: CONNECTED -> DISCONNECTED
 *
 * @see com.arc.reactor.mcp.McpManager 상태 관리
 * @see com.arc.reactor.mcp.McpReconnectionCoordinator 재연결 스케줄러
 */
enum class McpServerStatus {
    /** 등록됨, 아직 연결 시도 전 */
    PENDING,

    /** 연결 시도 중 */
    CONNECTING,

    /** 연결됨 */
    CONNECTED,

    /** 연결 해제됨 (수동) */
    DISCONNECTED,

    /** 연결 실패 */
    FAILED,

    /** 비활성화됨 */
    DISABLED
}
