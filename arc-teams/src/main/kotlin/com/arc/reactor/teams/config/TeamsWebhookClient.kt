package com.arc.reactor.teams.config

import com.arc.reactor.mcp.isPrivateOrReservedAddress
import com.arc.reactor.scheduler.TeamsMessageSender
import mu.KotlinLogging
import org.springframework.web.reactive.function.client.WebClient
import java.net.URI

private val logger = KotlinLogging.logger {}

private const val MAX_MESSAGE_LENGTH = 3000

/**
 * Microsoft Teams Incoming Webhook을 통해 MessageCard 형식으로 알림을 전송한다.
 *
 * HTTP 오류 발생 시 경고를 로깅하고 예외를 재전파하지 않는다 --
 * [SlackMessageSender]의 에러 정책과 일관성을 유지한다.
 *
 * SSRF 방지: 기본적으로 프라이빗/예약 IP 주소로의 webhook 전송을 차단한다.
 * 메시지가 [MAX_MESSAGE_LENGTH]를 초과하면 잘라서 전송한다.
 *
 * @param properties Teams 설정 프로퍼티
 * @param ssrfProtectionEnabled SSRF 보호 활성화 여부 (기본: true)
 * @see TeamsAutoConfiguration 이 클라이언트를 빈으로 등록하는 자동 설정
 */
class TeamsWebhookClient(
    private val properties: TeamsProperties,
    private val ssrfProtectionEnabled: Boolean = true
) : TeamsMessageSender {

    private val webClient = WebClient.builder().build()

    override fun sendMessage(webhookUrl: String, text: String) {
        // SSRF 방지: 프라이빗/예약 IP 주소로의 요청을 차단한다
        if (ssrfProtectionEnabled) {
            val host = try { URI(webhookUrl).host } catch (_: Exception) { null }
            if (isPrivateOrReservedAddress(host)) {
                logger.warn { "Blocked Teams webhook to private/reserved address: $host" }
                return
            }
        }
        val truncated = if (text.length > MAX_MESSAGE_LENGTH) text.take(MAX_MESSAGE_LENGTH) + "\n..." else text
        val payload = buildMessageCard(truncated)
        try {
            webClient.post()
                .uri(webhookUrl)
                .header("Content-Type", "application/json")
                .bodyValue(payload)
                .retrieve()
                .toBodilessEntity()
                .block()
            logger.debug { "Teams webhook message sent to $webhookUrl" }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to send Teams webhook message to $webhookUrl" }
        }
    }

    /** Teams MessageCard JSON 페이로드를 생성한다. */
    private fun buildMessageCard(text: String): String {
        val escaped = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return """
            {
              "@type": "MessageCard",
              "@context": "http://schema.org/extensions",
              "summary": "Arc Reactor Briefing",
              "themeColor": "0078D4",
              "text": "$escaped"
            }
        """.trimIndent()
    }
}
