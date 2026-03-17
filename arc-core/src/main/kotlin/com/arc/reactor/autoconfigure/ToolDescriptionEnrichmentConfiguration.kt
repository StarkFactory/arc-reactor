package com.arc.reactor.autoconfigure

import com.arc.reactor.tool.enrichment.DefaultToolDescriptionEnricher
import com.arc.reactor.tool.enrichment.ToolDescriptionEnricher
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 도구 설명 품질 분석기 자동 설정.
 *
 * [ToolDescriptionEnricher] 빈을 등록한다.
 * 사용자가 커스텀 구현을 제공하면 해당 빈이 우선 적용된다.
 */
@Configuration
class ToolDescriptionEnrichmentConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun toolDescriptionEnricher(): ToolDescriptionEnricher = DefaultToolDescriptionEnricher()
}
