package com.arc.reactor.health

import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Health indicator that verifies database connectivity via a simple query.
 */
class DatabaseHealthIndicator(
    private val jdbcTemplate: JdbcTemplate
) : HealthIndicator {

    override fun health(): Health {
        return try {
            val start = System.nanoTime()
            jdbcTemplate.queryForObject("SELECT 1", Int::class.java)
            val latencyMs = (System.nanoTime() - start) / 1_000_000
            Health.up()
                .withDetail("latencyMs", latencyMs)
                .build()
        } catch (e: Exception) {
            Health.down(e).build()
        }
    }
}
