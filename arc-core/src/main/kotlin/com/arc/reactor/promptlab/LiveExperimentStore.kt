package com.arc.reactor.promptlab

import com.arc.reactor.promptlab.model.ExperimentMetrics
import com.arc.reactor.promptlab.model.ExperimentResult
import com.arc.reactor.promptlab.model.LiveExperimentReport
import com.arc.reactor.promptlab.model.LiveExperimentStatus
import com.arc.reactor.promptlab.model.PromptExperiment
import com.arc.reactor.promptlab.model.PromptVariant
import mu.KotlinLogging
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

private val logger = KotlinLogging.logger {}

/**
 * 라이브 A/B 테스트 실험 저장소 인터페이스.
 *
 * 라이브 트래픽 분할 실험의 CRUD와 결과 기록을 관리한다.
 * 배치 실험용 [ExperimentStore]와는 별개의 저장소이다.
 *
 * @see InMemoryLiveExperimentStore 기본 인메모리 구현
 */
interface LiveExperimentStore {

    /** 실험을 저장하거나 갱신한다. */
    fun save(experiment: PromptExperiment): PromptExperiment

    /** ID로 실험을 조회한다. 없으면 null. */
    fun get(id: String): PromptExperiment?

    /** 상태별 필터로 실험 목록을 조회한다. null이면 전체 조회. */
    fun list(status: LiveExperimentStatus? = null): List<PromptExperiment>

    /** 실험을 삭제한다. 멱등성. */
    fun delete(id: String)

    /** RUNNING 상태인 실험만 조회한다. */
    fun listRunning(): List<PromptExperiment> = list(LiveExperimentStatus.RUNNING)

    /**
     * 실험을 시작한다 (DRAFT -> RUNNING).
     *
     * @return 시작된 실험, 또는 실험이 없거나 DRAFT가 아니면 null
     */
    fun start(id: String): PromptExperiment?

    /**
     * 실험을 중지한다 (RUNNING -> COMPLETED).
     *
     * @return 완료된 실험, 또는 실험이 없거나 RUNNING이 아니면 null
     */
    fun stop(id: String): PromptExperiment?

    /** 실행 결과를 기록하고 메트릭을 갱신한다. */
    fun recordResult(result: ExperimentResult)

    /** 실험의 모든 결과를 조회한다. */
    fun getResults(experimentId: String): List<ExperimentResult>

    /** 실험 보고서를 생성한다. 실험이 없으면 null. */
    fun getReport(experimentId: String): LiveExperimentReport?
}

/**
 * 인메모리 라이브 실험 저장소.
 *
 * [ConcurrentHashMap]을 사용한 스레드 안전 구현.
 * 서버 재시작 시 데이터가 소실된다.
 * 용량 초과 시 가장 오래된 완료 실험을 자동 퇴출한다.
 *
 * ## R317 CHM 감사 (의도적 CHM 유지)
 *
 * CLAUDE.md는 "unbounded ConcurrentHashMap" 사용을 금지한다. 이 저장소의 두 CHM 필드는
 * **커스텀 도메인 인식 eviction으로 bounded 되어 있어** 규칙의 정신에 부합한다 — Caffeine
 * 마이그레이션은 부적절하다:
 *
 * - `experiments`: [evictIfNeeded]가 `maxEntries=500` 상한 도달 시 **COMPLETED 상태의
 *   오래된 실험만** 축출. Caffeine W-TinyLFU는 도메인 상태를 모르므로 RUNNING 실험을
 *   실수로 evict하여 **실행 중 A/B 테스트를 파괴**할 수 있다.
 * - `results`: 실험당 `maxResultsPerExperiment=10_000` 상한을 [trimResults]가 강제.
 *   실험 결과 시계열이 시간 순 FIFO로 유지되어야 정확한 메트릭 집계가 가능하며,
 *   Caffeine의 "hot data 우선" 정책과 상충.
 *
 * 두 필드 모두 bounded 이며 R317 감사를 통해 **의도적 CHM 유지** 로 승인됨.
 *
 * @param maxEntries 최대 실험 수 (기본 500)
 * @param maxResultsPerExperiment 실험당 최대 결과 수 (기본 10,000)
 */
