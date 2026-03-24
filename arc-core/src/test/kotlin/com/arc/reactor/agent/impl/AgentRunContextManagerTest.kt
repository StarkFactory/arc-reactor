package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.AgentCommand
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import java.util.concurrent.CopyOnWriteArrayList

/**
 * AgentRunContextManager에 대한 테스트.
 *
 * 에이전트 실행 컨텍스트 관리를 검증합니다.
 */
class AgentRunContextManagerTest {

    @AfterEach
    fun cleanup() {
        MDC.remove("runId")
        MDC.remove("userId")
        MDC.remove("userEmail")
        MDC.remove("sessionId")
    }

    @Test
    fun `anonymous user and copied metadata로 create hook context해야 한다`() = runTest {
        val manager = AgentRunContextManager(runIdSupplier = { "run-1" })
        val toolsUsed = CopyOnWriteArrayList<String>()
        val command = AgentCommand(
            systemPrompt = "sys",
            userPrompt = "hello",
            userId = null,
            metadata = mapOf(
                "channel" to "slack",
                "sessionId" to 12345,
                "trace" to "abc",
                "requesterEmail" to "alice@example.com"
            )
        )

        val context = manager.open(command, toolsUsed)

        assertEquals("run-1", context.runId, "runId should match supplier value")
        assertEquals("anonymous", context.hookContext.userId, "null userId should resolve to anonymous")
        assertEquals("slack", context.hookContext.channel, "channel should be set from metadata")
        assertEquals("alice@example.com", context.hookContext.userEmail, "userEmail should be resolved from requesterEmail")
        assertEquals("abc", context.hookContext.metadata["trace"], "metadata trace key should be copied")
        // MDC는 MDCContext를 통해 코루틴 내에서만 전파됨 (thread-local MDC.put 제거됨)
        // HookContext 내 메타데이터가 올바르게 설정되었는지로 검증
        assertEquals("run-1", context.hookContext.metadata["runId"], "hookContext metadata should include runId")
    }

    @Test
    fun `email is unavailable일 때 use accountId해야 한다`() = runTest {
        val manager = AgentRunContextManager(runIdSupplier = { "run-2" })
        val toolsUsed = CopyOnWriteArrayList<String>()
        val command = AgentCommand(
            systemPrompt = "sys",
            userPrompt = "hello",
            userId = null,
            metadata = mapOf(
                "channel" to "web",
                "accountId" to "acct-777"
            )
        )

        val context = manager.open(command, toolsUsed)

        assertEquals("run-2", context.runId, "runId should match supplier value")
        assertEquals("acct-777", context.hookContext.userEmail, "accountId should be used as email fallback")
    }

    @Test
    fun `close should complete without error`() = runTest {
        val manager = AgentRunContextManager(runIdSupplier = { "run-1" })
        val toolsUsed = CopyOnWriteArrayList<String>()
        val command = AgentCommand(systemPrompt = "sys", userPrompt = "hello", userId = "u")

        manager.open(command, toolsUsed)
        manager.close()

        // close()가 예외 없이 완료되는지 검증
        // MDC는 MDCContext 기반이므로 thread-local 검증 대신 close 정상 완료로 확인
        assertNull(MDC.get("runId"), "MDC runId should be cleared after close()")
    }
}
