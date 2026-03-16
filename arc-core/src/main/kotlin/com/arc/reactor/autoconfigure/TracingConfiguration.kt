package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.tracing.ArcReactorTracer
import com.arc.reactor.tracing.NoOpArcReactorTracer
import com.arc.reactor.tracing.impl.OtelArcReactorTracer
import mu.KotlinLogging
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

private val logger = KotlinLogging.logger {}

/**
 * Arc Reactor 핵심 추적 자동 설정.
 *
 * 빈 해석 순서 ([@ConditionalOnMissingBean]을 통한 선착순):
 * 1. [arcReactorOtelTracer] — created when OTel API is present, an [io.opentelemetry.api.OpenTelemetry]
 *    bean exists in the context, and `arc.reactor.tracing.enabled=true` (the default).
 * 2. [noOpTracer] — created as a fallback when [arcReactorOtelTracer] was not registered.
 *
 * 사용자는 자체 [ArcReactorTracer] 빈을 제공하여 두 빈 중 하나를 재정의할 수 있다.
 * 이 설정은 admin 모듈과 완전히 독립적이며
 * require `arc.reactor.admin.enabled=true`.
 */
class TracingConfiguration {

    /**
     * OTel-backed tracer.
     *
     * 이 빈이 등록되려면 모든 조건이 참이어야 한다:
     * - `arc.reactor.tracing.enabled=true` (default)
     * - `io.opentelemetry.api.OpenTelemetry` class is on the classpath
     * - An `io.opentelemetry.api.OpenTelemetry` bean is available in the Spring context
     * - No user-provided [ArcReactorTracer] bean already exists
     */
    @Bean
    @ConditionalOnMissingBean(ArcReactorTracer::class)
    @ConditionalOnProperty(
        prefix = "arc.reactor.tracing", name = ["enabled"],
        havingValue = "true", matchIfMissing = true
    )
    @ConditionalOnClass(name = ["io.opentelemetry.api.OpenTelemetry"])
    @ConditionalOnBean(type = ["io.opentelemetry.api.OpenTelemetry"])
    fun arcReactorOtelTracer(
        openTelemetryProvider: ObjectProvider<io.opentelemetry.api.OpenTelemetry>,
        properties: AgentProperties
    ): ArcReactorTracer {
        val otel = requireNotNull(openTelemetryProvider.ifAvailable) {
            "OpenTelemetry bean required for arcReactorOtelTracer"
        }
        val tracer = otel.getTracer(properties.tracing.serviceName)
        logger.info { "ArcReactorTracer: OTel active (service=${properties.tracing.serviceName})" }
        return OtelArcReactorTracer(tracer)
    }

    /**
     * No-op fallback tracer.
     *
     * 다른 [ArcReactorTracer] 빈이 생성되지 않았을 때 등록된다 — 즉,
     * OTel API is absent, tracing is disabled, or no `OpenTelemetry` bean is available.
     * 모든 연산이 비어 있으며 할당이 없다.
     */
    @Bean
    @ConditionalOnMissingBean(ArcReactorTracer::class)
    fun noOpTracer(): ArcReactorTracer {
        logger.debug { "ArcReactorTracer: using NoOp (OTel absent or disabled)" }
        return NoOpArcReactorTracer()
    }
}
