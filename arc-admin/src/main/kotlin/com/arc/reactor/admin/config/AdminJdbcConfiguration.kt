package com.arc.reactor.admin.config

import com.arc.reactor.admin.alert.AlertEvaluator
import com.arc.reactor.admin.alert.AlertNotificationService
import com.arc.reactor.admin.alert.AlertNotifier
import com.arc.reactor.admin.alert.AlertRuleStore
import com.arc.reactor.admin.alert.AlertScheduler
import com.arc.reactor.admin.alert.BaselineCalculator
import com.arc.reactor.admin.alert.JdbcAlertRuleStore
import com.arc.reactor.admin.alert.LogAlertNotifier
import com.arc.reactor.admin.alert.QuotaEnforcerHook
import com.arc.reactor.admin.collection.JdbcMetricEventStore
import com.arc.reactor.admin.collection.MetricEventStore
import com.arc.reactor.admin.collection.MetricRingBuffer
import com.arc.reactor.admin.collection.MetricWriter
import com.arc.reactor.admin.collection.PipelineHealthMonitor
import com.arc.reactor.admin.pricing.JdbcModelPricingStore
import com.arc.reactor.admin.pricing.ModelPricingStore
import com.arc.reactor.admin.query.DashboardService
import com.arc.reactor.admin.query.ExportService
import com.arc.reactor.admin.query.MetricQueryService
import com.arc.reactor.admin.query.SloService
import com.arc.reactor.admin.tenant.JdbcTenantStore
import com.arc.reactor.admin.tenant.TenantStore
import mu.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import javax.sql.DataSource

private val logger = KotlinLogging.logger {}

@Configuration
@ConditionalOnProperty(
    prefix = "arc.reactor.admin", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
@ConditionalOnBean(DataSource::class)
class AdminJdbcConfiguration {

    @Bean
    @Primary
    fun jdbcTenantStore(jdbcTemplate: JdbcTemplate): TenantStore {
        logger.info { "Using JdbcTenantStore" }
        return JdbcTenantStore(jdbcTemplate)
    }

    @Bean
    @Primary
    fun jdbcModelPricingStore(jdbcTemplate: JdbcTemplate): ModelPricingStore {
        logger.info { "Using JdbcModelPricingStore" }
        return JdbcModelPricingStore(jdbcTemplate)
    }

    @Bean
    fun metricEventStore(jdbcTemplate: JdbcTemplate): MetricEventStore {
        return JdbcMetricEventStore(jdbcTemplate)
    }

    @Bean
    fun metricWriter(
        ringBuffer: MetricRingBuffer,
        metricEventStore: MetricEventStore,
        costCalculator: com.arc.reactor.admin.pricing.CostCalculator,
        properties: AdminProperties,
        healthMonitor: PipelineHealthMonitor
    ): MetricWriter {
        val writer = MetricWriter(
            ringBuffer = ringBuffer,
            store = metricEventStore,
            costCalculator = costCalculator,
            batchSize = properties.collection.batchSize,
            flushIntervalMs = properties.collection.flushIntervalMs,
            writerThreads = properties.collection.writerThreads,
            healthMonitor = healthMonitor
        )
        writer.start()
        return writer
    }

    // --- Phase 3: Query + SLO + Alert ---

    @Bean
    @ConditionalOnMissingBean
    fun metricQueryService(jdbcTemplate: JdbcTemplate): MetricQueryService =
        MetricQueryService(jdbcTemplate)

    @Bean
    @ConditionalOnMissingBean
    fun sloService(
        jdbcTemplate: JdbcTemplate,
        queryService: MetricQueryService
    ): SloService = SloService(jdbcTemplate, queryService)

    @Bean
    @ConditionalOnMissingBean
    fun dashboardService(
        jdbcTemplate: JdbcTemplate,
        queryService: MetricQueryService,
        sloService: SloService,
        tenantStore: TenantStore
    ): DashboardService = DashboardService(jdbcTemplate, queryService, sloService, tenantStore)

    @Bean
    @ConditionalOnMissingBean
    fun exportService(jdbcTemplate: JdbcTemplate): ExportService =
        ExportService(jdbcTemplate)

    @Bean
    @ConditionalOnMissingBean
    fun baselineCalculator(jdbcTemplate: JdbcTemplate): BaselineCalculator =
        BaselineCalculator(jdbcTemplate)

    @Bean
    @Primary
    fun jdbcAlertRuleStore(jdbcTemplate: JdbcTemplate): AlertRuleStore {
        logger.info { "Using JdbcAlertRuleStore" }
        return JdbcAlertRuleStore(jdbcTemplate)
    }

    @Bean
    @ConditionalOnMissingBean
    fun alertEvaluator(
        alertStore: AlertRuleStore,
        queryService: MetricQueryService,
        sloService: SloService,
        tenantStore: TenantStore,
        baselineCalculator: BaselineCalculator,
        healthMonitor: PipelineHealthMonitor
    ): AlertEvaluator = AlertEvaluator(alertStore, queryService, sloService, tenantStore, baselineCalculator, healthMonitor)

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(com.arc.reactor.resilience.CircuitBreakerRegistry::class)
    fun quotaEnforcerHook(
        tenantStore: TenantStore,
        queryService: MetricQueryService,
        circuitBreakerRegistry: com.arc.reactor.resilience.CircuitBreakerRegistry,
        healthMonitor: PipelineHealthMonitor,
        ringBuffer: MetricRingBuffer
    ): QuotaEnforcerHook {
        logger.info { "QuotaEnforcerHook registered (order=5, fail-open)" }
        return QuotaEnforcerHook(tenantStore, queryService, circuitBreakerRegistry, healthMonitor, ringBuffer)
    }

    // --- Alerting ---

    @Bean
    @ConditionalOnMissingBean
    fun alertNotificationService(
        notifiers: org.springframework.beans.factory.ObjectProvider<List<AlertNotifier>>
    ): AlertNotificationService {
        val list = notifiers.ifAvailable ?: listOf(LogAlertNotifier())
        return AlertNotificationService(list)
    }

    @Bean
    @ConditionalOnMissingBean
    fun alertScheduler(
        evaluator: AlertEvaluator,
        notificationService: AlertNotificationService,
        alertStore: AlertRuleStore
    ): AlertScheduler {
        val scheduler = AlertScheduler(evaluator, notificationService, alertStore)
        scheduler.start()
        return scheduler
    }
}
