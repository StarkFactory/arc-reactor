package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.metrics.EvaluationMetricsCollector
import com.arc.reactor.approval.ApprovalContextResolver
import com.arc.reactor.cache.ResponseCache
import com.arc.reactor.diagnostics.DoctorDiagnostics
import com.arc.reactor.diagnostics.StartupDoctorLogger
import com.arc.reactor.tool.summarize.ToolResponseSummarizer
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

/**
 * R236 [DoctorDiagnostics] 자동 구성.
 *
 * 항상 [DoctorDiagnostics] 빈을 등록한다 (opt-in 프로퍼티 없음). 서비스 자체가 read-only
 * introspection 이므로 등록 오버헤드가 매우 작고, 사용자는 필요할 때만 주입해서 호출하면
 * 된다.
 *
 * ## 의존성 해소
 *
 * - `ApprovalContextResolver`, `ToolResponseSummarizer`, `EvaluationMetricsCollector`는
 *   모두 `ObjectProvider`로 주입되어 **선택적** (미등록 시 `ifAvailable`이 null 반환)
 * - 사용자가 위 빈들을 등록했든 아니든 `DoctorDiagnostics`는 항상 동작
 *
 * ## 사용 예
 *
 * ```kotlin
 * @RestController
 * class DoctorController(private val doctor: DoctorDiagnostics) {
 *     @GetMapping("/admin/doctor")
 *     fun report(): DoctorReport = doctor.runDiagnostics()
 * }
 * ```
 *
 * ## @ConditionalOnMissingBean
 *
 * 사용자가 커스텀 `DoctorDiagnostics` 빈을 등록하면 기본 빈은 양보한다. 이는 사용자가
 * 추가 섹션을 포함한 확장 구현을 제공할 수 있도록 함이다.
 *
 * @see DoctorDiagnostics 진단 서비스
 * @see com.arc.reactor.diagnostics.DoctorReport 진단 보고서
 */
@AutoConfiguration
class DoctorDiagnosticsConfiguration {

    @Bean
    @ConditionalOnMissingBean(DoctorDiagnostics::class)
    fun doctorDiagnostics(
        approvalResolverProvider: ObjectProvider<ApprovalContextResolver>,
        toolSummarizerProvider: ObjectProvider<ToolResponseSummarizer>,
        evaluationCollectorProvider: ObjectProvider<EvaluationMetricsCollector>,
        responseCacheProvider: ObjectProvider<ResponseCache>
    ): DoctorDiagnostics = DoctorDiagnostics(
        approvalResolverProvider = approvalResolverProvider,
        toolSummarizerProvider = toolSummarizerProvider,
        evaluationCollectorProvider = evaluationCollectorProvider,
        responseCacheProvider = responseCacheProvider
    )

    /**
     * R243: [StartupDoctorLogger] — 기동 시점 자동 진단 로그.
     *
     * opt-in 기본값. `arc.reactor.diagnostics.startup-log.enabled=true` 로만 활성화된다.
     * 기본 off인 이유는 (1) 사용자에게 예상치 못한 로그 출력을 강요하지 않기 위함이고
     * (2) 이미 `DoctorController` REST 엔드포인트로 on-demand 조회가 가능하기 때문이다.
     *
     * ## 프로퍼티
     *
     * - `arc.reactor.diagnostics.startup-log.enabled` (기본 `false`) — 활성 여부
     * - `arc.reactor.diagnostics.startup-log.include-details` (기본 `true`) — check 상세 포함
     * - `arc.reactor.diagnostics.startup-log.warn-on-issues` (기본 `true`) — 경고/오류 추가 로그
     */
    @Bean
    @ConditionalOnMissingBean(StartupDoctorLogger::class)
    @ConditionalOnProperty(
        prefix = "arc.reactor.diagnostics.startup-log",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = false
    )
    fun startupDoctorLogger(
        doctor: DoctorDiagnostics,
        @org.springframework.beans.factory.annotation.Value(
            "\${arc.reactor.diagnostics.startup-log.include-details:true}"
        )
        includeDetails: Boolean,
        @org.springframework.beans.factory.annotation.Value(
            "\${arc.reactor.diagnostics.startup-log.warn-on-issues:true}"
        )
        warnOnIssues: Boolean
    ): StartupDoctorLogger = StartupDoctorLogger(
        doctor = doctor,
        includeDetails = includeDetails,
        warnOnIssues = warnOnIssues
    )
}
