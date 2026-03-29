package com.arc.reactor.hardening

import com.arc.reactor.guard.impl.DefaultInjectionDetectionStage
import com.arc.reactor.guard.impl.DefaultInputValidationStage
import com.arc.reactor.guard.impl.GuardPipeline
import com.arc.reactor.guard.impl.UnicodeNormalizationStage
import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Guard false-positive 회귀 방지 테스트.
 *
 * [DefaultInjectionDetectionStage]의 패턴 추가·수정 시 정상 업무 질문이
 * 오탐(false positive)으로 차단되지 않는지 검증한다.
 *
 * 검증 대상 6개 카테고리:
 * 1. "프롬프트" 포함 정상 질문 — 프롬프트 엔지니어링 학습 맥락
 * 2. "시스템" 포함 정상 질문 — IT 인프라·아키텍처 업무 맥락
 * 3. "설정/역할/지시" 포함 정상 질문 — 프로젝트 관리 맥락
 * 4. 보안 관련 정상 질문 — 방어적 보안 학습 맥락
 * 5. "무시/잊어" 포함 정상 질문 — 소프트웨어 개발 맥락
 * 6. 영문 정상 질문 — 개발자 기술 질문 맥락
 *
 * InjectionEvasionHardeningTest.FalsePositivePrevention 과 중복 없음:
 * 해당 섹션은 발음 부호·전각 문자·Base64 유사 입력 등 우회 형태 유사 케이스를 다루며,
 * 본 테스트는 키워드("프롬프트", "시스템", "무시" 등) 자체를 포함하는 업무 질문에 집중한다.
 *
 * @see DefaultInjectionDetectionStage 검사 대상 Stage
 * @see com.arc.reactor.guard.InjectionPatterns 탐지 패턴 정의
 */
@Tag("hardening")
class GuardFalsePositiveRegressionTest {

    /** 테스트용 Guard 파이프라인: 유니코드 정규화 → 입력 검증 → 인젝션 탐지 */
    private val guardPipeline = GuardPipeline(
        listOf(
            UnicodeNormalizationStage(),
            DefaultInputValidationStage(maxLength = 10000, minLength = 1),
            DefaultInjectionDetectionStage()
        )
    )

    private fun guardCommand(text: String) = GuardCommand(userId = "fp-regression-test", text = text)

    // =========================================================================
    // 카테고리 1: "프롬프트" 포함 정상 업무 질문
    // =========================================================================

    /**
     * "프롬프트"라는 단어가 포함되더라도 학습·업무 맥락이면 허용해야 한다.
     *
     * 탐지 위험 패턴: role_override, system_delimiter, prompt_override 등
     */
    @Nested
    inner class PromptKeywordNormalQuestions {

        @ParameterizedTest(name = "프롬프트 키워드 정상 질문 통과: {0}")
        @ValueSource(strings = [
            "프롬프트 엔지니어링 기법을 알려줘",
            "좋은 프롬프트 작성법이 뭐야?",
            "GPT 프롬프트 최적화 방법",
            "프롬프트 관련 Jira 이슈 찾아줘"
        ])
        fun `프롬프트 키워드가 포함된 정상 업무 질문은 허용되어야 한다`(input: String) = runTest {
            val result = guardPipeline.guard(guardCommand(input))
            assertInstanceOf(
                GuardResult.Allowed::class.java, result,
                "정상적인 프롬프트 관련 질문이 오탐으로 차단됨: '$input'"
            )
        }
    }

    // =========================================================================
    // 카테고리 2: "시스템" 포함 정상 업무 질문
    // =========================================================================

    /**
     * "시스템"이라는 단어가 포함되더라도 IT 아키텍처·인프라 맥락이면 허용해야 한다.
     *
     * 탐지 위험 패턴: system_delimiter (SYSTEM: 패턴), korean_role_override 등
     */
    @Nested
    inner class SystemKeywordNormalQuestions {

        @ParameterizedTest(name = "시스템 키워드 정상 질문 통과: {0}")
        @ValueSource(strings = [
            "시스템 아키텍처 문서 검색해줘",
            "시스템 모니터링 방법을 알려줘",
            "시스템 요구사항 정리해줘",
            "우리 시스템의 성능을 개선하고 싶어"
        ])
        fun `시스템 키워드가 포함된 정상 IT 업무 질문은 허용되어야 한다`(input: String) = runTest {
            val result = guardPipeline.guard(guardCommand(input))
            assertInstanceOf(
                GuardResult.Allowed::class.java, result,
                "정상적인 시스템 관련 질문이 오탐으로 차단됨: '$input'"
            )
        }
    }

    // =========================================================================
    // 카테고리 3: "설정/역할/지시" 포함 정상 업무 질문
    // =========================================================================

