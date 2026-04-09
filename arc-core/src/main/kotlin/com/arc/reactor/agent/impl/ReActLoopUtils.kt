package com.arc.reactor.agent.impl

import com.arc.reactor.agent.budget.BudgetStatus
import com.arc.reactor.agent.budget.StepBudgetTracker
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.model.TokenUsage
import com.arc.reactor.hook.model.HookContext
import mu.KotlinLogging
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.google.genai.GoogleGenAiChatOptions

private val logger = KotlinLogging.logger {}

/**
 * Manual/Streaming ReAct 루프가 공유하는 유틸리티.
 */
internal object ReActLoopUtils {

    /** 도구 에러 응답의 표준 접두사. ToolCallback 규약: throw 대신 "Error: ..." 문자열 반환. */
    const val TOOL_ERROR_PREFIX = "Error:"

    /** 도구당 보수적 토큰 추정치 (이름 + 설명 + JSON 스키마). */
    const val TOKENS_PER_TOOL_DEFINITION = 200

    /**
     * 도구 에러 후 LLM이 텍스트 응답(tool_call 없음)을 반환했을 때 재시도할 최대 횟수.
     * 무한 루프 방지를 위해 반드시 제한한다.
     */
    const val MAX_TEXT_RETRIES_AFTER_TOOL_ERROR = 2

    /**
     * 빈 검색 결과를 감지하는 패턴.
     * 도구가 에러 없이 0건 결과를 반환한 경우를 식별한다.
     * JSON 배열 빈 값, "totalSize":0, "results":[] 등을 매칭한다.
     */
    private val EMPTY_RESULT_PATTERN = Regex(
        "\"(?:totalSize|total|count|size)\"\\s*:\\s*0" +
            "|\"(?:results|issues|items|pages|values)\"\\s*:\\s*\\[\\s*\\]" +
            "|^\\s*\\[\\s*\\]\\s*$",
        RegexOption.MULTILINE
    )

    /**
     * 빈 검색 결과 감지 시 LLM에게 키워드 변형 재시도를 유도하는 힌트 메시지 (영/한 병기).
     *
     * LangGraph의 "state-based retry with feedback" 패턴과
     * CrewAI의 "max_retry_limit with guardrail feedback" 패턴을 참고하였다.
     */
    const val EMPTY_RESULT_RETRY_HINT =
        "The previous tool call returned 0 results (empty). " +
            "Try again with simplified or alternative keywords: " +
            "1) shorten to 1-2 core nouns, " +
            "2) switch between Korean and English, " +
            "3) split compound words, " +
            "4) try a different search tool. " +
            "Do NOT tell the user there are no results yet.\n" +
            "이전 도구 호출에서 검색 결과가 0건이었습니다. " +
            "키워드를 변형하여 재시도하세요: " +
            "1) 핵심 명사 1-2개로 축소, " +
            "2) 한국어↔영어 전환, " +
            "3) 복합어 분리, " +
            "4) 다른 검색 도구 시도. " +
            "아직 사용자에게 '결과가 없습니다'라고 말하지 마세요."

    /** 도구 에러 감지 시 LLM에게 tool_call 재시도를 유도하는 힌트 메시지 (영/한 병기). */
    const val TOOL_ERROR_RETRY_HINT =
        "The previous tool call returned an error. " +
            "Analyze the error message, fix the parameters, " +
            "and retry with a corrected tool call. " +
            "Do NOT respond with text.\n" +
            "이전 도구 호출에서 에러가 발생했습니다. " +
            "에러 메시지를 분석하고 파라미터를 수정한 후 " +
            "올바른 도구 호출로 재시도하세요. " +
            "텍스트로 응답하지 마세요."

    /**
     * 도구 에러 후 LLM이 텍스트만 반환했을 때 주입하는 강화 힌트.
     * UserMessage보다 SystemMessage가 LLM 준수율이 높다.
     */
    private const val TOOL_ERROR_FORCE_RETRY_HINT =
        "IMPORTANT: You just responded with text instead of a tool call. " +
            "You MUST make a tool call to retry. Do NOT explain or apologize — " +
            "directly call the tool with corrected parameters.\n" +
            "중요: 방금 텍스트로 응답했습니다. 반드시 도구 호출로 재시도하세요. " +
            "설명이나 사과 없이 수정된 파라미터로 도구를 직접 호출하세요."

