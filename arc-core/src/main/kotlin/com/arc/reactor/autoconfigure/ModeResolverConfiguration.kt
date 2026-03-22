package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.routing.AgentModeResolver
import com.arc.reactor.agent.routing.DefaultAgentModeResolver
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 자동 모드 선택(Mode Resolver) 자동 설정.
 *
 * `arc.reactor.mode-resolver.enabled=true`일 때 [DefaultAgentModeResolver]를 등록한다.
 * 사용자는 [AgentModeResolver] 빈을 직접 정의하여 기본 구현을 재정의할 수 있다.
 *
 * @see com.arc.reactor.agent.routing.AgentModeResolver 인터페이스
 * @see com.arc.reactor.agent.routing.DefaultAgentModeResolver 기본 구현
 */
@Configuration
@ConditionalOnProperty(
    prefix = "arc.reactor.mode-resolver", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
class ModeResolverConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun agentModeResolver(): AgentModeResolver = DefaultAgentModeResolver()
}
