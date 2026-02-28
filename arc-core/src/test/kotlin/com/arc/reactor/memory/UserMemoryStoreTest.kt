package com.arc.reactor.memory

import com.arc.reactor.memory.impl.InMemoryUserMemoryStore
import com.arc.reactor.memory.model.UserMemory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class UserMemoryStoreTest {

    @Nested
    inner class InMemoryStoreOperations {

        private val store: UserMemoryStore = InMemoryUserMemoryStore()

        @Test
        fun `get returns null for unknown user`() = runTest {
            val result = store.get("unknown-user")
            assertNull(result, "Unknown userId should return null from store")
        }

        @Test
        fun `save and get roundtrip preserves all fields`() = runTest {
            val memory = UserMemory(
                userId = "user-1",
                facts = mapOf("team" to "backend", "role" to "senior engineer"),
                preferences = mapOf("language" to "Korean"),
                recentTopics = listOf("Spring AI", "MCP")
            )
            store.save("user-1", memory)

            val loaded = store.get("user-1")
            assertNotNull(loaded, "Saved memory should be retrievable")
            assertEquals("user-1", loaded!!.userId, "userId must match after roundtrip")
            assertEquals(2, loaded.facts.size, "facts map should have 2 entries")
            assertEquals("backend", loaded.facts["team"], "team fact should be 'backend'")
            assertEquals("Korean", loaded.preferences["language"], "language preference should be 'Korean'")
            assertEquals(listOf("Spring AI", "MCP"), loaded.recentTopics, "recentTopics should match")
        }

        @Test
        fun `delete removes stored memory`() = runTest {
            store.save("user-2", UserMemory(userId = "user-2"))

            store.delete("user-2")

            assertNull(store.get("user-2"), "Memory should be null after delete")
        }

        @Test
        fun `delete is idempotent for unknown user`() = runTest {
            // Should not throw
            store.delete("nonexistent-user")
            assertNull(store.get("nonexistent-user"), "Deleting non-existent user should result in null")
        }

        @Test
        fun `updateFact merges new fact into existing memory`() = runTest {
            store.save("user-3", UserMemory(userId = "user-3", facts = mapOf("team" to "backend")))

            store.updateFact("user-3", "role", "senior engineer")

            val loaded = store.get("user-3")
            assertNotNull(loaded, "Memory should still exist after updateFact")
            assertEquals(2, loaded!!.facts.size, "facts should have 2 entries after merge")
            assertEquals("backend", loaded.facts["team"], "existing fact 'team' should be preserved")
            assertEquals("senior engineer", loaded.facts["role"], "new fact 'role' should be added")
        }

        @Test
        fun `updateFact creates new memory record when none exists`() = runTest {
            store.updateFact("new-user", "team", "platform")

            val loaded = store.get("new-user")
            assertNotNull(loaded, "updateFact should create memory for new user")
            assertEquals("platform", loaded!!.facts["team"], "fact should be set for new user")
        }

        @Test
        fun `updateFact overwrites existing fact with same key`() = runTest {
            store.save("user-4", UserMemory(userId = "user-4", facts = mapOf("team" to "backend")))

            store.updateFact("user-4", "team", "platform")

            val loaded = store.get("user-4")
            assertEquals("platform", loaded!!.facts["team"], "fact should be updated to new value")
            assertEquals(1, loaded.facts.size, "facts map size should not grow on key update")
        }

        @Test
        fun `updatePreference merges into existing preferences`() = runTest {
            store.save(
                "user-5",
                UserMemory(userId = "user-5", preferences = mapOf("language" to "Korean"))
            )

            store.updatePreference("user-5", "detail_level", "brief")

            val loaded = store.get("user-5")
            assertNotNull(loaded, "Memory should exist after updatePreference")
            assertEquals(2, loaded!!.preferences.size, "preferences should have 2 entries")
            assertEquals("brief", loaded.preferences["detail_level"], "new preference should be added")
        }

        @Test
        fun `addRecentTopic appends to topic list`() = runTest {
            store.save(
                "user-6",
                UserMemory(userId = "user-6", recentTopics = listOf("Spring AI"))
            )

            store.addRecentTopic("user-6", "MCP integration")

            val loaded = store.get("user-6")
            assertNotNull(loaded, "Memory should exist after addRecentTopic")
            assertEquals(
                listOf("Spring AI", "MCP integration"),
                loaded!!.recentTopics,
                "New topic should be appended to the list"
            )
        }

        @Test
        fun `addRecentTopic respects maxTopics limit`() = runTest {
            val topics = (1..5).map { "topic-$it" }
            store.save("user-7", UserMemory(userId = "user-7", recentTopics = topics))

            store.addRecentTopic("user-7", "topic-6", maxTopics = 5)

            val loaded = store.get("user-7")
            assertNotNull(loaded, "Memory should exist after addRecentTopic with maxTopics")
            assertEquals(5, loaded!!.recentTopics.size, "recentTopics should not exceed maxTopics=5")
            assertFalse(loaded.recentTopics.contains("topic-1"), "oldest topic should be evicted")
            assertTrue(loaded.recentTopics.contains("topic-6"), "newest topic should be present")
        }

        @Test
        fun `save overwrites previous memory completely`() = runTest {
            store.save("user-8", UserMemory(userId = "user-8", facts = mapOf("old" to "value")))
            store.save("user-8", UserMemory(userId = "user-8", facts = mapOf("new" to "value")))

            val loaded = store.get("user-8")
            assertNotNull(loaded, "Memory should exist after second save")
            assertFalse(loaded!!.facts.containsKey("old"), "Old fact should be gone after overwrite")
            assertTrue(loaded.facts.containsKey("new"), "New fact should be present after overwrite")
        }
    }

    @Nested
    inner class UserMemoryManagerOperations {

        private val store = InMemoryUserMemoryStore()
        private val manager = UserMemoryManager(store = store, maxRecentTopics = 3)

        @Test
        fun `getContextPrompt returns empty string when no memory exists`() = runTest {
            val prompt = manager.getContextPrompt("no-user")
            assertEquals("", prompt, "getContextPrompt should return empty string for unknown user")
        }

        @Test
        fun `getContextPrompt includes facts in output`() = runTest {
            store.save("ctx-user-1", UserMemory(userId = "ctx-user-1", facts = mapOf("team" to "backend")))

            val prompt = manager.getContextPrompt("ctx-user-1")
            assertTrue(prompt.contains("team=backend"), "Context prompt should include facts")
            assertTrue(prompt.startsWith("User context:"), "Context prompt should start with 'User context:'")
        }

        @Test
        fun `getContextPrompt includes preferences in output`() = runTest {
            store.save(
                "ctx-user-2",
                UserMemory(userId = "ctx-user-2", preferences = mapOf("language" to "Korean"))
            )

            val prompt = manager.getContextPrompt("ctx-user-2")
            assertTrue(prompt.contains("language=Korean"), "Context prompt should include preferences")
        }

        @Test
        fun `getContextPrompt includes recent topics in output`() = runTest {
            store.save(
                "ctx-user-3",
                UserMemory(userId = "ctx-user-3", recentTopics = listOf("Spring AI", "MCP"))
            )

            val prompt = manager.getContextPrompt("ctx-user-3")
            assertTrue(prompt.contains("recent topics:"), "Context prompt should include 'recent topics:' label")
            assertTrue(prompt.contains("Spring AI"), "Context prompt should include recent topic Spring AI")
            assertTrue(prompt.contains("MCP"), "Context prompt should include recent topic MCP")
        }

        @Test
        fun `getContextPrompt returns empty for memory with no data`() = runTest {
            store.save("empty-user", UserMemory(userId = "empty-user"))

            val prompt = manager.getContextPrompt("empty-user")
            assertEquals("", prompt, "Empty memory should produce empty context prompt")
        }

        @Test
        fun `recordTopic respects maxRecentTopics set at construction`() = runTest {
            store.save(
                "topic-user",
                UserMemory(userId = "topic-user", recentTopics = listOf("a", "b", "c"))
            )

            manager.recordTopic("topic-user", "d")

            val loaded = store.get("topic-user")
            assertNotNull(loaded, "Memory should exist after recordTopic")
            assertEquals(3, loaded!!.recentTopics.size, "recentTopics should stay at maxRecentTopics=3")
            assertFalse(loaded.recentTopics.contains("a"), "Oldest topic 'a' should be evicted")
            assertTrue(loaded.recentTopics.contains("d"), "New topic 'd' should be present")
        }
    }
}
