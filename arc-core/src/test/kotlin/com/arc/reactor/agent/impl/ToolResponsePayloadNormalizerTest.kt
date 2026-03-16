package com.arc.reactor.agent.impl

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ToolResponsePayloadNormalizerTest {

    @Nested
    inner class ValidJson {

        @Test
        fun `JSON object은(는) unchanged를 통과시킨다`() {
            val json = """{"key":"value"}"""
            ToolResponsePayloadNormalizer.normalizeForStrictJsonProvider(json) shouldBe json
        }

        @Test
        fun `JSON array은(는) unchanged를 통과시킨다`() {
            val json = """[1,2,3]"""
            ToolResponsePayloadNormalizer.normalizeForStrictJsonProvider(json) shouldBe json
        }

        @Test
        fun `JSON with whitespace padding은(는) trimmed를 통과시킨다`() {
            val json = """  {"key":"value"}  """
            ToolResponsePayloadNormalizer.normalizeForStrictJsonProvider(json) shouldBe """{"key":"value"}"""
        }
    }

    @Nested
    inner class NonJsonWrapping {

        @Test
        fun `plain은(는) text gets wrapped in result object`() {
            val result = ToolResponsePayloadNormalizer.normalizeForStrictJsonProvider("hello world")
            result shouldContain "\"result\""
            result shouldContain "hello world"
        }

        @Test
        fun `비어있는 string gets wrapped in result object`() {
            val result = ToolResponsePayloadNormalizer.normalizeForStrictJsonProvider("")
            result shouldContain "\"result\""
        }

        @Test
        fun `공백만 있는 string gets wrapped`() {
            val result = ToolResponsePayloadNormalizer.normalizeForStrictJsonProvider("   ")
            result shouldContain "\"result\""
        }

        @Test
        fun `malformed은(는) json gets wrapped`() {
            val result = ToolResponsePayloadNormalizer.normalizeForStrictJsonProvider("{broken")
            result shouldContain "\"result\""
            result shouldContain "{broken"
        }

        @Test
        fun `json gets wrapped후 trailing content`() {
            val result = ToolResponsePayloadNormalizer.normalizeForStrictJsonProvider("""{"a":1} extra""")
            result shouldContain "\"result\""
        }
    }
}
