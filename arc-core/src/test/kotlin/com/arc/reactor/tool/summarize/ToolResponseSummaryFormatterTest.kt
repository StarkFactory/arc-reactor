package com.arc.reactor.tool.summarize

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * R241: [ToolResponseSummary] 한국어 포맷터 테스트.
 *
 * `toHumanReadable()`, `toSlackMarkdown()`, `toCompressionLine()`,
 * `SummaryKind.koreanLabel()`, `SummaryKind.shortCode()`,
 * `ToolResponseSummary.compressionPercent()` 등을 검증한다.
 */
class ToolResponseSummaryFormatterTest {

    private fun sampleSummary(
        text: String = "1. JAR-36 — 버그 수정\n2. JAR-42 — 기능 추가",
        kind: SummaryKind = SummaryKind.LIST_TOP_N,
        originalLength: Int = 4096,
        itemCount: Int? = 25,
        primaryKey: String? = "JAR-36"
    ): ToolResponseSummary = ToolResponseSummary(
        text = text,
        kind = kind,
        originalLength = originalLength,
        itemCount = itemCount,
        primaryKey = primaryKey
    )

    @Nested
    inner class SummaryKindLabels {

        @Test
        fun `koreanLabel은 6개 kind별로 올바른 한국어를 반환해야 한다`() {
            assertEquals("에러", SummaryKind.ERROR_CAUSE_FIRST.koreanLabel())
            assertEquals("목록", SummaryKind.LIST_TOP_N.koreanLabel())
            assertEquals("긴 텍스트", SummaryKind.TEXT_HEAD_TAIL.koreanLabel())
            assertEquals("짧은 텍스트", SummaryKind.TEXT_FULL.koreanLabel())
            assertEquals("구조화", SummaryKind.STRUCTURED.koreanLabel())
            assertEquals("빈 응답", SummaryKind.EMPTY.koreanLabel())
        }

        @Test
        fun `shortCode는 6개 kind별로 서로 다른 코드를 반환해야 한다`() {
            val codes = SummaryKind.values().map { it.shortCode() }
            assertEquals(6, codes.toSet().size) {
                "모든 SummaryKind shortCode가 유일해야 한다: $codes"
            }
        }

        @Test
        fun `모든 shortCode는 6자 이내여야 한다`() {
            SummaryKind.values().forEach { kind ->
                assertTrue(kind.shortCode().length <= 6) {
                    "${kind.name} shortCode가 6자 이내: ${kind.shortCode()}"
                }
            }
        }

        @Test
        fun `모든 koreanLabel은 비어있지 않아야 한다`() {
            SummaryKind.values().forEach { kind ->
                assertTrue(kind.koreanLabel().isNotBlank()) {
                    "${kind.name} koreanLabel이 비어있으면 안 된다"
                }
            }
        }
    }

    @Nested
    inner class CompressionPercent {

        @Test
        fun `75퍼센트 압축 정확히 계산되어야 한다`() {
            val summary = ToolResponseSummary(
                text = "x".repeat(250),
                kind = SummaryKind.TEXT_HEAD_TAIL,
                originalLength = 1000
            )
            assertEquals(75, summary.compressionPercent())
        }

        @Test
        fun `원본 0일 때는 0을 반환해야 한다`() {
            val summary = ToolResponseSummary(
                text = "",
                kind = SummaryKind.EMPTY,
                originalLength = 0
            )
            assertEquals(0, summary.compressionPercent()) {
                "divide-by-zero 방지"
            }
        }

        @Test
        fun `요약이 원본보다 긴 경우 음수를 반환해야 한다`() {
            val summary = ToolResponseSummary(
                text = "x".repeat(200),
                kind = SummaryKind.STRUCTURED,
                originalLength = 100
            )
            assertTrue(summary.compressionPercent() < 0) {
                "요약이 원본보다 길면 음수: ${summary.compressionPercent()}"
            }
        }

        @Test
        fun `0퍼센트 압축 (원본과 동일한 길이)`() {
            val summary = ToolResponseSummary(
                text = "x".repeat(100),
                kind = SummaryKind.TEXT_FULL,
                originalLength = 100
            )
            assertEquals(0, summary.compressionPercent())
        }

        @Test
        fun `100퍼센트 가까운 압축 (원본 10000 → 요약 1)`() {
            val summary = ToolResponseSummary(
                text = "x",
                kind = SummaryKind.TEXT_HEAD_TAIL,
                originalLength = 10000
            )
            assertEquals(99, summary.compressionPercent())
        }
    }

