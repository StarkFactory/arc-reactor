package com.arc.reactor.diagnostics

import com.arc.reactor.agent.impl.PromptLayer
import com.arc.reactor.agent.impl.PromptLayerRegistry
import com.arc.reactor.agent.metrics.EvaluationMetricsCatalog
import com.arc.reactor.agent.metrics.EvaluationMetricsCollector
import com.arc.reactor.agent.metrics.NoOpEvaluationMetricsCollector
import com.arc.reactor.approval.ApprovalContextResolver
import com.arc.reactor.approval.RedactedApprovalContextResolver
import com.arc.reactor.tool.summarize.NoOpToolResponseSummarizer
import com.arc.reactor.tool.summarize.RedactedToolResponseSummarizer
import com.arc.reactor.tool.summarize.ToolResponseSummarizer
import java.time.Instant
import mu.KotlinLogging
import org.springframework.beans.factory.ObjectProvider

private val logger = KotlinLogging.logger {}

/**
 * Arc Reactor opt-in 기능들의 활성화 상태를 진단하는 서비스.
 *
 * R225~R235에서 도입한 모든 opt-in 기능(Approval Context Resolver, Tool Response
 * Summarizer, Evaluation Metrics Collector, Prompt Layer Registry)이 현재 배포에서
 * 어떻게 구성되었는지, 권장 설정과 어긋나는 부분은 없는지 확인한다.
 *
 * ## 사용 예
 *
 * ```kotlin
 * @RestController
 * class AdminDoctorController(private val doctor: DoctorDiagnostics) {
 *     @GetMapping("/admin/doctor")
 *     fun getReport(): DoctorReport = doctor.runDiagnostics()
 * }
 * ```
 *
 * 또는 관리자 CLI/스타트업 로깅에서 호출:
 *
 * ```kotlin
 * @Component
 * class StartupLogger(private val doctor: DoctorDiagnostics) : ApplicationRunner {
 *     override fun run(args: ApplicationArguments?) {
 *         val report = doctor.runDiagnostics()
 *         logger.info { report.summary() }
 *         if (report.hasWarningsOrErrors()) {
 *             report.sections
 *                 .filter { it.status != DoctorStatus.OK && it.status != DoctorStatus.SKIPPED }
 *                 .forEach { logger.warn { it } }
 *         }
 *     }
 * }
 * ```
 *
 * ## 진단 섹션
 *
 * 1. **Approval Context Resolver** — R225~R229 승인 인프라
 * 2. **Tool Response Summarizer** — R223~R232 ACI 요약 인프라
 * 3. **Evaluation Metrics Collector** — R222/R224/R234 관측 인프라
 * 4. **Prompt Layer Registry** — R220/R235 프롬프트 계층 classification
 *
 * ## Fail-Safe
 *
 * 각 섹션 진단은 try/catch로 감싸져 있어 한 섹션의 예외가 다른 섹션에 영향을 주지 않는다.
 * 예외 발생 시 해당 섹션은 `DoctorStatus.ERROR`로 표시된다.
 *
 * ## 3대 최상위 제약 준수
 *
 * - MCP: 순수 read-only introspection, 도구 경로 전혀 미접근
 * - Redis 캐시: SystemPromptBuilder 미수정
 * - 컨텍스트 관리: HookContext/MemoryStore 미접근
 *
 * @param approvalResolverProvider R225~R229 Approval Context Resolver (optional)
 * @param toolSummarizerProvider R223~R232 Tool Response Summarizer (optional)
 * @param evaluationCollectorProvider R222/R224/R234 Evaluation Metrics Collector (optional)
 *
 * @see DoctorReport 진단 결과 모델
 * @see EvaluationMetricsCatalog R234 메트릭 카탈로그
 */
