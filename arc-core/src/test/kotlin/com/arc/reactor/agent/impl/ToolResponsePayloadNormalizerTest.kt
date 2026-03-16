package com.arc.reactor.agent.impl

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ToolResponsePayloadNormalizerTest {

    @Nested
    inner class ValidJson {

        @Test
        fun `json object passes through unchanged`() {
            val json = """{"key":"value"}"""
            ToolResponsePayloadNormalizer.normalizeForStrictJsonProvider(json) shouldBe json
        }

        @Test
        fun `json array passes through unchanged`() {
            val json = """[1,2,3]"""
            ToolResponsePayloadNormalizer.normalizeForStrictJsonProvider(json) shouldBe json
        }

        @Test
        fun `json with whitespace padding passes through trimmed`() {
            val json = """  {"key":"value"}  """
            ToolResponsePayloadNormalizer.normalizeForStrictJsonProvider(json) shouldBe """{"key":"value"}"""
        }
    }

    @Nested
    inner class NonJsonWrapping {

        @Test
        fun `plain text gets wrapped in result object`() {
            val result = ToolResponsePayloadNormalizer.normalizeForStrictJsonProvider("hello world")
            result shouldContain "\"result\""
            result shouldContain "hello world"
        }

        @Test
        fun `empty string gets wrapped in result object`() {
            val result = ToolResponsePayloadNormalizer.normalizeForStrictJsonProvider("")
            result shouldContain "\"result\""
        }

        @Test
        fun `whitespace-only string gets wrapped`() {
            val result = ToolResponsePayloadNormalizer.normalizeForStrictJsonProvider("   ")
            result shouldContain "\"result\""
        }

        @Test
        fun `malformed json gets wrapped`() {
            val result = ToolResponsePayloadNormalizer.normalizeForStrictJsonProvider("{broken")
            result shouldContain "\"result\""
            result shouldContain "{broken"
        }

        @Test
        fun `trailing content after json gets wrapped`() {
            val result = ToolResponsePayloadNormalizer.normalizeForStrictJsonProvider("""{"a":1} extra""")
            result shouldContain "\"result\""
        }
    }
}
