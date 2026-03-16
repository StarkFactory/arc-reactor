package com.arc.reactor.admin.model

import java.math.BigDecimal
import java.time.Instant

/**
 * 메트릭 이벤트의 sealed 기본 클래스.
 *
 * 모든 하위 타입은 [time]과 [tenantId]를 공통으로 가진다.
 * [MetricRingBuffer]에 publish된 뒤 [MetricWriter]가 drain하여 DB에 적재한다.
 */
sealed class MetricEvent {
    abstract val time: Instant
    abstract val tenantId: String
}

/** 에이전트 실행 완료 이벤트. 지연 분해(LLM/도구/가드)와 성공 여부를 포함한다. */
data class AgentExecutionEvent(
    override val time: Instant = Instant.now(),
    override val tenantId: String,
    val runId: String,
    val userId: String? = null,
    val sessionId: String? = null,
    val channel: String? = null,
    val success: Boolean,
    val errorCode: String? = null,
    val errorClass: String? = null,
    val durationMs: Long = 0,
    val llmDurationMs: Long = 0,
    val toolDurationMs: Long = 0,
    val guardDurationMs: Long = 0,
    val queueWaitMs: Long = 0,
    val isStreaming: Boolean = false,
    val toolCount: Int = 0,
    val personaId: String? = null,
    val promptTemplateId: String? = null,
    val intentCategory: String? = null,
    val guardRejected: Boolean = false,
    val guardStage: String? = null,
    val guardCategory: String? = null,
    val retryCount: Int = 0,
    val fallbackUsed: Boolean = false
) : MetricEvent()

/** 도구 호출 이벤트. local 도구 또는 MCP 원격 도구 호출 결과를 기록한다. */
data class ToolCallEvent(
    override val time: Instant = Instant.now(),
    override val tenantId: String,
    val runId: String,
    val toolName: String,
    val toolSource: String = "local",
    val mcpServerName: String? = null,
    val callIndex: Int = 0,
    val success: Boolean,
    val durationMs: Long = 0,
    val errorClass: String? = null,
    val errorMessage: String? = null
) : MetricEvent()

/** 토큰 사용량 이벤트. 모델/provider별 prompt, completion, reasoning 토큰 수와 추정 비용을 포함한다. */
data class TokenUsageEvent(
    override val time: Instant = Instant.now(),
    override val tenantId: String,
    val runId: String,
    val model: String,
    val provider: String,
    val stepType: String = "act",
    val promptTokens: Int = 0,
    val promptCachedTokens: Int = 0,
    val completionTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val totalTokens: Int = 0,
    val estimatedCostUsd: BigDecimal = BigDecimal.ZERO
) : MetricEvent()

/** 세션 이벤트. 대화 세션의 턴 수, 총 지연, 토큰, 비용, 결과를 기록한다. */
data class SessionEvent(
    override val time: Instant = Instant.now(),
    override val tenantId: String,
    val sessionId: String,
    val userId: String? = null,
    val channel: String? = null,
    val turnCount: Int = 0,
    val totalDurationMs: Long = 0,
    val totalTokens: Long = 0,
    val totalCostUsd: BigDecimal = BigDecimal.ZERO,
    val firstResponseLatencyMs: Long = 0,
    val outcome: String = "resolved",
    val startedAt: Instant = time,
    val endedAt: Instant = time
) : MetricEvent()

/** 가드 이벤트. 입력/출력 가드의 허용 또는 거부 결과를 기록한다. */
data class GuardEvent(
    override val time: Instant = Instant.now(),
    override val tenantId: String,
    val userId: String? = null,
    val channel: String? = null,
    val stage: String,
    val category: String,
    val reasonClass: String? = null,
    val reasonDetail: String? = null,
    val isOutputGuard: Boolean = false,
    val action: String = "rejected",
    val inputHash: String? = null,
    val sessionId: String? = null,
    val requestId: String? = null,
    val pipelineLatencyMs: Long = 0,
    val stageLatencyMs: Long = 0
) : MetricEvent()

/** MCP 서버 상태 이벤트. 연결 상태, 응답 시간, 도구 수를 기록한다. */
data class McpHealthEvent(
    override val time: Instant = Instant.now(),
    override val tenantId: String,
    val serverName: String,
    val status: String = "CONNECTED",
    val responseTimeMs: Long = 0,
    val errorClass: String? = null,
    val errorMessage: String? = null,
    val toolCount: Int = 0
) : MetricEvent()

/** 평가(Eval) 결과 이벤트. 테스트 케이스별 pass/fail, 점수, 지연, 비용을 기록한다. */
data class EvalResultEvent(
    override val time: Instant = Instant.now(),
    override val tenantId: String,
    val evalRunId: String,
    val testCaseId: String,
    val pass: Boolean,
    val score: Double = 0.0,
    val latencyMs: Long = 0,
    val tokenUsage: Int = 0,
    val cost: BigDecimal = BigDecimal.ZERO,
    val assertionType: String? = null,
    val failureClass: String? = null,
    val failureDetail: String? = null,
    val tags: List<String> = emptyList()
) : MetricEvent()

/** 쿼터 이벤트. 쿼터 초과 거부, 90% 경고 등 쿼터 관련 액션을 기록한다. */
data class QuotaEvent(
    override val time: Instant = Instant.now(),
    override val tenantId: String,
    val action: String,
    val currentUsage: Long = 0,
    val quotaLimit: Long = 0,
    val usagePercent: Double = 0.0,
    val reason: String? = null
) : MetricEvent()

/** HITL(Human-in-the-Loop) 이벤트. 사람의 도구 호출 승인/거부와 대기 시간을 기록한다. */
data class HitlEvent(
    override val time: Instant = Instant.now(),
    override val tenantId: String,
    val runId: String,
    val toolName: String,
    val approved: Boolean,
    val waitMs: Long = 0,
    val rejectionReason: String? = null
) : MetricEvent()
