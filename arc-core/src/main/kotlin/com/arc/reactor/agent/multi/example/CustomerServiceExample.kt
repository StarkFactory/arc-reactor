package com.arc.reactor.agent.multi.example

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.multi.AgentNode
import com.arc.reactor.agent.multi.MultiAgent
import com.arc.reactor.agent.multi.MultiAgentResult
import com.arc.reactor.tool.ToolCallback
import org.springframework.ai.chat.client.ChatClient

/**
 * Multi-agent usage example -- Customer Service Center (Supervisor pattern)
 *
 * ## What This Example Demonstrates
 * 1. Where to define nodes
 * 2. How to create an agentFactory
 * 3. How to connect with a ChatController
 *
 * ## Usage
 * ```kotlin
 * // Register as a bean in Spring @Configuration
 * @Bean
 * fun customerService(chatClient: ChatClient, properties: AgentProperties): CustomerServiceExample {
 *     return CustomerServiceExample(chatClient, properties)
 * }
 *
 * // Use in a Controller
 * @PostMapping("/api/support")
 * suspend fun support(@RequestBody request: ChatRequest): ChatResponse {
 *     val result = customerService.handle(request.message, request.userId)
 *     return ChatResponse(content = result.finalResult.content, success = result.success)
 * }
 * ```
 *
 * @see com.arc.reactor.agent.multi.MultiAgent DSL builder
 * @see com.arc.reactor.agent.multi.WorkerAgentTool Adapter that wraps an agent as a tool
 * @see com.arc.reactor.agent.multi.SupervisorOrchestrator Supervisor orchestrator
 */
// @Component  <- Uncomment to auto-register
class CustomerServiceExample(
    private val chatClient: ChatClient,
    private val properties: AgentProperties
) {

    /**
     * Handles a customer request.
     *
     * The Supervisor analyzes the request and delegates to the appropriate worker agent.
     */
    suspend fun handle(message: String, userId: String? = null): MultiAgentResult {
        return MultiAgent.supervisor()
            // -- Worker node definitions --
            // Each node() defines one worker agent.
            // description is important: the Supervisor's LLM reads this description
            // to decide which worker to delegate to.
            .node("order") {
                systemPrompt = "You are an order specialist. Handle order inquiries, modifications, and cancellations."
                description = "Order lookup, modification, cancellation"
                // tools = listOf(orderLookupTool, orderCancelTool)  <- Connect actual tools here
                maxToolCalls = 5
            }
            .node("refund") {
                systemPrompt = "You are a refund specialist. Process refund requests according to company policy."
                description = "Refund requests, refund status checks, refund policy guidance"
                // tools = listOf(refundProcessTool, refundStatusTool)
                maxToolCalls = 5
            }
            .node("shipping") {
                systemPrompt = "You are a shipping specialist. Track packages and handle delivery issues."
                description = "Shipment tracking, address changes, delivery delay inquiries"
                // tools = listOf(trackingTool, addressChangeTool)
                maxToolCalls = 5
            }
            // -- Execution --
            // agentFactory: function that creates an actual AgentExecutor from each node
            // This function is called once per worker and once for the Supervisor.
            .execute(
                command = AgentCommand(
                    systemPrompt = "You are a customer service supervisor.",
                    userPrompt = message,
                    userId = userId
                ),
                agentFactory = { node -> createAgent(node) }
            )
    }

    /**
     * Creates a SpringAiAgentExecutor from an AgentNode.
     *
     * All agents (Supervisor + Workers) are created through this factory.
     * Common settings (chatClient, properties) are shared,
     * while per-node settings (systemPrompt, tools, maxToolCalls) differ for each.
     */
    private fun createAgent(node: AgentNode): AgentExecutor {
        return SpringAiAgentExecutor(
            chatClient = chatClient,
            properties = properties,
            // node.tools: tools defined for this node
            // For the Supervisor node, WorkerAgentTools are automatically included
            toolCallbacks = node.tools,
            localTools = node.localTools
        )
    }
}
