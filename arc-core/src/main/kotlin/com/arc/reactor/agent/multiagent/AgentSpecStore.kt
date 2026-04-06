package com.arc.reactor.agent.multiagent

/**
 * 에이전트 스펙 영속 저장소.
 *
 * [AgentRegistry]의 인메모리 레지스트리와 달리, DB 등 영속 저장소에
 * 에이전트 스펙을 관리한다. Admin API를 통한 CRUD 작업을 지원한다.
 *
 * @see AgentSpec 에이전트 사양 정의
 * @see JdbcAgentSpecStore JDBC 기반 구현
 */
interface AgentSpecStore {

    /** 모든 에이전트 스펙을 조회한다. */
    fun list(): List<AgentSpecRecord>

    /** 활성화된 에이전트 스펙만 조회한다. */
    fun listEnabled(): List<AgentSpecRecord>

    /** ID로 에이전트 스펙을 조회한다. */
    fun get(id: String): AgentSpecRecord?

    /** 에이전트 스펙을 저장한다 (UPSERT). */
    fun save(record: AgentSpecRecord): AgentSpecRecord

    /** 에이전트 스펙을 삭제한다. */
    fun delete(id: String)
}

/**
 * DB 영속용 에이전트 스펙 레코드.
 *
 * [AgentSpec]을 감싸며 영속 계층 메타데이터(enabled, 생성/수정 시각)를 추가한다.
 */
data class AgentSpecRecord(
    val id: String,
    val name: String,
    val description: String,
    val toolNames: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    val systemPrompt: String? = null,
    val mode: String = "REACT",
    val enabled: Boolean = true,
    val createdAt: java.time.Instant = java.time.Instant.now(),
    val updatedAt: java.time.Instant = java.time.Instant.now()
) {
    /** 도메인 모델 [AgentSpec]으로 변환한다. */
    fun toAgentSpec(): AgentSpec = AgentSpec(
        id = id,
        name = name,
        description = description,
        toolNames = toolNames,
        keywords = keywords,
        systemPromptOverride = systemPrompt,
        mode = com.arc.reactor.agent.model.AgentMode.valueOf(mode)
    )
}
