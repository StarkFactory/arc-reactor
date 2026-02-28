package com.arc.reactor.agent.model

import kotlin.random.Random
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@Tag("matrix")
class StreamEventMarkerFuzzTest {

    private val alphabet = (
        ('a'..'z') +
            ('A'..'Z') +
            ('0'..'9') +
            listOf(' ', ':', '-', '_', '/', '{', '}', '[', ']', ',', '.', '?')
        ).toList()

    private fun randomPayload(random: Random, maxLength: Int = 80): String {
        val len = random.nextInt(maxLength + 1)
        return buildString {
            repeat(len) { append(alphabet[random.nextInt(alphabet.size)]) }
        }
    }

    @Test
    fun `marker roundtrip should hold for 1000 random payloads`() {
        val random = Random(42)

        repeat(1_000) {
            val payload = randomPayload(random)

            val startMarker = StreamEventMarker.toolStart(payload)
            assertTrue(StreamEventMarker.isMarker(startMarker), "toolStart marker should be recognized as a marker: $startMarker")
            assertEquals("tool_start" to payload, StreamEventMarker.parse(startMarker))

            val endMarker = StreamEventMarker.toolEnd(payload)
            assertTrue(StreamEventMarker.isMarker(endMarker), "toolEnd marker should be recognized as a marker: $endMarker")
            assertEquals("tool_end" to payload, StreamEventMarker.parse(endMarker))

            val errorMarker = StreamEventMarker.error(payload)
            assertTrue(StreamEventMarker.isMarker(errorMarker), "error marker should be recognized as a marker: $errorMarker")
            assertEquals("error" to payload, StreamEventMarker.parse(errorMarker))
        }
    }

    @Test
    fun `parse should ignore non marker text across random corpus`() {
        val random = Random(2026)
        repeat(1_000) {
            val text = randomPayload(random)
            assertFalse(StreamEventMarker.isMarker(text), "Random text should not be recognized as a stream marker: '$text'")
            assertNull(StreamEventMarker.parse(text), "Parsing random non-marker text should return null: '$text'")
        }
    }
}
