package com.arc.reactor.diagnostics

import com.arc.reactor.agent.metrics.EvaluationMetricsCollector
import com.arc.reactor.agent.metrics.NoOpEvaluationMetricsCollector
import com.arc.reactor.approval.ApprovalContextResolver
import com.arc.reactor.approval.AtlassianApprovalContextResolver
import com.arc.reactor.approval.RedactedApprovalContextResolver
import com.arc.reactor.autoconfigure.ApprovalPiiRedactionConfiguration
import com.arc.reactor.autoconfigure.AtlassianApprovalResolverConfiguration
import com.arc.reactor.autoconfigure.DoctorDiagnosticsConfiguration
import com.arc.reactor.autoconfigure.ToolResponseSummarizerConfiguration
import com.arc.reactor.autoconfigure.ToolResponseSummarizerPiiRedactionConfiguration
import com.arc.reactor.tool.summarize.NoOpToolResponseSummarizer
import com.arc.reactor.tool.summarize.RedactedToolResponseSummarizer
import com.arc.reactor.tool.summarize.ToolResponseSummarizer
import io.mockk.every
import io.mockk.mockk
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * [DoctorDiagnostics] 진단 서비스 테스트.
 *
 * R236: opt-in 조합별로 진단 보고서가 올바르게 생성되는지 검증한다.
 * 두 레벨의 테스트:
 *
 * 1. **Unit tests**: mockk로 ObjectProvider 주입, 4개 섹션 각각의 로직 검증
 * 2. **Integration tests**: `ApplicationContextRunner`로 실제 auto-config 조합 테스트
 */
class DoctorDiagnosticsTest {

    @Nested
    inner class DefaultState {

        @Test
        fun `모든 기능이 비활성이면 5개 섹션 중 4개 SKIPPED 1개 OK여야 한다`() {
            // Approval / Summarizer / Evaluation / ResponseCache 모두 미설정
            val doctor = DoctorDiagnostics(
                approvalResolverProvider = emptyProvider(),
                toolSummarizerProvider = emptyProvider(),
                evaluationCollectorProvider = emptyProvider(),
                responseCacheProvider = emptyProvider()
            )

            val report = doctor.runDiagnostics()

            assertEquals(5, report.sections.size) { "R238 이후 5개 섹션" }

            val approval = report.sections.find { it.name == "Approval Context Resolver" }
            assertEquals(DoctorStatus.SKIPPED, approval!!.status)

            val summarizer = report.sections.find { it.name == "Tool Response Summarizer" }
            assertEquals(DoctorStatus.SKIPPED, summarizer!!.status)

            val evaluation = report.sections.find { it.name == "Evaluation Metrics Collector" }
            assertEquals(DoctorStatus.SKIPPED, evaluation!!.status)

            val responseCache = report.sections.find { it.name == "Response Cache" }
            assertEquals(DoctorStatus.SKIPPED, responseCache!!.status) {
                "ResponseCache 빈 미등록 → SKIPPED"
            }

            val promptLayer = report.sections.find { it.name == "Prompt Layer Registry" }
            assertEquals(DoctorStatus.OK, promptLayer!!.status) {
                "Prompt Layer Registry는 항상 OK (R220 classification)"
            }
        }

        @Test
        fun `NoOp 구현체만 있어도 SKIPPED로 판단되어야 한다`() {
            val doctor = DoctorDiagnostics(
                approvalResolverProvider = emptyProvider(),
                toolSummarizerProvider = providerOf<ToolResponseSummarizer>(
                    NoOpToolResponseSummarizer
                ),
                evaluationCollectorProvider = providerOf<EvaluationMetricsCollector>(
                    NoOpEvaluationMetricsCollector
                ),
                responseCacheProvider = emptyProvider()
            )

            val report = doctor.runDiagnostics()

            assertEquals(
                DoctorStatus.SKIPPED,
                report.sections.find { it.name == "Tool Response Summarizer" }!!.status
            ) { "NoOp summarizer → SKIPPED" }

            assertEquals(
                DoctorStatus.SKIPPED,
                report.sections.find { it.name == "Evaluation Metrics Collector" }!!.status
            ) { "NoOp collector → SKIPPED" }
        }
    }

