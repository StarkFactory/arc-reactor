package com.arc.reactor.guard.output

import com.arc.reactor.agent.metrics.EvaluationMetricsCollector
import com.arc.reactor.agent.metrics.MicrometerEvaluationMetricsCollector
import com.arc.reactor.agent.model.AgentCommand
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.coroutines.cancellation.CancellationException

/**
 * OutputGuardPipeline에 대한 테스트.
 *
 * 출력 가드 파이프라인의 동작을 검증합니다.
 */
class OutputGuardPipelineTest {

    private val defaultContext = OutputGuardContext(
        command = AgentCommand(systemPrompt = "Test", userPrompt = "Hello"),
        toolsUsed = emptyList(),
        durationMs = 100
    )

    @Nested
    inner class BasicBehavior {

        @Test
        fun `비어있는 pipeline returns Allowed`() = runTest {
            val pipeline = OutputGuardPipeline(emptyList())
            val result = pipeline.check("some content", defaultContext)
            assertInstanceOf(OutputGuardResult.Allowed::class.java, result) {
                "Empty pipeline should return Allowed"
            }
        }

        @Test
        fun `단일 allowing stage returns Allowed`() = runTest {
            val stage = allowingStage("AllowAll")
            val pipeline = OutputGuardPipeline(listOf(stage))

            val result = pipeline.check("content", defaultContext)
            assertInstanceOf(OutputGuardResult.Allowed::class.java, result) {
                "Single allowing stage should return Allowed"
            }
        }

        @Test
        fun `단일 rejecting stage returns Rejected`() = runTest {
            val stage = rejectingStage("BlockAll", "blocked")
            val pipeline = OutputGuardPipeline(listOf(stage))

            val result = pipeline.check("content", defaultContext)
            val rejected = assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "Single rejecting stage should return Rejected"
            }
            assertEquals("blocked", rejected.reason) { "Rejection reason should match" }
            assertEquals("BlockAll", rejected.stage) { "Stage name should be set" }
        }

