package com.arc.reactor.config

import mu.KotlinLogging
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.model.ChatModel

private val logger = KotlinLogging.logger {}

/**
 * 프로바이더 이름과 ChatModel 빈을 매핑하는 레지스트리.
 *
 * 요청별로 런타임에 LLM 프로바이더를 선택할 수 있게 해준다.
 * 프로바이더를 지정하지 않으면 [defaultProvider]로 폴백한다.
 *
 * 프로바이더 이름은 Spring AI 빈 이름으로부터 해석된다:
 * - `openAiChatModel` -> "openai"
 * - `anthropicChatModel` -> "anthropic"
 * - `googleAiGeminiChatModel` -> "gemini"
 *
 * WHY: 에이전트 실행 시 요청 단위로 다른 LLM을 사용해야 하는 경우가 있다.
 * 예를 들어, 인텐트 분류에는 저비용 모델을, 복잡한 분석에는 고성능 모델을 사용하는 식이다.
 * 이 레지스트리를 통해 프로바이더 전환을 단일 지점에서 관리한다.
 *
 * @param chatModels 프로바이더 이름에서 ChatModel로의 매핑
 * @param defaultProvider 폴백 프로바이더 이름
 * @see com.arc.reactor.autoconfigure.ArcReactorAutoConfiguration 에서 빈 등록
 */
class ChatModelProvider(
    private val chatModels: Map<String, ChatModel>,
    private val defaultProvider: String
) {

    init {
        logger.info {
            "ChatModelProvider 초기화 완료 — 프로바이더: " +
                "${chatModels.keys}, 기본=$defaultProvider"
        }
    }

    /**
     * 지정된 프로바이더 이름에 대한 ChatClient를 반환한다.
     * provider가 null이면 [defaultProvider]로 폴백한다.
     *
     * @param provider 프로바이더 이름 (null이면 기본 프로바이더 사용)
     * @return 해당 프로바이더의 ChatClient
     * @throws IllegalArgumentException 알 수 없는 프로바이더 이름인 경우
     */
    fun getChatClient(provider: String?): ChatClient {
        val name = provider ?: defaultProvider
        val model = chatModels[name]
            ?: throw IllegalArgumentException(
                "알 수 없는 프로바이더: $name. " +
                    "사용 가능: ${chatModels.keys}"
            )
        return ChatClient.create(model)
    }

    /** 사용 가능한 프로바이더 이름 집합을 반환한다. */
    fun availableProviders(): Set<String> = chatModels.keys

    /** 기본 프로바이더 이름을 반환한다. */
    fun defaultProvider(): String = defaultProvider

    companion object {
        /**
         * Spring AI 빈 이름에서 사람이 읽기 쉬운 프로바이더 이름으로의 매핑.
         * WHY: Spring AI가 빈 이름을 자동 생성하는데, 이 이름이 직관적이지 않으므로
         * 짧고 명확한 별칭으로 변환한다.
         */
        private val BEAN_NAME_MAPPING = mapOf(
            "openAiChatModel" to "openai",
            "anthropicChatModel" to "anthropic",
            "googleAiGeminiChatModel" to "gemini",
            "googleGenAiChatModel" to "gemini",
            "vertexAiGeminiChatModel" to "vertex"
        )

        /**
         * Spring AI 빈 이름에서 사용자 친화적인 프로바이더 이름을 해석한다.
         * 알 수 없는 빈 이름은 원래 이름 그대로 반환한다.
         *
         * @param beanName Spring AI ChatModel 빈 이름
         * @return 해석된 프로바이더 이름
         */
        fun resolveProviderName(beanName: String): String {
            return BEAN_NAME_MAPPING[beanName] ?: beanName
        }
    }
}
