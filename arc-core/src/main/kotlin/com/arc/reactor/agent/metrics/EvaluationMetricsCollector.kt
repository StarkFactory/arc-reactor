package com.arc.reactor.agent.metrics

/**
 * 평가(Evaluation) 메트릭 수집 인터페이스.
 *
 * `docs/agent-work-directive.md` §3.5 Benchmark-Aware Evaluation Loop 원칙에서 요구하는
 * 6가지 핵심 지표를 기록한다:
 *
 * 1. **task success rate** — [recordTaskCompleted]
 * 2. **average tool calls** — [recordToolCallCount]
 * 3. **latency** — [recordTaskCompleted]의 durationMs
 * 4. **token cost** — [recordTokenCost]
 * 5. **human override rate** — [recordHumanOverride]
 * 6. **safety rejection accuracy** — [recordSafetyRejection]
 *
 * ## 기존 메트릭과의 관계
 *
 * 이 인터페이스는 기존 [SlaMetrics], [AgentMetrics]와 **분리된 관측 계층**이다. 목적이 다르다:
 *
 * - [SlaMetrics]: SLA/SLO 보고용 (E2E 레이턴시, 가용성, ReAct 수렴, 도구 실패 상세)
 * - [AgentMetrics]: 운영 관측용 (요청 카운터, Guard 거부, 캐시 히트 등)
 * - [EvaluationMetricsCollector]: **평가셋 기반 개선 측정용** (기능 추가 전후 비교)
 *
 * 새 기능이 평가 지표에 어떤 영향을 미치는지 정량적으로 확인하기 위한 축이며, 향후 Directive
 * 기반 작업의 before/after 비교에 사용된다.
 *
 * ## opt-in 기본값
 *
 * 기본 구현체는 [NoOpEvaluationMetricsCollector]로, 아무 동작도 하지 않는다. Micrometer 기반
 * 수집을 원하면 [MicrometerEvaluationMetricsCollector]를 `@Bean`으로 등록한다.
 *
 * ## Fail-Open 원칙
 *
 * 구현체는 예외를 삼켜야 한다. 메트릭 수집 실패가 핵심 에이전트 실행을 방해하면 안 된다.
 *
 * @see NoOpEvaluationMetricsCollector 기본 no-op 구현체
 * @see MicrometerEvaluationMetricsCollector Micrometer 기반 구현체
 * @see EvaluationMetricsHook AfterAgentCompleteHook로 수집기를 연결하는 어댑터
 */
interface EvaluationMetricsCollector {

    /**
     * 에이전트 작업 완료 기록 (task success rate + latency).
     *
     * @param success 작업 성공 여부
     * @param durationMs 전체 실행 시간 (밀리초)
     * @param errorCode 실패 시 에러 코드 (RATE_LIMITED, TIMEOUT 등)
     */
    fun recordTaskCompleted(
        success: Boolean,
        durationMs: Long,
        errorCode: String? = null
    )

    /**
     * 작업당 도구 호출 수 기록 (average tool calls).
     *
     * @param count 해당 작업에서 호출된 도구 개수
     * @param toolNames 사용된 도구 이름 목록 (tag로 사용하기 위해, 선택)
     */
    fun recordToolCallCount(count: Int, toolNames: List<String> = emptyList())

    /**
     * 토큰 비용 기록 (token cost).
     *
     * @param costUsd 이번 요청의 추정 비용 (USD)
     * @param model 모델 이름 (tag용)
     */
    fun recordTokenCost(costUsd: Double, model: String = "unknown")

    /**
     * 사람 개입(HITL override) 기록 (human override rate).
     *
     * @param outcome 개입 결과 ("approved", "rejected", "timeout", "auto")
     * @param toolName 대상 도구 이름
     */
    fun recordHumanOverride(outcome: HumanOverrideOutcome, toolName: String)

    /**
     * 안전 거부 기록 (safety rejection accuracy).
     *
     * @param stage 거부가 발생한 단계 ("guard", "output_guard", "hook", "tool_policy")
     * @param reason 거부 사유 분류 (예: "injection", "rate_limit", "pii", "unauthorized")
     */
    fun recordSafetyRejection(stage: SafetyRejectionStage, reason: String)

