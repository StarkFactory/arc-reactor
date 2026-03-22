package com.arc.reactor.agent.slo

import java.time.Instant

/**
 * SLO 위반 유형.
 */
enum class SloViolationType {
    /** P95 레이턴시 임계값 초과 */
    LATENCY,

    /** 에러율 임계값 초과 */
    ERROR_RATE
}

/**
 * SLO 위반 정보.
 *
 * SLO 평가 결과 임계값을 초과한 경우 생성된다.
 *
 * @property type 위반 유형
 * @property currentValue 현재 측정값 (레이턴시: ms, 에러율: 0.0~1.0)
 * @property threshold 설정된 임계값
 * @property message 사람이 읽을 수 있는 위반 설명
 * @property timestamp 위반 감지 시각
 */
data class SloViolation(
    val type: SloViolationType,
    val currentValue: Double,
    val threshold: Double,
    val message: String,
    val timestamp: Instant = Instant.now()
)
