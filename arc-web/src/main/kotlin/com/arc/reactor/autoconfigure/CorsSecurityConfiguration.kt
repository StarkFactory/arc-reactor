package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.reactive.CorsWebFilter
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource

/**
 * Arc Reactor API CORS 설정.
 *
 * 옵트인: `arc.reactor.cors.enabled=true`로 활성화합니다.
 *
 * `arc.reactor.cors.*` 프로퍼티로 구성 가능:
 * - `allowed-origins` : 허용 오리진 (기본값: `http://localhost:3000`)
 * - `allowed-methods` : 허용 HTTP 메서드 (기본값: GET, POST, PUT, DELETE, OPTIONS)
 * - `allowed-headers` : 허용 헤더 (기본값: `*`)
 * - `allow-credentials` : 자격 증명 허용 (기본값: false)
 * - `max-age` : Preflight 캐시 지속 시간(초) (기본값: 3600)
 */
@Configuration
@ConditionalOnProperty(
    prefix = "arc.reactor.cors", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
class CorsSecurityConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun corsWebFilter(properties: AgentProperties): CorsWebFilter {
        val corsProps = properties.cors
        val config = CorsConfiguration().apply {
            allowedOrigins = corsProps.allowedOrigins
            allowedMethods = corsProps.allowedMethods
            allowedHeaders = corsProps.allowedHeaders
            allowCredentials = corsProps.allowCredentials
            maxAge = corsProps.maxAge
        }

        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/api/**", config)
        return CorsWebFilter(source)
    }
}
