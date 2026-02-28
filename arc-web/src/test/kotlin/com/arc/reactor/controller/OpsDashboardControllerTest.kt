package com.arc.reactor.controller

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.config.RagProperties
import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.UserRole
import com.arc.reactor.mcp.DefaultMcpManager
import com.arc.reactor.mcp.InMemoryMcpServerStore
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange

class OpsDashboardControllerTest {

    private fun adminExchange(): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        every { exchange.attributes } returns mutableMapOf<String, Any>(
            JwtAuthWebFilter.USER_ROLE_ATTRIBUTE to UserRole.ADMIN
        )
        return exchange
    }

    private fun userExchange(): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        every { exchange.attributes } returns mutableMapOf<String, Any>(
            JwtAuthWebFilter.USER_ROLE_ATTRIBUTE to UserRole.USER
        )
        return exchange
    }

    private fun provider(registry: MeterRegistry?): ObjectProvider<MeterRegistry> {
        val factory = StaticListableBeanFactory()
        if (registry != null) {
            factory.addBean("meterRegistry", registry)
        }
        return factory.getBeanProvider(MeterRegistry::class.java)
    }

    @Test
    fun `dashboard returns 403 for non-admin`() {
        val controller = OpsDashboardController(
            mcpManager = DefaultMcpManager(store = InMemoryMcpServerStore()),
            properties = AgentProperties(),
            meterRegistryProvider = provider(null)
        )

        val response = controller.dashboard(names = null, exchange = userExchange())
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    fun `dashboard returns metric snapshot for requested names`() {
        val registry = SimpleMeterRegistry()
        registry.counter("arc.slack.inbound.total", "entrypoint", "events").increment(3.0)

        val controller = OpsDashboardController(
            mcpManager = DefaultMcpManager(store = InMemoryMcpServerStore()),
            properties = AgentProperties(rag = RagProperties(enabled = true)),
            meterRegistryProvider = provider(registry)
        )

        val response = controller.dashboard(names = listOf("arc.slack.inbound.total"), exchange = adminExchange())
        assertEquals(HttpStatus.OK, response.statusCode)

        val body = response.body as OpsDashboardResponse
        assertTrue(body.ragEnabled, "RAG should be reported as enabled in dashboard body")
        assertEquals(1, body.metrics.size)
        assertEquals("arc.slack.inbound.total", body.metrics.first().name)
        assertEquals(3.0, body.metrics.first().measurements["count"])
    }

    @Test
    fun `metric names returns discovered metrics`() {
        val registry = SimpleMeterRegistry()
        registry.counter("arc.slack.inbound.total").increment()
        registry.counter("jvm.gc.pause").increment()

        val controller = OpsDashboardController(
            mcpManager = DefaultMcpManager(store = InMemoryMcpServerStore()),
            properties = AgentProperties(),
            meterRegistryProvider = provider(registry)
        )

        val response = controller.metricNames(exchange = adminExchange())
        assertEquals(HttpStatus.OK, response.statusCode)
        val names = response.body as List<*>
        assertTrue(names.contains("arc.slack.inbound.total"), "Metric names should include arc.slack.inbound.total")
        assertTrue(names.contains("jvm.gc.pause"), "Metric names should include jvm.gc.pause")
    }
}
