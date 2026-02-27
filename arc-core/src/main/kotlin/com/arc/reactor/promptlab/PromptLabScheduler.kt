package com.arc.reactor.promptlab

import com.arc.reactor.prompt.PromptTemplateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

/**
 * Cron-based scheduler for automatic prompt optimization.
 *
 * Periodically analyzes negative feedback and runs auto-optimization
 * experiments for configured template IDs.
 */
class PromptLabScheduler(
    private val orchestrator: ExperimentOrchestrator,
    private val promptTemplateStore: PromptTemplateStore,
    private val properties: PromptLabProperties
) {
    private val running = AtomicBoolean(false)
    private val lastRunTime = AtomicReference<Instant>(null)

    @Scheduled(cron = "\${arc.reactor.prompt-lab.schedule.cron:0 0 2 * * *}")
    fun runScheduled() {
        if (!running.compareAndSet(false, true)) {
            logger.info { "Prompt Lab optimization already running, skipping" }
            return
        }

        try {
            val since = lastRunTime.get()
            val templateIds = resolveTemplateIds()
            logger.info { "Scheduled optimization: templates=${templateIds.size}, since=$since" }

            for (templateId in templateIds) {
                runSingleTemplate(templateId, since)
            }

            lastRunTime.set(Instant.now())
            logger.info { "Scheduled optimization completed" }
        } finally {
            running.set(false)
        }
    }

    private fun runSingleTemplate(
        templateId: String,
        since: Instant?
    ) {
        try {
            val result = runBlocking(Dispatchers.IO) {
                orchestrator.runAutoPipeline(templateId, since)
            }
            if (result != null) {
                logger.info {
                    "Auto-optimization for template=$templateId: " +
                        "status=${result.status}"
                }
            } else {
                logger.debug {
                    "No optimization needed for template=$templateId"
                }
            }
        } catch (e: Exception) {
            logger.error(e) {
                "Auto-optimization failed for template=$templateId"
            }
        }
    }

    private fun resolveTemplateIds(): List<String> {
        val configured = properties.schedule.templateIds
        if (configured.isNotEmpty()) return configured
        return promptTemplateStore.listTemplates().map { it.id }
    }

    internal fun isRunning(): Boolean = running.get()
    internal fun lastRunTime(): Instant? = lastRunTime.get()
}
