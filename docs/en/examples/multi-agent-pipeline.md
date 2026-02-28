# Multi-Agent Research Pipeline

Build a research pipeline where a Researcher agent gathers information, an Analyst synthesizes it, and a Writer produces a polished report — each running in sequence with the previous agent's output as the next agent's input.

## Scenario

A user requests a research report on a topic. Three specialist agents execute in a sequential pipeline:

```
User Input → [Researcher] → findings → [Analyst] → analysis → [Writer] → final report
```

Each agent has its own system prompt and tool set. The framework automatically chains outputs as inputs.

## Sequential Pattern Overview

In Arc Reactor's sequential pattern:

- Each node's output becomes the next node's `userPrompt`
- If any node fails, the pipeline stops immediately
- All nodes share the same `agentFactory`, but each gets its own `systemPrompt` and `tools`

## Tools

### WebSearchTool

The Researcher uses a search tool to fetch current information:

```kotlin
package com.arc.reactor.tool.research

import com.arc.reactor.tool.ToolCallback
import org.springframework.stereotype.Component

@Component
class WebSearchTool(
    private val searchClient: SearchApiClient  // your Brave/SerpAPI/Tavily wrapper
) : ToolCallback {

    override val name = "web_search"
    override val description = "Search the web for current information on a topic. Returns a list of relevant excerpts."

    override val inputSchema: String
        get() = """
            {
              "type": "object",
              "properties": {
                "query": {
                  "type": "string",
                  "description": "The search query"
                },
                "maxResults": {
                  "type": "integer",
                  "description": "Maximum number of results to return (1-10)",
                  "default": 5
                }
              },
              "required": ["query"]
            }
        """.trimIndent()

    override suspend fun call(arguments: Map<String, Any?>): Any {
        val query = arguments["query"] as? String ?: return "Error: query is required"
        val maxResults = (arguments["maxResults"] as? Number)?.toInt() ?: 5

        return try {
            val results = searchClient.search(query, maxResults)
            results.mapIndexed { i, r ->
                "[${i + 1}] ${r.title}\n${r.snippet}\nURL: ${r.url}"
            }.joinToString("\n\n")
        } catch (e: Exception) {
            "Error: Search failed — ${e.message}"
        }
    }
}
```

### FetchPageTool

The Researcher can fetch a full page for deeper reading:

```kotlin
package com.arc.reactor.tool.research

import com.arc.reactor.tool.ToolCallback
import org.springframework.stereotype.Component

@Component
class FetchPageTool(
    private val httpClient: HttpContentFetcher  // your HTTP content fetcher
) : ToolCallback {

    override val name = "fetch_page"
    override val description = "Fetch and extract the main text content of a web page."

    override val inputSchema: String
        get() = """
            {
              "type": "object",
              "properties": {
                "url": {
                  "type": "string",
                  "description": "URL of the page to fetch"
                }
              },
              "required": ["url"]
            }
        """.trimIndent()

    // Per-tool timeout: fetching pages can be slow
    override val timeoutMs: Long = 20_000L

    override suspend fun call(arguments: Map<String, Any?>): Any {
        val url = arguments["url"] as? String ?: return "Error: url is required"

        return try {
            val content = httpClient.fetchText(url, maxChars = 8000)
            "Content from $url:\n$content"
        } catch (e: Exception) {
            "Error: Failed to fetch $url — ${e.message}"
        }
    }
}
```

## Pipeline Service

