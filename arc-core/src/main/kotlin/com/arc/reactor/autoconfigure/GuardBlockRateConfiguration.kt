package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.guard.blockrate.DefaultGuardBlockRateMonitor
import com.arc.reactor.guard.blockrate.GuardBlockRateHook
import com.arc.reactor.guard.blockrate.GuardBlockRateMonitor
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Guard 차단률 베이스라인 모니터링 자동 설정.
 *
 * `arc.reactor.guard-block-rate.enabled=true`일 때 활성화된다.
 * [GuardBlockRateMonitor]와 [GuardBlockRateHook] 빈을 등록한다.
 *
 * @see com.arc.reactor.guard.blockrate.DefaultGuardBlockRateMonitor 기본 모니터
 * @see com.arc.reactor.guard.blockrate.GuardBlockRateHook AfterAgentComplete Hook
 */
@Configuration
@ConditionalOnProperty(
    prefix = "arc.reactor.guard-block-rate", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
class GuardBlockRateConfiguration {

    /** Guard 차단률 모니터: 슬라이딩 윈도우 기반 기준선 비교. */
    @Bean
    @ConditionalOnMissingBean
    fun guardBlockRateMonitor(
        properties: AgentProperties
    ): GuardBlockRateMonitor {
        val config = properties.guardBlockRate
        return DefaultGuardBlockRateMonitor(
            windowSize = config.windowSize,
            spikeMultiplier = config.spikeMultiplier,
            dropDivisor = config.dropDivisor,
            minSamples = config.minSamples
        )
    }

    /** Guard 차단률 모니터링 Hook: 에이전트 완료 후 결과 기록 및 평가. */
    @Bean
    @ConditionalOnMissingBean(name = ["guardBlockRateHook"])
    fun guardBlockRateHook(
        monitor: GuardBlockRateMonitor,
        properties: AgentProperties
    ): GuardBlockRateHook = GuardBlockRateHook(
        monitor = monitor,
        evaluationInterval = properties.guardBlockRate.evaluationInterval
    )
}
