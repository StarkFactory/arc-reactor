package com.arc.reactor.guard.output.policy

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class OutputGuardRuleEvaluatorTest {

    private val evaluator = OutputGuardRuleEvaluator()

    @Test
    fun `masks content when mask rule matches`() {
        val result = evaluator.evaluate(
            content = "password: test123",
            rules = listOf(
                OutputGuardRule(
                    id = "r1",
                    name = "mask password",
                    pattern = "(?i)password\\s*:\\s*\\S+",
                    action = OutputGuardRuleAction.MASK,
                    priority = 10
                )
            )
        )

        result.blocked shouldBe false
        result.modified shouldBe true
        result.content shouldBe "[REDACTED]"
    }

    @Test
    fun `rejects content when reject rule matches`() {
        val result = evaluator.evaluate(
            content = "internal only",
            rules = listOf(
                OutputGuardRule(
                    id = "r1",
                    name = "reject internal",
                    pattern = "(?i)internal",
                    action = OutputGuardRuleAction.REJECT,
                    priority = 1
                )
            )
        )

        result.blocked shouldBe true
        result.blockedBy?.ruleId shouldBe "r1"
    }

    @Test
    fun `collects invalid regex rules and continues evaluation`() {
        val result = evaluator.evaluate(
            content = "secret",
            rules = listOf(
                OutputGuardRule(
                    id = "broken",
                    name = "broken",
                    pattern = "(?",
                    action = OutputGuardRuleAction.REJECT
                ),
                OutputGuardRule(
                    id = "good",
                    name = "good",
                    pattern = "(?i)secret",
                    action = OutputGuardRuleAction.REJECT
                )
            )
        )

        result.invalidRules.size shouldBe 1
        result.blocked shouldBe true
        result.blockedBy?.ruleId shouldBe "good"
    }
}
