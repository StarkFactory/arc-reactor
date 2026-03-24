package com.arc.reactor.memory

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.config.ConcurrencyProperties
import com.arc.reactor.agent.config.GuardProperties
import com.arc.reactor.agent.config.LlmProperties
import com.arc.reactor.agent.config.MemoryProperties
import com.arc.reactor.agent.config.RagProperties
import com.arc.reactor.agent.config.SummaryProperties
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.Message
import com.arc.reactor.agent.model.MessageRole
import com.arc.reactor.memory.summary.ConversationSummary
import com.arc.reactor.memory.summary.ConversationSummaryService
import com.arc.reactor.memory.summary.ConversationSummaryStore
import com.arc.reactor.memory.summary.FactCategory
import com.arc.reactor.memory.summary.InMemoryConversationSummaryStore
import com.arc.reactor.memory.summary.StructuredFact
import com.arc.reactor.memory.summary.SummarizationResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.SystemMessage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * ConversationManager에 대한 테스트.
 *
 * 대화 기록 관리, 요약, 토큰 제한 등
 * 핵심 기능을 검증합니다.
 */
class ConversationManagerTest {

    private val properties = AgentProperties(
        llm = LlmProperties(maxConversationTurns = 5),
        guard = GuardProperties(),
        rag = RagProperties(),
        concurrency = ConcurrencyProperties()
    )

    @Nested
    inner class LoadHistory {

        @Test
        fun `provided일 때 use conversationHistory from command해야 한다`() = runTest {
            val manager = DefaultConversationManager(memoryStore = null, properties = properties)
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "hello",
                conversationHistory = listOf(
                    Message(MessageRole.USER, "previous question"),
                    Message(MessageRole.ASSISTANT, "previous answer")
                )
            )

            val history = manager.loadHistory(command)

            assertEquals(2, history.size, "Should return provided conversation history")
        }

        @Test
        fun `no conversationHistory일 때 load from memoryStore해야 한다`() = runTest {
            val memoryStore = mockk<MemoryStore>()
            val memory = mockk<ConversationMemory>()
            every { memoryStore.get("session-1") } returns memory
            every { memory.getHistory() } returns listOf(
                Message(MessageRole.USER, "q1"),
                Message(MessageRole.ASSISTANT, "a1"),
                Message(MessageRole.USER, "q2"),
                Message(MessageRole.ASSISTANT, "a2")
            )

            val manager = DefaultConversationManager(memoryStore, properties)
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "hello",
                metadata = mapOf("sessionId" to "session-1")
            )

            val history = manager.loadHistory(command)

