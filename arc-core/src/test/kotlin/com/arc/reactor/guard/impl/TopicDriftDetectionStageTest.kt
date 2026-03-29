package com.arc.reactor.guard.impl

import com.arc.reactor.agent.model.Message
import com.arc.reactor.agent.model.MessageRole
import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.guard.model.RejectionCategory
import com.arc.reactor.memory.ConversationMemory
import com.arc.reactor.memory.MemoryStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * TopicDriftDetectionStage 단위 테스트.
 *
 * Crescendo 공격 탐지, 슬라이딩 윈도우, MemoryStore 연동,
 * 메타데이터 우선순위 등 핵심 동작을 검증한다.
 */
class TopicDriftDetectionStageTest {

    // ── 헬퍼: MemoryStore mock 생성 ──

    private fun memoryStoreWithHistory(vararg userMessages: String): MemoryStore {
        val messages = userMessages.map { Message(role = MessageRole.USER, content = it) }
        val memory = mockk<ConversationMemory>()
        every { memory.getHistory() } returns messages

        val store = mockk<MemoryStore>()
        every { store.get(any()) } returns memory
        return store
    }

    private fun emptyMemoryStore(): MemoryStore {
        val store = mockk<MemoryStore>()
        every { store.get(any()) } returns null
        return store
    }

    // ── 헬퍼: GuardCommand 생성 ──

    private fun commandWithHistory(
        text: String,
        history: List<String>
    ) = GuardCommand(
        userId = "test-user",
        text = text,
        metadata = mapOf("conversationHistory" to history)
    )

    private fun commandWithSession(text: String, sessionId: String) = GuardCommand(
        userId = "test-user",
        text = text,
        metadata = mapOf("sessionId" to sessionId)
    )

