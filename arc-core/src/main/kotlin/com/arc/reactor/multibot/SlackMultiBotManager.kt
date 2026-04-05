package com.arc.reactor.multibot

import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 멀티 Slack 봇 관리자.
 *
 * Phase 1: DB에서 봇 인스턴스 목록을 관리하고 Admin API를 제공한다.
 * Phase 2: 각 봇마다 독립 WebSocket 연결을 관리한다.
 *
 * 현재(Phase 1)는 봇 인스턴스의 CRUD만 지원하며,
 * 실제 WebSocket 연결은 기존 단일 봇 구조를 사용한다.
 */
class SlackMultiBotManager(
    private val store: SlackBotInstanceStore
) {

    /** 활성화된 봇 인스턴스 목록 */
    fun listEnabled(): List<SlackBotInstance> = store.listEnabled()

    /** 전체 봇 인스턴스 목록 */
    fun list(): List<SlackBotInstance> = store.list()

    /** 봇 인스턴스 조회 */
    fun get(id: String): SlackBotInstance? = store.get(id)

    /** 봇 인스턴스 등록 */
    fun register(instance: SlackBotInstance): SlackBotInstance {
        logger.info { "Slack 봇 인스턴스 등록: name=${instance.name}, persona=${instance.personaId}" }
        return store.save(instance)
    }

    /** 봇 인스턴스 삭제 */
    fun unregister(id: String) {
        val instance = store.get(id)
        if (instance != null) {
            logger.info { "Slack 봇 인스턴스 삭제: name=${instance.name}" }
            store.delete(id)
        }
    }

    /** personaId로 봇 인스턴스 검색 */
    fun findByPersonaId(personaId: String): List<SlackBotInstance> {
        return store.list().filter { it.personaId == personaId }
    }
}
