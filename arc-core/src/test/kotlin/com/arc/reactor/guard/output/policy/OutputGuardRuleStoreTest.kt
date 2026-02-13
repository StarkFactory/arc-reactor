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
                action = OutputGuardRuleAction.REJECT
            )
        )

        store.list().size shouldBe 1
        store.findById(created.id)?.name shouldBe "Secret keyword"

        val updated = store.update(
            created.id,
            created.copy(enabled = false, action = OutputGuardRuleAction.MASK)
        )

        updated?.enabled shouldBe false
        updated?.action shouldBe OutputGuardRuleAction.MASK

        store.delete(created.id)
        store.list().size shouldBe 0
    }
}
