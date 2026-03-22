package com.arc.reactor.agent.routing

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentMode
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * DefaultAgentModeResolver 단위 테스트.
 *
 * 키워드/휴리스틱 기반 자동 모드 선택의 전체 동작을 검증한다.
 */
class DefaultAgentModeResolverTest {

    private val resolver = DefaultAgentModeResolver()
    private val sampleTools = listOf("search", "calculator", "file_reader")

    // ────────────────────────────────────────────────────
    // STANDARD 모드 선택 테스트
    // ────────────────────────────────────────────────────
    @Nested
    inner class StandardModeTests {

        @Test
        fun `도구가 없으면 STANDARD를 반환해야 한다`() = runTest {
            val command = command("검색해줘")
            val mode = resolver.resolve(command, emptyList())
            assertEquals(
                AgentMode.STANDARD, mode,
                "도구가 없으면 STANDARD여야 한다"
            )
        }

        @Test
        fun `maxToolCalls가 0이면 STANDARD를 반환해야 한다`() = runTest {
            val command = command("데이터 분석해줘", maxToolCalls = 0)
            val mode = resolver.resolve(command, sampleTools)
            assertEquals(
                AgentMode.STANDARD, mode,
                "maxToolCalls=0이면 STANDARD여야 한다"
            )
        }

        @Test
        fun `빈 쿼리는 STANDARD를 반환해야 한다`() = runTest {
            val command = command("")
            val mode = resolver.resolve(command, sampleTools)
            assertEquals(
                AgentMode.STANDARD, mode,
                "빈 쿼리는 STANDARD여야 한다"
            )
        }

        @Test
        fun `한국어 인사는 STANDARD를 반환해야 한다`() = runTest {
            val command = command("안녕")
            val mode = resolver.resolve(command, sampleTools)
            assertEquals(
                AgentMode.STANDARD, mode,
                "한국어 인사는 STANDARD여야 한다"
            )
        }

        @Test
        fun `영어 인사는 STANDARD를 반환해야 한다`() = runTest {
            val command = command("Hello!")
            val mode = resolver.resolve(command, sampleTools)
            assertEquals(
                AgentMode.STANDARD, mode,
                "영어 인사는 STANDARD여야 한다"
            )
        }

        @Test
        fun `짧은 단순 질문은 STANDARD를 반환해야 한다`() = runTest {
            val command = command("오늘 날씨 어때?")
            val mode = resolver.resolve(command, sampleTools)
            assertEquals(
                AgentMode.STANDARD, mode,
                "짧은 단순 질문은 STANDARD여야 한다"
            )
        }

        @Test
        fun `감사 인사는 STANDARD를 반환해야 한다`() = runTest {
            val command = command("감사합니다")
            val mode = resolver.resolve(command, sampleTools)
            assertEquals(
                AgentMode.STANDARD, mode,
                "감사 인사는 STANDARD여야 한다"
            )
        }

        @Test
        fun `hi는 STANDARD를 반환해야 한다`() = runTest {
            val command = command("hi")
            val mode = resolver.resolve(command, sampleTools)
            assertEquals(
                AgentMode.STANDARD, mode,
                "hi는 STANDARD여야 한다"
            )
        }

        @Test
        fun `thank you는 STANDARD를 반환해야 한다`() = runTest {
            val command = command("thank you")
            val mode = resolver.resolve(command, sampleTools)
            assertEquals(
                AgentMode.STANDARD, mode,
                "thank you는 STANDARD여야 한다"
            )
        }
    }

