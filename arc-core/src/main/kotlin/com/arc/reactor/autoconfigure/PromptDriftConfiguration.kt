package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.drift.DefaultPromptDriftDetector
import com.arc.reactor.agent.drift.PromptDriftDetector
import com.arc.reactor.agent.drift.PromptDriftHook
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 프롬프트 드리프트 감지 자동 설정.
 *
 * `arc.reactor.prompt-drift.enabled=true`일 때 활성화된다.
 * [PromptDriftDetector]와 [PromptDriftHook] 빈을 등록한다.
 *
 * @see com.arc.reactor.agent.drift.DefaultPromptDriftDetector 기본 감지기
 * @see com.arc.reactor.agent.drift.PromptDriftHook AfterAgentComplete Hook
 */
@Configuration
@ConditionalOnProperty(
    prefix = "arc.reactor.prompt-drift", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
class PromptDriftConfiguration {

    /** 프롬프트 드리프트 감지기: 슬라이딩 윈도우 기반 분포 비교. */
    @Bean
    @ConditionalOnMissingBean
    fun promptDriftDetector(
        properties: AgentProperties
    ): PromptDriftDetector {
        val config = properties.promptDrift
        return DefaultPromptDriftDetector(
            windowSize = config.windowSize,
            deviationThreshold = config.deviationThreshold,
            minSamples = config.minSamples
        )
    }

    /** 프롬프트 드리프트 감지 Hook: 에이전트 완료 후 입출력 길이 기록 및 평가. */
    @Bean
    @ConditionalOnMissingBean(name = ["promptDriftHook"])
    fun promptDriftHook(
        detector: PromptDriftDetector,
        properties: AgentProperties
    ): PromptDriftHook = PromptDriftHook(
        detector = detector,
        evaluationInterval = properties.promptDrift.evaluationInterval
    )
}
