package com.arc.reactor.admin.tracing

import com.arc.reactor.admin.config.AdminProperties
import io.micrometer.observation.ObservationRegistry
import io.micrometer.tracing.Tracer
import io.micrometer.tracing.handler.DefaultTracingObservationHandler
import io.micrometer.tracing.otel.bridge.OtelBaggageManager
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext
import io.micrometer.tracing.otel.bridge.OtelTracer
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.SpanProcessor
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import io.opentelemetry.sdk.trace.samplers.Sampler
import mu.KotlinLogging
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.info.BuildProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

private val logger = KotlinLogging.logger {}

@Configuration
@ConditionalOnProperty(
    prefix = "arc.reactor.admin", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
@ConditionalOnClass(name = ["io.micrometer.tracing.Tracer"])
@EnableConfigurationProperties(AdminProperties::class)
class TracingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun otelSdkTracerProvider(
        properties: AdminProperties,
        spanProcessors: ObjectProvider<SpanProcessor>,
        spanExporters: ObjectProvider<SpanExporter>,
        buildProperties: ObjectProvider<BuildProperties>
    ): SdkTracerProvider {
        val version = buildProperties.ifAvailable?.version ?: "unknown"
        val resource = Resource.create(
            Attributes.builder()
                .put(AttributeKey.stringKey("service.name"), "arc-reactor")
                .put(AttributeKey.stringKey("service.version"), version)
                .put(AttributeKey.stringKey("instance.id"), properties.scaling.instanceId)
                .build()
        )

        val samplingRate = properties.tracing.samplingRate
        val sampler = if (samplingRate >= 1.0) {
            Sampler.alwaysOn()
        } else {
            Sampler.traceIdRatioBased(samplingRate)
        }

        val builder = SdkTracerProvider.builder()
            .setResource(resource)
            .setSampler(sampler)
        logger.info { "Tracing sampler: ${sampler.description} (sampling-rate=$samplingRate)" }

        // Register all SpanProcessors (e.g., TenantSpanProcessor)
        spanProcessors.orderedStream().forEach { processor ->
            builder.addSpanProcessor(processor)
            logger.debug { "Registered SpanProcessor: ${processor.javaClass.simpleName}" }
        }

        // Wrap each SpanExporter in a BatchSpanProcessor
        spanExporters.orderedStream().forEach { exporter ->
            builder.addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
            logger.info { "Registered SpanExporter: ${exporter.javaClass.simpleName}" }
        }

        return builder.build()
    }

    @Bean
    @ConditionalOnMissingBean
    fun openTelemetry(tracerProvider: SdkTracerProvider): OpenTelemetry =
        OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build()

    @Bean
    @ConditionalOnMissingBean
    fun micrometerTracer(otel: OpenTelemetry): Tracer {
        val otelTracer = otel.getTracer("arc-reactor")
        val currentTraceContext = OtelCurrentTraceContext()
        val baggageManager = OtelBaggageManager(currentTraceContext, emptyList(), emptyList())
        return OtelTracer(otelTracer, currentTraceContext, { null }, baggageManager)
    }

    @Bean
    @ConditionalOnMissingBean
    fun observationRegistry(tracer: Tracer): ObservationRegistry {
        val registry = ObservationRegistry.create()
        // Wire tracer into ObservationRegistry via DefaultTracingObservationHandler.
        // This bridges Micrometer Observation → OTel spans, enabling Spring AI auto-observation:
        // ChatModelObservationAutoConfiguration detects ObservationRegistry and wraps
        // all ChatModel.call() with gen_ai.client.operation spans automatically.
        registry.observationConfig()
            .observationHandler(DefaultTracingObservationHandler(tracer))
        logger.info { "ObservationRegistry created with tracing bridge — Spring AI auto-observation enabled" }
        return registry
    }
}
