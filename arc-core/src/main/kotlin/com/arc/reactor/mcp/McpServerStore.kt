package com.arc.reactor.mcp

import com.arc.reactor.mcp.model.McpServer
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * MCP 서버 설정을 영속하기 위한 저장소 인터페이스.
 *
 * MCP 서버 등록 데이터의 CRUD 작업을 관리한다.
 * [McpManager]가 시작 시 서버 설정을 로딩하고,
 * McpServerController가 REST API를 통한 동적 관리에 사용한다.
 *
 * WHY: MCP 서버 설정을 영속 저장하여 서버 재시작 시에도
 * 등록된 MCP 서버가 자동으로 복원되게 한다.
 *
 * @see McpManager MCP 서버 수명주기 관리
 * @see InMemoryMcpServerStore 인메모리 구현
 * @see JdbcMcpServerStore JDBC 영속 구현
 */
interface McpServerStore {

    /**
     * 등록된 모든 MCP 서버를 조회한다.
     *
     * @return 서버 목록
     */
    fun list(): List<McpServer>

    /**
     * 고유 이름으로 서버를 찾는다.
     *
     * @param name 서버 이름
     * @return 서버 설정, 또는 존재하지 않으면 null
     */
    fun findByName(name: String): McpServer?

    /**
     * 새 MCP 서버 설정을 저장한다.
     *
     * @param server 저장할 서버
     * @return 저장된 서버 (미설정 시 생성된 ID 포함)
     * @throws IllegalArgumentException 같은 이름의 서버가 이미 존재하는 경우
     */
    fun save(server: McpServer): McpServer

    /**
     * 기존 MCP 서버 설정을 갱신한다.
     *
     * @param name 현재 서버 이름
     * @param server 갱신된 서버 데이터
     * @return 갱신된 서버, 또는 존재하지 않으면 null
     */
    fun update(name: String, server: McpServer): McpServer?

    /**
     * MCP 서버 설정을 삭제한다.
     *
     * @param name 삭제할 서버 이름
     */
    fun delete(name: String)
}

/**
 * [McpServerStore]의 인메모리 구현.
 *
 * [ConcurrentHashMap]을 사용하여 스레드 안전 접근을 보장한다.
 * 애플리케이션 재시작 시 데이터가 소실된다.
 *
 * WHY: DB 없이도 기본 동작을 보장하기 위한 기본 구현.
 *
 * @see JdbcMcpServerStore 운영 환경용 JDBC 구현
 */
class InMemoryMcpServerStore : McpServerStore {

    /** 서버 이름을 키로 하는 동시성 안전 맵 */
    private val servers = ConcurrentHashMap<String, McpServer>()

    override fun list(): List<McpServer> {
        return servers.values.sortedBy { it.createdAt }
    }

    override fun findByName(name: String): McpServer? {
        return servers[name]
    }

    override fun save(server: McpServer): McpServer {
        require(!servers.containsKey(server.name)) { "MCP 서버 '${server.name}'가 이미 존재합니다" }
        val now = Instant.now()
        val toSave = server.copy(createdAt = now, updatedAt = now)
        servers[server.name] = toSave
        return toSave
    }

    override fun update(name: String, server: McpServer): McpServer? {
        val existing = servers[name] ?: return null
        val updated = server.copy(
            id = existing.id,
            name = name,
            createdAt = existing.createdAt,
            updatedAt = Instant.now()
        )
        servers[name] = updated
        return updated
    }

    override fun delete(name: String) {
        servers.remove(name)
    }
}
