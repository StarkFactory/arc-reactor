# Customer Support Bot

Build an e-commerce customer support bot with order lookup, FAQ search, and human-in-the-loop (HITL) escalation for refunds.

## Scenario

A user sends a message to `POST /api/support`. The agent:

1. Looks up order details using an order ID the user mentions
2. Answers FAQ questions from a static knowledge base
3. Routes refund requests through a human approval step before executing

## Tools

### LookupOrderTool

```kotlin
package com.arc.reactor.tool.support

import com.arc.reactor.tool.ToolCallback
import org.springframework.stereotype.Component

@Component
class LookupOrderTool(
    private val orderRepository: OrderRepository  // your JPA/JDBC repository
) : ToolCallback {

    override val name = "lookup_order"
    override val description = "Look up order details by order ID. Use when the customer mentions an order number."

    override val inputSchema: String
        get() = """
            {
              "type": "object",
              "properties": {
                "orderId": {
                  "type": "string",
                  "description": "The order ID (e.g. ORD-1234)"
                }
              },
              "required": ["orderId"]
            }
        """.trimIndent()

    override suspend fun call(arguments: Map<String, Any?>): Any {
        val orderId = arguments["orderId"] as? String
            ?: return "Error: orderId is required"

        val order = orderRepository.findById(orderId)
            ?: return "Error: Order $orderId not found"

        return "Order $orderId: status=${order.status}, " +
               "total=${order.totalAmount}, items=${order.itemCount}, " +
               "placedAt=${order.placedAt}"
    }
}
```

### CheckFaqTool

```kotlin
package com.arc.reactor.tool.support

import com.arc.reactor.tool.ToolCallback
import org.springframework.stereotype.Component

@Component
class CheckFaqTool : ToolCallback {

    override val name = "check_faq"
    override val description = "Search the FAQ knowledge base for answers to common questions about shipping, returns, and payments."

    override val inputSchema: String
        get() = """
            {
              "type": "object",
              "properties": {
                "topic": {
                  "type": "string",
                  "description": "The topic to search (e.g. returns, shipping, payment)"
                }
              },
              "required": ["topic"]
            }
        """.trimIndent()

    // Replace with a real database or vector store lookup in production
    private val faqMap = mapOf(
        "return" to "Items can be returned within 30 days of purchase. Original packaging required. Refund processed in 3-5 business days.",
        "shipping" to "Standard shipping takes 3-7 business days. Express shipping (1-2 days) is available at checkout.",
        "payment" to "We accept Visa, Mastercard, and PayPal. Payment is charged at time of order confirmation."
    )

    override suspend fun call(arguments: Map<String, Any?>): Any {
        val topic = arguments["topic"] as? String
            ?: return "Error: topic is required"

        val key = faqMap.keys.firstOrNull { topic.lowercase().contains(it) }
        return faqMap[key] ?: "No FAQ entry found for '$topic'. Please contact support@example.com."
    }
}
```

### ProcessRefundTool

This tool is gated behind HITL approval. The agent requests the tool call; a human approves or rejects it before it executes.

```kotlin
package com.arc.reactor.tool.support

import com.arc.reactor.tool.ToolCallback
import org.springframework.stereotype.Component

@Component
class ProcessRefundTool(
    private val paymentService: PaymentService  // your payment integration
) : ToolCallback {

    override val name = "process_refund"
    override val description = "Process a refund for a completed order. Requires human approval before execution."

    override val inputSchema: String
        get() = """
            {
              "type": "object",
              "properties": {
                "orderId": {
                  "type": "string",
                  "description": "The order ID to refund"
                },
                "reason": {
                  "type": "string",
                  "description": "Reason for the refund"
                }
              },
              "required": ["orderId", "reason"]
            }
        """.trimIndent()

    override suspend fun call(arguments: Map<String, Any?>): Any {
        val orderId = arguments["orderId"] as? String
            ?: return "Error: orderId is required"
        val reason = arguments["reason"] as? String
            ?: return "Error: reason is required"

        return try {
            val refundId = paymentService.processRefund(orderId, reason)
            "Refund $refundId initiated for order $orderId. Funds will appear in 3-5 business days."
        } catch (e: Exception) {
            "Error: Refund failed for order $orderId — ${e.message}"
        }
    }
}
```

## HITL Approval Policy

Register a `ToolApprovalPolicy` that requires human sign-off before refunds execute:

```kotlin
package com.arc.reactor.config

import com.arc.reactor.approval.ToolApprovalPolicy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class SupportApprovalConfig {

    @Bean
    fun refundApprovalPolicy(): ToolApprovalPolicy {
        // Any call to process_refund must be approved by a human operator
        return ToolApprovalPolicy { toolName, _ ->
            toolName == "process_refund"
        }
    }
}
```