    // ────────────────────────────────────────────────────
    // REACT 모드 선택 테스트
    // ────────────────────────────────────────────────────
    @Nested
    inner class ReactModeTests {

        @Test
        fun `단일 도구 의도의 한국어 쿼리는 REACT를 반환해야 한다`() = runTest {
            val command = command("최근 뉴스를 검색해줘")
            val mode = resolver.resolve(command, sampleTools)
            assertEquals(
                AgentMode.REACT, mode,
                "단일 도구 의도 쿼리는 REACT여야 한다"
            )
        }

        @Test
        fun `단일 도구 의도의 영어 쿼리는 REACT를 반환해야 한다`() = runTest {
            val command = command("Search for the latest news about AI")
            val mode = resolver.resolve(command, sampleTools)
            assertEquals(
                AgentMode.REACT, mode,
                "영어 단일 도구 의도 쿼리는 REACT여야 한다"
            )
        }

        @Test
        fun `도구 키워드가 포함된 긴 질문은 REACT를 반환해야 한다`() = runTest {
            val command = command("이 데이터를 분석해서 결과를 알려줘")
            val mode = resolver.resolve(command, sampleTools)
            assertEquals(
                AgentMode.REACT, mode,
                "도구 키워드가 포함된 질문은 REACT여야 한다"
            )
        }

        @Test
        fun `계산 요청은 REACT를 반환해야 한다`() = runTest {
            val command = command("15의 팩토리얼을 계산해줘")
            val mode = resolver.resolve(command, sampleTools)
            assertEquals(
                AgentMode.REACT, mode,
                "계산 요청은 REACT여야 한다"
            )
        }

        @Test
        fun `파일 생성 요청은 REACT를 반환해야 한다`() = runTest {
            val command = command("Create a new configuration file for the project")
            val mode = resolver.resolve(command, sampleTools)
            assertEquals(
                AgentMode.REACT, mode,
                "파일 생성 요청은 REACT여야 한다"
            )
        }
    }

    // ────────────────────────────────────────────────────
    // PLAN_EXECUTE 모드 선택 테스트
    // ────────────────────────────────────────────────────
    @Nested
    inner class PlanExecuteModeTests {

        @Test
        fun `순서 키워드가 있는 한국어 쿼리는 PLAN_EXECUTE를 반환해야 한다`() =
            runTest {
                val command = command(
                    "먼저 최신 뉴스를 검색하고 그다음 요약해줘"
                )
                val mode = resolver.resolve(command, sampleTools)
                assertEquals(
                    AgentMode.PLAN_EXECUTE, mode,
                    "순서 키워드(먼저...그다음)는 PLAN_EXECUTE여야 한다"
                )
            }

        @Test
        fun `단계 키워드가 있으면 PLAN_EXECUTE를 반환해야 한다`() = runTest {
            val command = command("1단계: 데이터 수집, 2단계: 분석")
            val mode = resolver.resolve(command, sampleTools)
            assertEquals(
                AgentMode.PLAN_EXECUTE, mode,
                "단계 키워드(1단계, 2단계)는 PLAN_EXECUTE여야 한다"
            )
        }

        @Test
        fun `순서대로 키워드가 있으면 PLAN_EXECUTE를 반환해야 한다`() = runTest {
            val command = command("다음 작업을 순서대로 실행해줘: 검색, 분석, 보고서 작성")
            val mode = resolver.resolve(command, sampleTools)
            assertEquals(
                AgentMode.PLAN_EXECUTE, mode,
                "'순서대로' 키워드는 PLAN_EXECUTE여야 한다"
            )
        }

        @Test
        fun `영어 step 키워드가 있으면 PLAN_EXECUTE를 반환해야 한다`() = runTest {
            val command = command(
                "Step 1: Search for data. Step 2: Analyze results."
            )
            val mode = resolver.resolve(command, sampleTools)
            assertEquals(
                AgentMode.PLAN_EXECUTE, mode,
                "영어 Step 키워드는 PLAN_EXECUTE여야 한다"
            )
        }

        @Test
        fun `first then 패턴이 있으면 PLAN_EXECUTE를 반환해야 한다`() = runTest {
            val command = command(
                "First search the web for information, then summarize it"
            )
            val mode = resolver.resolve(command, sampleTools)
            assertEquals(
                AgentMode.PLAN_EXECUTE, mode,
                "first...then 패턴은 PLAN_EXECUTE여야 한다"
            )
        }

        @Test
        fun `복합 연결어가 2개 이상이면 PLAN_EXECUTE를 반환해야 한다`() = runTest {
            val command = command(
                "데이터를 조회하고, 그리고 분석한 후, 그다음 보고서를 생성해줘"
            )
            val mode = resolver.resolve(command, sampleTools)
            assertEquals(
                AgentMode.PLAN_EXECUTE, mode,
                "복합 연결어 2개 이상은 PLAN_EXECUTE여야 한다"
            )
        }

        @Test
        fun `번호 목록 패턴이 있으면 PLAN_EXECUTE를 반환해야 한다`() = runTest {
            val command = command(
                "1. 사용자 목록을 조회해줘\n2. 각 사용자의 활동 기록을 분석해줘"
            )
            val mode = resolver.resolve(command, sampleTools)
            assertEquals(
                AgentMode.PLAN_EXECUTE, mode,
                "번호 목록 패턴은 PLAN_EXECUTE여야 한다"
            )
        }

        @Test
        fun `단계별로 키워드가 있으면 PLAN_EXECUTE를 반환해야 한다`() = runTest {
            val command = command("이 문제를 단계별로 해결해줘: 데이터 수집부터 보고서까지")
            val mode = resolver.resolve(command, sampleTools)
            assertEquals(
                AgentMode.PLAN_EXECUTE, mode,
                "'단계별로' 키워드는 PLAN_EXECUTE여야 한다"
            )
        }

        @Test
        fun `phase 키워드가 있으면 PLAN_EXECUTE를 반환해야 한다`() = runTest {
            val command = command(
                "Execute in phase 1 data collection and phase 2 analysis"
            )
            val mode = resolver.resolve(command, sampleTools)
            assertEquals(
                AgentMode.PLAN_EXECUTE, mode,
                "phase 키워드는 PLAN_EXECUTE여야 한다"
            )
        }
    }

