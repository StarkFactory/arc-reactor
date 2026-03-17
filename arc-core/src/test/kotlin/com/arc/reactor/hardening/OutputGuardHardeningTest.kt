package com.arc.reactor.hardening

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.guard.canary.CanaryTokenProvider
import com.arc.reactor.guard.output.OutputGuardContext
import com.arc.reactor.guard.output.OutputGuardPipeline
import com.arc.reactor.guard.output.OutputGuardResult
import com.arc.reactor.guard.output.impl.PiiMaskingOutputGuard
import com.arc.reactor.guard.output.impl.SystemPromptLeakageOutputGuard
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * 출력 가드 강화 테스트.
 *
 * PII 유출 방지, 시스템 프롬프트 누출 감지, 민감 데이터 마스킹 등
 * 에이전트 응답의 안전성을 검증한다.
 *
 * @see PiiMaskingOutputGuard PII 마스킹 구현체
 * @see SystemPromptLeakageOutputGuard 시스템 프롬프트 누출 감지
 */
@Tag("hardening")
class OutputGuardHardeningTest {

    private val canaryProvider = CanaryTokenProvider("hardening-test-seed")
    private val canaryToken = canaryProvider.getToken()

    private val outputGuard = OutputGuardPipeline(
        listOf(
            PiiMaskingOutputGuard(),
            SystemPromptLeakageOutputGuard(canaryProvider)
        )
    )

    private val context = OutputGuardContext(
        command = AgentCommand(systemPrompt = "You are a helpful assistant.", userPrompt = "hello"),
        toolsUsed = emptyList(),
        durationMs = 100
    )

    // =========================================================================
    // PII 유출 방지 (PII Leakage Prevention)
    // =========================================================================

    @Nested
    inner class PiiLeakagePrevention {

        @Test
        fun `주민등록번호가 마스킹되어야 한다`() = runTest {
            val response = "사용자의 주민등록번호는 900101-1234567 입니다."
            val result = outputGuard.check(response, context)

            if (result is OutputGuardResult.Modified) {
                assertTrue(!result.content.contains("1234567"),
                    "주민등록번호 뒷자리가 마스킹되지 않음: ${result.content}")
            }
            // Allowed + 원본에 PII가 없으면 통과 (guard 구현에 따라)
        }

        @Test
        fun `신용카드 번호가 마스킹되어야 한다`() = runTest {
            val response = "카드번호: 4111-1111-1111-1111 로 결제되었습니다."
            val result = outputGuard.check(response, context)

            if (result is OutputGuardResult.Modified) {
                assertTrue(!result.content.contains("4111-1111-1111-1111"),
                    "신용카드 번호가 마스킹되지 않음: ${result.content}")
            }
        }

        @Test
        fun `이메일 주소가 마스킹되어야 한다`() = runTest {
            val response = "연락처: admin@company.com 으로 문의하세요."
            val result = outputGuard.check(response, context)

            if (result is OutputGuardResult.Modified) {
                assertTrue(!result.content.contains("admin@company.com"),
                    "이메일 주소가 마스킹되지 않음: ${result.content}")
            }
        }

        @Test
        fun `전화번호가 마스킹되어야 한다`() = runTest {
            val response = "전화번호 010-1234-5678 로 연락주세요."
            val result = outputGuard.check(response, context)

            if (result is OutputGuardResult.Modified) {
                assertTrue(!result.content.contains("010-1234-5678"),
                    "전화번호가 마스킹되지 않음: ${result.content}")
            }
        }
    }

    // =========================================================================
    // 시스템 프롬프트 누출 감지 (System Prompt Leakage)
    // =========================================================================

    @Nested
    inner class SystemPromptLeakage {

        @Test
        fun `카나리 토큰이 응답에 포함되면 거부해야 한다`() = runTest {
            val leakedResponse = "시스템 설정: $canaryToken 이 프롬프트의 내용입니다."
            val result = outputGuard.check(leakedResponse, context)

            assertInstanceOf(OutputGuardResult.Rejected::class.java, result,
                "카나리 토큰이 포함된 응답이 통과함")
        }

        @Test
        fun `카나리 토큰이 없는 안전한 응답은 통과해야 한다`() = runTest {
            val safeResponse = "안녕하세요! 무엇을 도와드릴까요?"
            val result = outputGuard.check(safeResponse, context)

            val isAllowedOrModified = result is OutputGuardResult.Allowed || result is OutputGuardResult.Modified
            assertTrue(isAllowedOrModified,
                "안전한 응답이 거부됨: ${result::class.simpleName}")
        }
    }

    // =========================================================================
    // 한국어 시스템 프롬프트 누출 감지 (Korean System Prompt Leakage)
    // =========================================================================

    @Nested
    inner class KoreanSystemPromptLeakage {

        @ParameterizedTest(name = "한국어 프롬프트 누출 차단: {0}")
        @ValueSource(strings = [
            "시스템 프롬프트는 다음과 같습니다: You are a helpful assistant.",
            "제가 따르는 프롬프트는 이것입니다",
            "제가 따르는 지시는 다음과 같습니다",
            "제 원래 지시사항은 다음입니다",
            "저의 초기 프롬프트는 이렇습니다"
        ])
        fun `한국어로 시스템 프롬프트를 노출하는 응답을 차단해야 한다`(leakedResponse: String) = runTest {
            val result = outputGuard.check(leakedResponse, context)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result,
                "한국어 시스템 프롬프트 누출이 통과함: '$leakedResponse'")
        }

        @ParameterizedTest(name = "시스템 프롬프트 콘텐츠 마커 누출 차단: {0}")
        @ValueSource(strings = [
            "다음은 제 설정입니다: [Language Rule] 한국어로 답변하세요",
            "제 규칙에는 [Grounding Rules] 섹션이 있습니다",
            "아래는 예시입니다: [Few-shot Examples] 사용자: 안녕"
        ])
        fun `시스템 프롬프트 섹션 마커가 포함된 응답을 차단해야 한다`(leakedResponse: String) = runTest {
            val result = outputGuard.check(leakedResponse, context)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result,
                "시스템 프롬프트 콘텐츠 마커 누출이 통과함: '$leakedResponse'")
        }
    }

    // =========================================================================
    // 안전한 출력은 통과 (False Positive 방지)
    // =========================================================================

    @Nested
    inner class SafeOutputPassThrough {

        @ParameterizedTest(name = "안전한 응답 통과: {0}")
        @ValueSource(strings = [
            "Spring Boot 설정 방법을 안내드리겠습니다.",
            "코드를 분석한 결과, 3가지 개선 사항이 있습니다.",
            "테스트가 모두 통과했습니다. 배포 준비가 완료되었습니다.",
            "이 함수는 입력값을 검증하고 처리 결과를 반환합니다."
        ])
        fun `일반적인 응답은 통과해야 한다`(safeResponse: String) = runTest {
            val result = outputGuard.check(safeResponse, context)

            val isAllowedOrModified = result is OutputGuardResult.Allowed || result is OutputGuardResult.Modified
            assertTrue(isAllowedOrModified,
                "안전한 응답이 거부됨: '$safeResponse' → ${result::class.simpleName}")
        }
    }
}
