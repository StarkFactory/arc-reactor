package com.arc.reactor.tool

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkspaceMutationIntentDetectorTest {

    @Test
    fun `explicit workspace mutations를 감지한다`() {
        assertTrue(
            WorkspaceMutationIntentDetector.isWorkspaceMutationPrompt("Jira 이슈 DEV-51를 담당자에게 재할당해줘.")
        ) {
            "Explicit Jira mutations should be detected"
        }
    }

    @Test
    fun `treat release readiness pack as a mutation하지 않는다`() {
        assertFalse(
            WorkspaceMutationIntentDetector.isWorkspaceMutationPrompt(
                "DEV 프로젝트와 jarvis-project/dev 기준으로 release readiness pack을 출처와 함께 만들어줘."
            )
        ) {
            "Read-only synthesized packs should not be treated as workspace mutations"
        }
    }

    @Test
    fun `treat unassigned lookup as a mutation하지 않는다`() {
        assertFalse(
            WorkspaceMutationIntentDetector.isWorkspaceMutationPrompt(
                "DEV 프로젝트에서 unassigned 이슈를 찾아 소스와 함께 보여줘."
            )
        ) {
            "Unassigned issue discovery should remain read-only"
        }
    }

    @Test
    fun `treat approval policy discovery as a mutation하지 않는다`() {
        assertFalse(
            WorkspaceMutationIntentDetector.isWorkspaceMutationPrompt(
                "Confluence에서 '배포 승인' 키워드로 검색하고 관련 정책 문서가 있으면 링크와 함께 알려줘."
            )
        ) {
            "Approval-policy discovery should remain read-only"
        }
    }

    @Test
    fun `treat document change summaries as a mutation하지 않는다`() {
        assertFalse(
            WorkspaceMutationIntentDetector.isWorkspaceMutationPrompt(
                "오늘 standup용으로 Jira 진행 상황과 Confluence 문서 변경을 같이 요약해줘."
            )
        ) {
            "Document change summaries should remain read-only"
        }
    }

    @Test
    fun `swagger catalog removals as mutations를 감지한다`() {
        assertTrue(
            WorkspaceMutationIntentDetector.isWorkspaceMutationPrompt(
                "로드된 Petstore v2 스펙을 catalog에서 제거해줘."
            )
        ) {
            "Swagger catalog removals should be treated as write-style mutations"
        }
    }
}
