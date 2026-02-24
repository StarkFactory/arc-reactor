package com.arc.reactor.admin.config

import com.arc.reactor.admin.collection.MetricCollectionHook
import com.arc.reactor.admin.collection.MetricCollectorAgentMetrics
import com.arc.reactor.admin.collection.MetricRingBuffer
import com.arc.reactor.admin.collection.PipelineHealthMonitor
import com.arc.reactor.admin.collection.TenantResolver
import com.arc.reactor.admin.collection.TenantWebFilter
import com.arc.reactor.admin.pricing.CostCalculator
import com.arc.reactor.admin.pricing.InMemoryModelPricingStore
import com.arc.reactor.admin.pricing.ModelPricingStore
import com.arc.reactor.admin.tenant.InMemoryTenantStore
import com.arc.reactor.admin.tenant.TenantService
import com.arc.reactor.admin.tenant.TenantStore
import com.arc.reactor.admin.tracing.AgentTracingHooks
import com.arc.reactor.agent.metrics.AgentMetrics
import io.micrometer.tracing.Tracer
import mu.KotlinLogging
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

private val logger = KotlinLogging.logger {}

@AutoConfiguration
@EnableConfigurationProperties(AdminProperties::class)
@ConditionalOnProperty(
    prefix = "arc.reactor.admin", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
class AdminAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun tenantStore(): TenantStore {
        logger.info { "Using InMemoryTenantStore (no DataSource for admin)" }
        return InMemoryTenantStore()
    }

    @Bean
    @ConditionalOnMissingBean
    fun modelPricingStore(): ModelPricingStore {
        logger.info { "Using InMemoryModelPricingStore (no DataSource for admin)" }
        return InMemoryModelPricingStore()
    }

    @Bean
    @ConditionalOnMissingBean
    fun costCalculator(pricingStore: ModelPricingStore): CostCalculator =
        CostCalculator(pricingStore)

    @Bean
    @ConditionalOnMissingBean
    fun tenantResolver(): TenantResolver = TenantResolver()

    @Bean
    @ConditionalOnMissingBean
    fun tenantWebFilter(tenantResolver: TenantResolver): TenantWebFilter =
        TenantWebFilter(tenantResolver)

    @Bean
    @ConditionalOnMissingBean
    fun tenantService(
        tenantStore: TenantStore,
        properties: AdminProperties
    ): TenantService = TenantService(tenantStore, properties)

    @Bean
    @ConditionalOnMissingBean
    fun metricRingBuffer(properties: AdminProperties): MetricRingBuffer =
        MetricRingBuffer(properties.collection.ringBufferSize)

    @Bean
    @ConditionalOnMissingBean
    fun pipelineHealthMonitor(): PipelineHealthMonitor = PipelineHealthMonitor()

    @Bean
    @Primary
    @ConditionalOnMissingBean(name = ["metricCollectorAgentMetrics"])
    fun metricCollectorAgentMetrics(
        ringBuffer: MetricRingBuffer,
        tenantResolver: TenantResolver,
        healthMonitor: PipelineHealthMonitor
    ): AgentMetrics = MetricCollectorAgentMetrics(
        ringBuffer = ringBuffer,
        tenantResolver = tenantResolver,
        healthMonitor = healthMonitor
    )

    @Bean
    @ConditionalOnMissingBean
    fun metricCollectionHook(
        ringBuffer: MetricRingBuffer,
        tenantResolver: TenantResolver,
        healthMonitor: PipelineHealthMonitor
    ): MetricCollectionHook = MetricCollectionHook(
        ringBuffer = ringBuffer,
        tenantResolver = tenantResolver,
        healthMonitor = healthMonitor
    )

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(Tracer::class)
    fun agentTracingHooks(
        tracer: Tracer,
        tenantResolver: TenantResolver
    ): AgentTracingHooks {
        logger.info { "AgentTracingHooks registered (order=199)" }
        return AgentTracingHooks(tracer, tenantResolver)
    }

    @Bean
    @ConditionalOnMissingBean
    fun metricIngestionController(
        ringBuffer: MetricRingBuffer
    ): com.arc.reactor.admin.controller.MetricIngestionController =
        com.arc.reactor.admin.controller.MetricIngestionController(ringBuffer)
}
