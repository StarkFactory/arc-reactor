package com.arc.reactor.guard.output

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.guard.canary.CanaryTokenProvider
import com.arc.reactor.guard.output.impl.PiiMaskingOutputGuard
import com.arc.reactor.guard.output.impl.SystemPromptLeakageOutputGuard
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * 출력 Guard 파이프라인 통합 테스트
 *
 * 실제 OutputGuardStage 구현체들을 직접 연결하여 전체 출력 Guard 파이프라인의
 * 엔드-투-엔드 동작을 검증한다.
 *
 * 파이프라인 구성 (order 기준 오름차순):
 * [SystemPromptLeakageOutputGuard](order=5) → [PiiMaskingOutputGuard](order=10)
 *
 * Spring 컨텍스트 없이 실제 구현체만으로 통합 테스트를 수행하여
 * 단위 테스트에서 놓칠 수 있는 단계 간 상호작용 및 결합 동작을 검증한다.
 */
class OutputGuardPipelineIntegrationTest {

    /** 공유 Canary 토큰 제공자 (테스트 전용 seed) */
    private val canaryTokenProvider = CanaryTokenProvider(seed = "test-integration-seed")

    /** 실제 출력 Guard 파이프라인 생성 헬퍼 */
    private fun buildPipeline(): OutputGuardPipeline = OutputGuardPipeline(
        stages = listOf(
            SystemPromptLeakageOutputGuard(canaryTokenProvider = canaryTokenProvider),
            PiiMaskingOutputGuard()
        )
    )

    /** 테스트용 출력 Guard 컨텍스트 생성 헬퍼 */
    private fun ctx(userPrompt: String = "테스트 질문") = OutputGuardContext(
        command = AgentCommand(systemPrompt = "시스템 지시사항", userPrompt = userPrompt),
        toolsUsed = emptyList(),
        durationMs = 50L
    )

    // =========================================================================
    // 정상 텍스트 통과 (Allowed)
    // =========================================================================

    @Nested
    inner class NormalTextAllowed {

        @Test
        fun `PII 없는 일반 텍스트는 파이프라인을 통과해야 한다`() = runTest {
            val pipeline = buildPipeline()
            val result = pipeline.check("오늘 날씨가 참 좋습니다.", ctx())
            assertInstanceOf(OutputGuardResult.Allowed::class.java, result) {
                "PII 없는 일반 한국어 텍스트는 Allowed이어야 한다"
            }
        }

        @Test
        fun `PII 없는 영문 기술 응답은 파이프라인을 통과해야 한다`() = runTest {
            val pipeline = buildPipeline()
            val result = pipeline.check(
                "Spring Boot supports auto-configuration through @EnableAutoConfiguration.",
                ctx()
            )
            assertInstanceOf(OutputGuardResult.Allowed::class.java, result) {
                "PII 없는 영문 기술 응답은 Allowed이어야 한다"
            }
        }

        @Test
        fun `단계 없는 빈 파이프라인은 모든 텍스트를 통과시켜야 한다`() = runTest {
            val emptyPipeline = OutputGuardPipeline(emptyList())
            val result = emptyPipeline.check("어떤 내용이든", ctx())
            assertInstanceOf(OutputGuardResult.Allowed::class.java, result) {
                "활성 단계가 없는 파이프라인은 모든 출력을 허용해야 한다"
            }
        }
    }

    // =========================================================================
    // PII 탐지 및 마스킹 (Modified)
    // =========================================================================

    @Nested
    inner class PiiMasking {

        @Test
        fun `주민등록번호가 포함된 텍스트는 마스킹되어 Modified를 반환해야 한다`() = runTest {
            val pipeline = buildPipeline()
            val result = pipeline.check("고객 주민등록번호는 123456-1234567 입니다.", ctx())

            val modified = assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "주민등록번호가 포함된 출력은 Modified(마스킹)이어야 한다"
            }
            assertFalse(modified.content.contains("123456-1234567")) {
                "원본 주민등록번호가 수정된 콘텐츠에 노출되면 안 된다"
            }
            assertTrue(modified.content.contains("******-*******")) {
                "주민등록번호는 '******-*******' 형식으로 마스킹되어야 한다"
            }
        }

