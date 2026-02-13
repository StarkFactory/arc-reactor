package com.arc.reactor.guard.output.policy

import java.util.concurrent.atomic.AtomicLong

/**
 * In-process invalidation bus for dynamic output guard rule cache.
 *
 * Controllers call [touch] after rule mutations so guard stages can reload
 * immediately without waiting for periodic refresh intervals.
 */
class OutputGuardRuleInvalidationBus {
    private val revision = AtomicLong(0)

    fun currentRevision(): Long = revision.get()

    fun touch(): Long = revision.incrementAndGet()
}