    @Nested
    inner class ApprovalSection {

        @Test
        fun `Atlassian resolver 단독이면 WARN (PII 마스킹 비활성)`() {
            val doctor = DoctorDiagnostics(
                approvalResolverProvider = providerOf<ApprovalContextResolver>(
                    AtlassianApprovalContextResolver()
                ),
                toolSummarizerProvider = emptyProvider(),
                evaluationCollectorProvider = emptyProvider(),
                responseCacheProvider = emptyProvider()
            )

            val report = doctor.runDiagnostics()
            val approval = report.sections.find { it.name == "Approval Context Resolver" }!!

            assertEquals(DoctorStatus.WARN, approval.status)
            val piiCheck = approval.checks.find { it.name == "PII 마스킹 (R228)" }
            assertNotNull(piiCheck)
            assertEquals(DoctorStatus.WARN, piiCheck!!.status)
            assertTrue(piiCheck.detail.contains("비활성")) {
                "detail에 '비활성' 표현 포함"
            }
        }

        @Test
        fun `Redacted 래핑된 resolver는 OK`() {
            val wrapped = RedactedApprovalContextResolver(AtlassianApprovalContextResolver())
            val doctor = DoctorDiagnostics(
                approvalResolverProvider = providerOf<ApprovalContextResolver>(wrapped),
                toolSummarizerProvider = emptyProvider(),
                evaluationCollectorProvider = emptyProvider(),
                responseCacheProvider = emptyProvider()
            )

            val report = doctor.runDiagnostics()
            val approval = report.sections.find { it.name == "Approval Context Resolver" }!!

            assertEquals(DoctorStatus.OK, approval.status)
            val piiCheck = approval.checks.find { it.name == "PII 마스킹 (R228)" }!!
            assertEquals(DoctorStatus.OK, piiCheck.status)
        }

        @Test
        fun `resolver 예외가 나면 sample resolve 체크에서 감지되어야 한다`() {
            val brokenResolver = mockk<ApprovalContextResolver>()
            every { brokenResolver.resolve(any(), any()) } throws RuntimeException("broken")

            val doctor = DoctorDiagnostics(
                approvalResolverProvider = providerOf<ApprovalContextResolver>(brokenResolver),
                toolSummarizerProvider = emptyProvider(),
                evaluationCollectorProvider = emptyProvider(),
                responseCacheProvider = emptyProvider()
            )

            val report = doctor.runDiagnostics()
            val approval = report.sections.find { it.name == "Approval Context Resolver" }!!

            // 예외 → sample resolve 체크가 null 반환 → WARN
            val sampleCheck = approval.checks.find { it.name == "sample resolve" }!!
            assertEquals(DoctorStatus.WARN, sampleCheck.status)
        }
    }

    @Nested
    inner class SummarizerSection {

        @Test
        fun `Default summarizer는 OK이지만 PII 마스킹 비활성이면 WARN`() {
            val default = com.arc.reactor.tool.summarize.DefaultToolResponseSummarizer()
            val doctor = DoctorDiagnostics(
                approvalResolverProvider = emptyProvider(),
                toolSummarizerProvider = providerOf<ToolResponseSummarizer>(default),
                evaluationCollectorProvider = emptyProvider(),
                responseCacheProvider = emptyProvider()
            )

            val report = doctor.runDiagnostics()
            val summarizer = report.sections.find { it.name == "Tool Response Summarizer" }!!

            assertEquals(DoctorStatus.WARN, summarizer.status) {
                "PII 마스킹 비활성 → WARN"
            }

            val sampleCheck = summarizer.checks.find { it.name == "sample summarize" }!!
            assertEquals(DoctorStatus.OK, sampleCheck.status) {
                "sample summarize 자체는 성공"
            }
        }

        @Test
        fun `Redacted 래핑된 summarizer는 OK`() {
            val wrapped = RedactedToolResponseSummarizer(
                com.arc.reactor.tool.summarize.DefaultToolResponseSummarizer()
            )
            val doctor = DoctorDiagnostics(
                approvalResolverProvider = emptyProvider(),
                toolSummarizerProvider = providerOf<ToolResponseSummarizer>(wrapped),
                evaluationCollectorProvider = emptyProvider(),
                responseCacheProvider = emptyProvider()
            )

            val report = doctor.runDiagnostics()
            val summarizer = report.sections.find { it.name == "Tool Response Summarizer" }!!

            assertEquals(DoctorStatus.OK, summarizer.status)
        }
    }