```kotlin
package com.arc.reactor.service

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.multi.AgentNode
import com.arc.reactor.agent.multi.MultiAgent
import com.arc.reactor.agent.multi.MultiAgentResult
import com.arc.reactor.tool.research.FetchPageTool
import com.arc.reactor.tool.research.WebSearchTool
import org.springframework.ai.chat.client.ChatClient
import org.springframework.stereotype.Service

@Service
class ResearchPipelineService(
    private val chatClient: ChatClient,
    private val properties: AgentProperties,
    private val webSearchTool: WebSearchTool,
    private val fetchPageTool: FetchPageTool
) {

    suspend fun run(topic: String, userId: String? = null): MultiAgentResult {
        return MultiAgent.sequential()
            .node("researcher") {
                systemPrompt = """
                    You are a research specialist. Your job is to gather comprehensive,
                    accurate information on the given topic.

                    Steps:
                    1. Use web_search to find 3-5 relevant sources
                    2. Use fetch_page on the most relevant URLs to read in depth
                    3. Synthesize all gathered information into detailed findings

                    Output your findings in a structured format with:
                    - Key facts and statistics
                    - Multiple perspectives or viewpoints
                    - Source URLs for each finding
                """.trimIndent()
                tools = listOf(webSearchTool, fetchPageTool)
                maxToolCalls = 12
            }
            .node("analyst") {
                systemPrompt = """
                    You are a data analyst and critical thinker. You will receive research
                    findings from a researcher. Your job is to:

                    1. Identify the most significant patterns and insights
                    2. Evaluate the quality and reliability of the sources
                    3. Highlight conflicting viewpoints and explain them
                    4. Draw evidence-based conclusions

                    Output a structured analysis with clear sections:
                    - Key Insights
                    - Evidence Quality Assessment
                    - Areas of Uncertainty
                    - Conclusions
                """.trimIndent()
                // The analyst does not need tools — it works from the researcher's output
                maxToolCalls = 0
            }
            .node("writer") {
                systemPrompt = """
                    You are a professional technical writer. You will receive an analysis
                    and must produce a polished, well-structured report.

                    Requirements:
                    - Executive summary (3-5 sentences)
                    - Main sections with clear headings
                    - Concrete examples for each major point
                    - Actionable recommendations
                    - Professional, accessible tone

                    Do not add information beyond what is in the analysis you received.
                """.trimIndent()
                maxToolCalls = 0
            }
            .execute(
                command = AgentCommand(
                    systemPrompt = "",
                    userPrompt = topic,
                    userId = userId
                ),
                agentFactory = { node -> createAgent(node) }
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
```

The framework passes each agent's `content` as the `userPrompt` to the next agent automatically. The Writer receives the Analyst's structured analysis as its input.

## Supervisor Pattern: Research Center

For more complex scenarios where the orchestration logic is dynamic (e.g., the LLM decides whether to do more research or move on to writing), use the Supervisor pattern:

```kotlin
package com.arc.reactor.service

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.multi.AgentNode
import com.arc.reactor.agent.multi.MultiAgent
import com.arc.reactor.agent.multi.MultiAgentResult
import com.arc.reactor.tool.research.FetchPageTool
import com.arc.reactor.tool.research.WebSearchTool
import org.springframework.ai.chat.client.ChatClient
import org.springframework.stereotype.Service

@Service
class ResearchCenterService(
    private val chatClient: ChatClient,
    private val properties: AgentProperties,
    private val webSearchTool: WebSearchTool,
    private val fetchPageTool: FetchPageTool
) {

    suspend fun produce(request: String, userId: String? = null): MultiAgentResult {
        return MultiAgent.supervisor()
            .node("researcher") {
                systemPrompt = "You are a research specialist. Search the web and fetch pages to gather information."
                description = "Use when detailed factual research or source gathering is needed"
                tools = listOf(webSearchTool, fetchPageTool)
                maxToolCalls = 15
            }
            .node("analyst") {
                systemPrompt = "You are an analyst. Evaluate evidence, identify patterns, and draw conclusions from the provided information."
                description = "Use when analysis, critical evaluation, or synthesis of information is needed"
                maxToolCalls = 0
            }
            .node("writer") {
                systemPrompt = "You are a professional writer. Produce polished, well-structured reports from the content provided."
                description = "Use when a final polished document or report needs to be produced"
                maxToolCalls = 0
            }
            .execute(
                command = AgentCommand(
                    systemPrompt = """
                        You are a research center coordinator. Given a request, delegate to the
                        appropriate specialists in the right order. For a full report, typically:
                        1. Ask the researcher to gather information
                        2. Ask the analyst to evaluate the findings
                        3. Ask the writer to produce the final output
                    """.trimIndent(),
                    userPrompt = request,
                    userId = userId
                ),
                agentFactory = { node -> createAgent(node) }
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
```