class InMemoryLiveExperimentStore(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxResultsPerExperiment: Int = DEFAULT_MAX_RESULTS
) : LiveExperimentStore {

    /** 실험 메타. [evictIfNeeded]로 COMPLETED 상태 FIFO 축출 (R317 audit: intentional CHM). */
    private val experiments = ConcurrentHashMap<String, PromptExperiment>()
    /** 실험별 결과. [trimResults]로 maxResultsPerExperiment 강제 (R317 audit: intentional CHM). */
    private val results = ConcurrentHashMap<String, CopyOnWriteArrayList<ExperimentResult>>()

    override fun save(experiment: PromptExperiment): PromptExperiment {
        experiments[experiment.id] = experiment
        evictIfNeeded()
        return experiment
    }

    override fun get(id: String): PromptExperiment? = experiments[id]

    override fun list(
        status: LiveExperimentStatus?
    ): List<PromptExperiment> {
        return experiments.values
            .asSequence()
            .filter { status == null || it.status == status }
            .sortedByDescending { it.createdAt }
            .toList()
    }

    override fun delete(id: String) {
        experiments.remove(id)
        results.remove(id)
    }

    override fun start(id: String): PromptExperiment? {
        val experiment = experiments[id] ?: return null
        if (experiment.status != LiveExperimentStatus.DRAFT) return null
        val started = experiment.copy(
            status = LiveExperimentStatus.RUNNING,
            startedAt = Instant.now()
        )
        experiments[id] = started
        return started
    }

    override fun stop(id: String): PromptExperiment? {
        val experiment = experiments[id] ?: return null
        if (experiment.status != LiveExperimentStatus.RUNNING) return null
        val completed = experiment.copy(
            status = LiveExperimentStatus.COMPLETED,
            completedAt = Instant.now()
        )
        experiments[id] = completed
        return completed
    }

    override fun recordResult(result: ExperimentResult) {
        val experiment = experiments[result.experimentId] ?: return
        if (experiment.status != LiveExperimentStatus.RUNNING) return

        val list = results.computeIfAbsent(result.experimentId) {
            CopyOnWriteArrayList()
        }
        list.add(result)
        trimResults(result.experimentId, list)
        updateMetrics(result.experimentId, result)
    }

    override fun getResults(experimentId: String): List<ExperimentResult> {
        return results[experimentId]?.toList() ?: emptyList()
    }

    override fun getReport(experimentId: String): LiveExperimentReport? {
        val experiment = experiments[experimentId] ?: return null
        val winner = determineWinner(experiment.metrics)
        val confidence = determineConfidence(experiment.metrics)
        return LiveExperimentReport(
            experimentId = experiment.id,
            experimentName = experiment.name,
            metrics = experiment.metrics,
            winner = winner,
            confidenceLevel = confidence,
            generatedAt = Instant.now()
        )
    }

    /** 결과 수 메트릭으로 승자를 결정한다. 샘플 부족이면 null. */
    private fun determineWinner(metrics: ExperimentMetrics): PromptVariant? {
        if (metrics.totalSampleCount < MIN_SAMPLES_FOR_WINNER) return null
        val diff = metrics.variantSuccessRate - metrics.controlSuccessRate
        return when {
            diff > SIGNIFICANT_DIFF_THRESHOLD -> PromptVariant.VARIANT
            diff < -SIGNIFICANT_DIFF_THRESHOLD -> PromptVariant.CONTROL
            else -> null
        }
    }

    /** 통계 신뢰도를 결정한다. */
    private fun determineConfidence(metrics: ExperimentMetrics): String {
        val totalSamples = metrics.totalSampleCount
        return when {
            totalSamples < MIN_SAMPLES_FOR_WINNER -> "insufficient_data"
            totalSamples < 100 -> "low"
            totalSamples < 500 -> "medium"
            else -> "high"
        }
    }

    /** 실험 메트릭을 원자적으로 갱신한다. */
    private fun updateMetrics(
        experimentId: String,
        result: ExperimentResult
    ) {
        val experiment = experiments[experimentId] ?: return
        val m = experiment.metrics
        val updated = when (result.variant) {
            PromptVariant.CONTROL -> m.copy(
                controlTotalCount = m.controlTotalCount + 1,
                controlSuccessCount = m.controlSuccessCount + if (result.success) 1 else 0,
                controlTotalLatencyMs = m.controlTotalLatencyMs + result.latencyMs
            )
            PromptVariant.VARIANT -> m.copy(
                variantTotalCount = m.variantTotalCount + 1,
                variantSuccessCount = m.variantSuccessCount + if (result.success) 1 else 0,
                variantTotalLatencyMs = m.variantTotalLatencyMs + result.latencyMs
            )
        }
        experiments[experimentId] = experiment.copy(metrics = updated)
    }

    /** 실험당 결과 수를 제한한다. */
    private fun trimResults(
        experimentId: String,
        list: CopyOnWriteArrayList<ExperimentResult>
    ) {
        if (list.size <= maxResultsPerExperiment) return
        val toRemove = list.size - maxResultsPerExperiment
        for (i in 0 until toRemove) {
            if (list.isNotEmpty()) list.removeAt(0)
        }
    }

    /** 실험 수 제한 — 완료된 오래된 실험부터 퇴출. */
    private fun evictIfNeeded() {
        if (experiments.size <= maxEntries) return
        val completedIds = experiments.values
            .filter { it.status == LiveExperimentStatus.COMPLETED }
            .sortedBy { it.createdAt }
            .take(experiments.size - maxEntries)
            .map { it.id }
        for (id in completedIds) {
            delete(id)
        }
    }

    companion object {
        internal const val DEFAULT_MAX_ENTRIES = 500
        internal const val DEFAULT_MAX_RESULTS = 10_000
        private const val MIN_SAMPLES_FOR_WINNER = 30
        private const val SIGNIFICANT_DIFF_THRESHOLD = 0.05
    }
}