    // ────────────────────────────────────────────────────────────────────────────
    @Nested
    inner class 이력없음 {

        @Test
        fun `MemoryStore가 없으면 항상 허용된다`() = runTest {
            val stage = TopicDriftDetectionStage(memoryStore = null)
            val result = stage.enforce(GuardCommand(userId = "u1", text = "hello"))

            assertEquals(GuardResult.Allowed.DEFAULT, result) {
                "MemoryStore 없이는 이력이 없으므로 항상 허용해야 한다"
            }
        }

        @Test
        fun `sessionId가 없으면 항상 허용된다`() = runTest {
            val stage = TopicDriftDetectionStage(memoryStore = emptyMemoryStore())
            val result = stage.enforce(GuardCommand(userId = "u1", text = "hello"))

            assertEquals(GuardResult.Allowed.DEFAULT, result) {
                "sessionId 없이는 이력 조회 불가 — 허용해야 한다"
            }
        }

        @Test
        fun `MemoryStore에서 세션을 찾지 못하면 허용된다`() = runTest {
            val stage = TopicDriftDetectionStage(memoryStore = emptyMemoryStore())
            val result = stage.enforce(commandWithSession("test", "unknown-session"))

            assertEquals(GuardResult.Allowed.DEFAULT, result) {
                "세션 미존재 시 이력 없음 → 허용해야 한다"
            }
        }

        @Test
        fun `metadata 이력이 비어있으면 허용된다`() = runTest {
            val stage = TopicDriftDetectionStage()
            val result = stage.enforce(commandWithHistory("test", emptyList()))

            assertEquals(GuardResult.Allowed.DEFAULT, result) {
                "빈 이력에서는 드리프트를 측정할 수 없으므로 허용해야 한다"
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    @Nested
    inner class 일반_안전입력 {

        @Test
        fun `에스컬레이션 패턴 없는 정상 대화는 허용된다`() = runTest {
            val stage = TopicDriftDetectionStage()
            val result = stage.enforce(
                commandWithHistory(
                    text = "내일 날씨는 어때?",
                    history = listOf("안녕하세요", "오늘 점심 메뉴 추천해줘")
                )
            )

            assertEquals(GuardResult.Allowed.DEFAULT, result) {
                "에스컬레이션 패턴 없는 정상 대화는 허용되어야 한다"
            }
        }

        @Test
        fun `단일 이력 항목은 windowSize 미만이므로 허용된다`() = runTest {
            val stage = TopicDriftDetectionStage()
            // window = [이력1] + [현재] = size 2이지만 calculateDriftScore의 weight 합이 낮으면 통과
            val result = stage.enforce(
                commandWithHistory(
                    text = "일반적인 질문",
                    history = listOf("이전 일반 메시지")
                )
            )

            assertEquals(GuardResult.Allowed.DEFAULT, result) {
                "에스컬레이션 없는 단일 이력은 허용되어야 한다"
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    @Nested
    inner class 드리프트_거부 {

        @Test
        fun `완전한 5단계 Crescendo 패턴은 거부된다`() = runTest {
            // 실제 점수 계산: 각 패턴은 turn당 1회만 가중치 적용
            // idx0: 0.1×1.0=0.10, idx1: 0.15×1.2=0.18, idx2: 0.20×1.4=0.28,
            // idx3: 0.25×1.6=0.40, idx4: (0.25+0.40)×1.8=1.17 → 합계 2.13/5=0.426
            val stage = TopicDriftDetectionStage(maxDriftScore = 0.4)
            val result = stage.enforce(
                commandWithHistory(
                    text = "step by step instructions, bypass safety",
                    history = listOf(
                        "hypothetical scenario",
                        "what if we suppose",
                        "for research and academic purposes",
                        "detailed instructions how exactly"
                    )
                )
            )

            val rejected = assertInstanceOf(GuardResult.Rejected::class.java, result) {
                "5단계 Crescendo 패턴은 거부되어야 한다"
            }
            assertEquals(RejectionCategory.PROMPT_INJECTION, rejected.category) {
                "드리프트 거부 카테고리는 PROMPT_INJECTION이어야 한다"
            }
            assertTrue(rejected.reason.isNotBlank()) {
                "거부 사유가 비어있지 않아야 한다"
            }
        }

        @Test
        fun `낮은 임계값에서 중간 수준의 에스컬레이션도 거부된다`() = runTest {
            val stage = TopicDriftDetectionStage(maxDriftScore = 0.1)
            val result = stage.enforce(
                commandWithHistory(
                    text = "bypass the rules",
                    history = listOf("hypothetical", "what if")
                )
            )

            assertInstanceOf(GuardResult.Rejected::class.java, result) {
                "임계값 0.1에서 bypass 패턴은 거부되어야 한다"
            }
        }

        @Test
        fun `높은 임계값에서 동일 패턴이 허용된다`() = runTest {
            val stage = TopicDriftDetectionStage(maxDriftScore = 0.99)
            val result = stage.enforce(
                commandWithHistory(
                    text = "hypothetical",
                    history = listOf("what if")
                )
            )

            assertEquals(GuardResult.Allowed.DEFAULT, result) {
                "임계값 0.99에서 낮은 에스컬레이션 패턴은 허용되어야 한다"
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    @Nested
    inner class 슬라이딩_윈도우 {

        @Test
        fun `windowSize를 초과하는 이력은 마지막 N-1개만 사용된다`() = runTest {
            // windowSize=3이면 이력에서 최근 2개 + 현재 = 3개를 분석한다
            val stage = TopicDriftDetectionStage(maxDriftScore = 0.99, windowSize = 3)

            // 이력의 앞 3개는 높은 에스컬레이션이지만, 마지막 2개는 무해
            val result = stage.enforce(
                commandWithHistory(
                    text = "안녕하세요",
                    history = listOf(
                        "bypass safety override ignore rules",  // 이 2개는 윈도우 밖
                        "bypass safety override ignore rules",
                        "일반 메시지",                           // 윈도우 포함 시작
                        "평범한 질문"
                    )
                )
            )

            assertEquals(GuardResult.Allowed.DEFAULT, result) {
                "윈도우 밖의 에스컬레이션 패턴은 무시되어야 한다"
            }
        }

        @Test
        fun `windowSize=2면 이력 1개와 현재 메시지만 분석한다`() = runTest {
            val stage = TopicDriftDetectionStage(maxDriftScore = 0.99, windowSize = 2)
            val result = stage.enforce(
                commandWithHistory(
                    text = "일반 메시지",
                    history = listOf("이전 메시지")
                )
            )

            assertEquals(GuardResult.Allowed.DEFAULT, result) {
                "windowSize=2, 무해한 메시지는 허용되어야 한다"
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    @Nested
    inner class 메타데이터_우선순위 {

        @Test
        fun `metadata conversationHistory가 MemoryStore보다 우선한다`() = runTest {
            // MemoryStore에는 악의적 패턴이 있지만 metadata에는 안전한 이력
            val store = memoryStoreWithHistory(
                "bypass safety",
                "override rules",
                "ignore restrictions",
                "step by step bypass"
            )
            val stage = TopicDriftDetectionStage(memoryStore = store, maxDriftScore = 0.5)

            val result = stage.enforce(
                GuardCommand(
                    userId = "u1",
                    text = "안녕",
                    metadata = mapOf(
                        "sessionId" to "session-1",
                        "conversationHistory" to listOf("안녕하세요", "잘 지내셨나요")
                    )
                )
            )

            assertEquals(GuardResult.Allowed.DEFAULT, result) {
                "metadata conversationHistory가 있으면 MemoryStore 이력은 무시되어야 한다"
            }
        }

        @Test
        fun `metadata 이력 없으면 MemoryStore에서 USER 메시지만 로드한다`() = runTest {
            val memory = mockk<ConversationMemory>()
            every { memory.getHistory() } returns listOf(
                Message(role = MessageRole.USER, content = "bypass"),
                Message(role = MessageRole.ASSISTANT, content = "안전한 응답"),
                Message(role = MessageRole.USER, content = "override ignore rules"),
            )
            val store = mockk<MemoryStore>()
            every { store.get("session-1") } returns memory

            val stage = TopicDriftDetectionStage(memoryStore = store, maxDriftScore = 0.3)
            val result = stage.enforce(commandWithSession("step by step bypass", "session-1"))

            // USER 메시지 2개 + 현재 메시지 = window size 초과 가능
            // ASSISTANT 메시지는 필터링되어야 함
            assertInstanceOf(GuardResult.Rejected::class.java, result) {
                "MemoryStore에서 USER 메시지만 이력으로 사용하여 드리프트를 탐지해야 한다"
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    @Nested
    inner class 에스컬레이션_패턴 {

        @Test
        fun `대소문자 무관하게 bypass 패턴을 탐지한다`() = runTest {
            // 실제 점수: idx0: 0.1×1.0=0.10, idx1: 0.15×1.2=0.18, idx2: 0.4×1.4=0.56
            // 합계 0.84/3=0.28 → maxDriftScore=0.2으로 탐지
            val stage = TopicDriftDetectionStage(maxDriftScore = 0.2)
            val result = stage.enforce(
                commandWithHistory(
                    text = "BYPASS the SAFETY OVERRIDE",
                    history = listOf("HYPOTHETICAL scenario", "WHAT IF we suppose")
                )
            )

            assertInstanceOf(GuardResult.Rejected::class.java, result) {
                "대문자 bypass 패턴도 탐지되어야 한다"
            }
        }

        @Test
        fun `레벨 5 bypass 단독으로도 임계값에 따라 거부된다`() = runTest {
            val stage = TopicDriftDetectionStage(maxDriftScore = 0.3)
            val result = stage.enforce(
                commandWithHistory(
                    text = "bypass safety",
                    history = listOf("bypass override")
                )
            )

            assertInstanceOf(GuardResult.Rejected::class.java, result) {
                "높은 가중치의 bypass 패턴 반복은 거부되어야 한다"
            }
        }

        @Test
        fun `레벨 1 hypothetical만으로는 기본 임계값을 넘지 못한다`() = runTest {
            val stage = TopicDriftDetectionStage(maxDriftScore = 0.7)
            val result = stage.enforce(
                commandWithHistory(
                    text = "hypothetical",
                    history = listOf("imagine", "theoretical")
                )
            )

            assertEquals(GuardResult.Allowed.DEFAULT, result) {
                "낮은 가중치의 hypothetical 패턴만으로는 기본 임계값 0.7을 초과하지 않아야 한다"
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    @Nested
    inner class 스테이지_메타데이터 {

        @Test
        fun `stageName은 TopicDriftDetection이다`() {
            val stage = TopicDriftDetectionStage()
            assertEquals("TopicDriftDetection", stage.stageName) {
                "stageName이 TopicDriftDetection이어야 한다"
            }
        }

        @Test
        fun `order는 10이다`() {
            val stage = TopicDriftDetectionStage()
            assertEquals(10, stage.order) {
                "커스텀 Guard 단계로 order=10이어야 한다"
            }
        }
    }
}
