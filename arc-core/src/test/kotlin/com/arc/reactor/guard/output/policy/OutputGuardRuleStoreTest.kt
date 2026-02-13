package com.arc.reactor.guard.output.policy

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class OutputGuardRuleStoreTest {

    @Test
    fun `in-memory store supports CRUD`() {
        val store = InMemoryOutputGuardRuleStore()
        val created = store.save(
            OutputGuardRule(
                name = "Secret keyword",
                pattern = "(?i)secret",
                action = OutputGuardRuleAction.REJECT,
                priority = 50
            )
        )

        store.list().size shouldBe 1
        store.findById(created.id)?.name shouldBe "Secret keyword"
        store.findById(created.id)?.priority shouldBe 50

        val updated = store.update(
            created.id,
            created.copy(enabled = false, action = OutputGuardRuleAction.MASK, priority = 10)
        )

        updated?.enabled shouldBe false
        updated?.action shouldBe OutputGuardRuleAction.MASK
        updated?.priority shouldBe 10

        store.delete(created.id)
        store.list().size shouldBe 0
    }

    @Test
    fun `in-memory store lists rules by priority then createdAt`() {
        val store = InMemoryOutputGuardRuleStore()
        store.save(OutputGuardRule(name = "low", pattern = "a", priority = 100))
        store.save(OutputGuardRule(name = "high", pattern = "a", priority = 1))
        store.save(OutputGuardRule(name = "mid", pattern = "a", priority = 10))

        val names = store.list().map { it.name }
        names shouldBe listOf("high", "mid", "low")
    }
}
