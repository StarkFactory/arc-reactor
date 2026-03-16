package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.AgentCommand
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.google.genai.GoogleGenAiChatOptions
import org.springframework.ai.model.tool.ToolCallingChatOptions

/**
 * LLM 프로바이더와 도구 사용 여부에 따라 적절한 [ChatOptions]를 생성하는 팩토리.
 *
 * 프로바이더별 분기:
 * - **Gemini/Vertex**: [GoogleGenAiChatOptions] 사용 (Google Search Retrieval 지원)
 * - **기타 (OpenAI, Anthropic 등)**: 도구 있으면 [ToolCallingChatOptions], 없으면 기본 [ChatOptions]
 *
 * 도구가 있을 때 `internalToolExecutionEnabled=false`로 설정하여
 * Spring AI의 자동 도구 실행을 비활성화하고, ReAct 루프에서 수동으로 도구를 실행한다.
 *
 * @see PromptRequestSpecBuilder 생성된 ChatOptions를 요청 스펙에 적용
 * @see com.arc.reactor.agent.config.LlmProperties LLM 기본 설정값
 */
internal class ChatOptionsFactory(
    private val defaultTemperature: Double,
    private val maxOutputTokens: Int,
    private val googleSearchRetrievalEnabled: Boolean,
    private val topP: Double? = null,
    private val frequencyPenalty: Double? = null,
    private val presencePenalty: Double? = null
) {

    /**
     * 명령과 도구 유무에 따라 [ChatOptions]를 생성한다.
     *
     * @param command 에이전트 명령 (temperature, model 오버라이드 가능)
     * @param hasTools 도구가 하나 이상 있는지 여부
     * @param fallbackProvider 명령에 모델이 지정되지 않은 경우 사용할 기본 프로바이더
     * @return 프로바이더에 맞는 ChatOptions 인스턴스
     */
    fun create(command: AgentCommand, hasTools: Boolean, fallbackProvider: String): ChatOptions {
        val temperature = command.temperature ?: defaultTemperature
        val provider = command.model ?: fallbackProvider
        val isGemini = provider.equals("gemini", ignoreCase = true) || provider.equals("vertex", ignoreCase = true)

        // ── Gemini/Vertex 전용 옵션 (Google Search Retrieval 포함) ──
        if (isGemini) {
            return GoogleGenAiChatOptions.builder()
                .temperature(temperature)
                .maxOutputTokens(maxOutputTokens)
                .topP(topP)
                .frequencyPenalty(frequencyPenalty)
                .presencePenalty(presencePenalty)
                .googleSearchRetrieval(googleSearchRetrievalEnabled)
                .internalToolExecutionEnabled(!hasTools)
                .build()
        }

        // ── 범용 프로바이더: 도구 유무에 따라 옵션 타입 분기 ──
        return if (hasTools) {
            ToolCallingChatOptions.builder()
                .temperature(temperature)
                .maxTokens(maxOutputTokens)
                .topP(topP)
                .frequencyPenalty(frequencyPenalty)
                .presencePenalty(presencePenalty)
                .internalToolExecutionEnabled(false)
                .build()
        } else {
            ChatOptions.builder()
                .temperature(temperature)
                .maxTokens(maxOutputTokens)
                .topP(topP)
                .frequencyPenalty(frequencyPenalty)
                .presencePenalty(presencePenalty)
                .build()
        }
    }
}