    // ────────────────────────────────────────────────────
    // isSimpleQuery 내부 메서드 테스트
    // ────────────────────────────────────────────────────
    @Nested
    inner class SimpleQueryDetectionTests {

        @Test
        fun `빈 문자열은 단순 쿼리다`() {
            assertTrue(
                resolver.isSimpleQuery(""),
                "빈 문자열은 단순 쿼리여야 한다"
            )
        }

        @Test
        fun `인사말은 단순 쿼리다`() {
            assertTrue(
                resolver.isSimpleQuery("안녕"),
                "인사말은 단순 쿼리여야 한다"
            )
        }

        @Test
        fun `도구 키워드가 없는 짧은 질문은 단순 쿼리다`() {
            assertTrue(
                resolver.isSimpleQuery("오늘 뭐해?"),
                "도구 키워드 없는 짧은 질문은 단순 쿼리여야 한다"
            )
        }

        @Test
        fun `도구 키워드가 있는 짧은 질문은 단순 쿼리가 아니다`() {
            assertFalse(
                resolver.isSimpleQuery("뉴스 검색해줘"),
                "도구 키워드가 있으면 단순 쿼리가 아니어야 한다"
            )
        }

        @Test
        fun `긴 질문은 단순 쿼리가 아니다`() {
            val longQuery = "이것은 매우 긴 질문입니다. " +
                "여러 가지 주제에 대해 상세하게 답변을 요청합니다."
            assertFalse(
                resolver.isSimpleQuery(longQuery),
                "긴 질문(40자 이상)은 단순 쿼리가 아니어야 한다"
            )
        }
    }

