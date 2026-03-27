package com.arc.reactor.autoconfigure

import com.arc.reactor.auth.AuthProperties
import com.arc.reactor.auth.InMemoryTokenRevocationStore
import com.arc.reactor.auth.JdbcTokenRevocationStore
import com.arc.reactor.auth.RedisTokenRevocationStore
import com.arc.reactor.auth.TokenRevocationStore
import com.arc.reactor.auth.TokenRevocationStoreType
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
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
 * [ArcReactorTokenRevocationStoreConfiguration]의 조건부 빈 선택 로직을 검증한다.
 *
 * `arc.reactor.auth.token-revocation-store` 프로퍼티 값(memory/jdbc/redis)에 따라
 * 올바른 [TokenRevocationStore] 구현체가 생성되는지 확인한다.
 */
class ArcReactorTokenRevocationStoreConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(ArcReactorTokenRevocationStoreConfiguration::class.java)
        )

    // ── memory (기본값) ───────────────────────────────────────────

    @Test
    fun `프로퍼티 미지정 시 InMemoryTokenRevocationStore를 생성한다`() {
        contextRunner
            .withBean(AuthProperties::class.java, { AuthProperties() })
            .run { context ->
                context.getBean(TokenRevocationStore::class.java)
                    .shouldBeInstanceOf<InMemoryTokenRevocationStore>()
            }
    }

    @Test
    fun `memory로 명시 설정 시 InMemoryTokenRevocationStore를 생성한다`() {
        contextRunner
            .withBean(AuthProperties::class.java, {
                AuthProperties(tokenRevocationStore = TokenRevocationStoreType.MEMORY)
            })
            .run { context ->
                context.getBean(TokenRevocationStore::class.java)
                    .shouldBeInstanceOf<InMemoryTokenRevocationStore>()
            }
    }

    // ── jdbc ─────────────────────────────────────────────────────

    @Test
    fun `jdbc 설정 + JdbcTemplate 존재 시 JdbcTokenRevocationStore를 생성한다`() {
        contextRunner
            .withConfiguration(
                AutoConfigurations.of(
                    DataSourceAutoConfiguration::class.java,
                    JdbcTemplateAutoConfiguration::class.java,
                    DataSourceTransactionManagerAutoConfiguration::class.java,
                    TransactionAutoConfiguration::class.java
                )
            )
            .withBean(AuthProperties::class.java, {
                AuthProperties(tokenRevocationStore = TokenRevocationStoreType.JDBC)
            })
            .withPropertyValues(
                "spring.datasource.url=jdbc:h2:mem:revoke-cfg-test;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver"
            )
            .run { context ->
                context.getBean(TokenRevocationStore::class.java)
                    .shouldBeInstanceOf<JdbcTokenRevocationStore>()
            }
    }

    @Test
    fun `jdbc 설정 + JdbcTemplate 미존재 시 InMemory로 폴백한다`() {
        contextRunner
            .withBean(AuthProperties::class.java, {
                AuthProperties(tokenRevocationStore = TokenRevocationStoreType.JDBC)
            })
            .run { context ->
                context.getBean(TokenRevocationStore::class.java)
                    .shouldBeInstanceOf<InMemoryTokenRevocationStore>()
            }
    }

    // ── redis ────────────────────────────────────────────────────

    @Test
    fun `redis 설정 + 정상 RedisTemplate 시 RedisTokenRevocationStore를 생성한다`() {
        contextRunner
            .withBean(AuthProperties::class.java, {
                AuthProperties(tokenRevocationStore = TokenRevocationStoreType.REDIS)
            })
            .withUserConfiguration(AvailableRedisConfig::class.java)
            .run { context ->
                context.getBean(TokenRevocationStore::class.java)
                    .shouldBeInstanceOf<RedisTokenRevocationStore>()
            }
    }

    @Test
    fun `redis 설정 + RedisTemplate 미존재 시 InMemory로 폴백한다`() {
        contextRunner
            .withBean(AuthProperties::class.java, {
                AuthProperties(tokenRevocationStore = TokenRevocationStoreType.REDIS)
            })
            .run { context ->
                context.getBean(TokenRevocationStore::class.java)
                    .shouldBeInstanceOf<InMemoryTokenRevocationStore>()
            }
    }

    @Test
    fun `redis 설정 + Redis 연결 불가 시 InMemory로 폴백한다`() {
        contextRunner
            .withBean(AuthProperties::class.java, {
                AuthProperties(tokenRevocationStore = TokenRevocationStoreType.REDIS)
            })
            .withUserConfiguration(UnavailableRedisConfig::class.java)
            .run { context ->
                context.getBean(TokenRevocationStore::class.java)
                    .shouldBeInstanceOf<InMemoryTokenRevocationStore>()
            }
    }

    // ── ConditionalOnMissingBean ─────────────────────────────────

    @Test
    fun `커스텀 TokenRevocationStore 빈이 있으면 자동 설정을 건너뛴다`() {
        val custom = mockk<TokenRevocationStore>(relaxed = true)
        contextRunner
            .withBean(AuthProperties::class.java, { AuthProperties() })
            .withBean(TokenRevocationStore::class.java, { custom })
            .run { context ->
                context.getBean(TokenRevocationStore::class.java) shouldBe custom
            }
    }
}

@Configuration(proxyBeanMethods = false)
private class AvailableRedisConfig {

    @Bean
    fun stringRedisTemplate(): StringRedisTemplate {
        val template = mockk<StringRedisTemplate>(relaxed = true)
        every { template.hasKey(any()) } returns false
        return template
    }
}

@Configuration(proxyBeanMethods = false)
private class UnavailableRedisConfig {

    @Bean
    fun stringRedisTemplate(): StringRedisTemplate {
        val template = mockk<StringRedisTemplate>(relaxed = true)
        every { template.hasKey(any()) } throws RuntimeException("Redis 연결 실패")
        return template
    }
}
