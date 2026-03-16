package com.arc.reactor.agent.metrics

import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.TokenUsage
import com.arc.reactor.resilience.CircuitBreakerState
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class AgentMetricsTest {

    @Nested
    inner class DefaultMethodsTest {

        @Test
        fun `implementation with only 3 required methods은(는) compile and work해야 한다`() {
            // Simulates an existing user implementation that only overrides the 3 original methods
            val minimalImpl = object : AgentMetrics {
                override fun recordExecution(result: AgentResult) {}
                override fun recordToolCall(toolName: String, durationMs: Long, success: Boolean) {}
                override fun recordGuardRejection(stage: String, reason: String) {}
            }

            // New default methods은(는) be callable without override해야 합니다
            assertDoesNotThrow {
                minimalImpl.recordCacheHit("key")
                minimalImpl.recordExactCacheHit("key")
                minimalImpl.recordSemanticCacheHit("key")
                minimalImpl.recordCacheMiss("key")
                minimalImpl.recordCircuitBreakerStateChange(
                    "cb", CircuitBreakerState.OPEN, CircuitBreakerState.HALF_OPEN
                )
                minimalImpl.recordFallbackAttempt("anthropic", success = false)
                minimalImpl.recordTokenUsage(TokenUsage(promptTokens = 10, completionTokens = 20))
                minimalImpl.recordStreamingExecution(AgentResult.success(content = "s", durationMs = 1))
            }
        }
    }

    @Nested
    inner class AgentMetricsInterfaceTest {

        @Test
        fun `custom implementation은(는) receive correct parameters해야 한다`() {
            var lastExecution: AgentResult? = null
            var lastToolName: String? = null
            var lastToolDuration: Long? = null
            var lastToolSuccess: Boolean? = null
            var lastGuardStage: String? = null
            var lastGuardReason: String? = null

            val trackingMetrics = object : AgentMetrics {
                override fun recordExecution(result: AgentResult) {
                    lastExecution = result
                }

                override fun recordToolCall(toolName: String, durationMs: Long, success: Boolean) {
                    lastToolName = toolName
                    lastToolDuration = durationMs
                    lastToolSuccess = success
                }

                override fun recordGuardRejection(stage: String, reason: String) {
                    lastGuardStage = stage
                    lastGuardReason = reason
                }
            }

            // recordExecution 확인
            val result = AgentResult.success(content = "test", durationMs = 150)
            trackingMetrics.recordExecution(result)
            assertNotNull(lastExecution) { "recordExecution should capture the result" }
            val captured = lastExecution!!
            assertTrue(captured.success) { "Captured result should be successful" }
            assertEquals(150L, captured.durationMs) { "Duration should match" }

            // recordToolCall 확인
            trackingMetrics.recordToolCall("calculator", durationMs = 42, success = true)
            assertEquals("calculator", lastToolName) { "Tool name should be captured" }
            assertEquals(42L, lastToolDuration) { "Tool duration should be captured" }
            assertEquals(true, lastToolSuccess) { "Tool success should be captured" }

            // recordGuardRejection 확인
            trackingMetrics.recordGuardRejection("InputValidation", "Input too long")
            assertEquals("InputValidation", lastGuardStage) { "Guard stage should be captured" }
            assertEquals("Input too long", lastGuardReason) { "Guard reason should be captured" }
        }

        @Test
        fun `custom implementation은(는) receive new metrics parameters해야 한다`() {
            var lastCacheKey: String? = null
            var cacheHit = false
            var exactCacheHits = 0
            var semanticCacheHits = 0
            var lastCbName: String? = null
            var lastCbFrom: CircuitBreakerState? = null
            var lastCbTo: CircuitBreakerState? = null
            var lastFallbackModel: String? = null
            var lastFallbackSuccess: Boolean? = null
            var lastTokenUsage: TokenUsage? = null
            var lastStreamingResult: AgentResult? = null

            val trackingMetrics = object : AgentMetrics {
                override fun recordExecution(result: AgentResult) {}
                override fun recordToolCall(toolName: String, durationMs: Long, success: Boolean) {}
                override fun recordGuardRejection(stage: String, reason: String) {}

                override fun recordCacheHit(cacheKey: String) {
                    lastCacheKey = cacheKey
                    cacheHit = true
                }

                override fun recordExactCacheHit(cacheKey: String) {
                    lastCacheKey = cacheKey
                    exactCacheHits++
                }

                override fun recordSemanticCacheHit(cacheKey: String) {
                    lastCacheKey = cacheKey
                    semanticCacheHits++
                }

                override fun recordCacheMiss(cacheKey: String) {
                    lastCacheKey = cacheKey
                    cacheHit = false
                }

                override fun recordCircuitBreakerStateChange(
                    name: String, from: CircuitBreakerState, to: CircuitBreakerState
                ) {
                    lastCbName = name
                    lastCbFrom = from
                    lastCbTo = to
                }

                override fun recordFallbackAttempt(model: String, success: Boolean) {
                    lastFallbackModel = model
                    lastFallbackSuccess = success
                }

                override fun recordTokenUsage(usage: TokenUsage) {
                    lastTokenUsage = usage
                }

                override fun recordStreamingExecution(result: AgentResult) {
                    lastStreamingResult = result
                }
            }

            // Cache hit
            trackingMetrics.recordCacheHit("sha256-abc")
            assertEquals("sha256-abc", lastCacheKey) { "Cache key should be captured" }
            assertTrue(cacheHit) { "Should record as cache hit" }

            trackingMetrics.recordExactCacheHit("sha256-exact")
            assertEquals("sha256-exact", lastCacheKey) { "Exact cache key should be captured" }
            assertEquals(1, exactCacheHits) { "Exact cache hit counter should increment" }

            trackingMetrics.recordSemanticCacheHit("sha256-semantic")
            assertEquals("sha256-semantic", lastCacheKey) { "Semantic cache key should be captured" }
            assertEquals(1, semanticCacheHits) { "Semantic cache hit counter should increment" }

            // Cache miss
            trackingMetrics.recordCacheMiss("sha256-def")
            assertEquals("sha256-def", lastCacheKey) { "Cache miss key should be captured" }
            assertFalse(cacheHit) { "Should record as cache miss" }

            // Circuit breaker state change
            trackingMetrics.recordCircuitBreakerStateChange(
                "llm", CircuitBreakerState.CLOSED, CircuitBreakerState.OPEN
            )
            assertEquals("llm", lastCbName) { "CB name should be captured" }
            assertEquals(CircuitBreakerState.CLOSED, lastCbFrom) { "CB from-state should be captured" }
            assertEquals(CircuitBreakerState.OPEN, lastCbTo) { "CB to-state should be captured" }

            // Fallback attempt
            trackingMetrics.recordFallbackAttempt("openai", success = true)
            assertEquals("openai", lastFallbackModel) { "Fallback model should be captured" }
            assertEquals(true, lastFallbackSuccess) { "Fallback success should be captured" }

            // Token usage
            val usage = TokenUsage(promptTokens = 100, completionTokens = 50, totalTokens = 150)
            trackingMetrics.recordTokenUsage(usage)
            assertNotNull(lastTokenUsage) { "Token usage should be captured" }
            val captured = lastTokenUsage!!
            assertEquals(100, captured.promptTokens) { "Prompt tokens should match" }
            assertEquals(50, captured.completionTokens) { "Completion tokens should match" }
            assertEquals(150, captured.totalTokens) { "Total tokens should match" }

            // Streaming execution
            val streamResult = AgentResult.success(content = "streamed", durationMs = 500)
            trackingMetrics.recordStreamingExecution(streamResult)
            assertNotNull(lastStreamingResult) { "Streaming result should be captured" }
            assertTrue(lastStreamingResult!!.success) { "Streaming result should be successful" }
        }
    }

    @Nested
    inner class TokenUsageTest {

        @Test
        fun `auto-calculate totalTokens해야 한다`() {
            val usage = TokenUsage(promptTokens = 100, completionTokens = 50)
            assertEquals(150, usage.totalTokens) { "totalTokens should be sum of prompt and completion" }
        }

        @Test
        fun `allow explicit totalTokens해야 한다`() {
            val usage = TokenUsage(promptTokens = 100, completionTokens = 50, totalTokens = 200)
            assertEquals(200, usage.totalTokens) { "Explicit totalTokens should override default" }
        }
    }
}
