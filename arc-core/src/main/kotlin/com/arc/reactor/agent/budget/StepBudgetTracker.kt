package com.arc.reactor.agent.budget

import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 토큰 예산 상태.
 *
 * ReAct 루프의 각 단계에서 토큰 소비를 기록한 후 반환되는 상태.
 */
enum class BudgetStatus {
    /** 예산 내 — 정상 진행 */
    OK,

    /** 소프트 리밋 도달 — 경고 로그 기록, 루프는 계속 */
    SOFT_LIMIT,

    /** 하드 리밋 도달 — 루프 종료 유도 */
    EXHAUSTED
}

/**
 * 단계별 토큰 소비 기록.
 *
 * @param step 단계 이름 (예: "llm-call-1", "tool-exec-search")
 * @param inputTokens 해당 단계에서 사용된 입력 토큰 수
 * @param outputTokens 해당 단계에서 사용된 출력 토큰 수
 * @param cumulativeTokens 이 기록 시점까지의 누적 토큰 수
 * @param status 기록 후의 예산 상태
 */
data class StepRecord(
    val step: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val cumulativeTokens: Int,
    val status: BudgetStatus
)

/**
 * 단계별 토큰 예산 추적기.
 *
 * ReAct 루프의 각 단계(LLM 호출, 도구 실행)별 토큰 소비를 추적하고
 * 예산 초과 시 경고 또는 중단을 유도한다.
 *
 * ## 동작 방식
 * - **소프트 리밋** ([softLimitPercent]%): 경고 로그 기록. 루프는 계속 진행.
 * - **하드 리밋** (100%): [BudgetStatus.EXHAUSTED] 반환. 호출자가 루프를 종료해야 한다.
 *
 * ## 사용 예시
 * ```kotlin
 * val tracker = StepBudgetTracker(maxTokens = 10000, softLimitPercent = 80)
 *
 * val status = tracker.record("llm-call-1", inputTokens = 500, outputTokens = 200)
 * if (status == BudgetStatus.EXHAUSTED) {
 *     // 루프 종료
 * }
 * ```
 *
 * 이 클래스는 스레드 안전하지 않다. 단일 요청 스코프 내에서만 사용할 것.
 *
 * @param maxTokens 요청당 최대 토큰 예산
 * @param softLimitPercent 소프트 리밋 비율 (0-100). 기본 80%.
 * @see com.arc.reactor.agent.config.BudgetProperties 설정 속성
 */
class StepBudgetTracker(
    private val maxTokens: Int,
    private val softLimitPercent: Int = 80
) {
    init {
        require(maxTokens > 0) { "maxTokens는 양수여야 한다: $maxTokens" }
        require(softLimitPercent in 1..99) { "softLimitPercent는 1~99 범위여야 한다: $softLimitPercent" }
    }

    private var consumed: Int = 0
    private val steps: MutableList<StepRecord> = mutableListOf()
    private val softLimitTokens: Int = (maxTokens.toLong() * softLimitPercent / 100).toInt()
    private var softLimitWarned: Boolean = false

    /**
     * 단계별 토큰 소비를 기록하고 예산 상태를 반환한다.
     *
     * @param step 단계 이름 (예: "llm-call-1", "tool-exec-search")
     * @param inputTokens 입력 토큰 수 (0 이상)
     * @param outputTokens 출력 토큰 수 (0 이상)
     * @return 기록 후의 예산 상태
     */
    fun record(step: String, inputTokens: Int, outputTokens: Int): BudgetStatus {
        require(inputTokens >= 0) { "inputTokens는 음수일 수 없다: $inputTokens" }
        require(outputTokens >= 0) { "outputTokens는 음수일 수 없다: $outputTokens" }

        consumed += inputTokens + outputTokens
        val status = evaluateStatus()

        steps.add(
            StepRecord(
                step = step,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                cumulativeTokens = consumed,
                status = status
            )
        )

        when (status) {
            BudgetStatus.SOFT_LIMIT -> if (!softLimitWarned) {
                softLimitWarned = true
                logger.warn {
                    "토큰 예산 소프트 리밋 도달: consumed=$consumed, " +
                        "softLimit=$softLimitTokens, maxTokens=$maxTokens, step=$step"
                }
            }
            BudgetStatus.EXHAUSTED -> logger.warn {
                "토큰 예산 소진: consumed=$consumed, maxTokens=$maxTokens, step=$step"
            }
            BudgetStatus.OK -> Unit
        }

        return status
    }

    /** 현재까지 소비된 총 토큰 수. */
    fun totalConsumed(): Int = consumed

    /** 남은 토큰 수. 0 이하면 예산 소진. */
    fun remaining(): Int = (maxTokens - consumed).coerceAtLeast(0)

    /** 예산이 소진되었는지 여부. */
    fun isExhausted(): Boolean = consumed >= maxTokens

    /** 기록된 모든 단계의 불변 목록. */
    fun history(): List<StepRecord> = steps.toList()

    /** 현재 예산 상태를 평가한다. */
    private fun evaluateStatus(): BudgetStatus = when {
        consumed >= maxTokens -> BudgetStatus.EXHAUSTED
        consumed >= softLimitTokens -> BudgetStatus.SOFT_LIMIT
        else -> BudgetStatus.OK
    }
}
