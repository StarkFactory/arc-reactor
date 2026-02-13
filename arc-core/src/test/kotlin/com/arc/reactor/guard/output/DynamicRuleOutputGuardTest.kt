package com.arc.reactor.guard.output

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.guard.output.impl.DynamicRuleOutputGuard
import com.arc.reactor.guard.output.policy.InMemoryOutputGuardRuleStore
import com.arc.reactor.guard.output.policy.OutputGuardRule
import com.arc.reactor.guard.output.policy.OutputGuardRuleAction
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
}