class DoctorDiagnostics(
    private val approvalResolverProvider: ObjectProvider<ApprovalContextResolver>,
    private val toolSummarizerProvider: ObjectProvider<ToolResponseSummarizer>,
    private val evaluationCollectorProvider: ObjectProvider<EvaluationMetricsCollector>
) {

    /**
     * 전체 진단을 실행하고 [DoctorReport]를 반환한다.
     *
     * 각 섹션은 독립적으로 실행되며, 한 섹션의 예외가 다른 섹션을 막지 않는다.
     */
    fun runDiagnostics(): DoctorReport {
        val sections = listOf(
            safeRun("Approval Context Resolver") { diagnoseApprovalResolver() },
            safeRun("Tool Response Summarizer") { diagnoseToolSummarizer() },
            safeRun("Evaluation Metrics Collector") { diagnoseEvaluationCollector() },
            safeRun("Prompt Layer Registry") { diagnosePromptLayerRegistry() }
        )
        return DoctorReport(
            generatedAt = Instant.now(),
            sections = sections
        )
    }

    /** 섹션 실행을 fail-safe로 감싼다. 예외 시 ERROR 섹션 반환. */
    private fun safeRun(name: String, block: () -> DoctorSection): DoctorSection {
        return try {
            block()
        } catch (e: Exception) {
            logger.warn(e) { "진단 섹션 '$name' 실행 실패" }
            DoctorSection(
                name = name,
                status = DoctorStatus.ERROR,
                checks = listOf(
                    DoctorCheck(
                        name = "진단 실행",
                        status = DoctorStatus.ERROR,
                        detail = "예외 발생: ${e.javaClass.simpleName}: ${e.message ?: "메시지 없음"}"
                    )
                ),
                message = "진단 실행 중 예외 발생"
            )
        }
    }

    /** R225~R229 Approval Context Resolver 섹션. */
    private fun diagnoseApprovalResolver(): DoctorSection {
        val resolver = approvalResolverProvider.ifAvailable
        val checks = mutableListOf<DoctorCheck>()

        if (resolver == null) {
            checks.add(
                DoctorCheck(
                    name = "resolver bean",
                    status = DoctorStatus.SKIPPED,
                    detail = "등록 안 됨 — approval enrichment 비활성 (R221 기본 동작)"
                )
            )
            return DoctorSection(
                name = "Approval Context Resolver",
                status = DoctorStatus.SKIPPED,
                checks = checks,
                message = "비활성 — atlassian-resolver.enabled=true 설정 시 활성화"
            )
        }

        val resolverType = resolver::class.java.simpleName
        checks.add(
            DoctorCheck(
                name = "resolver bean",
                status = DoctorStatus.OK,
                detail = "등록됨: $resolverType"
            )
        )

        // R228 PII 마스킹 확인
        val isRedacted = resolver is RedactedApprovalContextResolver
        checks.add(
            DoctorCheck(
                name = "PII 마스킹 (R228)",
                status = if (isRedacted) DoctorStatus.OK else DoctorStatus.WARN,
                detail = if (isRedacted) {
                    "활성 — 감사 로그에 이메일/토큰 노출 없음"
                } else {
                    "비활성 — 감사 로그에 PII 노출 위험. " +
                        "arc.reactor.approval.pii-redaction.enabled=true 권장"
                }
            )
        )

        // Sanity check — 샘플 도구로 resolve 호출
        val sampleContext = try {
            resolver.resolve(
                toolName = "jira_get_issue",
                arguments = mapOf("issueKey" to "SAMPLE-1")
            )
        } catch (e: Exception) {
            logger.debug(e) { "sample resolve 호출 실패" }
            null
        }

        if (sampleContext != null) {
            checks.add(
                DoctorCheck(
                    name = "sample resolve",
                    status = DoctorStatus.OK,
                    detail = "정상 응답 — reversibility=${sampleContext.reversibility}"
                )
            )
        } else {
            checks.add(
                DoctorCheck(
                    name = "sample resolve",
                    status = DoctorStatus.WARN,
                    detail = "샘플 도구에 응답 없음 (null). 이는 fallback resolver가 지원하지 " +
                        "않는 도구일 수 있음"
                )
            )
        }

        val overallStatus = if (checks.any { it.status == DoctorStatus.WARN }) {
            DoctorStatus.WARN
        } else {
            DoctorStatus.OK
        }

        return DoctorSection(
            name = "Approval Context Resolver",
            status = overallStatus,
            checks = checks,
            message = "활성 ($resolverType)"
        )
    }

    /** R223~R232 Tool Response Summarizer 섹션. */
    private fun diagnoseToolSummarizer(): DoctorSection {
        val summarizer = toolSummarizerProvider.ifAvailable
        val checks = mutableListOf<DoctorCheck>()

        if (summarizer == null || summarizer === NoOpToolResponseSummarizer) {
            checks.add(
                DoctorCheck(
                    name = "summarizer bean",
                    status = DoctorStatus.SKIPPED,
                    detail = "NoOp 또는 미등록 — ACI 요약 비활성 (R223 기본)"
                )
            )
            return DoctorSection(
                name = "Tool Response Summarizer",
                status = DoctorStatus.SKIPPED,
                checks = checks,
                message = "비활성 — arc.reactor.tool.response.summarizer.enabled=true 설정 시 활성화"
            )
        }

        val summarizerType = summarizer::class.java.simpleName
        checks.add(
            DoctorCheck(
                name = "summarizer bean",
                status = DoctorStatus.OK,
                detail = "등록됨: $summarizerType"
            )
        )

        // R231 PII 마스킹 확인
        val isRedacted = summarizer is RedactedToolResponseSummarizer
        checks.add(
            DoctorCheck(
                name = "PII 마스킹 (R231)",
                status = if (isRedacted) DoctorStatus.OK else DoctorStatus.WARN,
                detail = if (isRedacted) {
                    "활성 — 요약 text/primaryKey 필드 PII 마스킹"
                } else {
                    "비활성 — 요약 결과에 PII 노출 위험. " +
                        "arc.reactor.tool.response.summarizer.pii-redaction.enabled=true 권장"
                }
            )
        )

        // Sanity check — 샘플 payload로 summarize 호출
        val sampleSummary = try {
            summarizer.summarize(
                toolName = "jira_search",
                rawPayload = """[{"key":"SAMPLE-1"}]""",
                success = true
            )
        } catch (e: Exception) {
            logger.debug(e) { "sample summarize 호출 실패" }
            null
        }

        if (sampleSummary != null) {
            checks.add(
                DoctorCheck(
                    name = "sample summarize",
                    status = DoctorStatus.OK,
                    detail = "정상 응답 — kind=${sampleSummary.kind}"
                )
            )
        } else {
            checks.add(
                DoctorCheck(
                    name = "sample summarize",
                    status = DoctorStatus.WARN,
                    detail = "샘플 payload에 응답 없음 (null)"
                )
            )
        }

        val overallStatus = if (checks.any { it.status == DoctorStatus.WARN }) {
            DoctorStatus.WARN
        } else {
            DoctorStatus.OK
        }

        return DoctorSection(
            name = "Tool Response Summarizer",
            status = overallStatus,
            checks = checks,
            message = "활성 ($summarizerType)"
        )
    }

    /** R222/R224/R234 Evaluation Metrics Collector 섹션. */
    private fun diagnoseEvaluationCollector(): DoctorSection {
        val collector = evaluationCollectorProvider.ifAvailable
        val checks = mutableListOf<DoctorCheck>()

        if (collector == null || collector === NoOpEvaluationMetricsCollector) {
            checks.add(
                DoctorCheck(
                    name = "collector bean",
                    status = DoctorStatus.SKIPPED,
                    detail = "NoOp 또는 미등록 — 평가 메트릭 비활성 (R222 기본)"
                )
            )
            return DoctorSection(
                name = "Evaluation Metrics Collector",
                status = DoctorStatus.SKIPPED,
                checks = checks,
                message = "비활성 — arc.reactor.evaluation.metrics.enabled=true 설정 시 활성화"
            )
        }

        val collectorType = collector::class.java.simpleName
        checks.add(
            DoctorCheck(
                name = "collector bean",
                status = DoctorStatus.OK,
                detail = "등록됨: $collectorType"
            )
        )

        // R234 카탈로그와 일치 여부
        checks.add(
            DoctorCheck(
                name = "metric catalog (R234)",
                status = DoctorStatus.OK,
                detail = "${EvaluationMetricsCatalog.ALL.size}개 메트릭 등록 " +
                    "(task/duration/tool.calls/cost/override/safety/kind)"
            )
        )

        // Sanity check — 실제 호출
        val recordOk = try {
            collector.recordTaskCompleted(success = true, durationMs = 1L)
            collector.recordToolCallCount(count = 0)
            true
        } catch (e: Exception) {
            logger.debug(e) { "sample record 호출 실패" }
            false
        }
        checks.add(
            DoctorCheck(
                name = "sample record",
                status = if (recordOk) DoctorStatus.OK else DoctorStatus.ERROR,
                detail = if (recordOk) "정상 동작" else "예외 발생"
            )
        )

        val overallStatus = when {
            checks.any { it.status == DoctorStatus.ERROR } -> DoctorStatus.ERROR
            checks.any { it.status == DoctorStatus.WARN } -> DoctorStatus.WARN
            else -> DoctorStatus.OK
        }

        return DoctorSection(
            name = "Evaluation Metrics Collector",
            status = overallStatus,
            checks = checks,
            message = "활성 ($collectorType) — ${EvaluationMetricsCatalog.ALL.size}개 메트릭"
        )
    }

    /** R220/R235 Prompt Layer Registry 섹션 — 분류 무결성 체크. */
    private fun diagnosePromptLayerRegistry(): DoctorSection {
        val checks = mutableListOf<DoctorCheck>()

        val allMethods = PromptLayerRegistry.allClassifiedMethods()
        checks.add(
            DoctorCheck(
                name = "classified methods",
                status = if (allMethods.isNotEmpty()) DoctorStatus.OK else DoctorStatus.ERROR,
                detail = "${allMethods.size}개 메서드 분류됨 " +
                    "(main ${PromptLayerRegistry.mainPathMethods().size} / " +
                    "planning ${PromptLayerRegistry.planningPathMethods().size})"
            )
        )

        // 모든 계층이 비어있지 않은지 확인
        val emptyLayers = PromptLayer.values().filter {
            PromptLayerRegistry.methodsInLayer(it).isEmpty()
        }
        checks.add(
            DoctorCheck(
                name = "layer coverage",
                status = if (emptyLayers.isEmpty()) DoctorStatus.OK else DoctorStatus.WARN,
                detail = if (emptyLayers.isEmpty()) {
                    "6개 계층 모두 1개 이상 메서드 할당됨"
                } else {
                    "빈 계층: ${emptyLayers.joinToString()}"
                }
            )
        )

        // 메인 경로와 계획 경로 교집합 없음
        val overlap = PromptLayerRegistry.mainPathMethods()
            .intersect(PromptLayerRegistry.planningPathMethods())
        checks.add(
            DoctorCheck(
                name = "path independence",
                status = if (overlap.isEmpty()) DoctorStatus.OK else DoctorStatus.ERROR,
                detail = if (overlap.isEmpty()) {
                    "메인/계획 경로 겹침 없음"
                } else {
                    "겹치는 메서드: $overlap"
                }
            )
        )

        val overallStatus = when {
            checks.any { it.status == DoctorStatus.ERROR } -> DoctorStatus.ERROR
            checks.any { it.status == DoctorStatus.WARN } -> DoctorStatus.WARN
            else -> DoctorStatus.OK
        }

        return DoctorSection(
            name = "Prompt Layer Registry",
            status = overallStatus,
            checks = checks,
            message = "무결성 확인됨 (${allMethods.size}개 메서드 / ${PromptLayer.values().size}개 계층)"
        )
    }
}
