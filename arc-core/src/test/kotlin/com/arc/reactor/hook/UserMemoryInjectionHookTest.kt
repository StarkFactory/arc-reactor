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

/**
 * UserMemoryInjectionHook에 대한 테스트.
 *
 * 사용자 메모리 프롬프트 주입 훅의 동작을 검증합니다.
 */
class UserMemoryInjectionHookTest {

    private val store = InMemoryUserMemoryStore()
    private val manager = UserMemoryManager(store = store)
    private val hook = UserMemoryInjectionHook(memoryManager = manager, injectIntoPrompt = true)

    @Nested
    inner class HookConfiguration {

        @Test
        fun `hook은(는) low order to run early를 가진다`() {
            assertTrue(
                hook.order <= 10,
                "UserMemoryInjectionHook.order should be <=10 to run before most hooks, got ${hook.order}"
            )
        }

        @Test
        fun `hook은(는) fail-open by default이다`() {
            assertFalse(hook.failOnError, "UserMemoryInjectionHook should be fail-open (failOnError=false)")
        }
    }

    @Nested
    inner class ContextInjection {

        @Test
        fun `memory context into HookContext metadata when memory exists를 주입한다`() = runTest {
            store.save(
                "user-1",
                UserMemory(
                    userId = "user-1",
                    facts = mapOf("team" to "backend"),
                    preferences = mapOf("language" to "Korean")
                )
            )
            val context = hookContext(userId = "user-1")

            val result = hook.beforeAgentStart(context)

            assertInstanceOf(HookResult.Continue::class.java, result, "Hook should return Continue")
            val injected = context.metadata[UserMemoryInjectionHook.USER_MEMORY_CONTEXT_KEY]
            assertNotNull(injected, "User memory context should be injected into metadata")
            val contextStr = injected.toString()
            assertTrue(contextStr.contains("team=backend"), "Injected context should contain fact 'team=backend'")
        }

        @Test
        fun `inject when user has no stored memory하지 않는다`() = runTest {
            val context = hookContext(userId = "no-memory-user")

            val result = hook.beforeAgentStart(context)

            assertInstanceOf(HookResult.Continue::class.java, result, "Hook should return Continue even with no memory")
            assertNull(
                context.metadata[UserMemoryInjectionHook.USER_MEMORY_CONTEXT_KEY],
                "Metadata should not contain userMemoryContext when user has no memory"
            )
        }

        @Test
        fun `inject for anonymous userId하지 않는다`() = runTest {
            val context = hookContext(userId = "anonymous")

            val result = hook.beforeAgentStart(context)

            assertInstanceOf(HookResult.Continue::class.java, result, "Hook should return Continue for anonymous userId")
            assertNull(
                context.metadata[UserMemoryInjectionHook.USER_MEMORY_CONTEXT_KEY],
                "Metadata should not contain userMemoryContext for anonymous user"
            )
        }

        @Test
        fun `inject for blank userId하지 않는다`() = runTest {
            val context = hookContext(userId = "")

            val result = hook.beforeAgentStart(context)

            assertInstanceOf(HookResult.Continue::class.java, result, "Hook should return Continue for blank userId")
            assertNull(
                context.metadata[UserMemoryInjectionHook.USER_MEMORY_CONTEXT_KEY],
                "Metadata should not contain userMemoryContext for blank userId"
            )
        }

        @Test
        fun `inject when memory contains no data하지 않는다`() = runTest {
            store.save("empty-user", UserMemory(userId = "empty-user"))
            val context = hookContext(userId = "empty-user")

            val result = hook.beforeAgentStart(context)

            assertInstanceOf(HookResult.Continue::class.java, result, "Hook should return Continue for empty memory")
            assertNull(
                context.metadata[UserMemoryInjectionHook.USER_MEMORY_CONTEXT_KEY],
                "Metadata should not contain userMemoryContext when memory has no data"
            )
        }

        @Test
        fun `does not inject when injectIntoPrompt은(는) false이다`() = runTest {
            val disabledHook = UserMemoryInjectionHook(memoryManager = manager, injectIntoPrompt = false)
            store.save(
                "user-disabled",
                UserMemory(userId = "user-disabled", facts = mapOf("team" to "backend"))
            )
            val context = hookContext(userId = "user-disabled")

            val result = disabledHook.beforeAgentStart(context)

            assertInstanceOf(HookResult.Continue::class.java, result, "Hook should return Continue when disabled")
            assertNull(
                context.metadata[UserMemoryInjectionHook.USER_MEMORY_CONTEXT_KEY],
                "Metadata should not contain userMemoryContext when injectIntoPrompt is false"
            )
        }

        @Test
        fun `injectIntoPrompt은(는) defaults to false`() = runTest {
            val defaultHook = UserMemoryInjectionHook(memoryManager = manager)
            store.save(
                "user-default",
                UserMemory(userId = "user-default", facts = mapOf("team" to "backend"))
            )
            val context = hookContext(userId = "user-default")

            defaultHook.beforeAgentStart(context)

            assertNull(
                context.metadata[UserMemoryInjectionHook.USER_MEMORY_CONTEXT_KEY],
                "Default hook (injectIntoPrompt=false) should not inject memory context"
            )
        }
    }

