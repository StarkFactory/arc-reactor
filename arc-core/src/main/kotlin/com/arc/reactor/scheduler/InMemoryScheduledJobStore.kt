package com.arc.reactor.scheduler

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory fallback implementation for scheduled job storage.
 */
class InMemoryScheduledJobStore : ScheduledJobStore {

    private val jobs = ConcurrentHashMap<String, ScheduledJob>()

    override fun list(): List<ScheduledJob> = jobs.values.toList().sortedBy { it.createdAt }

    override fun findById(id: String): ScheduledJob? = jobs[id]

    override fun findByName(name: String): ScheduledJob? = jobs.values.find { it.name == name }

    override fun save(job: ScheduledJob): ScheduledJob {
        val id = job.id.ifBlank { UUID.randomUUID().toString() }
        val now = Instant.now()
        val saved = job.copy(id = id, createdAt = now, updatedAt = now)
        jobs[saved.id] = saved
        return saved
    }

    override fun update(id: String, job: ScheduledJob): ScheduledJob? {
        val existing = jobs[id] ?: return null
        val updated = existing.copy(
            name = job.name,
            description = job.description,
            cronExpression = job.cronExpression,
            timezone = job.timezone,
            jobType = job.jobType,
            mcpServerName = job.mcpServerName,
            toolName = job.toolName,
            toolArguments = job.toolArguments,
            agentPrompt = job.agentPrompt,
            personaId = job.personaId,
            agentSystemPrompt = job.agentSystemPrompt,
            agentModel = job.agentModel,
            agentMaxToolCalls = job.agentMaxToolCalls,
            slackChannelId = job.slackChannelId,
            teamsWebhookUrl = job.teamsWebhookUrl,
            retryOnFailure = job.retryOnFailure,
            maxRetryCount = job.maxRetryCount,
            executionTimeoutMs = job.executionTimeoutMs,
            enabled = job.enabled,
            updatedAt = Instant.now()
        )
        jobs[id] = updated
        return updated
    }

    override fun delete(id: String) {
        jobs.remove(id)
    }

    override fun updateExecutionResult(id: String, status: JobExecutionStatus, result: String?) {
        val existing = jobs[id] ?: return
        jobs[id] = existing.copy(
            lastRunAt = Instant.now(),
            lastStatus = status,
            lastResult = result?.take(5000),
            updatedAt = Instant.now()
        )
    }
}
