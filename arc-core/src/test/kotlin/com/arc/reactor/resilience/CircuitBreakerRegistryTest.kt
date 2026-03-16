package com.arc.reactor.resilience

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * 에 대한 테스트. [CircuitBreakerRegistry].
 *
 * 대상: lazy creation, name isolation, resetAll, and getIfExists.
 */
class CircuitBreakerRegistryTest {

    @Nested
    inner class LazyCreation {

        @Test
        fun `create breaker on first access해야 한다`() {
            val registry = CircuitBreakerRegistry()
            val breaker = registry.get("llm")

            assertNotNull(breaker) { "Breaker should be created" }
            assertEquals(CircuitBreakerState.CLOSED, breaker.state()) {
                "New breaker should start CLOSED"
            }
            assertTrue(registry.names().contains("llm")) {
                "Registry should track the name"
            }
        }

        @Test
        fun `same name에 대해 return same instance해야 한다`() {
            val registry = CircuitBreakerRegistry()
            val first = registry.get("llm")
            val second = registry.get("llm")

            assertSame(first, second) { "Same name should return same instance" }
        }
    }

    @Nested
    inner class NameIsolation {

        @Test
        fun `different names에 대해 create separate breakers해야 한다`() {
            val registry = CircuitBreakerRegistry()
            val llm = registry.get("llm")
            val mcp = registry.get("mcp:weather")

            assertNotSame(llm, mcp) { "Different names should have different breakers" }
            assertEquals(setOf("llm", "mcp:weather"), registry.names()) {
                "Registry should track all names"
            }
        }

        @Test
        fun `isolate state between breakers해야 한다`() = runTest {
            val registry = CircuitBreakerRegistry(failureThreshold = 2)
            val llm = registry.get("llm")
            val mcp = registry.get("mcp:server1")

            // the LLM breaker를 트립시킵니다
            repeat(2) {
                runCatching { llm.execute { throw RuntimeException("llm fail") } }
            }

            assertEquals(CircuitBreakerState.OPEN, llm.state()) {
                "LLM breaker should be OPEN"
            }
            assertEquals(CircuitBreakerState.CLOSED, mcp.state()) {
                "MCP breaker should remain CLOSED (isolated)"
            }
        }
    }

    @Nested
    inner class ResetAll {

        @Test
        fun `reset all breakers해야 한다`() = runTest {
            val registry = CircuitBreakerRegistry(failureThreshold = 1)
            val b1 = registry.get("a")
            val b2 = registry.get("b")

            // both를 트립시킵니다
            runCatching { b1.execute { throw RuntimeException("fail") } }
            runCatching { b2.execute { throw RuntimeException("fail") } }

            assertEquals(CircuitBreakerState.OPEN, b1.state()) { "b1 should be OPEN" }
            assertEquals(CircuitBreakerState.OPEN, b2.state()) { "b2 should be OPEN" }

            registry.resetAll()

            assertEquals(CircuitBreakerState.CLOSED, b1.state()) { "b1 should be CLOSED after resetAll" }
            assertEquals(CircuitBreakerState.CLOSED, b2.state()) { "b2 should be CLOSED after resetAll" }
        }
    }

    @Nested
    inner class GetIfExists {

        @Test
        fun `non-existent breaker에 대해 return null해야 한다`() {
            val registry = CircuitBreakerRegistry()
            assertNull(registry.getIfExists("nonexistent")) {
                "Should return null for breaker that hasn't been created"
            }
        }

        @Test
        fun `return existing breaker해야 한다`() {
            val registry = CircuitBreakerRegistry()
            val created = registry.get("llm")
            val found = registry.getIfExists("llm")

            assertSame(created, found) { "getIfExists should return the same instance" }
        }
    }
}
