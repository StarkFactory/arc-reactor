package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.tool.idempotency.InMemoryToolIdempotencyGuard
import com.arc.reactor.tool.idempotency.ToolIdempotencyGuard
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 도구 멱등성 보호 자동 설정.
 *
 * `arc.reactor.tool-idempotency.enabled=true`일 때 활성화되며,
 * 동일한 도구 호출의 중복 실행을 방지하는 [ToolIdempotencyGuard] 빈을 등록한다.
 */
@Configuration
@ConditionalOnProperty(
    prefix = "arc.reactor.tool-idempotency", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
class ToolIdempotencyConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun toolIdempotencyGuard(properties: AgentProperties): ToolIdempotencyGuard {
        val props = properties.toolIdempotency
        return InMemoryToolIdempotencyGuard(
            ttlSeconds = props.ttlSeconds,
            maxSize = props.maxSize
        )
    }
}
