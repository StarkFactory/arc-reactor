package com.arc.reactor.memory

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.config.ConcurrencyProperties
import com.arc.reactor.agent.config.GuardProperties
import com.arc.reactor.agent.config.LlmProperties
import com.arc.reactor.agent.config.RagProperties
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.Message
import com.arc.reactor.agent.model.MessageRole
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * DefaultConversationManager의 세션 소유권 검증 커버리지 공백을 보강하는 테스트.
 *
 * 기존 ConversationManagerTest에서 다루지 않는 영역:
 * - 다른 사용자의 세션 접근 거부 (SessionOwnershipException)
 * - 소유권 DB 조회 실패 시 fail-close (SessionOwnershipVerificationException)
 * - 세션 소유자와 일치하는 사용자는 이력 로드 허용
 * - cancelActiveSummarization 호출
 * - userId=null 일 때 소유권 검증 건너뜀
 */
class ConversationManagerSessionOwnershipTest {

    /** 기본 AgentProperties (요약 비활성화) */
    private val properties = AgentProperties(
        llm = LlmProperties(maxConversationTurns = 5),
        guard = GuardProperties(),
        rag = RagProperties(),
        concurrency = ConcurrencyProperties()
    )

    private fun makeCommand(
        sessionId: String,
        userId: String? = null
    ): AgentCommand = AgentCommand(
        systemPrompt = "",
        userPrompt = "테스트 질문",
        userId = userId,
        metadata = mapOf("sessionId" to sessionId)
    )