In the Supervisor pattern:

- The Supervisor LLM decides which worker to call and when
- Workers are registered as `WorkerAgentTool` instances automatically by the framework
- The Supervisor can call workers multiple times or in any order it determines appropriate
- Each `delegate_to_{name}` tool call invokes the worker's full `SpringAiAgentExecutor` (with its own ReAct loop)

## Error Propagation

In the sequential pattern, a node failure stops the pipeline:

```kotlin
val result = pipeline.run("AI in healthcare 2026", userId)

if (!result.success) {
    // result.finalResult.errorCode tells you which node failed
    // result.nodeResults contains the results per node, including the failed one
    val failedNode = result.nodeResults.lastOrNull { !it.result.success }
    logger.error { "Pipeline failed at node '${failedNode?.nodeName}': ${failedNode?.result.errorMessage}" }
}
```

`nodeResults` preserves the output of every successfully completed node, so if the Writer fails you still have the Analyst's work.

In the Supervisor pattern, worker failures are returned as error strings to the Supervisor LLM, which can decide to retry or proceed with partial information.

## Configuring Independent Worker Models

Each worker agent can use a different LLM model. This is useful when you want a cheaper model for the Writer and a more capable model for the Researcher:

```kotlin
private fun createAgent(node: AgentNode): AgentExecutor {
    // Override the model per node using AgentCommand.model at execution time,
    // or configure different ChatClient instances per node name.
    return SpringAiAgentExecutor(
        chatClient = chatClient,
        properties = properties,
        toolCallbacks = node.tools,
        localTools = node.localTools
    )
}
```

To use different models per node, inject separate `ChatClient` beans configured for different models and select the right one in `createAgent()` based on `node.name`.

## Controller

```kotlin
package com.arc.reactor.controller

import com.arc.reactor.service.ResearchPipelineService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class ResearchRequest(
    val topic: String,
    val userId: String? = null
)

data class ResearchResponse(
    val report: String?,
    val success: Boolean,
    val durationMs: Long,
    val errorMessage: String?
)

@RestController
@RequestMapping("/api/research")
@Tag(name = "Research Pipeline", description = "Multi-agent research and writing pipeline")
class ResearchPipelineController(
    private val pipeline: ResearchPipelineService
) {

    @PostMapping
    @Operation(summary = "Run a multi-agent research pipeline on the given topic")
    suspend fun research(@RequestBody request: ResearchRequest): ResearchResponse {
        val result = pipeline.run(request.topic, request.userId)
        return ResearchResponse(
            report = result.finalResult.content,
            success = result.success,
            durationMs = result.totalDurationMs,
            errorMessage = if (!result.success) result.finalResult.errorMessage else null
        )
    }
}
```

## Testing

```bash
curl -X POST http://localhost:8080/api/research \
  -H "Content-Type: application/json" \
  -d '{
    "topic": "Impact of large language models on software development productivity in 2025-2026",
    "userId": "analyst-01"
  }'
```

The pipeline runs three sequential LLM-agent executions. The total duration reported in `durationMs` reflects wall-clock time for all three agents.

## Pattern Selection Guide

| Requirement | Pattern |
|-------------|---------|
| Agent A's output feeds Agent B | **Sequential** |
| All agents analyze the same input independently | **Parallel** |
| The LLM should decide which agent to call | **Supervisor** |
| Fixed pipeline with known order | **Sequential** |
| Dynamic routing based on request type | **Supervisor** |
| Maximum speed (independent tasks) | **Parallel** |

## Related

- [Multi-Agent Guide](../architecture/multi-agent.md) — Full pattern documentation
- [Supervisor Pattern Deep Dive](../architecture/supervisor-pattern.md)
- [ReportPipelineExample.kt](../../../arc-core/src/main/kotlin/com/arc/reactor/agent/multi/example/ReportPipelineExample.kt) — Built-in sequential example
- [CustomerServiceExample.kt](../../../arc-core/src/main/kotlin/com/arc/reactor/agent/multi/example/CustomerServiceExample.kt) — Built-in supervisor example
