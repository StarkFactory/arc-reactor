package com.example.chatbot

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.guard.impl.DefaultInputValidationStage
import com.arc.reactor.guard.impl.DefaultRateLimitStage
import com.arc.reactor.guard.impl.GuardPipeline
import com.arc.reactor.hook.AfterAgentCompleteHook
import com.arc.reactor.hook.BeforeAgentStartHook
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.memory.InMemoryMemoryStore
import kotlinx.coroutines.runBlocking
import org.springframework.ai.chat.client.ChatClient

/**
 * Basic Chatbot Example using Arc Reactor
 *
 * Demonstrates the full Guard → Hook → Agent → Memory pipeline.
 *
 * ## Prerequisites
 * - Spring Boot application with an LLM provider configured
 *   (e.g., spring-ai-starter-model-openai)
 * - A ChatClient bean available in the Spring context
 *
 * ## Usage
 * ```kotlin
 * @SpringBootApplication
 * class ChatbotApp
 *
 * fun main(args: Array<String>) {
 *     runApplication<ChatbotApp>(*args)
 * }
 *
 * @RestController
 * class ChatController(private val agentExecutor: AgentExecutor) {
 *
 *     @PostMapping("/chat")
 *     suspend fun chat(@RequestBody request: ChatRequest): ChatResponse {
 *         val result = agentExecutor.execute(
 *             AgentCommand(
 *                 systemPrompt = "You are a helpful assistant.",
 *                 userPrompt = request.message,
 *                 userId = request.userId,
 *                 metadata = mapOf("sessionId" to request.sessionId)
 *             )
 *         )
 *         return ChatResponse(result.content ?: "Error: ${result.errorMessage}")
 *     }
 * }
 * ```
 */
object ChatbotExample {

    /**
     * Build a complete agent with guards, hooks, and memory.
     */
    fun buildAgent(chatClient: ChatClient, properties: AgentProperties): SpringAiAgentExecutor {
        // 1. Guard Pipeline - protects against abuse
        val guard = GuardPipeline(
            listOf(
                DefaultRateLimitStage(requestsPerMinute = 30, requestsPerHour = 500),
                DefaultInputValidationStage(maxLength = 10_000)
            )
        )

        // 2. Hooks - logging and audit
        val loggingHook = object : BeforeAgentStartHook {
            override val order = 1
            override suspend fun beforeAgentStart(context: HookContext): HookResult {
                println("[${context.runId}] User ${context.userId}: ${context.userPrompt.take(50)}...")
                return HookResult.Continue
            }
        }

        val auditHook = object : AfterAgentCompleteHook {
            override val order = 1
            override suspend fun afterAgentComplete(context: HookContext, response: AgentResponse) {
                val duration = context.durationMs()
                println("[${context.runId}] Completed in ${duration}ms, success=${response.success}")
            }
        }

        val hookExecutor = HookExecutor(
            beforeStartHooks = listOf(loggingHook),
            afterCompleteHooks = listOf(auditHook)
        )

        // 3. Memory Store - multi-turn conversation support
        val memoryStore = InMemoryMemoryStore(maxSessions = 1000)

        // 4. Assemble the agent
        return SpringAiAgentExecutor(
            chatClient = chatClient,
            properties = properties,
            guard = guard,
            hookExecutor = hookExecutor,
            memoryStore = memoryStore
        )
    }

    /**
     * Simple interactive chatbot loop (for demonstration).
     */
    fun runInteractive(agent: SpringAiAgentExecutor, userId: String = "demo-user") = runBlocking {
        val sessionId = "session-${System.currentTimeMillis()}"

        println("Arc Reactor Chatbot (type 'quit' to exit)")
        println("==========================================")

        while (true) {
            print("\nYou: ")
            val input = readlnOrNull()?.trim() ?: break
            if (input.equals("quit", ignoreCase = true)) break

            val result = agent.execute(
                AgentCommand(
                    systemPrompt = "You are a helpful AI assistant powered by Arc Reactor.",
                    userPrompt = input,
                    userId = userId,
                    metadata = mapOf("sessionId" to sessionId)
                )
            )

            if (result.success) {
                println("\nAssistant: ${result.content}")
            } else {
                println("\nError: ${result.errorMessage}")
            }
        }

        println("\nGoodbye!")
    }
}
