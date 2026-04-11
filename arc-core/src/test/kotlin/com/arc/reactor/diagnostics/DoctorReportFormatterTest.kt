package com.arc.reactor.diagnostics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * R239: [DoctorReport]의 사람이 읽을 수 있는 포맷터 테스트.
 *
 * `toHumanReadable()`와 `toSlackMarkdown()`, `overallStatusLabel()`, `DoctorStatus`
 * 한국어 라벨/shortCode 동작을 검증한다.
 */
class DoctorReportFormatterTest {

    private val fixedTime: Instant = Instant.parse("2026-04-11T12:00:00Z")

    private fun sampleReport(vararg statuses: DoctorStatus): DoctorReport {
        val sections = statuses.mapIndexed { index, status ->
            DoctorSection(
                name = "Section $index",
                status = status,
                checks = listOf(
                    DoctorCheck("check a", status, "detail a"),
                    DoctorCheck("check b", DoctorStatus.OK, "detail b")
                ),
                message = "message $index"
            )
        }
        return DoctorReport(generatedAt = fixedTime, sections = sections)
    }

    @Nested
    inner class DoctorStatusLabels {

        @Test
        fun `koreanLabel은 4개 상태별로 올바른 한국어를 반환해야 한다`() {
            assertEquals("정상", DoctorStatus.OK.koreanLabel())
            assertEquals("비활성", DoctorStatus.SKIPPED.koreanLabel())
            assertEquals("경고", DoctorStatus.WARN.koreanLabel())
            assertEquals("오류", DoctorStatus.ERROR.koreanLabel())
        }

        @Test
        fun `shortCode는 4자 이내 축약 코드를 반환해야 한다`() {
            assertEquals("OK", DoctorStatus.OK.shortCode())
            assertEquals("SKIP", DoctorStatus.SKIPPED.shortCode())
            assertEquals("WARN", DoctorStatus.WARN.shortCode())
            assertEquals("ERR", DoctorStatus.ERROR.shortCode())
        }

        @Test
        fun `모든 shortCode는 4자 이내여야 한다`() {
            DoctorStatus.values().forEach { status ->
                assertTrue(status.shortCode().length <= 4) {
                    "${status.name} shortCode가 4자 이내: ${status.shortCode()}"
                }
            }
        }

        @Test
        fun `모든 koreanLabel은 비어있지 않아야 한다`() {
            DoctorStatus.values().forEach { status ->
                assertTrue(status.koreanLabel().isNotBlank()) {
                    "${status.name} koreanLabel이 비어있으면 안 된다"
                }
            }
        }
    }

    @Nested
    inner class OverallStatusLabel {

        @Test
        fun `모든 OK면 overall은 정상이어야 한다`() {
            val report = sampleReport(DoctorStatus.OK, DoctorStatus.OK)
            assertEquals("정상", report.overallStatusLabel())
        }

        @Test
        fun `OK와 SKIPPED 혼합이면 정상이어야 한다`() {
            val report = sampleReport(
                DoctorStatus.OK,
                DoctorStatus.SKIPPED,
                DoctorStatus.SKIPPED
            )
            assertEquals("정상", report.overallStatusLabel()) {
                "SKIPPED는 healthy에 포함"
            }
        }

        @Test
        fun `WARN이 있으면 overall은 경고 포함이어야 한다`() {
            val report = sampleReport(DoctorStatus.OK, DoctorStatus.WARN)
            assertEquals("경고 포함", report.overallStatusLabel())
        }

        @Test
        fun `ERROR가 있으면 overall은 오류 포함이어야 한다`() {
            val report = sampleReport(DoctorStatus.OK, DoctorStatus.ERROR)
            assertEquals("오류 포함", report.overallStatusLabel())
        }

        @Test
        fun `WARN과 ERROR 혼합이면 오류 포함이 우선이어야 한다`() {
            val report = sampleReport(DoctorStatus.WARN, DoctorStatus.ERROR)
            assertEquals("오류 포함", report.overallStatusLabel())
        }
    }

