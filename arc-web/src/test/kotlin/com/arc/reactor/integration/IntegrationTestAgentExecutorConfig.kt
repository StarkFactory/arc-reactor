package com.arc.reactor.integration

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/**
 * Minimal AgentExecutor stub for web integration tests that do not verify chat execution.
 */
@TestConfiguration
class IntegrationTestAgentExecutorConfig {

    @Bean
    fun agentExecutor(): AgentExecutor = object : AgentExecutor {
        override suspend fun execute(command: AgentCommand): AgentResult =
            AgentResult.success(content = "integration-test-stub")
    }
}
