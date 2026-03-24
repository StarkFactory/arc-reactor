package com.arc.reactor.agent.config

/**
 * 동적 스케줄러 설정.
 *
 * ## 설정 예시
 * ```yaml
 * arc:
 *   reactor:
 *     scheduler:
 *       enabled: true
 *       thread-pool-size: 5
 *       default-execution-timeout-ms: 300000
 * ```
 */
data class SchedulerProperties(
    /** 동적 스케줄러 활성화. 기본 비활성 (opt-in). */
    val enabled: Boolean = false,

    /** 스케줄된 작업 실행을 위한 스레드 풀 크기. */
    val threadPoolSize: Int = 5,

    /** 사용자가 지정하지 않은 경우의 기본 타임존. */
    val defaultTimezone: String = java.time.ZoneId.systemDefault().id,

    /** 명시적 타임아웃이 없는 작업의 기본 실행 타임아웃 (밀리초). */
    val defaultExecutionTimeoutMs: Long = 300_000,

    /** 작업당 유지할 최대 실행 히스토리 항목 수. 0 = 무제한. */
    val maxExecutionsPerJob: Int = 100
)

/**
 * 동적 모델 라우팅 설정.
 *
 * 비용/품질 기반으로 요청을 적절한 LLM 모델로 자동 라우팅한다.
 * 단순 질문은 저렴한 모델(flash/haiku)로, 복잡한 추론은 비싼 모델(pro/opus)로 전달한다.
 *
 * ## 설정 예시
 * ```yaml
 * arc:
 *   reactor:
 *     model-routing:
 *       enabled: true
 *       default-model: gemini-2.0-flash
 *       high-complexity-model: gemini-2.5-pro
 *       routing-strategy: balanced
 *       complexity-threshold-chars: 500
 * ```
 *
 * @see com.arc.reactor.agent.routing.ModelRouter 라우팅 인터페이스
 * @see com.arc.reactor.agent.routing.CostAwareModelRouter 기본 구현
 */
data class ModelRoutingProperties(
    /** 동적 모델 라우팅 활성화. 기본 비활성 (opt-in). */
    val enabled: Boolean = false,

    /** 단순 요청에 사용할 기본(저비용) 모델 ID. */
    val defaultModel: String = "gemini-2.0-flash",

    /** 복잡한 요청에 사용할 고급(고비용) 모델 ID. */
    val highComplexityModel: String = "gemini-2.5-pro",

    /** 라우팅 전략: "cost-optimized", "quality-first", "balanced". */
    val routingStrategy: String = "balanced",

    /** 복잡도 판단을 위한 입력 길이 임계값 (문자 수). */
    val complexityThresholdChars: Int = 500
)

/**
 * 단계별 토큰 예산 추적 설정.
 *
 * ReAct 루프의 각 단계(LLM 호출, 도구 실행)별 토큰 소비를 추적하고
 * 예산 초과 시 경고 또는 중단을 유도한다.
 *
 * ## 설정 예시
 * ```yaml
 * arc:
 *   reactor:
 *     budget:
 *       enabled: true
 *       max-tokens-per-request: 50000
 *       soft-limit-percent: 80
 * ```
 *
 * @see com.arc.reactor.agent.budget.StepBudgetTracker 예산 추적기
 */
data class BudgetProperties(
    /** 토큰 예산 추적 활성화. 기본 비활성 (opt-in). */
    val enabled: Boolean = false,

    /** 요청당 최대 토큰 예산. */
    val maxTokensPerRequest: Int = 50000,

    /** 소프트 리밋 비율 (1-99). 이 비율 도달 시 경고 로그를 기록한다. */
    val softLimitPercent: Int = 80,

    /** 테넌트별 월간 비용 한도 (USD). 0이면 무제한. */
    val monthlyLimitUsd: Double = 0.0,

    /** 월간 한도의 경고 임계치 비율 (1-99). 이 비율 도달 시 경고 로그를 기록한다. */
    val monthlyWarningPercent: Int = 80
)

/**
 * 멀티 에이전트(Supervisor 패턴) 설정.
 *
 * 활성화하면 Supervisor 에이전트가 사용자 쿼리를 분석하여
 * 등록된 전문 에이전트에 자동 위임한다.
 *
 * ## 설정 예시
 * ```yaml
 * arc:
 *   reactor:
 *     multi-agent:
 *       enabled: true
 *       max-delegations: 3
 * ```
 *
 * @see com.arc.reactor.agent.multiagent.SupervisorAgent 인터페이스
 * @see com.arc.reactor.agent.multiagent.AgentRegistry 에이전트 레지스트리
 */
data class MultiAgentProperties(
    /** 멀티 에이전트(Supervisor) 기능 활성화. 기본 비활성 (opt-in). */
    val enabled: Boolean = false,

    /** 단일 요청에서 최대 위임 에이전트 수. */
    val maxDelegations: Int = 3
)

/**
 * SLO 알림 설정.
 *
 * 레이턴시(P95)와 에러율을 슬라이딩 윈도우로 추적하고,
 * 임계값 초과 시 자동 알림을 발송한다.
 *
 * ## 설정 예시
 * ```yaml
 * arc:
 *   reactor:
 *     slo:
 *       enabled: true
 *       latency-threshold-ms: 2000
 *       error-rate-threshold: 0.05
 *       evaluation-window-seconds: 300
 *       alert-cooldown-seconds: 600
 * ```
 *
 * @see com.arc.reactor.agent.slo.SloAlertEvaluator 평가기 인터페이스
 * @see com.arc.reactor.agent.slo.SloAlertNotifier 알림 발송 인터페이스
 */
