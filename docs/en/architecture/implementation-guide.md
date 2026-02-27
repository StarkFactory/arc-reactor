# Implementation Guide

Code templates and patterns for extending Arc Reactor. Referenced from CLAUDE.md via
`@docs/en/architecture/implementation-guide.md`.

## New ToolCallback

```kotlin
class MyTool : ToolCallback {
    override val name = "my_tool"
    override val description = "One-line description for LLM tool selection"
    override val inputSchema: String get() = """
        {"type":"object","properties":{"param":{"type":"string","description":"..."}},"required":["param"]}
    """.trimIndent()
    override suspend fun call(arguments: Map<String, Any?>): Any {
        val param = arguments["param"] as? String ?: return "Error: 'param' is required"
        return "Result: $param"  // Return error as string, do NOT throw
    }
}
```

## New GuardStage

```kotlin
class MyGuard : GuardStage {
    override val stageName = "MyGuard"
    override val order = 35  // 0=UnicodeNorm 1=RateLimit 2=InputValidation 3=Injection 4=Classification 5=Permission 6=TopicDrift
    override suspend fun check(command: GuardCommand): GuardResult {
        if (invalid) return GuardResult.Rejected("reason", RejectionCategory.INVALID_INPUT, stageName)
        return GuardResult.Allowed.DEFAULT
    }
}
```

## New Hook

```kotlin
private val logger = KotlinLogging.logger {}
class MyHook : AfterAgentCompleteHook {
    override val order = 100  // 100-199: standard range
    override suspend fun afterAgentComplete(context: HookContext, response: AgentResponse) {
        try { /* hook logic */ }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { logger.error(e) { "Hook failed for runId=${context.runId}" } }
    }
}
```

## New Bean Registration

```kotlin
// 1. Interface at package root
interface MyFeature { suspend fun process(input: String): String }

// 2. Default impl in impl/
class DefaultMyFeature : MyFeature { override suspend fun process(input: String) = "..." }

// 3. Register in ArcReactorAutoConfiguration
@Bean @ConditionalOnMissingBean
fun myFeature(): MyFeature = DefaultMyFeature()

// 4. Optional feature with property toggle → nested @Configuration
@Configuration
@ConditionalOnProperty(prefix = "arc.reactor.my-feature", name = ["enabled"], havingValue = "true")
class MyFeatureConfiguration { ... }

// 5. JDBC store → @ConditionalOnClass + @Primary
@Bean @Primary
@ConditionalOnClass(name = ["org.springframework.jdbc.core.JdbcTemplate"])
fun jdbcMyStore(jdbc: JdbcTemplate): MyStore = JdbcMyStore(jdbc)

// 6. Optional dependency → ObjectProvider<T>
fun executor(ragProvider: ObjectProvider<RagPipeline>): AgentExecutor =
    SpringAiAgentExecutor(ragPipeline = ragProvider.ifAvailable)
```

## Test Patterns

```kotlin
// ✅ DO: runTest for coroutines, coEvery for suspend mocks
@Test fun `test name`() = runTest {
    coEvery { service.process(any()) } returns "result"
    // ...
}

// ✅ DO: ALL assertions must have failure messages
assertTrue(result.success) { "Expected success but got error: ${result.errorMessage}" }
assertEquals(expected, actual) { "Expected $expected but got $actual" }
val rejected = assertInstanceOf(GuardResult.Rejected::class.java, result) {
    "Expected Rejected but got ${result::class.simpleName}"
}

// ✅ DO: returnsMany for sequential ReAct loop responses
every { fixture.requestSpec.call() } returnsMany listOf(
    fixture.mockToolCallResponse(listOf(toolCall)),
    fixture.mockFinalResponse("Done")
)

// ✅ DO: Mock options() explicitly for streaming tests
every { requestSpec.options(any<ChatOptions>()) } returns requestSpec

// ✅ DO: AtomicInteger for concurrency counting
val counter = AtomicInteger(0)

// ❌ DON'T: bare assertions without messages
assertTrue(x)           // NO — no failure context
assertNotNull(y)        // NO — no failure context

// ❌ DON'T: Thread.sleep in tests
Thread.sleep(1000)      // NO — use delayingToolCallback or coroutine delay
```

## Coroutine Anti-Patterns

```kotlin
// ❌ WRONG: Generic catch swallows CancellationException → breaks withTimeout
suspend fun doWork() {
    try { work() }
    catch (e: Exception) { log(e) }  // CancellationException caught here!
}

// ✅ RIGHT: Always rethrow CancellationException first
suspend fun doWork() {
    try { work() }
    catch (e: CancellationException) { throw e }
    catch (e: Exception) { log(e) }
}

// ❌ WRONG: .forEach {} creates non-suspend lambda
list.forEach { doSuspendWork(it) }

// ✅ RIGHT: for loop preserves suspend context
for (item in list) { doSuspendWork(item) }
```