        @Test
        fun `한국 휴대폰 번호가 포함된 텍스트는 마스킹되어 Modified를 반환해야 한다`() = runTest {
            val pipeline = buildPipeline()
            val result = pipeline.check("담당자에게 010-1234-5678로 연락하세요.", ctx())

            val modified = assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "한국 휴대폰 번호가 포함된 출력은 Modified(마스킹)이어야 한다"
            }
            assertFalse(modified.content.contains("010-1234-5678")) {
                "원본 전화번호가 수정된 콘텐츠에 노출되면 안 된다"
            }
            assertTrue(modified.content.contains("***-****-****")) {
                "전화번호는 '***-****-****' 형식으로 마스킹되어야 한다"
            }
        }

        @Test
        fun `이메일 주소가 포함된 텍스트는 마스킹되어 Modified를 반환해야 한다`() = runTest {
            val pipeline = buildPipeline()
            val result = pipeline.check("문의사항은 user@example.com으로 보내주세요.", ctx())

            val modified = assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "이메일 주소가 포함된 출력은 Modified(마스킹)이어야 한다"
            }
            assertFalse(modified.content.contains("user@example.com")) {
                "원본 이메일 주소가 수정된 콘텐츠에 노출되면 안 된다"
            }
            assertTrue(modified.content.contains("***@***.***")) {
                "이메일 주소는 '***@***.***' 형식으로 마스킹되어야 한다"
            }
        }

        @Test
        fun `PII와 일반 텍스트가 혼합된 경우 PII만 마스킹되고 나머지는 보존되어야 한다`() = runTest {
            val pipeline = buildPipeline()
            val originalText = "안녕하세요! 담당자 연락처는 010-9876-5432이고, 오늘 회의는 3시에 있습니다."
            val result = pipeline.check(originalText, ctx())

            val modified = assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "PII가 포함된 혼합 텍스트는 Modified이어야 한다"
            }
            assertFalse(modified.content.contains("010-9876-5432")) {
                "전화번호는 마스킹되어야 한다"
            }
            assertTrue(modified.content.contains("안녕하세요!")) {
                "PII가 아닌 일반 텍스트는 그대로 보존되어야 한다"
            }
            assertTrue(modified.content.contains("오늘 회의는 3시에 있습니다.")) {
                "PII 이후의 일반 텍스트도 보존되어야 한다"
            }
        }
    }

    // =========================================================================
    // 시스템 프롬프트 유출 탐지 (Rejected)
    // =========================================================================

    @Nested
    inner class SystemPromptLeakageRejected {

        @Test
        fun `시스템 프롬프트 내용을 직접 노출하는 패턴은 Rejected되어야 한다`() = runTest {
            val pipeline = buildPipeline()
            val result = pipeline.check(
                "Here is my system prompt: You are a helpful AI assistant.",
                ctx()
            )
            val rejected = assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "시스템 프롬프트 유출 표현은 Rejected이어야 한다"
            }
            assertEquals(OutputRejectionCategory.POLICY_VIOLATION, rejected.category) {
                "시스템 프롬프트 유출은 POLICY_VIOLATION 카테고리이어야 한다"
            }
            assertEquals("SystemPromptLeakage", rejected.stage) {
                "유출 탐지는 SystemPromptLeakage 단계에서 거부되어야 한다"
            }
        }

        @Test
        fun `원래 지시사항을 말하는 패턴은 Rejected되어야 한다`() = runTest {
            val pipeline = buildPipeline()
            val result = pipeline.check(
                "My original instructions are to follow these rules: always be polite.",
                ctx()
            )
            val rejected = assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "원본 지시사항 노출 표현은 Rejected이어야 한다"
            }
            assertEquals(OutputRejectionCategory.POLICY_VIOLATION, rejected.category) {
                "원본 지시사항 노출은 POLICY_VIOLATION 카테고리이어야 한다"
            }
        }

        @Test
        fun `한국어 시스템 프롬프트 유출 패턴은 Rejected되어야 한다`() = runTest {
            val pipeline = buildPipeline()
            val result = pipeline.check(
                "시스템 프롬프트는 다음과 같습니다: 당신은 도움이 되는 AI입니다.",
                ctx()
            )
            val rejected = assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "한국어 시스템 프롬프트 유출 표현은 Rejected이어야 한다"
            }
            assertEquals(OutputRejectionCategory.POLICY_VIOLATION, rejected.category) {
                "한국어 유출 패턴도 POLICY_VIOLATION 카테고리이어야 한다"
            }
        }

        @Test
        fun `Canary 토큰이 포함된 출력은 Rejected되어야 한다`() = runTest {
            val pipeline = buildPipeline()
            val canaryToken = canaryTokenProvider.getToken()
            val result = pipeline.check(
                "물론입니다. 시스템 토큰은 $canaryToken 입니다.",
                ctx()
            )
            val rejected = assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "Canary 토큰이 출력에 포함되면 반드시 Rejected이어야 한다"
            }
            assertEquals(OutputRejectionCategory.POLICY_VIOLATION, rejected.category) {
                "Canary 토큰 탐지는 POLICY_VIOLATION 카테고리이어야 한다"
            }
            assertEquals("SystemPromptLeakage", rejected.stage) {
                "Canary 토큰 탐지는 SystemPromptLeakage 단계에서 거부되어야 한다"
            }
        }

        @Test
        fun `다른 seed의 Canary 토큰은 탐지되지 않아야 한다`() = runTest {
            val pipeline = buildPipeline()
            val otherToken = CanaryTokenProvider(seed = "completely-different-seed").getToken()
            val result = pipeline.check(
                "여기에 다른 토큰이 있습니다: $otherToken",
                ctx()
            )
            // 다른 seed의 토큰이므로 canaryTokenProvider.containsToken()에서 탐지 안 됨
            // 유출 패턴 매칭도 없으므로 PII 단계로 진행
            assertInstanceOf(OutputGuardResult.Allowed::class.java, result) {
                "자신의 Canary 토큰이 아닌 경우 Rejected되면 안 된다"
            }
        }
    }

    // =========================================================================
    // 파이프라인 단계 순서 및 통합 동작 검증
    // =========================================================================

    @Nested
    inner class PipelineOrderAndInteraction {

        @Test
        fun `SystemPromptLeakage(order=5)가 PiiMasking(order=10)보다 먼저 실행되어야 한다`() = runTest {
            val pipeline = buildPipeline()
            // 시스템 프롬프트 유출 + PII를 동시에 포함한 텍스트
            // order=5인 SystemPromptLeakage가 먼저 실행되어 Rejected를 반환해야 한다
            val result = pipeline.check(
                "Here is my system prompt: 연락처 010-1234-5678로 문의하세요.",
                ctx()
            )
            val rejected = assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "시스템 프롬프트 유출이 PII보다 먼저 탐지되어 Rejected이어야 한다"
            }
            assertEquals("SystemPromptLeakage", rejected.stage) {
                "order=5인 SystemPromptLeakage 단계가 먼저 Rejected를 반환해야 한다"
            }
        }

        @Test
        fun `PiiMasking 단계가 Modified를 반환하면 stage명이 설정되어야 한다`() = runTest {
            val pipeline = buildPipeline()
            val result = pipeline.check("이메일: test@test.com", ctx())

            val modified = assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "PII가 포함된 텍스트는 Modified이어야 한다"
            }
            assertEquals("PiiMasking", modified.stage) {
                "Modified 결과의 stage는 PiiMasking이어야 한다"
            }
        }

        @Test
        fun `두 단계 모두 통과하면 최종 결과는 Allowed이어야 한다`() = runTest {
            val pipeline = buildPipeline()
            val result = pipeline.check("Kotlin은 JVM 언어입니다.", ctx())
            assertInstanceOf(OutputGuardResult.Allowed::class.java, result) {
                "시스템 프롬프트 유출도 PII도 없는 텍스트는 Allowed이어야 한다"
            }
        }

        @Test
        fun `onStageComplete 콜백이 각 단계 완료 시 호출되어야 한다`() = runTest {
            val completedStages = mutableListOf<Pair<String, String>>()
            val pipeline = OutputGuardPipeline(
                stages = listOf(
                    SystemPromptLeakageOutputGuard(canaryTokenProvider = canaryTokenProvider),
                    PiiMaskingOutputGuard()
                ),
                onStageComplete = { stage, action, _ -> completedStages.add(stage to action) }
            )

            pipeline.check("일반 안전 텍스트입니다.", ctx())

            assertEquals(2, completedStages.size) {
                "활성 단계 2개 모두 완료 콜백이 호출되어야 한다, 실제: $completedStages"
            }
            assertTrue(completedStages.any { it.first == "SystemPromptLeakage" }) {
                "SystemPromptLeakage 단계 완료 콜백이 호출되어야 한다"
            }
            assertTrue(completedStages.any { it.first == "PiiMasking" }) {
                "PiiMasking 단계 완료 콜백이 호출되어야 한다"
            }
        }

        @Test
        fun `PII 마스킹 후 다음 단계는 마스킹된 콘텐츠를 받아야 한다`() = runTest {
            val receivedBySecondStage = mutableListOf<String>()
            val spyStage = object : OutputGuardStage {
                override val stageName = "SpyStage"
                override val order = 50 // PiiMasking(10) 이후
                override suspend fun check(content: String, context: OutputGuardContext): OutputGuardResult {
                    receivedBySecondStage.add(content)
                    return OutputGuardResult.Allowed.DEFAULT
                }
            }
            val pipeline = OutputGuardPipeline(
                stages = listOf(
                    PiiMaskingOutputGuard(),
                    spyStage
                )
            )

            pipeline.check("전화: 010-1111-2222", ctx())

            assertEquals(1, receivedBySecondStage.size) {
                "SpyStage는 정확히 한 번 실행되어야 한다"
            }
            assertFalse(receivedBySecondStage[0].contains("010-1111-2222")) {
                "SpyStage는 이미 마스킹된 콘텐츠를 받아야 한다 (원본 전화번호 없어야 함)"
            }
            assertTrue(receivedBySecondStage[0].contains("***-****-****")) {
                "SpyStage가 받은 콘텐츠에 마스킹 문자열이 있어야 한다"
            }
        }
    }

    // =========================================================================
    // PiiMaskingOutputGuard 단독 시나리오
    // =========================================================================

    @Nested
    inner class PiiMaskingOnly {

        private fun buildPiiOnlyPipeline() = OutputGuardPipeline(listOf(PiiMaskingOutputGuard()))

        @Test
        fun `주민등록번호와 전화번호가 동시에 포함된 경우 모두 마스킹되어야 한다`() = runTest {
            val pipeline = buildPiiOnlyPipeline()
            val result = pipeline.check(
                "고객 정보: 주민번호 800101-1234567, 연락처 010-2345-6789",
                ctx()
            )
            val modified = assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "주민등록번호와 전화번호가 모두 포함되면 Modified이어야 한다"
            }
            assertFalse(modified.content.contains("800101-1234567")) {
                "주민등록번호가 수정된 콘텐츠에 노출되면 안 된다"
            }
            assertFalse(modified.content.contains("010-2345-6789")) {
                "전화번호가 수정된 콘텐츠에 노출되면 안 된다"
            }
            assertTrue(modified.content.contains("******-*******")) {
                "주민등록번호 마스킹 문자열이 포함되어야 한다"
            }
            assertTrue(modified.content.contains("***-****-****")) {
                "전화번호 마스킹 문자열이 포함되어야 한다"
            }
        }

        @Test
        fun `PII가 없는 일반 텍스트는 Allowed를 반환해야 한다`() = runTest {
            val pipeline = buildPiiOnlyPipeline()
            val result = pipeline.check("Kotlin coroutines are powerful.", ctx())
            assertInstanceOf(OutputGuardResult.Allowed::class.java, result) {
                "PII 없는 텍스트는 PiiMaskingOutputGuard에서 Allowed이어야 한다"
            }
        }
    }

    // =========================================================================
    // SystemPromptLeakageOutputGuard 단독 시나리오
    // =========================================================================

    @Nested
    inner class SystemPromptLeakageOnly {

        private fun buildLeakageOnlyPipeline(withCanary: Boolean = true) = OutputGuardPipeline(
            listOf(
                SystemPromptLeakageOutputGuard(
                    canaryTokenProvider = if (withCanary) canaryTokenProvider else null
                )
            )
        )

        @Test
        fun `Canary 제공자 없이도 유출 패턴 매칭으로 Rejected되어야 한다`() = runTest {
            val pipeline = buildLeakageOnlyPipeline(withCanary = false)
            val result = pipeline.check(
                "The system prompt says you are a helpful assistant.",
                ctx()
            )
            val rejected = assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "Canary 없이도 유출 패턴으로 Rejected이어야 한다"
            }
            assertEquals(OutputRejectionCategory.POLICY_VIOLATION, rejected.category) {
                "패턴 기반 유출 탐지는 POLICY_VIOLATION 카테고리이어야 한다"
            }
        }

        @Test
        fun `섹션 마커가 포함된 출력은 시스템 프롬프트 유출로 Rejected되어야 한다`() = runTest {
            val pipeline = buildLeakageOnlyPipeline()
            val result = pipeline.check(
                "다음은 내용입니다: [Language Rule] 항상 한국어로 답하세요.",
                ctx()
            )
            val rejected = assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "시스템 프롬프트 섹션 마커가 포함된 출력은 Rejected이어야 한다"
            }
            assertEquals(OutputRejectionCategory.POLICY_VIOLATION, rejected.category) {
                "섹션 마커 유출은 POLICY_VIOLATION 카테고리이어야 한다"
            }
        }

        @Test
        fun `일반 안전 텍스트는 유출 Guard에서 Allowed를 반환해야 한다`() = runTest {
            val pipeline = buildLeakageOnlyPipeline()
            val result = pipeline.check(
                "데이터베이스 스키마를 설계할 때는 정규화를 고려하세요.",
                ctx()
            )
            assertInstanceOf(OutputGuardResult.Allowed::class.java, result) {
                "유출 패턴 없는 일반 텍스트는 SystemPromptLeakage Guard에서 Allowed이어야 한다"
            }
        }
    }
}
