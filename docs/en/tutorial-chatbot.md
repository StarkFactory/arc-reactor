# Tutorial: Build a Customer Service Chatbot in 30 Minutes

This tutorial walks you through building a working customer service chatbot with Arc Reactor. You'll create custom tools, configure a persona, test via curl and Swagger, and deploy with Docker.

**Prerequisites**: Java 21+, Git, an LLM API key (Gemini, OpenAI, or Anthropic)

---

## Step 1: Fork & Setup (5 min)

### Fork the repository

1. Go to the [Arc Reactor GitHub repo](https://github.com/StarkFactory/arc-reactor)
2. Click **Fork**
3. Clone your fork:

```bash
git clone https://github.com/<your-username>/arc-reactor.git
cd arc-reactor
```

### Configure your LLM provider

Set your API key as an environment variable:

```bash
# Gemini (default)
export GEMINI_API_KEY=your-api-key-here

# Or OpenAI
# export OPENAI_API_KEY=your-api-key-here

# Or Anthropic
# export ANTHROPIC_API_KEY=your-api-key-here
```

If using OpenAI or Anthropic, uncomment the corresponding dependency in `build.gradle.kts`:

```kotlin
// Uncomment ONE of these:
// implementation("org.springframework.ai:spring-ai-starter-model-openai")
// implementation("org.springframework.ai:spring-ai-starter-model-anthropic")
```

### Verify it works

```bash
./gradlew :arc-app:bootRun
```

In another terminal:

```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "Hello!"}'
```

You should get a JSON response with the agent's reply. Press `Ctrl+C` to stop the server.

---

## Step 2: Create Custom Tools (10 min)

Tools are how your agent interacts with your business logic. We'll create two tools: one for order lookup and one for FAQ search.

### Tool 1: Order Lookup

Create `src/main/kotlin/com/arc/reactor/tool/example/OrderLookupTool.kt`:

```kotlin
package com.arc.reactor.tool.example

import com.arc.reactor.tool.DefaultToolCategory
import com.arc.reactor.tool.LocalTool
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam

// @Component  // Uncomment to auto-register
class OrderLookupTool : LocalTool {

    override val category = DefaultToolCategory.SEARCH

    // Simulated order database
    private val orders = mapOf(
        "ORD-1001" to Order("ORD-1001", "Wireless Headphones", "Shipped", "2026-02-12"),
        "ORD-1002" to Order("ORD-1002", "USB-C Hub", "Processing", "2026-02-15"),
        "ORD-1003" to Order("ORD-1003", "Mechanical Keyboard", "Delivered", "2026-02-08")
    )

    @Tool(description = "Look up order status by order ID. Returns order details including product, status, and estimated delivery date.")
    fun lookupOrder(
        @ToolParam(description = "Order ID (e.g., ORD-1001)") orderId: String
    ): String {
        val order = orders[orderId.uppercase()]
            ?: return "Order '$orderId' not found. Please check the order ID and try again."
        return """
            Order: ${order.id}
            Product: ${order.product}
            Status: ${order.status}
            Estimated Delivery: ${order.estimatedDelivery}
        """.trimIndent()
    }

    data class Order(
        val id: String,
        val product: String,
        val status: String,
        val estimatedDelivery: String
    )
}
```

### Tool 2: FAQ Search

Create `src/main/kotlin/com/arc/reactor/tool/example/FaqTool.kt`:

```kotlin
package com.arc.reactor.tool.example

import com.arc.reactor.tool.DefaultToolCategory
import com.arc.reactor.tool.LocalTool
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam

// @Component  // Uncomment to auto-register
class FaqTool : LocalTool {

    override val category = DefaultToolCategory.SEARCH

    private val faqs = listOf(
        Faq("return", "You can return items within 30 days of delivery. Items must be unused and in original packaging. Visit /returns to start a return."),
        Faq("shipping", "Standard shipping takes 3-5 business days. Express shipping (2-day) is available for an additional fee. Free shipping on orders over \$50."),
        Faq("payment", "We accept Visa, Mastercard, American Express, and PayPal. All transactions are encrypted with TLS 1.3."),
        Faq("warranty", "All electronics come with a 1-year manufacturer warranty. Extended warranty (2 years) can be purchased at checkout.")
    )

    @Tool(description = "Search the FAQ knowledge base for answers to common customer questions about returns, shipping, payment, and warranty.")
    fun searchFaq(
        @ToolParam(description = "Search keyword or topic (e.g., 'return', 'shipping', 'payment')") query: String
    ): String {
        val matches = faqs.filter { it.topic.contains(query.lowercase()) }
        if (matches.isEmpty()) {
            return "No FAQ found for '$query'. Available topics: return, shipping, payment, warranty."
        }
        return matches.joinToString("\n\n") { "[${it.topic.uppercase()}]\n${it.answer}" }
    }

    data class Faq(val topic: String, val answer: String)
}
```

### Register the tools

Uncomment `@Component` on both classes to auto-register them. That's it - Spring discovers them automatically.

> **Tip**: In a real project, replace the hardcoded data with actual database queries or API calls. The tool interface stays the same.

---

## Step 3: Configure a Persona (5 min)

A persona sets the agent's personality and behavior. Start the server and use the REST API to create one:

```bash
./gradlew :arc-app:bootRun
```

Create a customer service persona:

```bash
curl -X POST http://localhost:8080/api/personas \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Customer Service Agent",
    "systemPrompt": "You are a friendly and helpful customer service agent for an online electronics store. Follow these rules:\n\n1. Always greet the customer warmly\n2. Use the order lookup tool when a customer asks about their order\n3. Use the FAQ tool for general questions about returns, shipping, payment, or warranty\n4. If you cannot help, apologize and suggest contacting support@example.com\n5. Keep responses concise and professional\n6. Always end by asking if there is anything else you can help with",
    "isDefault": true
  }'
```

When `isDefault: true`, this persona is automatically applied to all chat requests that don't specify a custom system prompt.

---

## Step 4: Test with curl & Swagger (5 min)

### Test order lookup

```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "What is the status of order ORD-1001?"}'
```

Expected: The agent uses the `lookupOrder` tool and responds with the order details.

### Test FAQ

```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "What is your return policy?"}'
```

Expected: The agent uses the `searchFaq` tool and explains the return policy.

### Test streaming

```bash
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message": "Tell me about shipping options and payment methods"}'
```

Expected: The response streams in real-time, with the agent potentially calling the FAQ tool for both topics.

### Use Swagger UI

Open [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html) in your browser. You can:
- Try all endpoints interactively
- See request/response schemas
- Test with different parameters

### View conversation history

```bash
# List all sessions
curl http://localhost:8080/api/sessions

# Get messages for a specific session
curl http://localhost:8080/api/sessions/<session-id>

# Export as markdown
curl http://localhost:8080/api/sessions/<session-id>/export?format=markdown
```

---

## Step 5: Deploy with Docker (5 min)

### Setup environment

```bash
cp .env.example .env
```

Edit `.env` and set your API key:

```env
GEMINI_API_KEY=your-actual-api-key
```

### Build and run

```bash
# Start with PostgreSQL (persistent storage)
docker-compose up -d

# Or start backend only (in-memory storage)
docker-compose up app -d
```

### Verify deployment

```bash
# Health check
curl http://localhost:8080/actuator/health

# Test the chatbot
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "Hi! Can you check order ORD-1002 for me?"}'
```

### Stop

```bash
docker-compose down
```

---

## Next Steps

Now that you have a working chatbot, here's what to explore next:

- **Add more tools**: Connect to databases, APIs, or external services. See the [Tool Guide](tools.md)
- **Enable authentication**: Add JWT auth for per-user sessions. See [Authentication](authentication.md)
- **Enable RAG**: Add document-based Q&A with vector search. See [Memory & RAG](memory-rag.md)
- **Connect MCP servers**: Add external tools via Model Context Protocol. See [MCP Guide](mcp.md)
- **Add Guard stages**: Implement custom content filtering or permission checks. See [Guard & Hook](guard-hook.md)
- **Multi-agent patterns**: Build a supervisor that delegates to specialized worker agents. See [Multi-Agent Guide](multi-agent.md)
- **Add webhooks**: Get notified when agents complete. Set `arc.reactor.webhook.enabled: true`

For the full configuration reference, see [Configuration](configuration.md).
