package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.ToolRoutingConfig
import com.arc.reactor.agent.config.ToolRoutingConfigValidator
import com.arc.reactor.tool.ToolCallback
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener

/**
 * 애플리케이션 시작 시 tool-routing.yml 설정을 검증한다.
 *
 * 누락·오타로 인한 무음 품질 저하를 방지하기 위해
 * [ToolRoutingConfigValidator]를 호출한다.
 *
 * 등록된 도구 목록이 있으면 preferredTools가 실제 레지스트리에
 * 존재하는지도 크로스체크한다.
 */
class ToolRoutingValidationInitializer(
    private val toolCallbacks: ObjectProvider<List<ToolCallback>>
) {

    @EventListener(ApplicationReadyEvent::class)
    fun validateToolRouting() {
        val config = ToolRoutingConfig.loadFromClasspath()
        ToolRoutingConfigValidator.validate(config)

        val registeredToolNames = toolCallbacks.ifAvailable
            ?.map { it.name }
            ?: emptyList()
        ToolRoutingConfigValidator.validatePreferredToolsAgainstRegistry(
            config,
            registeredToolNames
        )
    }
}
