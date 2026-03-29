package com.arc.reactor.guard.output

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.guard.canary.CanaryTokenProvider
import com.arc.reactor.guard.output.impl.OutputBlockPattern
import com.arc.reactor.guard.output.impl.PatternAction
import com.arc.reactor.guard.output.impl.PiiMaskingOutputGuard
import com.arc.reactor.guard.output.impl.RegexPatternOutputGuard
import com.arc.reactor.guard.output.impl.SystemPromptLeakageOutputGuard
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Output Guard 심층 커버리지 테스트.
 *
 * 이전 테스트 파일들이 다루지 않은 엣지 케이스를 추가 검증한다:
 *
 * 1. [SystemPromptLeakageOutputGuard] — 난독화 공격, 부분 카나리, 다행 유출 패턴
 * 2. [RegexPatternOutputGuard] — Atlassian API URL 차단, 잘못된 패턴, MASK→REJECT 순서
 * 3. [OutputGuardPipeline] — onStageComplete 콜백 정밀 검증, Modified→Rejected 연계
 * 4. [PiiMaskingOutputGuard] — 한 문장 내 주민번호+전화+카드 복합 PII, JSON 내부 PII
 */
class OutputGuardDepthCoverageTest {

    private val defaultContext = OutputGuardContext(
        command = AgentCommand(systemPrompt = "시스템 지시사항", userPrompt = "질문"),
        toolsUsed = emptyList(),
        durationMs = 100L
    )

    // ─────────────────────────────────────────────────────────────
    // 1. SystemPromptLeakageOutputGuard 추가 엣지 케이스
    // ─────────────────────────────────────────────────────────────

    @Nested
    inner class SystemPromptLeakage추가엣지 {

        private lateinit var canaryProvider: CanaryTokenProvider
        private lateinit var guardWithCanary: SystemPromptLeakageOutputGuard

        @BeforeEach
        fun setUp() {
            canaryProvider = CanaryTokenProvider("depth-coverage-seed-2025")
            guardWithCanary = SystemPromptLeakageOutputGuard(canaryProvider)
        }

        @Test
        fun `카나리 토큰이 문장 중간에 삽입되어도 탐지된다`() = runTest {
            val token = canaryProvider.getToken()
            val content = "결론적으로 답변 드리면, 우선 $token 다음과 같이 설명드립니다."
            val result = guardWithCanary.check(content, defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "카나리 토큰이 문장 중간에 있어도 반드시 Rejected이어야 한다"
            }
        }

        @Test
        fun `카나리 토큰이 줄바꿈으로 분리된 출력에서도 탐지된다`() = runTest {
            val token = canaryProvider.getToken()
            val content = "안내 드립니다.\n\n$token\n\n이상입니다."
            val result = guardWithCanary.check(content, defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "카나리 토큰이 다른 줄에 있어도 Rejected이어야 한다"
            }
        }

        @Test
        fun `부분 카나리 토큰은 탐지하지 않는다`() = runTest {
            // CANARY- 프리픽스만 있거나 앞 8자만 있는 경우 — 오탐 방지
            val token = canaryProvider.getToken()
            val partialToken = token.take(token.length / 2) // 앞 절반만 사용
            val content = "응답 내용에 $partialToken 이 포함되었습니다."
            val result = guardWithCanary.check(content, defaultContext)
            assertInstanceOf(OutputGuardResult.Allowed::class.java, result) {
                "카나리 토큰의 절반만 있는 경우 Allowed이어야 한다 (오탐 방지)"
            }
        }

        @Test
        fun `getInjectionClause 출력이 응답에 포함되면 탐지된다`() = runTest {
            // LLM이 시스템 프롬프트의 canary 지시문 전체를 그대로 출력하는 경우
            val injectionClause = canaryProvider.getInjectionClause()
            val result = guardWithCanary.check(injectionClause, defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "카나리 지시문 전체가 출력되면 Rejected이어야 한다"
            }
        }

        @Test
        fun `여러 유출 패턴이 동시에 포함되어도 첫 번째 패턴에서 즉시 Rejected된다`() = runTest {
            // 두 가지 유출 패턴을 모두 포함 — 첫 번째 탐지 즉시 종료
            val content = "My system prompt is: 도와드립니다. My original instructions are: 안전하게."
            val guard = SystemPromptLeakageOutputGuard(canaryTokenProvider = null)
            val result = guard.check(content, defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "복수 유출 패턴이 있어도 첫 번째에서 Rejected이어야 한다"
            }
        }

        @Test
        fun `빈 문자열에 대해 카나리 공급자 있어도 Allowed 반환한다`() = runTest {
            val result = guardWithCanary.check("", defaultContext)
            assertInstanceOf(OutputGuardResult.Allowed::class.java, result) {
                "빈 문자열은 카나리 공급자가 있어도 Allowed이어야 한다"
            }
        }

        @Test
        fun `stageName은 Rejected 응답의 stage 필드와 일치한다`() = runTest {
            val token = canaryProvider.getToken()
            val result = guardWithCanary.check("유출: $token", defaultContext)
            val rejected = assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "Rejected이어야 한다"
            }
            assertEquals(guardWithCanary.stageName, rejected.stage) {
                "Rejected.stage는 guardWithCanary.stageName과 일치해야 한다"
            }
        }