    // ────────────────────────────────────────────────────
    // isMultiStepQuery 내부 메서드 테스트
    // ────────────────────────────────────────────────────
    @Nested
    inner class MultiStepQueryDetectionTests {

        @Test
        fun `순서 키워드가 포함된 쿼리는 멀티스텝이다`() {
            assertTrue(
                resolver.isMultiStepQuery("먼저 데이터를 수집한 후 그다음 분석해줘"),
                "순서 키워드 포함 쿼리는 멀티스텝이어야 한다"
            )
        }

        @Test
        fun `단순 쿼리는 멀티스텝이 아니다`() {
            assertFalse(
                resolver.isMultiStepQuery("파일을 검색해줘"),
                "단순 쿼리는 멀티스텝이 아니어야 한다"
            )
        }

        @Test
        fun `연결어가 1개만 있으면 멀티스텝이 아니다`() {
            assertFalse(
                resolver.isMultiStepQuery("데이터를 조회하고 분석해줘"),
                "연결어 1개는 멀티스텝이 아니어야 한다"
            )
        }

        @Test
        fun `연결어가 2개 이상이면 멀티스텝이다`() {
            assertTrue(
                resolver.isMultiStepQuery(
                    "조회하고 그리고 분석한 후 그다음 보고서를 만들어"
                ),
                "연결어 2개 이상은 멀티스텝이어야 한다"
            )
        }

        @Test
        fun `번호 목록은 멀티스텝이다`() {
            assertTrue(
                resolver.isMultiStepQuery("1. 검색\n2. 분석"),
                "번호 목록은 멀티스텝이어야 한다"
            )
        }

        @Test
        fun `차례로 키워드는 멀티스텝이다`() {
            assertTrue(
                resolver.isMultiStepQuery("차례로 실행해줘: A, B, C"),
                "'차례로' 키워드는 멀티스텝이어야 한다"
            )
        }
    }

    // ────────────────────────────────────────────────────
    // 결정론성 테스트
    // ────────────────────────────────────────────────────
    @Nested
    inner class DeterminismTests {

        @Test
        fun `동일 입력에 대해 항상 같은 결과를 반환해야 한다`() = runTest {
            val command = command("먼저 검색하고 그다음 분석해줘")
            val results = (1..10).map {
                resolver.resolve(command, sampleTools)
            }
            val unique = results.toSet()
            assertEquals(
                1, unique.size,
                "동일 입력에 대해 항상 같은 결과여야 한다: $unique"
            )
        }
    }

    // ────────────────────────────────────────────────────
    // 엣지 케이스 테스트
    // ────────────────────────────────────────────────────
    @Nested
    inner class EdgeCaseTests {

        @Test
        fun `공백만 있는 쿼리는 STANDARD를 반환해야 한다`() = runTest {
            val command = command("   ")
            val mode = resolver.resolve(command, sampleTools)
            assertEquals(
                AgentMode.STANDARD, mode,
                "공백만 있는 쿼리는 STANDARD여야 한다"
            )
        }

        @Test
        fun `maxToolCalls가 음수면 STANDARD를 반환해야 한다`() = runTest {
            val command = command("데이터 분석해줘", maxToolCalls = -1)
            val mode = resolver.resolve(command, sampleTools)
            assertEquals(
                AgentMode.STANDARD, mode,
                "maxToolCalls 음수면 STANDARD여야 한다"
            )
        }

        @Test
        fun `매우 긴 단일 의도 쿼리는 REACT를 반환해야 한다`() = runTest {
            val longQuery = "다음 데이터를 검색해줘: " + "x".repeat(500)
            val command = command(longQuery)
            val mode = resolver.resolve(command, sampleTools)
            assertEquals(
                AgentMode.REACT, mode,
                "긴 단일 의도 쿼리는 REACT여야 한다"
            )
        }

        @Test
        fun `도구 1개만 사용 가능해도 REACT를 반환할 수 있다`() = runTest {
            val command = command("최근 뉴스를 검색해줘")
            val mode = resolver.resolve(command, listOf("search"))
            assertEquals(
                AgentMode.REACT, mode,
                "도구 1개여도 도구 의도가 있으면 REACT여야 한다"
            )
        }
    }

    /** 테스트용 AgentCommand 헬퍼 */
    private fun command(
        userPrompt: String,
        maxToolCalls: Int = 10
    ) = AgentCommand(
        systemPrompt = "You are helpful.",
        userPrompt = userPrompt,
        maxToolCalls = maxToolCalls
    )
}
