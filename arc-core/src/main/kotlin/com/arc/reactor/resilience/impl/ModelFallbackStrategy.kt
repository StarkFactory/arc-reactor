package com.arc.reactor.resilience.impl

import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.metrics.NoOpAgentMetrics
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.config.ChatModelProvider
import com.arc.reactor.resilience.FallbackStrategy
import com.arc.reactor.support.throwIfCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 대체 LLM 모델로 요청을 재시도하는 폴백 전략.
 *
 * 모델들을 순서대로 시도한다. 첫 번째 성공한 응답을 반환한다.
 * 모든 모델이 실패하면 `null`을 반환하여 폴백 소진을 알린다.
 *
 * 폴백 호출은 단순 LLM 호출(도구 없음, ReAct 루프 없음)로 수행하여
 * 성능 저하 상황에서 성공 확률을 극대화한다.
 *
 * ## 왜 단순 호출인가?
 * 폴백 상황은 이미 장애가 발생한 상태이다.
 * 도구 호출이나 ReAct 루프를 포함하면 추가 실패 포인트가 생긴다.
 * 단순 LLM 호출은 가장 안정적인 폴백 경로이다.
 *
 * @see FallbackStrategy 인터페이스 계약
 *
 * ## 설정 예시
 * ```yaml
 * arc:
 *   reactor:
 *     fallback:
 *       enabled: true
 *       models:
 *         - openai
 *         - anthropic
 * ```
 */
class ModelFallbackStrategy(
    private val fallbackModels: List<String>,
    private val chatModelProvider: ChatModelProvider,
    private val agentMetrics: AgentMetrics = NoOpAgentMetrics()
) : FallbackStrategy {

    override suspend fun execute(command: AgentCommand, originalError: Exception): AgentResult? {
        if (fallbackModels.isEmpty()) return null

        for (model in fallbackModels) {
            try {
                logger.info { "폴백 모델 시도: $model" }
                val chatClient = chatModelProvider.getChatClient(model)
                // Dispatchers.IO로 블로킹 HTTP 호출을 오프로드
                val response = runInterruptible(Dispatchers.IO) {
                    chatClient.prompt()
                        .system(command.systemPrompt)
                        .user(command.userPrompt)
                        .call()
                        .chatResponse()
                }
                val content = response?.results?.firstOrNull()?.output?.text
                if (!content.isNullOrBlank()) {
                    logger.info { "폴백 모델 '$model' 성공" }
                    agentMetrics.recordFallbackAttempt(model, success = true)
                    return AgentResult.success(content = content)
                }
                logger.warn { "폴백 모델 '$model' 빈 응답 반환" }
                agentMetrics.recordFallbackAttempt(model, success = false)
            } catch (e: Exception) {
                e.throwIfCancellation()
                logger.warn(e) { "폴백 모델 '$model' 실패" }
                agentMetrics.recordFallbackAttempt(model, success = false)
            }
        }

        logger.warn { "모든 폴백 모델 소진: $fallbackModels" }
        return null
    }
}
