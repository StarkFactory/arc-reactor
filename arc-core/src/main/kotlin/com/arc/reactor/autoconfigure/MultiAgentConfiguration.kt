package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.multiagent.AgentRegistry
import com.arc.reactor.agent.multiagent.DefaultAgentRegistry
import com.arc.reactor.agent.multiagent.DefaultSupervisorAgent
import com.arc.reactor.agent.multiagent.SupervisorAgent
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 멀티 에이전트(Supervisor 패턴) 자동 설정.
 *
 * `arc.reactor.multi-agent.enabled=true`일 때
 * [AgentRegistry]와 [SupervisorAgent]를 등록한다.
 * 사용자는 각 빈을 직접 정의하여 기본 구현을 재정의할 수 있다.
 *
 * @see com.arc.reactor.agent.multiagent.AgentRegistry 에이전트 레지스트리
 * @see com.arc.reactor.agent.multiagent.SupervisorAgent Supervisor 에이전트
 */
@Configuration
@ConditionalOnProperty(
    prefix = "arc.reactor.multi-agent", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
class MultiAgentConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun agentRegistry(): AgentRegistry = DefaultAgentRegistry()

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(AgentExecutor::class)
    fun supervisorAgent(
        agentExecutor: AgentExecutor,
        agentRegistry: AgentRegistry,
        properties: AgentProperties
    ): SupervisorAgent = DefaultSupervisorAgent(
        agentExecutor = agentExecutor,
        agentRegistry = agentRegistry,
        maxDelegations = properties.multiAgent.maxDelegations
    )
}
