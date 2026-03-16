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
    fun `database query succeeds일 때 health은(는) UP이다`() {
        every { jdbcTemplate.queryForObject("SELECT 1", Int::class.java) } returns 1

        val health = indicator.health()

        health.status shouldBe Status.UP
        health.details["latencyMs"].shouldBeInstanceOf<Long>()
    }

    @Test
    fun `database query fails일 때 health은(는) DOWN이다`() {
        every {
            jdbcTemplate.queryForObject("SELECT 1", Int::class.java)
        } throws RuntimeException("Connection refused")

        val health = indicator.health()

        health.status shouldBe Status.DOWN
    }

    @Test
    fun `헬스 includes non-negative latency on success`() {
        every { jdbcTemplate.queryForObject("SELECT 1", Int::class.java) } returns 1

        val health = indicator.health()
        val latency = health.details["latencyMs"] as Long

        latency shouldBeGreaterThanOrEqual 0L
    }
}
