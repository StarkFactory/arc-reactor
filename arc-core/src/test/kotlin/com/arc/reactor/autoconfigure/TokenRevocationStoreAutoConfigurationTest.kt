package com.arc.reactor.autoconfigure

import com.arc.reactor.auth.InMemoryTokenRevocationStore
import com.arc.reactor.auth.JdbcTokenRevocationStore
import com.arc.reactor.auth.RedisTokenRevocationStore
import com.arc.reactor.auth.TokenRevocationStore
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
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

/**
 * 토큰 폐기 저장소 자동 설정에 대한 테스트.
 *
 * 토큰 폐기 저장소의 조건부 빈 등록을 검증합니다.
 */
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

    @Test
    fun `R288 strict 모드에서 Redis 미가용 시 fail fast 해야 한다`() {
        // R288 fix 검증: tokenRevocationStoreStrict=true이면 Redis 미가용 시 silent
        // in-memory fallback 대신 BeanCreationException으로 startup 실패. 보안 회귀
        // (revoked tokens가 restart 후 모두 revalidate) 방지.
        baseRunner
            .withPropertyValues(
                "arc.reactor.auth.token-revocation-store=redis",
                "arc.reactor.auth.token-revocation-store-strict=true"
            )
            .withUserConfiguration(UnavailableRedisTokenRevocationDepsConfig::class.java)
            .run { context ->
                assertNotNull(context.startupFailure) {
                    "R288 fix: strict 모드에서 Redis 미가용 시 startup이 실패해야 한다 " +
                        "(silent fallback 차단)"
                }
                val rootCause = generateSequence(context.startupFailure) { it.cause }
                    .lastOrNull()?.message.orEmpty()
                assertTrue(rootCause.contains("token-revocation-store=redis")) {
                    "R288 fix: 실패 메시지에 backend 종류 명시 필요. 실제: $rootCause"
                }
                assertTrue(rootCause.contains("strict")) {
                    "R288 fix: 실패 메시지에 strict 모드 안내 필요. 실제: $rootCause"
                }
            }
    }

    @Test
    fun `R288 strict 모드에서 Redis 정상이면 정상 등록`() {
        // strict 모드라도 backend가 정상이면 정상 작동해야 한다 (false alarm 없음).
        baseRunner
            .withPropertyValues(
                "arc.reactor.auth.token-revocation-store=redis",
                "arc.reactor.auth.token-revocation-store-strict=true"
            )
            .withUserConfiguration(AvailableRedisTokenRevocationDepsConfig::class.java)
            .run { context ->
                val store = context.getBean(TokenRevocationStore::class.java)
                assertInstanceOf(RedisTokenRevocationStore::class.java, store) {
                    "R288 fix: strict 모드라도 Redis 정상이면 RedisTokenRevocationStore 등록"
                }
            }
    }

    @Test
    fun `R288 strict 모드에서 JDBC 미가용 시 fail fast 해야 한다`() {
        baseRunner
            .withPropertyValues(
                "arc.reactor.auth.token-revocation-store=jdbc",
                "arc.reactor.auth.token-revocation-store-strict=true"
            )
            .run { context ->
                // JdbcTemplate auto-config 없이 jdbc 요청 → 미가용
                assertNotNull(context.startupFailure) {
                    "R288 fix: strict 모드에서 JdbcTemplate 미가용 시 startup이 실패해야 한다"
                }
                val rootCause = generateSequence(context.startupFailure) { it.cause }
                    .lastOrNull()?.message.orEmpty()
                assertTrue(rootCause.contains("token-revocation-store=jdbc")) {
                    "R288 fix: 실패 메시지에 jdbc backend 종류 명시 필요. 실제: $rootCause"
                }
            }
    }

    @Test
    fun `R288 strict false default는 silent fallback 보존 (backward compat)`() {
        // strict 기본값 false → 기존 동작(silent fallback) 유지
        baseRunner
            .withPropertyValues("arc.reactor.auth.token-revocation-store=redis")
            .withUserConfiguration(UnavailableRedisTokenRevocationDepsConfig::class.java)
            .run { context ->
                val store = context.getBean(TokenRevocationStore::class.java)
                assertInstanceOf(InMemoryTokenRevocationStore::class.java, store) {
                    "R288: strict 기본값 false에서는 backward compat (silent fallback) 유지"
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