        @Test
        fun `매우 긴 정상 출력도 Allowed를 반환한다`() = runTest {
            // 성능 회귀 방지: 10,000자 이상의 정상 출력
            val longContent = "일반적인 기술 응답입니다. ".repeat(500)
            val guard = SystemPromptLeakageOutputGuard(canaryTokenProvider = null)
            val result = guard.check(longContent, defaultContext)
            assertInstanceOf(OutputGuardResult.Allowed::class.java, result) {
                "매우 긴 정상 출력도 Allowed이어야 한다"
            }
        }

        @Test
        fun `here is my system prompt이 한 문장에 있으면 탐지한다`() = runTest {
            val guard = SystemPromptLeakageOutputGuard(canaryTokenProvider = null)
            val content = "저는 도움을 드리겠습니다. Here is my system prompt today!"
            val result = guard.check(content, defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "유출 패턴이 일반 문장 내 포함되어도 Rejected이어야 한다"
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 2. RegexPatternOutputGuard — Atlassian API URL 차단 및 추가 케이스
    // ─────────────────────────────────────────────────────────────

    @Nested
    inner class RegexPattern_AtlassianApiUrl {

        /** Atlassian API URL 노출 차단 패턴 (REJECT 액션) */
        private fun buildAtlassianGuard(): RegexPatternOutputGuard = RegexPatternOutputGuard(
            listOf(
                OutputBlockPattern(
                    name = "AtlassianApiToken",
                    pattern = """(?i)https?://[a-z0-9\-]+\.atlassian\.net/rest/api/[^\s]+""",
                    action = PatternAction.REJECT
                )
            )
        )

        @Test
        fun `Atlassian REST API URL이 포함된 응답을 차단한다`() = runTest {
            val guard = buildAtlassianGuard()
            val content = "티켓 정보: https://mycompany.atlassian.net/rest/api/3/issue/PROJ-123"
            val result = guard.check(content, defaultContext)
            val rejected = assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "Atlassian API URL이 포함된 응답은 Rejected이어야 한다"
            }
            assertTrue(rejected.reason.contains("AtlassianApiToken")) {
                "거부 사유에 패턴명 AtlassianApiToken이 포함되어야 한다"
            }
        }

        @Test
        fun `Atlassian Cloud URL이 포함된 응답을 차단한다`() = runTest {
            val guard = buildAtlassianGuard()
            val content = "API 호출: https://acme-corp.atlassian.net/rest/api/2/search?jql=project=DEV"
            val result = guard.check(content, defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "Atlassian Cloud REST API URL은 항상 차단되어야 한다"
            }
        }

        @Test
        fun `일반 atlassian 웹 URL은 API 경로 없으면 통과한다`() = runTest {
            val guard = buildAtlassianGuard()
            val content = "Jira 보드는 https://mycompany.atlassian.net/jira/software/projects/PROJ 입니다."
            // /rest/api/ 경로가 없으므로 통과해야 한다
            val result = guard.check(content, defaultContext)
            assertInstanceOf(OutputGuardResult.Allowed::class.java, result) {
                "Atlassian 웹 URL(API 경로 없음)은 차단하지 않아야 한다"
            }
        }

        @Test
        fun `대소문자 혼합 Atlassian API URL도 차단한다`() = runTest {
            val guard = buildAtlassianGuard()
            val content = "URL: HTTPS://Company.ATLASSIAN.NET/rest/api/3/user?accountId=abc123"
            val result = guard.check(content, defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "대소문자 무관 Atlassian API URL도 차단되어야 한다"
            }
        }
    }

    @Nested
    inner class RegexPattern_추가엣지케이스 {

        @Test
        fun `MASK가 REJECT보다 목록 앞에 있어도 REJECT가 우선한다`() = runTest {
            // 목록 순서가 아닌 REJECT 로직의 즉시 반환이 우선임을 검증
            val guard = RegexPatternOutputGuard(
                listOf(
                    OutputBlockPattern(name = "MaskFirst", pattern = "maskword", action = PatternAction.MASK),
                    OutputBlockPattern(name = "RejectAfter", pattern = "blockword", action = PatternAction.REJECT)
                )
            )
            val result = guard.check("maskword then blockword here", defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "MASK 패턴이 먼저 목록에 있어도 REJECT 패턴이 있으면 Rejected이어야 한다"
            }
        }

        @Test
        fun `REJECT가 MASK보다 목록 앞에 있으면 MASK는 적용되지 않는다`() = runTest {
            val guard = RegexPatternOutputGuard(
                listOf(
                    OutputBlockPattern(name = "BlockFirst", pattern = "blockword", action = PatternAction.REJECT),
                    OutputBlockPattern(name = "MaskAfter", pattern = "maskword", action = PatternAction.MASK)
                )
            )
            val result = guard.check("maskword then blockword", defaultContext)
            // REJECT 패턴이 목록 앞에 있고 매칭되면 즉시 Rejected
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "REJECT 패턴이 목록 앞에서 매칭되면 즉시 Rejected이어야 한다"
            }
        }

        @Test
        fun `MASK만 있고 여러 개 패턴이 모두 매칭되면 모두 마스킹된다`() = runTest {
            val guard = RegexPatternOutputGuard(
                listOf(
                    OutputBlockPattern(name = "API_KEY", pattern = "api_key=[A-Za-z0-9]+", action = PatternAction.MASK),
                    OutputBlockPattern(name = "TOKEN", pattern = "token=[A-Za-z0-9]+", action = PatternAction.MASK)
                )
            )
            val content = "설정: api_key=secretABC token=bearer123"
            val result = guard.check(content, defaultContext)
            val modified = assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "두 마스킹 패턴이 모두 매칭되면 Modified이어야 한다"
            }
            assertFalse(modified.content.contains("secretABC")) { "api_key 값이 마스킹되어야 한다" }
            assertFalse(modified.content.contains("bearer123")) { "token 값이 마스킹되어야 한다" }
            assertTrue(modified.reason.contains("API_KEY")) { "이유에 API_KEY 포함되어야 한다" }
            assertTrue(modified.reason.contains("TOKEN")) { "이유에 TOKEN 포함되어야 한다" }
        }

