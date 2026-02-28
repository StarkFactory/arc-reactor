package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.hook.impl.UserMemoryInjectionHook
import com.arc.reactor.memory.UserMemoryManager
import com.arc.reactor.memory.UserMemoryStore
import com.arc.reactor.memory.impl.InMemoryUserMemoryStore
import com.arc.reactor.memory.impl.JdbcUserMemoryStore
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Auto-configuration for per-user long-term memory.
 *
 * Registers [InMemoryUserMemoryStore] as the default store, [UserMemoryManager],
 * and (when enabled) [UserMemoryInjectionHook].
 *
 * When `arc.reactor.memory.user.enabled=false` (the default), no beans are registered.
 */
@Configuration
@ConditionalOnProperty(
    prefix = "arc.reactor.memory.user", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
class UserMemoryConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun inMemoryUserMemoryStore(): UserMemoryStore = InMemoryUserMemoryStore()

    @Bean
    @ConditionalOnMissingBean
    fun userMemoryManager(store: UserMemoryStore, properties: AgentProperties): UserMemoryManager =
        UserMemoryManager(store = store, maxRecentTopics = properties.memory.user.maxRecentTopics)

    @Bean
    @ConditionalOnMissingBean
    fun userMemoryInjectionHook(userMemoryManager: UserMemoryManager): UserMemoryInjectionHook =
        UserMemoryInjectionHook(memoryManager = userMemoryManager)
}

/**
 * JDBC-backed [UserMemoryStore] (when DataSource is available).
 *
 * Registers with @Primary so it takes precedence over [InMemoryUserMemoryStore].
 * Only active when both a DataSource URL and user memory are configured.
 */
@Configuration
@ConditionalOnClass(name = ["org.springframework.jdbc.core.JdbcTemplate"])
@ConditionalOnProperty(prefix = "spring.datasource", name = ["url"])
class JdbcUserMemoryStoreConfiguration {

    @Bean
    @Primary
    @ConditionalOnProperty(
        prefix = "arc.reactor.memory.user", name = ["enabled"],
        havingValue = "true", matchIfMissing = false
    )
    fun jdbcUserMemoryStore(jdbcTemplate: JdbcTemplate, properties: AgentProperties): UserMemoryStore =
        JdbcUserMemoryStore(
            jdbcTemplate = jdbcTemplate,
            tableName = properties.memory.user.jdbc.tableName
        )
}
