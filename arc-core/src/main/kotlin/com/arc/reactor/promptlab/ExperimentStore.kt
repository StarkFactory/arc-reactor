package com.arc.reactor.promptlab

import com.arc.reactor.promptlab.model.Experiment
import com.arc.reactor.promptlab.model.ExperimentReport
import com.arc.reactor.promptlab.model.ExperimentStatus
import com.arc.reactor.promptlab.model.Trial
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Experiment Store Interface
 *
 * Manages CRUD operations for prompt lab experiments, trials, and reports.
 *
 * @see InMemoryExperimentStore for default implementation
 */
interface ExperimentStore {

    /** Save or update an experiment. */
    fun save(experiment: Experiment): Experiment

    /** Get an experiment by ID. Returns null if not found. */
    fun get(id: String): Experiment?

    /**
     * List experiments with optional filters.
     * All filters are AND-combined. Null means "no filter on this field".
     */
    fun list(
        status: ExperimentStatus? = null,
        templateId: String? = null
    ): List<Experiment>

    /** Delete an experiment by ID. Idempotent — no error if not found. */
    fun delete(id: String)

    /** Save trials for an experiment. Appends to existing trials. */
    fun saveTrials(experimentId: String, trials: List<Trial>)

    /** Get all trials for an experiment. */
    fun getTrials(experimentId: String): List<Trial>

    /** Save a report for an experiment. Overwrites any existing report. */
    fun saveReport(experimentId: String, report: ExperimentReport)

    /** Get the report for an experiment. Returns null if not found. */
    fun getReport(experimentId: String): ExperimentReport?
}

/**
 * In-Memory Experiment Store
 *
 * Thread-safe implementation using [ConcurrentHashMap].
 * Not persistent — data is lost on server restart.
 * Automatically evicts oldest completed experiments when capacity is exceeded.
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

    /** Must be called within synchronized(evictionLock). */
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