    /**
     * R224: 도구 응답 요약 분류 기록 (R223 ACI 요약 계층과의 시너지).
     *
     * 각 도구 호출의 응답이 어떤 [kind]로 분류되었는지 누적하여, 도구 응답 품질의 분포를
     * 관측한다. 예를 들어 `list_top_n` 비율이 낮고 `error_cause_first`가 많으면 해당 도구의
     * 신뢰성이 낮다고 추정할 수 있다.
     *
     * **기본 구현은 no-op** 이다. [NoOpEvaluationMetricsCollector]를 포함한 기존 구현체가
     * 변경 없이 동작하도록 하기 위함이다.
     *
     * @param kind 요약 분류 문자열 (예: "error_cause_first", "list_top_n", "structured",
     *   "text_head_tail", "text_full", "empty"). [com.arc.reactor.tool.summarize.SummaryKind]의
     *   소문자 name이 기본 포맷.
     * @param toolName 대상 도구 이름
     */
    fun recordToolResponseKind(kind: String, toolName: String) {
        // 기본 no-op (backward compat)
    }

    /**
     * R242: 도구 응답 요약의 압축률 분포 기록 (R222+R241 시너지).
     *
     * R241이 1급 시민화한 `ToolResponseSummary.compressionPercent()`를 Micrometer
     * `DistributionSummary`에 기록하여 평균/중앙값/p95 분포를 관측한다. 이를 통해:
     *
     * - ACI 요약 계층(R223)이 얼마나 효과적으로 원본을 축소하는지 정량 측정
     * - 특정 도구의 요약 효율이 낮다면 (`compressionPercent < 30`) 요약 전략 재검토
     * - 도구별 태그로 Jira vs Confluence vs Bitbucket 요약 효율 비교
     * - 시계열 모니터링으로 R223 휴리스틱 개선 효과 before/after 비교
     *
     * **기본 구현은 no-op** 이다. [NoOpEvaluationMetricsCollector]를 포함한 기존 구현체가
     * 변경 없이 동작하도록 하기 위함이다.
     *
     * ## 음수 압축률 처리
     *
     * 요약이 원본보다 긴 경우(드물지만 `STRUCTURED` 등에서 가능) `percent`가 음수로 전달된다.
     * 구현체는 이 값을 그대로 기록하거나 0으로 clamp할 수 있다. Micrometer 기본은 0 clamp.
     *
     * @param percent 압축률(%) — 0~100 정수가 일반적, 음수 가능
     * @param toolName 대상 도구 이름
     *
     * @see com.arc.reactor.tool.summarize.ToolResponseSummary.compressionPercent
     */
    fun recordToolResponseCompression(percent: Int, toolName: String) {
        // 기본 no-op (backward compat)
    }
}

/**
 * 사람 개입 결과 분류.
 */
enum class HumanOverrideOutcome {
    /** 사람이 명시적으로 승인함 */
    APPROVED,

    /** 사람이 명시적으로 거부함 */
    REJECTED,

    /** 승인 대기 중 타임아웃 */
    TIMEOUT,

    /** 사람 개입 없이 정책상 자동 처리 (대조군) */
    AUTO
}

/**
 * 안전 거부가 발생한 실행 단계 분류.
 */
enum class SafetyRejectionStage {
    /** 요청 단계 Guard (입력 필터, 레이트 리밋, 인젝션 탐지 등) */
    GUARD,

    /** 응답 단계 Output Guard (PII 마스킹, 출력 검증 등) */
    OUTPUT_GUARD,

    /** Hook 기반 거부 (BeforeAgentStart/BeforeToolCall에서 Reject 반환) */
    HOOK,

    /** Tool 정책 거부 (ToolPolicy에서 차단) */
    TOOL_POLICY,

    /** 기타 / 분류 불가 */
    OTHER
}

/**
 * 아무 동작도 하지 않는 기본 [EvaluationMetricsCollector] 구현체.
 *
 * 사용자가 별도의 수집기를 제공하지 않으면 이 구현체가 기본값으로 주입된다.
 * 모든 메서드는 즉시 반환하므로 성능 오버헤드가 0이다.
 */
object NoOpEvaluationMetricsCollector : EvaluationMetricsCollector {
    override fun recordTaskCompleted(success: Boolean, durationMs: Long, errorCode: String?) {
        // no-op
    }

    override fun recordToolCallCount(count: Int, toolNames: List<String>) {
        // no-op
    }

    override fun recordTokenCost(costUsd: Double, model: String) {
        // no-op
    }

    override fun recordHumanOverride(outcome: HumanOverrideOutcome, toolName: String) {
        // no-op
    }

    override fun recordSafetyRejection(stage: SafetyRejectionStage, reason: String) {
        // no-op
    }
}
