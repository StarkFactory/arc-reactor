package com.arc.reactor.mcp

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType
import org.springframework.transaction.support.TransactionTemplate

/**
 * JdbcMcpSecurityPolicyStore에 대한 테스트.
 *
 * JDBC 기반 MCP 보안 정책 저장소의 동작을 검증합니다.
 */
class JdbcMcpSecurityPolicyStoreTest {

    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var transactionTemplate: TransactionTemplate
    private lateinit var store: JdbcMcpSecurityPolicyStore

    @BeforeEach
    fun setup() {
        val dataSource = EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .generateUniqueName(true)
            .build()

        jdbcTemplate = JdbcTemplate(dataSource)
        transactionTemplate = TransactionTemplate(DataSourceTransactionManager(dataSource))

        // V32 DDL
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS mcp_security_policy (
                id                     VARCHAR(64) PRIMARY KEY,
                allowed_server_names   TEXT        NOT NULL DEFAULT '[]',
                max_tool_output_length INTEGER     NOT NULL DEFAULT 50000,
                created_at             TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at             TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """.trimIndent()
        )

        store = JdbcMcpSecurityPolicyStore(jdbcTemplate, transactionTemplate)
    }

    private fun createPolicy(
        allowedServerNames: Set<String> = setOf("my-mcp-server", "trusted-server"),
        maxToolOutputLength: Int = 30_000
    ) = McpSecurityPolicy(
        allowedServerNames = allowedServerNames,
        maxToolOutputLength = maxToolOutputLength
    )

    @Nested
    inner class SaveAndGet {

        @Test
        fun `save and getOrNull해야 한다`() {
            val policy = createPolicy()
            store.save(policy)

            val found = store.getOrNull()

            assertNotNull(found) { "Saved policy should be retrievable" }
            assertEquals(
                setOf("my-mcp-server", "trusted-server"),
                found!!.allowedServerNames
            ) { "allowedServerNames should match" }
            assertEquals(30_000, found.maxToolOutputLength) { "maxToolOutputLength should match" }
        }

        @Test
        fun `save로 기존 정책을 update해야 한다`() {
            store.save(createPolicy(maxToolOutputLength = 10_000))
            val first = store.getOrNull()

            Thread.sleep(10)
            store.save(createPolicy(maxToolOutputLength = 50_000))
            val updated = store.getOrNull()

            assertNotNull(updated) { "Updated policy should be retrievable" }
            assertEquals(50_000, updated!!.maxToolOutputLength) { "maxToolOutputLength should be updated" }
            assertEquals(
                first!!.createdAt.epochSecond,
                updated.createdAt.epochSecond
            ) { "createdAt should be preserved on update" }
        }

        @Test
        fun `save해도 createdAt은 보존해야 한다`() {
            val saved = store.save(createPolicy())
            val createdAt = saved.createdAt

            Thread.sleep(10)
            val updated = store.save(createPolicy(maxToolOutputLength = 99_999))

            assertEquals(
                createdAt.epochSecond,
                updated.createdAt.epochSecond
            ) { "createdAt should not change on subsequent saves" }
            assertTrue(updated.updatedAt.isAfter(createdAt)) { "updatedAt should advance" }
        }
    }

    @Nested
    inner class Delete {

        @Test
        fun `delete 시 정책이 제거되어야 한다`() {
            store.save(createPolicy())
            assertNotNull(store.getOrNull()) { "Policy should exist before delete" }

            val deleted = store.delete()

            assertTrue(deleted) { "delete should return true when policy existed" }
            assertNull(store.getOrNull(), "Policy should be null after delete")
        }

        @Test
        fun `정책이 없을 때 delete는 false를 반환해야 한다`() {
            val deleted = store.delete()

            assertFalse(deleted) { "delete should return false when no policy exists" }
        }
    }

    @Nested
    inner class EdgeCases {

        @Test
        fun `정책이 없을 때 getOrNull은 null을 반환해야 한다`() {
            val found = store.getOrNull()

            assertNull(found, "getOrNull should return null when no policy exists")
        }

        @Test
        fun `빈 allowedServerNames로 save해야 한다`() {
            store.save(createPolicy(allowedServerNames = emptySet()))

            val found = store.getOrNull()

            assertNotNull(found) { "Policy with empty allowedServerNames should be retrievable" }
            assertTrue(found!!.allowedServerNames.isEmpty()) { "allowedServerNames should be empty" }
        }

        @Test
        fun `여러 서버 이름으로 roundtrip해야 한다`() {
            val names = setOf("server-a", "server-b", "server-c", "server-d")
            store.save(createPolicy(allowedServerNames = names))

            val found = store.getOrNull()

            assertEquals(names, found!!.allowedServerNames) { "Multiple server names should roundtrip" }
        }

        @Test
        fun `delete 후 다시 save해야 한다`() {
            store.save(createPolicy(maxToolOutputLength = 10_000))
            store.delete()

            store.save(createPolicy(maxToolOutputLength = 20_000))
            val found = store.getOrNull()

            assertNotNull(found) { "Policy should be re-saveable after delete" }
            assertEquals(20_000, found!!.maxToolOutputLength) { "Re-saved policy should have new value" }
        }

        @Test
        fun `maxToolOutputLength 경계값으로 roundtrip해야 한다`() {
            store.save(createPolicy(maxToolOutputLength = 0))
            assertEquals(0, store.getOrNull()!!.maxToolOutputLength) {
                "Zero maxToolOutputLength should roundtrip"
            }

            store.delete()
            store.save(createPolicy(maxToolOutputLength = Int.MAX_VALUE))
            assertEquals(Int.MAX_VALUE, store.getOrNull()!!.maxToolOutputLength) {
                "Max int maxToolOutputLength should roundtrip"
            }
        }
    }
}
