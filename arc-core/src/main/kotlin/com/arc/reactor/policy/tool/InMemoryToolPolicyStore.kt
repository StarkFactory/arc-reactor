package com.arc.reactor.policy.tool

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

class InMemoryToolPolicyStore(
    initial: ToolPolicy? = null
) : ToolPolicyStore {

    private val ref = AtomicReference<ToolPolicy?>(initial)

    override fun getOrNull(): ToolPolicy? = ref.get()

    override fun save(policy: ToolPolicy): ToolPolicy {
        val now = Instant.now()
        val current = ref.get()
        val saved = policy.copy(
            createdAt = current?.createdAt ?: now,
            updatedAt = now
        )
        ref.set(saved)
        return saved
    }

    override fun delete(): Boolean {
        val existed = ref.getAndSet(null) != null
        return existed
    }
}
