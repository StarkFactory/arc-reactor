package com.arc.reactor.guard.output.policy

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * 출력 가드 규칙 평가기에 대한 테스트.
 *
 * 규칙 평가 로직을 검증합니다.
 */
class OutputGuardRuleEvaluatorTest {

    private val evaluator = OutputGuardRuleEvaluator()

    @Test
    fun `content when mask rule matches를 마스킹한다`() {
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
    fun `content when reject rule matches를 거부한다`() {
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
    fun `custom replacement 문자열로 마스킹한다`() {
        val result = evaluator.evaluate(
            content = "my email is user@example.com ok",
            rules = listOf(
                OutputGuardRule(
                    id = "r2",
                    name = "mask email",
                    pattern = "[\\w.+-]+@[\\w-]+\\.[\\w.]+",
                    action = OutputGuardRuleAction.MASK,
                    replacement = "[EMAIL_REMOVED]",
                    priority = 10
                )
            )
        )

        result.blocked shouldBe false
        result.modified shouldBe true
        result.content shouldBe "my email is [EMAIL_REMOVED] ok"
    }

    @Test
    fun `replacement 기본값은 REDACTED이다`() {
        val result = evaluator.evaluate(
            content = "secret: abc123",
            rules = listOf(
                OutputGuardRule(
                    id = "r3",
                    name = "mask secret",
                    pattern = "secret:\\s*\\S+",
                    action = OutputGuardRuleAction.MASK,
                    priority = 10
                )
            )
        )

        result.content shouldBe "[REDACTED]"
    }

    @Test
    fun `collects은(는) invalid regex rules and continues evaluation`() {
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
