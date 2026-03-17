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

/** [JdbcAlertRuleStore]의 알림 규칙 CRUD 및 알림 인스턴스 조회/저장 테스트 */
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
        fun `queries은(는) with tenant_id and enabled filter`() {
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
        fun `no rules match일 때 empty list를 반환한다`() {
            every { jdbcTemplate.query(any<String>(), any<RowMapper<*>>(), *anyVararg()) } returns emptyList<Any>()

            store.findRulesForTenant("unknown").size shouldBe 0
        }
    }

    @Nested
    inner class FindPlatformRules {

        @Test
        fun `queries은(는) with platform_only and enabled filter`() {
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
        fun `all rules ordered by created_at를 반환한다`() {
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
        fun `performs은(는) UPSERT with all fields`() {
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
        fun `rule deleted일 때 true를 반환한다`() {
            every { jdbcTemplate.update(match<String> { it.contains("DELETE") }, any<String>()) } returns 1

            store.deleteRule("rule-1") shouldBe true
        }

        @Test
        fun `rule not found일 때 false를 반환한다`() {
            every { jdbcTemplate.update(match<String> { it.contains("DELETE") }, any<String>()) } returns 0

            store.deleteRule("nonexistent") shouldBe false
        }
    }

    @Nested
    inner class FindActiveAlerts {

        @Test
        fun `by tenant when tenantId provided를 필터링한다`() {
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
        fun `tenantId is null일 때 all active alerts를 반환한다`() {
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
        fun `alert with all fields를 삽입한다`() {
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
        fun `status to RESOLVED with resolved_at를 업데이트한다`() {
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
