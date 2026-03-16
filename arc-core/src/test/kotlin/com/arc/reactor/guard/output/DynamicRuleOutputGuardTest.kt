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

/**
 * 동적 규칙 출력 가드에 대한 테스트.
 *
 * 동적 규칙 기반 출력 필터링을 검증합니다.
 */
class DynamicRuleOutputGuardTest {

    private val context = OutputGuardContext(
        command = AgentCommand(systemPrompt = "sys", userPrompt = "hello"),
        toolsUsed = emptyList(),
        durationMs = 10
    )

    @Test
    fun `content when mask rule matches를 마스킹한다`() = runTest {
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
    fun `content when reject rule matches를 거부한다`() = runTest {
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
    fun `mask rule전에 higher-priority reject rule runs`() = runTest {
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
    fun `reloads immediately when invalidation bus은(는) touched이다`() = runTest {
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

    @Test
    fun `비어있는 store returns Allowed`() = runTest {
        val store = InMemoryOutputGuardRuleStore()
        val guard = DynamicRuleOutputGuard(store, refreshIntervalMs = 0)

        val result = guard.check("any content here", context)
        result shouldBe OutputGuardResult.Allowed.DEFAULT
    }

    @Test
    fun `disabled rule은(는) skipped이다`() = runTest {
        val store = InMemoryOutputGuardRuleStore()
        store.save(
            OutputGuardRule(
                name = "Disabled rule",
                pattern = "(?i)secret",
                action = OutputGuardRuleAction.REJECT,
                enabled = false
            )
        )
        val guard = DynamicRuleOutputGuard(store, refreshIntervalMs = 0)

        val result = guard.check("this is SECRET", context)
        result shouldBe OutputGuardResult.Allowed.DEFAULT
    }

    @Test
    fun `serves stale rules within refresh interval를 캐시한다`() = runTest {
        val store = InMemoryOutputGuardRuleStore()
        val saved = store.save(
            OutputGuardRule(
                name = "Mask secret",
                pattern = "(?i)secret",
                action = OutputGuardRuleAction.MASK
            )
        )
        // 긴 갱신 간격 — 저장소 변경 후 캐시가 다시 로드되지 않음
        val guard = DynamicRuleOutputGuard(store, refreshIntervalMs = 60_000)

        // 첫 번째 호출이 캐시를 로드
        val first = guard.check("SECRET data", context)
        (first is OutputGuardResult.Modified) shouldBe true

        // 저장소에서 규칙 삭제 — 하지만 긴 간격 때문에 캐시가 다시 로드되지 않음
        store.delete(saved.id)

        // still match because cache is stale (within refresh interval)해야 합니다
        val second = guard.check("SECRET data", context)
        (second is OutputGuardResult.Modified) shouldBe true
    }

    @Test
    fun `다중 mask rules apply cumulatively`() = runTest {
        val store = InMemoryOutputGuardRuleStore()
        store.save(
            OutputGuardRule(
                name = "Mask password",
                pattern = "(?i)password\\s*:\\s*\\S+",
                action = OutputGuardRuleAction.MASK,
                priority = 10
            )
        )
        store.save(
            OutputGuardRule(
                name = "Mask email",
                pattern = "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}",
                action = OutputGuardRuleAction.MASK,
                priority = 20
            )
        )
        val guard = DynamicRuleOutputGuard(store, refreshIntervalMs = 0)

        val result = guard.check("password: abc123 and email: user@test.com", context)
        val modified = result as OutputGuardResult.Modified
        (modified.content.contains("abc123")) shouldBe false
        (modified.content.contains("user@test.com")) shouldBe false
    }
}
