package com.arc.reactor.memory.summary

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.config.LlmProperties
import com.arc.reactor.agent.config.MemoryProperties
import com.arc.reactor.agent.config.SummaryProperties
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.Message
import com.arc.reactor.agent.model.MessageRole
import com.arc.reactor.memory.DefaultConversationManager
import com.arc.reactor.memory.InMemoryMemoryStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stress tests for the hierarchical conversation memory feature.
 *
 * Validates correctness under high-volume conversations, concurrent access,
 * and async summarization race conditions. All tests use a mocked
 * [ConversationSummaryService] to avoid real LLM calls.
 */
class ConversationMemoryStressTest {

    private val summaryProps = SummaryProperties(
        enabled = true,
        triggerMessageCount = 20,
        recentMessageCount = 10,
        maxNarrativeTokens = 500
    )

    private val properties = AgentProperties(
        llm = LlmProperties(maxConversationTurns = 10),
        memory = MemoryProperties(summary = summaryProps)
    )

    private lateinit var memoryStore: InMemoryMemoryStore
    private lateinit var summaryStore: InMemoryConversationSummaryStore
    private lateinit var summaryService: ConversationSummaryService

    @BeforeEach
    fun setup() {
        memoryStore = InMemoryMemoryStore()
        summaryStore = InMemoryConversationSummaryStore()
        summaryService = mockk()
    }

    /**
     * Populate a session with the given number of user/assistant message pairs.
     */
    private fun populateSession(sessionId: String, messageCount: Int) {
        for (i in 1..messageCount) {
            val role = if (i % 2 == 1) "user" else "assistant"
            val content = if (i % 2 == 1) "Question #${(i + 1) / 2}" else "Answer #${i / 2}"
            memoryStore.addMessage(sessionId, role, content, "test-user")
        }
    }

    private fun command(sessionId: String) = AgentCommand(
        systemPrompt = "",
        userPrompt = "next question",
        metadata = mapOf("sessionId" to sessionId)
    )

    @Nested
    inner class LongConversation {

        @Test
        fun `should produce hierarchical structure for 100-message conversation`() = runTest {
            val sessionId = "long-session"
            populateSession(sessionId, 100)

            coEvery { summaryService.summarize(any(), any()) } returns SummarizationResult(
                narrative = "Long conversation covering 90 messages of Q and A",
                facts = listOf(
                    StructuredFact(key = "total_questions", value = "50", category = FactCategory.NUMERIC),
                    StructuredFact(key = "topic", value = "stress-test", category = FactCategory.GENERAL)
                )
            )

            val manager = DefaultConversationManager(
                memoryStore, properties, summaryStore, summaryService
            )

            val history = manager.loadHistory(command(sessionId))

            // Expected: [Facts SystemMessage] + [Narrative SystemMessage] + [10 recent messages]
            val expectedSize = 2 + summaryProps.recentMessageCount
            assertEquals(expectedSize, history.size) {
                "Should have 2 summary system messages + ${summaryProps.recentMessageCount} recent messages, " +
                    "but got ${history.size}"
            }

            // First message should be Facts
            assertTrue(history[0] is SystemMessage) {
                "First message should be Facts SystemMessage, got ${history[0]::class.simpleName}"
            }
            val factsText = (history[0] as SystemMessage).text
            assertTrue(factsText.contains("total_questions")) {
                "Facts should contain 'total_questions' but was: $factsText"
            }

            // Second message should be Narrative
            assertTrue(history[1] is SystemMessage) {
                "Second message should be Narrative SystemMessage, got ${history[1]::class.simpleName}"
            }
            val narrativeText = (history[1] as SystemMessage).text
            assertTrue(narrativeText.contains("Long conversation")) {
                "Narrative should contain summary text but was: $narrativeText"
            }

            // Remaining messages should be the most recent verbatim ones (not all 100)
            val recentMessages = history.subList(2, history.size)
            assertEquals(summaryProps.recentMessageCount, recentMessages.size) {
                "Should have exactly ${summaryProps.recentMessageCount} recent messages, " +
                    "not all 100"
            }
        }

        @Test
        fun `should not include all 100 messages verbatim`() = runTest {
            val sessionId = "long-no-verbatim"
            populateSession(sessionId, 100)

            coEvery { summaryService.summarize(any(), any()) } returns SummarizationResult(
                narrative = "Summary of conversation",
                facts = listOf(StructuredFact(key = "k", value = "v"))
            )

            val manager = DefaultConversationManager(
                memoryStore, properties, summaryStore, summaryService
            )

            val history = manager.loadHistory(command(sessionId))

            assertTrue(history.size < 100) {
                "Hierarchical history should be much smaller than 100, got ${history.size}"
            }
            // At most: 2 system messages + recentMessageCount
            assertTrue(history.size <= 2 + summaryProps.recentMessageCount) {
                "History should be at most ${2 + summaryProps.recentMessageCount} but got ${history.size}"
            }
        }
    }

