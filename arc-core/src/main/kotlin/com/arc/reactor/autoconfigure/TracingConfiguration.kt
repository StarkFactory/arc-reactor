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
 * Auto-configuration for Arc Reactor core tracing.
 *
 * Bean resolution order (first match wins via [@ConditionalOnMissingBean]):
 * 1. [arcReactorOtelTracer] — created when OTel API is present, an [io.opentelemetry.api.OpenTelemetry]
 *    bean exists in the context, and `arc.reactor.tracing.enabled=true` (the default).
 * 2. [noOpTracer] — created as a fallback when [arcReactorOtelTracer] was not registered.
 *
 * Users can override either bean by providing their own [ArcReactorTracer] bean.
 * This configuration is completely independent of the admin module and does NOT
 * require `arc.reactor.admin.enabled=true`.
 */
class TracingConfiguration {

    /**
     * OTel-backed tracer.
     *
     * All conditions must be true for this bean to be registered:
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
     * Registered when no other [ArcReactorTracer] bean was created — i.e., when the
     * OTel API is absent, tracing is disabled, or no `OpenTelemetry` bean is available.
     * All operations are empty and allocation-free.
     */
    @Bean
    @ConditionalOnMissingBean(ArcReactorTracer::class)
    fun noOpTracer(): ArcReactorTracer {
        logger.debug { "ArcReactorTracer: using NoOp (OTel absent or disabled)" }
        return NoOpArcReactorTracer()
    }
}
