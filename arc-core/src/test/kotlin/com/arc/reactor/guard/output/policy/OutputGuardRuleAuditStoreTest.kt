package com.arc.reactor.guard.output.policy

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * 출력 가드 규칙 감사 저장소에 대한 테스트.
 *
 * 규칙 평가 감사 기록을 검증합니다.
 */
class OutputGuardRuleAuditStoreTest {

    @Test
    fun `인메모리 audit store saves newest first`() {
        val store = InMemoryOutputGuardRuleAuditStore()
        store.save(OutputGuardRuleAuditLog(ruleId = "r1", action = OutputGuardRuleAuditAction.CREATE, actor = "admin"))
        store.save(OutputGuardRuleAuditLog(ruleId = "r2", action = OutputGuardRuleAuditAction.UPDATE, actor = "admin"))

        val logs = store.list(limit = 10)
        logs.size shouldBe 2
        logs[0].ruleId shouldBe "r2"
        logs[1].ruleId shouldBe "r1"
    }
}
