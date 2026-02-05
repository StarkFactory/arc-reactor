package com.arc.reactor.hook.model

import java.time.Instant

/**
 * Hook 실행 결과
 */
sealed class HookResult {
    /** 계속 진행 */
    data object Continue : HookResult()

    /** 실행 거부 */
    data class Reject(val reason: String) : HookResult()

    /** 파라미터 수정 후 계속 */
    data class Modify(val modifiedParams: Map<String, Any>) : HookResult()

    /** 승인 대기 (비동기 승인 워크플로우) */
    data class PendingApproval(
        val approvalId: String,
        val message: String
    ) : HookResult()
}

/**
 * Agent 실행 Hook 컨텍스트
 */
data class HookContext(
    val runId: String,
    val userId: String,
    val userEmail: String? = null,
    val userPrompt: String,
    val channel: String? = null,
    val startedAt: Instant = Instant.now(),
    val toolsUsed: MutableList<String> = mutableListOf(),
    val metadata: MutableMap<String, Any> = mutableMapOf()
) {
    fun durationMs(): Long = Instant.now().toEpochMilli() - startedAt.toEpochMilli()
}

/**
 * Tool 호출 Hook 컨텍스트
 */
data class ToolCallContext(
    val agentContext: HookContext,
    val toolName: String,
    val toolParams: Map<String, Any?>,
    val callIndex: Int
) {
    /** 민감정보 마스킹 */
    fun maskedParams(): Map<String, Any?> {
        val sensitiveKeys = setOf("password", "token", "secret", "key", "credential", "apikey")
        return toolParams.mapValues { (key, value) ->
            if (sensitiveKeys.any { key.lowercase().contains(it) }) "***" else value
        }
    }
}

/**
 * Tool 호출 결과
 */
data class ToolCallResult(
    val success: Boolean,
    val output: String? = null,
    val errorMessage: String? = null,
    val durationMs: Long = 0
)

/**
 * Agent 응답 결과
 */
data class AgentResponse(
    val success: Boolean,
    val response: String? = null,
    val errorMessage: String? = null,
    val toolsUsed: List<String> = emptyList(),
    val totalDurationMs: Long = 0
)