    @Nested
    inner class HumanReadableFormat {

        @Test
        fun `toHumanReadable은 헤더를 포함해야 한다`() {
            val report = sampleReport(DoctorStatus.OK)
            val text = report.toHumanReadable()

            assertTrue(text.contains("=== Arc Reactor Doctor Report ===")) {
                "헤더 포함"
            }
            assertTrue(text.contains("생성 시각:")) { "생성 시각 라벨" }
            assertTrue(text.contains("요약:")) { "요약 라벨" }
            assertTrue(text.contains("전체 상태:")) { "전체 상태 라벨" }
        }

        @Test
        fun `toHumanReadable은 generatedAt을 포함해야 한다`() {
            val report = sampleReport(DoctorStatus.OK)
            val text = report.toHumanReadable()
            assertTrue(text.contains("2026-04-11T12:00:00Z")) {
                "fixedTime이 출력에 포함"
            }
        }

        @Test
        fun `toHumanReadable은 각 섹션의 shortCode와 이름을 포함해야 한다`() {
            val report = sampleReport(DoctorStatus.OK, DoctorStatus.WARN)
            val text = report.toHumanReadable()

            assertTrue(text.contains("[OK] Section 0"))
            assertTrue(text.contains("[WARN] Section 1"))
        }

        @Test
        fun `toHumanReadable은 섹션 메시지를 포함해야 한다`() {
            val report = sampleReport(DoctorStatus.OK)
            val text = report.toHumanReadable()
            assertTrue(text.contains("message 0"))
        }

        @Test
        fun `includeDetails=true이면 모든 check가 포함되어야 한다`() {
            val report = sampleReport(DoctorStatus.WARN)
            val text = report.toHumanReadable(includeDetails = true)

            assertTrue(text.contains("check a")) { "check a 이름" }
            assertTrue(text.contains("detail a")) { "check a detail" }
            assertTrue(text.contains("check b")) { "check b 이름" }
            assertTrue(text.contains("detail b")) { "check b detail" }
        }

        @Test
        fun `includeDetails=false이면 check가 포함되지 않아야 한다`() {
            val report = sampleReport(DoctorStatus.WARN)
            val text = report.toHumanReadable(includeDetails = false)

            assertFalse(text.contains("check a")) { "check a 이름 제외" }
            assertFalse(text.contains("detail a")) { "check a detail 제외" }
            // 섹션 message는 여전히 포함
            assertTrue(text.contains("message 0"))
        }

        @Test
        fun `커스텀 lineSeparator가 적용되어야 한다`() {
            val report = sampleReport(DoctorStatus.OK)
            val text = report.toHumanReadable(lineSeparator = "\r\n")
            assertTrue(text.contains("\r\n")) { "CRLF 줄바꿈 포함" }
            // 각 논리적 라인이 CRLF로 끝나므로 "===" 다음에도 \r\n이 붙어 있어야 함
            assertTrue(text.contains("===\r\n")) {
                "헤더 뒤 CRLF — 라인 구분자가 일관되게 적용되어야 한다"
            }
        }

        @Test
        fun `전체 상태 라벨이 헤더에 포함되어야 한다`() {
            val okReport = sampleReport(DoctorStatus.OK)
            assertTrue(okReport.toHumanReadable().contains("전체 상태: 정상"))

            val warnReport = sampleReport(DoctorStatus.OK, DoctorStatus.WARN)
            assertTrue(warnReport.toHumanReadable().contains("전체 상태: 경고 포함"))

            val errorReport = sampleReport(DoctorStatus.ERROR)
            assertTrue(errorReport.toHumanReadable().contains("전체 상태: 오류 포함"))
        }

        @Test
        fun `빈 섹션 리스트도 정상 처리되어야 한다`() {
            val empty = DoctorReport(generatedAt = fixedTime, sections = emptyList())
            val text = empty.toHumanReadable()
            assertTrue(text.contains("=== Arc Reactor Doctor Report ==="))
            assertTrue(text.contains("0 섹션"))
        }
    }

