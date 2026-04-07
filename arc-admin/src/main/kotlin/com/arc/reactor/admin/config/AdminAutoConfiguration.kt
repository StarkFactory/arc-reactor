package com.arc.reactor.admin.config

import com.arc.reactor.admin.collection.HitlEventHook
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
/**
 * arc-admin 모듈의 기본 자동 설정 클래스.
 *
 * DataSource 없이도 동작하는 인메모리 기본 bean(TenantStore, ModelPricingStore 등)을 등록한다.
 * DataSource가 존재하면 [AdminJdbcConfiguration]이 JDBC 구현체로 대체한다.
 *
 * @see AdminJdbcConfiguration JDBC 기반 bean 설정
 * @see AdminProperties 설정 프로퍼티
 */
class AdminAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun tenantStore(): TenantStore {
        logger.info { "InMemoryTenantStore 사용 (admin용 DataSource 없음)" }
        return InMemoryTenantStore()
    }

    @Bean
    @ConditionalOnMissingBean
    fun modelPricingStore(): ModelPricingStore {
        logger.info { "InMemoryModelPricingStore 사용 (admin용 DataSource 없음)" }
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
        healthMonitor: PipelineHealthMonitor,
        costCalculator: CostCalculator
    ): AgentMetrics = MetricCollectorAgentMetrics(
        ringBuffer = ringBuffer,
        healthMonitor = healthMonitor,
        costCalculator = costCalculator
    )

    @Bean
    @ConditionalOnMissingBean
    fun metricCollectionHook(
        ringBuffer: MetricRingBuffer,
        healthMonitor: PipelineHealthMonitor,
        properties: AdminProperties
    ): MetricCollectionHook {
        logger.info { "MetricCollectionHook 생성: storeSession=${properties.privacy.storeSessionIdentifiers}, storeUser=${properties.privacy.storeUserIdentifiers}" }
        return MetricCollectionHook(
            ringBuffer = ringBuffer,
            healthMonitor = healthMonitor,
            storeUserIdentifiers = properties.privacy.storeUserIdentifiers,
            storeSessionIdentifiers = properties.privacy.storeSessionIdentifiers
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun hitlEventHook(
        ringBuffer: MetricRingBuffer,
        healthMonitor: PipelineHealthMonitor
    ): HitlEventHook = HitlEventHook(ringBuffer, healthMonitor)

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(Tracer::class)
    fun agentTracingHooks(
        tracer: Tracer,
        properties: AdminProperties
    ): AgentTracingHooks {
        logger.info { "AgentTracingHooks 등록 완료 (order=199)" }
        return AgentTracingHooks(
            tracer = tracer,
            storeUserIdentifiers = properties.privacy.storeUserIdentifiers,
            storeSessionIdentifiers = properties.privacy.storeSessionIdentifiers
        )
    }
}