When `process_refund` is triggered, the executor returns `HookResult.PendingApproval`. The agent pauses until an operator approves or rejects via the approval REST API.

```bash
# List pending approvals
GET /api/approvals

# Approve
POST /api/approvals/{approvalId}/approve

# Reject with a reason
POST /api/approvals/{approvalId}/reject
Content-Type: application/json
{"reason": "Order was placed more than 90 days ago"}
```

## System Prompt

The system prompt defines the agent's persona and behavior boundaries:

```kotlin
val SUPPORT_SYSTEM_PROMPT = """
You are a customer support agent for ExampleShop, an e-commerce platform.

## Capabilities
- Look up order status and history using the lookup_order tool
- Answer shipping, return, and payment questions using the check_faq tool
- Initiate refunds using the process_refund tool (requires manager approval)

## Behavior Rules
- Always look up the order before discussing it with the customer
- Only process refunds for orders in DELIVERED or RETURNED status
- If a question cannot be answered by your tools, say "I'll escalate this to our team"
- Keep responses concise and professional
- Never reveal internal system details or tool names to the customer
""".trimIndent()
```

## Guard Configuration

Rate limiting and injection protection keep the bot safe in production:

```yaml
arc:
  reactor:
    guard:
      enabled: true
      rate-limit-per-minute: 20       # per user
      rate-limit-per-hour: 200
      injection-detection-enabled: true
      unicode-normalization-enabled: true
      audit-enabled: true
    max-tool-calls: 8                  # enough for order lookup + FAQ + refund
```

## Service Class

```kotlin
package com.arc.reactor.service

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import org.springframework.stereotype.Service

@Service
class CustomerSupportService(
    private val agentExecutor: AgentExecutor
) {

    suspend fun handleMessage(
        message: String,
        userId: String,
        sessionId: String
    ): AgentResult {
        return agentExecutor.execute(
            AgentCommand(
                systemPrompt = SUPPORT_SYSTEM_PROMPT,
                userPrompt = message,
                userId = userId,
                metadata = mapOf("sessionId" to sessionId),
                maxToolCalls = 8
            )
        )
    }
}
```

The `sessionId` in `metadata` activates conversation memory so the agent remembers the order the user mentioned earlier in the conversation.

## Controller

```kotlin
package com.arc.reactor.controller

import com.arc.reactor.service.CustomerSupportService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class SupportRequest(
    val message: String,
    val userId: String,
    val sessionId: String
)

data class SupportResponse(
    val content: String?,
    val success: Boolean,
    val errorMessage: String?
)

@RestController
@RequestMapping("/api/support")
@Tag(name = "Customer Support", description = "E-commerce customer support bot")
class CustomerSupportController(
    private val supportService: CustomerSupportService
) {

    @PostMapping
    @Operation(summary = "Send a message to the customer support agent")
    suspend fun chat(@Valid @RequestBody request: SupportRequest): SupportResponse {
        val result = supportService.handleMessage(
            message = request.message,
            userId = request.userId,
            sessionId = request.sessionId
        )
        return SupportResponse(
            content = result.content,
            success = result.success,
            errorMessage = result.errorMessage
        )
    }
}
```

## Testing the Bot

```bash
# Order lookup
curl -X POST http://localhost:8080/api/support \
  -H "Content-Type: application/json" \
  -d '{
    "message": "What is the status of my order ORD-5678?",
    "userId": "customer-42",
    "sessionId": "session-abc"
  }'

# FAQ
curl -X POST http://localhost:8080/api/support \
  -H "Content-Type: application/json" \
  -d '{
    "message": "How long does shipping take?",
    "userId": "customer-42",
    "sessionId": "session-abc"
  }'

# Refund (triggers HITL approval flow)
curl -X POST http://localhost:8080/api/support \
  -H "Content-Type: application/json" \
  -d '{
    "message": "I want a refund for order ORD-5678 — the item arrived damaged.",
    "userId": "customer-42",
    "sessionId": "session-abc"
  }'
```

The refund request will return a response indicating approval is pending. An operator then approves or rejects via `POST /api/approvals/{id}/approve`.

## Related

- [Guard & Hook System](../architecture/guard-hook.md)
- [ToolCallback interface](../../../arc-core/src/main/kotlin/com/arc/reactor/tool/ToolCallback.kt)
- [ToolApprovalPolicy](../../../arc-core/src/main/kotlin/com/arc/reactor/approval/ToolApprovalPolicy.kt)
