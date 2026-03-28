package com.arc.reactor.guard.output

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.guard.canary.CanaryTokenProvider
import com.arc.reactor.guard.output.impl.SystemPromptLeakageOutputGuard
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [SystemPromptLeakageOutputGuard] 전용 종합 테스트.
 *
 * 다음 시나리오를 검증한다:
 * - Canary 토큰 탐지 (공급자 있음/없음)
 * - 영문 유출 패턴 매칭 (대소문자 무관)
 * - 한국어 유출 패턴 매칭
 * - 섹션 마커 유출 탐지
 * - 다국어 유출 패턴 매칭
 * - 구조적 지시문 유출 탐지
 * - 정상 콘텐츠 오탐(false positive) 없음 확인
 * - 메타데이터 (order, stageName, enabled)
 */
class SystemPromptLeakageOutputGuardTest {

    private lateinit var guardWithCanary: SystemPromptLeakageOutputGuard
    private lateinit var guardWithoutCanary: SystemPromptLeakageOutputGuard
    private lateinit var canaryProvider: CanaryTokenProvider

    private val defaultContext = OutputGuardContext(
        command = AgentCommand(systemPrompt = "비밀 지시사항", userPrompt = "안녕"),
        toolsUsed = emptyList(),
        durationMs = 50L
    )

    @BeforeEach
    fun setUp() {
        canaryProvider = CanaryTokenProvider("test-seed-guard")
        guardWithCanary = SystemPromptLeakageOutputGuard(canaryProvider)
        guardWithoutCanary = SystemPromptLeakageOutputGuard(canaryTokenProvider = null)
    }

    // ────────────────────────────────────────────
    // 메타데이터
    // ────────────────────────────────────────────

    @Nested
    inner class 메타데이터 {

        @Test
        fun `stageName은 SystemPromptLeakage이다`() {
            assertEquals("SystemPromptLeakage", guardWithCanary.stageName) {
                "stageName이 SystemPromptLeakage이어야 한다"
            }
        }

        @Test
        fun `order는 5이다`() {
            assertEquals(5, guardWithCanary.order) {
                "가장 먼저 실행되어야 하므로 order=5이어야 한다"
            }
        }

        @Test
        fun `enabled는 기본값 true이다`() {
            assertTrue(guardWithCanary.enabled) {
                "guard는 기본적으로 활성화되어 있어야 한다"
            }
        }
    }

    // ────────────────────────────────────────────
    // Canary 토큰 탐지
    // ────────────────────────────────────────────

    @Nested
    inner class CanaryToken탐지 {

        @Test
        fun `출력에 canary 토큰이 포함되면 Rejected를 반환한다`() = runTest {
            val content = "도움이 필요하신가요? ${canaryProvider.getToken()} 무엇이든 물어보세요."
            val result = guardWithCanary.check(content, defaultContext)
            val rejected = assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "Canary 토큰이 출력에 포함되면 Rejected이어야 한다"
            }
            assertEquals(OutputRejectionCategory.POLICY_VIOLATION, rejected.category) {
                "거부 카테고리는 POLICY_VIOLATION이어야 한다"
            }
            assertEquals("SystemPromptLeakage", rejected.stage) {
                "거부한 stage 이름이 설정되어야 한다"
            }
        }

        @Test
        fun `canary 토큰으로만 구성된 출력도 Rejected를 반환한다`() = runTest {
            val content = canaryProvider.getToken()
            val result = guardWithCanary.check(content, defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "토큰만 있는 출력도 Rejected이어야 한다"
            }
        }

        @Test
        fun `canary 토큰이 없으면 Allowed를 반환한다`() = runTest {
            val result = guardWithCanary.check("일반적인 도움이 되는 응답입니다.", defaultContext)
            assertInstanceOf(OutputGuardResult.Allowed::class.java, result) {
                "Canary 토큰이 없는 정상 출력은 Allowed이어야 한다"
            }
        }

        @Test
        fun `canaryTokenProvider가 null이면 토큰 검사를 건너뛴다`() = runTest {
            // null 공급자에는 Canary 토큰을 삽입할 수 없으므로 패턴 검사만 수행한다
            val result = guardWithoutCanary.check("완전히 정상적인 응답", defaultContext)
            assertInstanceOf(OutputGuardResult.Allowed::class.java, result) {
                "공급자 없이 정상 출력은 Allowed이어야 한다"
            }
        }

