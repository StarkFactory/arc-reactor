package com.arc.reactor.autoconfigure

import com.arc.reactor.controller.McpAdminWebClientFactory
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import org.springframework.web.server.WebFilter

/**
 * Arc Reactor 웹 자동 구성.
 *
 * HTTP/REST 계층 빈(보안 헤더, CORS, OpenAPI)을 구성합니다.
 * 모듈형 게이트웨이 아키텍처를 지원하기 위해 코어 자동 구성과 분리되어 있습니다.
 */
@AutoConfiguration
class ArcReactorWebAutoConfiguration {

    /**
     * MCP admin 프록시 컨트롤러용 공유 WebClient 팩토리.
     * DisposableBean을 구현하여 종료 시 Netty ConnectionProvider를 해제한다.
     */
    @Bean
    @ConditionalOnMissingBean
    fun mcpAdminWebClientFactory(): McpAdminWebClientFactory = McpAdminWebClientFactory()

    /** 보안 헤더 WebFilter (기본값: 활성화). */
    @Bean
    @ConditionalOnMissingBean(name = ["securityHeadersWebFilter"])
    @ConditionalOnProperty(
        prefix = "arc.reactor.security-headers", name = ["enabled"],
        havingValue = "true", matchIfMissing = true
    )
    fun securityHeadersWebFilter(): WebFilter = SecurityHeadersWebFilter()

    /** 요청 상관 ID WebFilter (기본값: 활성화). */
    @Bean
    @ConditionalOnMissingBean(name = ["requestCorrelationFilter"])
    @ConditionalOnProperty(
        prefix = "arc.reactor.request-correlation", name = ["enabled"],
        havingValue = "true", matchIfMissing = true
    )
    fun requestCorrelationFilter(): WebFilter = RequestCorrelationFilter()

    /** API 버전 계약 WebFilter (기본값: 활성화). */
    @Bean
    @ConditionalOnMissingBean(name = ["apiVersionContractWebFilter"])
    @ConditionalOnProperty(
        prefix = "arc.reactor.api-version", name = ["enabled"],
        havingValue = "true", matchIfMissing = true
    )
    fun apiVersionContractWebFilter(
        objectMapper: ObjectMapper,
        environment: Environment
    ): WebFilter {
        val currentVersion = environment.getProperty("arc.reactor.api-version.current", "v1")
        val supportedVersions = environment
            .getProperty("arc.reactor.api-version.supported", currentVersion)
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
            .ifEmpty { setOf(currentVersion) }
        return ApiVersionContractWebFilter(
            objectMapper = objectMapper,
            currentVersion = currentVersion,
            supportedVersions = supportedVersions
        )
    }
}