    @Nested
    inner class EvaluationSection {

        @Test
        fun `Micrometer 수집기 등록 시 OK + 카탈로그 정보 포함`() {
            val registry = SimpleMeterRegistry()
            val collector = com.arc.reactor.agent.metrics.MicrometerEvaluationMetricsCollector(
                registry
            )
            val doctor = DoctorDiagnostics(
                approvalResolverProvider = emptyProvider(),
                toolSummarizerProvider = emptyProvider(),
                evaluationCollectorProvider = providerOf<EvaluationMetricsCollector>(collector),
                responseCacheProvider = emptyProvider()
            )

            val report = doctor.runDiagnostics()
            val evaluation = report.sections.find { it.name == "Evaluation Metrics Collector" }!!

            assertEquals(DoctorStatus.OK, evaluation.status)

            val catalogCheck = evaluation.checks.find { it.name == "metric catalog (R234)" }!!
            assertTrue(catalogCheck.detail.contains("7개")) { "7개 메트릭 언급" }

            val sampleCheck = evaluation.checks.find { it.name == "sample record" }!!
            assertEquals(DoctorStatus.OK, sampleCheck.status)
        }
    }

    @Nested
    inner class ResponseCacheSection {

        @Test
        fun `NoOp 캐시는 SKIPPED로 분류되어야 한다`() {
            val noOp = com.arc.reactor.cache.impl.NoOpResponseCache()
            val doctor = DoctorDiagnostics(
                approvalResolverProvider = emptyProvider(),
                toolSummarizerProvider = emptyProvider(),
                evaluationCollectorProvider = emptyProvider(),
                responseCacheProvider = providerOf<com.arc.reactor.cache.ResponseCache>(noOp)
            )
            val report = doctor.runDiagnostics()
            val section = report.sections.find { it.name == "Response Cache" }!!

            assertEquals(DoctorStatus.SKIPPED, section.status) {
                "NoOp 캐시는 실제 캐싱 없음 → SKIPPED"
            }

            val tierCheck = section.checks.find { it.name == "cache tier" }!!
            assertEquals(DoctorStatus.SKIPPED, tierCheck.status)
            assertTrue(tierCheck.detail.contains("NoOp"))
        }

        @Test
        fun `Caffeine 캐시는 WARN으로 분류되어야 한다 (프로세스 로컬)`() {
            val caffeine = com.arc.reactor.cache.impl.CaffeineResponseCache(
                maxSize = 1000,
                ttlMinutes = 60
            )
            val doctor = DoctorDiagnostics(
                approvalResolverProvider = emptyProvider(),
                toolSummarizerProvider = emptyProvider(),
                evaluationCollectorProvider = emptyProvider(),
                responseCacheProvider = providerOf<com.arc.reactor.cache.ResponseCache>(caffeine)
            )
            val report = doctor.runDiagnostics()
            val section = report.sections.find { it.name == "Response Cache" }!!

            assertEquals(DoctorStatus.WARN, section.status) {
                "Caffeine은 프로세스 로컬 → 멀티 인스턴스 환경에서 WARN"
            }

            val tierCheck = section.checks.find { it.name == "cache tier" }!!
            assertTrue(tierCheck.detail.contains("Caffeine"))

            val semanticCheck = section.checks.find { it.name == "semantic search" }!!
            assertEquals(DoctorStatus.WARN, semanticCheck.status) {
                "Caffeine은 SemanticResponseCache 미구현 → 의미적 검색 불가"
            }
        }

        @Test
        fun `SemanticResponseCache 구현체는 OK로 분류되어야 한다`() {
            // SemanticResponseCache 인터페이스를 구현하는 stub
            val semantic = object : com.arc.reactor.cache.SemanticResponseCache {
                override suspend fun get(key: String): com.arc.reactor.cache.CachedResponse? = null

                override suspend fun put(
                    key: String,
                    response: com.arc.reactor.cache.CachedResponse
                ) { /* no-op */ }

                override fun invalidateAll() { /* no-op */ }

                override suspend fun getSemantic(
                    command: com.arc.reactor.agent.model.AgentCommand,
                    toolNames: List<String>,
                    exactKey: String
                ): com.arc.reactor.cache.CachedResponse? = null

                override suspend fun putSemantic(
                    command: com.arc.reactor.agent.model.AgentCommand,
                    toolNames: List<String>,
                    exactKey: String,
                    response: com.arc.reactor.cache.CachedResponse
                ) { /* no-op */ }
            }
            val doctor = DoctorDiagnostics(
                approvalResolverProvider = emptyProvider(),
                toolSummarizerProvider = emptyProvider(),
                evaluationCollectorProvider = emptyProvider(),
                responseCacheProvider = providerOf<com.arc.reactor.cache.ResponseCache>(semantic)
            )
            val report = doctor.runDiagnostics()
            val section = report.sections.find { it.name == "Response Cache" }!!

            assertEquals(DoctorStatus.OK, section.status)

            val semanticCheck = section.checks.find { it.name == "semantic search" }!!
            assertEquals(DoctorStatus.OK, semanticCheck.status)
            assertTrue(semanticCheck.detail.contains("활성"))

            val tierCheck = section.checks.find { it.name == "cache tier" }!!
            assertTrue(tierCheck.detail.contains("의미적"))
        }

        @Test
        fun `빈 미등록이면 SKIPPED 반환`() {
            val doctor = DoctorDiagnostics(
                approvalResolverProvider = emptyProvider(),
                toolSummarizerProvider = emptyProvider(),
                evaluationCollectorProvider = emptyProvider(),
                responseCacheProvider = emptyProvider()
            )
            val report = doctor.runDiagnostics()
            val section = report.sections.find { it.name == "Response Cache" }!!

            assertEquals(DoctorStatus.SKIPPED, section.status)
            assertTrue(section.message.contains("비활성"))
        }
    }

