# Example Application & Tool Addition Guide

## Project Structure

Arc Reactor is a **core project that you fork and attach your own tools to**.
It is not a library -- it is a Spring Boot application that you run directly.

```
src/main/kotlin/com/arc/reactor/
├── ArcReactorApplication.kt          ← Main entry point
├── config/
│   └── ChatClientConfig.kt           ← ChatClient bean configuration
├── controller/
│   └── ChatController.kt             ← REST API endpoints
├── tool/
│   ├── ToolCallback.kt               ← Tool interface (key!)
│   └── example/
│       ├── CalculatorTool.kt          ← Example: calculator tool
│       └── DateTimeTool.kt            ← Example: current time tool
├── agent/                             ← Agent core (no need to modify)
├── guard/                             ← Security guard (no need to modify)
├── hook/                              ← Hook system (no need to modify)
├── memory/                            ← Conversation memory (no need to modify)
└── rag/                               ← RAG pipeline (no need to modify)
```

---

## Quick Start

### 1. Set Up Gemini API Key

```bash
# Get your key at https://aistudio.google.com/apikey
export GEMINI_API_KEY=your-api-key
```

### 2. Run

```bash
./gradlew :arc-app:bootRun
```

### 3. Test Calls

```bash
# Standard response
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "What is 3 + 5?"}'

# Streaming response (SSE)
curl -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message": "What time is it in Seoul right now?"}' \
  --no-buffer
```

---

## Adding Your Own Tools

### The Key Point: Just 3 Steps

1. Implement the `ToolCallback` interface
2. Annotate with `@Component`
3. Done! (Auto-registered)

### Example: Creating a Weather Tool

```kotlin
package com.arc.reactor.tool.example

import com.arc.reactor.tool.ToolCallback
import org.springframework.stereotype.Component

@Component
class WeatherTool : ToolCallback {

    // 1. Tool name (the LLM calls this tool by this name)
    override val name = "get_weather"

    // 2. Description (the LLM uses this to decide when to use the tool)
    override val description = "Get current weather for a city"

    // 3. Input schema (tells the LLM what parameters to send)
    override val inputSchema: String
        get() = """
            {
              "type": "object",
              "properties": {
                "city": {
                  "type": "string",
                  "description": "City name (e.g., Seoul, Tokyo)"
                }
              },
              "required": ["city"]
            }
        """.trimIndent()

    // 4. Actual logic
    override suspend fun call(arguments: Map<String, Any?>): Any {
        val city = arguments["city"] as? String
            ?: return "Error: city parameter is required"

        // Put your actual weather API call logic here
        return "Current weather in $city: Clear, 22 degrees"
    }
}
```

By simply adding this one file, the LLM will automatically recognize the tool and call it when needed.

---

## Included Example Tools

### CalculatorTool

A math calculation tool. Performs basic arithmetic operations (add, subtract, multiply, divide).

```
User: "What is 152 times 38?"
LLM: [calls calculator tool] -> "152.0 * 38.0 = 5776.0"
LLM: "152 times 38 is 5,776."
```

### DateTimeTool

A current date/time lookup tool. Supports timezone specification.

```
User: "What time is it in New York right now?"
LLM: [calls current_datetime tool, timezone="America/New_York"]
LLM: "It is currently 2026-02-06 10:30:00 (Thursday) in New York."
```

---

## API Endpoints

### POST /api/chat

Standard response. Returns the complete result at once.

**Request:**
```json
{
  "message": "What is 3 + 5?",
  "systemPrompt": "You are a math teacher.",
  "userId": "user-123"
}
```

**Response:**
```json
{
  "content": "3 + 5 is 8!",
  "success": true,
  "toolsUsed": ["calculator"],
  "errorMessage": null
}
```

### POST /api/chat/stream

Streaming response (SSE). Delivers tokens in real time.

```
data: Let
data: me
data: calculate
data: .
[tool executing...]
data: 3
data:  +
data:  5
data:  is
data:  8
data: !
```

---

## Configuration (application.yml)

```yaml
# Gemini configuration
spring:
  ai:
    google:
      genai:
        api-key: ${GEMINI_API_KEY}
        chat:
          options:
            model: gemini-2.0-flash  # Can be changed to gemini-2.0-pro, etc.

# Agent configuration
arc:
  reactor:
    max-tool-calls: 10              # Maximum number of tool calls per request
    llm:
      temperature: 0.7              # Creativity (0.0 ~ 1.0)
      max-output-tokens: 4096       # Maximum response length
    guard:
      enabled: true                 # Enable security guard
      rate-limit-per-minute: 60     # Request rate limit per minute
```

---

## Changing LLM Providers

The default is Gemini (API Key). To use a different provider, modify `build.gradle.kts`.

### Using OpenAI

```kotlin
// build.gradle.kts
implementation("org.springframework.ai:spring-ai-starter-model-openai")
// compileOnly("org.springframework.ai:spring-ai-starter-model-google-genai")  <- comment out
```

```yaml
# application.yml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      chat:
        options:
          model: gpt-4o-mini
```

### Using Anthropic (Claude)

```kotlin
// build.gradle.kts
implementation("org.springframework.ai:spring-ai-starter-model-anthropic")
// compileOnly("org.springframework.ai:spring-ai-starter-model-google-genai")  <- comment out
```

```yaml
# application.yml
spring:
  ai:
    anthropic:
      api-key: ${ANTHROPIC_API_KEY}
      chat:
        options:
          model: claude-sonnet-4-5-20250929
```

---

## How It Works (Brief Overview)

```
1. User sends a message to /api/chat
2. ChatController calls AgentExecutor.execute()
3. Guard pipeline performs security checks (Rate Limit, Injection Detection, etc.)
4. Message + registered tool list are sent to the LLM
5. LLM decides to call tools -> tools execute -> results are passed back to the LLM
6. LLM generates the final response
7. Response is returned to the user
```

The key point is that **step 5 repeats automatically** (ReAct loop).
If the LLM wants to call more tools, it keeps calling them; when it has enough information, it produces the final answer.
