# Example App and Tool Extension Guide

Arc Reactor is an executable multi-module Spring Boot application intended to be forked and customized.

## Current module layout (high-level)

```text
arc-app         Executable assembly (bootRun / bootJar)
arc-core        Agent runtime (ReAct loop, guard/hook, tool abstractions, memory, auth)
arc-web         REST/SSE controllers and web API layer
arc-admin       Admin control-plane features (metrics/tracing/tenant dashboards)
arc-slack       Slack channel integration
arc-error-report Optional error-reporting feature module
```

## Minimal local run

```bash
export GEMINI_API_KEY=your-api-key
export ARC_REACTOR_AUTH_JWT_SECRET=$(openssl rand -base64 32)
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/arcreactor
export SPRING_DATASOURCE_USERNAME=arc
export SPRING_DATASOURCE_PASSWORD=arc
./gradlew :arc-app:bootRun
```

## First API calls

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"qa@example.com","password":"passw0rd!","name":"QA"}' \
  | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')

curl -X POST http://localhost:8080/api/chat \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: default" \
  -H "Content-Type: application/json" \
  -d '{"message":"What is 3 + 5?"}'
```

Streaming:

```bash
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: default" \
  -H "Content-Type: application/json" \
  -d '{"message":"Give me a 3-line summary of ReAct."}'
```

## Add your own tool (ToolCallback style)

`ToolCallback` is the framework-agnostic adapter point used by the runtime.

```kotlin
package com.arc.reactor.tool.custom

import com.arc.reactor.tool.ToolCallback
import org.springframework.stereotype.Component

@Component
class WeatherTool : ToolCallback {
    override val name: String = "get_weather"
    override val description: String = "Get current weather by city name"

    override val inputSchema: String
        get() = """
            {
              "type": "object",
              "properties": {
                "city": {"type": "string", "description": "City name"}
              },
              "required": ["city"]
            }
        """.trimIndent()

    override suspend fun call(arguments: Map<String, Any?>): Any {
        val city = arguments["city"] as? String
            ?: return "Error: city is required"
        return "Weather for $city: Clear, 22C"
    }
}
```

## Add your own tool (LocalTool + @Tool style)

For strongly typed method-based tools, use `LocalTool` with Spring AI `@Tool`.

```kotlin
package com.arc.reactor.tool.custom

import com.arc.reactor.tool.LocalTool
import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Component

@Component
class RepoTool : LocalTool {

    @Tool(description = "Get repository default branch")
    suspend fun getDefaultBranch(repo: String): String {
        return "main"
    }
}
```

## Validation checklist after adding tools

- Tool bean is discovered by Spring (`@Component` or explicit `@Bean`)
- Tool name/description are clear enough for LLM selection
- Tool returns `"Error: ..."` on recoverable failure instead of throwing
- Integration smoke test confirms tool appears in `toolsUsed`

## Next docs

- [Configuration quickstart](configuration-quickstart.md)
- [Configuration reference](configuration.md)
- [Tool reference](../reference/tools.md)
- [MCP runtime management](../architecture/mcp/runtime-management.md)
