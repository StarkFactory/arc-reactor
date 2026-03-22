package com.arc.reactor.guard.blockrate

import java.time.Instant

/**
 * Guard 차단률 이상 유형.
 */
enum class GuardAnomalyType {
    /** 차단률 급증 — 공격 가능성 */
    SPIKE,

    /** 차단률 급감 — Guard 고장 가능성 */
    DROP
}

/**
 * Guard 차단률 이상 탐지 결과.
 *
 * 현재 차단률이 기준선에서 비정상적으로 벗어났을 때 생성된다.
 *
 * @property type 이상 유형 (급증 또는 급감)
 * @property currentRate 현재 차단률 (0.0~1.0)
 * @property baselineRate 기준선 차단률 (0.0~1.0)
 * @property message 사람이 읽을 수 있는 경고 메시지
 * @property timestamp 이상 감지 시각
 */
data class GuardBlockRateAnomaly(
    val type: GuardAnomalyType,
    val currentRate: Double,
    val baselineRate: Double,
    val message: String,
    val timestamp: Instant = Instant.now()
)

/**
 * Guard 차단률 통계.
 *
 * 현재 슬라이딩 윈도우의 차단률 요약 정보.
 *
 * @property blockRate 현재 차단률 (0.0~1.0)
 * @property baselineRate 기준선 차단률 (0.0~1.0)
 * @property totalRequests 윈도우 내 총 요청 수
 * @property blockedRequests 윈도우 내 차단된 요청 수
 */
data class GuardBlockRateStats(
    val blockRate: Double,
    val baselineRate: Double,
    val totalRequests: Int,
    val blockedRequests: Int
)
