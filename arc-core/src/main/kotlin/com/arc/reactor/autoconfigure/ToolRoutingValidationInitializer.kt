package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.ToolRoutingConfig
import com.arc.reactor.agent.config.ToolRoutingConfigValidator
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener

/**
 * 애플리케이션 시작 시 tool-routing.yml 설정을 검증한다.
 *
 * 누락·오타로 인한 무음 품질 저하를 방지하기 위해
 * [ToolRoutingConfigValidator]를 호출한다.
 */
class ToolRoutingValidationInitializer {

    @EventListener(ApplicationReadyEvent::class)
    fun validateToolRouting() {
        val config = ToolRoutingConfig.loadFromClasspath()
        ToolRoutingConfigValidator.validate(config)
    }
}