    /** 도구 응답에 에러가 포함되어 있는지 확인한다. */
    fun hasToolError(toolResponses: List<ToolResponseMessage.ToolResponse>): Boolean =
        toolResponses.any { it.responseData().startsWith(TOOL_ERROR_PREFIX) }

    /** 도구 응답이 빈 검색 결과인지 확인한다 (에러 아닌 0건 응답). */
    fun hasEmptySearchResult(
        toolResponses: List<ToolResponseMessage.ToolResponse>
    ): Boolean =
        toolResponses.any { resp ->
            val data = resp.responseData()
            !data.startsWith(TOOL_ERROR_PREFIX) &&
                EMPTY_RESULT_PATTERN.containsMatchIn(data)
        }

    /** Google GenAI 프로바이더일 때 Tool 응답을 JSON으로 정규화해야 하는지 판단합니다. */
    fun shouldNormalizeToolResponses(chatOptions: ChatOptions): Boolean =
        chatOptions is GoogleGenAiChatOptions

    /** maxToolCalls 도달 시 LLM에게 최종 답변을 요청하는 SystemMessage를 생성합니다. */
    fun buildMaxToolCallsMessage(totalToolCalls: Int, maxToolCalls: Int): SystemMessage =
        SystemMessage(
            "Tool call limit reached ($totalToolCalls/$maxToolCalls). " +
                "Summarize the results you have so far and provide your best answer. " +
                "Do not request additional tool calls."
        )

    /**
     * 도구 응답 중 에러가 있으면 재시도 힌트를, 빈 결과이면 키워드 변형 힌트를 주입합니다.
     *
     * LLM이 도구 에러를 보고 텍스트 응답("다시 시도하겠습니다")을 생성하는 대신
     * 실제 tool_call을 생성하도록 유도합니다. 이전 iteration의 hint는 제거하여 누적을 방지합니다.
     */
    fun injectToolErrorRetryHint(
        toolResponses: List<ToolResponseMessage.ToolResponse>,
        messages: MutableList<Message>
    ) {
        // 이전 iteration의 retry hint는 항상 제거 — 성공 후에도 stale hint가 남지 않도록
        cleanupRetryHints(messages)

        if (hasToolError(toolResponses)) {
            logger.debug { "도구 에러 감지, 재시도 힌트 주입" }
            messages.add(UserMessage(TOOL_ERROR_RETRY_HINT))
        } else if (hasEmptySearchResult(toolResponses)) {
            logger.debug { "빈 검색 결과 감지, 키워드 변형 힌트 주입" }
            messages.add(UserMessage(EMPTY_RESULT_RETRY_HINT))
        }
    }

    /**
     * 도구 에러 후 LLM이 텍스트만 반환한 경우, 강화 힌트(SystemMessage)를 주입하고 루프 계속 여부를 반환한다.
     *
     * @param messages 현재 메시지 리스트
     * @param textRetryCount 현재까지 텍스트 재시도 횟수
     * @return true이면 루프를 계속해야 함 (강화 힌트 주입 완료), false이면 루프 종료
     */
    fun injectForceRetryHintIfNeeded(
        messages: MutableList<Message>,
        textRetryCount: Int
    ): Boolean {
        if (textRetryCount >= MAX_TEXT_RETRIES_AFTER_TOOL_ERROR) {
            logger.info {
                "Text retry limit reached ($textRetryCount/$MAX_TEXT_RETRIES_AFTER_TOOL_ERROR), " +
                    "accepting text response"
            }
            return false
        }
        cleanupRetryHints(messages)
        logger.info {
            "Tool error followed by text response, " +
                "injecting force-retry hint (attempt ${textRetryCount + 1}/$MAX_TEXT_RETRIES_AFTER_TOOL_ERROR)"
        }
        messages.add(SystemMessage(TOOL_ERROR_FORCE_RETRY_HINT))
        return true
    }

    /** 이전 iteration의 retry hint(UserMessage, SystemMessage 모두)를 제거한다. */
    private fun cleanupRetryHints(messages: MutableList<Message>) {
        messages.removeAll {
            (it is UserMessage && it.text == TOOL_ERROR_RETRY_HINT) ||
                (it is UserMessage && it.text == EMPTY_RESULT_RETRY_HINT) ||
                (it is SystemMessage && it.text == TOOL_ERROR_FORCE_RETRY_HINT)
        }
    }

    // ── 공통 메서드: Manual / Streaming ReAct 루프 양쪽에서 호출 ──