    @Nested
    inner class SlackMarkdownFormat {

        @Test
        fun `Slack 포맷은 bold 타이틀을 포함해야 한다`() {
            val report = sampleReport(DoctorStatus.OK)
            val text = report.toSlackMarkdown()
            assertTrue(text.contains("*Arc Reactor Doctor Report*")) {
                "Slack mrkdwn bold 타이틀"
            }
        }

        @Test
        fun `Slack 포맷은 summary를 quote 형식으로 포함해야 한다`() {
            val report = sampleReport(DoctorStatus.OK, DoctorStatus.WARN)
            val text = report.toSlackMarkdown()
            assertTrue(text.contains("> 2 섹션")) { "Slack mrkdwn quote" }
        }

        @Test
        fun `Slack 포맷은 각 섹션을 한 줄로 표현해야 한다`() {
            val report = sampleReport(
                DoctorStatus.OK,
                DoctorStatus.WARN,
                DoctorStatus.SKIPPED
            )
            val text = report.toSlackMarkdown()

            // 각 섹션이 shortCode + 이름 + message를 포함
            assertTrue(text.contains("`[OK]`"))
            assertTrue(text.contains("`[WARN]`"))
            assertTrue(text.contains("`[SKIP]`"))
            assertTrue(text.contains("*Section 0*"))
            assertTrue(text.contains("*Section 1*"))
            assertTrue(text.contains("*Section 2*"))
        }

        @Test
        fun `Slack 포맷은 각 섹션을 em dash로 구분해야 한다`() {
            val report = sampleReport(DoctorStatus.OK)
            val text = report.toSlackMarkdown()
            assertTrue(text.contains(" — ")) {
                "섹션 이름과 메시지 사이 em dash"
            }
        }

        @Test
        fun `Slack 포맷은 상세 check를 포함하지 않아야 한다 (축약)`() {
            val report = sampleReport(DoctorStatus.WARN)
            val text = report.toSlackMarkdown()
            assertFalse(text.contains("check a")) {
                "Slack 축약 포맷은 개별 check 제외"
            }
            assertFalse(text.contains("detail a")) {
                "Slack 축약 포맷은 개별 detail 제외"
            }
        }

        @Test
        fun `Slack 포맷은 후행 공백이 없어야 한다`() {
            val report = sampleReport(DoctorStatus.OK)
            val text = report.toSlackMarkdown()
            assertFalse(text.endsWith("\n")) { "trimEnd 적용" }
            assertFalse(text.endsWith(" ")) { "trailing space 없음" }
        }

        @Test
        fun `빈 섹션 리스트도 정상 처리되어야 한다`() {
            val empty = DoctorReport(generatedAt = fixedTime, sections = emptyList())
            val text = empty.toSlackMarkdown()
            assertTrue(text.contains("*Arc Reactor Doctor Report*"))
            assertTrue(text.contains("> 0 섹션"))
        }
    }

