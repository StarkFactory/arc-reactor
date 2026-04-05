package com.arc.reactor.multibot

import java.time.Instant

/**
 * Slack 봇 인스턴스 — 1개의 Arc Reactor에 연결된 개별 Slack 봇.
 *
 * 각 봇은 독립된 Bot Token, App Token, 페르소나를 가진다.
 * 같은 AgentExecutor, MCP 도구, Guard Pipeline을 공유한다.
 */
data class SlackBotInstance(
    val id: String,
    val name: String,
    val botToken: String,
    val appToken: String,
    val personaId: String,
    val defaultChannel: String? = null,
    val enabled: Boolean = true,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)

/**
 * Slack 봇 인스턴스 저장소 인터페이스.
 */
interface SlackBotInstanceStore {
    fun list(): List<SlackBotInstance>
    fun listEnabled(): List<SlackBotInstance> = list().filter { it.enabled }
    fun get(id: String): SlackBotInstance?
    fun save(instance: SlackBotInstance): SlackBotInstance
    fun delete(id: String)
}
