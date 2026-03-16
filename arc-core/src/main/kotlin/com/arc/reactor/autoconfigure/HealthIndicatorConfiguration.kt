package com.arc.reactor.autoconfigure

import com.arc.reactor.health.DatabaseHealthIndicator
import com.arc.reactor.health.LlmProviderHealthIndicator
import com.arc.reactor.health.McpServerHealthIndicator
import com.arc.reactor.mcp.McpManager
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Arc Reactor 헬스 인디케이터 자동 설정.
 */
@ConditionalOnClass(name = ["org.springframework.boot.actuate.health.HealthIndicator"])
class HealthIndicatorConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun llmProviderHealthIndicator(environment: Environment): LlmProviderHealthIndicator =
        LlmProviderHealthIndicator(environment)

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(JdbcTemplate::class)
    fun databaseHealthIndicator(jdbcTemplate: JdbcTemplate): DatabaseHealthIndicator =
        DatabaseHealthIndicator(jdbcTemplate)

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(McpManager::class)
    fun mcpServerHealthIndicator(mcpManager: McpManager): McpServerHealthIndicator =
        McpServerHealthIndicator(mcpManager)
}