    @Nested
    inner class ContextFormat {

        @Test
        fun `injected은(는) context uses Facts prefix for facts`() = runTest {
            store.save("fact-user", UserMemory(userId = "fact-user", facts = mapOf("role" to "lead")))
            val context = hookContext(userId = "fact-user")

            hook.beforeAgentStart(context)

            val injected = context.metadata[UserMemoryInjectionHook.USER_MEMORY_CONTEXT_KEY]?.toString()
            assertNotNull(injected, "Context should be injected")
            assertTrue(injected!!.startsWith("Facts:"), "Injected context should start with 'Facts:' but was: $injected")
        }

        @Test
        fun `injected은(는) context uses Preferences prefix for preferences`() = runTest {
            store.save(
                "pref-user",
                UserMemory(userId = "pref-user", preferences = mapOf("detail_level" to "brief"))
            )
            val context = hookContext(userId = "pref-user")

            hook.beforeAgentStart(context)

            val injected = context.metadata[UserMemoryInjectionHook.USER_MEMORY_CONTEXT_KEY]?.toString()
            assertNotNull(injected, "Context should be injected when preferences exist")
            assertTrue(
                injected!!.contains("Preferences: detail_level=brief"),
                "Injected context should contain 'Preferences: detail_level=brief' but was: $injected"
            )
        }

        @Test
        fun `injected context은(는) separate lines for facts and preferences를 가진다`() = runTest {
            store.save(
                "full-user",
                UserMemory(
                    userId = "full-user",
                    facts = mapOf("team" to "backend"),
                    preferences = mapOf("language" to "Korean")
                )
            )
            val context = hookContext(userId = "full-user")

            hook.beforeAgentStart(context)

            val injected = context.metadata[UserMemoryInjectionHook.USER_MEMORY_CONTEXT_KEY]?.toString()
            assertNotNull(injected, "Context should be injected for full user")
            val lines = injected!!.split("\n")
            assertEquals(2, lines.size, "Context should have exactly 2 lines (Facts + Preferences)")
            assertTrue(lines[0].startsWith("Facts:"), "First line should be Facts")
            assertTrue(lines[1].startsWith("Preferences:"), "Second line should be Preferences")
        }
    }

    @Nested
    inner class FailureBehavior {

        @Test
        fun `memory manager throws일 때 continues without injection`() = runTest {
            val failingManager = mockk<UserMemoryManager>()
            coEvery { failingManager.getContextPrompt(any()) } throws RuntimeException("Simulated storage failure")
            val failingHook = UserMemoryInjectionHook(memoryManager = failingManager, injectIntoPrompt = true)
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
