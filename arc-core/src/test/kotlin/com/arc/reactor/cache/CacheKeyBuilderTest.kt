package com.arc.reactor.cache

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.ResponseFormat
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for CacheKeyBuilder.
 */
class CacheKeyBuilderTest {

    @Nested
    inner class KeyGeneration {

        @Test
        fun `same inputs produce same key`() {
            val command = AgentCommand(
                systemPrompt = "You are helpful",
                userPrompt = "Hello"
            )

            val key1 = CacheKeyBuilder.buildKey(command, listOf("tool1", "tool2"))
            val key2 = CacheKeyBuilder.buildKey(command, listOf("tool1", "tool2"))

            assertEquals(key1, key2) { "Same inputs should produce same cache key" }
        }

        @Test
        fun `different user prompts produce different keys`() {
            val cmd1 = AgentCommand(systemPrompt = "sys", userPrompt = "Hello")
            val cmd2 = AgentCommand(systemPrompt = "sys", userPrompt = "Goodbye")

            val key1 = CacheKeyBuilder.buildKey(cmd1, emptyList())
            val key2 = CacheKeyBuilder.buildKey(cmd2, emptyList())

            assertNotEquals(key1, key2) { "Different prompts should produce different keys" }
        }

        @Test
        fun `different system prompts produce different keys`() {
            val cmd1 = AgentCommand(systemPrompt = "You are helpful", userPrompt = "Hi")
            val cmd2 = AgentCommand(systemPrompt = "You are concise", userPrompt = "Hi")

            val key1 = CacheKeyBuilder.buildKey(cmd1, emptyList())
            val key2 = CacheKeyBuilder.buildKey(cmd2, emptyList())

            assertNotEquals(key1, key2) { "Different system prompts should produce different keys" }
        }

        @Test
        fun `different tool lists produce different keys`() {
            val command = AgentCommand(systemPrompt = "sys", userPrompt = "Hello")

            val key1 = CacheKeyBuilder.buildKey(command, listOf("tool1"))
            val key2 = CacheKeyBuilder.buildKey(command, listOf("tool1", "tool2"))

            assertNotEquals(key1, key2) { "Different tool lists should produce different keys" }
        }

        @Test
        fun `tool order does not affect key`() {
            val command = AgentCommand(systemPrompt = "sys", userPrompt = "Hello")

            val key1 = CacheKeyBuilder.buildKey(command, listOf("tool1", "tool2"))
            val key2 = CacheKeyBuilder.buildKey(command, listOf("tool2", "tool1"))

            assertEquals(key1, key2) { "Tool order should not affect cache key" }
        }

        @Test
        fun `different models produce different keys`() {
            val cmd1 = AgentCommand(systemPrompt = "sys", userPrompt = "Hello", model = "openai")
            val cmd2 = AgentCommand(systemPrompt = "sys", userPrompt = "Hello", model = "anthropic")

            val key1 = CacheKeyBuilder.buildKey(cmd1, emptyList())
            val key2 = CacheKeyBuilder.buildKey(cmd2, emptyList())

            assertNotEquals(key1, key2) { "Different models should produce different keys" }
        }

        @Test
        fun `different user ids produce different keys`() {
            val cmd1 = AgentCommand(systemPrompt = "sys", userPrompt = "Hello", userId = "user-a")
            val cmd2 = AgentCommand(systemPrompt = "sys", userPrompt = "Hello", userId = "user-b")

            val key1 = CacheKeyBuilder.buildKey(cmd1, emptyList())
            val key2 = CacheKeyBuilder.buildKey(cmd2, emptyList())

            assertNotEquals(key1, key2) { "Different users must not share cache keys" }
        }

        @Test
        fun `different session ids produce different keys`() {
            val cmd1 = AgentCommand(
                systemPrompt = "sys",
                userPrompt = "Hello",
                metadata = mapOf("sessionId" to "session-a")
            )
            val cmd2 = AgentCommand(
                systemPrompt = "sys",
                userPrompt = "Hello",
                metadata = mapOf("sessionId" to "session-b")
            )

            val key1 = CacheKeyBuilder.buildKey(cmd1, emptyList())
            val key2 = CacheKeyBuilder.buildKey(cmd2, emptyList())

            assertNotEquals(key1, key2) { "Different sessions must not share cache keys" }
        }

        @Test
        fun `different tenant ids produce different keys`() {
            val cmd1 = AgentCommand(
                systemPrompt = "sys",
                userPrompt = "Hello",
                metadata = mapOf("tenantId" to "tenant-a")
            )
            val cmd2 = AgentCommand(
                systemPrompt = "sys",
                userPrompt = "Hello",
                metadata = mapOf("tenantId" to "tenant-b")
            )

            val key1 = CacheKeyBuilder.buildKey(cmd1, emptyList())
            val key2 = CacheKeyBuilder.buildKey(cmd2, emptyList())

            assertNotEquals(key1, key2) { "Different tenants must not share cache keys" }
        }

        @Test
        fun `different response schema produces different keys`() {
            val cmd1 = AgentCommand(
                systemPrompt = "sys",
                userPrompt = "Hello",
                responseFormat = ResponseFormat.JSON,
                responseSchema = """{"type":"object","properties":{"a":{"type":"string"}}}"""
            )
            val cmd2 = AgentCommand(
                systemPrompt = "sys",
                userPrompt = "Hello",
                responseFormat = ResponseFormat.JSON,
                responseSchema = """{"type":"object","properties":{"b":{"type":"string"}}}"""
            )

            val key1 = CacheKeyBuilder.buildKey(cmd1, emptyList())
            val key2 = CacheKeyBuilder.buildKey(cmd2, emptyList())

            assertNotEquals(key1, key2) { "Different response schema must produce different keys" }
        }

        @Test
        fun `key is a valid SHA-256 hex string`() {
            val command = AgentCommand(systemPrompt = "sys", userPrompt = "test")

            val key = CacheKeyBuilder.buildKey(command, emptyList())

            assertEquals(64, key.length) { "SHA-256 hex should be 64 characters" }
            assertTrue(key.matches(Regex("[0-9a-f]+"))) { "Key should be lowercase hex: $key" }
        }

        @Test
        fun `scope fingerprint should exclude user prompt text`() {
            val cmd1 = AgentCommand(systemPrompt = "sys", userPrompt = "Question A")
            val cmd2 = AgentCommand(systemPrompt = "sys", userPrompt = "Question B")

            val fp1 = CacheKeyBuilder.buildScopeFingerprint(cmd1, listOf("tool1"))
            val fp2 = CacheKeyBuilder.buildScopeFingerprint(cmd2, listOf("tool1"))

            assertEquals(fp1, fp2) { "Scope fingerprint must stay stable across prompt variants" }
        }
    }
}
