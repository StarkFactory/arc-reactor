package com.arc.reactor.agent.checkpoint

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * [InMemoryCheckpointStore] 단위 테스트.
 */
class InMemoryCheckpointStoreTest {

    private fun checkpoint(
        runId: String = "run-1",
        step: Int = 1,
        messages: List<String> = listOf("msg"),
        toolCalls: List<String> = listOf("tool"),
        tokensUsed: Int = 100
    ) = ExecutionCheckpoint(
        runId = runId,
        step = step,
        messages = messages,
        toolCalls = toolCalls,
        tokensUsed = tokensUsed,
        createdAt = Instant.now()
    )

    @Nested
    inner class Save {

        @Test
        fun `체크포인트를 저장하고 조회할 수 있다`() = runTest {
            val store = InMemoryCheckpointStore()

            store.save(checkpoint(runId = "run-1", step = 1))

            val result = store.findByRunId("run-1")
            result shouldHaveSize 1
            result[0].step shouldBe 1
        }

        @Test
        fun `여러 체크포인트를 저장할 수 있다`() = runTest {
            val store = InMemoryCheckpointStore()

            store.save(checkpoint(runId = "run-1", step = 1))
            store.save(checkpoint(runId = "run-1", step = 2))
            store.save(checkpoint(runId = "run-1", step = 3))

            val result = store.findByRunId("run-1")
            result shouldHaveSize 3
        }

        @Test
        fun `최대 체크포인트 수를 초과하면 가장 오래된 항목이 제거된다`() = runTest {
            val store = InMemoryCheckpointStore(maxCheckpointsPerRun = 3)

            store.save(checkpoint(runId = "run-1", step = 1))
            store.save(checkpoint(runId = "run-1", step = 2))
            store.save(checkpoint(runId = "run-1", step = 3))
            store.save(checkpoint(runId = "run-1", step = 4))

            val result = store.findByRunId("run-1")
            result shouldHaveSize 3
            result[0].step shouldBe 2
            result[2].step shouldBe 4
        }
    }

    @Nested
    inner class FindByRunId {

        @Test
        fun `존재하지 않는 runId는 빈 목록을 반환한다`() = runTest {
            val store = InMemoryCheckpointStore()

            val result = store.findByRunId("nonexistent")

            result.shouldBeEmpty()
        }

        @Test
        fun `결과는 step 오름차순으로 정렬된다`() = runTest {
            val store = InMemoryCheckpointStore()

            store.save(checkpoint(runId = "run-1", step = 3))
            store.save(checkpoint(runId = "run-1", step = 1))
            store.save(checkpoint(runId = "run-1", step = 2))

            val result = store.findByRunId("run-1")
            result.map { it.step } shouldBe listOf(1, 2, 3)
        }

        @Test
        fun `다른 runId의 체크포인트는 포함되지 않는다`() = runTest {
            val store = InMemoryCheckpointStore()

            store.save(checkpoint(runId = "run-1", step = 1))
            store.save(checkpoint(runId = "run-2", step = 1))

            val result = store.findByRunId("run-1")
            result shouldHaveSize 1
            result[0].runId shouldBe "run-1"
        }
    }

    @Nested
    inner class DeleteByRunId {

        @Test
        fun `체크포인트를 삭제할 수 있다`() = runTest {
            val store = InMemoryCheckpointStore()
            store.save(checkpoint(runId = "run-1", step = 1))
            store.save(checkpoint(runId = "run-1", step = 2))

            store.deleteByRunId("run-1")

            val result = store.findByRunId("run-1")
            result.shouldBeEmpty()
        }

        @Test
        fun `존재하지 않는 runId를 삭제해도 예외가 발생하지 않는다`() = runTest {
            val store = InMemoryCheckpointStore()

            store.deleteByRunId("nonexistent")

            // 예외 없이 정상 완료
        }

        @Test
        fun `특정 runId만 삭제되고 다른 runId는 유지된다`() = runTest {
            val store = InMemoryCheckpointStore()
            store.save(checkpoint(runId = "run-1", step = 1))
            store.save(checkpoint(runId = "run-2", step = 1))

            store.deleteByRunId("run-1")

            store.findByRunId("run-1").shouldBeEmpty()
            store.findByRunId("run-2") shouldHaveSize 1
        }
    }

    @Nested
    inner class ExecutionCheckpointData {

        @Test
        fun `데이터 클래스 필드가 올바르게 설정된다`() {
            val now = Instant.now()
            val cp = ExecutionCheckpoint(
                runId = "run-1",
                step = 3,
                messages = listOf("user: hello", "assistant: hi"),
                toolCalls = listOf("searchTool", "calcTool"),
                tokensUsed = 500,
                createdAt = now
            )

            cp.runId shouldBe "run-1"
            cp.step shouldBe 3
            cp.messages shouldBe listOf("user: hello", "assistant: hi")
            cp.toolCalls shouldBe listOf("searchTool", "calcTool")
            cp.tokensUsed shouldBe 500
            cp.createdAt shouldBe now
        }
    }
}
