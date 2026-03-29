package com.arc.reactor.admin.tracing

import com.arc.reactor.admin.collection.TenantResolver
import com.arc.reactor.admin.config.AdminProperties
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.trace.SpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import mu.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import javax.sql.DataSource

private val logger = KotlinLogging.logger {}

@Configuration
@ConditionalOnProperty(
    prefix = "arc.reactor.admin", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
@ConditionalOnProperty(
    prefix = "arc.reactor.admin.tracing", name = ["enabled"],
    havingValue = "true", matchIfMissing = true
)
/**
 * OTLP 및 TimescaleDB span exporter 설정 클래스.
 *
 * TenantSpanProcessor, TimescaleSpanExporter, OTLP exporter를 조건부로 등록한다.
 *
 * @see TracingAutoConfiguration 트레이싱 자동 설정 (TracerProvider, ObservationRegistry)
 */
class OtlpExporterConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun tenantSpanProcessor(tenantResolver: TenantResolver): SpanProcessor {
        logger.info { "TenantSpanProcessor 등록 완료" }
        return TenantSpanProcessor(tenantResolver)
    }

    @Bean
    @ConditionalOnMissingBean(name = ["timescaleSpanExporter"])
    @ConditionalOnProperty(
        prefix = "arc.reactor.admin.tracing", name = ["timescale-export"],
        havingValue = "true", matchIfMissing = true
    )
    @ConditionalOnBean(DataSource::class)
    fun timescaleSpanExporter(jdbcTemplate: JdbcTemplate): SpanExporter {
        logger.info { "TimescaleSpanExporter 등록 완료 — span → metric_spans 테이블" }
        return TimescaleSpanExporter(jdbcTemplate)
    }

    @Bean
    @ConditionalOnMissingBean(name = ["otlpSpanExporter"])
    @ConditionalOnProperty(
        prefix = "arc.reactor.admin.tracing.otlp", name = ["enabled"],
        havingValue = "true", matchIfMissing = false
    )
    fun otlpSpanExporter(properties: AdminProperties): SpanExporter {
        val otlp = properties.tracing.otlp
        val endpoint = otlp.endpoint

        logger.info { "OTLP SpanExporter 등록 → $endpoint (protocol=${otlp.protocol})" }

        return if (otlp.protocol == "grpc") {
            val builder = OtlpGrpcSpanExporter.builder().setEndpoint(endpoint)
            for ((k, v) in otlp.headers) { builder.addHeader(k, v) }
            builder.build()
        } else {
            val builder = OtlpHttpSpanExporter.builder().setEndpoint(endpoint)
            for ((k, v) in otlp.headers) { builder.addHeader(k, v) }
            builder.build()
        }
    }
}
