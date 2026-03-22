package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.multiagent.AgentMessageBus
import com.arc.reactor.agent.multiagent.AgentRegistry
import com.arc.reactor.agent.multiagent.DefaultAgentRegistry
import com.arc.reactor.agent.multiagent.DefaultSharedAgentContext
import com.arc.reactor.agent.multiagent.DefaultSupervisorAgent
import com.arc.reactor.agent.multiagent.InMemoryAgentMessageBus
import com.arc.reactor.agent.multiagent.SharedAgentContext
import com.arc.reactor.agent.multiagent.SupervisorAgent
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 멀티 에이전트(Supervisor 패턴) 자동 설정.
 *
 * `arc.reactor.multi-agent.enabled=true`일 때
 * [AgentRegistry], [SupervisorAgent], [AgentMessageBus],
 * [SharedAgentContext]를 등록한다.
 * 사용자는 각 빈을 직접 정의하여 기본 구현을 재정의할 수 있다.
 *
 * @see com.arc.reactor.agent.multiagent.AgentRegistry 에이전트 레지스트리
 * @see com.arc.reactor.agent.multiagent.SupervisorAgent Supervisor 에이전트
 * @see com.arc.reactor.agent.multiagent.AgentMessageBus 에이전트 간 메시지 버스
 * @see com.arc.reactor.agent.multiagent.SharedAgentContext 공유 컨텍스트
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
    fun agentMessageBus(): AgentMessageBus = InMemoryAgentMessageBus()

    @Bean
    @ConditionalOnMissingBean
    fun sharedAgentContext(): SharedAgentContext =
        DefaultSharedAgentContext()

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(AgentExecutor::class)
    fun supervisorAgent(
        agentExecutor: AgentExecutor,
        agentRegistry: AgentRegistry,
        properties: AgentProperties,
        messageBusProvider: ObjectProvider<AgentMessageBus>
    ): SupervisorAgent = DefaultSupervisorAgent(
        agentExecutor = agentExecutor,
        agentRegistry = agentRegistry,
        maxDelegations = properties.multiAgent.maxDelegations,
        messageBus = messageBusProvider.ifAvailable
    )
}
