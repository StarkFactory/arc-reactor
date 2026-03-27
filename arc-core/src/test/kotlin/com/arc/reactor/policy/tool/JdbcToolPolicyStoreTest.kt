package com.arc.reactor.policy.tool

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
 * JdbcToolPolicyStore에 대한 테스트.
 *
 * JDBC 기반 도구 정책 저장소의 동작을 검증합니다.
 */
class JdbcToolPolicyStoreTest {

    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var transactionTemplate: TransactionTemplate
    private lateinit var store: JdbcToolPolicyStore

    @BeforeEach
    fun setup() {
        val dataSource = EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .generateUniqueName(true)
            .build()

        jdbcTemplate = JdbcTemplate(dataSource)
        transactionTemplate = TransactionTemplate(DataSourceTransactionManager(dataSource))

        // V12 + V14 + V15 combined DDL
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS tool_policy (
                id                                       VARCHAR(64) PRIMARY KEY,
                enabled                                  BOOLEAN     NOT NULL DEFAULT FALSE,
                write_tool_names                         TEXT        NOT NULL DEFAULT '[]',
                deny_write_channels                      TEXT        NOT NULL DEFAULT '[]',
                allow_write_tool_names_in_deny_channels  TEXT        NOT NULL DEFAULT '[]',
                allow_write_tool_names_by_channel        TEXT        NOT NULL DEFAULT '{}',
                deny_write_message                       TEXT        NOT NULL DEFAULT 'Error: This tool is not allowed in this channel',
                created_at                               TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at                               TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """.trimIndent()
        )

        store = JdbcToolPolicyStore(jdbcTemplate, transactionTemplate)
    }

    private fun createPolicy(
        enabled: Boolean = true,
        writeToolNames: Set<String> = setOf("file_write", "db_execute"),
        denyWriteChannels: Set<String> = setOf("general", "random"),
        allowWriteToolNamesInDenyChannels: Set<String> = setOf("safe_write"),
        allowWriteToolNamesByChannel: Map<String, Set<String>> = mapOf(
            "ops" to setOf("db_execute")
        ),
        denyWriteMessage: String = "This tool is blocked"
    ) = ToolPolicy(
        enabled = enabled,
        writeToolNames = writeToolNames,
        denyWriteChannels = denyWriteChannels,
        allowWriteToolNamesInDenyChannels = allowWriteToolNamesInDenyChannels,
        allowWriteToolNamesByChannel = allowWriteToolNamesByChannel,
        denyWriteMessage = denyWriteMessage
    )

    @Nested
    inner class SaveAndGet {

        @Test
        fun `save and getOrNull해야 한다`() {
            val policy = createPolicy()
            store.save(policy)

            val found = store.getOrNull()

            assertNotNull(found) { "Saved policy should be retrievable" }
            assertTrue(found!!.enabled) { "enabled should be true" }
            assertEquals(setOf("file_write", "db_execute"), found.writeToolNames) { "writeToolNames should match" }
            assertEquals(setOf("general", "random"), found.denyWriteChannels) { "denyWriteChannels should match" }
            assertEquals(setOf("safe_write"), found.allowWriteToolNamesInDenyChannels) {
                "allowWriteToolNamesInDenyChannels should match"
            }
            assertEquals(mapOf("ops" to setOf("db_execute")), found.allowWriteToolNamesByChannel) {
                "allowWriteToolNamesByChannel should match"
            }
            assertEquals("This tool is blocked", found.denyWriteMessage) { "denyWriteMessage should match" }
        }

        @Test
        fun `save로 기존 정책을 update해야 한다`() {
            store.save(createPolicy(enabled = true))
            val first = store.getOrNull()

            Thread.sleep(10)
            store.save(createPolicy(enabled = false, denyWriteMessage = "Updated"))
            val updated = store.getOrNull()

            assertNotNull(updated) { "Updated policy should be retrievable" }
            assertFalse(updated!!.enabled) { "enabled should be updated to false" }
            assertEquals("Updated", updated.denyWriteMessage) { "denyWriteMessage should be updated" }
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
            val updated = store.save(createPolicy(denyWriteMessage = "Changed"))

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
        fun `빈 집합으로 save해야 한다`() {
            store.save(
                createPolicy(
                    writeToolNames = emptySet(),
                    denyWriteChannels = emptySet(),
                    allowWriteToolNamesInDenyChannels = emptySet(),
                    allowWriteToolNamesByChannel = emptyMap()
                )
            )

            val found = store.getOrNull()

            assertNotNull(found) { "Policy with empty collections should be retrievable" }
            assertTrue(found!!.writeToolNames.isEmpty()) { "writeToolNames should be empty" }
            assertTrue(found.denyWriteChannels.isEmpty()) { "denyWriteChannels should be empty" }
            assertTrue(found.allowWriteToolNamesInDenyChannels.isEmpty()) {
                "allowWriteToolNamesInDenyChannels should be empty"
            }
            assertTrue(found.allowWriteToolNamesByChannel.isEmpty()) {
                "allowWriteToolNamesByChannel should be empty"
            }
        }

        @Test
        fun `여러 채널 매핑으로 roundtrip해야 한다`() {
            val channelMap = mapOf(
                "ops" to setOf("db_execute", "deploy"),
                "dev" to setOf("file_write"),
                "staging" to setOf("file_write", "db_execute", "deploy")
            )
            store.save(createPolicy(allowWriteToolNamesByChannel = channelMap))

            val found = store.getOrNull()

            assertNotNull(found) { "Policy with multi-channel map should be retrievable" }
            assertEquals(channelMap, found!!.allowWriteToolNamesByChannel) {
                "Multi-channel map should roundtrip correctly"
            }
        }

        @Test
        fun `delete 후 다시 save해야 한다`() {
            store.save(createPolicy(denyWriteMessage = "First"))
            store.delete()

            store.save(createPolicy(denyWriteMessage = "Second"))
            val found = store.getOrNull()

            assertNotNull(found) { "Policy should be re-saveable after delete" }
            assertEquals("Second", found!!.denyWriteMessage) { "Re-saved policy should have new message" }
        }
    }
}