    @Nested
    inner class ConcurrentLoadHistory {

        @Test
        fun `should handle 10 concurrent loadHistory calls without crashes`() = runTest {
            val sessionId = "concurrent-load"
            populateSession(sessionId, 30)

            val callCount = AtomicInteger(0)
            coEvery { summaryService.summarize(any(), any()) } coAnswers {
                callCount.incrementAndGet()
                delay(50) // simulate LLM latency
                SummarizationResult(
                    narrative = "Concurrent summary",
                    facts = listOf(StructuredFact(key = "concurrent", value = "true"))
                )
            }

            val manager = DefaultConversationManager(
                memoryStore, properties, summaryStore, summaryService
            )

            val results = mutableListOf<List<org.springframework.ai.chat.messages.Message>>()
            val errors = AtomicInteger(0)

            coroutineScope {
                repeat(10) {
                    launch {
                        try {
                            val history = manager.loadHistory(command(sessionId))
                            synchronized(results) { results.add(history) }
                        } catch (e: Exception) {
                            errors.incrementAndGet()
                        }
                    }
                }
            }

            assertEquals(0, errors.get()) {
                "No concurrent loadHistory calls should throw exceptions"
            }
            assertEquals(10, results.size) {
                "All 10 concurrent loadHistory calls should return results"
            }

            // All results should have consistent structure
            for ((index, history) in results.withIndex()) {
                assertTrue(history.isNotEmpty()) {
                    "Result #$index should not be empty"
                }
                assertTrue(history.size <= 2 + summaryProps.recentMessageCount) {
                    "Result #$index should have at most ${2 + summaryProps.recentMessageCount} " +
                        "messages but got ${history.size}"
                }
            }
        }

        @Test
        fun `all concurrent loadHistory results should have same size`() = runTest {
            val sessionId = "concurrent-consistency"
            populateSession(sessionId, 40)

            // Pre-populate a summary so all calls use the cache (no LLM call variance)
            summaryStore.save(
                ConversationSummary(
                    sessionId = sessionId,
                    narrative = "Pre-cached summary for consistency",
                    facts = listOf(StructuredFact(key = "cached", value = "yes")),
                    summarizedUpToIndex = 30 // covers messages 0..29, recent = last 10
                )
            )

            val manager = DefaultConversationManager(
                memoryStore, properties, summaryStore, summaryService
            )

            val sizes = mutableListOf<Int>()

            coroutineScope {
                repeat(10) {
                    launch {
                        val history = manager.loadHistory(command(sessionId))
                        synchronized(sizes) { sizes.add(history.size) }
                    }
                }
            }

            val distinctSizes = sizes.distinct()
            assertEquals(1, distinctSizes.size) {
                "All concurrent loadHistory calls should return the same size, " +
                    "but got distinct sizes: $distinctSizes"
            }
        }
    }

