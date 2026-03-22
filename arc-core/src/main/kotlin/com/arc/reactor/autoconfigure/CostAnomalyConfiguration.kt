package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.budget.CostAnomalyDetector
import com.arc.reactor.agent.budget.CostAnomalyHook
import com.arc.reactor.agent.budget.DefaultCostAnomalyDetector
import com.arc.reactor.agent.config.AgentProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 비용 이상 탐지 자동 설정.
 *
 * `arc.reactor.cost-anomaly.enabled=true`일 때 활성화된다.
 * [CostAnomalyDetector]와 [CostAnomalyHook] 빈을 등록한다.
 *
 * @see com.arc.reactor.agent.budget.DefaultCostAnomalyDetector 기본 탐지기
 * @see com.arc.reactor.agent.budget.CostAnomalyHook AfterAgentComplete Hook
 */
@Configuration
@ConditionalOnProperty(
    prefix = "arc.reactor.cost-anomaly", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
class CostAnomalyConfiguration {

    /** 비용 이상 탐지기: 슬라이딩 윈도우 기반 이동 평균 비교. */
    @Bean
    @ConditionalOnMissingBean
    fun costAnomalyDetector(properties: AgentProperties): CostAnomalyDetector {
        val config = properties.costAnomaly
        return DefaultCostAnomalyDetector(
            windowSize = config.windowSize,
            thresholdMultiplier = config.thresholdMultiplier,
            minSamples = config.minSamples
        )
    }

    /** 비용 이상 탐지 Hook: 에이전트 완료 후 비용 기록 및 이상 평가. */
    @Bean
    @ConditionalOnMissingBean(name = ["costAnomalyHook"])
    fun costAnomalyHook(
        detector: CostAnomalyDetector
    ): CostAnomalyHook = CostAnomalyHook(detector)
}
