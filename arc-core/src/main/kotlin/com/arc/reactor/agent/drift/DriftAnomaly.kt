package com.arc.reactor.agent.drift

import java.time.Instant

/**
 * 프롬프트 드리프트 유형.
 */
enum class DriftType {
    /** 입력 길이 분포 변화 */
    INPUT_LENGTH,

    /** 출력 길이 분포 변화 */
    OUTPUT_LENGTH
}

/**
 * 프롬프트 드리프트 이상 탐지 결과.
 *
 * 입력 또는 출력 길이의 현재 이동 평균이 기준선에서
 * [deviationFactor] 표준편차만큼 벗어났을 때 생성된다.
 *
 * @property type 드리프트 유형 (입력 길이 / 출력 길이)
 * @property currentMean 현재 윈도우의 이동 평균
 * @property baselineMean 기준선 이동 평균
 * @property standardDeviation 기준선 표준편차
 * @property deviationFactor 기준선 대비 표준편차 배수
 * @property message 사람이 읽을 수 있는 경고 메시지
 * @property timestamp 이상 감지 시각
 */
data class DriftAnomaly(
    val type: DriftType,
    val currentMean: Double,
    val baselineMean: Double,
    val standardDeviation: Double,
    val deviationFactor: Double,
    val message: String,
    val timestamp: Instant = Instant.now()
)

/**
 * 프롬프트 드리프트 통계.
 *
 * 현재 슬라이딩 윈도우의 입출력 길이 분포 요약 정보.
 *
 * @property inputMean 입력 길이 이동 평균
 * @property inputStdDev 입력 길이 표준편차
 * @property outputMean 출력 길이 이동 평균
 * @property outputStdDev 출력 길이 표준편차
 * @property sampleCount 총 샘플 수 (입력 기준)
 */
data class DriftStats(
    val inputMean: Double,
    val inputStdDev: Double,
    val outputMean: Double,
    val outputStdDev: Double,
    val sampleCount: Int
)
