package com.arc.reactor.errorreport.config

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.errorreport.handler.DefaultErrorReportHandler
import com.arc.reactor.errorreport.handler.ErrorReportHandler
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 오류 리포트 모듈 자동 설정.
 *
 * `arc.reactor.error-report.enabled=true`일 때 활성화된다.
 * 모든 빈은 `@ConditionalOnMissingBean`으로 선언되어 사용자 정의 구현으로 교체 가능하다.
 *
 * @see ErrorReportProperties 설정 프로퍼티
 * @see DefaultErrorReportHandler 기본 핸들러 (AgentExecutor 위임)
 * @see ErrorReportController REST 엔드포인트
 */
@Configuration
@ConditionalOnProperty(
    prefix = "arc.reactor.error-report", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
@EnableConfigurationProperties(ErrorReportProperties::class)
class ErrorReportAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(AgentExecutor::class)
    fun errorReportHandler(
        agentExecutor: AgentExecutor,
        properties: ErrorReportProperties
    ): ErrorReportHandler = DefaultErrorReportHandler(
        agentExecutor = agentExecutor,
        properties = properties
    )
}