    @Nested
    inner class SaveHistoryAsyncSummarization {

        @Test
        fun `loadHistory should work immediately after saveHistory`() = runTest {
            val sessionId = "async-race"
            populateSession(sessionId, 22) // above trigger (20)

            val summarizeCallCount = AtomicInteger(0)
            coEvery { summaryService.summarize(any(), any()) } coAnswers {
                summarizeCallCount.incrementAndGet()
                delay(200) // simulate slow async summarization
                SummarizationResult(
                    narrative = "Async summary",
                    facts = listOf(StructuredFact(key = "async", value = "true"))
                )
            }

            val manager = DefaultConversationManager(
                memoryStore, properties, summaryStore, summaryService
            )

            // Save triggers async summarization
            val saveCommand = AgentCommand(
                systemPrompt = "",
                userPrompt = "trigger question",
                metadata = mapOf("sessionId" to sessionId),
                userId = "test-user"
            )
            manager.saveHistory(saveCommand, AgentResult.success("trigger answer"))

            // Immediately call loadHistory (async summary may not be done yet)
            val history = manager.loadHistory(command(sessionId))

            // loadHistory should succeed regardless of async completion state
            assertTrue(history.isNotEmpty()) {
                "loadHistory should return non-empty result even before async summary completes"
            }
        }

        @Test
        fun `no exceptions when async summary and loadHistory race`() = runTest {
            val sessionId = "race-safety"
            populateSession(sessionId, 25)

            coEvery { summaryService.summarize(any(), any()) } coAnswers {
                delay(50)
                SummarizationResult(
                    narrative = "Race condition summary",
                    facts = listOf(StructuredFact(key = "race", value = "safe"))
                )
            }

            val manager = DefaultConversationManager(
                memoryStore, properties, summaryStore, summaryService
            )

            val errors = AtomicInteger(0)

            coroutineScope {
                // Interleave save and load operations
                repeat(5) { i ->
                    launch {
                        try {
                            val cmd = AgentCommand(
                                systemPrompt = "",
                                userPrompt = "save-question-$i",
                                metadata = mapOf("sessionId" to sessionId),
                                userId = "test-user"
                            )
                            manager.saveHistory(cmd, AgentResult.success("save-answer-$i"))
                        } catch (e: Exception) {
                            errors.incrementAndGet()
                        }
                    }
                    launch {
                        try {
                            delay(10) // slight offset to overlap with save
                            manager.loadHistory(command(sessionId))
                        } catch (e: Exception) {
                            errors.incrementAndGet()
                        }
                    }
                }
            }

            assertEquals(0, errors.get()) {
                "No exceptions should be thrown when save and load race concurrently"
            }
        }
    }

    @Nested
    inner class SummaryStoreConsistencyUnderConcurrentWrites {

        @Test
        fun `InMemoryConversationSummaryStore should not corrupt under concurrent saves`() = runTest {
            val store = InMemoryConversationSummaryStore()
            val errors = AtomicInteger(0)

            coroutineScope {
                repeat(20) { i ->
                    launch {
                        try {
                            store.save(
                                ConversationSummary(
                                    sessionId = "session-${i % 5}", // 5 sessions, 4 writes each
                                    narrative = "Narrative from writer $i",
                                    facts = (1..5).map { j ->
                                        StructuredFact(
                                            key = "fact-$j-by-$i",
                                            value = "value-$j",
                                            category = FactCategory.GENERAL
                                        )
                                    },
                                    summarizedUpToIndex = i * 10
                                )
                            )
                        } catch (e: Exception) {
                            errors.incrementAndGet()
                        }
                    }
                }
            }

            assertEquals(0, errors.get()) {
                "No exceptions should be thrown during concurrent saves"
            }

            // All 5 sessions should have a summary
            for (s in 0 until 5) {
                val summary = store.get("session-$s")
                assertNotNull(summary) {
                    "Session session-$s should have a summary after concurrent writes"
                }
                assertTrue(summary!!.facts.isNotEmpty()) {
                    "Session session-$s should have non-empty facts"
                }
            }
        }

        @Test
        fun `concurrent reads and writes should not corrupt InMemoryConversationSummaryStore`() = runTest {
            val store = InMemoryConversationSummaryStore()
            val readErrors = AtomicInteger(0)
            val writeErrors = AtomicInteger(0)

            // Pre-populate
            store.save(
                ConversationSummary(
                    sessionId = "shared",
                    narrative = "Initial narrative",
                    facts = listOf(StructuredFact(key = "init", value = "true")),
                    summarizedUpToIndex = 5
                )
            )

            coroutineScope {
                // Writers
                repeat(10) { i ->
                    launch {
                        try {
                            delay((i * 5).toLong())
                            store.save(
                                ConversationSummary(
                                    sessionId = "shared",
                                    narrative = "Updated by writer $i",
                                    facts = (1..3).map { j ->
                                        StructuredFact(key = "key-$j", value = "w$i-v$j")
                                    },
                                    summarizedUpToIndex = 5 + i
                                )
                            )
                        } catch (e: Exception) {
                            writeErrors.incrementAndGet()
                        }
                    }
                }
                // Readers
                repeat(10) { i ->
                    launch {
                        try {
                            delay((i * 3).toLong())
                            val result = store.get("shared")
                            // Result should never be structurally invalid
                            assertNotNull(result) {
                                "Read #$i should find 'shared' session"
                            }
                            assertNotNull(result!!.narrative) {
                                "Read #$i narrative should not be null"
                            }
                            assertNotNull(result.facts) {
                                "Read #$i facts should not be null"
                            }
                        } catch (e: Exception) {
                            readErrors.incrementAndGet()
                        }
                    }
                }
            }

            assertEquals(0, writeErrors.get()) {
                "No write errors should occur during concurrent read/write"
            }
            assertEquals(0, readErrors.get()) {
                "No read errors should occur during concurrent read/write"
            }
        }
    }