    @Nested
    inner class HumanReadableFormat {

        @Test
        fun `헤더와 필수 필드를 포함해야 한다`() {
            val summary = sampleSummary()
            val text = summary.toHumanReadable(toolName = "jira_search_issues")

            assertTrue(text.contains("=== 도구 응답 요약 ===")) { "헤더" }
            assertTrue(text.contains("도구: jira_search_issues")) { "도구 이름" }
            assertTrue(text.contains("전략: 목록 (LIST_TOP_N)")) { "전략 한국어 + enum 이름" }
            assertTrue(text.contains("원본 길이: 4,096자")) { "쉼표 천 단위 포맷" }
            assertTrue(text.contains("압축률:")) { "압축률 라인" }
        }

        @Test
        fun `toolName null이면 도구 라인이 생략되어야 한다`() {
            val summary = sampleSummary()
            val text = summary.toHumanReadable(toolName = null)
            assertFalse(text.contains("도구:")) {
                "toolName null이면 생략"
            }
        }

        @Test
        fun `itemCount null이면 항목 수 라인이 없어야 한다`() {
            val summary = sampleSummary(itemCount = null)
            val text = summary.toHumanReadable()
            assertFalse(text.contains("항목 수:")) {
                "itemCount null → 생략"
            }
        }

        @Test
        fun `primaryKey null이면 주요 항목 라인이 없어야 한다`() {
            val summary = sampleSummary(primaryKey = null)
            val text = summary.toHumanReadable()
            assertFalse(text.contains("주요 항목:")) {
                "primaryKey null → 생략"
            }
        }

        @Test
        fun `primaryKey와 itemCount 모두 있으면 양쪽 모두 포함되어야 한다`() {
            val summary = sampleSummary(itemCount = 25, primaryKey = "JAR-36")
            val text = summary.toHumanReadable()
            assertTrue(text.contains("항목 수: 25"))
            assertTrue(text.contains("주요 항목: JAR-36"))
        }

        @Test
        fun `includeBody=true이면 요약 본문이 포함되어야 한다`() {
            val summary = sampleSummary(text = "1. JAR-36 — 버그\n2. JAR-42 — 기능")
            val text = summary.toHumanReadable(includeBody = true)
            assertTrue(text.contains("[요약 본문]"))
            assertTrue(text.contains("JAR-36"))
            assertTrue(text.contains("JAR-42"))
        }

        @Test
        fun `includeBody=false이면 요약 본문이 제외되어야 한다`() {
            val summary = sampleSummary(text = "숨겨진 본문")
            val text = summary.toHumanReadable(includeBody = false)
            assertFalse(text.contains("[요약 본문]"))
            assertFalse(text.contains("숨겨진 본문"))
            // 메타데이터는 여전히 포함
            assertTrue(text.contains("전략:"))
        }

        @Test
        fun `빈 text는 본문 섹션이 생략되어야 한다`() {
            val summary = ToolResponseSummary(
                text = "",
                kind = SummaryKind.EMPTY,
                originalLength = 0
            )
            val text = summary.toHumanReadable(includeBody = true)
            assertFalse(text.contains("[요약 본문]")) {
                "빈 text는 본문 섹션 생략"
            }
        }

        @Test
        fun `커스텀 lineSeparator가 적용되어야 한다`() {
            val summary = sampleSummary()
            val text = summary.toHumanReadable(lineSeparator = "\r\n")
            assertTrue(text.contains("===\r\n")) {
                "헤더 뒤 CRLF"
            }
        }

        @Test
        fun `1000자 미만 길이는 쉼표 없이 포맷되어야 한다`() {
            val summary = ToolResponseSummary(
                text = "짧은 요약",
                kind = SummaryKind.TEXT_FULL,
                originalLength = 42
            )
            val text = summary.toHumanReadable()
            assertTrue(text.contains("원본 길이: 42자")) {
                "1000 미만은 쉼표 없이"
            }
        }
    }

