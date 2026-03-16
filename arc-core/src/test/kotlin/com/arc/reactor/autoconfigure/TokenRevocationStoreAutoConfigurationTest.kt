package com.arc.reactor.autoconfigure

import com.arc.reactor.auth.InMemoryTokenRevocationStore
import com.arc.reactor.auth.JdbcTokenRevocationStore
import com.arc.reactor.auth.RedisTokenRevocationStore
import com.arc.reactor.auth.TokenRevocationStore
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate

class TokenRevocationStoreAutoConfigurationTest {

    private val baseRunner = ApplicationContextRunner()
        .withPropertyValues(
            "arc.reactor.postgres.required=false",
            "arc.reactor.auth.jwt-secret=test-secret-key-for-hmac-sha256-that-is-long-enough"
        )
        .withConfiguration(AutoConfigurations.of(ArcReactorAutoConfiguration::class.java))

    @Test
    fun `use in-memory token revocation store by default해야 한다`() {
        baseRunner.run { context ->
            val store = context.getBean(TokenRevocationStore::class.java)
            assertInstanceOf(InMemoryTokenRevocationStore::class.java, store) {
                "Default token revocation store must be in-memory"
            }
        }
    }

    @Test
    fun `configured일 때 use JDBC token revocation store해야 한다`() {
        baseRunner
            .withConfiguration(
                AutoConfigurations.of(
                    DataSourceAutoConfiguration::class.java,
                    JdbcTemplateAutoConfiguration::class.java,
                    DataSourceTransactionManagerAutoConfiguration::class.java,
                    TransactionAutoConfiguration::class.java
                )
            )
            .withPropertyValues(
                "arc.reactor.auth.token-revocation-store=jdbc",
                "spring.datasource.url=jdbc:h2:mem:token-revocation-config;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver"
            )
            .run { context ->
                val store = context.getBean(TokenRevocationStore::class.java)
                assertInstanceOf(JdbcTokenRevocationStore::class.java, store) {
                    "JDBC token revocation store should be selected when configured"
                }
            }
    }

    @Test
    fun `configured일 때 use Redis token revocation store해야 한다`() {
        baseRunner
            .withPropertyValues("arc.reactor.auth.token-revocation-store=redis")
            .withUserConfiguration(AvailableRedisTokenRevocationDepsConfig::class.java)
            .run { context ->
                val store = context.getBean(TokenRevocationStore::class.java)
                assertInstanceOf(RedisTokenRevocationStore::class.java, store) {
                    "Redis token revocation store should be selected when configured"
                }
            }
    }

    @Test
    fun `redis template exists but redis is unreachable일 때 fall back to in-memory해야 한다`() {
        baseRunner
            .withPropertyValues("arc.reactor.auth.token-revocation-store=redis")
            .withUserConfiguration(UnavailableRedisTokenRevocationDepsConfig::class.java)
            .run { context ->
                val store = context.getBean(TokenRevocationStore::class.java)
                assertInstanceOf(InMemoryTokenRevocationStore::class.java, store) {
                    "Unreachable Redis must fall back to in-memory token revocation store"
                }
            }
    }

    @Test
    fun `redis store is configured without redis template일 때 fall back to in-memory해야 한다`() {
        baseRunner
            .withPropertyValues("arc.reactor.auth.token-revocation-store=redis")
            .run { context ->
                val store = context.getBean(TokenRevocationStore::class.java)
                assertInstanceOf(InMemoryTokenRevocationStore::class.java, store) {
                    "Missing Redis template must fall back to in-memory token revocation store"
                }
            }
    }

    @Test
    fun `jdbc store is configured without jdbc template일 때 fall back to in-memory해야 한다`() {
        baseRunner
            .withPropertyValues("arc.reactor.auth.token-revocation-store=jdbc")
            .run { context ->
                val store = context.getBean(TokenRevocationStore::class.java)
                assertInstanceOf(InMemoryTokenRevocationStore::class.java, store) {
                    "Missing JdbcTemplate must fall back to in-memory token revocation store"
                }
            }
    }

    @Test
    fun `invalid token revocation store value에 대해 fail fast해야 한다`() {
        baseRunner
            .withPropertyValues("arc.reactor.auth.token-revocation-store=invalid")
            .run { context ->
                assertNotNull(context.startupFailure) {
                    "Context startup should fail for invalid token-revocation-store"
                }
            }
    }
}

@Configuration
private class AvailableRedisTokenRevocationDepsConfig {

    @Bean
    fun stringRedisTemplate(): StringRedisTemplate {
        val template = mockk<StringRedisTemplate>(relaxed = true)
        every { template.hasKey(any()) } returns false
        return template
    }
}

@Configuration
private class UnavailableRedisTokenRevocationDepsConfig {

    @Bean
    fun stringRedisTemplate(): StringRedisTemplate {
        val template = mockk<StringRedisTemplate>(relaxed = true)
        every { template.hasKey(any()) } throws RuntimeException("redis down")
        return template
    }
}
