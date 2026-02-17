# Semantic Tool Discovery

## One-Line Summary

**Dynamically select the most relevant tools for each request using embedding similarity, instead of passing all tools to the LLM.**

---

## Why Semantic Tool Discovery?

When an agent has many tools, passing all of them to the LLM creates problems:

```
Agent with 30 tools:
  getWeather, searchWeb, calculator, sendEmail, readFile, writeFile,
  checkOrder, cancelOrder, processRefund, trackShipping, getBalance,
  createTicket, updateTicket, closeTicket, assignTicket, listTickets,
  queryDatabase, exportCsv, generateReport, scheduleTask, ...
```

- **Token waste**: Every tool's name, description, and schema consumes tokens
- **Wrong tool selection**: The LLM may pick an irrelevant tool from a large list
- **Slower responses**: More context means longer inference time

Semantic Tool Discovery solves this by selecting only the tools that are relevant to the user's request:

```
User: "What's the weather in Seoul?"

All tools (30) -> SemanticToolSelector -> [getWeather, searchWeb] (2 tools)

Only 2 tools are passed to the LLM, not 30.
```

---

## How It Works

```
1. Startup / First Request
   Batch-embed all tool descriptions and cache them
   "Get current weather" -> [0.12, -0.34, 0.56, ...]
   "Process a refund"    -> [0.78, -0.11, 0.23, ...]
   ...
      |
2. User Request Arrives
   Embed the user prompt
   "What's the weather?" -> [0.15, -0.31, 0.52, ...]
      |
3. Cosine Similarity Computation
   Compare prompt embedding with each tool embedding
   getWeather:     0.92  (high match)
   searchWeb:      0.71  (moderate match)
   processRefund:  0.08  (low match)
   calculator:     0.12  (low match)
      |
4. Filter by Threshold + Sort
   threshold = 0.3, maxResults = 10
   -> [getWeather (0.92), searchWeb (0.71)]
      |
5. Selected Tools Passed to LLM
   The LLM only sees getWeather and searchWeb
```

### Cache and Auto-Refresh

- Tool description embeddings are cached in a `ConcurrentHashMap`
- A fingerprint (hash of all tool names + descriptions) is computed on each request
- If the fingerprint changes (tools added, removed, or descriptions updated), the cache is automatically refreshed
- First request incurs a one-time embedding cost; subsequent requests use the cache

---

## Configuration

```yaml
arc:
  reactor:
    tool:
      selection:
        strategy: semantic          # all | keyword | semantic
        similarity-threshold: 0.3   # minimum cosine similarity (0.0 ~ 1.0)
        max-results: 10             # maximum number of tools to select
```

### Strategy Options

| Strategy | Behavior | Requires EmbeddingModel |
|----------|----------|------------------------|
| `all` | Pass all tools to the LLM (default) | No |
| `keyword` | Match based on ToolCategory keywords | No |
| `semantic` | Embedding-based cosine similarity | Yes |

### Tuning the Threshold

- **0.1 ~ 0.2**: Very permissive. More tools selected, fewer misses, more tokens used
- **0.3 ~ 0.4**: Balanced. Good for most use cases (recommended starting point)
- **0.5 ~ 0.7**: Strict. Only very relevant tools selected. Risk of missing useful tools
- **0.8+**: Too strict. Likely to miss relevant tools

---

## Prerequisites

Semantic strategy requires an `EmbeddingModel` bean. This typically comes from a Spring AI starter:

```kotlin
// build.gradle.kts
implementation("org.springframework.ai:spring-ai-openai-spring-boot-starter")
```

```yaml
# application.yml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
```

If no `EmbeddingModel` bean is available, `SemanticToolSelector` automatically falls back to the `all` strategy with a warning log. Your application will not fail.

---

## Usage

### Basic Setup

Just set the configuration. No code changes needed.

```yaml
arc:
  reactor:
    tool:
      selection:
        strategy: semantic
        similarity-threshold: 0.3
        max-results: 10
```

The `SemanticToolSelector` bean is auto-configured when:
1. `strategy` is set to `semantic`
2. An `EmbeddingModel` bean exists

### Programmatic Override

You can provide your own `ToolSelector` bean to override the auto-configured one:

```kotlin
@Component
class CustomToolSelector(
    private val embeddingModel: EmbeddingModel
) : ToolSelector {

    override suspend fun select(
        prompt: String,
        tools: List<ToolCallbackWrapper>
    ): List<ToolCallbackWrapper> {
        // Your custom logic
        // For example: always include certain critical tools
        val semanticResults = semanticSelect(prompt, tools)
        val criticalTools = tools.filter { it.name in setOf("emergency_stop") }
        return (criticalTools + semanticResults).distinctBy { it.name }
    }
}
```

Thanks to `@ConditionalOnMissingBean`, your custom bean replaces the default automatically.

---

## Graceful Fallback

`SemanticToolSelector` is designed to never block the agent:

| Scenario | Behavior |
|----------|----------|
| No `EmbeddingModel` bean | Falls back to `all` strategy |
| Embedding API call fails | Returns all tools (logs warning) |
| All similarities below threshold | Returns all tools (assumes no good match) |
| Empty tool list | Returns empty list |

The principle: **it is better to give the LLM too many tools than to give it none**.

---

## Comparison with Keyword Strategy

| | Keyword (`ToolCategory`) | Semantic (`SemanticToolSelector`) |
|---|---|---|
| **How it matches** | Exact keyword matching | Embedding cosine similarity |
| **Setup effort** | Must define keywords per category | Zero setup (uses tool descriptions) |
| **Multilingual** | Must add keywords for each language | Works across languages automatically |
| **"refund inquiry"** | Matches if "refund" keyword exists | Matches processRefund (0.87), checkOrder (0.65) |
| **Requires external API** | No | Yes (EmbeddingModel) |
| **Cost** | Free | Embedding API cost per unique prompt |

**Recommendation:**
- Small number of tools (< 10): Use `all` -- no filtering needed
- Medium (10-30) with clear categories: `keyword` works well
- Large (30+) or diverse tools: `semantic` gives the best results

---

## Reference Code

| File | Description |
|------|-------------|
| [`SemanticToolSelector.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/tool/SemanticToolSelector.kt) | Embedding-based tool selection |
| [`AgentProperties.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/agent/config/AgentProperties.kt) | ToolSelectionProperties configuration |
| [`ArcReactorAutoConfiguration.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/autoconfigure/ArcReactorAutoConfiguration.kt) | Auto-configuration of SemanticToolSelector |
| [`ToolSelector.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/tool/ToolSelector.kt) | ToolSelector interface |
| [`ToolCategory.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/tool/ToolCategory.kt) | Keyword-based category system (comparison) |
