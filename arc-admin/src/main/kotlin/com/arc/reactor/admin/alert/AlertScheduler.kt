package com.arc.reactor.admin.alert

import mu.KotlinLogging
import org.springframework.beans.factory.DisposableBean
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}

/**
 * Periodically evaluates alert rules and dispatches notifications for fired alerts.
 * Uses a single-threaded scheduler to avoid concurrency issues in evaluation.
 *
 * Implements DisposableBean to ensure executor shutdown on Spring context close.
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

    fun start() {
        logger.info { "Alert scheduler started (interval=${intervalSeconds}s)" }
        scheduler.scheduleAtFixedRate(
            { runEvaluation() },
            intervalSeconds,
            intervalSeconds,
            TimeUnit.SECONDS
        )
    }

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
            val beforeCount = alertStore.findActiveAlerts().size
            evaluator.evaluateAll()
            val afterCount = alertStore.findActiveAlerts().size
            val newAlerts = afterCount - beforeCount

            consecutiveFailures.set(0)

            if (newAlerts > 0) {
                val active = alertStore.findActiveAlerts()
                val newest = active.sortedByDescending { it.firedAt }.take(newAlerts)
                for (alert in newest) {
                    notificationService.dispatch(alert)
                }
                logger.info { "Alert evaluation: $newAlerts new alerts fired (total active: $afterCount)" }
            }
        } catch (e: Exception) {
            val failures = consecutiveFailures.incrementAndGet()
            if (failures <= 3 || failures % 10 == 0L) {
                logger.warn(e) { "Alert evaluation cycle failed (consecutive failures: $failures)" }
            }
        }
    }
}
