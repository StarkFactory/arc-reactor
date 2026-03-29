package com.arc.reactor.autoconfigure

import com.arc.reactor.auth.AdminInitializer
import com.arc.reactor.auth.AuthProperties
import com.arc.reactor.auth.AuthProvider
import com.arc.reactor.auth.AuthRateLimitFilter
import com.arc.reactor.auth.DefaultAuthProvider
import com.arc.reactor.auth.InMemoryUserStore
import com.arc.reactor.auth.JdbcUserStore
import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.JwtTokenProvider
import com.arc.reactor.auth.TokenRevocationStoreType
import com.arc.reactor.auth.TokenRevocationStore
import com.arc.reactor.auth.UserStore
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import javax.sql.DataSource
import org.springframework.web.server.WebFilter

private const val JWT_SECRET_MIN_BYTES = 32

/**
 * 애플리케이션 시작 시 JWT 시크릿을 검증한다.
 *
 * 명확하고 조치 가능한 에러 메시지와 함께 빠르게 실패하여,
 * 첫 번째 인증 요청이 아닌 컨텍스트 초기화 전에
 * 설정 오류를 감지한다.
 */
class JwtSecretValidator(secret: String) {
    init {
        check(secret.toByteArray().size >= JWT_SECRET_MIN_BYTES) {
            "arc.reactor.auth.jwt-secret must be set to at least $JWT_SECRET_MIN_BYTES characters " +
                "Current length: ${secret.toByteArray().size} bytes. " +
                "Generate with: openssl rand -base64 32"
        }
    }
}

/**
 * 인증 자동 설정
 */
@Configuration
@ConditionalOnClass(name = ["io.jsonwebtoken.Jwts"])
class AuthConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun authProperties(environment: Environment): AuthProperties {
        val publicPathsCsv = environment.getProperty("arc.reactor.auth.public-paths")
        val selfRegistrationEnabled = environment.getProperty(
            "arc.reactor.auth.self-registration-enabled",
            Boolean::class.java,
            false
        )
        val defaultPublicPaths = mutableListOf(
            "/api/auth/login",
            "/v3/api-docs", "/swagger-ui", "/webjars"
        )
        if (selfRegistrationEnabled) {
            defaultPublicPaths.add("/api/auth/register")
        }

        // Convenience option: make health endpoint publicly accessible without requiring users to
        // override the entire public-paths list.
        val publicActuatorHealth = environment.getProperty(
            "arc.reactor.auth.public-actuator-health", Boolean::class.java, true
        )
        if (publicActuatorHealth) {
            defaultPublicPaths.add("/actuator/health")
        }

        val publicPaths = publicPathsCsv
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: defaultPublicPaths

        return AuthProperties(
            jwtSecret = environment.getProperty("arc.reactor.auth.jwt-secret", ""),
            jwtExpirationMs = environment.getProperty(
                "arc.reactor.auth.jwt-expiration-ms", Long::class.java, 86_400_000L
            ),
            defaultTenantId = environment.getProperty(
                "arc.reactor.auth.default-tenant-id", "default"
            ),
            selfRegistrationEnabled = selfRegistrationEnabled,
            publicPaths = publicPaths,
            loginRateLimitPerMinute = environment.getProperty(
                "arc.reactor.auth.login-rate-limit-per-minute", Int::class.java, 10
            ),
            tokenRevocationStore = parseTokenRevocationStore(environment)
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun jwtSecretValidator(authProperties: AuthProperties): JwtSecretValidator =
        JwtSecretValidator(authProperties.jwtSecret)

    @Bean
    @ConditionalOnMissingBean
    fun userStore(): UserStore = InMemoryUserStore()

    @Bean
    @ConditionalOnMissingBean
    fun authProvider(userStore: UserStore): AuthProvider = DefaultAuthProvider(userStore)

    @Bean
    @ConditionalOnMissingBean
    fun jwtTokenProvider(authProperties: AuthProperties, jwtSecretValidator: JwtSecretValidator): JwtTokenProvider =
        JwtTokenProvider(authProperties)

    @Bean
    @ConditionalOnMissingBean(name = ["jwtAuthWebFilter"])
    fun jwtAuthWebFilter(
        jwtTokenProvider: JwtTokenProvider,
        authProperties: AuthProperties,
        authProvider: AuthProvider,
        tokenRevocationStore: TokenRevocationStore
    ): WebFilter = JwtAuthWebFilter(
        jwtTokenProvider = jwtTokenProvider,
        authProperties = authProperties,
        authProvider = authProvider,
        tokenRevocationStore = tokenRevocationStore
    )

    @Bean
    @ConditionalOnMissingBean
    fun adminInitializer(
        userStore: UserStore,
        authProvider: AuthProvider
    ): AdminInitializer = AdminInitializer(userStore, authProvider)

    @Bean
    @ConditionalOnMissingBean(name = ["authRateLimitFilter"])
    fun authRateLimitFilter(authProperties: AuthProperties): WebFilter =
        AuthRateLimitFilter(
            maxAttemptsPerMinute = authProperties.loginRateLimitPerMinute,
            trustForwardedHeaders = authProperties.trustForwardedHeaders
        )
}

private fun parseTokenRevocationStore(environment: Environment): TokenRevocationStoreType {
    val raw = environment.getProperty("arc.reactor.auth.token-revocation-store", "memory")
    return try {
        TokenRevocationStoreType.valueOf(raw.trim().uppercase())
    } catch (_: IllegalArgumentException) {
        throw IllegalStateException(
            "Invalid arc.reactor.auth.token-revocation-store='$raw'. Use one of: memory, jdbc, redis"
        )
    }
}

/**
 * JDBC 인증 설정 (JDBC 및 DataSource URL이 설정된 경우)
 */
@Configuration
@ConditionalOnClass(name = ["org.springframework.jdbc.core.JdbcTemplate", "io.jsonwebtoken.Jwts"])
@ConditionalOnExpression("'\${spring.datasource.url:}'.trim().length() > 0")
@ConditionalOnBean(DataSource::class)
class JdbcAuthConfiguration {

    @Bean
    @Primary
    @ConditionalOnMissingBean(name = ["jdbcUserStore"])
    fun jdbcUserStore(
        jdbcTemplate: JdbcTemplate
    ): UserStore = JdbcUserStore(jdbcTemplate)
}
