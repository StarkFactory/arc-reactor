package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.scheduler.InMemoryScheduledJobStore
import com.arc.reactor.mcp.McpManager
import com.arc.reactor.scheduler.DynamicSchedulerService
import com.arc.reactor.scheduler.ScheduledJobStore
import com.arc.reactor.scheduler.tool.CreateScheduledJobTool
import com.arc.reactor.scheduler.tool.DeleteScheduledJobTool
import com.arc.reactor.scheduler.tool.ListScheduledJobsTool
import com.arc.reactor.scheduler.tool.UpdateScheduledJobTool
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 스케줄러 설정에 대한 테스트.
 *
 * 스케줄러 빈 등록과 설정 적용을 검증합니다.
 */
class SchedulerConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(SchedulerConfiguration::class.java))
        .withBean(ScheduledJobStore::class.java, { mockk(relaxed = true) })
        .withBean(McpManager::class.java, { mockk(relaxed = true) })
        .withBean(AgentProperties::class.java, { AgentProperties() })

    private val dbLessContextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(SchedulerConfiguration::class.java))
        .withBean(McpManager::class.java, { mockk(relaxed = true) })
        .withBean(AgentProperties::class.java, { AgentProperties() })

    @Nested
    inner class ConditionalActivation {

        @Test
        fun `beans are NOT created when scheduler은(는) disabled이다`() {
            contextRunner
                .withPropertyValues("arc.reactor.scheduler.enabled=false")
                .run { context ->
                    context.getBeansOfType(DynamicSchedulerService::class.java).isEmpty()
                        .shouldBeTrue()
                    context.getBeansOfType(CreateScheduledJobTool::class.java).isEmpty()
                        .shouldBeTrue()
                }
        }

        @Test
        fun `beans은(는) NOT created without enabled property이다`() {
            contextRunner.run { context ->
                context.getBeansOfType(DynamicSchedulerService::class.java).isEmpty()
                    .shouldBeTrue()
            }
        }
    }

    @Nested
    inner class ToolBeanWiring {

        @Test
        fun `scheduler tool beans are created when scheduler은(는) enabled이다`() {
            contextRunner
                .withPropertyValues("arc.reactor.scheduler.enabled=true")
                .run { context ->
                    context.getBean(DynamicSchedulerService::class.java).shouldNotBeNull()
                    context.getBean(CreateScheduledJobTool::class.java).shouldNotBeNull()
                    context.getBean(ListScheduledJobsTool::class.java).shouldNotBeNull()
                    context.getBean(UpdateScheduledJobTool::class.java).shouldNotBeNull()
                    context.getBean(DeleteScheduledJobTool::class.java).shouldNotBeNull()
                }
        }

        @Test
        fun `no persistent store exists일 때 scheduler falls back to in-memory store`() {
            dbLessContextRunner
                .withPropertyValues("arc.reactor.scheduler.enabled=true")
                .run { context ->
                    context.getBean(DynamicSchedulerService::class.java).shouldNotBeNull()
                    assertEquals(
                        InMemoryScheduledJobStore::class.java,
                        context.getBean(ScheduledJobStore::class.java)::class.java,
                        "DB-less scheduler should fall back to InMemoryScheduledJobStore"
                    )
                }
        }

        @Test
        fun `도구 beans depend on DynamicSchedulerService`() {
            contextRunner
                .withPropertyValues("arc.reactor.scheduler.enabled=true")
                .run { context ->
                    context.containsBean("dynamicSchedulerService").shouldBeTrue()
                    context.containsBean("createScheduledJobTool").shouldBeTrue()
                    context.containsBean("listScheduledJobsTool").shouldBeTrue()
                    context.containsBean("updateScheduledJobTool").shouldBeTrue()
                    context.containsBean("deleteScheduledJobTool").shouldBeTrue()
                }
        }

        @Test
        fun `커스텀 tool bean overrides default via ConditionalOnMissingBean`() {
            val customTool = CreateScheduledJobTool(mockk(relaxed = true), "UTC")
            contextRunner
                .withPropertyValues("arc.reactor.scheduler.enabled=true")
                .withBean(CreateScheduledJobTool::class.java, { customTool })
                .run { context ->
                    val bean = context.getBean(CreateScheduledJobTool::class.java)
                    (bean === customTool).shouldBeTrue()
                }
        }

        @Test
        fun `scheduler은(는) service can lazily resolve agent executor without circular dependency`() {
            contextRunner
                .withPropertyValues("arc.reactor.scheduler.enabled=true")
                .withUserConfiguration(CyclicAgentExecutorConfig::class.java)
                .run { context ->
                    context.getBean(DynamicSchedulerService::class.java).shouldNotBeNull()
                    context.getBean(CreateScheduledJobTool::class.java).shouldNotBeNull()
                    context.getBean(AgentExecutor::class.java).shouldNotBeNull()
                }
        }
    }

    @Configuration
    class CyclicAgentExecutorConfig {

        @Bean
        fun agentExecutor(createScheduledJobTool: CreateScheduledJobTool): AgentExecutor {
            createScheduledJobTool.shouldNotBeNull()
            return mockk(relaxed = true)
        }
    }
}
