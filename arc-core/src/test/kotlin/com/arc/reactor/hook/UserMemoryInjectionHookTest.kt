package com.arc.reactor.hook

import com.arc.reactor.hook.impl.UserMemoryInjectionHook
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.memory.UserMemoryManager
import com.arc.reactor.memory.impl.InMemoryUserMemoryStore
import com.arc.reactor.memory.model.UserMemory
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class UserMemoryInjectionHookTest {

    private val store = InMemoryUserMemoryStore()
    private val manager = UserMemoryManager(store = store)
    private val hook = UserMemoryInjectionHook(memoryManager = manager)

    @Nested
    inner class HookConfiguration {

        @Test
        fun `hook has low order to run early`() {
            assertTrue(
                hook.order <= 10,
                "UserMemoryInjectionHook.order should be <=10 to run before most hooks, got ${hook.order}"
            )
        }

        @Test
        fun `hook is fail-open by default`() {
            assertFalse(hook.failOnError, "UserMemoryInjectionHook should be fail-open (failOnError=false)")
        }
    }

    @Nested
    inner class ContextInjection {

        @Test
        fun `injects memory context into HookContext metadata when memory exists`() = runTest {
            store.save(
                "user-1",
                UserMemory(
                    userId = "user-1",
                    facts = mapOf("team" to "backend"),
                    recentTopics = listOf("Spring AI")
                )
            )
            val context = hookContext(userId = "user-1")

            val result = hook.beforeAgentStart(context)

            assertInstanceOf(HookResult.Continue::class.java, result, "Hook should return Continue")
            val injected = context.metadata[UserMemoryInjectionHook.USER_MEMORY_CONTEXT_KEY]
            assertNotNull(injected, "User memory context should be injected into metadata")
            val contextStr = injected.toString()
            assertTrue(contextStr.contains("team=backend"), "Injected context should contain fact 'team=backend'")
            assertTrue(contextStr.contains("Spring AI"), "Injected context should contain recent topic 'Spring AI'")
        }

        @Test
        fun `does not inject when user has no stored memory`() = runTest {
            val context = hookContext(userId = "no-memory-user")

            val result = hook.beforeAgentStart(context)

            assertInstanceOf(HookResult.Continue::class.java, result, "Hook should return Continue even with no memory")
            assertNull(
                context.metadata[UserMemoryInjectionHook.USER_MEMORY_CONTEXT_KEY],
                "Metadata should not contain userMemoryContext when user has no memory"
            )
        }

        @Test
        fun `does not inject for anonymous userId`() = runTest {
            val context = hookContext(userId = "anonymous")

            val result = hook.beforeAgentStart(context)

            assertInstanceOf(HookResult.Continue::class.java, result, "Hook should return Continue for anonymous userId")
            assertNull(
                context.metadata[UserMemoryInjectionHook.USER_MEMORY_CONTEXT_KEY],
                "Metadata should not contain userMemoryContext for anonymous user"
            )
        }

        @Test
        fun `does not inject for blank userId`() = runTest {
            val context = hookContext(userId = "")

            val result = hook.beforeAgentStart(context)

            assertInstanceOf(HookResult.Continue::class.java, result, "Hook should return Continue for blank userId")
            assertNull(
                context.metadata[UserMemoryInjectionHook.USER_MEMORY_CONTEXT_KEY],
                "Metadata should not contain userMemoryContext for blank userId"
            )
        }

        @Test
        fun `does not inject when memory contains no data`() = runTest {
            store.save("empty-user", UserMemory(userId = "empty-user"))
            val context = hookContext(userId = "empty-user")

            val result = hook.beforeAgentStart(context)

            assertInstanceOf(HookResult.Continue::class.java, result, "Hook should return Continue for empty memory")
            assertNull(
                context.metadata[UserMemoryInjectionHook.USER_MEMORY_CONTEXT_KEY],
                "Metadata should not contain userMemoryContext when memory has no data"
            )
        }
    }

    @Nested
    inner class ContextFormat {

        @Test
        fun `injected context starts with User context prefix`() = runTest {
            store.save("prefix-user", UserMemory(userId = "prefix-user", facts = mapOf("role" to "lead")))
            val context = hookContext(userId = "prefix-user")

            hook.beforeAgentStart(context)

            val injected = context.metadata[UserMemoryInjectionHook.USER_MEMORY_CONTEXT_KEY]?.toString()
            assertNotNull(injected, "Context should be injected")
            assertTrue(injected!!.startsWith("User context:"), "Injected context should start with 'User context:'")
        }

        @Test
        fun `injected context includes preferences`() = runTest {
            store.save(
                "pref-user",
                UserMemory(userId = "pref-user", preferences = mapOf("detail_level" to "brief"))
            )
            val context = hookContext(userId = "pref-user")

            hook.beforeAgentStart(context)

            val injected = context.metadata[UserMemoryInjectionHook.USER_MEMORY_CONTEXT_KEY]?.toString()
            assertNotNull(injected, "Context should be injected when preferences exist")
            assertTrue(
                injected!!.contains("detail_level=brief"),
                "Injected context should contain preference 'detail_level=brief'"
            )
        }
    }

    @Nested
    inner class FailureBehavior {

        @Test
        fun `continues without injection when memory manager throws`() = runTest {
            val failingManager = mockk<UserMemoryManager>()
            coEvery { failingManager.getContextPrompt(any()) } throws RuntimeException("Simulated storage failure")
            val failingHook = UserMemoryInjectionHook(memoryManager = failingManager)
            val context = hookContext(userId = "fail-user")

            val result = failingHook.beforeAgentStart(context)

            assertInstanceOf(
                HookResult.Continue::class.java,
                result,
                "Hook should return Continue even when memory manager throws"
            )
            assertNull(
                context.metadata[UserMemoryInjectionHook.USER_MEMORY_CONTEXT_KEY],
                "Metadata should not contain userMemoryContext when manager throws"
            )
        }
    }

    private fun hookContext(userId: String = "user-1"): HookContext = HookContext(
        runId = "run-test",
        userId = userId,
        userPrompt = "Hello"
    )
}
