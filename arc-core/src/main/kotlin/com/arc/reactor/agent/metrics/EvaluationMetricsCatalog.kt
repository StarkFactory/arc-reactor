package com.arc.reactor.agent.metrics

/**
 * R222+R224 평가(Evaluation) 메트릭의 **타입화된 카탈로그**.
 *
 * [MicrometerEvaluationMetricsCollector]가 기록하는 모든 메트릭의 이름, 유형, 태그를
 * 한 곳에서 조회할 수 있도록 한다. 사용자는 이 카탈로그를 사용하여:
 *
 * - Prometheus 쿼리 작성 시 매직 문자열 대신 [Metric.name] 상수 참조
 * - Grafana 대시보드 JSON 생성 시 태그 목록 확인
 * - 테스트에서 메트릭 이름 검증
 * - 관측 문서 자동 생성
 *
 * ## Single source of truth
 *
 * [Metric.name]과 [Metric.tags]는 [MicrometerEvaluationMetricsCollector]의 `METRIC_*` /
 * `TAG_*` 상수를 직접 참조한다. 따라서 Micrometer 구현체를 수정하면 카탈로그도 자동으로
 * 업데이트된다.
 *
 * ## 관측 문서
 *
 * 사용자가 이 카탈로그를 실제 대시보드/알림으로 활용하는 방법은 `docs/evaluation-metrics.md`
 * 를 참조한다. 각 메트릭의 Prometheus 쿼리 예시와 Grafana 패널 정의가 포함되어 있다.
 *
 * ## R222+R224+R242+R245 9개 메트릭
 *
 * | Enum | Name | 유형 | 태그 |
 * |------|------|------|------|
 * | [TASK_COMPLETED] | `arc.reactor.eval.task.completed` | COUNTER | result, error_code |
 * | [TASK_DURATION] | `arc.reactor.eval.task.duration` | TIMER | result |
 * | [TOOL_CALLS] | `arc.reactor.eval.tool.calls` | DISTRIBUTION_SUMMARY | — |
 * | [TOKEN_COST] | `arc.reactor.eval.token.cost.usd` | COUNTER | model |
 * | [HUMAN_OVERRIDE] | `arc.reactor.eval.human.override` | COUNTER | outcome, tool |
 * | [SAFETY_REJECTION] | `arc.reactor.eval.safety.rejection` | COUNTER | stage, reason |
 * | [TOOL_RESPONSE_KIND] | `arc.reactor.eval.tool.response.kind` | COUNTER | kind, tool |
 * | [TOOL_RESPONSE_COMPRESSION] | `arc.reactor.eval.tool.response.compression` | DISTRIBUTION_SUMMARY | tool |
 * | [EXECUTION_ERROR] | `arc.reactor.eval.execution.error` | COUNTER | stage, exception |
 *
 * @see MicrometerEvaluationMetricsCollector 실제 Micrometer 구현체
 * @see EvaluationMetricsCollector 수집기 인터페이스
 */
object EvaluationMetricsCatalog {

    /**
     * 메트릭 유형 분류.
     */
    enum class MetricType {
        /** 누적 카운터 (단조 증가) */
        COUNTER,

        /** 시간 히스토그램 (count, sum, percentile) */
        TIMER,

        /** 값 분포 히스토그램 */
        DISTRIBUTION_SUMMARY
    }

    /**
     * 단일 메트릭 정의.
     *
     * @property name Prometheus에 노출되는 메트릭 이름 (접두사 `arc.reactor.eval.`)
     * @property type Micrometer 유형
     * @property tags 태그 키 목록 (순서는 그룹핑 우선순위에 따름)
     * @property description 사람이 읽을 수 있는 설명
     * @property unit 단위 문자열 (선택)
     */
    data class Metric(
        val name: String,
        val type: MetricType,
        val tags: List<String>,
        val description: String,
        val unit: String? = null
    )

    /** R222: task success rate + error code 분포. */
    val TASK_COMPLETED: Metric = Metric(
        name = MicrometerEvaluationMetricsCollector.METRIC_TASK_COMPLETED,
        type = MetricType.COUNTER,
        tags = listOf(
            MicrometerEvaluationMetricsCollector.TAG_RESULT,
            MicrometerEvaluationMetricsCollector.TAG_ERROR_CODE
        ),
        description = "에이전트 작업 완료 누적 카운터. result=success|failure, " +
            "error_code는 실패 시 에러 코드 (RATE_LIMITED, TIMEOUT 등) 또는 none."
    )

    /** R222: task 실행 지연 (latency). */
    val TASK_DURATION: Metric = Metric(
        name = MicrometerEvaluationMetricsCollector.METRIC_TASK_DURATION,
        type = MetricType.TIMER,
        tags = listOf(MicrometerEvaluationMetricsCollector.TAG_RESULT),
        description = "에이전트 작업 전체 실행 시간 타이머. percentile 쿼리로 p50/p95/p99 " +
            "관측 가능.",
        unit = "ms"
    )

    /** R222: 작업당 도구 호출 수 분포. */
    val TOOL_CALLS: Metric = Metric(
        name = MicrometerEvaluationMetricsCollector.METRIC_TOOL_CALLS,
        type = MetricType.DISTRIBUTION_SUMMARY,
        tags = emptyList(),
        description = "한 작업에서 호출된 도구 개수의 분포. 평균/중앙값 관측용.",
        unit = "calls"
    )