    @Nested
    inner class PromptLayerSection {

        @Test
        fun `Prompt Layer Registry는 기본적으로 항상 OK여야 한다`() {
            val doctor = DoctorDiagnostics(
                approvalResolverProvider = emptyProvider(),
                toolSummarizerProvider = emptyProvider(),
                evaluationCollectorProvider = emptyProvider(),
                responseCacheProvider = emptyProvider()
            )

            val report = doctor.runDiagnostics()
            val promptLayer = report.sections.find { it.name == "Prompt Layer Registry" }!!

            assertEquals(DoctorStatus.OK, promptLayer.status)

            val classifiedCheck = promptLayer.checks.find { it.name == "classified methods" }!!
            assertEquals(DoctorStatus.OK, classifiedCheck.status)

            val coverageCheck = promptLayer.checks.find { it.name == "layer coverage" }!!
            assertEquals(DoctorStatus.OK, coverageCheck.status)

            val independenceCheck = promptLayer.checks.find { it.name == "path independence" }!!
            assertEquals(DoctorStatus.OK, independenceCheck.status)
        }
    }

    @Nested
    inner class ReportHelpers {

        @Test
        fun `summary는 섹션 수와 상태 카운트를 포함해야 한다`() {
            val doctor = DoctorDiagnostics(
                approvalResolverProvider = emptyProvider(),
                toolSummarizerProvider = emptyProvider(),
                evaluationCollectorProvider = emptyProvider(),
                responseCacheProvider = emptyProvider()
            )

            val report = doctor.runDiagnostics()
            val summary = report.summary()

            assertTrue(summary.contains("5 섹션")) { "R238 이후 섹션 수" }
            assertTrue(summary.contains("OK") || summary.contains("SKIPPED")) {
                "상태 이름 포함"
            }
        }

        @Test
        fun `allHealthy는 WARN이 있으면 false여야 한다`() {
            val doctor = DoctorDiagnostics(
                approvalResolverProvider = providerOf<ApprovalContextResolver>(
                    AtlassianApprovalContextResolver()
                ),
                toolSummarizerProvider = emptyProvider(),
                evaluationCollectorProvider = emptyProvider(),
                responseCacheProvider = emptyProvider()
            )

            val report = doctor.runDiagnostics()
            // AtlassianResolver 단독 → WARN → allHealthy false
            assertFalse(report.allHealthy())
            assertTrue(report.hasWarningsOrErrors())
            assertFalse(report.hasErrors())
        }

        @Test
        fun `hasErrors는 ERROR 없으면 false여야 한다`() {
            val doctor = DoctorDiagnostics(
                approvalResolverProvider = emptyProvider(),
                toolSummarizerProvider = emptyProvider(),
                evaluationCollectorProvider = emptyProvider(),
                responseCacheProvider = emptyProvider()
            )

            val report = doctor.runDiagnostics()
            assertFalse(report.hasErrors()) {
                "SKIPPED와 OK만 있으면 hasErrors false"
            }
        }
    }

