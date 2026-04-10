package com.arc.reactor.approval

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * R240: [ToolApprovalRequest] / [ApprovalSummary] 한국어 포맷터 테스트.
 *
 * `toHumanReadable()`, `toSlackMarkdown()`, `toApprovalPromptLine()`,
 * `Reversibility.koreanLabel()`, `ApprovalStatus.koreanLabel()`,
 * `ApprovalContext.toOneLineSummary()` 등을 검증한다.
 */
class ApprovalRequestFormatterTest {

    private val fixedTime: Instant = Instant.parse("2026-04-11T12:30:00Z")

    private fun sampleRequest(
        context: ApprovalContext? = null,
        arguments: Map<String, Any?> = mapOf(
            "issueIdOrKey" to "JAR-36",
            "transitionId" to "31"
        )
    ): ToolApprovalRequest = ToolApprovalRequest(
        id = "req-abc123",
        runId = "run-xyz789",
        userId = "stark@example.com",
        toolName = "jira_transition_issue",
        arguments = arguments,
        requestedAt = fixedTime,
        context = context
    )

    private fun sampleContext(
        reversibility: Reversibility = Reversibility.REVERSIBLE
    ): ApprovalContext = ApprovalContext(
        reason = "이슈 상태 전이 (완료)",
        action = "JAR-36 이슈를 'Done' 상태로 전이",
        impactScope = "1 Jira 이슈",
        reversibility = reversibility
    )

    @Nested
    inner class ReversibilityLabels {

        @Test
        fun `koreanLabel은 4개 상태별로 올바른 한국어를 반환해야 한다`() {
            assertEquals("복구 가능", Reversibility.REVERSIBLE.koreanLabel())
            assertEquals("부분 복구 가능", Reversibility.PARTIALLY_REVERSIBLE.koreanLabel())
            assertEquals("복구 불가", Reversibility.IRREVERSIBLE.koreanLabel())
            assertEquals("알 수 없음", Reversibility.UNKNOWN.koreanLabel())
        }

        @Test
        fun `모든 koreanLabel은 비어있지 않아야 한다`() {
            Reversibility.values().forEach { r ->
                assertTrue(r.koreanLabel().isNotBlank()) {
                    "${r.name} koreanLabel이 비어있으면 안 된다"
                }
            }
        }

        @Test
        fun `symbol은 4개 상태별로 서로 다른 유니코드 심볼을 반환해야 한다`() {
            val symbols = Reversibility.values().map { it.symbol() }
            assertEquals(4, symbols.toSet().size) {
                "모든 Reversibility symbol이 서로 달라야 한다: $symbols"
            }
            Reversibility.values().forEach { r ->
                assertTrue(r.symbol().isNotBlank()) {
                    "${r.name} symbol이 비어있으면 안 된다"
                }
            }
        }
    }

    @Nested
    inner class ApprovalStatusLabels {

        @Test
        fun `koreanLabel은 4개 상태별로 올바른 한국어를 반환해야 한다`() {
            assertEquals("대기 중", ApprovalStatus.PENDING.koreanLabel())
            assertEquals("승인됨", ApprovalStatus.APPROVED.koreanLabel())
            assertEquals("거부됨", ApprovalStatus.REJECTED.koreanLabel())
            assertEquals("타임아웃", ApprovalStatus.TIMED_OUT.koreanLabel())
        }

        @Test
        fun `모든 ApprovalStatus는 한국어 라벨을 가져야 한다`() {
            ApprovalStatus.values().forEach { s ->
                assertTrue(s.koreanLabel().isNotBlank()) {
                    "${s.name} koreanLabel이 비어있으면 안 된다"
                }
            }
        }
    }

    @Nested
    inner class OneLineSummary {

        @Test
        fun `정보가 없는 컨텍스트는 빈 문자열을 반환해야 한다`() {
            assertEquals("", ApprovalContext.EMPTY.toOneLineSummary())
        }

        @Test
        fun `모든 필드가 채워진 컨텍스트는 가운데 점으로 구분되어야 한다`() {
            val ctx = sampleContext()
            val summary = ctx.toOneLineSummary()
            assertTrue(summary.contains(" · ")) { "가운데 점 구분자 포함: $summary" }
            assertTrue(summary.contains("이슈 상태 전이 (완료)")) { "reason 포함" }
            assertTrue(summary.contains("JAR-36")) { "action 포함" }
            assertTrue(summary.contains("1 Jira 이슈")) { "impactScope 포함" }
            assertTrue(summary.contains("복구 가능")) { "reversibility 한국어 포함" }
        }

        @Test
        fun `UNKNOWN reversibility는 요약에서 생략되어야 한다`() {
            val ctx = ApprovalContext(
                reason = "사유만 있음",
                reversibility = Reversibility.UNKNOWN
            )
            val summary = ctx.toOneLineSummary()
            assertEquals("사유만 있음", summary) {
                "UNKNOWN이면 reversibility 라벨 없이 reason만 나와야 한다"
            }
        }

        @Test
        fun `빈 문자열 필드는 생략되어야 한다`() {
            val ctx = ApprovalContext(
                reason = "",
                action = "실제 행동",
                impactScope = "  ",
                reversibility = Reversibility.IRREVERSIBLE
            )
            val summary = ctx.toOneLineSummary()
            assertEquals("실제 행동 · 복구 불가", summary) {
                "빈/공백 필드는 제외되어야 한다"
            }
        }
    }