    @Nested
    inner class DeleteDuringAsyncSummarization {

        @Test
        fun `should not crash when session is deleted while async summarization is in-flight`() = runTest {
            val sessionId = "delete-race"
            populateSession(sessionId, 30) // above trigger

            val summarizeStarted = java.util.concurrent.CountDownLatch(1)
            val proceedWithSummarize = java.util.concurrent.CountDownLatch(1)

            coEvery { summaryService.summarize(any(), any()) } coAnswers {
                summarizeStarted.countDown()
                // Block until delete happens
                proceedWithSummarize.await(5, java.util.concurrent.TimeUnit.SECONDS)
                SummarizationResult(
                    narrative = "Summary after delete",
                    facts = listOf(StructuredFact(key = "orphan", value = "true"))
                )
            }

            val manager = DefaultConversationManager(
                memoryStore, properties, summaryStore, summaryService
            )

            // Step 1: Trigger async summarization via saveHistory
            val saveCommand = AgentCommand(
                systemPrompt = "",
                userPrompt = "trigger",
                metadata = mapOf("sessionId" to sessionId),
                userId = "test-user"
            )
            manager.saveHistory(saveCommand, AgentResult.success("answer"))

            // Step 2: Wait for summarization to start
            assertTrue(summarizeStarted.await(3, java.util.concurrent.TimeUnit.SECONDS)) {
                "Async summarization should have started"
            }

            // Step 3: Delete session while summarization is blocked
            memoryStore.remove(sessionId)
            summaryStore.delete(sessionId)

            // Step 4: Let summarization complete — it will try to save to a deleted session
            proceedWithSummarize.countDown()
            Thread.sleep(500) // Let async job finish

            // Step 5: Verify no crash — the orphan summary may exist, which is acceptable
            val orphanSummary = summaryStore.get(sessionId)
            // Either null (if store rejected) or present (orphan) — both are OK
            if (orphanSummary != null) {
                assertEquals("Summary after delete", orphanSummary.narrative) {
                    "Orphaned summary should have the late-arriving narrative"
                }
            }
            // Key assertion: no exception was thrown during the entire flow
            manager.destroy()
        }

        @Test
        fun `should handle rapid delete-then-load without exceptions`() = runTest {
            val sessionId = "rapid-delete-load"
            populateSession(sessionId, 30)

            coEvery { summaryService.summarize(any(), any()) } coAnswers {
                delay(100)
                SummarizationResult(
                    narrative = "Summary",
                    facts = listOf(StructuredFact(key = "k", value = "v"))
                )
            }

            val manager = DefaultConversationManager(
                memoryStore, properties, summaryStore, summaryService
            )

            val errors = java.util.concurrent.atomic.AtomicInteger(0)

            // Interleave saves, deletes, and loads across 5 different sessions
            coroutineScope {
                repeat(5) { sessionIdx ->
                    val sid = "session-$sessionIdx"
                    populateSession(sid, 25)

                    // Save (triggers async summarization)
                    launch {
                        try {
                            val cmd = AgentCommand(
                                systemPrompt = "",
                                userPrompt = "q",
                                metadata = mapOf("sessionId" to sid),
                                userId = "user"
                            )
                            manager.saveHistory(cmd, AgentResult.success("a"))
                        } catch (e: Exception) {
                            errors.incrementAndGet()
                        }
                    }
                    // Delete (while async may be running)
                    launch {
                        try {
                            delay(50)
                            memoryStore.remove(sid)
                            summaryStore.delete(sid)
                        } catch (e: Exception) {
                            errors.incrementAndGet()
                        }
                    }
                    // Load (after delete — should handle missing session gracefully)
                    launch {
                        try {
                            delay(100)
                            manager.loadHistory(command(sid))
                        } catch (e: Exception) {
                            errors.incrementAndGet()
                        }
                    }
                }
            }

            assertEquals(0, errors.get()) {
                "No exceptions should occur during interleaved save/delete/load operations"
            }
            manager.destroy()
        }

        @Test
        fun `should not accumulate orphaned summaries after repeated create-delete cycles`() = runTest {
            coEvery { summaryService.summarize(any(), any()) } coAnswers {
                SummarizationResult(
                    narrative = "Cycle summary",
                    facts = listOf(StructuredFact(key = "cycle", value = "true"))
                )
            }

            val manager = DefaultConversationManager(
                memoryStore, properties, summaryStore, summaryService
            )

            // Run 10 create-summarize-delete cycles for different sessions
            for (cycle in 1..10) {
                val sid = "cycle-$cycle"
                populateSession(sid, 25)

                // Load triggers synchronous summarization
                manager.loadHistory(command(sid))

                // Verify summary was created
                assertNotNull(summaryStore.get(sid)) {
                    "Summary for cycle $cycle should exist after loadHistory"
                }

                // Clean up both stores
                memoryStore.remove(sid)
                summaryStore.delete(sid)

                // Verify clean deletion
                assertNull(summaryStore.get(sid)) {
                    "Summary for cycle $cycle should be deleted after cleanup"
                }
            }

            manager.destroy()
        }
    }