    @Nested
    inner class SafeRunBehavior {

        @Test
        fun `섹션 한 개 예외가 나도 나머지는 정상 실행되어야 한다`() {
            // 예외를 던지는 resolver mock
            val brokenResolver = mockk<ApprovalContextResolver>()
            every { brokenResolver.resolve(any(), any()) } throws
                OutOfMemoryError("simulated")  // 더 심각한 예외

            // OutOfMemoryError는 catch되지 않지만 RuntimeException은 catch됨
            // 여기서는 RuntimeException을 사용
            every { brokenResolver.resolve(any(), any()) } throws RuntimeException("boom")

            val doctor = DoctorDiagnostics(
                approvalResolverProvider = providerOf<ApprovalContextResolver>(brokenResolver),
                toolSummarizerProvider = emptyProvider(),
                evaluationCollectorProvider = emptyProvider(),
                responseCacheProvider = emptyProvider()
            )

            val report = doctor.runDiagnostics()

            // 5개 섹션 모두 존재 (R238 이후)
            assertEquals(5, report.sections.size)

            // 다른 섹션은 정상
            val promptLayer = report.sections.find { it.name == "Prompt Layer Registry" }!!
            assertEquals(DoctorStatus.OK, promptLayer.status)
        }
    }

    @Nested
    inner class AutoConfigIntegration {

        private val contextRunner = ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    ToolResponseSummarizerConfiguration::class.java,
                    ToolResponseSummarizerPiiRedactionConfiguration::class.java,
                    AtlassianApprovalResolverConfiguration::class.java,
                    ApprovalPiiRedactionConfiguration::class.java,
                    DoctorDiagnosticsConfiguration::class.java
                )
            )

        @Test
        fun `DoctorDiagnostics 빈이 기본 등록되어야 한다`() {
            contextRunner.run { context ->
                val doctor = context.getBean(DoctorDiagnostics::class.java)
                assertNotNull(doctor)
            }
        }

        @Test
        fun `Atlassian + Redaction 양쪽 활성화 시 진단이 OK여야 한다`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.approval.atlassian-resolver.enabled=true",
                    "arc.reactor.approval.pii-redaction.enabled=true"
                )
                .run { context ->
                    val doctor = context.getBean(DoctorDiagnostics::class.java)
                    val report = doctor.runDiagnostics()

                    val approval = report.sections.find {
                        it.name == "Approval Context Resolver"
                    }!!
                    assertEquals(DoctorStatus.OK, approval.status) {
                        "Atlassian + Redaction → OK (PII 마스킹 활성)"
                    }
                }
        }

        @Test
        fun `Summarizer + Redaction 양쪽 활성화 시 진단이 OK여야 한다`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.tool.response.summarizer.enabled=true",
                    "arc.reactor.tool.response.summarizer.pii-redaction.enabled=true"
                )
                .run { context ->
                    val doctor = context.getBean(DoctorDiagnostics::class.java)
                    val report = doctor.runDiagnostics()

                    val summarizer = report.sections.find {
                        it.name == "Tool Response Summarizer"
                    }!!
                    assertEquals(DoctorStatus.OK, summarizer.status) {
                        "Summarizer + Redaction → OK"
                    }
                }
        }

        @Test
        fun `전체 opt-in 활성화 시 모든 섹션 OK여야 한다`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.approval.atlassian-resolver.enabled=true",
                    "arc.reactor.approval.pii-redaction.enabled=true",
                    "arc.reactor.tool.response.summarizer.enabled=true",
                    "arc.reactor.tool.response.summarizer.pii-redaction.enabled=true"
                )
                .run { context ->
                    val doctor = context.getBean(DoctorDiagnostics::class.java)
                    val report = doctor.runDiagnostics()

                    // Approval + Summarizer + PromptLayer = OK
                    // Evaluation = SKIPPED (별도 프로퍼티)
                    assertTrue(report.allHealthy()) {
                        "모든 섹션이 OK 또는 SKIPPED여야 한다: ${report.summary()}"
                    }
                }
        }
    }

    // ── helpers ──

    private fun <T> emptyProvider(): ObjectProvider<T> {
        val provider = mockk<ObjectProvider<T>>()
        every { provider.ifAvailable } returns null
        return provider
    }

    private fun <T : Any> providerOf(instance: T): ObjectProvider<T> {
        val provider = mockk<ObjectProvider<T>>()
        every { provider.ifAvailable } returns instance
        return provider
    }
}
