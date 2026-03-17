package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.routing.CostAwareModelRouter
import com.arc.reactor.agent.routing.ModelRouter
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 동적 모델 라우팅 자동 설정.
 *
 * `arc.reactor.model-routing.enabled=true`일 때 [CostAwareModelRouter]를 등록한다.
 * 사용자는 [ModelRouter] 빈을 직접 정의하여 기본 구현을 재정의할 수 있다.
 *
 * @see com.arc.reactor.agent.routing.ModelRouter 라우팅 인터페이스
 * @see com.arc.reactor.agent.routing.CostAwareModelRouter 비용/품질 기반 기본 구현
 */
@Configuration
@ConditionalOnProperty(
    prefix = "arc.reactor.model-routing", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
class ModelRoutingConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun modelRouter(properties: AgentProperties): ModelRouter =
        CostAwareModelRouter(properties.modelRouting)
}
