package com.arc.reactor.admin.model

import java.math.BigDecimal
import java.time.Instant

sealed class MetricEvent {
    abstract val time: Instant
    abstract val tenantId: String
}

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
    val action: String = "rejected"
) : MetricEvent()

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