    @Nested
    inner class JdbcFactsSerializationRoundtrip {

        private lateinit var jdbcTemplate: JdbcTemplate
        private lateinit var jdbcStore: JdbcConversationSummaryStore

        @BeforeEach
        fun setupJdbc() {
            val dataSource = EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build()
            jdbcTemplate = JdbcTemplate(dataSource)
            jdbcTemplate.execute(
                """
                CREATE TABLE IF NOT EXISTS conversation_summaries (
                    session_id       VARCHAR(255) PRIMARY KEY,
                    narrative        TEXT         NOT NULL,
                    facts_json       TEXT         NOT NULL DEFAULT '[]',
                    summarized_up_to INT          NOT NULL DEFAULT 0,
                    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """.trimIndent()
            )
            jdbcStore = JdbcConversationSummaryStore(jdbcTemplate)
        }

        @Test
        fun `should roundtrip 100 facts through JDBC serialization`() {
            val facts = (1..100).map { i ->
                StructuredFact(
                    key = "fact_$i",
                    value = "value_$i with special chars: quotes\"and'apostrophes",
                    category = FactCategory.entries[i % FactCategory.entries.size]
                )
            }

            val summary = ConversationSummary(
                sessionId = "large-facts-session",
                narrative = "A lengthy narrative covering many topics discussed over 100 turns",
                facts = facts,
                summarizedUpToIndex = 200
            )

            jdbcStore.save(summary)
            val loaded = jdbcStore.get("large-facts-session")

            assertNotNull(loaded) {
                "Saved summary with 100 facts should be retrievable"
            }
            assertEquals(100, loaded!!.facts.size) {
                "All 100 facts should survive the JDBC roundtrip, got ${loaded.facts.size}"
            }

            for (i in 1..100) {
                val fact = loaded.facts[i - 1]
                assertEquals("fact_$i", fact.key) {
                    "Fact #$i key should be 'fact_$i' but was '${fact.key}'"
                }
                assertTrue(fact.value.contains("value_$i")) {
                    "Fact #$i value should contain 'value_$i' but was '${fact.value}'"
                }
                val expectedCategory = FactCategory.entries[i % FactCategory.entries.size]
                assertEquals(expectedCategory, fact.category) {
                    "Fact #$i category should be $expectedCategory but was ${fact.category}"
                }
            }
        }

        @Test
        fun `should roundtrip facts with unicode and long values`() {
            val facts = listOf(
                StructuredFact(
                    key = "unicode_name",
                    value = "Customer name: \u6D4B\u8BD5\u7528\u6237 (test user in Chinese)",
                    category = FactCategory.ENTITY
                ),
                StructuredFact(
                    key = "emoji_note",
                    value = "Status: completed \u2705 with notes \uD83D\uDCDD",
                    category = FactCategory.STATE
                ),
                StructuredFact(
                    key = "long_value",
                    value = "A".repeat(5000),
                    category = FactCategory.GENERAL
                ),
                StructuredFact(
                    key = "json_in_value",
                    value = """{"nested": "json", "array": [1, 2, 3]}""",
                    category = FactCategory.GENERAL
                )
            )

            val summary = ConversationSummary(
                sessionId = "unicode-session",
                narrative = "Conversation with unicode content",
                facts = facts,
                summarizedUpToIndex = 10
            )

            jdbcStore.save(summary)
            val loaded = jdbcStore.get("unicode-session")

            assertNotNull(loaded) {
                "Summary with unicode facts should be retrievable"
            }
            assertEquals(4, loaded!!.facts.size) {
                "All 4 facts should survive roundtrip, got ${loaded.facts.size}"
            }
            assertTrue(loaded.facts[0].value.contains("\u6D4B\u8BD5\u7528\u6237")) {
                "Chinese characters should survive roundtrip"
            }
            assertEquals(5000, loaded.facts[2].value.length) {
                "Long value should survive roundtrip with full length"
            }
            assertTrue(loaded.facts[3].value.contains("\"nested\"")) {
                "JSON-in-value should survive roundtrip without corruption"
            }
        }

        @Test
        fun `should handle upsert with growing facts list`() {
            // Start with 10 facts
            val initialFacts = (1..10).map { i ->
                StructuredFact(key = "fact_$i", value = "initial_$i")
            }
            jdbcStore.save(
                ConversationSummary(
                    sessionId = "growing",
                    narrative = "Initial",
                    facts = initialFacts,
                    summarizedUpToIndex = 20
                )
            )

            // Update to 100 facts
            val expandedFacts = (1..100).map { i ->
                StructuredFact(key = "fact_$i", value = "expanded_$i")
            }
            jdbcStore.save(
                ConversationSummary(
                    sessionId = "growing",
                    narrative = "Expanded",
                    facts = expandedFacts,
                    summarizedUpToIndex = 200
                )
            )

            val loaded = jdbcStore.get("growing")

            assertNotNull(loaded) {
                "Updated summary should be retrievable"
            }
            assertEquals(100, loaded!!.facts.size) {
                "Updated summary should have 100 facts, got ${loaded.facts.size}"
            }
            assertEquals("Expanded", loaded.narrative) {
                "Narrative should be updated"
            }
            assertEquals(200, loaded.summarizedUpToIndex) {
                "summarizedUpToIndex should be updated to 200"
            }
            assertEquals("expanded_1", loaded.facts[0].value) {
                "First fact should have updated value"
            }
        }
    }
}