    @Nested
    inner class SlackMarkdownFormat {

        @Test
        fun `bold 타이틀과 code 도구명을 포함해야 한다`() {
            val summary = sampleSummary()
            val text = summary.toSlackMarkdown(toolName = "jira_search_issues")
            assertTrue(text.contains("*도구 응답 요약*")) { "bold 타이틀" }
            assertTrue(text.contains("`jira_search_issues`")) { "code 도구명" }
        }

        @Test
        fun `shortCode가 backtick으로 감싸져 포함되어야 한다`() {
            val summary = sampleSummary(kind = SummaryKind.LIST_TOP_N)
            val text = summary.toSlackMarkdown()
            assertTrue(text.contains("`[LIST]`")) { "shortCode backtick wrap" }
        }

        @Test
        fun `itemCount가 있으면 N건 형식으로 포함되어야 한다`() {
            val summary = sampleSummary(itemCount = 25)
            val text = summary.toSlackMarkdown()
            assertTrue(text.contains("25건")) { "itemCount N건" }
        }

        @Test
        fun `압축률과 길이 전환 표기가 포함되어야 한다`() {
            val summary = ToolResponseSummary(
                text = "x".repeat(512),
                kind = SummaryKind.LIST_TOP_N,
                originalLength = 4096,
                itemCount = 25
            )
            val text = summary.toSlackMarkdown()
            assertTrue(text.contains("% 축약")) { "축약 라벨" }
            assertTrue(text.contains("4,096자")) { "원본 길이" }
            assertTrue(text.contains("→")) { "화살표 전환 표기" }
            assertTrue(text.contains("512자")) { "요약 길이" }
        }

        @Test
        fun `includeBody=true이면 본문이 quote로 포함되어야 한다`() {
            val summary = sampleSummary(text = "줄1\n줄2")
            val text = summary.toSlackMarkdown(includeBody = true)
            assertTrue(text.contains("> 줄1"))
            assertTrue(text.contains("> 줄2"))
        }

        @Test
        fun `includeBody=false이면 본문이 제외되어야 한다`() {
            val summary = sampleSummary(text = "숨겨진")
            val text = summary.toSlackMarkdown(includeBody = false)
            assertFalse(text.contains("> 숨겨진"))
        }

        @Test
        fun `maxBodyLines 초과 시 생략 안내가 포함되어야 한다`() {
            val body = (1..10).joinToString("\n") { "line $it" }
            val summary = sampleSummary(text = body)
            val text = summary.toSlackMarkdown(includeBody = true, maxBodyLines = 3)

            assertTrue(text.contains("> line 1"))
            assertTrue(text.contains("> line 2"))
            assertTrue(text.contains("> line 3"))
            assertFalse(text.contains("> line 4")) { "4번째부터 생략" }
            assertTrue(text.contains("(+7행 생략)")) { "생략 안내" }
        }

        @Test
        fun `maxBodyLines 이하일 때는 생략 안내가 없어야 한다`() {
            val summary = sampleSummary(text = "단일 라인")
            val text = summary.toSlackMarkdown(includeBody = true, maxBodyLines = 5)
            assertFalse(text.contains("행 생략")) {
                "maxBodyLines 이하면 생략 안내 없음"
            }
        }

        @Test
        fun `toolName null이면 대시 + code가 생략되어야 한다`() {
            val summary = sampleSummary()
            val text = summary.toSlackMarkdown(toolName = null)
            assertTrue(text.contains("*도구 응답 요약*"))
            assertFalse(text.contains("— `")) {
                "toolName null이면 대시+code 생략"
            }
        }
    }

