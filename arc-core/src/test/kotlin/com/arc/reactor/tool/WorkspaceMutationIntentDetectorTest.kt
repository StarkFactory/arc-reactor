package com.arc.reactor.tool

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * WorkspaceMutationIntentDetector에 대한 테스트.
 *
 * 워크스페이스 변경 의도 감지 로직을 검증합니다.
 */
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
    fun `format conversion request를 mutation으로 감지하지 않는다`() {
        assertFalse(
            WorkspaceMutationIntentDetector.isWorkspaceMutationPrompt(
                "JAR-36 이슈를 슬랙 메시지 형태로 작성해줘"
            )
        ) {
            "Format conversion requests should not be treated as workspace mutations"
        }
    }

    @Test
    fun `actual workspace mutation은 여전히 감지한다`() {
        assertTrue(
            WorkspaceMutationIntentDetector.isWorkspaceMutationPrompt(
                "JAR-36 이슈의 상태를 변경해줘"
            )
        ) {
            "Actual workspace mutations should still be detected"
        }
    }

    @Test
    fun `Confluence page creation은 여전히 mutation으로 감지한다`() {
        assertTrue(
            WorkspaceMutationIntentDetector.isWorkspaceMutationPrompt(
                "Confluence에 새 페이지 작성해줘"
            )
        ) {
            "Confluence page creation should still be treated as a workspace mutation"
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
