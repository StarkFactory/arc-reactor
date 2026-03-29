package com.arc.reactor.promptlab.hook

import com.arc.reactor.hook.AfterAgentCompleteHook
import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.support.throwIfCancellation
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import mu.KotlinLogging
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * 관찰성(observability)을 위한 캡처된 실험 실행 데이터.
 *
 * @see ExperimentCaptureHook 캡처 훅
 */
data class CapturedExperimentData(
    val runId: String,
    val experimentId: String,
    val versionId: String,
    val response: String?,
    val toolsUsed: List<String>,
    val durationMs: Long,
    val success: Boolean,
    val capturedAt: Instant = Instant.now()
)

/**
 * 실험 캡처 훅
 *
 * 에이전트 커맨드에 `promptlab.experimentId` 메타데이터가 포함된 경우
 * 실험 실행 데이터를 캡처하는 AfterAgentCompleteHook.
 *
 * ## 동작
 * - 순서 270: FeedbackCapture(250)와 RagCapture(260) 이후
 * - Fail-open: 에이전트 응답을 절대 차단하지 않음
 * - TTL: 1시간, 최대 10,000건
 * - 메타데이터에 실험 식별자가 포함된 경우에만 활성화
 *
 * WHY: 실험 실행 중 에이전트 응답 데이터를 캡처하여
 * 실험 결과 분석에 활용한다. Fail-open으로 실험 코드의 오류가
 * 일반 에이전트 동작에 영향을 미치지 않게 한다.
 *
 * @see com.arc.reactor.promptlab.ExperimentOrchestrator 실험 실행 엔진
 */
class ExperimentCaptureHook(
    private val clock: Clock = Clock.systemUTC(),
    ticker: Ticker = Ticker.systemTicker()
) : AfterAgentCompleteHook {

    override val order: Int = 270
    override val failOnError: Boolean = false

    /** runId → 캡처 데이터 캐시 (Caffeine: TTL 1시간, 최대 10,000개) */
    private val cache = Caffeine.newBuilder()
        .maximumSize(MAX_ENTRIES)
        .expireAfterWrite(Duration.ofSeconds(TTL_SECONDS))
        .ticker(ticker)
        .build<String, CapturedExperimentData>()

    override suspend fun afterAgentComplete(
        context: HookContext,
        response: AgentResponse
    ) {
        try {
            val experimentId = context.metadata[EXPERIMENT_ID_KEY]?.toString()
                ?: return
            val versionId = context.metadata[VERSION_ID_KEY]?.toString()
                ?: return

            val data = CapturedExperimentData(
                runId = context.runId,
                experimentId = experimentId,
                versionId = versionId,
                response = response.response,
                toolsUsed = response.toolsUsed,
                durationMs = response.totalDurationMs,
                success = response.success,
                capturedAt = Instant.now(clock)
            )
            cache.put(context.runId, data)
            logger.debug { "Captured experiment data for runId=${context.runId}" }
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn { "Failed to capture experiment data: ${e.javaClass.simpleName}" }
        }
    }

    /** runId로 캐시된 데이터를 조회한다 */
    fun get(runId: String): CapturedExperimentData? = cache.getIfPresent(runId)

    /** 현재 캐시 크기 (테스트용). 정확한 값을 위해 보류 중인 퇴거를 먼저 실행한다. */
    internal fun cacheSize(): Int {
        cache.cleanUp()
        return cache.asMap().size
    }

    companion object {
        const val EXPERIMENT_ID_KEY = "promptlab.experimentId"
        const val VERSION_ID_KEY = "promptlab.versionId"
        const val RUN_ID_KEY = "promptlab.runId"
        /** 캐시 TTL: 1시간 */
        internal const val TTL_SECONDS = 3600L
        /** 최대 캐시 엔트리 수 */
        private const val MAX_ENTRIES = 10_000L
    }
}
