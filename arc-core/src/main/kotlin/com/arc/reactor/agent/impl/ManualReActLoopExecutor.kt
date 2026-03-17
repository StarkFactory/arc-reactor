package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.MediaConverter
import com.arc.reactor.agent.model.ResponseFormat
import com.arc.reactor.agent.model.TokenUsage
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.tracing.ArcReactorTracer
import com.arc.reactor.tracing.NoOpArcReactorTracer
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import mu.KotlinLogging
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.google.genai.GoogleGenAiChatOptions
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

/**
 * 수동 ReAct 루프 실행기 — LLM 호출과 Tool 실행을 반복하는 핵심 루프 본체.
 *
 * Spring AI의 내장 Tool 실행 루프를 사용하지 않고 직접 루프를 관리합니다.
 * 이를 통해 maxToolCalls 제한, Hook 실행, 메트릭 기록 등을 세밀하게 제어합니다.
 *
 * 루프 흐름:
 * 1. 메시지 트리밍 (컨텍스트 윈도우 초과 방지)
 * 2. LLM 호출 (재시도 포함)
 * 3. Tool Call 감지 -> 있으면 [ToolCallOrchestrator]로 병렬 실행
 * 4. 결과 메시지(AssistantMessage + ToolResponseMessage) 추가
 * 5. maxToolCalls 도달 시 Tool 비활성화 후 최종 답변 요청
 * 6. Tool Call이 없으면 응답 검증 후 반환
 *
 * @see SpringAiAgentExecutor 이 실행기를 생성하고 호출하는 상위 클래스
 * @see ToolCallOrchestrator 병렬 도구 실행 위임 대상
 * @see ConversationMessageTrimmer 컨텍스트 윈도우 내 메시지 트리밍
 */
