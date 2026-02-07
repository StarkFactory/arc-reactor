package com.arc.reactor.memory

import com.arc.reactor.agent.model.Message
import com.arc.reactor.agent.model.MessageRole
import com.arc.reactor.hook.model.HookContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ConversationMemoryConcurrencyTest {

    @Test
    fun `concurrent add should not lose messages`() {
        val memory = InMemoryConversationMemory(maxMessages = 500)
        val threadCount = 100
        val executor = Executors.newFixedThreadPool(threadCount)
        val readyLatch = CountDownLatch(threadCount)
        val startLatch = CountDownLatch(1)

        try {
            val futures = (1..threadCount).map { i ->
                executor.submit {
                    readyLatch.countDown()
                    startLatch.await()
                    memory.add(Message(MessageRole.USER, "Message $i"))
                }
            }

            readyLatch.await(5, TimeUnit.SECONDS)
            startLatch.countDown()

            futures.forEach { it.get(10, TimeUnit.SECONDS) }

            assertEquals(threadCount, memory.getHistory().size)
        } finally {
            executor.shutdown()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `concurrent add with eviction should maintain max size`() {
        val maxMessages = 10
        val memory = InMemoryConversationMemory(maxMessages = maxMessages)
        val threadCount = 100
        val executor = Executors.newFixedThreadPool(20)
        val readyLatch = CountDownLatch(threadCount)
        val startLatch = CountDownLatch(1)

        try {
            val futures = (1..threadCount).map { i ->
                executor.submit {
                    readyLatch.countDown()
                    startLatch.await()
                    memory.add(Message(MessageRole.USER, "Message $i"))
                }
            }

            readyLatch.await(5, TimeUnit.SECONDS)
            startLatch.countDown()

            futures.forEach { it.get(10, TimeUnit.SECONDS) }

            val history = memory.getHistory()
            assertTrue(
                history.size <= maxMessages,
                "History size (${history.size}) should not exceed maxMessages ($maxMessages)"
            )
        } finally {
            executor.shutdown()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `concurrent read and write should not throw`() = runBlocking {
        val memory = InMemoryConversationMemory(maxMessages = 50)

        // Pre-populate
        for (i in 1..20) {
            memory.add(Message(MessageRole.USER, "Initial $i"))
        }

        val writers = (1..50).map { i ->
            async(Dispatchers.Default) {
                memory.add(Message(MessageRole.USER, "New $i"))
            }
        }

        val readers = (1..50).map {
            async(Dispatchers.Default) {
                memory.getHistory()
            }
        }

        writers.awaitAll()
        val snapshots = readers.awaitAll()

        snapshots.forEach { snapshot ->
            assertNotNull(snapshot) { "Snapshot from concurrent read should not be null" }
            assertTrue(snapshot.isNotEmpty()) { "Snapshot should contain messages, got empty list" }
        }
    }
}

class MemoryStoreConcurrencyTest {

    @Test
    fun `concurrent getOrCreate with different session IDs should not lose sessions`() {
        val store = InMemoryMemoryStore(maxSessions = 1000)
        val threadCount = 100
        val executor = Executors.newFixedThreadPool(threadCount)
        val readyLatch = CountDownLatch(threadCount)
        val startLatch = CountDownLatch(1)

        try {
            val futures = (1..threadCount).map { i ->
                executor.submit<ConversationMemory> {
                    readyLatch.countDown()
                    startLatch.await()
                    store.getOrCreate("session-$i")
                }
            }

            // Release all threads at once for maximum contention
            readyLatch.await(5, TimeUnit.SECONDS)
            startLatch.countDown()

            val results = futures.map { it.get(10, TimeUnit.SECONDS) }

            // Every future should have returned a non-null memory
            assertEquals(threadCount, results.size)
            results.forEachIndexed { index, it ->
                assertNotNull(it, "Session memory at index $index should not be null")
            }

            // Every session ID should be retrievable from the store
            for (i in 1..threadCount) {
                assertNotNull(
                    store.get("session-$i"),
                    "Session session-$i should exist in the store"
                )
            }
        } finally {
            executor.shutdown()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `concurrent getOrCreate with same session ID should return same instance`() {
        val store = InMemoryMemoryStore()
        val threadCount = 50
        val executor = Executors.newFixedThreadPool(threadCount)
        val readyLatch = CountDownLatch(threadCount)
        val startLatch = CountDownLatch(1)

        try {
            val futures = (1..threadCount).map {
                executor.submit<ConversationMemory> {
                    readyLatch.countDown()
                    startLatch.await()
                    store.getOrCreate("shared-session")
                }
            }

            readyLatch.await(5, TimeUnit.SECONDS)
            startLatch.countDown()

            val results = futures.map { it.get(10, TimeUnit.SECONDS) }

            // All threads should receive the exact same ConversationMemory instance
            val firstMemory = results[0]
            results.forEach { memory ->
                assertSame(firstMemory, memory, "All threads must get the same memory instance")
            }
        } finally {
            executor.shutdown()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `concurrent eviction with maxSessions should not exceed limit`() {
        val maxSessions = 10
        val store = InMemoryMemoryStore(maxSessions = maxSessions)
        val totalSessions = 100
        val threadCount = 50
        val executor = Executors.newFixedThreadPool(threadCount)
        val readyLatch = CountDownLatch(totalSessions)
        val startLatch = CountDownLatch(1)

        try {
            val futures = (1..totalSessions).map { i ->
                executor.submit {
                    readyLatch.countDown()
                    startLatch.await()
                    store.getOrCreate("evict-session-$i")
                }
            }

            readyLatch.await(5, TimeUnit.SECONDS)
            startLatch.countDown()

            futures.forEach { it.get(10, TimeUnit.SECONDS) }

            // Count surviving sessions
            var survivingCount = 0
            for (i in 1..totalSessions) {
                if (store.get("evict-session-$i") != null) {
                    survivingCount++
                }
            }

            assertEquals(
                maxSessions,
                survivingCount,
                "Exactly $maxSessions sessions should survive after eviction"
            )
        } finally {
            executor.shutdown()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `concurrent toolsUsed additions should not throw or lose entries`() = runBlocking {
        val context = HookContext(
            runId = "run-1",
            userId = "user-1",
            userPrompt = "test prompt"
        )

        val toolCount = 100
        val deferred = (1..toolCount).map { i ->
            async(Dispatchers.Default) {
                context.toolsUsed.add("tool-$i")
            }
        }

        deferred.awaitAll()

        assertEquals(
            toolCount,
            context.toolsUsed.size,
            "All tool entries should be present without loss"
        )

        // Verify no duplicates were introduced
        val distinctTools = context.toolsUsed.toSet()
        assertEquals(toolCount, distinctTools.size, "Each tool entry should be unique")

        // Verify all expected tools are present
        for (i in 1..toolCount) {
            assertTrue(
                context.toolsUsed.contains("tool-$i"),
                "tool-$i should be present in toolsUsed"
            )
        }
    }

    @Test
    fun `concurrent toolsUsed iteration during modification should not throw`() = runBlocking {
        val context = HookContext(
            runId = "run-2",
            userId = "user-2",
            userPrompt = "test prompt"
        )

        // Pre-populate with some tools
        for (i in 1..10) {
            context.toolsUsed.add("initial-tool-$i")
        }

        val writerCount = 50
        val readerCount = 50

        val writers = (1..writerCount).map { i ->
            async(Dispatchers.Default) {
                context.toolsUsed.add("new-tool-$i")
            }
        }

        val readers = (1..readerCount).map {
            async(Dispatchers.Default) {
                // Iteration over CopyOnWriteArrayList should not throw
                // ConcurrentModificationException even during concurrent writes
                context.toolsUsed.toList()
            }
        }

        writers.awaitAll()
        val snapshots = readers.awaitAll()

        // All snapshots should be valid lists (no exceptions thrown)
        snapshots.forEach { snapshot ->
            assertNotNull(snapshot) { "Snapshot from concurrent toolsUsed read should not be null" }
            assertTrue(snapshot.isNotEmpty(), "Snapshots should contain at least initial tools")
        }

        // Final state should contain all tools
        assertEquals(10 + writerCount, context.toolsUsed.size)
    }

    @Test
    fun `concurrent metadata writes should not lose entries`() = runBlocking {
        val context = HookContext(
            runId = "run-3",
            userId = "user-3",
            userPrompt = "test prompt"
        )

        val entryCount = 100
        val deferred = (1..entryCount).map { i ->
            async(Dispatchers.Default) {
                context.metadata["key-$i"] = "value-$i"
            }
        }

        deferred.awaitAll()

        assertEquals(
            entryCount,
            context.metadata.size,
            "All metadata entries should be present without loss"
        )

        // Verify every entry is correct
        for (i in 1..entryCount) {
            assertEquals(
                "value-$i",
                context.metadata["key-$i"],
                "Metadata key-$i should have correct value"
            )
        }
    }

    @Test
    fun `concurrent metadata reads and writes should not throw`() = runBlocking {
        val context = HookContext(
            runId = "run-4",
            userId = "user-4",
            userPrompt = "test prompt"
        )

        // Pre-populate metadata
        for (i in 1..20) {
            context.metadata["existing-$i"] = "value-$i"
        }

        val writerCount = 50
        val readerCount = 50

        val writers = (1..writerCount).map { i ->
            async(Dispatchers.Default) {
                context.metadata["new-key-$i"] = "new-value-$i"
            }
        }

        val readers = (1..readerCount).map {
            async(Dispatchers.Default) {
                // Reading from ConcurrentHashMap during writes should be safe
                context.metadata.entries.associate { entry -> entry.key to entry.value }
            }
        }

        writers.awaitAll()
        val snapshots = readers.awaitAll()

        // All reader snapshots should be valid maps (no exceptions thrown)
        snapshots.forEach { snapshot ->
            assertNotNull(snapshot) { "Snapshot from concurrent metadata read should not be null" }
            assertTrue(snapshot.isNotEmpty(), "Snapshots should contain at least pre-populated entries")
        }

        // Final state should contain all entries
        assertEquals(20 + writerCount, context.metadata.size)

        for (i in 1..writerCount) {
            assertEquals("new-value-$i", context.metadata["new-key-$i"])
        }
        for (i in 1..20) {
            assertEquals("value-$i", context.metadata["existing-$i"])
        }
    }
}
