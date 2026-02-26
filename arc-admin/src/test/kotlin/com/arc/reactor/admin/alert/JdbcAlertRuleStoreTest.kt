package com.arc.reactor.admin.alert

import com.arc.reactor.admin.model.AlertInstance
import com.arc.reactor.admin.model.AlertRule
import com.arc.reactor.admin.model.AlertSeverity
import com.arc.reactor.admin.model.AlertStatus
import com.arc.reactor.admin.model.AlertType
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

class JdbcAlertRuleStoreTest {

    private val jdbcTemplate = mockk<JdbcTemplate>(relaxed = true)
    private val store = JdbcAlertRuleStore(jdbcTemplate)

    private val testRule = AlertRule(
        id = "rule-1",
        tenantId = "t1",
        name = "High Error Rate",
        type = AlertType.STATIC_THRESHOLD,
        severity = AlertSeverity.CRITICAL,
        metric = "error_rate",
        threshold = 0.10,
        windowMinutes = 15
    )

    private val testAlert = AlertInstance(
        id = "alert-1",
        ruleId = "rule-1",
        tenantId = "t1",
        severity = AlertSeverity.CRITICAL,
        status = AlertStatus.ACTIVE,
        message = "High error rate detected",
        metricValue = 0.15,
        threshold = 0.10
    )

    @Nested
    inner class FindRulesForTenant {

        @Test
        fun `queries with tenant_id and enabled filter`() {
            every { jdbcTemplate.query(any<String>(), any<RowMapper<*>>(), *anyVararg()) } returns listOf(testRule)

            val result = store.findRulesForTenant("t1")

            result.size shouldBe 1
            result[0].tenantId shouldBe "t1"
            verify {
                jdbcTemplate.query(
                    match<String> { it.contains("tenant_id = ?") && it.contains("enabled = TRUE") },
                    any<RowMapper<*>>(),
                    eq("t1")
                )
            }
        }

        @Test
        fun `returns empty list when no rules match`() {
            every { jdbcTemplate.query(any<String>(), any<RowMapper<*>>(), *anyVararg()) } returns emptyList<Any>()

            store.findRulesForTenant("unknown").size shouldBe 0
        }
    }

    @Nested
    inner class FindPlatformRules {

        @Test
        fun `queries with platform_only and enabled filter`() {
            val platformRule = testRule.copy(id = "pr-1", platformOnly = true, tenantId = null)
            every { jdbcTemplate.query(any<String>(), any<RowMapper<*>>()) } returns listOf(platformRule)

            val result = store.findPlatformRules()

            result.size shouldBe 1
            verify {
                jdbcTemplate.query(
                    match<String> { it.contains("platform_only = TRUE") && it.contains("enabled = TRUE") },
                    any<RowMapper<*>>()
                )
            }
        }
    }

    @Nested
    inner class FindAllRules {

        @Test
        fun `returns all rules ordered by created_at`() {
            every { jdbcTemplate.query(any<String>(), any<RowMapper<*>>()) } returns listOf(testRule)

            val result = store.findAllRules()

            result.size shouldBe 1
            verify {
                jdbcTemplate.query(
                    match<String> { it.contains("ORDER BY created_at DESC") },
                    any<RowMapper<*>>()
                )
            }
        }
    }

    @Nested
    inner class SaveRule {

        @Test
        fun `performs UPSERT with all fields`() {
            every { jdbcTemplate.update(any<String>(), *anyVararg()) } returns 1

            val result = store.saveRule(testRule)

            result shouldBe testRule
            verify {
                jdbcTemplate.update(
                    match<String> { it.contains("INSERT INTO alert_rules") && it.contains("ON CONFLICT") },
                    *anyVararg()
                )
            }
        }
    }

    @Nested
    inner class DeleteRule {

        @Test
        fun `returns true when rule deleted`() {
            every { jdbcTemplate.update(match<String> { it.contains("DELETE") }, any<String>()) } returns 1

            store.deleteRule("rule-1") shouldBe true
        }

        @Test
        fun `returns false when rule not found`() {
            every { jdbcTemplate.update(match<String> { it.contains("DELETE") }, any<String>()) } returns 0

            store.deleteRule("nonexistent") shouldBe false
        }
    }

    @Nested
    inner class FindActiveAlerts {

        @Test
        fun `filters by tenant when tenantId provided`() {
            every { jdbcTemplate.query(any<String>(), any<RowMapper<*>>(), *anyVararg()) } returns listOf(testAlert)

            val result = store.findActiveAlerts("t1")

            result.size shouldBe 1
            verify {
                jdbcTemplate.query(
                    match<String> { it.contains("status = 'ACTIVE'") && it.contains("tenant_id = ?") },
                    any<RowMapper<*>>(),
                    eq("t1")
                )
            }
        }

        @Test
        fun `returns all active alerts when tenantId is null`() {
            every { jdbcTemplate.query(any<String>(), any<RowMapper<*>>()) } returns listOf(testAlert)

            val result = store.findActiveAlerts(null)

            result.size shouldBe 1
            verify {
                jdbcTemplate.query(
                    match<String> { it.contains("status = 'ACTIVE'") && !it.contains("tenant_id = ?") },
                    any<RowMapper<*>>()
                )
            }
        }
    }

    @Nested
    inner class SaveAlert {

        @Test
        fun `inserts alert with all fields`() {
            every { jdbcTemplate.update(any<String>(), *anyVararg()) } returns 1

            val result = store.saveAlert(testAlert)

            result shouldBe testAlert
            verify {
                jdbcTemplate.update(
                    match<String> { it.contains("INSERT INTO alert_instances") },
                    *anyVararg()
                )
            }
        }
    }

    @Nested
    inner class ResolveAlert {

        @Test
        fun `updates status to RESOLVED with resolved_at`() {
            every { jdbcTemplate.update(any<String>(), any<String>()) } returns 1

            store.resolveAlert("alert-1")

            verify {
                jdbcTemplate.update(
                    match<String> { it.contains("status = 'RESOLVED'") && it.contains("resolved_at = NOW()") },
                    eq("alert-1")
                )
            }
        }
    }
}
