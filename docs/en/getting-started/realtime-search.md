# Real-time Search Setup

## Why Real-time Search Is Needed

LLMs have a **knowledge cutoff** — they cannot know events or facts that changed after their training data was collected. Questions about current political leaders, exchange rates, stock prices, or recent news will receive stale or incorrect answers.

Arc Reactor's default system prompt includes a **Real-time Information Policy** that instructs the LLM to:
1. Use available search tools to verify current facts
2. Return a standard "cannot verify" message when no search tool is available
3. Never guess or present outdated information as current fact

## Gemini Google Search (Recommended)

Gemini's built-in `googleSearchRetrieval` provides real-time web search with zero additional setup. No extra search API key, no external service, and no MCP configuration are needed beyond your Gemini provider credentials.

### Enable

Set the environment variable before starting the app:

```bash
export ARC_REACTOR_REALTIME_SEARCH_ENABLED=true
```

This binds to `spring.ai.google.genai.chat.options.google-search-retrieval` in `application.yml`.

### Verify

```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "Who is the current president of the United States?"}'
```

The response should include real-time search results rather than the "cannot verify" fallback.

### How It Works

- Gemini automatically performs a Google Search when the query requires current information
- Search results are grounded in the response (no hallucination)
- No additional cost beyond the Gemini API
- **Gemini-only** — does not work with OpenAI or Anthropic providers

## Fallback Priority

| Priority | Source | Example |
|----------|--------|---------|
| 1 | Gemini Google Search | Built-in `googleSearchRetrieval` |
| 2 | MCP search tool | Tavily, Brave Search, Serper (manual registration) |
| 3 | Internal API tool | Custom `ToolCallback` wrapping your search API |
| 4 | No tool available | Returns: "I cannot confirm this as no real-time verification source is available." |

## Alternative: MCP Search Servers

For non-Gemini providers (OpenAI, Anthropic), register a search MCP server via REST API:

```bash
# Tavily example
curl -X POST http://localhost:8080/api/mcp/servers \
  -H "Content-Type: application/json" \
  -d '{
    "name": "tavily-search",
    "transportType": "STDIO",
    "config": {
      "command": "npx",
      "args": ["-y", "tavily-mcp@latest"],
      "env": {
        "TAVILY_API_KEY": "your-tavily-api-key"
      }
    }
  }'
```

| Provider | MCP Package | Free Tier |
|----------|-------------|-----------|
| **Tavily** | `tavily-mcp@latest` | 1,000 searches/month |
| **Brave Search** | `@anthropic/brave-search-mcp` | 2,000 queries/month |
| **Serper** | `serper-mcp-server` | 2,500 queries (one-time) |

## Custom System Prompt

If you want to customize the real-time policy, create a persona with a modified system prompt:

```bash
curl -X POST http://localhost:8080/api/personas \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Custom Assistant",
    "systemPrompt": "You are a helpful AI assistant.\n\n[Real-time Information Policy]\nAlways use the search tool for any factual question.\nIf no search tool is available, say: \"Please connect a search tool for accurate answers.\"",
    "isDefault": true
  }'
```

The persona system prompt takes priority over the hardcoded default. See [System Prompt Resolution](../architecture/session-management.md) for the full priority chain.
