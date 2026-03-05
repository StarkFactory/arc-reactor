package com.arc.reactor.admin.collection

import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks pipeline health meta-metrics.
 */
class PipelineHealthMonitor {
    val writeLatencyMs = AtomicLong(0)
    val writeErrorsTotal = AtomicLong(0)
    val writtenTotal = AtomicLong(0)
    val droppedTotal = AtomicLong(0)
    val cacheExactHitsTotal = AtomicLong(0)
    val cacheSemanticHitsTotal = AtomicLong(0)
    val cacheMissesTotal = AtomicLong(0)

    @Volatile
    var bufferUsagePercent: Double = 0.0

    fun recordWrite(count: Int, latencyMs: Long) {
        writtenTotal.addAndGet(count.toLong())
        writeLatencyMs.set(latencyMs)
    }

    fun recordWriteError() {
        writeErrorsTotal.incrementAndGet()
    }

    fun recordDrop(count: Long) {
        droppedTotal.addAndGet(count)
    }

    fun updateBufferUsage(percent: Double) {
        bufferUsagePercent = percent
    }

    fun recordExactCacheHit() {
        cacheExactHitsTotal.incrementAndGet()
    }

    fun recordSemanticCacheHit() {
        cacheSemanticHitsTotal.incrementAndGet()
    }

    fun recordCacheMiss() {
        cacheMissesTotal.incrementAndGet()
    }

    fun snapshot(): PipelineHealthSnapshot = PipelineHealthSnapshot(
        bufferUsagePercent = bufferUsagePercent,
        droppedTotal = droppedTotal.get(),
        writeLatencyMs = writeLatencyMs.get(),
        writeErrorsTotal = writeErrorsTotal.get(),
        writtenTotal = writtenTotal.get(),
        cacheExactHitsTotal = cacheExactHitsTotal.get(),
        cacheSemanticHitsTotal = cacheSemanticHitsTotal.get(),
        cacheMissesTotal = cacheMissesTotal.get()
    )
}

data class PipelineHealthSnapshot(
    val bufferUsagePercent: Double = 0.0,
    val droppedTotal: Long = 0,
    val writeLatencyMs: Long = 0,
    val writeErrorsTotal: Long = 0,
    val writtenTotal: Long = 0,
    val cacheExactHitsTotal: Long = 0,
    val cacheSemanticHitsTotal: Long = 0,
    val cacheMissesTotal: Long = 0
)
