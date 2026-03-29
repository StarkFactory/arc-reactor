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
/**
 * OpenTelemetry + Micrometer Tracing 자동 설정.
 *
 * SdkTracerProvider, OpenTelemetry SDK, Micrometer Tracer, ObservationRegistry를 구성한다.
 * Micrometer Tracing이 classpath에 있을 때만 활성화된다.
 *
 * Spring AI의 ChatModelObservationAutoConfiguration이 ObservationRegistry를 감지하여
 * 모든 ChatModel.call()에 gen_ai.client.operation span을 자동 부착한다.
 *
 * @see OtlpExporterConfiguration OTLP/TimescaleDB span exporter 설정
 * @see AgentTracingHooks 에이전트/도구 레벨 span 생성 hook
 */
class TracingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    /** OTel SDK TracerProvider를 구성한다. 샘플링률, SpanProcessor, SpanExporter를 등록한다. */
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
        logger.info { "트레이싱 샘플러: ${sampler.description} (sampling-rate=$samplingRate)" }

        // ── 단계: SpanProcessor 등록 (예: TenantSpanProcessor) ──
        for (processor in spanProcessors.orderedStream().toList()) {
            builder.addSpanProcessor(processor)
            logger.debug { "SpanProcessor 등록: ${processor.javaClass.simpleName}" }
        }

        // ── 단계: SpanExporter를 BatchSpanProcessor로 감싸서 등록 ──
        for (exporter in spanExporters.orderedStream().toList()) {
            builder.addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
            logger.info { "SpanExporter 등록: ${exporter.javaClass.simpleName}" }
        }

        return builder.build()
    }

    @Bean
    @ConditionalOnMissingBean
    /** W3C Trace Context propagation이 적용된 OpenTelemetry SDK를 생성한다. */
    fun openTelemetry(tracerProvider: SdkTracerProvider): OpenTelemetry =
        OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build()

    @Bean
    @ConditionalOnMissingBean
    /** OTel → Micrometer Tracing 브릿지 Tracer를 생성한다. */
    fun micrometerTracer(otel: OpenTelemetry): Tracer {
        val otelTracer = otel.getTracer("arc-reactor")
        val currentTraceContext = OtelCurrentTraceContext()
        val baggageManager = OtelBaggageManager(currentTraceContext, emptyList(), emptyList())
        return OtelTracer(otelTracer, currentTraceContext, { null }, baggageManager)
    }

    @Bean
    @ConditionalOnMissingBean
    /**
     * Micrometer Observation → OTel span 브릿지가 적용된 ObservationRegistry를 생성한다.
     *
     * Spring AI의 ChatModelObservationAutoConfiguration이 이 registry를 감지하여
     * 모든 ChatModel.call()에 gen_ai.client.operation span을 자동 부착한다.
     */
    fun observationRegistry(tracer: Tracer): ObservationRegistry {
        val registry = ObservationRegistry.create()
        registry.observationConfig()
            .observationHandler(DefaultTracingObservationHandler(tracer))
        logger.info { "ObservationRegistry 생성 완료 — Spring AI 자동 관측 활성화" }
        return registry
    }
}
