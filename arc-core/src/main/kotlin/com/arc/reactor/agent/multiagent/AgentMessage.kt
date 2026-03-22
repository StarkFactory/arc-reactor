package com.arc.reactor.agent.multiagent

import java.time.Instant

/**
 * 에이전트 간 전달 메시지.
 *
 * 에이전트 A의 실행 결과를 에이전트 B에 전달하기 위한 메시지 객체이다.
 * [targetAgentId]가 null이면 브로드캐스트로 간주하여 모든 에이전트가 수신한다.
 *
 * @param sourceAgentId 메시지를 보낸 에이전트 ID
 * @param targetAgentId 수신 대상 에이전트 ID (null이면 브로드캐스트)
 * @param content 메시지 내용 (일반적으로 에이전트 실행 결과)
 * @param metadata 추가 메타데이터 (toolsUsed, durationMs 등)
 * @param timestamp 메시지 생성 시각
 *
 * @see AgentMessageBus 메시지 발행 및 구독
 * @see DefaultSupervisorAgent 에이전트 위임 시 메시지 발행
 */
data class AgentMessage(
    val sourceAgentId: String,
    val targetAgentId: String?,
    val content: String,
    val metadata: Map<String, Any> = emptyMap(),
    val timestamp: Instant = Instant.now()
)
