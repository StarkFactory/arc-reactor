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
    fun `concurrent add은(는) not lose messages해야 한다`() {
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

            assertEquals(threadCount, memory.getHistory().size) { "All $threadCount messages should be present" }
        } finally {
            executor.shutdown()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `concurrent add with eviction은(는) maintain max size해야 한다`() {
        val maxMessages = 10
        val memory = InMemoryConversationMemory(maxMessages = maxMessages)
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
    fun `concurrent read and write은(는) not throw해야 한다`() = runBlocking {
        val memory = InMemoryConversationMemory(maxMessages = 50)

        // 미리 채우기
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
    fun `concurrent getOrCreate with different session IDs은(는) not lose sessions해야 한다`() {
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

            // all threads at once for maximum contention를 해제합니다
            readyLatch.await(5, TimeUnit.SECONDS)
            startLatch.countDown()

            val results = futures.map { it.get(10, TimeUnit.SECONDS) }

            // Every future은(는) have returned a non-null memory해야 합니다
            assertEquals(threadCount, results.size) { "All futures should return results" }
            results.forEachIndexed { index, it ->
                assertNotNull(it, "Session memory at index $index should not be null")
            }

            // Every session ID은(는) be retrievable from the store해야 합니다
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
    fun `concurrent getOrCreate with same session ID은(는) return same instance해야 한다`() {
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

            // All threads은(는) receive the exact same ConversationMemory instance해야 합니다
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
    fun `concurrent eviction with maxSessions은(는) not exceed limit해야 한다`() {
        val maxSessions = 10
        val store = InMemoryMemoryStore(maxSessions = maxSessions)
        val totalSessions = 100
        val threadCount = totalSessions
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

            // surviving sessions를 세다
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
    fun `concurrent toolsUsed additions은(는) not throw or lose entries해야 한다`() = runBlocking {
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

        // no duplicates were introduced 확인
        val distinctTools = context.toolsUsed.toSet()
        assertEquals(toolCount, distinctTools.size, "Each tool entry should be unique")

        // all expected tools are present 확인
        for (i in 1..toolCount) {
            assertTrue(
                context.toolsUsed.contains("tool-$i"),
                "tool-$i should be present in toolsUsed"
            )
        }
    }

    @Test
    fun `concurrent toolsUsed iteration during modification은(는) not throw해야 한다`() = runBlocking {
        val context = HookContext(
            runId = "run-2",
            userId = "user-2",
            userPrompt = "test prompt"
        )

        // with some tools를 미리 채웁니다
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
                // Iteration over CopyOnWriteArrayList은(는) not throw해야 합니다
                // 동시 쓰기 중에도 ConcurrentModificationException 없음
                context.toolsUsed.toList()
            }
        }

        writers.awaitAll()
        val snapshots = readers.awaitAll()

        // All snapshots은(는) be valid lists (no exceptions thrown)해야 합니다
        snapshots.forEach { snapshot ->
            assertNotNull(snapshot) { "Snapshot from concurrent toolsUsed read should not be null" }
            assertTrue(snapshot.isNotEmpty(), "Snapshots should contain at least initial tools")
        }

        // Final state은(는) contain all tools해야 합니다
        assertEquals(10 + writerCount, context.toolsUsed.size) { "Should have initial + new tools" }
    }

    @Test
    fun `concurrent metadata writes은(는) not lose entries해야 한다`() = runBlocking {
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

        // every entry is correct 확인
        for (i in 1..entryCount) {
            assertEquals(
                "value-$i",
                context.metadata["key-$i"],
                "Metadata key-$i should have correct value"
            )
        }
    }

    @Test
    fun `concurrent metadata reads and writes은(는) not throw해야 한다`() = runBlocking {
        val context = HookContext(
            runId = "run-4",
            userId = "user-4",
            userPrompt = "test prompt"
        )

        // metadata를 미리 채웁니다
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
                // Reading from ConcurrentHashMap during writes은(는) be safe해야 합니다
                context.metadata.entries.associate { entry -> entry.key to entry.value }
            }
        }

        writers.awaitAll()
        val snapshots = readers.awaitAll()

        // All reader snapshots은(는) be valid maps (no exceptions thrown)해야 합니다
        snapshots.forEach { snapshot ->
            assertNotNull(snapshot) { "Snapshot from concurrent metadata read should not be null" }
            assertTrue(snapshot.isNotEmpty(), "Snapshots should contain at least pre-populated entries")
        }

        // Final state은(는) contain all entries해야 합니다
        assertEquals(20 + writerCount, context.metadata.size) { "Should have pre-populated + new entries" }

        for (i in 1..writerCount) {
            assertEquals("new-value-$i", context.metadata["new-key-$i"]) { "new-key-$i should have correct value" }
        }
        for (i in 1..20) {
            assertEquals("value-$i", context.metadata["existing-$i"]) { "existing-$i should have correct value" }
        }
    }
}
