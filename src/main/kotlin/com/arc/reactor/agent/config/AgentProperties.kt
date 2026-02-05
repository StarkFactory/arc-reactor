package com.arc.reactor.agent.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Arc Reactor Agent 설정
 */
@ConfigurationProperties(prefix = "arc.reactor")
data class AgentProperties(
    /** LLM 설정 */
    val llm: LlmProperties = LlmProperties(),

    /** Guard 설정 */
    val guard: GuardProperties = GuardProperties(),

    /** RAG 설정 */
    val rag: RagProperties = RagProperties(),

    /** 동시성 설정 */
    val concurrency: ConcurrencyProperties = ConcurrencyProperties(),

    /** 요청당 최대 Tool 개수 */
    val maxToolsPerRequest: Int = 20,

    /** 최대 Tool 호출 횟수 (무한 루프 방지) */
    val maxToolCalls: Int = 10
)

data class LlmProperties(
    /** 기본 Temperature */
    val temperature: Double = 0.3,

    /** 최대 출력 토큰 */
    val maxOutputTokens: Int = 4096,

    /** 타임아웃 (ms) */
    val timeoutMs: Long = 60000,

    /** 대화 히스토리 최대 턴 수 */
    val maxConversationTurns: Int = 10
)

data class GuardProperties(
    /** Guard 활성화 */
    val enabled: Boolean = true,

    /** 분당 요청 제한 */
    val rateLimitPerMinute: Int = 10,

    /** 시간당 요청 제한 */
    val rateLimitPerHour: Int = 100,

    /** 최대 입력 길이 */
    val maxInputLength: Int = 10000,

    /** Injection 탐지 활성화 */
    val injectionDetectionEnabled: Boolean = true
)

data class ConcurrencyProperties(
    /** 최대 동시 요청 */
    val maxConcurrentRequests: Int = 20,

    /** 요청 대기 타임아웃 (초) */
    val requestTimeoutSeconds: Long = 30
)

data class RagProperties(
    /** RAG 활성화 */
    val enabled: Boolean = false,

    /** 검색 유사도 임계값 */
    val similarityThreshold: Double = 0.7,

    /** 검색 결과 개수 */
    val topK: Int = 10,

    /** 재순위 적용 */
    val rerankEnabled: Boolean = true,

    /** 컨텍스트 최대 토큰 */
    val maxContextTokens: Int = 4000
)
