package com.arc.reactor.health

import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.boot.actuate.health.Status
import org.springframework.jdbc.core.JdbcTemplate

class DatabaseHealthIndicatorTest {

    private val jdbcTemplate = mockk<JdbcTemplate>()
    private val indicator = DatabaseHealthIndicator(jdbcTemplate)

    @Test
    fun `health is UP when database query succeeds`() {
        every { jdbcTemplate.queryForObject("SELECT 1", Int::class.java) } returns 1

        val health = indicator.health()

        health.status shouldBe Status.UP
        health.details["latencyMs"].shouldBeInstanceOf<Long>()
    }

    @Test
    fun `health is DOWN when database query fails`() {
        every {
            jdbcTemplate.queryForObject("SELECT 1", Int::class.java)
        } throws RuntimeException("Connection refused")

        val health = indicator.health()

        health.status shouldBe Status.DOWN
    }

    @Test
    fun `health includes non-negative latency on success`() {
        every { jdbcTemplate.queryForObject("SELECT 1", Int::class.java) } returns 1

        val health = indicator.health()
        val latency = health.details["latencyMs"] as Long

        latency shouldBeGreaterThanOrEqual 0L
    }
}
