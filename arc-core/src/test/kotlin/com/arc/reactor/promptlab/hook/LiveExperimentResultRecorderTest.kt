package com.arc.reactor.promptlab.hook

import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.promptlab.InMemoryLiveExperimentStore
import com.arc.reactor.promptlab.model.LiveExperimentStatus
import com.arc.reactor.promptlab.model.PromptExperiment
import com.arc.reactor.promptlab.model.PromptVariant
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * LiveExperimentResultRecorder 테스트.
 *
 * 라이브 A/B 테스트 결과 기록 훅의 동작을 검증한다.
 */
class LiveExperimentResultRecorderTest {

    private lateinit var store: InMemoryLiveExperimentStore
    private lateinit var recorder: LiveExperimentResultRecorder

    @BeforeEach
    fun setup() {
        store = InMemoryLiveExperimentStore()
        recorder = LiveExperimentResultRecorder(store)
    }

    private fun createContext(
        metadata: Map<String, Any> = emptyMap()
    ): HookContext {
        val mutableMetadata = ConcurrentHashMap<String, Any>()
        mutableMetadata.putAll(metadata)
        return HookContext(
            runId = UUID.randomUUID().toString(),
            userId = "test-user",
            userPrompt = "test prompt",
            startedAt = Instant.now(),
            toolsUsed = CopyOnWriteArrayList(),
            metadata = mutableMetadata
        )
    }

    private fun createResponse(
        success: Boolean = true,
        durationMs: Long = 150
    ): AgentResponse {
        return AgentResponse(
            success = success,
            response = "test response",
            totalDurationMs = durationMs
        )
    }

    private fun setupRunningExperiment(id: String = "exp-1") {
        val experiment = PromptExperiment(
            id = id,
            name = "Test A/B",
            controlPrompt = "control",
            variantPrompt = "variant",
            trafficPercent = 50,
            status = LiveExperimentStatus.DRAFT
        )
        store.save(experiment)
        store.start(id)
    }

    @Nested
    inner class RecordExperimentData {

        @Test
        fun `실험 메타데이터가 있으면 결과를 기록해야 한다`() = runTest {
            setupRunningExperiment()

            val context = createContext(
                mapOf(
                    LiveExperimentResultRecorder.LIVE_EXPERIMENT_ID_KEY to "exp-1",
                    LiveExperimentResultRecorder.LIVE_EXPERIMENT_VARIANT_KEY to "CONTROL"
                )
            )
            val response = createResponse(success = true, durationMs = 200)

            recorder.afterAgentComplete(context, response)

            val results = store.getResults("exp-1")
            results shouldHaveSize 1
            results[0].variant shouldBe PromptVariant.CONTROL
            results[0].success shouldBe true
            results[0].latencyMs shouldBe 200
        }

        @Test
        fun `VARIANT 변형도 올바르게 기록해야 한다`() = runTest {
            setupRunningExperiment()

            val context = createContext(
                mapOf(
                    LiveExperimentResultRecorder.LIVE_EXPERIMENT_ID_KEY to "exp-1",
                    LiveExperimentResultRecorder.LIVE_EXPERIMENT_VARIANT_KEY to "VARIANT"
                )
            )
            val response = createResponse(success = false, durationMs = 500)

            recorder.afterAgentComplete(context, response)

            val results = store.getResults("exp-1")
            results shouldHaveSize 1
            results[0].variant shouldBe PromptVariant.VARIANT
            results[0].success shouldBe false
            results[0].latencyMs shouldBe 500
        }

        @Test
        fun `세션 ID가 있으면 결과에 포함해야 한다`() = runTest {
            setupRunningExperiment()

            val context = createContext(
                mapOf(
                    LiveExperimentResultRecorder.LIVE_EXPERIMENT_ID_KEY to "exp-1",
                    LiveExperimentResultRecorder.LIVE_EXPERIMENT_VARIANT_KEY to "CONTROL",
                    LiveExperimentResultRecorder.SESSION_ID_KEY to "session-123"
                )
            )
            recorder.afterAgentComplete(context, createResponse())

            val results = store.getResults("exp-1")
            results shouldHaveSize 1
            results[0].sessionId shouldBe "session-123"
        }
    }

    @Nested
    inner class SkipConditions {

        @Test
        fun `실험 ID 메타데이터가 없으면 무시해야 한다`() = runTest {
            setupRunningExperiment()

            val context = createContext()
            recorder.afterAgentComplete(context, createResponse())

            store.getResults("exp-1").shouldBeEmpty()
        }

        @Test
        fun `변형 메타데이터가 없으면 무시해야 한다`() = runTest {
            setupRunningExperiment()

            val context = createContext(
                mapOf(
                    LiveExperimentResultRecorder.LIVE_EXPERIMENT_ID_KEY to "exp-1"
                )
            )
            recorder.afterAgentComplete(context, createResponse())

            store.getResults("exp-1").shouldBeEmpty()
        }

        @Test
        fun `잘못된 변형 값이면 무시해야 한다`() = runTest {
            setupRunningExperiment()

            val context = createContext(
                mapOf(
                    LiveExperimentResultRecorder.LIVE_EXPERIMENT_ID_KEY to "exp-1",
                    LiveExperimentResultRecorder.LIVE_EXPERIMENT_VARIANT_KEY to "INVALID"
                )
            )
            recorder.afterAgentComplete(context, createResponse())

            store.getResults("exp-1").shouldBeEmpty()
        }
    }

    @Nested
    inner class HookProperties {

        @Test
        fun `order는 280이어야 한다`() {
            recorder.order shouldBe 280
        }

        @Test
        fun `failOnError는 false이어야 한다`() {
            recorder.failOnError shouldBe false
        }
    }
}
