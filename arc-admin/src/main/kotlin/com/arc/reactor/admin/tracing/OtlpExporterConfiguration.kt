package com.arc.reactor.admin.tracing

import com.arc.reactor.admin.collection.TenantResolver
import com.arc.reactor.admin.config.AdminProperties
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.trace.SpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import mu.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
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
class OtlpExporterConfiguration {

    @Bean
    fun tenantSpanProcessor(tenantResolver: TenantResolver): SpanProcessor {
        logger.info { "TenantSpanProcessor registered" }
        return TenantSpanProcessor(tenantResolver)
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "arc.reactor.admin.tracing", name = ["timescale-export"],
        havingValue = "true", matchIfMissing = true
    )
    @ConditionalOnBean(DataSource::class)
    fun timescaleSpanExporter(jdbcTemplate: JdbcTemplate): SpanExporter {
        logger.info { "TimescaleSpanExporter registered — spans → metric_spans table" }
        return TimescaleSpanExporter(jdbcTemplate)
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "arc.reactor.admin.tracing.otlp", name = ["enabled"],
        havingValue = "true", matchIfMissing = false
    )
    fun otlpSpanExporter(properties: AdminProperties): SpanExporter {
        val otlp = properties.tracing.otlp
        val endpoint = otlp.endpoint

        logger.info { "OTLP SpanExporter → $endpoint (protocol=${otlp.protocol})" }

        return if (otlp.protocol == "grpc") {
            val builder = OtlpGrpcSpanExporter.builder().setEndpoint(endpoint)
            otlp.headers.forEach { (k, v) -> builder.addHeader(k, v) }
            builder.build()
        } else {
            val builder = OtlpHttpSpanExporter.builder().setEndpoint(endpoint)
            otlp.headers.forEach { (k, v) -> builder.addHeader(k, v) }
            builder.build()
        }
    }
}
