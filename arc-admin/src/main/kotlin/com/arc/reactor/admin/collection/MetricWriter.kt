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
import java.util.concurrent.locks.ReentrantLock

private val logger = KotlinLogging.logger {}

/**
 * [MetricRingBuffer]에서 drain하여 [MetricEventStore]에 배치 기록하는 writer.
 *
 * 설정 가능한 flush 주기와 배치 크기를 가진 ScheduledExecutor를 사용한다.
 * 링 버퍼가 lock-free이므로 writer 스레드는 독립적으로 동작한다.
 *
 * [DisposableBean]을 구현하여 Spring 컨텍스트 종료 시 executor를 정리한다.
 *
 * @see MetricRingBuffer 이벤트가 버퍼링되는 링 버퍼
 * @see MetricEventStore 최종 저장소
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

    // drain() 접근을 직렬화 — 링 버퍼는 단일 Consumer 전용
    private val flushLock = ReentrantLock()

    fun start() {
        if (!running.compareAndSet(false, true)) return

        if (writerThreads > 1) {
            logger.warn {
                "MetricWriter writerThreads=$writerThreads but drain() is single-consumer only. " +
                    "Extra threads will be idle most of the time — consider using writerThreads=1."
            }
        }

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

        // 잔여 버퍼 이벤트를 최종 flush — executor 스레드가 lock을 쥐고 있을 수 있으므로 blocking lock 사용
        flushLock.lock()
        try {
            val events = ringBuffer.drain(batchSize)
            if (events.isNotEmpty()) {
                try {
                    val enriched = enrichCosts(events)
                    store.batchInsert(enriched)
                    healthMonitor.recordWrite(events.size, 0)
                } catch (e: Exception) {
                    healthMonitor.recordWriteError()
                    logger.error(e) { "Final flush failed: ${events.size} events lost" }
                }
            }
        } finally {
            flushLock.unlock()
        }
        logger.info { "MetricWriter stopped" }
    }

    override fun destroy() {
        stop()
    }

    /** TokenUsageEvent에 가격 테이블 기반 추정 비용을 보강한다. writer 스레드에서 실행 (hot path 밖). */
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
        if (!flushLock.tryLock()) return // Another thread is already flushing
        try {
            val events = ringBuffer.drain(batchSize)
            if (events.isEmpty()) return

            healthMonitor.updateBufferUsage(ringBuffer.usagePercent())

            // ── 단계: 토큰 사용량 이벤트에 추정 비용 보강 (hot path 밖) ──
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
        } finally {
            flushLock.unlock()
        }
    }
}