    @Nested
    inner class HumanReadableFormat {

        @Test
        fun `헤더와 기본 필드를 포함해야 한다`() {
            val req = sampleRequest()
            val text = req.toHumanReadable()

            assertTrue(text.contains("=== 도구 승인 요청 ===")) { "헤더 포함" }
            assertTrue(text.contains("ID: req-abc123")) { "요청 ID" }
            assertTrue(text.contains("실행 ID: run-xyz789")) { "runId" }
            assertTrue(text.contains("사용자: stark@example.com")) { "userId" }
            assertTrue(text.contains("도구: jira_transition_issue")) { "toolName" }
            assertTrue(text.contains("2026-04-11T12:30:00Z")) { "requestedAt" }
        }

        @Test
        fun `컨텍스트가 있으면 컨텍스트 섹션이 포함되어야 한다`() {
            val req = sampleRequest(context = sampleContext())
            val text = req.toHumanReadable()

            assertTrue(text.contains("[컨텍스트]")) { "컨텍스트 섹션 헤더" }
            assertTrue(text.contains("사유: 이슈 상태 전이 (완료)")) { "reason" }
            assertTrue(text.contains("행동: JAR-36 이슈를 'Done' 상태로 전이")) { "action" }
            assertTrue(text.contains("영향: 1 Jira 이슈")) { "impactScope" }
            assertTrue(text.contains("복구: 복구 가능")) { "reversibility 한국어" }
        }

        @Test
        fun `컨텍스트가 null이면 컨텍스트 섹션이 없어야 한다`() {
            val req = sampleRequest(context = null)
            val text = req.toHumanReadable()
            assertFalse(text.contains("[컨텍스트]")) {
                "컨텍스트가 없으면 섹션 생략"
            }
        }

        @Test
        fun `빈 컨텍스트는 컨텍스트 섹션이 없어야 한다`() {
            val req = sampleRequest(context = ApprovalContext.EMPTY)
            val text = req.toHumanReadable()
            assertFalse(text.contains("[컨텍스트]")) {
                "정보 없는 컨텍스트는 섹션 생략"
            }
        }

        @Test
        fun `includeArguments=true이면 인수가 포함되어야 한다`() {
            val req = sampleRequest()
            val text = req.toHumanReadable(includeArguments = true)

            assertTrue(text.contains("[인수]")) { "인수 섹션 헤더" }
            assertTrue(text.contains("issueIdOrKey: \"JAR-36\"")) { "문자열은 따옴표" }
            assertTrue(text.contains("transitionId: \"31\"")) { "문자열 인수" }
        }

        @Test
        fun `includeArguments=false이면 인수가 포함되지 않아야 한다`() {
            val req = sampleRequest()
            val text = req.toHumanReadable(includeArguments = false)

            assertFalse(text.contains("[인수]")) { "인수 섹션 제외" }
            assertFalse(text.contains("issueIdOrKey")) { "인수 키 제외" }
        }

        @Test
        fun `null 인수는 문자열 null로 출력되어야 한다`() {
            val req = sampleRequest(arguments = mapOf("optional" to null))
            val text = req.toHumanReadable()
            assertTrue(text.contains("optional: null")) { "null 인수 처리" }
        }

        @Test
        fun `200자 초과 인수는 축약되어야 한다`() {
            val longValue = "x".repeat(250)
            val req = sampleRequest(arguments = mapOf("body" to longValue))
            val text = req.toHumanReadable()
            assertTrue(text.contains("...")) { "긴 값은 말줄임표로 축약" }
            assertFalse(text.contains("x".repeat(250))) { "원본 그대로 포함되면 안 됨" }
        }

        @Test
        fun `커스텀 lineSeparator가 적용되어야 한다`() {
            val req = sampleRequest(context = sampleContext())
            val text = req.toHumanReadable(lineSeparator = "\r\n")
            assertTrue(text.contains("===\r\n")) {
                "헤더 뒤 CRLF — 라인 구분자 일관 적용"
            }
        }

        @Test
        fun `빈 인수 맵도 정상 처리되어야 한다`() {
            val req = sampleRequest(arguments = emptyMap())
            val text = req.toHumanReadable()
            assertTrue(text.contains("ID: req-abc123")) { "기본 필드 정상 출력" }
            assertFalse(text.contains("[인수]")) { "빈 인수는 섹션 생략" }
        }
    }

