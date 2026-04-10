package com.arc.reactor.agent.impl

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * ManualReActLoopExecutor.looksLikeCompletedAnswer 단위 테스트.
 *
 * R204에서 발견된 intent detection false positive를 방지하는 로직이 올바르게 작동하는지 검증한다.
 * R203 R2에서 완결된 답변("BB30 저장소 조회 실패 + 💡 인사이트")이 intent로 오감지되어
 * 불필요한 retry가 트리거되던 버그를 차단하는 safeguard.
 */
class ManualReActLoopExecutorCompletedAnswerTest {

    @Nested
    inner class ShortText {
        @Test
        fun `150자 미만 짧은 텍스트는 완결되지 않음으로 판정`() {
            val shortText = "짧은 응답입니다. 💡 인사이트 포함"
            val result = ManualReActLoopExecutor.looksLikeCompletedAnswer(shortText)
            assertFalse(result) {
                "Text shorter than 150 chars should NOT be completed regardless of markers"
            }
        }

        @Test
        fun `빈 텍스트는 완결되지 않음으로 판정`() {
            val result = ManualReActLoopExecutor.looksLikeCompletedAnswer("")
            assertFalse(result) { "Empty text is not a completed answer" }
        }
    }

    @Nested
    inner class InsightMarker {
        @Test
        fun `150자 이상 + 💡 마커 포함 시 완결 판정`() {
            val text = "a".repeat(140) + "\n💡 인사이트: 중요한 분석 결과 포함"
            assertTrue(ManualReActLoopExecutor.looksLikeCompletedAnswer(text)) {
                "Text >= 150 chars with 💡 marker should be completed"
            }
        }

        @Test
        fun `150자 이상 + bulb emoji 텍스트 포함 시 완결 판정`() {
            val text = "a".repeat(140) + "\n:bulb: key insight with more content"
            assertTrue(ManualReActLoopExecutor.looksLikeCompletedAnswer(text)) {
                "Text >= 150 chars with :bulb: marker should be completed"
            }
        }
    }

    @Nested
    inner class MarkdownStrong {
        @Test
        fun `150자 이상 + 마크다운 강조 포함 시 완결 판정`() {
            val text = "설명 문장을 쓰고 **핵심 요약** 섹션을 포함합니다. " + "a".repeat(130)
            assertTrue(ManualReActLoopExecutor.looksLikeCompletedAnswer(text)) {
                "Text >= 150 chars with ** markdown should be completed"
            }
        }
    }

    @Nested
    inner class HttpUrl {
        @Test
        fun `150자 이상 + http URL 포함 시 완결 판정`() {
            val text = "출처 확인용 답변입니다. https://example.com/page " + "a".repeat(120)
            assertTrue(ManualReActLoopExecutor.looksLikeCompletedAnswer(text)) {
                "Text >= 150 chars with http URL should be completed"
            }
        }

        @Test
        fun `150자 이상 + https URL 포함 시 완결 판정`() {
            val text = "결과 안내 답변입니다. https://example.org/path?q=1 " + "a".repeat(120)
            assertTrue(ManualReActLoopExecutor.looksLikeCompletedAnswer(text)) {
                "Text >= 150 chars with https URL should be completed"
            }
        }
    }

    @Nested
    inner class Bullets {
        @Test
        fun `150자 이상 + 2개 이상 dash 불릿 포함 시 완결 판정`() {
            val text = "주요 항목들을 나열합니다.\n- 첫 번째 항목\n- 두 번째 항목\n- 세 번째 항목\n" +
                "a".repeat(120)
            assertTrue(ManualReActLoopExecutor.looksLikeCompletedAnswer(text)) {
                "Text >= 150 chars with 3 bullet points should be completed"
            }
        }

        @Test
        fun `150자 이상 + 번호 불릿 2개 이상 포함 시 완결 판정`() {
            val text = "순서 있는 항목을 나열합니다.\n1. 첫 번째 단계\n2. 두 번째 단계\n" +
                "a".repeat(120)
            assertTrue(ManualReActLoopExecutor.looksLikeCompletedAnswer(text)) {
                "Text >= 150 chars with 2 numbered bullets should be completed"
            }
        }

        @Test
        fun `150자 이상이지만 불릿이 1개만 있으면 완결되지 않음`() {
            val singleBullet = "설명 문장을 길게 작성합니다. ".repeat(10) + "\n- 단일 항목"
            val result = ManualReActLoopExecutor.looksLikeCompletedAnswer(singleBullet)
            assertFalse(result) {
                "Text with only 1 bullet should not be considered completed (needs 2+)"
            }
        }
    }

    @Nested
    inner class RealWorldScenarios {
        /**
         * R203 R2에서 관찰된 실제 false positive 케이스.
         * BB30 저장소 조회 실패 응답이 intent로 오감지되었던 내용을 재현.
         */
        @Test
        fun `R203 R2 BB30 저장소 조회 실패 답변은 완결로 판정`() {
            val bb30FailureAnswer = "BB30 저장소의 PR을 조회하는 데 실패했어요. " +
                "💡 'BB30' 레포를 찾을 수 없거나 API 권한이 부족해서 발생한 문제로 보입니다.\n\n" +
                "혹시 다른 저장소를 찾고 계셨다면 이름을 다시 한 번 확인해 주시겠어요? " +
                "아니면 제가 현재 접근 가능한 저장소 목록을 먼저 보여드릴까요?\n\n" +
                "출처\n- https://bitbucket.org/ihunet/BB30"
            assertTrue(ManualReActLoopExecutor.looksLikeCompletedAnswer(bb30FailureAnswer)) {
                "R203 R2 BB30 failure answer should be recognized as completed " +
                    "(has 💡, URL, and sufficient length). False positive was the R204 target."
            }
        }

        @Test
        fun `구조화된 Jira 이슈 목록 답변은 완결로 판정`() {
            val jiraAnswer = "현재 담당 중인 Jira 이슈들을 정리해 드릴게요.\n\n" +
                "### 진행 중 이슈 (총 3건)\n" +
                "- **HRFW-5695**: 엑스온스튜디오FLEX 입과요청\n" +
                "- **LND-77**: 하위 이슈 2\n" +
                "- **SETTING-104**: 잔디메신저 관련 F/U\n\n" +
                "💡 HRFW-5695의 마감일이 8월 20일로 임박해 보이니 확인이 필요해 보여요.\n\n" +
                "출처\n- https://ihunet.atlassian.net/browse/HRFW-5695"
            assertTrue(ManualReActLoopExecutor.looksLikeCompletedAnswer(jiraAnswer)) {
                "Structured Jira issue list with 💡 + URL + bullets should be completed"
            }
        }

        @Test
        fun `잠시만 기다려 주세요 짧은 예약 문구는 완결되지 않음`() {
            val reservation = "잠시만 기다려 주세요! 내 지라 티켓을 확인하고 있어요."
            assertFalse(ManualReActLoopExecutor.looksLikeCompletedAnswer(reservation)) {
                "Short reservation message is not a completed answer (should be retry target)"
            }
        }
    }
}
