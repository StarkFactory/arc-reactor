package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.hook.impl.UserMemoryInjectionHook
import com.arc.reactor.memory.UserMemoryManager
import com.arc.reactor.memory.UserMemoryStore
import com.arc.reactor.memory.impl.InMemoryUserMemoryStore
import com.arc.reactor.memory.impl.JdbcUserMemoryStore
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import javax.sql.DataSource

/**
 * 사용자별 장기 메모리 자동 설정.
 *
 * 기본 저장소로 [InMemoryUserMemoryStore], [UserMemoryManager],
 * 그리고 (활성화 시) [UserMemoryInjectionHook]을 등록한다.
 *
 * `arc.reactor.memory.user.enabled=false` (기본값)이면 빈이 등록되지 않는다.
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
        UserMemoryManager(
            store = store,
            maxRecentTopics = properties.memory.user.maxRecentTopics,
            maxPromptInjectionChars = properties.memory.user.maxPromptInjectionChars
        )

    @Bean
    @ConditionalOnMissingBean
    fun userMemoryInjectionHook(
        userMemoryManager: UserMemoryManager,
        properties: AgentProperties
    ): UserMemoryInjectionHook =
        UserMemoryInjectionHook(
            memoryManager = userMemoryManager,
            injectIntoPrompt = properties.memory.user.injectIntoPrompt
        )
}

/**
 * JDBC 기반 [UserMemoryStore] (DataSource가 사용 가능할 때).
 *
 * @Primary로 등록하여 [InMemoryUserMemoryStore]보다 우선한다.
 * DataSource URL과 사용자 메모리 모두 설정된 경우에만 활성화된다.
 */
@Configuration
@ConditionalOnClass(name = ["org.springframework.jdbc.core.JdbcTemplate"])
@ConditionalOnExpression("'\${spring.datasource.url:}'.trim().length() > 0")
@ConditionalOnBean(DataSource::class)
class JdbcUserMemoryStoreConfiguration {

    @Bean
    @Primary
    @ConditionalOnMissingBean(name = ["jdbcUserMemoryStore"])
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
