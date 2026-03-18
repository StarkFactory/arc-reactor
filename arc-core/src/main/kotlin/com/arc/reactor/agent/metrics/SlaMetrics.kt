package com.arc.reactor.agent.metrics

/**
 * SLA/SLO 메트릭 인터페이스 -- E2E 레이턴시, 가용성, ReAct 수렴, 도구 실패 상세를 추적한다.
 *
 * 300인 규모 조직의 SLA 보고에 필요한 지표를 기록하기 위한 프레임워크 비종속 추상화.
 * Micrometer, Prometheus, 또는 기타 메트릭 백엔드로 구현할 수 있다.
 *
 * @see NoOpSlaMetrics 기본 no-op 구현체
 * @see MicrometerSlaMetrics Micrometer 기반 구현체
 */
interface SlaMetrics {

    /**
     * ReAct 루프 수렴 기록: 스텝 수, 종료 사유, 소요 시간.
     *
     * @param steps 실행된 ReAct 스텝 수
     * @param stopReason 종료 사유 ("completed", "max_tool_calls", "budget_exhausted", "timeout", "error")
     * @param durationMs ReAct 루프 소요 시간 (밀리초)
     * @param metadata 부가 메타데이터 (선택)
     */
    fun recordReActConvergence(
        steps: Int,
        stopReason: String,
        durationMs: Long,
        metadata: Map<String, Any> = emptyMap()
    )

    /**
     * 도구 실패 상세 기록: 에러 유형 분류.
     *
     * @param toolName 실패한 도구 이름
     * @param errorType 에러 유형 ("timeout", "connection", "validation", "internal", "mcp_unavailable")
     * @param errorMessage 에러 메시지
     * @param durationMs 도구 호출 소요 시간 (밀리초)
     */
    fun recordToolFailureDetail(
        toolName: String,
        errorType: String,
        errorMessage: String,
        durationMs: Long
    )

    /**
     * 시스템 가용성 샘플 기록 (주기적 호출).
     *
     * @param healthy 시스템 정상 여부
     */
    fun recordAvailabilitySample(healthy: Boolean)

    /**
     * E2E 요청 레이턴시 기록 (SLO 추적용).
     *
     * @param durationMs 요청 E2E 소요 시간 (밀리초)
     * @param channel 요청 채널 ("rest", "slack", "teams" 등)
     */
    fun recordE2eLatency(durationMs: Long, channel: String = "unknown")
}
