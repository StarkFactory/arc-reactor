package com.arc.reactor.guard.output

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.guard.output.impl.DynamicRuleOutputGuard
import com.arc.reactor.guard.output.policy.InMemoryOutputGuardRuleStore
import com.arc.reactor.guard.output.policy.OutputGuardRule
import com.arc.reactor.guard.output.policy.OutputGuardRuleAction
import com.arc.reactor.guard.output.policy.OutputGuardRuleInvalidationBus
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class DynamicRuleOutputGuardTest {

    private val context = OutputGuardContext(
        command = AgentCommand(systemPrompt = "sys", userPrompt = "hello"),
        toolsUsed = emptyList(),
        durationMs = 10
    )

    @Test
    fun `masks content when mask rule matches`() = runTest {
        val store = InMemoryOutputGuardRuleStore()
        store.save(
            OutputGuardRule(
                name = "Password",
                pattern = "(?i)password\\s*[:=]\\s*\\S+",
                action = OutputGuardRuleAction.MASK
            )
        )
        val guard = DynamicRuleOutputGuard(store, refreshIntervalMs = 0)

        val result = guard.check("password: super-secret", context)

        (result as OutputGuardResult.Modified).content shouldBe "[REDACTED]"
    }

    @Test
    fun `rejects content when reject rule matches`() = runTest {
        val store = InMemoryOutputGuardRuleStore()
        store.save(
            OutputGuardRule(
                name = "Internal only",
                pattern = "(?i)internal\\s+use\\s+only",
                action = OutputGuardRuleAction.REJECT
            )
        )
        val guard = DynamicRuleOutputGuard(store, refreshIntervalMs = 0)

        val result = guard.check("This is INTERNAL USE ONLY", context)

        val rejected = result as OutputGuardResult.Rejected
        rejected.category shouldBe OutputRejectionCategory.POLICY_VIOLATION
    }

    @Test
    fun `higher-priority reject rule runs before mask rule`() = runTest {
        val store = InMemoryOutputGuardRuleStore()
        store.save(
            OutputGuardRule(
                name = "Mask secret",
                pattern = "(?i)secret",
                action = OutputGuardRuleAction.MASK,
                priority = 50
            )
        )
        store.save(
            OutputGuardRule(
                name = "Reject secret",
                pattern = "(?i)secret",
                action = OutputGuardRuleAction.REJECT,
                priority = 1
            )
        )

        val guard = DynamicRuleOutputGuard(store, refreshIntervalMs = 60_000)
        val result = guard.check("SECRET", context)
        (result is OutputGuardResult.Rejected) shouldBe true
    }

    @Test
    fun `reloads immediately when invalidation bus is touched`() = runTest {
        val store = InMemoryOutputGuardRuleStore()
        val invalidationBus = OutputGuardRuleInvalidationBus()
        val guard = DynamicRuleOutputGuard(
            store = store,
            refreshIntervalMs = 60_000,
            invalidationBus = invalidationBus
        )

        val first = guard.check("password: x", context)
        first shouldBe OutputGuardResult.Allowed.DEFAULT

        store.save(
            OutputGuardRule(
                name = "Mask password",
                pattern = "(?i)password\\s*:\\s*\\S+",
                action = OutputGuardRuleAction.MASK
            )
        )
        invalidationBus.touch()

        val second = guard.check("password: x", context)
        (second as OutputGuardResult.Modified).content shouldBe "[REDACTED]"
    }
}