            assertEquals(4, history.size, "Should load all messages from memory")
        }

        @Test
        fun `limit history by maxConversationTurns해야 한다`() = runTest {
            val memoryStore = mockk<MemoryStore>()
            val memory = mockk<ConversationMemory>()
            every { memoryStore.get("session-1") } returns memory

            // 20 messages (10 turns), but maxConversationTurns=5 means max 10 messages
            val messages = (1..20).map { i ->
                if (i % 2 == 1) Message(MessageRole.USER, "q$i")
                else Message(MessageRole.ASSISTANT, "a$i")
            }
            every { memory.getHistory() } returns messages

            val props = properties.copy(llm = properties.llm.copy(maxConversationTurns = 5))
            val manager = DefaultConversationManager(memoryStore, props)
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "hello",
                metadata = mapOf("sessionId" to "session-1")
            )

            val history = manager.loadHistory(command)

            assertEquals(10, history.size, "Should limit to maxConversationTurns * 2")
        }

        @Test
        fun `no sessionId and no conversationHistory일 때 return empty list해야 한다`() = runTest {
            val manager = DefaultConversationManager(memoryStore = null, properties = properties)
            val command = AgentCommand(systemPrompt = "", userPrompt = "hello")

            val history = manager.loadHistory(command)

            assertTrue(history.isEmpty(), "Should return empty when no session and no history")
        }

        @Test
        fun `memoryStore is null일 때 return empty list해야 한다`() = runTest {
            val manager = DefaultConversationManager(memoryStore = null, properties = properties)
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "hello",
                metadata = mapOf("sessionId" to "session-1")
            )

            val history = manager.loadHistory(command)

            assertTrue(history.isEmpty(), "Should return empty when memoryStore is null")
        }
    }

    @Nested
    inner class SaveHistory {

        @Test
        fun `save user and assistant messages on success해야 한다`() = runTest {
            val memoryStore = mockk<MemoryStore>(relaxed = true)
            val manager = DefaultConversationManager(memoryStore, properties)
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "what is AI?",
                metadata = mapOf("sessionId" to "s1")
            )
            val result = AgentResult.success("AI is artificial intelligence")

            manager.saveHistory(command, result)

            verify { memoryStore.addMessage("s1", "user", "what is AI?", "anonymous") }
            verify { memoryStore.addMessage("s1", "assistant", "AI is artificial intelligence", "anonymous") }
        }

        @Test
        fun `save user message but not assistant on failure해야 한다`() = runTest {
            val memoryStore = mockk<MemoryStore>(relaxed = true)
            val manager = DefaultConversationManager(memoryStore, properties)
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "hello",
                metadata = mapOf("sessionId" to "s1")
            )
            val result = AgentResult.failure("error")

            manager.saveHistory(command, result)

            verify(exactly = 1) { memoryStore.addMessage("s1", "user", "hello", "anonymous") }
            verify(exactly = 0) { memoryStore.addMessage("s1", "assistant", any(), any()) }
        }

        @Test
        fun `memoryStore save fails일 때 not throw해야 한다`() = runTest {
            val memoryStore = mockk<MemoryStore>()
            every { memoryStore.addMessage(any(), any(), any(), any()) } throws RuntimeException("DB error")
            val manager = DefaultConversationManager(memoryStore, properties)
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "hello",
                metadata = mapOf("sessionId" to "s1")
            )
            val result = AgentResult.success("ok")

            assertDoesNotThrow({
                kotlinx.coroutines.runBlocking { manager.saveHistory(command, result) }
            }, "Should handle memoryStore errors gracefully")
        }
    }

    @Nested
    inner class SaveStreamingHistory {

        @Test
        fun `save streaming content해야 한다`() = runTest {
            val memoryStore = mockk<MemoryStore>(relaxed = true)
            val manager = DefaultConversationManager(memoryStore, properties)
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "tell me a story",
                metadata = mapOf("sessionId" to "s1")
            )

            manager.saveStreamingHistory(command, "Once upon a time...")

            verify { memoryStore.addMessage("s1", "user", "tell me a story", "anonymous") }
            verify { memoryStore.addMessage("s1", "assistant", "Once upon a time...", "anonymous") }
        }

        @Test
        fun `not save empty streaming content as assistant message해야 한다`() = runTest {
            val memoryStore = mockk<MemoryStore>(relaxed = true)
            val manager = DefaultConversationManager(memoryStore, properties)
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "hello",
                metadata = mapOf("sessionId" to "s1")
            )

            manager.saveStreamingHistory(command, "")

            verify { memoryStore.addMessage("s1", "user", "hello", "anonymous") }
            verify(exactly = 0) { memoryStore.addMessage("s1", "assistant", any(), any()) }
        }
    }

    @Nested
    inner class SessionMemoryPersistence {

        @Test
        fun `save and load일 때 history is available in next request해야 한다`() = runTest {
            val memoryStore = InMemoryMemoryStore()
            val manager = DefaultConversationManager(memoryStore, properties)
            val sessionId = "test-session"

            // 요청 1: 사용자가 자기소개
            val command1 = AgentCommand(
                systemPrompt = "You are a helpful assistant",
                userPrompt = "내 이름은 테스터야",
                metadata = mapOf("sessionId" to sessionId)
            )
            val result1 = AgentResult.success("안녕하세요, 테스터님! 무엇을 도와드릴까요?")
            manager.saveHistory(command1, result1)

            // 요청 2: 같은 세션으로 이름을 물어봄
            val command2 = AgentCommand(
                systemPrompt = "You are a helpful assistant",
                userPrompt = "내 이름이 뭐야?",
                metadata = mapOf("sessionId" to sessionId)
            )
            val history = manager.loadHistory(command2)

            assertTrue(history.isNotEmpty()) {
                "History should not be empty for second request with same sessionId"
            }
            assertEquals(2, history.size) {
                "History should contain user + assistant messages from first request"
            }
        }

        @Test
        fun `multiple turns에서 accumulate history해야 한다`() = runTest {
            val memoryStore = InMemoryMemoryStore()
            val manager = DefaultConversationManager(memoryStore, properties)
            val sessionId = "multi-turn-session"

            // 턴 1
            val cmd1 = AgentCommand(
                systemPrompt = "", userPrompt = "Hello",
                metadata = mapOf("sessionId" to sessionId)
            )
            manager.saveHistory(cmd1, AgentResult.success("Hi there!"))

            // 턴 2
            val cmd2 = AgentCommand(
                systemPrompt = "", userPrompt = "What is 1+1?",
                metadata = mapOf("sessionId" to sessionId)
            )
            manager.saveHistory(cmd2, AgentResult.success("2"))

            // 턴 3: 이력 확인
            val cmd3 = AgentCommand(
                systemPrompt = "", userPrompt = "Thanks!",
                metadata = mapOf("sessionId" to sessionId)
            )
            val history = manager.loadHistory(cmd3)

            assertEquals(4, history.size) {
                "History should contain 4 messages (2 user + 2 assistant) from previous turns"
            }
        }
    }

    @Nested
    inner class MessagePairIntegrity {

        @Test
        fun `concurrent saves to same session은(는) maintain user-assistant pair ordering해야 한다`() {
            val memoryStore = InMemoryMemoryStore()
            val manager = DefaultConversationManager(memoryStore, properties)
            val sessionId = "concurrent-session"
            val threadCount = 20
            val executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount)
            val readyLatch = CountDownLatch(threadCount)
            val startLatch = CountDownLatch(1)

            try {
                val futures = (1..threadCount).map { i ->
                    executor.submit {
                        readyLatch.countDown()
                        startLatch.await()
                        val command = AgentCommand(
                            systemPrompt = "",
                            userPrompt = "user-$i",
                            metadata = mapOf("sessionId" to sessionId)
                        )
                        val result = AgentResult.success("assistant-$i")
                        kotlinx.coroutines.runBlocking {
                            manager.saveHistory(command, result)
                        }
                    }
                }

                readyLatch.await(5, TimeUnit.SECONDS)
                startLatch.countDown()
                futures.forEach { it.get(10, TimeUnit.SECONDS) }

                val history = memoryStore.get(sessionId)!!.getHistory()
                assertEquals(
                    threadCount * 2, history.size,
                    "Should have $threadCount user + $threadCount assistant messages"
                )

                // user-assistant pairs are adjacent (never interleaved) 확인
                var i = 0
                while (i < history.size) {
                    val userMsg = history[i]
                    assertEquals(MessageRole.USER, userMsg.role,
                        "Message at index $i should be USER, got ${userMsg.role}")
                    assertTrue(i + 1 < history.size,
                        "USER message at index $i must have a following ASSISTANT message")
                    val assistantMsg = history[i + 1]
                    assertEquals(MessageRole.ASSISTANT, assistantMsg.role,
                        "Message at index ${i + 1} should be ASSISTANT, got ${assistantMsg.role}")

                    // the number from content to verify pair matches 추출
                    val userNum = userMsg.content.removePrefix("user-")
                    val assistantNum = assistantMsg.content.removePrefix("assistant-")
                    assertEquals(userNum, assistantNum,
                        "User-$userNum and Assistant-$assistantNum should be paired together")
                    i += 2
                }
            } finally {
                executor.shutdown()
                executor.awaitTermination(5, TimeUnit.SECONDS)
                manager.destroy()
            }
        }
    }

    @Nested
    inner class HierarchicalMemory {

        private val summaryProps = SummaryProperties(
            enabled = true,
            triggerMessageCount = 6,
            recentMessageCount = 4,
            maxNarrativeTokens = 500
        )

        private val summaryEnabledProperties = properties.copy(
            memory = MemoryProperties(summary = summaryProps)
        )

        @Test
        fun `summary is disabled일 때 use takeLast해야 한다`() = runTest {
            val memoryStore = mockk<MemoryStore>()
            val memory = mockk<ConversationMemory>()
            every { memoryStore.get("s1") } returns memory
            every { memory.getHistory() } returns generateMessages(30)

            val manager = DefaultConversationManager(memoryStore, properties)
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "hi",
                metadata = mapOf("sessionId" to "s1")
            )

            val history = manager.loadHistory(command)

            assertEquals(10, history.size, "Should use takeLast when summary disabled")
        }

        @Test
        fun `message count below trigger일 때 use takeLast해야 한다`() = runTest {
            val memoryStore = mockk<MemoryStore>()
            val memory = mockk<ConversationMemory>()
            every { memoryStore.get("s1") } returns memory
            every { memory.getHistory() } returns generateMessages(4)

            val manager = DefaultConversationManager(memoryStore, summaryEnabledProperties,
                summaryStore = InMemoryConversationSummaryStore(),
                summaryService = mockk())
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "hi",
                metadata = mapOf("sessionId" to "s1")
            )

            val history = manager.loadHistory(command)

            assertEquals(4, history.size, "Should use takeLast when below trigger count")
        }

        @Test
        fun `facts and narrative로 build hierarchical history해야 한다`() = runTest {
            val memoryStore = mockk<MemoryStore>()
            val memory = mockk<ConversationMemory>()
            val summaryStore = InMemoryConversationSummaryStore()
            val summaryService = mockk<ConversationSummaryService>()

            val messages = generateMessages(10) // above trigger (6)
            every { memoryStore.get("s1") } returns memory
            every { memory.getHistory() } returns messages

            coEvery { summaryService.summarize(any(), any()) } returns SummarizationResult(
                narrative = "User discussed order details",
                facts = listOf(
                    StructuredFact(key = "order_id", value = "#1234", category = FactCategory.ENTITY)
                )
            )

            val manager = DefaultConversationManager(
                memoryStore, summaryEnabledProperties, summaryStore, summaryService
            )
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "hi",
                metadata = mapOf("sessionId" to "s1")
            )

            val history = manager.loadHistory(command)

            // 기대값: [Facts SystemMessage] + [Narrative SystemMessage] + [4 recent messages]
            assertEquals(6, history.size, "Should have 2 system messages + 4 recent messages")
            assertTrue(history[0] is SystemMessage, "First message should be Facts SystemMessage")
            assertTrue((history[0] as SystemMessage).text.contains("order_id"),
                "Facts should contain order_id")
            assertTrue(history[1] is SystemMessage, "Second message should be Narrative SystemMessage")
            assertTrue((history[1] as SystemMessage).text.contains("order details"),
                "Narrative should contain summary text")
        }

        @Test
        fun `up to date일 때 reuse cached summary해야 한다`() = runTest {
            val memoryStore = mockk<MemoryStore>()
            val memory = mockk<ConversationMemory>()
            val summaryStore = InMemoryConversationSummaryStore()
            val summaryService = mockk<ConversationSummaryService>()

            val messages = generateMessages(10)
            every { memoryStore.get("s1") } returns memory
            every { memory.getHistory() } returns messages

            // Pre-populate cached summary covering messages 0..5 (splitIndex = 10 - 4 = 6)
            summaryStore.save(ConversationSummary(
                sessionId = "s1",
                narrative = "Cached summary",
                facts = listOf(StructuredFact(key = "cached", value = "true")),
                summarizedUpToIndex = 6
            ))

            val manager = DefaultConversationManager(
                memoryStore, summaryEnabledProperties, summaryStore, summaryService
            )
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "hi",
                metadata = mapOf("sessionId" to "s1")
            )

            val history = manager.loadHistory(command)

            // use cached summary without calling summaryService해야 합니다
            assertEquals(6, history.size, "Should use cached summary")
            assertTrue((history[0] as SystemMessage).text.contains("cached"),
                "Should use cached facts")
        }

        @Test
        fun `fallback to takeLast on summarization failure해야 한다`() = runTest {
            val memoryStore = mockk<MemoryStore>()
            val memory = mockk<ConversationMemory>()
            val summaryStore = InMemoryConversationSummaryStore()
            val summaryService = mockk<ConversationSummaryService>()

            val messages = generateMessages(10)
            every { memoryStore.get("s1") } returns memory
            every { memory.getHistory() } returns messages

            coEvery { summaryService.summarize(any(), any()) } throws RuntimeException("LLM error")

            val manager = DefaultConversationManager(
                memoryStore, summaryEnabledProperties, summaryStore, summaryService
            )
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "hi",
                metadata = mapOf("sessionId" to "s1")
            )

            val history = manager.loadHistory(command)

            assertEquals(10, history.size,
                "Should fallback to takeLast (maxConversationTurns=5 * 2=10)")
        }

        @Test
        fun `handle empty facts in hierarchical history해야 한다`() = runTest {
            val memoryStore = mockk<MemoryStore>()
            val memory = mockk<ConversationMemory>()
            val summaryStore = InMemoryConversationSummaryStore()
            val summaryService = mockk<ConversationSummaryService>()

            every { memoryStore.get("s1") } returns memory
            every { memory.getHistory() } returns generateMessages(10)

            coEvery { summaryService.summarize(any(), any()) } returns SummarizationResult(
                narrative = "Just a narrative",
                facts = emptyList()
            )

            val manager = DefaultConversationManager(
                memoryStore, summaryEnabledProperties, summaryStore, summaryService
            )
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "hi",
                metadata = mapOf("sessionId" to "s1")
            )

            val history = manager.loadHistory(command)

            // facts SystemMessage, just [Narrative SystemMessage] + [4 recent messages] 없음
            assertEquals(5, history.size, "Should have 1 narrative message + 4 recent messages")
            assertTrue(history[0] is SystemMessage, "First should be Narrative SystemMessage")
        }

        @Test
        fun `trigger async summarization from saveStreamingHistory해야 한다`() = runTest {
            val memoryStore = mockk<MemoryStore>(relaxed = true)
            val memory = mockk<ConversationMemory>()
            val summaryStore = mockk<ConversationSummaryStore>(relaxed = true)
            val summaryService = mockk<ConversationSummaryService>()

            // 20 messages (above trigger of 6)를 미리 채웁니다
            val messages = generateMessages(20)
            every { memoryStore.get("s1") } returns memory
            every { memory.getHistory() } returns messages
            every { summaryStore.get("s1") } returns null

            val latch = CountDownLatch(1)
            coEvery { summaryService.summarize(any(), any()) } coAnswers {
                latch.countDown()
                SummarizationResult(narrative = "Streamed summary", facts = emptyList())
            }

            val manager = DefaultConversationManager(
                memoryStore, summaryEnabledProperties, summaryStore, summaryService
            )
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "streaming msg",
                metadata = mapOf("sessionId" to "s1")
            )

            manager.saveStreamingHistory(command, "response content")

            assertTrue(latch.await(5, TimeUnit.SECONDS)) {
                "saveStreamingHistory should trigger async summarization"
            }
            manager.destroy()
        }

        @Test
        fun `same session on rapid saves에 대해 cancel previous summarization job해야 한다`() = runTest {
            val memoryStore = mockk<MemoryStore>(relaxed = true)
            val memory = mockk<ConversationMemory>()
            val summaryStore = mockk<ConversationSummaryStore>(relaxed = true)
            val summaryService = mockk<ConversationSummaryService>()

            val messages = generateMessages(20)
            every { memoryStore.get("s1") } returns memory
            every { memory.getHistory() } returns messages
            every { summaryStore.get("s1") } returns null

            val firstCallStarted = CountDownLatch(1)
            val secondCallLatch = CountDownLatch(1)
            val callCount = AtomicInteger(0)
            coEvery { summaryService.summarize(any(), any()) } coAnswers {
                val current = callCount.incrementAndGet()
                if (current == 1) {
                    firstCallStarted.countDown()
                    delay(5000) // 첫 번째 호출이 오래 블록됨
                }
                if (current == 2) {
                    secondCallLatch.countDown()
                }
                SummarizationResult(narrative = "summary-$current", facts = emptyList())
            }

            val manager = DefaultConversationManager(
                memoryStore, summaryEnabledProperties, summaryStore, summaryService
            )
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "msg",
                metadata = mapOf("sessionId" to "s1")
            )

            // 두 번의 빠른 저장을 트리거 — 두 번째가 첫 번째를 취소해야 합니다
            manager.saveHistory(command, AgentResult.success("r1"))
            assertTrue(firstCallStarted.await(3, TimeUnit.SECONDS)) {
                "First summarization should start before triggering second save"
            }
            manager.saveHistory(command, AgentResult.success("r2"))

            assertTrue(secondCallLatch.await(3, TimeUnit.SECONDS)) {
                "Second summarization should complete (first should be cancelled)"
            }
            manager.destroy()
        }

        @Test
        fun `sessionId is missing일 때 not trigger async summarization해야 한다`() = runTest {
            val memoryStore = mockk<MemoryStore>(relaxed = true)
            val summaryService = mockk<ConversationSummaryService>()

            val manager = DefaultConversationManager(
                memoryStore, summaryEnabledProperties,
                summaryStore = InMemoryConversationSummaryStore(),
                summaryService = summaryService
            )
            // 메타데이터에 sessionId 없음
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "msg",
                metadata = emptyMap()
            )

            manager.saveHistory(command, AgentResult.success("response"))

            // never attempt summarization해야 합니다
            coVerify(exactly = 0) { summaryService.summarize(any(), any()) }
            manager.destroy()
        }

        @Test
        fun `session memory is null일 때 not trigger async summarization해야 한다`() = runTest {
            val memoryStore = mockk<MemoryStore>(relaxed = true)
            val summaryService = mockk<ConversationSummaryService>()

            // memoryStore.get()이 null을 반환합니다 (예: 세션이 삭제됨)
            every { memoryStore.get("s1") } returns null

            val manager = DefaultConversationManager(
                memoryStore, summaryEnabledProperties,
                summaryStore = InMemoryConversationSummaryStore(),
                summaryService = summaryService
            )
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "msg",
                metadata = mapOf("sessionId" to "s1")
            )

            manager.saveHistory(command, AgentResult.success("response"))
            Thread.sleep(300) // Give async a chance to fire (it shouldn't)

            coVerify(exactly = 0) { summaryService.summarize(any(), any()) }
            manager.destroy()
        }

        @Test
        fun `cancel asyncScope on destroy해야 한다`() = runTest {
            val memoryStore = mockk<MemoryStore>(relaxed = true)
            val memory = mockk<ConversationMemory>()
            val summaryStore = mockk<ConversationSummaryStore>(relaxed = true)
            val summaryService = mockk<ConversationSummaryService>()

            val messages = generateMessages(20)
            every { memoryStore.get("s1") } returns memory
            every { memory.getHistory() } returns messages
            every { summaryStore.get("s1") } returns null

            coEvery { summaryService.summarize(any(), any()) } coAnswers {
                delay(10000) // Long-running job
                SummarizationResult(narrative = "should not complete", facts = emptyList())
            }

            val manager = DefaultConversationManager(
                memoryStore, summaryEnabledProperties, summaryStore, summaryService
            )
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "msg",
                metadata = mapOf("sessionId" to "s1")
            )

            manager.saveHistory(command, AgentResult.success("r1"))
            Thread.sleep(100) // Let async job start

            // destroy은(는) cancel all pending jobs해야 합니다
            manager.destroy()
            Thread.sleep(300) // Let cancellation propagate

            // the summary was NOT saved (job was cancelled before completion) 확인
            coVerify(exactly = 0) { summaryStore.save(any()) }
        }

        @Test
        fun `summary produces empty facts and narrative일 때 fallback to takeLast해야 한다`() = runTest {
            val memoryStore = mockk<MemoryStore>()
            val memory = mockk<ConversationMemory>()
            val summaryStore = InMemoryConversationSummaryStore()
            val summaryService = mockk<ConversationSummaryService>()

            val messages = generateMessages(10)
            every { memoryStore.get("s1") } returns memory
            every { memory.getHistory() } returns messages

            // LLM이 빈 요약을 반환합니다 (사실 없음, 빈 서술)
            coEvery { summaryService.summarize(any(), any()) } returns SummarizationResult(
                narrative = "",
                facts = emptyList()
            )

            val manager = DefaultConversationManager(
                memoryStore, summaryEnabledProperties, summaryStore, summaryService
            )
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "hi",
                metadata = mapOf("sessionId" to "s1")
            )

            val history = manager.loadHistory(command)

            // fallback to takeLast, NOT return only recentMessageCount (4)해야 합니다
            assertEquals(10, history.size) {
                "Empty summary should fallback to takeLast (maxConversationTurns=5 * 2=10), not recentMessageCount=4"
            }
            assertFalse(history.any { it is SystemMessage }) {
                "Fallback path should not contain SystemMessages"
            }
        }

        @Test
        fun `recentMessageCount exceeds total messages일 때 return all messages해야 한다`() = runTest {
            val memoryStore = mockk<MemoryStore>()
            val memory = mockk<ConversationMemory>()
            val summaryService = mockk<ConversationSummaryService>()

            // recentMessageCount=4 but only 3 messages above trigger
            // triggerMessageCount < recentMessageCount인 설정이 필요합니다
            val misconfigProps = properties.copy(
                memory = MemoryProperties(summary = SummaryProperties(
                    enabled = true,
                    triggerMessageCount = 2,  // at 2를 트리거합니다
                    recentMessageCount = 10,  // but recent window is 10
                    maxNarrativeTokens = 500
                ))
            )

            val messages = generateMessages(4) // 4 messages > trigger (2)
            every { memoryStore.get("s1") } returns memory
            every { memory.getHistory() } returns messages

            val manager = DefaultConversationManager(
                memoryStore, misconfigProps,
                summaryStore = InMemoryConversationSummaryStore(),
                summaryService = summaryService
            )
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "hi",
                metadata = mapOf("sessionId" to "s1")
            )

            val history = manager.loadHistory(command)

            // splitIndex = max(4 - 10, 0) = 0 → early exit, return all messages
            assertEquals(4, history.size) {
                "When recentMessageCount >= totalMessages, should return all messages without summarizing"
            }
            // summarize은(는) NOT be called해야 합니다
            coVerify(exactly = 0) { summaryService.summarize(any(), any()) }
        }

        private fun generateMessages(count: Int): List<Message> = (1..count).map { i ->
            if (i % 2 == 1) Message(MessageRole.USER, "q$i")
            else Message(MessageRole.ASSISTANT, "a$i")
        }
    }
}
