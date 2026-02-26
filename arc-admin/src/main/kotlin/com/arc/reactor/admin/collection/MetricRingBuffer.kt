package com.arc.reactor.admin.collection

import com.arc.reactor.admin.model.MetricEvent
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * Lock-free ring buffer for metric events (Disruptor-inspired).
 *
 * Producers (agent threads) never block. If the buffer is full,
 * events are dropped and the drop counter is incremented.
 * Thread-safe via CAS on the write sequence.
 */
class MetricRingBuffer(size: Int = 8192) {

    private val capacity: Int = Integer.highestOneBit(size.coerceAtLeast(64))
    private val mask: Int = capacity - 1
    private val buffer = AtomicReferenceArray<MetricEvent?>(capacity)

    private val writeSequence = AtomicLong(0)
    private val readSequence = AtomicLong(0)

    val droppedCount = AtomicLong(0)

    fun publish(event: MetricEvent): Boolean {
        while (true) {
            val currentWrite = writeSequence.get()
            val currentRead = readSequence.get()

            if (currentWrite - currentRead >= capacity) {
                droppedCount.incrementAndGet()
                return false
            }

            if (writeSequence.compareAndSet(currentWrite, currentWrite + 1)) {
                val index = (currentWrite and mask.toLong()).toInt()
                buffer.set(index, event)
                return true
            }
            // CAS failed, another thread won — retry
        }
    }

    /**
     * Drain up to [maxBatch] events.
     *
     * **IMPORTANT: Single-consumer only.** This method is NOT safe for concurrent
     * invocation. Multiple threads calling drain() simultaneously will cause
     * duplicate reads and data loss due to non-atomic read-then-advance of
     * [readSequence]. Ensure only one thread calls drain() at a time
     * (e.g., MetricWriter with writerThreads=1).
     */
    fun drain(maxBatch: Int): List<MetricEvent> {
        val currentRead = readSequence.get()
        val currentWrite = writeSequence.get()
        val available = (currentWrite - currentRead).toInt().coerceAtMost(maxBatch)

        if (available <= 0) return emptyList()

        val events = ArrayList<MetricEvent>(available)
        for (i in 0 until available) {
            val index = ((currentRead + i) and mask.toLong()).toInt()
            val event = buffer.getAndSet(index, null)
            if (event != null) {
                events.add(event)
            }
        }
        readSequence.addAndGet(available.toLong())
        return events
    }

    fun size(): Int = (writeSequence.get() - readSequence.get()).toInt().coerceAtLeast(0)

    fun usagePercent(): Double = size().toDouble() / capacity * 100.0

    fun capacity(): Int = capacity
}
