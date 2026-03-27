package com.arc.reactor.guard.output.policy

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType

/**
 * JdbcOutputGuardRuleStore에 대한 테스트.
 *
 * JDBC 기반 출력 Guard 규칙 저장소의 CRUD 동작을 검증합니다.
 */
class JdbcOutputGuardRuleStoreTest {

    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var store: JdbcOutputGuardRuleStore

    @BeforeEach
    fun setup() {
        val dataSource = EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .generateUniqueName(true)
            .build()

        jdbcTemplate = JdbcTemplate(dataSource)

        // V9 + V10 + V38 combined DDL
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS output_guard_rules (
                id          VARCHAR(36)     PRIMARY KEY,
                name        VARCHAR(120)    NOT NULL,
                pattern     TEXT            NOT NULL,
                action      VARCHAR(16)     NOT NULL,
                replacement VARCHAR(256)    NOT NULL DEFAULT '[REDACTED]',
                priority    INT             NOT NULL DEFAULT 100,
                enabled     BOOLEAN         NOT NULL DEFAULT TRUE,
                created_at  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """.trimIndent()
        )

        store = JdbcOutputGuardRuleStore(jdbcTemplate)
    }

    private fun createRule(
        name: String = "PII filter",
        pattern: String = "\\d{3}-\\d{2}-\\d{4}",
        action: OutputGuardRuleAction = OutputGuardRuleAction.MASK,
        replacement: String = "[SSN]",
        priority: Int = 50,
        enabled: Boolean = true
    ) = OutputGuardRule(
        name = name,
        pattern = pattern,
        action = action,
        replacement = replacement,
        priority = priority,
        enabled = enabled
    )

    @Nested
    inner class SaveAndFind {

        @Test
        fun `save and findById해야 한다`() {
            val rule = createRule()
            store.save(rule)

            val found = store.findById(rule.id)

            assertNotNull(found) { "Saved rule should be retrievable by ID" }
            assertEquals(rule.id, found!!.id) { "ID should match" }
            assertEquals("PII filter", found.name) { "name should match" }
            assertEquals("\\d{3}-\\d{2}-\\d{4}", found.pattern) { "pattern should match" }
            assertEquals(OutputGuardRuleAction.MASK, found.action) { "action should match" }
            assertEquals("[SSN]", found.replacement) { "replacement should match" }
            assertEquals(50, found.priority) { "priority should match" }
            assertTrue(found.enabled) { "enabled should be true" }
        }

        @Test
        fun `save multiple rules and list해야 한다`() {
            store.save(createRule(name = "low", priority = 100))
            store.save(createRule(name = "high", priority = 1))
            store.save(createRule(name = "mid", priority = 50))

            val rules = store.list()

            assertEquals(3, rules.size) { "Should have 3 rules" }
            assertEquals("high", rules[0].name) { "Highest priority (lowest value) should be first" }
            assertEquals("mid", rules[1].name) { "Mid priority should be second" }
            assertEquals("low", rules[2].name) { "Lowest priority should be last" }
        }

        @Test
        fun `rules가 없을 때 빈 리스트를 반환해야 한다`() {
            val rules = store.list()

            assertTrue(rules.isEmpty()) { "Should return empty list when no rules exist" }
        }
    }

    @Nested
    inner class Update {

        @Test
        fun `update 시 필드가 변경되어야 한다`() {
            val rule = createRule()
            store.save(rule)

            Thread.sleep(10)
            val updateData = rule.copy(
                name = "Updated filter",
                pattern = "(?i)secret",
                action = OutputGuardRuleAction.REJECT,
                replacement = "[BLOCKED]",
                priority = 10,
                enabled = false
            )
            val updated = store.update(rule.id, updateData)

            assertNotNull(updated) { "update should return the updated rule" }
            assertEquals("Updated filter", updated!!.name) { "name should be updated" }
            assertEquals("(?i)secret", updated.pattern) { "pattern should be updated" }
            assertEquals(OutputGuardRuleAction.REJECT, updated.action) { "action should be updated" }
            assertEquals("[BLOCKED]", updated.replacement) { "replacement should be updated" }
            assertEquals(10, updated.priority) { "priority should be updated" }
            assertFalse(updated.enabled) { "enabled should be updated to false" }
        }

        @Test
        fun `update 시 createdAt은 보존해야 한다`() {
            val rule = createRule()
            store.save(rule)
            val saved = store.findById(rule.id)!!

            Thread.sleep(10)
            val updated = store.update(rule.id, rule.copy(name = "Changed"))!!

            assertEquals(
                saved.createdAt.epochSecond,
                updated.createdAt.epochSecond
            ) { "createdAt should be preserved on update" }
        }

        @Test
        fun `존재하지 않는 ID로 update 시 null을 반환해야 한다`() {
            val result = store.update("nonexistent", createRule())

            assertNull(result, "Updating nonexistent rule should return null")
        }
    }

    @Nested
    inner class Delete {

        @Test
        fun `delete 시 규칙이 제거되어야 한다`() {
            val rule = createRule()
            store.save(rule)
            assertNotNull(store.findById(rule.id)) { "Rule should exist before delete" }

            store.delete(rule.id)

            assertNull(store.findById(rule.id), "Rule should be null after delete")
        }

        @Test
        fun `delete 후 list에서 제외되어야 한다`() {
            val rule1 = createRule(name = "Rule A")
            val rule2 = createRule(name = "Rule B")
            store.save(rule1)
            store.save(rule2)

            store.delete(rule1.id)
            val remaining = store.list()

            assertEquals(1, remaining.size) { "Should have 1 rule after deleting one" }
            assertEquals(rule2.id, remaining[0].id) { "Remaining rule should be rule2" }
        }

        @Test
        fun `존재하지 않는 ID delete는 예외 없이 완료해야 한다`() {
            assertDoesNotThrow { store.delete("nonexistent") }
        }
    }

    @Nested
    inner class EdgeCases {

        @Test
        fun `존재하지 않는 ID로 findById 시 null을 반환해야 한다`() {
            val found = store.findById("nonexistent")

            assertNull(found, "findById should return null for unknown ID")
        }

        @Test
        fun `REJECT action으로 roundtrip해야 한다`() {
            val rule = createRule(action = OutputGuardRuleAction.REJECT)
            store.save(rule)

            val found = store.findById(rule.id)

            assertEquals(OutputGuardRuleAction.REJECT, found!!.action) { "REJECT action should roundtrip" }
        }

        @Test
        fun `MASK action으로 roundtrip해야 한다`() {
            val rule = createRule(action = OutputGuardRuleAction.MASK)
            store.save(rule)

            val found = store.findById(rule.id)

            assertEquals(OutputGuardRuleAction.MASK, found!!.action) { "MASK action should roundtrip" }
        }

        @Test
        fun `비활성 규칙도 list에 포함해야 한다`() {
            store.save(createRule(name = "active", enabled = true))
            store.save(createRule(name = "inactive", enabled = false))

            val rules = store.list()

            assertEquals(2, rules.size) { "Both active and inactive rules should be listed" }
        }

        @Test
        fun `동일 우선순위일 때 createdAt 순으로 정렬해야 한다`() {
            val rule1 = createRule(name = "first", priority = 50)
            store.save(rule1)
            Thread.sleep(10)
            val rule2 = createRule(name = "second", priority = 50)
            store.save(rule2)

            val rules = store.list()

            assertEquals("first", rules[0].name) { "Earlier created rule should be first at same priority" }
            assertEquals("second", rules[1].name) { "Later created rule should be second at same priority" }
        }

        @Test
        fun `기본 replacement 값으로 save해야 한다`() {
            val rule = OutputGuardRule(
                name = "default replacement",
                pattern = "test",
                action = OutputGuardRuleAction.MASK
            )
            store.save(rule)

            val found = store.findById(rule.id)

            assertEquals(OutputGuardRule.DEFAULT_REPLACEMENT, found!!.replacement) {
                "Default replacement should be '[REDACTED]'"
            }
        }
    }
}