    /**
     * 프로젝트 관리·조직 운영 맥락에서 쓰이는 "역할", "지시", "설정" 키워드는
     * 인젝션 패턴의 "role", "instruction" 계열과 형태가 비슷하지만 정상 질문이어야 한다.
     *
     * 탐지 위험 패턴: role_override, new (role|persona) 등
     */
    @Nested
    inner class RoleConfigInstructionNormalQuestions {

        @ParameterizedTest(name = "설정/역할/지시 키워드 정상 질문 통과: {0}")
        @ValueSource(strings = [
            "Spring Boot 설정 방법 알려줘",
            "팀원 역할 분담을 어떻게 하면 좋을까?",
            "신입 사원에게 지시사항을 정리하고 싶어",
            "프로젝트 설정 파일을 검토해줘"
        ])
        fun `역할과 지시 키워드가 포함된 정상 업무 질문은 허용되어야 한다`(input: String) = runTest {
            val result = guardPipeline.guard(guardCommand(input))
            assertInstanceOf(
                GuardResult.Allowed::class.java, result,
                "정상적인 역할/지시 관련 질문이 오탐으로 차단됨: '$input'"
            )
        }
    }

    // =========================================================================
    // 카테고리 4: 보안 관련 정상 업무 질문
    // =========================================================================

    /**
     * "보안", "인젝션", "SQL 인젝션" 등의 단어가 포함된 방어적 보안 학습 질문은
     * 허용되어야 한다. 공격 코드가 없는 개념·정책 질문이다.
     *
     * 탐지 위험 패턴: override safety/content/security filter 등
     */
    @Nested
    inner class SecurityTopicNormalQuestions {

        @ParameterizedTest(name = "보안 주제 정상 질문 통과: {0}")
        @ValueSource(strings = [
            "보안 정책 문서를 찾아줘",
            "OWASP Top 10이 뭐야?",
            "SQL 인젝션 방어 방법 알려줘",
            "API 보안 best practice"
        ])
        fun `방어적 보안 학습 질문은 허용되어야 한다`(input: String) = runTest {
            val result = guardPipeline.guard(guardCommand(input))
            assertInstanceOf(
                GuardResult.Allowed::class.java, result,
                "정상적인 보안 학습 질문이 오탐으로 차단됨: '$input'"
            )
        }
    }

    // =========================================================================
    // 카테고리 5: "무시/잊어" 포함 정상 업무 질문
    // =========================================================================

    /**
     * "무시", "잊어"가 포함되어도 소프트웨어 개발 맥락(에러 무시, 경고 무시,
     * 비밀번호 분실 복구)이면 허용되어야 한다.
     *
     * 탐지 위험 패턴: role_override "ignore/forget", korean_role_override 무시/잊어
     * 주의: 한국어 패턴은 "앞의/이전/위의 + 대화/지시/명령 + 무시/잊어"의 3-토큰 조합만 차단.
     * "에러를 무시", "경고를 무시"는 해당 패턴에 해당하지 않아 통과해야 한다.
     */
    @Nested
    inner class IgnoreForgetNormalQuestions {

        @ParameterizedTest(name = "무시/잊어 키워드 정상 질문 통과: {0}")
        @ValueSource(strings = [
            "이 에러를 무시해도 되나요?",
            "deprecated 경고를 무시하는 방법",
            "비밀번호를 잊어버렸을 때 복구 방법",
            "이전 버전과의 호환성을 무시할 수 있나?"
        ])
        fun `무시 또는 잊어 키워드가 포함된 개발 관련 질문은 허용되어야 한다`(input: String) = runTest {
            val result = guardPipeline.guard(guardCommand(input))
            assertInstanceOf(
                GuardResult.Allowed::class.java, result,
                "정상적인 무시/잊어 키워드 포함 개발 질문이 오탐으로 차단됨: '$input'"
            )
        }
    }

    // =========================================================================
    // 카테고리 6: 영문 정상 업무 질문
    // =========================================================================

    /**
     * 영문 환경에서 자주 쓰이는 개발자 기술 질문이 허용되어야 한다.
     * 특히 "DAN"이 단독 패턴이 아닌 설명적 맥락("DAN pattern in testing")으로
     * 쓰인 경우도 정상 질문으로 통과해야 한다.
     *
     * 탐지 위험 패턴: role_override (DAN), system_delimiter, prompt_override
     * DAN 패턴: "do anything now|act as DAN|you are DAN|DAN mode|be DAN|DAN jailbreak|pretend.*DAN"
     * → "role of DAN pattern in testing"은 어떤 DAN 구문과도 일치하지 않아 통과해야 한다.
     */
    @Nested
    inner class EnglishNormalQuestions {

        @ParameterizedTest(name = "영문 정상 질문 통과: {0}")
        @ValueSource(strings = [
            "How to ignore deprecated warnings in Kotlin?",
            "What is prompt engineering?",
            "Can you show me the system architecture?",
            "Explain the role of DAN pattern in testing"
        ])
        fun `영문 정상 업무 질문은 허용되어야 한다`(input: String) = runTest {
            val result = guardPipeline.guard(guardCommand(input))
            assertInstanceOf(
                GuardResult.Allowed::class.java, result,
                "정상적인 영문 기술 질문이 오탐으로 차단됨: '$input'"
            )
        }
    }
}