        @Test
        fun `단일 modifying stage returns Modified`() = runTest {
            val stage = modifyingStage("Masker", "masked content", "PII found")
            val pipeline = OutputGuardPipeline(listOf(stage))

            val result = pipeline.check("original", defaultContext)
            val modified = assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "Single modifying stage should return Modified"
            }
            assertEquals("masked content", modified.content) { "Modified content should match" }
            assertEquals("PII found", modified.reason) { "Modification reason should match" }
            assertEquals("Masker", modified.stage) { "Stage name should be set" }
        }
    }

    @Nested
    inner class StageOrdering {

        @Test
        fun `stages은(는) execute in ascending order`() = runTest {
            val executionOrder = mutableListOf<String>()
            val stages = listOf(
                trackingStage("Third", 30, executionOrder),
                trackingStage("First", 10, executionOrder),
                trackingStage("Second", 20, executionOrder)
            )
            val pipeline = OutputGuardPipeline(stages)

            pipeline.check("content", defaultContext)
            assertEquals(listOf("First", "Second", "Third"), executionOrder) {
                "Stages should execute in ascending order"
            }
        }

        @Test
        fun `disabled stages은(는) skipped이다`() = runTest {
            val executionOrder = mutableListOf<String>()
            val stages = listOf(
                trackingStage("Active", 10, executionOrder),
                trackingStage("Disabled", 20, executionOrder, enabled = false),
                trackingStage("AlsoActive", 30, executionOrder)
            )
            val pipeline = OutputGuardPipeline(stages)

            pipeline.check("content", defaultContext)
            assertEquals(listOf("Active", "AlsoActive"), executionOrder) {
                "Disabled stages should be skipped"
            }
        }

        @Test
        fun `size은(는) reflects only enabled stages`() {
            val stages = listOf(
                allowingStage("A", order = 10),
                allowingStage("B", order = 20, enabled = false),
                allowingStage("C", order = 30)
            )
            val pipeline = OutputGuardPipeline(stages)
            assertEquals(2, pipeline.size) { "Size should count only enabled stages" }
        }
    }

    @Nested
    inner class PipelineFlow {

        @Test
        fun `rejection은(는) stops pipeline immediately`() = runTest {
            val executionOrder = mutableListOf<String>()
            val stages = listOf(
                trackingStage("First", 10, executionOrder),
                rejectingStage("Blocker", "blocked", order = 20),
                trackingStage("NeverReached", 30, executionOrder)
            )
            val pipeline = OutputGuardPipeline(stages)

            val result = pipeline.check("content", defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "Should be rejected"
            }
            assertEquals(listOf("First"), executionOrder) {
                "Stages after rejection should not execute"
            }
        }

        @Test
        fun `modification은(는) propagates to next stage`() = runTest {
            val receivedContent = mutableListOf<String>()
            val stages = listOf(
                object : OutputGuardStage {
                    override val stageName = "Modifier"
                    override val order = 10
                    override suspend fun check(content: String, context: OutputGuardContext): OutputGuardResult {
                        receivedContent.add(content)
                        return OutputGuardResult.Modified(content = "modified", reason = "test")
                    }
                },
                object : OutputGuardStage {
                    override val stageName = "Checker"
                    override val order = 20
                    override suspend fun check(content: String, context: OutputGuardContext): OutputGuardResult {
                        receivedContent.add(content)
                        return OutputGuardResult.Allowed.DEFAULT
                    }
                }
            )
            val pipeline = OutputGuardPipeline(stages)

            val result = pipeline.check("original", defaultContext)
            val modified = assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "Should return Modified from first stage"
            }
            assertEquals("modified", modified.content) { "Final content should be modified" }
            assertEquals(listOf("original", "modified"), receivedContent) {
                "Second stage should receive modified content from first stage"
            }
        }

        @Test
        fun `다중 modifications chain correctly`() = runTest {
            val stages = listOf(
                modifyingStage("First", "step1", "reason1", order = 10),
                modifyingStage("Second", "step2", "reason2", order = 20)
            )
            val pipeline = OutputGuardPipeline(stages)

            val result = pipeline.check("original", defaultContext)
            val modified = assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "Should return last Modified result"
            }
            assertEquals("step2", modified.content) { "Should contain last modification" }
            assertEquals("Second", modified.stage) { "Stage should be last modifier" }
        }
    }

    @Nested
    inner class FailClose {

        @Test
        fun `exception은(는) causes rejection (fail-close)`() = runTest {
            val stage = object : OutputGuardStage {
                override val stageName = "Crasher"
                override val order = 10
                override suspend fun check(content: String, context: OutputGuardContext): OutputGuardResult {
                    throw RuntimeException("Unexpected error")
                }
            }
            val pipeline = OutputGuardPipeline(listOf(stage))

            val result = pipeline.check("content", defaultContext)
            val rejected = assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "Exception should cause rejection (fail-close)"
            }
            assertEquals(OutputRejectionCategory.SYSTEM_ERROR, rejected.category) {
                "Category should be SYSTEM_ERROR"
            }
            assertEquals("Crasher", rejected.stage) { "Stage name should be set" }
        }

        @Test
        fun `CancellationException은(는) rethrown, not caught이다`() = runTest {
            val stage = object : OutputGuardStage {
                override val stageName = "Canceller"
                override val order = 10
                override suspend fun check(content: String, context: OutputGuardContext): OutputGuardResult {
                    throw CancellationException("Cancelled")
                }
            }
            val pipeline = OutputGuardPipeline(listOf(stage))

            assertThrows(CancellationException::class.java) {
                kotlinx.coroutines.test.runTest {
                    pipeline.check("content", defaultContext)
                }
            }
        }
    }

    // -- 헬퍼 함수 --

    private fun allowingStage(name: String, order: Int = 100, enabled: Boolean = true) = object : OutputGuardStage {
        override val stageName = name
        override val order = order
        override val enabled = enabled
        override suspend fun check(content: String, context: OutputGuardContext) = OutputGuardResult.Allowed.DEFAULT
    }

    private fun rejectingStage(name: String, reason: String, order: Int = 100) = object : OutputGuardStage {
        override val stageName = name
        override val order = order
        override suspend fun check(content: String, context: OutputGuardContext) = OutputGuardResult.Rejected(
            reason = reason,
            category = OutputRejectionCategory.POLICY_VIOLATION
        )
    }

    private fun modifyingStage(name: String, newContent: String, reason: String, order: Int = 100) =
        object : OutputGuardStage {
            override val stageName = name
            override val order = order
            override suspend fun check(content: String, context: OutputGuardContext) =
                OutputGuardResult.Modified(content = newContent, reason = reason)
        }

    private fun trackingStage(
        name: String, order: Int, tracker: MutableList<String>, enabled: Boolean = true
    ) = object : OutputGuardStage {
        override val stageName = name
        override val order = order
        override val enabled = enabled
        override suspend fun check(content: String, context: OutputGuardContext): OutputGuardResult {
            tracker.add(name)
            return OutputGuardResult.Allowed.DEFAULT
        }
    }

    private fun throwingStage(name: String, exception: Throwable, order: Int = 100) =
        object : OutputGuardStage {
            override val stageName = name
            override val order = order
            override suspend fun check(content: String, context: OutputGuardContext): OutputGuardResult {
                throw exception
            }
        }

    // ========================================================================
    // R250: OUTPUT_GUARD stage 자동 기록 테스트
    // ========================================================================

    @Nested
    inner class R250ExecutionErrorRecording {

        private fun newCollector(): Pair<SimpleMeterRegistry, EvaluationMetricsCollector> {
            val registry = SimpleMeterRegistry()
            return registry to MicrometerEvaluationMetricsCollector(registry)
        }

        @Test
        fun `R250 stage 예외가 OUTPUT_GUARD stage로 기록되어야 한다`() = runTest {
            val (registry, collector) = newCollector()
            val stage = throwingStage("PiiMasking", IllegalStateException("regex compile failed"))
            val pipeline = OutputGuardPipeline(
                stages = listOf(stage),
                evaluationMetricsCollector = collector
            )

            val result = pipeline.check("some content", defaultContext)

            // fail-close → Rejected(SYSTEM_ERROR)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "fail-close → Rejected 반환"
            }
            val rejected = result as OutputGuardResult.Rejected
            assertEquals(OutputRejectionCategory.SYSTEM_ERROR, rejected.category)

            val counter = registry.find(MicrometerEvaluationMetricsCollector.METRIC_EXECUTION_ERROR)
                .tag(MicrometerEvaluationMetricsCollector.TAG_STAGE, "output_guard")
                .tag(MicrometerEvaluationMetricsCollector.TAG_EXCEPTION, "IllegalStateException")
                .counter()
            assertNotNull(counter) {
                "Output guard 예외가 OUTPUT_GUARD stage로 기록되어야 한다"
            }
            assertEquals(1.0, counter!!.count())
        }

        @Test
        fun `R250 여러 stage 중 첫 실패만 기록 (이후 stage 실행 안 됨)`() = runTest {
            val (registry, collector) = newCollector()
            val stage1 = throwingStage("Stage1", NullPointerException("boom"), order = 1)
            val stage2 = throwingStage("Stage2", RuntimeException("never reached"), order = 2)
            val pipeline = OutputGuardPipeline(
                stages = listOf(stage1, stage2),
                evaluationMetricsCollector = collector
            )

            pipeline.check("content", defaultContext)

            val firstCounter = registry.find(MicrometerEvaluationMetricsCollector.METRIC_EXECUTION_ERROR)
                .tag(MicrometerEvaluationMetricsCollector.TAG_STAGE, "output_guard")
                .tag(MicrometerEvaluationMetricsCollector.TAG_EXCEPTION, "NullPointerException")
                .counter()
            assertNotNull(firstCounter) { "첫 stage 예외는 기록" }
            assertEquals(1.0, firstCounter!!.count())

            val secondCounter = registry.find(MicrometerEvaluationMetricsCollector.METRIC_EXECUTION_ERROR)
                .tag(MicrometerEvaluationMetricsCollector.TAG_STAGE, "output_guard")
                .tag(MicrometerEvaluationMetricsCollector.TAG_EXCEPTION, "RuntimeException")
                .counter()
            assertNull(secondCounter) {
                "fail-close로 첫 실패 후 즉시 Rejected 반환 → 두 번째 stage 실행 안 됨"
            }
        }

        @Test
        fun `R250 정상 실행은 기록하지 않음`() = runTest {
            val (registry, collector) = newCollector()
            val stage = allowingStage("AllowAll")
            val pipeline = OutputGuardPipeline(
                stages = listOf(stage),
                evaluationMetricsCollector = collector
            )

            pipeline.check("content", defaultContext)

            val meter = registry.find(MicrometerEvaluationMetricsCollector.METRIC_EXECUTION_ERROR)
                .counter()
            assertNull(meter) { "정상 경로는 기록 안 됨" }
        }

        @Test
        fun `R250 Rejected 반환은 기록하지 않음 (예외 아닌 정상 거부)`() = runTest {
            val (registry, collector) = newCollector()
            val stage = rejectingStage("PolicyBlock", "blocked by policy")
            val pipeline = OutputGuardPipeline(
                stages = listOf(stage),
                evaluationMetricsCollector = collector
            )

            val result = pipeline.check("content", defaultContext)

            assertInstanceOf(OutputGuardResult.Rejected::class.java, result)
            val meter = registry.find(MicrometerEvaluationMetricsCollector.METRIC_EXECUTION_ERROR)
                .counter()
            assertNull(meter) {
                "정책에 의한 거부는 예외가 아니므로 execution.error에 기록 안 됨"
            }
        }

        @Test
        fun `R250 CancellationException은 기록하지 않고 재throw`() = runTest {
            val (registry, collector) = newCollector()
            val stage = throwingStage("CancellingStage", CancellationException("cancelled"))
            val pipeline = OutputGuardPipeline(
                stages = listOf(stage),
                evaluationMetricsCollector = collector
            )

            try {
                pipeline.check("content", defaultContext)
                fail("Expected CancellationException")
            } catch (_: CancellationException) {
                // 예상
            }

            val meter = registry.find(MicrometerEvaluationMetricsCollector.METRIC_EXECUTION_ERROR)
                .counter()
            assertNull(meter) {
                "CancellationException은 execution.error에 기록하지 않음"
            }
        }

        @Test
        fun `R250 기본 NoOp collector backward compat 유지`() = runTest {
            val stage = throwingStage("failing", IllegalStateException("boom"))
            // evaluationMetricsCollector 생략 → NoOp 기본값
            val pipeline = OutputGuardPipeline(stages = listOf(stage))

            val result = pipeline.check("content", defaultContext)

            // fail-close 동작은 그대로
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result)
        }
    }
}
