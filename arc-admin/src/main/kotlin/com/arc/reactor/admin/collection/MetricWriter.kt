package com.arc.reactor.admin.collection

import com.arc.reactor.admin.model.MetricEvent
import com.arc.reactor.admin.model.TokenUsageEvent
import com.arc.reactor.admin.pricing.CostCalculator
import mu.KotlinLogging
import org.springframework.beans.factory.DisposableBean
import java.math.BigDecimal
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

/**
 * Drains MetricRingBuffer and writes batches to MetricEventStore.
 *
 * Uses a scheduled executor with configurable flush interval and batch size.
 * Writer threads operate independently - the ring buffer is lock-free.
 *
 * Implements DisposableBean to ensure executor shutdown on Spring context close.
 */
class MetricWriter(
    private val ringBuffer: MetricRingBuffer,
    private val store: MetricEventStore,
    private val costCalculator: CostCalculator,
    private val batchSize: Int = 1000,
    private val flushIntervalMs: Long = 1000,
    private val writerThreads: Int = 1,
    private val healthMonitor: PipelineHealthMonitor
) : DisposableBean {

    private val running = AtomicBoolean(false)
    private var executor: ScheduledExecutorService? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return

        val exec = Executors.newScheduledThreadPool(writerThreads) { r ->
            Thread(r, "metric-writer").apply { isDaemon = true }
        }
        executor = exec

        for (i in 0 until writerThreads) {
            exec.scheduleWithFixedDelay(
                { flush() },
                flushIntervalMs,
                flushIntervalMs,
                TimeUnit.MILLISECONDS
            )
        }

        logger.info { "MetricWriter started: threads=$writerThreads, batch=$batchSize, interval=${flushIntervalMs}ms" }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        executor?.shutdown()
        try {
            executor?.awaitTermination(5, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        // Final flush to persist any remaining buffered events
        flush()
        logger.info { "MetricWriter stopped" }
    }

    override fun destroy() {
        stop()
    }

    /**
     * Enrich TokenUsageEvents with estimated cost from pricing table.
     * Runs in writer thread (off hot path) to avoid blocking agent execution.
     */
    private fun enrichCosts(events: List<MetricEvent>): List<MetricEvent> {
        return events.map { event ->
            if (event is TokenUsageEvent && event.estimatedCostUsd == BigDecimal.ZERO) {
                try {
                    val cost = costCalculator.calculate(
                        provider = event.provider,
                        model = event.model,
                        time = event.time,
                        promptTokens = event.promptTokens,
                        cachedTokens = event.promptCachedTokens,
                        completionTokens = event.completionTokens,
                        reasoningTokens = event.reasoningTokens
                    )
                    if (cost > BigDecimal.ZERO) event.copy(estimatedCostUsd = cost) else event
                } catch (e: Exception) {
                    logger.debug { "Cost calculation failed for ${event.provider}:${event.model}: ${e.message}" }
                    event
                }
            } else {
                event
            }
        }
    }

    private fun flush() {
        try {
            val events = ringBuffer.drain(batchSize)
            if (events.isEmpty()) return

            healthMonitor.updateBufferUsage(ringBuffer.usagePercent())

            // Enrich token usage events with estimated cost (off hot path)
            val enriched = enrichCosts(events)

            val startMs = System.currentTimeMillis()
            store.batchInsert(enriched)
            val elapsed = System.currentTimeMillis() - startMs

            healthMonitor.recordWrite(events.size, elapsed)

            if (elapsed > 500) {
                logger.warn { "Slow metric write: ${events.size} events in ${elapsed}ms" }
            }
        } catch (e: Exception) {
            healthMonitor.recordWriteError()
            logger.error(e) { "Failed to write metric batch" }
        }
    }
}