    @Nested
    inner class RealisticScenarios {

        @Test
        fun `프로덕션 정상 상태 시나리오`() {
            val report = DoctorReport(
                generatedAt = fixedTime,
                sections = listOf(
                    DoctorSection(
                        name = "Approval Context Resolver",
                        status = DoctorStatus.OK,
                        checks = listOf(
                            DoctorCheck("resolver bean", DoctorStatus.OK, "등록됨: RedactedApprovalContextResolver"),
                            DoctorCheck("PII 마스킹", DoctorStatus.OK, "활성")
                        ),
                        message = "활성 (RedactedApprovalContextResolver)"
                    ),
                    DoctorSection(
                        name = "Response Cache",
                        status = DoctorStatus.OK,
                        checks = emptyList(),
                        message = "활성 (RedisSemanticResponseCache, tier=semantic)"
                    )
                )
            )

            val human = report.toHumanReadable()
            assertTrue(human.contains("전체 상태: 정상"))
            assertTrue(human.contains("[OK] Approval Context Resolver"))
            assertTrue(human.contains("[OK] Response Cache"))
            assertTrue(human.contains("RedactedApprovalContextResolver"))
            assertTrue(human.contains("RedisSemanticResponseCache"))

            val slack = report.toSlackMarkdown()
            assertTrue(slack.contains("*Arc Reactor Doctor Report*"))
            assertTrue(slack.contains("`[OK]` *Approval Context Resolver*"))
            assertTrue(slack.contains("`[OK]` *Response Cache*"))
        }

        @Test
        fun `PII 마스킹 누락 경고 시나리오`() {
            val report = DoctorReport(
                generatedAt = fixedTime,
                sections = listOf(
                    DoctorSection(
                        name = "Approval Context Resolver",
                        status = DoctorStatus.WARN,
                        checks = listOf(
                            DoctorCheck("PII 마스킹 (R228)", DoctorStatus.WARN,
                                "비활성 — 감사 로그에 PII 노출 위험")
                        ),
                        message = "활성 (AtlassianApprovalContextResolver)"
                    )
                )
            )

            val human = report.toHumanReadable()
            assertTrue(human.contains("전체 상태: 경고 포함"))
            assertTrue(human.contains("[WARN] Approval Context Resolver"))
            assertTrue(human.contains("PII 노출 위험"))

            val slack = report.toSlackMarkdown()
            assertTrue(slack.contains("`[WARN]`"))
        }

        @Test
        fun `5개 섹션 혼합 시나리오 (실제 R238 가능 상태)`() {
            val report = sampleReport(
                DoctorStatus.OK,        // Approval
                DoctorStatus.OK,        // Summarizer
                DoctorStatus.SKIPPED,   // Evaluation
                DoctorStatus.WARN,      // Response Cache (Caffeine)
                DoctorStatus.OK         // Prompt Layer
            )

            val human = report.toHumanReadable()
            assertTrue(human.contains("5 섹션"))
            assertTrue(human.contains("전체 상태: 경고 포함"))
            // 모든 5개 상태 shortCode가 섹션 헤더에 등장
            assertTrue(human.contains("[OK] Section 0"))
            assertTrue(human.contains("[OK] Section 1"))
            assertTrue(human.contains("[SKIP] Section 2"))
            assertTrue(human.contains("[WARN] Section 3"))
            assertTrue(human.contains("[OK] Section 4"))
        }
    }

