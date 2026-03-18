package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.metrics.MicrometerSlaMetrics
import com.arc.reactor.agent.metrics.NoOpSlaMetrics
import com.arc.reactor.agent.metrics.SlaMetrics
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/**
 * SLA/SLO 메트릭 자동 설정.
 *
 * Micrometer가 클래스패스에 존재하고 [MeterRegistry] 빈이 있으면
 * [MicrometerSlaMetrics]를 등록한다. 그렇지 않으면 [NoOpSlaMetrics]를 사용한다.
 */
@AutoConfiguration
@ConditionalOnClass(name = ["io.micrometer.core.instrument.MeterRegistry"])
class SlaMetricsConfiguration {

    /**
     * Micrometer 기반 SLA 메트릭 (MeterRegistry가 사용 가능할 때).
     */
    @Bean
    @Primary
    @ConditionalOnBean(MeterRegistry::class)
    fun micrometerSlaMetrics(registry: MeterRegistry): SlaMetrics = MicrometerSlaMetrics(registry)

    /**
     * No-op SLA 메트릭 (기본: MeterRegistry가 없을 때).
     */
    @Bean
    @ConditionalOnMissingBean
    fun slaMetrics(): SlaMetrics = NoOpSlaMetrics()
}
