package com.arc.reactor.agent.checkpoint

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Nested
    inner class EdgeCaseSaveTest {

        @Test
        fun `step=0인 체크포인트도 저장하고 조회할 수 있다`() = runTest {
            val store = InMemoryCheckpointStore()

            store.save(checkpoint(runId = "run-zero", step = 0))

            val result = store.findByRunId("run-zero")
            result shouldHaveSize 1
            result[0].step shouldBe 0
        }

        @Test
        fun `messages가 빈 목록인 체크포인트도 저장되어야 한다`() = runTest {
            val store = InMemoryCheckpointStore()

            store.save(checkpoint(runId = "run-empty-msgs", step = 1, messages = emptyList()))

            val result = store.findByRunId("run-empty-msgs")
            result shouldHaveSize 1
            result[0].messages.shouldBeEmpty()
        }

        @Test
        fun `toolCalls가 빈 목록인 체크포인트도 저장되어야 한다`() = runTest {
            val store = InMemoryCheckpointStore()

            store.save(checkpoint(runId = "run-no-tools", step = 1, toolCalls = emptyList()))

            val result = store.findByRunId("run-no-tools")
            result shouldHaveSize 1
            result[0].toolCalls.shouldBeEmpty()
        }

        @Test
        fun `messages와 toolCalls가 모두 빈 체크포인트도 저장되어야 한다`() = runTest {
            val store = InMemoryCheckpointStore()

            store.save(
                checkpoint(
                    runId = "run-all-empty",
                    step = 1,
                    messages = emptyList(),
                    toolCalls = emptyList()
                )
            )

            val result = store.findByRunId("run-all-empty")
            result shouldHaveSize 1
            result[0].messages.shouldBeEmpty()
            result[0].toolCalls.shouldBeEmpty()
        }

        @Test
        fun `step이 같은 체크포인트를 두 번 저장하면 두 개 모두 보관된다`() = runTest {
            val store = InMemoryCheckpointStore()

            store.save(checkpoint(runId = "run-dup", step = 1, tokensUsed = 100))
            store.save(checkpoint(runId = "run-dup", step = 1, tokensUsed = 200))

            val result = store.findByRunId("run-dup")
            result shouldHaveSize 2
            assertEquals(2, result.count { it.step == 1 }) {
                "동일 step 2개가 모두 저장되어야 한다"
            }
        }
    }

    @Nested
    inner class ConcurrentSaveTest {

        @Test
        fun `동시에 여러 runId로 저장해도 각각의 체크포인트가 보존되어야 한다`() = runTest {
            val store = InMemoryCheckpointStore()
            val runCount = 20

            val jobs = (1..runCount).map { runIndex ->
                launch(Dispatchers.Default) {
                    store.save(checkpoint(runId = "concurrent-run-$runIndex", step = runIndex))
                }
            }
            jobs.forEach { it.join() }

            for (runIndex in 1..runCount) {
                val result = store.findByRunId("concurrent-run-$runIndex")
                assertEquals(1, result.size) {
                    "concurrent-run-${runIndex}에 1개의 체크포인트가 있어야 한다"
                }
                assertEquals(runIndex, result[0].step) {
                    "concurrent-run-${runIndex}의 step이 ${runIndex}여야 한다"
                }
            }
        }

        @Test
        fun `동일 runId에 동시 저장해도 최대 개수를 초과하지 않아야 한다`() = runTest {
            val maxPerRun = 5
            val store = InMemoryCheckpointStore(maxCheckpointsPerRun = maxPerRun)
            val totalSaves = 20

            val jobs = (1..totalSaves).map { i ->
                launch(Dispatchers.Default) {
                    store.save(checkpoint(runId = "shared-run", step = i))
                }
            }
            jobs.forEach { it.join() }

            val result = store.findByRunId("shared-run")
            assertTrue(result.size <= maxPerRun) {
                "동시 저장 후 체크포인트 수는 최대 $maxPerRun 이하여야 한다, 실제: ${result.size}"
            }
        }
    }
}