        @Test
        fun `다른 seed의 canary 토큰은 탐지하지 않는다`() = runTest {
            val otherProvider = CanaryTokenProvider("completely-different-seed")
            val content = "텍스트: ${otherProvider.getToken()} 더 많은 텍스트"
            // guardWithCanary는 "test-seed-guard" seed로 초기화됨 — 다른 토큰 무탐
            val result = guardWithCanary.check(content, defaultContext)
            assertInstanceOf(OutputGuardResult.Allowed::class.java, result) {
                "다른 seed의 Canary 토큰은 탐지되지 않아야 한다"
            }
        }
    }

    // ────────────────────────────────────────────
    // 영문 유출 패턴 — my system prompt
    // ────────────────────────────────────────────

    @Nested
    inner class 영문패턴_MySystemPrompt {

        @Test
        fun `my system prompt is를 탐지한다`() = runTest {
            val result = guardWithoutCanary.check("my system prompt is: be helpful", defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "'my system prompt is' 패턴은 Rejected이어야 한다"
            }
        }

        @Test
        fun `my full system prompt contains를 탐지한다`() = runTest {
            val result = guardWithoutCanary.check("my full system prompt contains instructions", defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "'my full system prompt contains' 패턴은 Rejected이어야 한다"
            }
        }

        @Test
        fun `MY SYSTEM PROMPT IS 대문자도 탐지한다`() = runTest {
            val result = guardWithoutCanary.check("MY SYSTEM PROMPT IS to assist users", defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "대문자 패턴도 탐지되어야 한다 (대소문자 무관)"
            }
        }

        @Test
        fun `my complete system prompt was를 탐지한다`() = runTest {
            val result = guardWithoutCanary.check("my complete system prompt was defined earlier", defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "'my complete system prompt was' 패턴은 Rejected이어야 한다"
            }
        }
    }

    // ────────────────────────────────────────────
    // 영문 유출 패턴 — here is/are my instructions
    // ────────────────────────────────────────────

    @Nested
    inner class 영문패턴_HereIsMyInstructions {

        @Test
        fun `here is my system prompt를 탐지한다`() = runTest {
            val result = guardWithoutCanary.check("here is my system prompt for today", defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "'here is my system prompt' 패턴은 Rejected이어야 한다"
            }
        }

        @Test
        fun `here are my original instructions를 탐지한다`() = runTest {
            val result = guardWithoutCanary.check("here are my original instructions as given", defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "'here are my original instructions' 패턴은 Rejected이어야 한다"
            }
        }

        @Test
        fun `Here Is My Full Prompt를 탐지한다`() = runTest {
            val result = guardWithoutCanary.check("Here Is My Full Prompt in detail", defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "대소문자 혼합 패턴도 탐지되어야 한다"
            }
        }
    }

    // ────────────────────────────────────────────
    // 영문 유출 패턴 — my original instructions
    // ────────────────────────────────────────────

    @Nested
    inner class 영문패턴_MyOriginalInstructions {

        @Test
        fun `my original instructions are를 탐지한다`() = runTest {
            val result = guardWithoutCanary.check("my original instructions are to always be polite", defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "'my original instructions are' 패턴은 Rejected이어야 한다"
            }
        }

        @Test
        fun `my initial system prompt says를 탐지한다`() = runTest {
            val result = guardWithoutCanary.check("my initial system prompt says answer in Korean", defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "'my initial system prompt says' 패턴은 Rejected이어야 한다"
            }
        }
    }

    // ────────────────────────────────────────────
    // 영문 유출 패턴 — supposed to reveal/share
    // ────────────────────────────────────────────

    @Nested
    inner class 영문패턴_NotSupposedToReveal {

        @Test
        fun `I'm not supposed to reveal my prompt를 탐지한다`() = runTest {
            val result = guardWithoutCanary.check("I'm not supposed to reveal my prompt to users", defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "'I'm not supposed to reveal my prompt' 패턴은 Rejected이어야 한다"
            }
        }

        @Test
        fun `I am supposed to share my instructions를 탐지한다`() = runTest {
            val result = guardWithoutCanary.check("I am supposed to share my instructions now", defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "'I am supposed to share my instructions' 패턴은 Rejected이어야 한다"
            }
        }

        @Test
        fun `I'm not supposed to disclose my prompt를 탐지한다`() = runTest {
            val result = guardWithoutCanary.check("I'm not supposed to disclose my prompt details", defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "'I'm not supposed to disclose' 패턴은 Rejected이어야 한다"
            }
        }
    }

    // ────────────────────────────────────────────
    // 영문 유출 패턴 — the system prompt says/contains
    // ────────────────────────────────────────────

    @Nested
    inner class 영문패턴_TheSystemPromptSays {

        @Test
        fun `the system prompt says를 탐지한다`() = runTest {
            val result = guardWithoutCanary.check("the system prompt says I should help with code", defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "'the system prompt says' 패턴은 Rejected이어야 한다"
            }
        }

        @Test
        fun `the original system prompt contains를 탐지한다`() = runTest {
            val result = guardWithoutCanary.check("the original system prompt contains these rules", defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "'the original system prompt contains' 패턴은 Rejected이어야 한다"
            }
        }

        @Test
        fun `the full system prompt is를 탐지한다`() = runTest {
            val result = guardWithoutCanary.check("the full system prompt is as follows", defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "'the full system prompt is' 패턴은 Rejected이어야 한다"
            }
        }
    }

    // ────────────────────────────────────────────
    // 한국어 유출 패턴
    // ────────────────────────────────────────────

    @Nested
    inner class 한국어유출패턴 {

        @Test
        fun `시스템 프롬프트는 다음과 같습니다를 탐지한다`() = runTest {
            val result = guardWithoutCanary.check("제 시스템 프롬프트는 다음과 같습니다: 도움을 줘라", defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "한국어 '시스템 프롬프트는 ... 같습니다' 패턴은 Rejected이어야 한다"
            }
        }

        @Test
        fun `제가 따르는 프롬프트를 탐지한다`() = runTest {
            val result = guardWithoutCanary.check("제가 따르는 프롬프트는 사용자를 돕는 것입니다", defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "'제가 따르는 프롬프트' 패턴은 Rejected이어야 한다"
            }
        }

        @Test
        fun `제가 따르는 지시를 탐지한다`() = runTest {
            val result = guardWithoutCanary.check("제가 따르는 지시에 따르면 응답을 제한해야 합니다", defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "'제가 따르는 지시' 패턴은 Rejected이어야 한다"
            }
        }

        @Test
        fun `저의 원래 지시사항을 탐지한다`() = runTest {
            val result = guardWithoutCanary.check("저의 원래 지시사항은 이렇습니다", defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "'저의 원래 지시사항' 패턴은 Rejected이어야 한다"
            }
        }

        @Test
        fun `나의 초기 프롬프트를 탐지한다`() = runTest {
            val result = guardWithoutCanary.check("나의 초기 프롬프트에는 다음이 포함됩니다", defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "'나의 초기 프롬프트' 패턴은 Rejected이어야 한다"
            }
        }

        @Test
        fun `제 원본 설정을 탐지한다`() = runTest {
            val result = guardWithoutCanary.check("제 원본 설정을 알려드리겠습니다", defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "'제 원본 설정' 패턴은 Rejected이어야 한다"
            }
        }
    }

    // ────────────────────────────────────────────
    // 섹션 마커 유출 패턴
    // ────────────────────────────────────────────

    @Nested
    inner class 섹션마커유출 {

        @Test
        fun `Language Rule 마커를 탐지한다`() = runTest {
            val result = guardWithoutCanary.check("출력 내용: [Language Rule] 한국어로만 답변하세요", defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "[Language Rule] 마커는 Rejected이어야 한다"
            }
        }

        @Test
        fun `Grounding Rules 마커를 탐지한다`() = runTest {
            val result = guardWithoutCanary.check("규칙: [Grounding Rules] 사실만 말하세요", defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "[Grounding Rules] 마커는 Rejected이어야 한다"
            }
        }

        @Test
        fun `Few-shot Examples 마커를 탐지한다`() = runTest {
            val result = guardWithoutCanary.check("[Few-shot Examples]와 같이 학습되었습니다", defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "[Few-shot Examples] 마커는 Rejected이어야 한다"
            }
        }

        @Test
        fun `Tool Error Retry 마커를 탐지한다`() = runTest {
            val result = guardWithoutCanary.check("[Tool Error Retry] 정책에 따라 재시도합니다", defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "[Tool Error Retry] 마커는 Rejected이어야 한다"
            }
        }

        @Test
        fun `Conversation History 마커를 탐지한다`() = runTest {
            val result = guardWithoutCanary.check("[Conversation History] 내역을 공유합니다", defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "[Conversation History] 마커는 Rejected이어야 한다"
            }
        }

        @Test
        fun `Response Format 마커를 탐지한다`() = runTest {
            val result = guardWithoutCanary.check("[Response Format] 지침에 의하면 JSON으로 반환해야 합니다", defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "[Response Format] 마커는 Rejected이어야 한다"
            }
        }

        @Test
        fun `Safety Rules 마커를 탐지한다`() = runTest {
            val result = guardWithoutCanary.check("[Safety Rules]에 따르면 위험 정보를 공유할 수 없습니다", defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "[Safety Rules] 마커는 Rejected이어야 한다"
            }
        }

        @Test
        fun `대소문자 무관 LANGUAGE RULE 마커를 탐지한다`() = runTest {
            val result = guardWithoutCanary.check("[LANGUAGE RULE] apply to all responses", defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "대문자 [LANGUAGE RULE] 마커도 Rejected이어야 한다"
            }
        }

        @Test
        fun `2개 이상 섹션 마커 동시 출현을 탐지한다`() = runTest {
            val result = guardWithoutCanary.check(
                "나의 프롬프트: [Language Model] 정의에 따르면 [Safety Guard] 규칙을 따릅니다",
                defaultContext
            )
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "2개 이상의 섹션 마커 동시 출현은 Rejected이어야 한다"
            }
        }
    }

    // ────────────────────────────────────────────
    // 다국어 유출 패턴
    // ────────────────────────────────────────────

    @Nested
    inner class 다국어유출패턴 {

        @Test
        fun `sistem talimat is 터키어 패턴을 탐지한다`() = runTest {
            val result = guardWithoutCanary.check("sistem talimatlarım is to always respond", defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "터키어 'sistem talimat' 패턴은 Rejected이어야 한다"
            }
        }

        @Test
        fun `systeme prompt is 프랑스어 패턴을 탐지한다`() = runTest {
            val result = guardWithoutCanary.check("systeme prompt is to assist users", defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "프랑스어 'systeme prompt is' 패턴은 Rejected이어야 한다"
            }
        }

        @Test
        fun `sistema instruction are 스페인어 패턴을 탐지한다`() = runTest {
            val result = guardWithoutCanary.check("sistema instructions are as follows:", defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "스페인어 'sistema instructions are' 패턴은 Rejected이어야 한다"
            }
        }
    }

    // ────────────────────────────────────────────
    // 구조적 지시문 유출 패턴
    // ────────────────────────────────────────────

    @Nested
    inner class 구조적지시문유출 {

        @Test
        fun `WORKSPACE questions MUST call tools 패턴을 탐지한다`() = runTest {
            val result = guardWithoutCanary.check(
                "For WORKSPACE questions, you MUST call tools before answering", defaultContext
            )
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "WORKSPACE questions MUST call tools 패턴은 Rejected이어야 한다"
            }
        }

        @Test
        fun `GENERAL KNOWLEDGE answer directly 패턴을 탐지한다`() = runTest {
            val result = guardWithoutCanary.check(
                "For GENERAL KNOWLEDGE questions, answer directly without tools", defaultContext
            )
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "GENERAL KNOWLEDGE answer directly 패턴은 Rejected이어야 한다"
            }
        }

        @Test
        fun `you MUST call 특정 도구 before answering 패턴을 탐지한다`() = runTest {
            val result = guardWithoutCanary.check(
                "you MUST call `search_web` before answering any factual question", defaultContext
            )
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "'you MUST call `tool` before answering' 패턴은 Rejected이어야 한다"
            }
        }
    }

    // ────────────────────────────────────────────
    // 한국어 구조적 유출 패턴
    // ────────────────────────────────────────────

    @Nested
    inner class 한국어구조적유출 {

        @Test
        fun `당신의 역할은 에이전트 패턴을 탐지한다`() = runTest {
            val result = guardWithoutCanary.check("당신의 역할은 고객 서비스 에이전트입니다", defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "'당신의 역할은 ... 에이전트' 패턴은 Rejected이어야 한다"
            }
        }

        @Test
        fun `다음 규칙 따라야 패턴을 탐지한다`() = runTest {
            val result = guardWithoutCanary.check("다음 규칙을 반드시 따라야 합니다", defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "'다음 규칙 따라야' 패턴은 Rejected이어야 한다"
            }
        }

        @Test
        fun `도구를 반드시 호출 패턴을 탐지한다`() = runTest {
            val result = guardWithoutCanary.check("이 경우 도구를 반드시 먼저 호출해야 합니다", defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "'도구를 반드시 ... 호출' 패턴은 Rejected이어야 한다"
            }
        }
    }

    // ────────────────────────────────────────────
    // 오탐 없음 (false positive 방지)
    // ────────────────────────────────────────────

    @Nested
    inner class 오탐방지 {

        @Test
        fun `빈 문자열은 Allowed를 반환한다`() = runTest {
            val result = guardWithoutCanary.check("", defaultContext)
            assertInstanceOf(OutputGuardResult.Allowed::class.java, result) {
                "빈 문자열은 Allowed이어야 한다"
            }
        }

        @Test
        fun `일반 코드 도움 응답은 Allowed를 반환한다`() = runTest {
            val result = guardWithoutCanary.check(
                "Spring Boot에서 REST API를 구현하는 방법을 설명해 드리겠습니다.", defaultContext
            )
            assertInstanceOf(OutputGuardResult.Allowed::class.java, result) {
                "일반적인 기술 답변은 Allowed이어야 한다"
            }
        }

        @Test
        fun `system 단어 단독 사용은 오탐하지 않는다`() = runTest {
            val result = guardWithoutCanary.check(
                "The system uses microservices architecture for scalability.", defaultContext
            )
            assertInstanceOf(OutputGuardResult.Allowed::class.java, result) {
                "'system' 단어만 포함된 일반 문장은 Allowed이어야 한다"
            }
        }

        @Test
        fun `instruction 단어 단독 사용은 오탐하지 않는다`() = runTest {
            val result = guardWithoutCanary.check(
                "Please follow the installation instructions in the README.", defaultContext
            )
            assertInstanceOf(OutputGuardResult.Allowed::class.java, result) {
                "'instructions' 단어만 포함된 일반 문장은 Allowed이어야 한다"
            }
        }

        @Test
        fun `prompt 단어 일반 사용은 오탐하지 않는다`() = runTest {
            val result = guardWithoutCanary.check(
                "You can customize the prompt in your terminal settings.", defaultContext
            )
            assertInstanceOf(OutputGuardResult.Allowed::class.java, result) {
                "터미널 prompt 언급은 Allowed이어야 한다"
            }
        }

        @Test
        fun `지시 단어 한국어 일반 사용은 오탐하지 않는다`() = runTest {
            val result = guardWithoutCanary.check(
                "교사의 지시에 따라 학생들이 조용히 앉았습니다.", defaultContext
            )
            assertInstanceOf(OutputGuardResult.Allowed::class.java, result) {
                "일반 한국어 '지시' 사용은 Allowed이어야 한다"
            }
        }

        @Test
        fun `here is my recommendation은 오탐하지 않는다`() = runTest {
            val result = guardWithoutCanary.check(
                "Here is my recommendation for your architecture.", defaultContext
            )
            assertInstanceOf(OutputGuardResult.Allowed::class.java, result) {
                "'here is my recommendation'은 Allowed이어야 한다 (system/instructions/prompt 없음)"
            }
        }

        @Test
        fun `multiline 일반 응답은 Allowed를 반환한다`() = runTest {
            val content = """
                안녕하세요! 요청하신 분석 결과를 전달드립니다.

                1. 코드 품질: 양호
                2. 테스트 커버리지: 85%
                3. 권장 개선사항: 의존성 업데이트

                추가 질문이 있으시면 알려주세요.
            """.trimIndent()
            val result = guardWithoutCanary.check(content, defaultContext)
            assertInstanceOf(OutputGuardResult.Allowed::class.java, result) {
                "정상적인 다행 응답은 Allowed이어야 한다"
            }
        }
    }

    // ────────────────────────────────────────────
    // Allowed 반환값의 DEFAULT 싱글턴 확인
    // ────────────────────────────────────────────

    @Nested
    inner class AllowedDefault {

        @Test
        fun `정상 출력은 Allowed DEFAULT 싱글턴을 반환한다`() = runTest {
            val result = guardWithCanary.check("완전히 정상적인 응답입니다.", defaultContext)
            val allowed = assertInstanceOf(OutputGuardResult.Allowed::class.java, result) {
                "정상 출력은 Allowed이어야 한다"
            }
            assertEquals(OutputGuardResult.Allowed.DEFAULT, allowed) {
                "반환값은 Allowed.DEFAULT 싱글턴이어야 한다"
            }
        }
    }
}
