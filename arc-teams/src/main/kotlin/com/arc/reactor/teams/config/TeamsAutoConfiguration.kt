package com.arc.reactor.teams.config

import com.arc.reactor.scheduler.TeamsMessageSender
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Microsoft Teams 통합 모듈 자동 설정.
 *
 * `arc.reactor.teams.enabled=true`일 때 활성화된다.
 * 모든 빈은 `@ConditionalOnMissingBean`으로 선언되어 사용자 정의 구현으로 교체 가능하다.
 *
 * @see TeamsProperties 설정 프로퍼티
 * @see TeamsWebhookClient Incoming Webhook 메시지 전송 클라이언트
 */
@Configuration
@ConditionalOnProperty(prefix = "arc.reactor.teams", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(TeamsProperties::class)
class TeamsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun teamsWebhookClient(properties: TeamsProperties): TeamsMessageSender =
        TeamsWebhookClient(properties)
}