    @Nested
    inner class SlackMarkdownFormat {

        @Test
        fun `Slack 포맷은 bold 타이틀과 code 도구명을 포함해야 한다`() {
            val req = sampleRequest(context = sampleContext())
            val text = req.toSlackMarkdown()

            assertTrue(text.contains("*도구 승인 요청*")) { "bold 타이틀" }
            assertTrue(text.contains("`jira_transition_issue`")) { "code 도구명" }
        }

        @Test
        fun `컨텍스트가 있으면 quote 요약 라인이 포함되어야 한다`() {
            val req = sampleRequest(context = sampleContext())
            val text = req.toSlackMarkdown()

            assertTrue(text.contains("> ")) { "quote 라인 포함" }
            assertTrue(text.contains("이슈 상태 전이")) { "reason" }
            assertTrue(text.contains("복구 가능")) { "reversibility" }
            assertTrue(text.contains(" · ")) { "가운데 점 구분자" }
        }

        @Test
        fun `컨텍스트가 null이면 quote 라인이 생략되어야 한다`() {
            val req = sampleRequest(context = null)
            val text = req.toSlackMarkdown()
            assertFalse(text.contains("> ")) { "컨텍스트 없으면 quote 생략" }
        }

        @Test
        fun `요청자와 ID가 italic으로 포함되어야 한다`() {
            val req = sampleRequest(context = sampleContext())
            val text = req.toSlackMarkdown()
            assertTrue(text.contains("_요청자: stark@example.com_")) { "italic 요청자" }
            assertTrue(text.contains("_ID: req-abc123_")) { "italic ID" }
        }

        @Test
        fun `Slack 포맷은 인수를 포함하지 않아야 한다`() {
            val req = sampleRequest(context = sampleContext())
            val text = req.toSlackMarkdown()
            assertFalse(text.contains("issueIdOrKey")) {
                "Slack 축약 포맷은 인수 제외"
            }
        }
    }

    @Nested
    inner class ApprovalPromptLine {

        @Test
        fun `컨텍스트가 없으면 도구명만 반환해야 한다`() {
            val req = sampleRequest(context = null)
            assertEquals("jira_transition_issue", req.toApprovalPromptLine())
        }

        @Test
        fun `빈 컨텍스트는 도구명만 반환해야 한다`() {
            val req = sampleRequest(context = ApprovalContext.EMPTY)
            assertEquals("jira_transition_issue", req.toApprovalPromptLine())
        }

        @Test
        fun `전체 컨텍스트는 reversibility prefix와 action, impactScope를 포함해야 한다`() {
            val req = sampleRequest(context = sampleContext(Reversibility.IRREVERSIBLE))
            val line = req.toApprovalPromptLine()

            assertTrue(line.startsWith("[복구 불가]")) { "prefix" }
            assertTrue(line.contains("jira_transition_issue")) { "tool name" }
            assertTrue(line.contains("JAR-36")) { "action" }
            assertTrue(line.contains("(1 Jira 이슈)")) { "impactScope in parens" }
        }

        @Test
        fun `UNKNOWN reversibility는 prefix 없이 출력되어야 한다`() {
            val ctx = ApprovalContext(
                action = "단순 조회",
                reversibility = Reversibility.UNKNOWN
            )
            val req = sampleRequest(context = ctx)
            val line = req.toApprovalPromptLine()
            assertFalse(line.startsWith("[")) { "UNKNOWN이면 prefix 없음" }
            assertTrue(line.contains("단순 조회")) { "action 여전히 포함" }
        }
    }