data class SloAlertProperties(
    /** SLO 알림 활성화. 기본 비활성 (opt-in). */
    val enabled: Boolean = false,

    /** P95 레이턴시 알림 임계값 (밀리초). */
    val latencyThresholdMs: Long = 2000,

    /** 에러율 알림 임계값 (0.0~1.0). 0.05 = 5%. */
    val errorRateThreshold: Double = 0.05,

    /** 평가 슬라이딩 윈도우 크기 (초). */
    val evaluationWindowSeconds: Long = 300,

    /** 동일 유형 알림 재발송 방지 쿨다운 (초). */
    val alertCooldownSeconds: Long = 600
)

/**
 * 비용 이상 탐지 설정.
 *
 * 슬라이딩 윈도우 기반 이동 평균으로 요청당 비용 기준선을 유지하고,
 * 최신 비용이 기준선의 [thresholdMultiplier]배를 초과하면 WARN 로깅한다.
 *
 * ## 설정 예시
 * ```yaml
 * arc:
 *   reactor:
 *     cost-anomaly:
 *       enabled: true
 *       threshold-multiplier: 3.0
 *       window-size: 100
 *       min-samples: 10
 * ```
 *
 * @see com.arc.reactor.agent.budget.CostAnomalyDetector 탐지기 인터페이스
 * @see com.arc.reactor.agent.budget.CostAnomalyHook AfterAgentComplete Hook
 */
data class CostAnomalyProperties(
    /** 비용 이상 탐지 활성화. 기본 비활성 (opt-in). */
    val enabled: Boolean = false,

    /** 이상 판단 배수 임계값. 기준선 × 이 값 초과 시 이상으로 판단. */
    val thresholdMultiplier: Double = 3.0,

    /** 이동 평균 슬라이딩 윈도우 크기 (샘플 수). */
    val windowSize: Int = 100,

    /** 평가에 필요한 최소 샘플 수. */
    val minSamples: Int = 10
)

/**
 * 프롬프트 드리프트 감지 설정.
 *
 * 입력/출력 길이 분포의 변화를 슬라이딩 윈도우 기반으로 모니터링하고,
 * 분포가 기준선에서 [deviationThreshold] 표준편차 이상 벗어나면 WARN 로깅한다.
 *
 * 드리프트는 프롬프트 인젝션 시도(비정상적으로 긴 입력),
 * 모델 성능 저하(짧은 응답), 시스템 프롬프트 변경(응답 패턴 변화) 등을 나타낼 수 있다.
 *
 * ## 설정 예시
 * ```yaml
 * arc:
 *   reactor:
 *     prompt-drift:
 *       enabled: true
 *       deviation-threshold: 2.0
 *       window-size: 200
 *       min-samples: 20
 *       evaluation-interval: 10
 * ```
 *
 * @see com.arc.reactor.agent.drift.PromptDriftDetector 감지기 인터페이스
 * @see com.arc.reactor.agent.drift.PromptDriftHook AfterAgentComplete Hook
 */
data class PromptDriftProperties(
    /** 프롬프트 드리프트 감지 활성화. 기본 비활성 (opt-in). */
    val enabled: Boolean = false,

    /** 이상 판단 표준편차 배수 임계값. */
    val deviationThreshold: Double = 2.0,

    /** 슬라이딩 윈도우 크기 (샘플 수). */
    val windowSize: Int = 200,

    /** 평가에 필요한 최소 샘플 수. */
    val minSamples: Int = 20,

    /** 드리프트 평가 주기 (요청 N회마다 평가). */
    val evaluationInterval: Int = 10
)

/**
 * Guard 차단률 베이스라인 모니터링 설정.
 *
 * 슬라이딩 윈도우 기반으로 Guard 차단률의 기준선을 유지하고,
 * 차단률이 급증(공격 가능성)하거나 급감(Guard 고장 가능성)하면 WARN 로깅한다.
 *
 * ## 설정 예시
 * ```yaml
 * arc:
 *   reactor:
 *     guard-block-rate:
 *       enabled: true
 *       spike-multiplier: 3.0
 *       drop-divisor: 3.0
 *       window-size: 200
 *       min-samples: 50
 *       evaluation-interval: 20
 * ```
 *
 * @see com.arc.reactor.guard.blockrate.GuardBlockRateMonitor 모니터 인터페이스
 * @see com.arc.reactor.guard.blockrate.GuardBlockRateHook AfterAgentComplete Hook
 */
data class GuardBlockRateProperties(
    /** Guard 차단률 모니터링 활성화. 기본 비활성 (opt-in). */
    val enabled: Boolean = false,

    /** 급증 판단 배수. 현재 차단률 > 기준선 × 이 값이면 SPIKE 알림. */
    val spikeMultiplier: Double = 3.0,

    /** 급감 판단 제수. 현재 차단률 < 기준선 / 이 값이면 DROP 알림. */
    val dropDivisor: Double = 3.0,

    /** 슬라이딩 윈도우 크기 (샘플 수). */
    val windowSize: Int = 200,

    /** 평가에 필요한 최소 샘플 수. */
    val minSamples: Int = 50,

    /** 차단률 평가 주기 (요청 N회마다 평가). */
    val evaluationInterval: Int = 20
)
