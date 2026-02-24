package com.arc.reactor.autoconfigure

import com.arc.reactor.auth.AdminInitializer
import com.arc.reactor.auth.AuthProperties
import com.arc.reactor.auth.AuthProvider
import com.arc.reactor.auth.AuthRateLimitFilter
import com.arc.reactor.auth.DefaultAuthProvider
import com.arc.reactor.auth.JdbcUserStore
import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.JwtTokenProvider
import com.arc.reactor.auth.UserStore
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.server.WebFilter

/**
 * Auth Configuration (only when arc.reactor.auth.enabled=true)
 */
@Configuration
@ConditionalOnProperty(
    prefix = "arc.reactor.auth", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
@ConditionalOnClass(name = ["io.jsonwebtoken.Jwts"])
class AuthConfiguration {

    @Bean
    fun authProperties(environment: Environment): AuthProperties {
        val publicPathsCsv = environment.getProperty("arc.reactor.auth.public-paths")
        val defaultPublicPaths = mutableListOf(
            "/api/auth/login", "/api/auth/register",
            "/v3/api-docs", "/swagger-ui", "/webjars"
        )

        // Convenience option: make health endpoint publicly accessible without requiring users to
        // override the entire public-paths list.
        val publicActuatorHealth = environment.getProperty(
            "arc.reactor.auth.public-actuator-health", Boolean::class.java, false
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
            enabled = true,
            jwtSecret = environment.getProperty("arc.reactor.auth.jwt-secret", ""),
            jwtExpirationMs = environment.getProperty(
                "arc.reactor.auth.jwt-expiration-ms", Long::class.java, 86_400_000L
            ),
            publicPaths = publicPaths,
            loginRateLimitPerMinute = environment.getProperty(
                "arc.reactor.auth.login-rate-limit-per-minute", Int::class.java, 5
            )
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun authProvider(userStore: UserStore): AuthProvider = DefaultAuthProvider(userStore)

    @Bean
    @ConditionalOnMissingBean
    fun jwtTokenProvider(authProperties: AuthProperties): JwtTokenProvider =
        JwtTokenProvider(authProperties)

    @Bean
    @ConditionalOnMissingBean(name = ["jwtAuthWebFilter"])
    fun jwtAuthWebFilter(
        jwtTokenProvider: JwtTokenProvider,
        authProperties: AuthProperties
    ): WebFilter = JwtAuthWebFilter(jwtTokenProvider, authProperties)

    @Bean
    @ConditionalOnMissingBean
    fun adminInitializer(
        userStore: UserStore,
        authProvider: AuthProvider
    ): AdminInitializer = AdminInitializer(userStore, authProvider)

    @Bean
    @ConditionalOnMissingBean(name = ["authRateLimitFilter"])
    fun authRateLimitFilter(authProperties: AuthProperties): WebFilter =
        AuthRateLimitFilter(maxAttemptsPerMinute = authProperties.loginRateLimitPerMinute)
}

/**
 * JDBC Auth Configuration (when JDBC is available and auth is enabled)
 */
@Configuration
@ConditionalOnClass(name = ["org.springframework.jdbc.core.JdbcTemplate", "io.jsonwebtoken.Jwts"])
@ConditionalOnProperty(prefix = "spring.datasource", name = ["url"])
class JdbcAuthConfiguration {

    @Bean
    @Primary
    @ConditionalOnProperty(
        prefix = "arc.reactor.auth", name = ["enabled"],
        havingValue = "true", matchIfMissing = false
    )
    fun jdbcUserStore(
        jdbcTemplate: JdbcTemplate
    ): UserStore = JdbcUserStore(jdbcTemplate)
}