    @Nested
    inner class R262ObservabilityAssetsGoldenScenarios {

        /**
         * R261 collector 활성 상태에서 `DoctorDiagnostics.runDiagnostics()`가 만들어낼
         * 6 섹션 보고서를 충실히 재현한 실제 모양 골든 fixture.
         *
         * 시간 순서: Approval → Summarizer → Evaluation → Prompt Layer → Response Cache
         * → **Observability Assets (R261)**.
         */
        private fun sixSectionProductionFixture(): DoctorReport = DoctorReport(
            generatedAt = fixedTime,
            sections = listOf(
                DoctorSection(
                    name = "Approval Context Resolver",
                    status = DoctorStatus.OK,
                    checks = listOf(
                        DoctorCheck("resolver bean", DoctorStatus.OK,
                            "등록됨: RedactedApprovalContextResolver")
                    ),
                    message = "활성 (RedactedApprovalContextResolver)"
                ),
                DoctorSection(
                    name = "Tool Response Summarizer",
                    status = DoctorStatus.OK,
                    checks = listOf(
                        DoctorCheck("summarizer bean", DoctorStatus.OK,
                            "등록됨: RedactedToolResponseSummarizer")
                    ),
                    message = "활성 (RedactedToolResponseSummarizer)"
                ),
                DoctorSection(
                    name = "Evaluation Metrics Collector",
                    status = DoctorStatus.OK,
                    checks = listOf(
                        DoctorCheck("collector bean", DoctorStatus.OK,
                            "등록됨: MicrometerEvaluationMetricsCollector"),
                        DoctorCheck("metric catalog (R234)", DoctorStatus.OK, "9개 메트릭 등록")
                    ),
                    message = "활성 (MicrometerEvaluationMetricsCollector) — 9개 메트릭"
                ),
                DoctorSection(
                    name = "Prompt Layer Registry",
                    status = DoctorStatus.OK,
                    checks = listOf(
                        DoctorCheck("classified methods", DoctorStatus.OK,
                            "20개 메서드 분류됨 (main 12 / planning 8)")
                    ),
                    message = "무결성 확인됨 (20개 메서드 / 6개 계층)"
                ),
                DoctorSection(
                    name = "Response Cache",
                    status = DoctorStatus.OK,
                    checks = listOf(
                        DoctorCheck("cache tier", DoctorStatus.OK,
                            "의미적 캐시 구현체 — 프로덕션 권장 백엔드")
                    ),
                    message = "활성 (RedisSemanticResponseCache, tier=semantic)"
                ),
                // R261 6번째 섹션
                DoctorSection(
                    name = "Observability Assets",
                    status = DoctorStatus.OK,
                    checks = ObservabilityAssetsCatalog.all.map { asset ->
                        DoctorCheck(
                            name = "${asset.round} ${asset.kind}",
                            status = DoctorStatus.OK,
                            detail = "${asset.path} — ${asset.description}"
                        )
                    },
                    message = "3개 자산 사용 가능 (R256/R259/R260)"
                )
            )
        )

        @Test
        fun `R262 toHumanReadable은 6개 섹션을 모두 포함해야 한다`() {
            val report = sixSectionProductionFixture()
            val human = report.toHumanReadable()

            assertTrue(human.contains("6 섹션")) {
                "summary에 R261 이후 섹션 수 6 포함"
            }
            assertTrue(human.contains("전체 상태: 정상")) {
                "모두 OK인 시나리오의 overall label"
            }

            // 6개 섹션이 정확한 순서로 등장
            val sectionsInOrder = listOf(
                "[OK] Approval Context Resolver",
                "[OK] Tool Response Summarizer",
                "[OK] Evaluation Metrics Collector",
                "[OK] Prompt Layer Registry",
                "[OK] Response Cache",
                "[OK] Observability Assets"
            )
            sectionsInOrder.forEach { line ->
                assertTrue(human.contains(line)) {
                    "human-readable에 '$line' 라인 포함"
                }
            }
        }

        @Test
        fun `R262 toHumanReadable은 R256 R259 R260 자산 라벨을 모두 노출해야 한다`() {
            val report = sixSectionProductionFixture()
            val human = report.toHumanReadable(includeDetails = true)

            // 카탈로그의 모든 라운드 라벨이 detail 섹션에 등장
            assertTrue(human.contains("R256 playbook")) {
                "R256 playbook 체크 이름 포함"
            }
            assertTrue(human.contains("R259 dashboard")) {
                "R259 dashboard 체크 이름 포함"
            }
            assertTrue(human.contains("R260 alerts")) {
                "R260 alerts 체크 이름 포함"
            }

            // 자산 path가 detail에 포함
            assertTrue(human.contains("docs/alertmanager-rules.yaml")) {
                "R260 자산의 YAML 경로 포함"
            }
            assertTrue(human.contains("docs/evaluation-metrics.md")) {
                "R256/R259 자산의 markdown 경로 포함"
            }

            // section message가 명시적으로 포함
            assertTrue(human.contains("3개 자산 사용 가능 (R256/R259/R260)")) {
                "section message 포함"
            }
        }

        @Test
        fun `R262 toSlackMarkdown은 6개 섹션을 한 줄씩 노출해야 한다`() {
            val report = sixSectionProductionFixture()
            val slack = report.toSlackMarkdown()

            // 6 섹션 summary
            assertTrue(slack.contains("> 6 섹션")) {
                "Slack quote에 6 섹션 표시"
            }

            // 모든 섹션이 OK 라벨로 한 줄씩
            val expectedLines = listOf(
                "`[OK]` *Approval Context Resolver*",
                "`[OK]` *Tool Response Summarizer*",
                "`[OK]` *Evaluation Metrics Collector*",
                "`[OK]` *Prompt Layer Registry*",
                "`[OK]` *Response Cache*",
                "`[OK]` *Observability Assets*"
            )
            expectedLines.forEach { line ->
                assertTrue(slack.contains(line)) {
                    "Slack 출력에 '$line' 포함"
                }
            }

            // 새 섹션의 message가 em dash 뒤에 위치
            assertTrue(slack.contains("*Observability Assets* — 3개 자산 사용 가능 (R256/R259/R260)")) {
                "Observability Assets section의 message가 em dash 뒤에 노출"
            }
        }

        @Test
        fun `R262 toSlackMarkdown은 자산 detail을 포함하지 않아야 한다 (축약 유지)`() {
            // R261 섹션은 검증 가능한 detail 3개를 가지지만 Slack 축약 포맷은 detail을 노출하지 않음
            val report = sixSectionProductionFixture()
            val slack = report.toSlackMarkdown()

            // detail 라인은 빠져야 한다
            assertFalse(slack.contains("R256 playbook")) {
                "Slack 축약: 카탈로그 체크 이름 미노출"
            }
            assertFalse(slack.contains("alertmanager-rules.yaml")) {
                "Slack 축약: 카탈로그 path 미노출"
            }
        }

        @Test
        fun `R262 collector 비활성 시나리오 - 6번째 섹션은 SKIPPED`() {
            // collector 비활성 + 다른 섹션 모두 비활성
            val report = DoctorReport(
                generatedAt = fixedTime,
                sections = listOf(
                    DoctorSection("Approval Context Resolver", DoctorStatus.SKIPPED,
                        emptyList(), "비활성"),
                    DoctorSection("Tool Response Summarizer", DoctorStatus.SKIPPED,
                        emptyList(), "비활성"),
                    DoctorSection("Evaluation Metrics Collector", DoctorStatus.SKIPPED,
                        emptyList(), "비활성"),
                    DoctorSection("Prompt Layer Registry", DoctorStatus.OK,
                        emptyList(), "무결성 확인됨"),
                    DoctorSection("Response Cache", DoctorStatus.SKIPPED,
                        emptyList(), "비활성 — 빈 미등록"),
                    // R261 6번째 섹션이 SKIPPED 상태
                    DoctorSection("Observability Assets", DoctorStatus.SKIPPED,
                        listOf(
                            DoctorCheck("metrics enabled", DoctorStatus.SKIPPED,
                                "EvaluationMetricsCollector 비활성 — 운영 자산 안내 무관")
                        ),
                        "비활성 — evaluation metrics 활성화 후 자산 적용 가능")
                )
            )

            val human = report.toHumanReadable()
            assertTrue(human.contains("6 섹션"))
            assertTrue(human.contains("전체 상태: 정상")) {
                "SKIPPED + OK 조합은 정상으로 분류"
            }
            assertTrue(human.contains("[SKIP] Observability Assets")) {
                "Observability Assets shortCode SKIP"
            }
            assertTrue(human.contains("evaluation metrics 활성화 후 자산 적용 가능")) {
                "비활성 message 포함"
            }

            val slack = report.toSlackMarkdown()
            assertTrue(slack.contains("`[SKIP]` *Observability Assets*"))
            assertTrue(slack.contains("> 6 섹션"))
        }

        @Test
        fun `R262 ObservabilityAssetsCatalog 변경이 골든 시나리오에 자동 반영되어야 한다`() {
            // 골든 fixture가 ObservabilityAssetsCatalog.all을 동적으로 사용하므로
            // 카탈로그에 자산이 추가되면 fixture 체크 수도 자동으로 늘어난다
            val report = sixSectionProductionFixture()
            val observability = report.sections.find { it.name == "Observability Assets" }!!

            assertEquals(ObservabilityAssetsCatalog.all.size, observability.checks.size) {
                "fixture의 체크 수가 카탈로그 자산 수와 일치"
            }

            // 모든 카탈로그 자산이 fixture에 등장
            ObservabilityAssetsCatalog.all.forEach { asset ->
                val matchingCheck = observability.checks.find {
                    it.name == "${asset.round} ${asset.kind}"
                }
                assertTrue(matchingCheck != null) {
                    "fixture에 ${asset.round} ${asset.kind} 체크가 존재"
                }
                assertTrue(matchingCheck!!.detail.contains(asset.path)) {
                    "fixture detail에 자산 path 포함"
                }
            }
        }
    }
}