    // ─────────────────────────────────────────────────────────────────────
    // 세션 소유권 검증 — 잘못된 사용자 접근 차단
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    inner class SessionOwnershipEnforcement {

        @Test
        fun `다른 사용자가 세션에 접근하면 빈 이력을 반환한다`() = runTest {
            val store = mockk<MemoryStore>()
            val memory = mockk<ConversationMemory>()

            // 세션 소유자: "alice" — 하지만 "bob"이 접근 시도
            every { store.getSessionOwner("session-1") } returns "alice"
            every { store.get("session-1") } returns memory
            every { memory.getHistory() } returns listOf(
                Message(MessageRole.USER, "앨리스의 메시지")
            )

            val manager = DefaultConversationManager(store, properties)
            val command = makeCommand("session-1", userId = "bob")

            val history = manager.loadHistory(command)

            assertTrue(history.isEmpty()) {
                "세션 소유자(alice)가 아닌 사용자(bob)에게는 빈 이력을 반환해야 한다"
            }
        }

        @Test
        fun `세션 소유자와 동일한 사용자는 이력을 로드할 수 있다`() = runTest {
            val store = mockk<MemoryStore>()
            val memory = mockk<ConversationMemory>()

            every { store.getSessionOwner("session-2") } returns "alice"
            every { store.get("session-2") } returns memory
            every { memory.getHistory() } returns listOf(
                Message(MessageRole.USER, "q1"),
                Message(MessageRole.ASSISTANT, "a1")
            )

            val manager = DefaultConversationManager(store, properties)
            val command = makeCommand("session-2", userId = "alice")

            val history = manager.loadHistory(command)

            assertEquals(2, history.size) {
                "세션 소유자(alice)는 자신의 이력 2개를 로드할 수 있어야 한다"
            }
        }

        @Test
        fun `세션에 소유자가 없으면 (null) 누구든 이력을 로드할 수 있다`() = runTest {
            val store = mockk<MemoryStore>()
            val memory = mockk<ConversationMemory>()

            // getSessionOwner가 null 반환 → 소유권 없음 → 접근 허용
            every { store.getSessionOwner("session-3") } returns null
            every { store.get("session-3") } returns memory
            every { memory.getHistory() } returns listOf(
                Message(MessageRole.USER, "q1")
            )

            val manager = DefaultConversationManager(store, properties)
            val command = makeCommand("session-3", userId = "carol")

            val history = manager.loadHistory(command)

            assertEquals(1, history.size) {
                "세션 소유자가 없으면 모든 사용자가 이력을 로드할 수 있어야 한다"
            }
        }

        @Test
        fun `R318 userId가 null인 경우 anonymous로 리졸브되어 소유권 검증을 수행한다`() = runTest {
            // R318 fix: 이전 구현은 null userId 요청을 조건 없이 통과시켜 CLAUDE.md Gotcha #8
            // 위반(Guard null userId skip = 보안 취약점). 이제 save 경로와 동일하게 "anonymous"로
            // 리졸브하여 소유권 검증을 수행한다 — session owner="alice"와 불일치 시 loadHistory가
            // 내부 fail-close로 empty list 반환 (SessionOwnershipException을 catch 하여 empty 반환).
            val store = mockk<MemoryStore>()

            every { store.getSessionOwner("session-4") } returns "alice"

            val manager = DefaultConversationManager(store, properties)
            // userId=null → "anonymous"로 리졸브 → "alice" 세션과 불일치 → fail-close 빈 리스트
            val command = makeCommand("session-4", userId = null)

            val history = manager.loadHistory(command)

            assertEquals(0, history.size) {
                "null userId 요청은 anonymous로 리졸브되어 owner=alice와 불일치 시 fail-close로 빈 이력을 반환해야 한다"
            }
        }

        @Test
        fun `R318 userId가 null이고 session owner도 없으면 anonymous anonymous 매칭으로 통과한다`() = runTest {
            val store = mockk<MemoryStore>()
            val memory = mockk<ConversationMemory>()

            // session owner 없음 → verifySessionOwnership이 early return (ownership 미보유)
            every { store.getSessionOwner("session-5") } returns null
            every { store.get("session-5") } returns memory
            every { memory.getHistory() } returns listOf(
                Message(MessageRole.USER, "q1")
            )

            val manager = DefaultConversationManager(store, properties)
            val command = makeCommand("session-5", userId = null)

            val history = manager.loadHistory(command)

            assertEquals(1, history.size) {
                "session owner가 없으면 null userId도 load 가능해야 한다"
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 소유권 DB 조회 실패 — fail-close
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    inner class OwnershipVerificationFailure {

        @Test
        fun `소유권 DB 조회 실패 시 fail-close로 빈 이력을 반환한다`() = runTest {
            val store = mockk<MemoryStore>()

            // getSessionOwner가 DB 오류로 예외를 던짐 (SessionOwnershipVerificationException 경로)
            every { store.getSessionOwner("session-err") } throws RuntimeException("DB connection failed")

            val manager = DefaultConversationManager(store, properties)
            val command = makeCommand("session-err", userId = "alice")

            val history = manager.loadHistory(command)

            // fail-close: 소유권 검증 실패 → 안전하게 빈 이력 반환
            assertTrue(history.isEmpty()) {
                "소유권 DB 조회 실패 시 fail-close로 빈 이력을 반환해야 한다 (정보 유출 방지)"
            }
        }

        @Test
        fun `소유권 DB 조회 실패 시 예외가 전파되지 않는다`() = runTest {
            val store = mockk<MemoryStore>()
            every { store.getSessionOwner(any()) } throws RuntimeException("네트워크 타임아웃")

            val manager = DefaultConversationManager(store, properties)
            val command = makeCommand("session-timeout", userId = "alice")

            // 예외가 전파되면 안 된다 — assertDoesNotThrow 역할
            var threw = false
            try {
                manager.loadHistory(command)
            } catch (_: Exception) {
                threw = true
            }

            assertTrue(!threw) {
                "소유권 DB 조회 실패 시 예외가 호출자에게 전파되면 안 된다"
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // cancelActiveSummarization — 활성 요약 작업 취소
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    inner class CancelActiveSummarization {

        @Test
        fun `cancelActiveSummarization은 예외 없이 완료된다`() {
            val manager = DefaultConversationManager(memoryStore = null, properties = properties)

            // 요약 작업이 없어도 예외가 발생하면 안 된다
            var threw = false
            try {
                manager.cancelActiveSummarization("nonexistent-session")
            } catch (_: Exception) {
                threw = true
            }

            assertTrue(!threw) {
                "활성 요약 없는 세션에 cancelActiveSummarization을 호출해도 예외가 없어야 한다"
            }
        }

        @Test
        fun `destroy 후에도 cancelActiveSummarization은 예외 없이 완료된다`() {
            val manager = DefaultConversationManager(memoryStore = null, properties = properties)
            manager.destroy()

            var threw = false
            try {
                manager.cancelActiveSummarization("some-session")
            } catch (_: Exception) {
                threw = true
            }

            assertTrue(!threw) {
                "destroy 후에도 cancelActiveSummarization은 안전하게 호출 가능해야 한다"
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 세션 격리 — 서로 다른 세션은 이력이 섞이지 않는다
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    inner class SessionIsolation {

        @Test
        fun `두 사용자의 세션은 서로 독립적으로 저장된다`() = runTest {
            val memoryStore = InMemoryMemoryStore()
            val manager = DefaultConversationManager(memoryStore, properties)

            // alice의 세션
            val aliceCmd = AgentCommand(
                systemPrompt = "", userPrompt = "앨리스 질문",
                userId = "alice",
                metadata = mapOf("sessionId" to "alice-session")
            )
            manager.saveHistory(aliceCmd, AgentResult.success("앨리스 응답"))

            // bob의 세션
            val bobCmd = AgentCommand(
                systemPrompt = "", userPrompt = "밥 질문",
                userId = "bob",
                metadata = mapOf("sessionId" to "bob-session")
            )
            manager.saveHistory(bobCmd, AgentResult.success("밥 응답"))

            // alice 세션 소유자 검증
            val aliceOwner = memoryStore.getSessionOwner("alice-session")
            assertEquals("alice", aliceOwner) {
                "alice-session의 소유자는 'alice'여야 한다"
            }

            // bob 세션 소유자 검증
            val bobOwner = memoryStore.getSessionOwner("bob-session")
            assertEquals("bob", bobOwner) {
                "bob-session의 소유자는 'bob'이어야 한다"
            }

            // alice가 bob 세션에 접근하면 빈 이력
            val aliceAccessBobSession = AgentCommand(
                systemPrompt = "", userPrompt = "앨리스의 침입 시도",
                userId = "alice",
                metadata = mapOf("sessionId" to "bob-session")
            )
            val blockedHistory = manager.loadHistory(aliceAccessBobSession)
            assertTrue(blockedHistory.isEmpty()) {
                "alice는 bob의 세션(bob-session)에 접근할 수 없어야 한다"
            }

            manager.destroy()
        }
    }
}