internal class ManualReActLoopExecutor(
    private val messageTrimmer: ConversationMessageTrimmer,
    private val toolCallOrchestrator: ToolCallOrchestrator,
    private val buildRequestSpec: (
        ChatClient,
        String,
        List<Message>,
        ChatOptions,
        List<Any>
    ) -> ChatClient.ChatClientRequestSpec,
    private val callWithRetry: suspend (
        suspend () -> org.springframework.ai.chat.model.ChatResponse?
    ) -> org.springframework.ai.chat.model.ChatResponse?,
    private val buildChatOptions: (AgentCommand, Boolean) -> ChatOptions,
    private val validateAndRepairResponse: suspend (
        String,
        ResponseFormat,
        AgentCommand,
        TokenUsage?,
        List<String>
    ) -> AgentResult,
    private val recordTokenUsage: (TokenUsage, Map<String, Any>) -> Unit,
    private val tracer: ArcReactorTracer = NoOpArcReactorTracer()
) {

    /**
     * ReAct 루프를 실행하여 에이전트 결과를 반환합니다.
     *
     * @param command 에이전트 실행 명령
     * @param activeChatClient 사용할 ChatClient (모델별 분기 적용 완료)
     * @param systemPrompt RAG 컨텍스트와 응답 형식이 포함된 시스템 프롬프트
     * @param initialTools 선택된 Tool 목록 (maxToolCalls=0이면 비활성화됨)
     * @param conversationHistory 기존 대화 히스토리
     * @param hookContext Hook/메트릭용 실행 컨텍스트
     * @param toolsUsed 실행된 도구 이름을 누적하는 리스트
     * @param allowedTools Intent 기반 Tool 허용 목록 (null이면 전체 허용)
     * @param maxToolCalls 최대 Tool 호출 횟수
     * @return 에이전트 실행 결과
     * @see ToolCallOrchestrator.executeInParallel
     */
    suspend fun execute(
        command: AgentCommand,
        activeChatClient: ChatClient,
        systemPrompt: String,
        initialTools: List<Any>,
        conversationHistory: List<Message>,
        hookContext: HookContext,
        toolsUsed: MutableList<String>,
        allowedTools: Set<String>?,
        maxToolCalls: Int
    ): AgentResult {
        // ── 루프 상태 초기화 ──
        var totalToolCalls = 0
        var llmCallIndex = 0
        var activeTools = if (maxToolCalls > 0) initialTools else emptyList()
        var chatOptions = buildChatOptions(command, activeTools.isNotEmpty())
        var totalTokenUsage: TokenUsage? = null
        var totalLlmDurationMs = 0L
        var totalToolDurationMs = 0L

        // ── 대화 히스토리 + 현재 사용자 메시지 조합 ──
        val messages = mutableListOf<Message>()
        if (conversationHistory.isNotEmpty()) {
            messages.addAll(conversationHistory)
        }
        messages.add(MediaConverter.buildUserMessage(command.userPrompt, command.media))

        // ── ReAct 루프 시작 — Tool Call이 없을 때까지 반복 ──
        while (true) {
            // 단계 A: 컨텍스트 윈도우 초과 방지를 위한 메시지 트리밍
            messageTrimmer.trim(messages, systemPrompt, activeTools.size * TOKENS_PER_TOOL_DEFINITION)

            // 단계 B: LLM 호출 (재시도 포함)
            val requestSpec = buildRequestSpec(activeChatClient, systemPrompt, messages, chatOptions, activeTools)
            val llmStart = System.nanoTime()
            val llmSpan = tracer.startSpan(
                "arc.agent.llm.call",
                mapOf("llm.call.index" to llmCallIndex.toString())
            )
            val chatResponse = try {
                callWithRetry {
                    runInterruptible(Dispatchers.IO) { requestSpec.call().chatResponse() }
                }
            } finally {
                llmSpan.close()
            }
            llmCallIndex++
            totalLlmDurationMs += (System.nanoTime() - llmStart) / 1_000_000

            totalTokenUsage = accumulateTokenUsage(chatResponse, totalTokenUsage)
            chatResponse?.metadata?.let { meta ->
                val usage = meta.usage ?: return@let
                val enrichedMetadata = buildMap<String, Any> {
                    putAll(hookContext.metadata)
                    put("runId", hookContext.runId)
                    meta.model?.let { put("model", it) }
                }
                recordTokenUsage(
                    TokenUsage(
                        promptTokens = usage.promptTokens.toInt(),
                        completionTokens = usage.completionTokens.toInt(),
                        totalTokens = usage.totalTokens.toInt()
                    ),
                    enrichedMetadata
                )
            }

            // 단계 C: Tool Call 존재 여부 확인 — 없으면 루프 종료
            val assistantOutput = chatResponse?.results?.firstOrNull()?.output
            val pendingToolCalls = assistantOutput?.toolCalls.orEmpty()
            if (pendingToolCalls.isEmpty() || activeTools.isEmpty()) {
                hookContext.metadata["llmDurationMs"] = totalLlmDurationMs
                hookContext.metadata["toolDurationMs"] = totalToolDurationMs
                recordStageTiming(hookContext, "llm_calls", totalLlmDurationMs)
                recordStageTiming(hookContext, "tool_execution", totalToolDurationMs)
                return validateAndRepairResponse(
                    assistantOutput?.text.orEmpty(),
                    command.responseFormat,
                    command,
                    totalTokenUsage,
                    ArrayList(toolsUsed)
                )
            }

            if (assistantOutput == null) {
                logger.error { "Assistant output is null despite pending tool calls" }
                return AgentResult.failure(
                    errorMessage = "Assistant output is null when tool calls are present",
                    errorCode = AgentErrorCode.UNKNOWN
                )
            }

            // 단계 D: Tool 병렬 실행 — ToolCallOrchestrator에 위임
            val totalToolCallsCounter = AtomicInteger(totalToolCalls)
            val toolStart = System.nanoTime()
            val toolSpans = pendingToolCalls.mapIndexed { idx, tc ->
                tracer.startSpan(
                    "arc.agent.tool.call",
                    mapOf(
                        "tool.name" to tc.name(),
                        "tool.call.index" to (totalToolCalls + idx).toString()
                    )
                )
            }
            val toolResponses = try {
                toolCallOrchestrator.executeInParallel(
                    pendingToolCalls,
                    activeTools,
                    hookContext,
                    toolsUsed,
                    totalToolCallsCounter,
                    maxToolCalls,
                    allowedTools,
                    normalizeToolResponseToJson = shouldNormalizeToolResponses(chatOptions)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Tool execution failed, skipping message pair" }
                throw e
            } finally {
                toolSpans.forEach { it.close() }
            }
            totalToolDurationMs += (System.nanoTime() - toolStart) / 1_000_000
            totalToolCalls = totalToolCallsCounter.get()

            // 단계 E: AssistantMessage + ToolResponseMessage 쌍으로 추가 (메시지 쌍 무결성 필수)
            messages.add(assistantOutput)
            messages.add(
                ToolResponseMessage.builder()
                    .responses(toolResponses)
                    .build()
            )

            // 단계 E-2: 도구 에러 시 재시도 힌트 주입 — LLM이 텍스트 대신 tool_call을 생성하도록 유도
            if (totalToolCalls < maxToolCalls) {
                injectToolErrorRetryHint(toolResponses, messages)
            }

            // 단계 F: maxToolCalls 도달 시 Tool 비활성화 — 무한 루프 방지 필수
            if (totalToolCalls >= maxToolCalls) {
                logger.info { "maxToolCalls reached ($totalToolCalls/$maxToolCalls), final answer" }
                activeTools = emptyList()
                chatOptions = buildChatOptions(command, false)
                messages.add(
                    org.springframework.ai.chat.messages.SystemMessage(
                        "Tool call limit reached ($totalToolCalls/$maxToolCalls). " +
                            "Summarize the results you have so far and provide your best answer. " +
                            "Do not request additional tool calls."
                    )
                )
            }
        }
    }

    /** 여러 LLM 호출의 Token 사용량을 누적합니다. */
    private fun accumulateTokenUsage(
        chatResponse: org.springframework.ai.chat.model.ChatResponse?,
        previous: TokenUsage?
    ): TokenUsage? {
        val usage = chatResponse?.metadata?.usage ?: return previous
        val current = TokenUsage(
            promptTokens = usage.promptTokens.toInt(),
            completionTokens = usage.completionTokens.toInt(),
            totalTokens = usage.totalTokens.toInt()
        )
        return previous?.let {
            TokenUsage(
                promptTokens = it.promptTokens + current.promptTokens,
                completionTokens = it.completionTokens + current.completionTokens,
                totalTokens = it.totalTokens + current.totalTokens
            )
        } ?: current
    }

    /** Google GenAI 프로바이더일 때 Tool 응답을 JSON으로 정규화해야 하는지 판단합니다. */
    private fun shouldNormalizeToolResponses(chatOptions: ChatOptions): Boolean {
        return chatOptions is GoogleGenAiChatOptions
    }

    companion object {
        /** 도구당 보수적 토큰 추정치 (이름 + 설명 + JSON 스키마). */
        private const val TOKENS_PER_TOOL_DEFINITION = 200

        /** 도구 에러 감지 시 LLM에게 tool_call 재시도를 유도하는 힌트 메시지. */
        internal const val TOOL_ERROR_RETRY_HINT =
            "The previous tool call returned an error. " +
                "Analyze the error message, fix the parameters, " +
                "and retry with a corrected tool call. " +
                "Do NOT respond with text."

        /**
         * 도구 응답 중 에러가 있으면 재시도 힌트 UserMessage를 주입합니다.
         *
         * LLM이 도구 에러를 보고 텍스트 응답("다시 시도하겠습니다")을 생성하는 대신
         * 실제 tool_call을 생성하도록 유도합니다.
         */
        internal fun injectToolErrorRetryHint(
            toolResponses: List<ToolResponseMessage.ToolResponse>,
            messages: MutableList<Message>
        ) {
            val hasError = toolResponses.any { it.responseData().startsWith("Error:") }
            if (hasError) {
                logger.debug { "Tool error detected, injecting retry hint" }
                messages.add(UserMessage(TOOL_ERROR_RETRY_HINT))
            }
        }
    }
}
