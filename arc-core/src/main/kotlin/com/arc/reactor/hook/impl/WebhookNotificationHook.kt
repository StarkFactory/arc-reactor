package com.arc.reactor.hook.impl

import com.arc.reactor.hook.AfterAgentCompleteHook
import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.support.throwIfCancellation
import kotlinx.coroutines.reactor.awaitSingleOrNull
import mu.KotlinLogging
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Webhook 알림 Hook
 *
 * 에이전트 실행 완료 후 외부 URL로 HTTP POST 알림을 전송한다.
 * 모니터링 시스템, Slack, 커스텀 대시보드 등과 통합하는 데 유용하다.
 *
 * ## 설정 예시
 * ```yaml
 * arc:
 *   reactor:
 *     webhook:
 *       enabled: true
 *       url: https://example.com/webhook
 *       timeout-ms: 5000
 *       include-conversation: false
 * ```
 *
 * ## 동작 방식
 * - 에이전트 완료 후(성공/실패 무관) 비동기 전송
 * - 에이전트 응답을 절대 차단하거나 실패시키지 않음 (fail-open, order 200)
 * - 타임아웃으로 느린 엔드포인트에 의한 지연을 방지
 *
 * @param webhookProperties Webhook 설정 속성
 * @param webClient HTTP 클라이언트 (기본값: 기본 WebClient)
 *
 * @see com.arc.reactor.hook.AfterAgentCompleteHook 에이전트 완료 후 Hook 인터페이스
 */
class WebhookNotificationHook(
    private val webhookProperties: WebhookProperties,
    private val webClient: WebClient = WebClient.create()
) : AfterAgentCompleteHook {

    // 200+: 후기 Hook (알림, 정리)
    override val order: Int = 200

    // Webhook 실패가 에이전트를 차단하면 안 됨
    override val failOnError: Boolean = false

    override suspend fun afterAgentComplete(context: HookContext, response: AgentResponse) {
        val url = webhookProperties.url
        if (url.isBlank()) {
            logger.warn { "Webhook URL is blank, skipping notification" }
            return
        }

        val payload = buildPayload(context, response)

        try {
            webClient.post()
                .uri(url)
                .bodyValue(payload)
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofMillis(webhookProperties.timeoutMs))
                .onErrorResume { e ->
                    e.throwIfCancellation()
                    logger.warn { "Webhook POST to $url failed: ${e.message}" }
                    Mono.empty()
                }
                .awaitSingleOrNull()

            logger.debug { "Webhook notification sent to $url for runId=${context.runId}" }
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn { "Webhook notification failed for runId=${context.runId}: ${e.message}" }
        }
    }

    /**
     * Webhook 페이로드를 구성한다.
     * 성공 시 콘텐츠 미리보기를, 실패 시 오류 메시지를 포함한다.
     * [WebhookProperties.includeConversation]이 true이면 전체 대화 내용도 포함한다.
     */
    private fun buildPayload(context: HookContext, response: AgentResponse): Map<String, Any?> {
        val payload = mutableMapOf<String, Any?>(
            "event" to "AGENT_COMPLETE",
            "timestamp" to Instant.now().toString(),
            "runId" to context.runId,
            "userId" to context.userId,
            "success" to response.success,
            "toolsUsed" to response.toolsUsed,
            "durationMs" to response.totalDurationMs
        )

        if (response.success) {
            payload["contentPreview"] = response.response?.take(200)
        } else {
            payload["errorMessage"] = response.errorMessage
        }

        if (webhookProperties.includeConversation) {
            payload["userPrompt"] = context.userPrompt
            payload["fullResponse"] = response.response
        }

        return payload
    }
}

/**
 * Webhook 설정 속성
 *
 * `arc.reactor.webhook.*` 접두사로 설정된다.
 *
 * @property enabled Webhook 활성화 여부
 * @property url POST 대상 URL
 * @property timeoutMs HTTP 타임아웃 (밀리초)
 * @property includeConversation 페이로드에 전체 대화 내용을 포함할지 여부
 */
data class WebhookProperties(
    /** Webhook 활성화 여부 */
    val enabled: Boolean = false,

    /** POST 대상 URL */
    val url: String = "",

    /** HTTP 타임아웃 (밀리초) */
    val timeoutMs: Long = 5000,

    /** 페이로드에 전체 대화 내용을 포함할지 여부 */
    val includeConversation: Boolean = false
)