    /**
     * 도구 에러 직후 텍스트 응답(tool_call 없음)이면 강화 힌트를 주입하고
     * 루프 재시도 여부를 반환한다.
     */
    fun shouldRetryAfterToolError(
        hadToolError: Boolean,
        pendingToolCalls: List<AssistantMessage.ToolCall>,
        activeTools: List<Any>,
        messages: MutableList<Message>,
        textRetryCount: Int
    ): Boolean {
        if (!hadToolError || pendingToolCalls.isNotEmpty()
            || activeTools.isEmpty()
        ) {
            return false
        }
        return injectForceRetryHintIfNeeded(messages, textRetryCount)
    }

    /**
     * LLM 응답 메타데이터에서 토큰 사용량을 메트릭 콜백으로 전달한다.
     *
     * @param meta LLM 응답 메타데이터 (null이면 무시)
     * @param hookContext 실행 컨텍스트 (runId, tenantId 등 식별자 추출용)
     * @param recordTokenUsage 토큰 사용량을 기록하는 콜백
     */
    fun emitTokenUsageMetric(
        meta: ChatResponseMetadata?,
        hookContext: HookContext,
        recordTokenUsage: (TokenUsage, Map<String, Any>) -> Unit
    ) {
        if (meta == null) return
        val usage = meta.usage ?: return
        val tokenMetadata = buildMap<String, Any>(3) {
            put("runId", hookContext.runId)
            meta.model?.let { put("model", it) }
            hookContext.metadata["tenantId"]?.let { put("tenantId", it) }
        }
        recordTokenUsage(
            TokenUsage(
                promptTokens = usage.promptTokens.toInt(),
                completionTokens = usage.completionTokens.toInt(),
                totalTokens = usage.totalTokens.toInt()
            ),
            tokenMetadata
        )
    }

    /**
     * 루프 종료 시 LLM/Tool 소요 시간을 HookContext에 기록한다.
     * 스트리밍 모드에서는 [agentMetrics]를 통해 추가 메트릭도 기록한다.
     *
     * @param hookContext 타이밍을 저장할 훅 컨텍스트
     * @param totalLlmDurationMs LLM 호출 총 소요 시간(밀리초)
     * @param totalToolDurationMs 도구 실행 총 소요 시간(밀리초)
     * @param agentMetrics 추가 메트릭 기록기 (null이면 생략)
     * @param metricsMetadata agentMetrics에 전달할 메타데이터 (null이면 생략)
     */
    fun recordLoopDurations(
        hookContext: HookContext,
        totalLlmDurationMs: Long,
        totalToolDurationMs: Long,
        agentMetrics: AgentMetrics? = null,
        metricsMetadata: Map<String, Any>? = null
    ) {
        hookContext.metadata["llmDurationMs"] = totalLlmDurationMs
        hookContext.metadata["toolDurationMs"] = totalToolDurationMs
        recordStageTiming(hookContext, "llm_calls", totalLlmDurationMs)
        recordStageTiming(
            hookContext, "tool_execution", totalToolDurationMs
        )
        if (agentMetrics != null && metricsMetadata != null) {
            agentMetrics.recordStageLatency(
                "llm_calls", totalLlmDurationMs, metricsMetadata
            )
            agentMetrics.recordStageLatency(
                "tool_execution", totalToolDurationMs, metricsMetadata
            )
        }
    }

    /**
     * LLM 호출 후 토큰 예산을 추적하고 EXHAUSTED 여부를 반환한다.
     * tracker가 null이면 항상 false를 반환한다.
     *
     * @param meta LLM 응답 메타데이터 (null이면 false)
     * @param tracker 토큰 예산 추적기 (null이면 false)
     * @param stepName 예산 추적에 사용할 단계 이름
     * @param hookContext 예산 상태를 기록할 훅 컨텍스트
     */
    fun trackBudgetAndCheckExhausted(
        meta: ChatResponseMetadata?,
        tracker: StepBudgetTracker?,
        stepName: String,
        hookContext: HookContext
    ): Boolean {
        tracker ?: return false
        val usage = meta?.usage ?: return false
        val status = tracker.trackStep(
            step = stepName,
            inputTokens = usage.promptTokens.toInt(),
            outputTokens = usage.completionTokens.toInt()
        )
        hookContext.metadata["tokensUsed"] = tracker.totalConsumed()
        hookContext.metadata["budgetStatus"] = status.name
        return status == BudgetStatus.EXHAUSTED
    }
}
