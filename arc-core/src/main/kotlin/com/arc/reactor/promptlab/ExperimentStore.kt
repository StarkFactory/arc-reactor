package com.arc.reactor.promptlab

import com.arc.reactor.promptlab.model.Experiment
import com.arc.reactor.promptlab.model.ExperimentReport
import com.arc.reactor.promptlab.model.ExperimentStatus
import com.arc.reactor.promptlab.model.Trial
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 실험 저장소 인터페이스
 *
 * 프롬프트 실험실(Prompt Lab)의 실험, 트라이얼, 보고서에 대한 CRUD 작업을 관리한다.
 *
 * @see InMemoryExperimentStore 기본 인메모리 구현
 * @see JdbcExperimentStore JDBC 영속 구현
 */
interface ExperimentStore {

    /** 실험을 저장하거나 갱신한다. */
    fun save(experiment: Experiment): Experiment

    /** ID로 실험을 조회한다. 존재하지 않으면 null 반환. */
    fun get(id: String): Experiment?

    /**
     * 선택적 필터를 적용하여 실험 목록을 조회한다.
     * 모든 필터는 AND 조합. null은 해당 필드 필터 없음을 의미한다.
     */
    fun list(
        status: ExperimentStatus? = null,
        templateId: String? = null
    ): List<Experiment>

    /** ID로 실험을 삭제한다. 멱등성 — 존재하지 않아도 에러 없음. */
    fun delete(id: String)

    /** 실험의 트라이얼을 저장한다. 기존 트라이얼에 추가된다. */
    fun saveTrials(experimentId: String, trials: List<Trial>)

    /** 실험의 모든 트라이얼을 조회한다. */
    fun getTrials(experimentId: String): List<Trial>

    /** 실험 보고서를 저장한다. 기존 보고서를 덮어쓴다. */
    fun saveReport(experimentId: String, report: ExperimentReport)

    /** 실험 보고서를 조회한다. 존재하지 않으면 null 반환. */
    fun getReport(experimentId: String): ExperimentReport?
}

/**
 * 인메모리 실험 저장소
 *
 * [ConcurrentHashMap]을 사용한 스레드 안전 구현.
 * 영속적이지 않음 — 서버 재시작 시 데이터가 소실된다.
 * 용량 초과 시 가장 오래된 완료된 실험을 자동 퇴출한다.
 *
 * WHY: DB 없이도 PromptLab 기본 동작을 보장하기 위한 기본 구현.
 * 완료/실패 상태의 오래된 실험을 자동 퇴출하여 메모리 사용을 제한한다.
 */
class InMemoryExperimentStore(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES
) : ExperimentStore {

    private val experiments = ConcurrentHashMap<String, Experiment>()
    private val trials = ConcurrentHashMap<String, CopyOnWriteArrayList<Trial>>()
    private val reports = ConcurrentHashMap<String, ExperimentReport>()
    private val evictionLock = Any()

    override fun save(experiment: Experiment): Experiment {
        synchronized(evictionLock) {
            experiments[experiment.id] = experiment
            if (experiments.size > maxEntries) {
                evictIfNeeded()
            }
        }
        return experiment
    }

    companion object {
        internal const val DEFAULT_MAX_ENTRIES = 1_000
    }

    override fun get(id: String): Experiment? = experiments[id]

    override fun list(
        status: ExperimentStatus?,
        templateId: String?
    ): List<Experiment> {
        return experiments.values.toList()
            .asSequence()
            .filter { status == null || it.status == status }
            .filter { templateId == null || it.templateId == templateId }
            .sortedByDescending { it.createdAt }
            .toList()
    }

    override fun delete(id: String) {
        experiments.remove(id)
        trials.remove(id)
        reports.remove(id)
    }

    override fun saveTrials(experimentId: String, trials: List<Trial>) {
        this.trials
            .computeIfAbsent(experimentId) { CopyOnWriteArrayList() }
            .addAll(trials)
    }

    override fun getTrials(experimentId: String): List<Trial> {
        return trials[experimentId]?.toList() ?: emptyList()
    }

    override fun saveReport(
        experimentId: String,
        report: ExperimentReport
    ) {
        reports[experimentId] = report
    }

    override fun getReport(experimentId: String): ExperimentReport? {
        return reports[experimentId]
    }

    /** synchronized(evictionLock) 내에서 호출되어야 한다. */
    private fun evictIfNeeded() {
        if (experiments.size <= maxEntries) return
        val completedIds = experiments.values
            .filter { it.status in TERMINAL_STATUSES }
            .sortedBy { it.createdAt }
            .take(experiments.size - maxEntries)
            .map { it.id }
        for (id in completedIds) {
            delete(id)
        }
    }

    private val TERMINAL_STATUSES = setOf(
        ExperimentStatus.COMPLETED,
        ExperimentStatus.FAILED,
        ExperimentStatus.CANCELLED
    )
}
