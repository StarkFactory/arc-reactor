package com.arc.reactor.agent.budget

import java.time.Instant

/**
 * 비용 이상 탐지 결과.
 *
 * 요청당 비용이 이동 평균 기준선의 [threshold]배를 초과했을 때 생성된다.
 *
 * @property currentCost 이상으로 판단된 요청의 비용 (USD)
 * @property baselineCost 이동 평균 기준선 비용 (USD)
 * @property multiplier 기준선 대비 실제 배수 (예: 3.5x)
 * @property threshold 설정된 임계 배수 (예: 3.0x)
 * @property message 사람이 읽을 수 있는 경고 메시지
 * @property timestamp 이상 감지 시각
 */
data class CostAnomaly(
    val currentCost: Double,
    val baselineCost: Double,
    val multiplier: Double,
    val threshold: Double,
    val message: String,
    val timestamp: Instant = Instant.now()
)
