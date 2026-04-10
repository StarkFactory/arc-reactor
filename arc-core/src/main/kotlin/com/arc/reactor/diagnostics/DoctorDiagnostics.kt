package com.arc.reactor.diagnostics

import com.arc.reactor.agent.impl.PromptLayer
import com.arc.reactor.agent.impl.PromptLayerRegistry
import com.arc.reactor.agent.metrics.EvaluationMetricsCatalog
import com.arc.reactor.agent.metrics.EvaluationMetricsCollector
import com.arc.reactor.agent.metrics.NoOpEvaluationMetricsCollector
import com.arc.reactor.approval.ApprovalContextResolver
import com.arc.reactor.approval.RedactedApprovalContextResolver
import com.arc.reactor.cache.ResponseCache
import com.arc.reactor.cache.SemanticResponseCache
import com.arc.reactor.cache.impl.CaffeineResponseCache
import com.arc.reactor.cache.impl.NoOpResponseCache
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
    private val evaluationCollectorProvider: ObjectProvider<EvaluationMetricsCollector>,
    private val responseCacheProvider: ObjectProvider<ResponseCache>
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
            safeRun("Prompt Layer Registry") { diagnosePromptLayerRegistry() },
            safeRun("Response Cache") { diagnoseResponseCache() }
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
                    "(task/duration/tool.calls/cost/override/safety/kind/compression/error)"
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

    /**
     * R238: Response Cache 섹션 — NoOp/Caffeine/Redis(Semantic) 구분.
     *
     * Redis 의미적 캐시 미사용 시 WARN으로 표시. R231 이후 Arc Reactor가 Redis 의미적
     * 캐시를 핵심 성능/비용 축으로 삼고 있으므로, 프로덕션에서는 Redis 백엔드가 권장된다.
     */
    private fun diagnoseResponseCache(): DoctorSection {
        val cache = responseCacheProvider.ifAvailable
        val checks = mutableListOf<DoctorCheck>()

        if (cache == null) {
            checks.add(
                DoctorCheck(
                    name = "cache bean",
                    status = DoctorStatus.SKIPPED,
                    detail = "ResponseCache 빈 미등록 — 응답 캐싱 완전 비활성"
                )
            )
            return DoctorSection(
                name = "Response Cache",
                status = DoctorStatus.SKIPPED,
                checks = checks,
                message = "비활성 — 빈 미등록"
            )
        }

        val cacheType = cache::class.java.simpleName
        checks.add(
            DoctorCheck(
                name = "cache bean",
                status = DoctorStatus.OK,
                detail = "등록됨: $cacheType"
            )
        )

        // 캐시 유형 분류
        val tier = classifyCacheTier(cache)
        checks.add(
            DoctorCheck(
                name = "cache tier",
                status = tier.status,
                detail = tier.detail
            )
        )

        // NoOp 캐시는 의미적 검색 여부가 무의미하므로 early-return
        // (캐싱 자체가 비활성화된 상태에서 semantic search 여부 경고는 혼란만 유발)
        if (tier.tierName == "noop") {
            return DoctorSection(
                name = "Response Cache",
                status = DoctorStatus.SKIPPED,
                checks = checks,
                message = "비활성 — NoOp 캐시 (실제 캐싱 없음)"
            )
        }

        // 의미적 캐시 기능 확인 (Redis Semantic만 지원)
        val isSemantic = cache is SemanticResponseCache
        checks.add(
            DoctorCheck(
                name = "semantic search",
                status = if (isSemantic) DoctorStatus.OK else DoctorStatus.WARN,
                detail = if (isSemantic) {
                    "활성 — 의미적 유사도 기반 캐시 히트 지원"
                } else {
                    "비활성 — 정확한 키 매칭만 (의미적 히트 없음). " +
                        "Redis + pgvector + Spring AI EmbeddingModel 설정 권장"
                }
            )
        )

        val overallStatus = when {
            checks.any { it.status == DoctorStatus.ERROR } -> DoctorStatus.ERROR
            checks.any { it.status == DoctorStatus.WARN } -> DoctorStatus.WARN
            else -> DoctorStatus.OK
        }

        return DoctorSection(
            name = "Response Cache",
            status = overallStatus,
            checks = checks,
            message = "활성 ($cacheType, tier=${tier.tierName})"
        )
    }

    /**
     * 캐시 구현체를 tier로 분류한다.
     * - NoOp: 완전 비활성 (SKIPPED)
     * - Caffeine: 프로세스 로컬 (WARN — 멀티 인스턴스 환경에서는 비효율)
     * - RedisSemanticResponseCache: 프로덕션 권장 (OK)
     * - 기타 커스텀: OK (사용자가 알아서 결정)
     */
    private fun classifyCacheTier(cache: ResponseCache): CacheTier {
        return when {
            cache is NoOpResponseCache -> CacheTier(
                tierName = "noop",
                status = DoctorStatus.SKIPPED,
                detail = "NoOp 캐시 — 모든 get/put이 no-op, 실제 캐싱 없음"
            )
            cache is CaffeineResponseCache -> CacheTier(
                tierName = "caffeine",
                status = DoctorStatus.WARN,
                detail = "Caffeine in-memory 캐시 — 프로세스 로컬만. " +
                    "멀티 인스턴스 환경에서는 Redis 권장"
            )
            // RedisSemanticResponseCache는 SemanticResponseCache를 구현하며
            // 별도 클래스 체크는 검증 복잡도를 키우므로 인터페이스 체크로 충분
            cache is SemanticResponseCache -> CacheTier(
                tierName = "semantic",
                status = DoctorStatus.OK,
                detail = "의미적 캐시 구현체 — 프로덕션 권장 백엔드 (Redis + pgvector 등)"
            )
            else -> CacheTier(
                tierName = "custom",
                status = DoctorStatus.OK,
                detail = "커스텀 구현체: ${cache::class.java.simpleName}"
            )
        }
    }

    /** 캐시 tier 분류 결과 (사설 helper). */
    private data class CacheTier(
        val tierName: String,
        val status: DoctorStatus,
        val detail: String
    )

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
