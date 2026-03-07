package com.arc.reactor.agent.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WorkContextForcedToolPlannerTest {

    @Test
    fun `should plan owner lookup from issue ownership prompt`() {
        val plan = WorkContextForcedToolPlanner.plan("PAY-123 이슈 기준으로 담당 서비스와 owner, 팀을 찾아줘.")

        requireNotNull(plan)
        assertEquals("work_owner_lookup", plan.toolName)
        assertEquals("PAY-123", plan.arguments["query"])
    }

    @Test
    fun `should plan item context from issue context prompt`() {
        val plan = WorkContextForcedToolPlanner.plan("PAY-123 이슈 전체 맥락을 정리해줘. 관련 문서와 다음 액션까지 포함해줘.")

        requireNotNull(plan)
        assertEquals("work_item_context", plan.toolName)
        assertEquals("PAY-123", plan.arguments["issueKey"])
    }

    @Test
    fun `should plan service context from service digest prompt`() {
        val plan = WorkContextForcedToolPlanner.plan("payments 서비스 기준으로 최근 Jira 이슈, 관련 문서, 열린 PR까지 한 번에 요약해줘.")

        requireNotNull(plan)
        assertEquals("work_service_context", plan.toolName)
        assertEquals("payments", plan.arguments["service"])
    }

    @Test
    fun `should ignore unrelated prompts`() {
        val plan = WorkContextForcedToolPlanner.plan("이번 주 운영 리포트를 보여줘.")

        assertNull(plan)
    }
}