    /** R222: 요청당 LLM 비용 누적. */
    val TOKEN_COST: Metric = Metric(
        name = MicrometerEvaluationMetricsCollector.METRIC_TOKEN_COST,
        type = MetricType.COUNTER,
        tags = listOf(MicrometerEvaluationMetricsCollector.TAG_MODEL),
        description = "모델별 LLM 추정 비용 누적 (USD). " +
            "ExecutionResultFinalizer의 costEstimateUsd 메타데이터에서 추출.",
        unit = "usd"
    )

    /** R222: HITL 사람 개입 결과. */
    val HUMAN_OVERRIDE: Metric = Metric(
        name = MicrometerEvaluationMetricsCollector.METRIC_HUMAN_OVERRIDE,
        type = MetricType.COUNTER,
        tags = listOf(
            MicrometerEvaluationMetricsCollector.TAG_OUTCOME,
            MicrometerEvaluationMetricsCollector.TAG_TOOL
        ),
        description = "Human-in-the-Loop 개입 결과 분포. outcome=approved|rejected|" +
            "timeout|auto, tool=도구 이름."
    )

    /** R222: 안전 정책 거부 추적. */
    val SAFETY_REJECTION: Metric = Metric(
        name = MicrometerEvaluationMetricsCollector.METRIC_SAFETY_REJECTION,
        type = MetricType.COUNTER,
        tags = listOf(
            MicrometerEvaluationMetricsCollector.TAG_STAGE,
            MicrometerEvaluationMetricsCollector.TAG_REASON
        ),
        description = "안전 거부 카운터. stage=guard|output_guard|hook|tool_policy, " +
            "reason=injection|pii|rate_limit 등의 분류 문자열."
    )

    /** R224: R222+R223 시너지 — 도구 응답 요약 분류. */
    val TOOL_RESPONSE_KIND: Metric = Metric(
        name = MicrometerEvaluationMetricsCollector.METRIC_TOOL_RESPONSE_KIND,
        type = MetricType.COUNTER,
        tags = listOf(
            MicrometerEvaluationMetricsCollector.TAG_KIND,
            MicrometerEvaluationMetricsCollector.TAG_TOOL
        ),
        description = "R224 시너지 메트릭. R223 ToolResponseSummarizer가 분류한 " +
            "SummaryKind(empty|error_cause_first|list_top_n|structured|text_head_tail|" +
            "text_full)별 카운터. kind=요약 분류, tool=도구 이름."
    )

    /** R242: R222+R241 시너지 — 도구 응답 요약 압축률 분포. */
    val TOOL_RESPONSE_COMPRESSION: Metric = Metric(
        name = MicrometerEvaluationMetricsCollector.METRIC_TOOL_RESPONSE_COMPRESSION,
        type = MetricType.DISTRIBUTION_SUMMARY,
        tags = listOf(MicrometerEvaluationMetricsCollector.TAG_TOOL),
        description = "R242 시너지 메트릭. R241 ToolResponseSummary.compressionPercent() " +
            "값의 분포. 0~100 정수, 음수는 0으로 clamp. 도구별 요약 효율성 관측용 " +
            "(평균/p50/p95). tool=도구 이름.",
        unit = "percent"
    )

    /** R245: 실행 경로 예외 분포 — stage + exception class별 카운터. */
    val EXECUTION_ERROR: Metric = Metric(
        name = MicrometerEvaluationMetricsCollector.METRIC_EXECUTION_ERROR,
        type = MetricType.COUNTER,
        tags = listOf(
            MicrometerEvaluationMetricsCollector.TAG_STAGE,
            MicrometerEvaluationMetricsCollector.TAG_EXCEPTION
        ),
        description = "R245 런타임 예외 분포. task.completed의 집계 관점, " +
            "safety.rejection의 정책 관점과 달리 실제 throwable 예외를 stage별로 분류하여 " +
            "운영 문제를 빠르게 탐지한다. stage=tool_call|llm_call|guard|hook|output_guard|" +
            "parsing|memory|cache|other, exception=예외 클래스 simple name."
    )

    /**
     * 전체 메트릭 리스트 — 카탈로그의 single source of truth.
     * 새 메트릭 추가 시 여기에도 추가해야 한다.
     */
    val ALL: List<Metric> = listOf(
        TASK_COMPLETED,
        TASK_DURATION,
        TOOL_CALLS,
        TOKEN_COST,
        HUMAN_OVERRIDE,
        SAFETY_REJECTION,
        TOOL_RESPONSE_KIND,
        TOOL_RESPONSE_COMPRESSION,
        EXECUTION_ERROR
    )

    /**
     * 특정 메트릭 이름으로 카탈로그 항목을 조회한다.
     * 등록되지 않은 이름이면 null.
     */
    fun findByName(name: String): Metric? = ALL.firstOrNull { it.name == name }

    /**
     * 메트릭 유형별로 필터링한다.
     * 예: `filterByType(MetricType.COUNTER)` → 5개 counter 메트릭.
     */
    fun filterByType(type: MetricType): List<Metric> = ALL.filter { it.type == type }

    /** 공통 메트릭 이름 접두사 — 모든 evaluation 메트릭이 이 접두사를 가진다. */
    const val METRIC_NAME_PREFIX: String = "arc.reactor.eval."
}
