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

    class DefinitionOnlyCallback {
        fun getToolDefinition(): FakeDefinition = FakeDefinition()
        fun call(args: String): String = "Definition: $args"
    }

    class FakeDefinition {
        fun getName(): String = "definition-tool"
        fun getDescription(): String = "Provided via ToolDefinition"
        fun getInputSchema(): String = """{"type":"object","properties":{"query":{"type":"string"}}}"""
    }

    class SpringApiCallback : org.springframework.ai.tool.ToolCallback {
        private val definition = org.springframework.ai.tool.definition.ToolDefinition.builder()
            .name("spring-tool")
            .description("Provided via Spring ToolDefinition")
            .inputSchema("""{"type":"object","properties":{"id":{"type":"string"}}}""")
            .build()

        override fun getToolDefinition(): org.springframework.ai.tool.definition.ToolDefinition = definition

        override fun call(toolInput: String): String = "Spring: $toolInput"
    }

    // Object with no matching methods at all
    class EmptyObject

    // Object with only getName
    class NameOnlyCallback {
        fun getName(): String = "name-only-tool"
    }

    @Test
    fun `object has no getName method일 때 name returns unknown`() {
        val adapter = SpringAiToolCallbackAdapter(EmptyObject())

        assertEquals("unknown", adapter.name) { "Name should be 'unknown' for object without getName" }
    }

    @Test
    fun `object has no getDescription method일 때 description returns empty string`() {
        val adapter = SpringAiToolCallbackAdapter(EmptyObject())

        assertEquals("", adapter.description) { "Description should be empty for object without getDescription" }
    }

    @Test
    fun `throws RuntimeException when object has no call method를 호출한다`() {
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
    fun `wrapping은(는) object with getName getDescription and call methods works correctly`() {
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
    fun `returns the original object를 언래핑한다`() {
        val fake = FakeSpringCallback()
        val adapter = SpringAiToolCallbackAdapter(fake)

        assertSame(fake, adapter.unwrap()) { "unwrap() should return the original wrapped object" }
    }

    @Test
    fun `arguments은(는) serialized to JSON correctly이다`() {
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
    fun `available일 때 tool definition metadata은(는) preferred이다`() {
        val adapter = SpringAiToolCallbackAdapter(DefinitionOnlyCallback())

        assertEquals("definition-tool", adapter.name) { "Name should come from ToolDefinition when getName is absent" }
        assertEquals(
            "Provided via ToolDefinition",
            adapter.description
        ) { "Description should come from ToolDefinition when getDescription is absent" }
        assertEquals(
            """{"type":"object","properties":{"query":{"type":"string"}}}""",
            adapter.inputSchema
        ) { "Input schema should come from ToolDefinition" }
    }

    @Test
    fun `spring ai tool definition metadata with name methods은(는) supported이다`() {
        val adapter = SpringAiToolCallbackAdapter(SpringApiCallback())

        assertEquals("spring-tool", adapter.name) { "Name should come from ToolDefinition.name()" }
        assertEquals(
            "Provided via Spring ToolDefinition",
            adapter.description
        ) { "Description should come from ToolDefinition.description()" }
        assertEquals(
            """{"type":"object","properties":{"id":{"type":"string"}}}""",
            adapter.inputSchema
        ) { "Input schema should come from ToolDefinition.inputSchema()" }
    }

    @Test
    fun `name은(는) returns value from getName method on partial object`() {
        val adapter = SpringAiToolCallbackAdapter(NameOnlyCallback())

        assertEquals("name-only-tool", adapter.name) { "Name should come from getName method" }
        assertEquals("", adapter.description) { "Description should be empty for object without getDescription" }
    }

    @Test
    fun `with empty arguments serializes to empty JSON object를 호출한다`() {
        val fake = FakeSpringCallback()
        val adapter = SpringAiToolCallbackAdapter(fake)

        val result = runBlocking {
            adapter.call(emptyMap())
        } as String

        assertEquals("Result: {}", result) { "Empty args should serialize to empty JSON object" }
    }
}
