package com.arc.reactor.admin.collection

import com.arc.reactor.admin.model.MetricEvent
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * 메트릭 이벤트용 Lock-free 링 버퍼 (Disruptor에서 영감).
 *
 * Producer(에이전트 스레드)는 절대 블로킹하지 않는다. 버퍼가 꽉 차면
 * 이벤트를 삭제하고 drop 카운터를 증가시킨다.
 * write sequence의 CAS를 통해 스레드 안전성을 보장한다.
 *
 * @see MetricWriter 이 버퍼에서 drain하여 DB에 기록하는 writer
 */
class MetricRingBuffer(size: Int = 8192) {

    private val capacity: Int = Integer.highestOneBit(size.coerceAtLeast(64))
    private val mask: Int = capacity - 1
    private val buffer = AtomicReferenceArray<MetricEvent?>(capacity)

    private val writeSequence = AtomicLong(0)
    private val readSequence = AtomicLong(0)

    val droppedCount = AtomicLong(0)

    /** 이벤트를 버퍼에 발행한다. 버퍼가 꽉 차면 false를 반환한다. */
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
            // CAS 실패, 다른 스레드가 선점 — 재시도
        }
    }

    /**
     * 최대 [maxBatch]개의 이벤트를 꺼낸다.
     *
     * **중요: 단일 Consumer 전용.** 이 메서드는 동시 호출에 안전하지 않다.
     * 여러 스레드가 동시에 drain()을 호출하면 [readSequence]의
     * 비원자적 read-then-advance로 인해 중복 읽기 및 데이터 손실이 발생한다.
     * 반드시 한 스레드만 호출해야 한다 (예: MetricWriter writerThreads=1).
     */
    fun drain(maxBatch: Int): List<MetricEvent> {
        val currentRead = readSequence.get()
        val currentWrite = writeSequence.get()
        val available = (currentWrite - currentRead).toInt().coerceAtMost(maxBatch)

        if (available <= 0) return emptyList()

        val events = ArrayList<MetricEvent>(available)
        for (i in 0 until available) {
            val index = ((currentRead + i) and mask.toLong()).toInt()
            // publish()의 CAS와 buffer.set() 사이 race 대응: null이면 spin-wait
            var event: MetricEvent? = null
            var spins = 0
            while (event == null && spins < 1000) {
                event = buffer.getAndSet(index, null)
                if (event == null) {
                    Thread.onSpinWait()
                    spins++
                }
            }
            if (event != null) {
                events.add(event)
            } else {
                droppedCount.incrementAndGet()
            }
        }
        readSequence.addAndGet(available.toLong())
        return events
    }

    fun size(): Int = (writeSequence.get() - readSequence.get()).toInt().coerceAtLeast(0)

    fun usagePercent(): Double = size().toDouble() / capacity * 100.0

    fun capacity(): Int = capacity
}
