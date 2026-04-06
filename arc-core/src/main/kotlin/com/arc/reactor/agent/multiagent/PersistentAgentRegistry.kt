package com.arc.reactor.agent.multiagent

import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * DB 영속 기반 에이전트 레지스트리.
 *
 * [AgentSpecStore]에서 활성화된 에이전트 스펙을 조회하여
 * [AgentRegistry] 인터페이스를 구현한다.
 * [DefaultAgentRegistry]의 키워드 매칭 로직을 재사용한다.
 *
 * @param store 에이전트 스펙 저장소
 * @see AgentSpecStore DB 영속 저장소
 * @see DefaultAgentRegistry 키워드 매칭 로직
 */
class PersistentAgentRegistry(
    private val store: AgentSpecStore
) : AgentRegistry {

    override fun register(spec: AgentSpec) {
        store.save(
            AgentSpecRecord(
                id = spec.id,
                name = spec.name,
                description = spec.description,
                toolNames = spec.toolNames,
                keywords = spec.keywords,
                systemPrompt = spec.systemPromptOverride,
                mode = spec.mode.name
            )
        )
        logger.debug { "에이전트 DB 등록: id=${spec.id}" }
    }

    override fun unregister(id: String): Boolean {
        val exists = store.get(id) != null
        if (exists) store.delete(id)
        return exists
    }

    override fun findById(id: String): AgentSpec? {
        return store.get(id)
            ?.takeIf { it.enabled }
            ?.toAgentSpec()
    }

    override fun findAll(): List<AgentSpec> {
        return store.listEnabled().map { it.toAgentSpec() }
    }

    override fun findByCapability(query: String): List<AgentSpec> {
        if (query.isBlank()) return emptyList()
        val lowerQuery = query.lowercase()
        return findAll()
            .map { it to DefaultAgentRegistry.calculateMatchScore(it, lowerQuery) }
            .filter { (_, score) -> score > 0 }
            .sortedByDescending { (_, score) -> score }
            .map { (spec, _) -> spec }
    }
}