    @Nested
    inner class ApprovalSummaryFormat {

        private fun sampleSummary(
            status: ApprovalStatus = ApprovalStatus.PENDING,
            context: ApprovalContext? = sampleContext()
        ): ApprovalSummary = ApprovalSummary(
            id = "req-abc123",
            runId = "run-xyz789",
            userId = "stark@example.com",
            toolName = "jira_transition_issue",
            arguments = mapOf("issueIdOrKey" to "JAR-36"),
            requestedAt = fixedTime,
            status = status,
            context = context
        )

        @Test
        fun `ApprovalSummary 포맷은 상태 한국어 라벨을 포함해야 한다`() {
            val summary = sampleSummary(status = ApprovalStatus.APPROVED)
            val text = summary.toHumanReadable()
            assertTrue(text.contains("=== 승인 요약 ===")) { "요약 헤더" }
            assertTrue(text.contains("상태: 승인됨")) { "상태 한국어" }
        }

        @Test
        fun `ApprovalSummary 포맷은 컨텍스트를 포함할 수 있어야 한다`() {
            val summary = sampleSummary()
            val text = summary.toHumanReadable()
            assertTrue(text.contains("[컨텍스트]")) { "컨텍스트 섹션" }
            assertTrue(text.contains("복구: 복구 가능")) { "reversibility" }
        }

        @Test
        fun `컨텍스트 없는 ApprovalSummary도 정상 처리되어야 한다`() {
            val summary = sampleSummary(context = null)
            val text = summary.toHumanReadable()
            assertFalse(text.contains("[컨텍스트]")) {
                "컨텍스트 없으면 섹션 생략"
            }
            assertTrue(text.contains("도구: jira_transition_issue")) {
                "기본 필드는 그대로 출력"
            }
        }

        @Test
        fun `ApprovalSummary 4개 상태 모두 라벨이 출력되어야 한다`() {
            ApprovalStatus.values().forEach { status ->
                val text = sampleSummary(status = status).toHumanReadable()
                assertTrue(text.contains("상태: ${status.koreanLabel()}")) {
                    "${status.name} 상태 라벨 출력"
                }
            }
        }
    }

    @Nested
    inner class RealisticScenarios {

        @Test
        fun `Jira 전이 승인 시나리오 (복구 가능)`() {
            val req = sampleRequest(
                context = ApprovalContext(
                    reason = "이슈 상태 전이 (완료)",
                    action = "JAR-36 이슈를 'Done' 상태로 전이",
                    impactScope = "1 Jira 이슈",
                    reversibility = Reversibility.REVERSIBLE
                )
            )

            val human = req.toHumanReadable()
            assertTrue(human.contains("복구: 복구 가능"))
            assertTrue(human.contains("JAR-36"))

            val slack = req.toSlackMarkdown()
            assertTrue(slack.contains("*도구 승인 요청*"))
            assertTrue(slack.contains("복구 가능"))

            val line = req.toApprovalPromptLine()
            assertTrue(line.startsWith("[복구 가능]"))
        }

        @Test
        fun `Bitbucket PR 머지 시나리오 (복구 불가)`() {
            val req = ToolApprovalRequest(
                id = "req-merge-42",
                runId = "run-deploy",
                userId = "stark@example.com",
                toolName = "bitbucket_merge_pull_request",
                arguments = mapOf("repo" to "web-labs", "prId" to 42),
                requestedAt = fixedTime,
                context = ApprovalContext(
                    reason = "PR 머지 (main 브랜치)",
                    action = "PR #42를 main에 머지",
                    impactScope = "web-labs 저장소 main 브랜치",
                    reversibility = Reversibility.IRREVERSIBLE
                )
            )

            val human = req.toHumanReadable()
            assertTrue(human.contains("복구: 복구 불가"))
            assertTrue(human.contains("PR #42"))
            assertTrue(human.contains("web-labs"))

            val line = req.toApprovalPromptLine()
            assertTrue(line.startsWith("[복구 불가]")) {
                "IRREVERSIBLE prefix 경고 표시"
            }
        }

        @Test
        fun `컨텍스트 없는 레거시 요청도 정상 처리되어야 한다`() {
            val req = ToolApprovalRequest(
                id = "legacy-req",
                runId = "legacy-run",
                userId = "old-user",
                toolName = "old_tool",
                arguments = mapOf("param" to "value"),
                requestedAt = fixedTime
                // context = null (default)
            )

            val human = req.toHumanReadable()
            assertTrue(human.contains("old_tool")) { "기본 경로 동작" }
            assertFalse(human.contains("[컨텍스트]")) { "컨텍스트 없음" }

            val slack = req.toSlackMarkdown()
            assertTrue(slack.contains("`old_tool`")) { "Slack 기본 경로" }
            assertFalse(slack.contains("> ")) { "quote 없음" }

            assertEquals("old_tool", req.toApprovalPromptLine())
        }
    }
}
