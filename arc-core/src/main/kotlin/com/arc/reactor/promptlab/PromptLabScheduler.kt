package com.arc.reactor.promptlab

import com.arc.reactor.prompt.PromptTemplateStore
import com.arc.reactor.support.throwIfCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

/**
 * 자동 프롬프트 최적화를 위한 크론 기반 스케줄러.
 *
 * 설정된 템플릿 ID에 대해 주기적으로 부정 피드백을 분석하고
 * 자동 최적화 실험을 실행한다.
 *
 * WHY: PromptLab의 자동 최적화 파이프라인을 크론으로 정기 실행하여
 * 수동 개입 없이 프롬프트를 지속적으로 개선한다.
 *
 * @see ExperimentOrchestrator 실험 실행 엔진
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
            logger.info { "Prompt Lab 최적화 이미 실행 중, 건너뜀" }
            return
        }

        try {
            val since = lastRunTime.get()
            val templateIds = resolveTemplateIds()
            logger.info { "예약 최적화 시작: templates=${templateIds.size}, since=$since" }

            for (templateId in templateIds) {
                runSingleTemplate(templateId, since)
            }

            lastRunTime.set(Instant.now())
            logger.info { "예약 최적화 완료" }
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
                    "자동 최적화 완료: template=$templateId, " +
                        "status=${result.status}"
                }
            } else {
                logger.debug {
                    "최적화 불필요: template=$templateId"
                }
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.error(e) {
                "자동 최적화 실패: template=$templateId"
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
