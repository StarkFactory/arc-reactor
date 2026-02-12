package com.arc.reactor.agent.multi.example

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.multi.AgentNode
import com.arc.reactor.agent.multi.MultiAgent
import com.arc.reactor.agent.multi.MultiAgentResult
import org.springframework.ai.chat.client.ChatClient

/**
 * Multi-agent usage example -- Report Pipeline (Sequential pattern)
 *
 * ## What This Example Demonstrates
 * A three-stage pipeline where each agent's output feeds into the next:
 * 1. **Researcher** gathers information
 * 2. **Writer** produces a structured report
 * 3. **Reviewer** polishes and improves
 *
 * ## Usage
 * ```kotlin
 * @Bean
 * fun reportPipeline(chatClient: ChatClient, props: AgentProperties) =
 *     ReportPipelineExample(chatClient, props)
 *
 * @PostMapping("/api/report")
 * suspend fun report(@RequestBody request: ChatRequest): ChatResponse {
 *     val result = reportPipeline.handle(request.message, request.userId)
 *     return ChatResponse(content = result.finalResult.content, success = result.success)
 * }
 * ```
 *
 * @see com.arc.reactor.agent.multi.SequentialOrchestrator
 */
// @Component  <- Uncomment to auto-register
class ReportPipelineExample(
    private val chatClient: ChatClient,
    private val properties: AgentProperties
) {

    suspend fun handle(
        message: String,
        userId: String? = null
    ): MultiAgentResult {
        return MultiAgent.sequential()
            .node("researcher") {
                systemPrompt =
                    "Research the given topic thoroughly. " +
                    "Provide key findings with supporting data."
            }
            .node("writer") {
                systemPrompt =
                    "Based on the research provided, " +
                    "write a well-structured report."
            }
            .node("reviewer") {
                systemPrompt =
                    "Review and improve the report. " +
                    "Fix errors and enhance clarity."
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