        @Test
        fun `REJECT 패턴이 매칭되면 stage 필드가 비어있다`() = runTest {
            val guard = RegexPatternOutputGuard(
                listOf(
                    OutputBlockPattern(name = "Blocker", pattern = "secret", action = PatternAction.REJECT)
                )
            )
            val result = guard.check("이것은 secret 입니다", defaultContext)
            val rejected = assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "Rejected이어야 한다"
            }
            // RegexPatternOutputGuard는 stage를 설정하지 않는다 (OutputGuardPipeline이 설정)
            assertTrue(rejected.stage == null || rejected.stage.isEmpty()) {
                "RegexPatternOutputGuard 단독 실행 시 stage는 null 또는 빈 문자열이어야 한다"
            }
        }

        @Test
        fun `빈 content에 패턴이 없으면 Allowed를 반환한다`() = runTest {
            val guard = RegexPatternOutputGuard(
                listOf(
                    OutputBlockPattern(name = "Any", pattern = "forbidden", action = PatternAction.REJECT)
                )
            )
            val result = guard.check("", defaultContext)
            assertInstanceOf(OutputGuardResult.Allowed::class.java, result) {
                "빈 content는 어떤 패턴도 매칭되지 않으므로 Allowed이어야 한다"
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 3. OutputGuardPipeline — onStageComplete 콜백 정밀 검증
    // ─────────────────────────────────────────────────────────────

    @Nested
    inner class OutputGuardPipeline_콜백정밀검증 {

        private val actionLog = mutableListOf<Triple<String, String, String>>()

        @BeforeEach
        fun clearLog() = actionLog.clear()

        private fun buildTrackedPipeline(vararg stages: OutputGuardStage): OutputGuardPipeline =
            OutputGuardPipeline(
                stages = stages.toList(),
                onStageComplete = { stage, action, reason ->
                    actionLog.add(Triple(stage, action, reason))
                }
            )

        @Test
        fun `Allowed 단계에서 콜백이 빈 이유로 호출된다`() = runTest {
            val pipeline = buildTrackedPipeline(
                object : OutputGuardStage {
                    override val stageName = "AllowStage"
                    override val order = 10
                    override suspend fun check(content: String, context: OutputGuardContext) =
                        OutputGuardResult.Allowed.DEFAULT
                }
            )
            pipeline.check("안전한 내용", defaultContext)

            assertEquals(1, actionLog.size) { "콜백이 정확히 1회 호출되어야 한다" }
            assertEquals("AllowStage", actionLog[0].first) { "stage명이 일치해야 한다" }
            assertEquals("allowed", actionLog[0].second) { "action이 'allowed'이어야 한다" }
            assertEquals("", actionLog[0].third) { "이유가 빈 문자열이어야 한다" }
        }

        @Test
        fun `Modified 단계에서 콜백이 이유와 함께 호출된다`() = runTest {
            val pipeline = buildTrackedPipeline(
                object : OutputGuardStage {
                    override val stageName = "ModifyStage"
                    override val order = 10
                    override suspend fun check(content: String, context: OutputGuardContext) =
                        OutputGuardResult.Modified(content = "수정된 내용", reason = "PII 탐지")
                }
            )
            pipeline.check("원본 내용", defaultContext)

            assertEquals(1, actionLog.size) { "콜백이 정확히 1회 호출되어야 한다" }
            assertEquals("ModifyStage", actionLog[0].first) { "stage명이 일치해야 한다" }
            assertEquals("modified", actionLog[0].second) { "action이 'modified'이어야 한다" }
            assertEquals("PII 탐지", actionLog[0].third) { "이유가 정확히 전달되어야 한다" }
        }

        @Test
        fun `Rejected 단계에서 콜백이 이유와 함께 호출되고 이후 단계는 호출되지 않는다`() = runTest {
            val pipeline = buildTrackedPipeline(
                object : OutputGuardStage {
                    override val stageName = "AllowFirst"
                    override val order = 10
                    override suspend fun check(content: String, context: OutputGuardContext) =
                        OutputGuardResult.Allowed.DEFAULT
                },
                object : OutputGuardStage {
                    override val stageName = "RejectSecond"
                    override val order = 20
                    override suspend fun check(content: String, context: OutputGuardContext) =
                        OutputGuardResult.Rejected(
                            reason = "정책 위반",
                            category = OutputRejectionCategory.POLICY_VIOLATION
                        )
                },
                object : OutputGuardStage {
                    override val stageName = "NeverCalled"
                    override val order = 30
                    override suspend fun check(content: String, context: OutputGuardContext) =
                        OutputGuardResult.Allowed.DEFAULT
                }
            )
            pipeline.check("어떤 내용", defaultContext)

            assertEquals(2, actionLog.size) { "콜백은 2회만 호출되어야 한다 (AllowFirst + RejectSecond)" }
            assertEquals("RejectSecond", actionLog[1].first) { "두 번째 콜백은 RejectSecond여야 한다" }
            assertEquals("rejected", actionLog[1].second) { "action이 'rejected'이어야 한다" }
            assertEquals("정책 위반", actionLog[1].third) { "거부 이유가 정확히 전달되어야 한다" }
            assertTrue(actionLog.none { it.first == "NeverCalled" }) {
                "Rejected 이후 단계의 콜백은 호출되지 않아야 한다"
            }
        }

        @Test
        fun `Modified 후 Rejected가 발생하면 수정된 내용에 대해 거부된다`() = runTest {
            val contentSeenBySecondStage = mutableListOf<String>()

            val pipeline = buildTrackedPipeline(
                object : OutputGuardStage {
                    override val stageName = "Masker"
                    override val order = 10
                    override suspend fun check(content: String, context: OutputGuardContext) =
                        OutputGuardResult.Modified(content = "마스킹된 내용", reason = "PII")
                },
                object : OutputGuardStage {
                    override val stageName = "Blocker"
                    override val order = 20
                    override suspend fun check(content: String, context: OutputGuardContext): OutputGuardResult {
                        contentSeenBySecondStage.add(content)
                        return OutputGuardResult.Rejected(
                            reason = "추가 정책 위반",
                            category = OutputRejectionCategory.POLICY_VIOLATION
                        )
                    }
                }
            )
            val result = pipeline.check("원본 내용", defaultContext)

            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "Rejected이어야 한다"
            }
            assertEquals(1, contentSeenBySecondStage.size) { "두 번째 단계가 정확히 1회 실행되어야 한다" }
            assertEquals("마스킹된 내용", contentSeenBySecondStage[0]) {
                "두 번째 단계는 Modified된 내용을 받아야 한다"
            }
        }

        @Test
        fun `예외 발생 시 onStageComplete 콜백이 호출되지 않는다`() = runTest {
            val pipeline = buildTrackedPipeline(
                object : OutputGuardStage {
                    override val stageName = "Crasher"
                    override val order = 10
                    override suspend fun check(content: String, context: OutputGuardContext): OutputGuardResult {
                        throw RuntimeException("예외 발생")
                    }
                }
            )
            pipeline.check("내용", defaultContext)

            assertTrue(actionLog.isEmpty()) {
                "예외가 발생한 단계의 콜백은 호출되지 않아야 한다"
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 4. PiiMaskingOutputGuard — 복합 PII (한 문장 내 주민번호+전화+카드)
    // ─────────────────────────────────────────────────────────────

    @Nested
    inner class PiiMasking_복합PII {

        private val guard = PiiMaskingOutputGuard()

        @Test
        fun `한 문장에 주민번호, 전화번호, 카드번호가 모두 포함되면 전부 마스킹된다`() = runTest {
            val content =
                "고객 정보: 주민번호 850320-1234567, 연락처 010-9876-5432, 카드 1234-5678-9012-3456."
            val result = guard.check(content, defaultContext)
            val modified = assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "주민번호+전화번호+카드번호 복합 PII는 Modified이어야 한다"
            }
            assertFalse(modified.content.contains("850320-1234567")) { "주민등록번호가 마스킹되어야 한다" }
            assertFalse(modified.content.contains("010-9876-5432")) { "전화번호가 마스킹되어야 한다" }
            assertFalse(modified.content.contains("1234-5678-9012-3456")) { "카드번호가 마스킹되어야 한다" }
            assertTrue(modified.content.contains("******-*******")) { "주민등록번호 마스크가 있어야 한다" }
            assertTrue(modified.content.contains("***-****-****")) { "전화번호 마스크가 있어야 한다" }
            assertTrue(modified.content.contains("****-****-****-****")) { "카드번호 마스크가 있어야 한다" }
            assertTrue(modified.reason.contains("주민등록번호")) { "이유에 주민등록번호 포함되어야 한다" }
            assertTrue(modified.reason.contains("전화번호")) { "이유에 전화번호 포함되어야 한다" }
            assertTrue(modified.reason.contains("신용카드번호")) { "이유에 신용카드번호 포함되어야 한다" }
        }

        @Test
        fun `JSON 구조 내 PII도 마스킹된다`() = runTest {
            val content = """{"name":"홍길동","phone":"010-1111-2222","rrn":"900505-1234567"}"""
            val result = guard.check(content, defaultContext)
            val modified = assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "JSON 내부 PII도 Modified이어야 한다"
            }
            assertFalse(modified.content.contains("010-1111-2222")) { "JSON 내 전화번호가 마스킹되어야 한다" }
            assertFalse(modified.content.contains("900505-1234567")) { "JSON 내 주민번호가 마스킹되어야 한다" }
        }

        @Test
        fun `이메일과 카드번호가 함께 있으면 모두 마스킹된다`() = runTest {
            val content = "이메일: ceo@company.co.kr, 법인카드: 9999-8888-7777-6666"
            val result = guard.check(content, defaultContext)
            val modified = assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "이메일+카드번호 복합 PII는 Modified이어야 한다"
            }
            assertFalse(modified.content.contains("ceo@company.co.kr")) { "이메일이 마스킹되어야 한다" }
            assertFalse(modified.content.contains("9999-8888-7777-6666")) { "카드번호가 마스킹되어야 한다" }
            assertTrue(modified.reason.contains("이메일")) { "이유에 이메일 포함되어야 한다" }
            assertTrue(modified.reason.contains("신용카드번호")) { "이유에 신용카드번호 포함되어야 한다" }
        }

        @Test
        fun `문자열 시작 부분의 PII가 마스킹된다`() = runTest {
            val content = "010-5555-6666이 공개되지 않도록 주의하세요."
            val result = guard.check(content, defaultContext)
            val modified = assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "문자열 시작 부분의 PII도 Modified이어야 한다"
            }
            assertFalse(modified.content.contains("010-5555-6666")) { "시작 부분 전화번호가 마스킹되어야 한다" }
        }

        @Test
        fun `문자열 끝 부분의 PII가 마스킹된다`() = runTest {
            val content = "연락처는 다음과 같습니다: 010-3333-4444"
            val result = guard.check(content, defaultContext)
            val modified = assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "문자열 끝 부분의 PII도 Modified이어야 한다"
            }
            assertFalse(modified.content.contains("010-3333-4444")) { "끝 부분 전화번호가 마스킹되어야 한다" }
        }

        @Test
        fun `동일한 PII가 여러 번 등장해도 모두 마스킹된다`() = runTest {
            val content = "주민번호 950101-1234567을 확인하였고, 다시 한번 950101-1234567을 기록합니다."
            val result = guard.check(content, defaultContext)
            val modified = assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "중복 PII도 Modified이어야 한다"
            }
            assertFalse(modified.content.contains("950101-1234567")) {
                "동일한 PII가 반복되더라도 모두 마스킹되어야 한다"
            }
            assertFalse(
                modified.content.indexOf("******-*******") == modified.content.lastIndexOf("******-*******")
            ) {
                "마스킹 결과가 두 번 모두 나타나야 한다"
            }
        }

        @Test
        fun `마스킹 후 남은 일반 텍스트 구조가 보존된다`() = runTest {
            val content = "안내 메시지:\n- 이름: 홍길동\n- 전화: 010-7777-8888\n- 기타 정보 없음"
            val result = guard.check(content, defaultContext)
            val modified = assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "PII 포함 텍스트는 Modified이어야 한다"
            }
            assertTrue(modified.content.contains("안내 메시지:")) { "헤더 텍스트가 보존되어야 한다" }
            assertTrue(modified.content.contains("이름: 홍길동")) { "비PII 한글 텍스트가 보존되어야 한다" }
            assertTrue(modified.content.contains("기타 정보 없음")) { "마지막 라인도 보존되어야 한다" }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 5. 파이프라인 — Allowed→Modified→Rejected 우선순위 통합 검증
    // ─────────────────────────────────────────────────────────────

    @Nested
    inner class Pipeline_우선순위통합 {

        @Test
        fun `Allowed 단계 → Modified 단계 → Allowed 단계 순서면 최종 Modified를 반환한다`() = runTest {
            val pipeline = OutputGuardPipeline(
                listOf(
                    object : OutputGuardStage {
                        override val stageName = "A"
                        override val order = 10
                        override suspend fun check(content: String, context: OutputGuardContext) =
                            OutputGuardResult.Allowed.DEFAULT
                    },
                    object : OutputGuardStage {
                        override val stageName = "M"
                        override val order = 20
                        override suspend fun check(content: String, context: OutputGuardContext) =
                            OutputGuardResult.Modified(content = "변환된 내용", reason = "수정")
                    },
                    object : OutputGuardStage {
                        override val stageName = "A2"
                        override val order = 30
                        override suspend fun check(content: String, context: OutputGuardContext) =
                            OutputGuardResult.Allowed.DEFAULT
                    }
                )
            )
            val result = pipeline.check("원본", defaultContext)
            val modified = assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "Allowed→Modified→Allowed 순서에서 최종 결과는 Modified이어야 한다"
            }
            assertEquals("변환된 내용", modified.content) { "수정된 내용이 반환되어야 한다" }
            assertEquals("M", modified.stage) { "stage명이 M이어야 한다" }
        }

        @Test
        fun `Rejected가 있으면 이전 Modified 결과를 무시하고 Rejected를 반환한다`() = runTest {
            val pipeline = OutputGuardPipeline(
                listOf(
                    object : OutputGuardStage {
                        override val stageName = "Modifier"
                        override val order = 10
                        override suspend fun check(content: String, context: OutputGuardContext) =
                            OutputGuardResult.Modified(content = "PII 제거됨", reason = "마스킹")
                    },
                    object : OutputGuardStage {
                        override val stageName = "Blocker"
                        override val order = 20
                        override suspend fun check(content: String, context: OutputGuardContext) =
                            OutputGuardResult.Rejected(
                                reason = "위험 콘텐츠",
                                category = OutputRejectionCategory.HARMFUL_CONTENT
                            )
                    }
                )
            )
            val result = pipeline.check("원본", defaultContext)
            val rejected = assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "Modified 이후 Rejected가 발생하면 최종 결과는 Rejected이어야 한다"
            }
            assertEquals(OutputRejectionCategory.HARMFUL_CONTENT, rejected.category) {
                "거부 카테고리가 HARMFUL_CONTENT이어야 한다"
            }
            assertEquals("Blocker", rejected.stage) {
                "파이프라인이 Rejected.stage를 Blocker로 설정해야 한다"
            }
        }

        @Test
        fun `PiiMasking(order=10) + RegexPattern(order=20) 실제 파이프라인에서 PII 마스킹 후 패턴 검사`() = runTest {
            val pipeline = OutputGuardPipeline(
                listOf(
                    PiiMaskingOutputGuard(), // order=10
                    RegexPatternOutputGuard(  // order=20
                        listOf(
                            OutputBlockPattern(
                                name = "Forbidden",
                                pattern = "FORBIDDEN_WORD",
                                action = PatternAction.REJECT
                            )
                        )
                    )
                )
            )
            // 전화번호 + 금지어가 함께 있는 경우
            val content = "연락처: 010-1234-5678 그리고 FORBIDDEN_WORD 포함됨"
            val result = pipeline.check(content, defaultContext)
            // PiiMasking이 먼저 전화번호를 마스킹 → RegexPattern이 FORBIDDEN_WORD 탐지 → Rejected
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "PII 마스킹 후 금지어 패턴이 탐지되면 Rejected이어야 한다"
            }
        }

        @Test
        fun `비활성화된 중간 단계는 실행되지 않고 파이프라인이 정상 완료된다`() = runTest {
            var disabledStageCalled = false
            val pipeline = OutputGuardPipeline(
                listOf(
                    object : OutputGuardStage {
                        override val stageName = "Active1"
                        override val order = 10
                        override suspend fun check(content: String, context: OutputGuardContext) =
                            OutputGuardResult.Allowed.DEFAULT
                    },
                    object : OutputGuardStage {
                        override val stageName = "Disabled"
                        override val order = 20
                        override val enabled = false
                        override suspend fun check(content: String, context: OutputGuardContext): OutputGuardResult {
                            disabledStageCalled = true
                            return OutputGuardResult.Rejected(
                                reason = "비활성화되어야 하는 거부",
                                category = OutputRejectionCategory.POLICY_VIOLATION
                            )
                        }
                    },
                    object : OutputGuardStage {
                        override val stageName = "Active2"
                        override val order = 30
                        override suspend fun check(content: String, context: OutputGuardContext) =
                            OutputGuardResult.Allowed.DEFAULT
                    }
                )
            )
            val result = pipeline.check("내용", defaultContext)
            assertInstanceOf(OutputGuardResult.Allowed::class.java, result) {
                "비활성화된 Rejected 단계를 건너뛰면 최종 결과는 Allowed이어야 한다"
            }
            assertFalse(disabledStageCalled) {
                "비활성화된 단계의 check()는 호출되지 않아야 한다"
            }
        }

        @Test
        fun `SystemPromptLeakage+PiiMasking 3단계 파이프라인에서 canary 토큰이 PII보다 먼저 탐지된다`() = runTest {
            val canaryProvider = CanaryTokenProvider("pipeline-priority-test")
            val pipeline = OutputGuardPipeline(
                listOf(
                    SystemPromptLeakageOutputGuard(canaryProvider), // order=5
                    PiiMaskingOutputGuard()                          // order=10
                )
            )
            // canary 토큰 + 전화번호가 동시에 있는 경우
            val canaryToken = canaryProvider.getToken()
            val content = "토큰: $canaryToken 그리고 전화: 010-9999-0000"
            val result = pipeline.check(content, defaultContext)
            val rejected = assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "Canary 토큰 탐지가 PII 마스킹보다 먼저 실행되어야 한다"
            }
            assertEquals("SystemPromptLeakage", rejected.stage) {
                "order=5인 SystemPromptLeakage 단계에서 먼저 Rejected이어야 한다"
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 6. CanaryTokenProvider 단독 검증
    // ─────────────────────────────────────────────────────────────

    @Nested
    inner class CanaryTokenProvider추가검증 {

        @Test
        fun `같은 seed에서 항상 동일한 토큰이 생성된다`() {
            val provider1 = CanaryTokenProvider("stable-seed")
            val provider2 = CanaryTokenProvider("stable-seed")
            assertEquals(provider1.getToken(), provider2.getToken()) {
                "동일한 seed에서 생성된 토큰은 항상 같아야 한다 (결정적 생성)"
            }
        }

        @Test
        fun `다른 seed에서 항상 다른 토큰이 생성된다`() {
            val provider1 = CanaryTokenProvider("seed-alpha")
            val provider2 = CanaryTokenProvider("seed-beta")
            assertFalse(provider1.getToken() == provider2.getToken()) {
                "다른 seed에서 생성된 토큰은 달라야 한다"
            }
        }

        @Test
        fun `토큰은 CANARY- 프리픽스로 시작한다`() {
            val provider = CanaryTokenProvider("prefix-test")
            assertTrue(provider.getToken().startsWith("CANARY-")) {
                "토큰은 반드시 'CANARY-' 프리픽스로 시작해야 한다"
            }
        }

        @Test
        fun `containsToken은 정확히 일치할 때만 true를 반환한다`() {
            val provider = CanaryTokenProvider("contains-test")
            val token = provider.getToken()
            assertTrue(provider.containsToken("앞부분 $token 뒷부분")) {
                "토큰이 포함된 텍스트에서 containsToken은 true이어야 한다"
            }
            assertFalse(provider.containsToken("전혀 관계없는 텍스트")) {
                "토큰이 없는 텍스트에서 containsToken은 false이어야 한다"
            }
            assertFalse(provider.containsToken(token.drop(1))) {
                "첫 글자가 빠진 토큰은 containsToken에서 false이어야 한다"
            }
        }

        @Test
        fun `getInjectionClause는 토큰을 포함한다`() {
            val provider = CanaryTokenProvider("injection-test")
            val clause = provider.getInjectionClause()
            assertTrue(clause.contains(provider.getToken())) {
                "getInjectionClause() 결과에 실제 토큰이 포함되어야 한다"
            }
        }

        @Test
        fun `빈 seed로도 정상 토큰이 생성된다`() {
            assertDoesNotThrow {
                val provider = CanaryTokenProvider("")
                val token = provider.getToken()
                assertTrue(token.startsWith("CANARY-")) { "빈 seed로도 유효한 토큰이 생성되어야 한다" }
            }
        }
    }
}
