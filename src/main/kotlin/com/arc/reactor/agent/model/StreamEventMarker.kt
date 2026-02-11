package com.arc.reactor.agent.model

/**
 * Markers for streaming events emitted within the text Flow.
 *
 * The executor emits special marker strings alongside text tokens.
 * The controller layer parses these markers and converts them into
 * typed SSE events (tool_start, tool_end, error) for the frontend.
 *
 * Markers use a null-byte prefix (\u0000) to avoid collision with LLM output.
 */
object StreamEventMarker {

    private const val PREFIX = "\u0000__arc__"
    private const val TOOL_START = "${PREFIX}tool_start:"
    private const val TOOL_END = "${PREFIX}tool_end:"
    private const val ERROR = "${PREFIX}error:"

    fun toolStart(toolName: String): String = "$TOOL_START$toolName"

    fun toolEnd(toolName: String): String = "$TOOL_END$toolName"

    fun error(message: String): String = "$ERROR$message"

    fun isMarker(text: String): Boolean = text.startsWith(PREFIX)

    /**
     * Parses a marker string into (eventType, payload).
     * Returns null if the text is not a marker.
     */
    fun parse(text: String): Pair<String, String>? = when {
        text.startsWith(TOOL_START) -> "tool_start" to text.removePrefix(TOOL_START)
        text.startsWith(TOOL_END) -> "tool_end" to text.removePrefix(TOOL_END)
        text.startsWith(ERROR) -> "error" to text.removePrefix(ERROR)
        else -> null
    }
}
