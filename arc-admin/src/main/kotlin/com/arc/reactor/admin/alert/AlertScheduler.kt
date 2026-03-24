package com.arc.reactor.admin.alert

import mu.KotlinLogging
import org.springframework.beans.factory.DisposableBean
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}

/**
 * 알림 규칙을 주기적으로 평가하고 발동된 알림의 알림을 전송하는 스케줄러.
 *
 * 단일 스레드 스케줄러를 사용하여 평가 시 동시성 문제를 방지한다.
 * [DisposableBean]을 구현하여 Spring 컨텍스트 종료 시 executor를 정리한다.
 *
 * @see AlertEvaluator 규칙 평가 로직
 * @see AlertNotificationService 알림 전송 서비스
 */
class AlertScheduler(
    private val evaluator: AlertEvaluator,
    private val notificationService: AlertNotificationService,
    private val alertStore: AlertRuleStore,
    private val intervalSeconds: Long = 60
) : DisposableBean {

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "alert-scheduler").apply { isDaemon = true }
    }

    private val consecutiveFailures = AtomicLong(0)

    /** 스케줄러를 시작한다. 지정 간격으로 평가 사이클을 반복한다. */
    fun start() {
        logger.info { "Alert scheduler started (interval=${intervalSeconds}s)" }
        scheduler.scheduleAtFixedRate(
            { runEvaluation() },
            intervalSeconds,
            intervalSeconds,
            TimeUnit.SECONDS
        )
    }

    /** 스케줄러를 중지하고 executor 종료를 대기한다 (최대 10초). */
    fun stop() {
        scheduler.shutdown()
        try {
            scheduler.awaitTermination(10, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        logger.info { "Alert scheduler stopped" }
    }

    override fun destroy() {
        stop()
    }

    private fun runEvaluation() {
        try {
            val beforeIds = alertStore.findActiveAlerts().map { it.id }.toSet()
            evaluator.evaluateAll()
            val afterAlerts = alertStore.findActiveAlerts()
            val newAlerts = afterAlerts.filter { it.id !in beforeIds }

            consecutiveFailures.set(0)

            if (newAlerts.isNotEmpty()) {
                for (alert in newAlerts) {
                    notificationService.dispatch(alert)
                }
                logger.info { "Alert evaluation: ${newAlerts.size} new alerts fired (total active: ${afterAlerts.size})" }
            }
        } catch (e: Exception) {
            val failures = consecutiveFailures.incrementAndGet()
            if (failures <= 3 || failures % 10 == 0L) {
                logger.warn(e) { "Alert evaluation cycle failed (consecutive failures: $failures)" }
            }
        }
    }
}
