package com.arc.reactor.teams.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Microsoft Teams 통합 모듈 설정 프로퍼티.
 *
 * 프리픽스: `arc.reactor.teams`
 *
 * @property enabled Teams 통합 활성화 여부
 * @property defaultWebhookUrl 작업(job)에 webhook URL이 지정되지 않았을 때 사용하는 기본 Teams Incoming Webhook URL
 * @see TeamsAutoConfiguration 자동 설정
 * @see TeamsWebhookClient webhook 전송 클라이언트
 */
@ConfigurationProperties(prefix = "arc.reactor.teams")
data class TeamsProperties(
    val enabled: Boolean = false,
    val defaultWebhookUrl: String = ""
)
