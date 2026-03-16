package com.arc.reactor.mcp

import com.arc.reactor.mcp.model.McpServer
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * MCP 서버 메타데이터의 영속 동기화 헬퍼.
 *
 * 스토어 관련 관심사를 런타임 매니저에서 분리한다.
 * 스토어가 null이면 모든 작업이 노옵(no-op)이다.
 *
 * WHY: DefaultMcpManager의 핵심 로직(연결/상태 관리)에서
 * 영속 스토어 동기화 로직을 분리하여 관심사를 명확히 한다.
 * 스토어가 없는 환경(인메모리 전용)에서도 매니저가 정상 동작한다.
 *
 * @param store MCP 서버 영속 스토어 (null이면 영속 없음)
 * @see DefaultMcpManager 매니저에서의 활용
 */
internal class McpStoreSync(
    private val store: McpServerStore?
) {

    /** 서버가 스토어에 없을 때만 저장한다 (중복 방지). 스토어 오류는 경고 로그 후 무시. */
    fun saveIfAbsent(server: McpServer) {
        if (store == null) return
        try {
            if (store.findByName(server.name) != null) return
            store.save(server)
        } catch (e: Exception) {
            logger.warn(e) { "MCP 서버 '${server.name}' 스토어 저장 실패" }
        }
    }

    /** 스토어에서 서버를 삭제한다. 스토어 오류는 경고 로그 후 무시. */
    fun delete(serverName: String) {
        if (store == null) return
        try {
            store.delete(serverName)
        } catch (e: Exception) {
            logger.warn(e) { "MCP 서버 '$serverName' 스토어 삭제 실패" }
        }
    }

    /** 스토어에서 모든 서버를 로딩한다. 오류 시 빈 리스트를 반환한다. */
    fun loadAll(): List<McpServer> {
        if (store == null) return emptyList()
        return try {
            store.list()
        } catch (e: Exception) {
            logger.warn(e) { "스토어에서 MCP 서버 로딩 실패, 빈 리스트로 계속" }
            emptyList()
        }
    }

    /** 스토어가 있으면 스토어 목록을, 없으면 런타임 서버를 반환한다. 스토어 오류 시 런타임 폴백. */
    fun listOr(runtimeServers: Collection<McpServer>): List<McpServer> {
        if (store == null) return runtimeServers.toList()
        return try {
            store.list()
        } catch (e: Exception) {
            logger.warn(e) { "스토어에서 MCP 서버 목록 조회 실패, 런타임 레지스트리 폴백 사용" }
            runtimeServers.toList()
        }
    }
}
