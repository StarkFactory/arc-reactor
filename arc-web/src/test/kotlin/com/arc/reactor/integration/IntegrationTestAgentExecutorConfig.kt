package com.arc.reactor.integration

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/**
 * 채팅 실행을 검증하지 않는 웹 통합 테스트를 위한 최소한의 AgentExecutor 스텁.
 */
@TestConfiguration
class IntegrationTestAgentExecutorConfig {

    @Bean
    fun agentExecutor(): AgentExecutor = object : AgentExecutor {
        override suspend fun execute(command: AgentCommand): AgentResult =
            AgentResult.success(content = "integration-test-stub")
    }
}
