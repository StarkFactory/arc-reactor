package com.arc.reactor.agent.multi.example

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.multi.AgentNode
import com.arc.reactor.agent.multi.MultiAgent
import com.arc.reactor.agent.multi.MultiAgentResult
import com.arc.reactor.agent.multi.ResultMerger
import org.springframework.ai.chat.client.ChatClient

/**
 * Multi-agent usage example -- Code Review (Parallel pattern)
 *
 * ## What This Example Demonstrates
 * Three reviewers analyze code concurrently, each from a different angle:
 * - **Security**: Vulnerability analysis
 * - **Style**: Code style and readability
 * - **Logic**: Logic errors and edge cases
 *
 * Results are merged into a single combined review.
 *
 * ## Usage
 * ```kotlin
 * @Bean
 * fun codeReview(chatClient: ChatClient, props: AgentProperties) =
 *     CodeReviewExample(chatClient, props)
 *
 * @PostMapping("/api/review")
 * suspend fun review(@RequestBody request: ChatRequest): ChatResponse {
 *     val result = codeReview.handle(request.message, request.userId)
 *     return ChatResponse(content = result.finalResult.content, success = result.success)
 * }
 * ```
 *
 * @see com.arc.reactor.agent.multi.ParallelOrchestrator
 */
// @Component  <- Uncomment to auto-register
class CodeReviewExample(
    private val chatClient: ChatClient,
    private val properties: AgentProperties
) {

    suspend fun handle(
        message: String,
        userId: String? = null
    ): MultiAgentResult {
        return MultiAgent.parallel(
            merger = ResultMerger.JOIN_WITH_NEWLINE,
            failFast = false
        )
            .node("security") {
                systemPrompt = "Analyze security vulnerabilities only."
            }
            .node("style") {
                systemPrompt = "Review code style and readability only."
            }
            .node("logic") {
                systemPrompt =
                    "Check for logic errors and edge cases only."
            }
            .execute(
                command = AgentCommand(
                    systemPrompt = "",
                    userPrompt = message,
                    userId = userId
                ),
                agentFactory = ::createAgent
            )
    }

    private fun createAgent(node: AgentNode): AgentExecutor {
        return SpringAiAgentExecutor(
            chatClient = chatClient,
            properties = properties,
            toolCallbacks = node.tools,
            localTools = node.localTools
        )
    }
}
