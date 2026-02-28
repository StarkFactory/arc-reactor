package com.arc.reactor.agent.impl

import kotlin.random.Random
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@Tag("matrix")
class ToolArgumentParserFuzzTest {

    private val random = Random(20260218)
    private val alphabet = (
        ('a'..'z') +
            ('A'..'Z') +
            ('0'..'9') +
            listOf(' ', ':', ',', '[', ']', '{', '}', '"', '\'', '-', '_', '.', '!')
        ).toList()

    private fun randomText(length: Int): String {
        return buildString {
            repeat(length) { append(alphabet[random.nextInt(alphabet.size)]) }
        }
    }

    @Test
    fun `parser should return empty map for null and blank`() {
        assertTrue(parseToolArguments(null).isEmpty(), "null argument should parse to empty map")
        assertTrue(parseToolArguments("").isEmpty(), "empty string should parse to empty map")
        assertTrue(parseToolArguments("   ").isEmpty(), "blank string should parse to empty map")
    }

    @Test
    fun `parser should fail closed for 1000 malformed payloads`() {
        var checked = 0
        repeat(1_000) { i ->
            val malformed = when (i % 5) {
                0 -> "not-json-${randomText(24)}"
                1 -> """{"unterminated":${randomText(8)}"""
                2 -> """[1,2,3"""
                3 -> """{"k$i": }"""
                else -> """{"k$i":"${randomText(8)}""" // missing closing quote/brace
            }
            val parsed = parseToolArguments(malformed)
            assertTrue(parsed.isEmpty(), "input='$malformed' should fail-closed")
            checked++
        }
        assertEquals(1_000, checked)
    }

    @Test
    fun `parser should keep key value structure for representative valid payloads`() {
        val parsed1 = parseToolArguments("""{"city":"seoul","days":3,"metric":true}""")
        assertEquals("seoul", parsed1["city"])
        assertEquals(3, parsed1["days"])
        assertEquals(true, parsed1["metric"])

        val parsed2 = parseToolArguments("""{"nested":{"a":1},"arr":[1,2,3],"nullValue":null}""")
        assertInstanceOf(Map::class.java, parsed2["nested"]) {
            "nested key should contain a map value"
        }
        assertInstanceOf(List::class.java, parsed2["arr"]) {
            "arr key should contain a list value"
        }
        assertTrue(parsed2.containsKey("nullValue"), "Parsed map should contain 'nullValue' key for explicit JSON null")
    }

    @Test
    fun `parser should handle large valid map payload`() {
        val json = buildString {
            append("{")
            for (i in 0 until 250) {
                if (i > 0) append(",")
                append("\"k$i\":$i")
            }
            append("}")
        }

        val parsed = parseToolArguments(json)
        assertEquals(250, parsed.size)
        assertEquals(0, parsed["k0"])
        assertEquals(249, parsed["k249"])
    }
}
