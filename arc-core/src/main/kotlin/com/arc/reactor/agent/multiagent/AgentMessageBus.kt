package com.arc.reactor.agent.multiagent

import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

private val logger = KotlinLogging.logger {}

/**
 * 에이전트 간 메시지 버스 인터페이스.
 *
 * 에이전트 A의 실행 결과를 에이전트 B의 입력 컨텍스트로 전달하는 통신 채널이다.
 * 단일 Supervisor 실행 범위 내에서 메시지를 저장하고 전달한다.
 *
 * ## 사용 흐름
 * ```
 * Agent A 실행 → 결과를 AgentMessage로 publish → Agent B 실행 시 getMessages()로 조회
 * ```
 *
 * @see AgentMessage 에이전트 간 전달 메시지
 * @see InMemoryAgentMessageBus 인메모리 기본 구현
 * @see DefaultSupervisorAgent 메시지 버스를 사용하여 에이전트 간 컨텍스트 전달
 */
interface AgentMessageBus {

    /**
     * 메시지를 발행한다.
     *
     * 구독자가 있으면 즉시 핸들러를 호출한다.
     *
     * @param message 발행할 메시지
     */
    suspend fun publish(message: AgentMessage)

    /**
     * 특정 에이전트 ID로 메시지를 구독한다.
     *
     * 해당 에이전트를 대상으로 하는 메시지 또는 브로드캐스트 메시지가
     * 발행될 때 핸들러가 호출된다.
     *
     * @param agentId 구독할 에이전트 ID
     * @param handler 메시지 수신 시 호출될 핸들러
     */
    suspend fun subscribe(
        agentId: String,
        handler: suspend (AgentMessage) -> Unit
    )

    /**
     * 특정 에이전트에 전달된 모든 메시지를 조회한다.
     *
     * 해당 에이전트를 대상으로 하는 메시지와 브로드캐스트 메시지를 반환한다.
     *
     * @param agentId 조회할 에이전트 ID
     * @return 해당 에이전트의 메시지 목록 (시간순)
     */
    fun getMessages(agentId: String): List<AgentMessage>

    /**
     * 모든 메시지를 시간순으로 반환한다.
     *
     * @return 전체 메시지 목록 (시간순)
     */
    fun getConversation(): List<AgentMessage>

    /**
     * 모든 메시지와 구독을 초기화한다.
     */
    fun clear()
}

/**
 * 인메모리 에이전트 메시지 버스.
 *
 * 단일 JVM 내에서 동작하며, 단일 Supervisor 실행 범위에 한정된다.
 * [ConcurrentHashMap]과 [CopyOnWriteArrayList]로 스레드 안전성을 보장한다.
 *
 * @see AgentMessageBus 인터페이스 정의
 */
class InMemoryAgentMessageBus : AgentMessageBus {

    /** 전체 메시지 저장소 (시간순) */
    private val allMessages = CopyOnWriteArrayList<AgentMessage>()

    /** 에이전트별 구독 핸들러 */
    private val subscribers =
        ConcurrentHashMap<String, CopyOnWriteArrayList<suspend (AgentMessage) -> Unit>>()

    override suspend fun publish(message: AgentMessage) {
        allMessages.add(message)
        logger.debug {
            "메시지 발행: source=${message.sourceAgentId}, " +
                "target=${message.targetAgentId ?: "broadcast"}"
        }
        notifySubscribers(message)
    }

    override suspend fun subscribe(
        agentId: String,
        handler: suspend (AgentMessage) -> Unit
    ) {
        subscribers
            .getOrPut(agentId) { CopyOnWriteArrayList() }
            .add(handler)
        logger.debug { "구독 등록: agentId=$agentId" }
    }

    override fun getMessages(agentId: String): List<AgentMessage> {
        return allMessages.filter { msg ->
            msg.targetAgentId == agentId || msg.targetAgentId == null
        }
    }

    override fun getConversation(): List<AgentMessage> {
        return allMessages.toList()
    }

    override fun clear() {
        allMessages.clear()
        subscribers.clear()
        logger.debug { "메시지 버스 초기화 완료" }
    }

    /**
     * 메시지 대상 에이전트의 구독자에게 알림을 보낸다.
     * 브로드캐스트 메시지는 모든 구독자에게 전달한다.
     */
    private suspend fun notifySubscribers(message: AgentMessage) {
        val targetId = message.targetAgentId
        if (targetId != null) {
            for (handler in subscribers[targetId].orEmpty()) { handler(message) }
        } else {
            for ((_, handlers) in subscribers) {
                for (handler in handlers) {
                    handler(message)
                }
            }
        }
    }
}
