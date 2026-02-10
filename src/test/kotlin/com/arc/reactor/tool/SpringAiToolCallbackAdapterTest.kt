package com.arc.reactor.tool

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows

class SpringAiToolCallbackAdapterTest {

    // Test double that mimics Spring AI ToolCallback
    class FakeSpringCallback {
        fun getName(): String = "test-tool"
        fun getDescription(): String = "A test tool"
        fun call(args: String): String = "Result: $args"
    }

    // Object with no matching methods at all
    class EmptyObject

    // Object with only getName
    class NameOnlyCallback {
        fun getName(): String = "name-only-tool"
    }

    @Test
    fun `name returns unknown when object has no getName method`() {
        val adapter = SpringAiToolCallbackAdapter(EmptyObject())

        assertEquals("unknown", adapter.name) { "Name should be 'unknown' for object without getName" }
    }

    @Test
    fun `description returns empty string when object has no getDescription method`() {
        val adapter = SpringAiToolCallbackAdapter(EmptyObject())

        assertEquals("", adapter.description) { "Description should be empty for object without getDescription" }
    }

    @Test
    fun `call throws RuntimeException when object has no call method`() {
        val adapter = SpringAiToolCallbackAdapter(EmptyObject())

        val exception = assertThrows<RuntimeException> {
            runBlocking {
                adapter.call(mapOf("key" to "value"))
            }
        }

        assertTrue(exception.message!!.contains("'call' method not found"),
            "Exception should mention missing 'call' method")
    }

    @Test
    fun `wrapping object with getName getDescription and call methods works correctly`() {
        val fake = FakeSpringCallback()
        val adapter = SpringAiToolCallbackAdapter(fake)

        assertEquals("test-tool", adapter.name) { "Name should come from FakeSpringCallback.getName()" }
        assertEquals("A test tool", adapter.description) { "Description should come from FakeSpringCallback.getDescription()" }

        val result = runBlocking {
            adapter.call(mapOf("query" to "hello"))
        }

        assertTrue((result as String).startsWith("Result: "),
            "Result should start with 'Result: ', got: $result")
    }

    @Test
    fun `unwrap returns the original object`() {
        val fake = FakeSpringCallback()
        val adapter = SpringAiToolCallbackAdapter(fake)

        assertSame(fake, adapter.unwrap()) { "unwrap() should return the original wrapped object" }
    }

    @Test
    fun `arguments are serialized to JSON correctly`() {
        val fake = FakeSpringCallback()
        val adapter = SpringAiToolCallbackAdapter(fake)

        val result = runBlocking {
            adapter.call(mapOf("name" to "arc", "count" to 42))
        } as String

        // The fake callback returns "Result: <json>", so extract the JSON part
        val json = result.removePrefix("Result: ")
        assertTrue(json.contains("\"name\""), "JSON should contain 'name' key, got: $json")
        assertTrue(json.contains("\"arc\""), "JSON should contain 'arc' value, got: $json")
        assertTrue(json.contains("\"count\""), "JSON should contain 'count' key, got: $json")
        assertTrue(json.contains("42"), "JSON should contain 42 value, got: $json")
    }

    @Test
    fun `name returns value from getName method on partial object`() {
        val adapter = SpringAiToolCallbackAdapter(NameOnlyCallback())

        assertEquals("name-only-tool", adapter.name) { "Name should come from getName method" }
        assertEquals("", adapter.description) { "Description should be empty for object without getDescription" }
    }

    @Test
    fun `call with empty arguments serializes to empty JSON object`() {
        val fake = FakeSpringCallback()
        val adapter = SpringAiToolCallbackAdapter(fake)

        val result = runBlocking {
            adapter.call(emptyMap())
        } as String

        assertEquals("Result: {}", result) { "Empty args should serialize to empty JSON object" }
    }
}