    @Nested
    inner class CompressionLine {

        @Test
        fun `기본 형식은 shortCode + itemCount + 압축률 + 길이를 포함해야 한다`() {
            val summary = ToolResponseSummary(
                text = "x".repeat(512),
                kind = SummaryKind.LIST_TOP_N,
                originalLength = 4096,
                itemCount = 25
            )
            val line = summary.toCompressionLine()

            assertTrue(line.startsWith("[LIST]")) { "shortCode prefix" }
            assertTrue(line.contains("25건")) { "itemCount" }
            assertTrue(line.contains("% 축약")) { "압축률 라벨" }
            assertTrue(line.contains("4,096자")) { "원본" }
            assertTrue(line.contains("512자")) { "요약" }
            assertTrue(line.contains("→")) { "화살표" }
        }

        @Test
        fun `itemCount 없으면 primaryKey를 대신 표시해야 한다`() {
            val summary = ToolResponseSummary(
                text = "요약",
                kind = SummaryKind.STRUCTURED,
                originalLength = 1000,
                itemCount = null,
                primaryKey = "JAR-36"
            )
            val line = summary.toCompressionLine()
            assertTrue(line.contains("JAR-36")) { "primaryKey 대체 표시" }
            assertFalse(line.contains("건")) { "건 표기 없음" }
        }

        @Test
        fun `itemCount와 primaryKey 모두 없으면 kind와 압축률만 나와야 한다`() {
            val summary = ToolResponseSummary(
                text = "잘린 본문",
                kind = SummaryKind.TEXT_HEAD_TAIL,
                originalLength = 2000
            )
            val line = summary.toCompressionLine()
            assertTrue(line.startsWith("[HEAD]"))
            assertTrue(line.contains("% 축약"))
            assertFalse(line.contains("건"))
        }

        @Test
        fun `에러 요약도 올바르게 렌더링되어야 한다`() {
            val summary = ToolResponseSummary(
                text = "401 Unauthorized",
                kind = SummaryKind.ERROR_CAUSE_FIRST,
                originalLength = 1024
            )
            val line = summary.toCompressionLine()
            assertTrue(line.startsWith("[ERR]")) { "ERR prefix" }
        }

        @Test
        fun `빈 응답도 올바르게 렌더링되어야 한다`() {
            val summary = ToolResponseSummary(
                text = "",
                kind = SummaryKind.EMPTY,
                originalLength = 0
            )
            val line = summary.toCompressionLine()
            assertTrue(line.startsWith("[EMPTY]"))
            assertTrue(line.contains("0% 축약")) { "0 division guard" }
        }
    }

    @Nested
    inner class RealisticScenarios {

        @Test
        fun `Jira 검색 결과 25건 → 5건 요약 시나리오`() {
            val body = (1..5).joinToString("\n") { "$it. JAR-${30 + it} — 이슈 제목 $it" }
            val summary = ToolResponseSummary(
                text = body,
                kind = SummaryKind.LIST_TOP_N,
                originalLength = 8192,
                itemCount = 25,
                primaryKey = "JAR-31"
            )

            val human = summary.toHumanReadable(toolName = "jira_search_issues")
            assertTrue(human.contains("도구: jira_search_issues"))
            assertTrue(human.contains("전략: 목록"))
            assertTrue(human.contains("항목 수: 25"))
            assertTrue(human.contains("주요 항목: JAR-31"))
            assertTrue(human.contains("JAR-31 — 이슈 제목 1"))

            val slack = summary.toSlackMarkdown(toolName = "jira_search_issues")
            assertTrue(slack.contains("*도구 응답 요약*"))
            assertTrue(slack.contains("`[LIST]`"))
            assertTrue(slack.contains("25건"))

            val line = summary.toCompressionLine()
            assertTrue(line.contains("25건"))
            assertTrue(line.contains("% 축약"))
        }

        @Test
        fun `Confluence 긴 페이지 → head-tail 요약 시나리오`() {
            val summary = ToolResponseSummary(
                text = "페이지 제목\n...\n마지막 문단",
                kind = SummaryKind.TEXT_HEAD_TAIL,
                originalLength = 50000,
                itemCount = null,
                primaryKey = "Confluence 페이지"
            )

            val human = summary.toHumanReadable(toolName = "confluence_get_page")
            assertTrue(human.contains("전략: 긴 텍스트"))
            assertTrue(human.contains("50,000자"))
            assertFalse(human.contains("항목 수:")) { "itemCount null" }
            assertTrue(human.contains("주요 항목: Confluence 페이지"))
        }

        @Test
        fun `에러 응답 시나리오`() {
            val summary = ToolResponseSummary(
                text = "401 Unauthorized - API token invalid",
                kind = SummaryKind.ERROR_CAUSE_FIRST,
                originalLength = 2048,
                itemCount = null,
                primaryKey = null
            )

            val human = summary.toHumanReadable(toolName = "atlassian_get_user")
            assertTrue(human.contains("전략: 에러"))
            assertTrue(human.contains("401 Unauthorized"))

            val line = summary.toCompressionLine()
            assertTrue(line.startsWith("[ERR]"))
        }
    }
}
