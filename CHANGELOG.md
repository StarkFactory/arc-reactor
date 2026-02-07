# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.0] - 2026-02-08

### Added
- **Multi-LLM Provider Support**: Runtime provider selection via `model` field in ChatRequest/AgentCommand. Supports Google Gemini, OpenAI, and Anthropic Claude simultaneously
- `ChatModelProvider`: Registry that maps provider names to ChatModel beans. Auto-configured from available Spring AI starters
- `model` field on `ChatRequest`, `ChatResponse`, and `AgentCommand` for per-request provider selection
- `defaultProvider` property in `LlmProperties` (default: "gemini")
- Multi-agent example controllers: `MultiAgentExampleController`, `ReportPipelineExample` (Sequential), `CodeReviewExample` (Parallel)

### Changed
- OpenAI and Anthropic dependencies changed from `compileOnly` to `implementation` (auto-configured when API key is set)
- `SpringAiAgentExecutor` accepts optional `ChatModelProvider` for runtime ChatClient resolution
- `application.yml` updated with multi-provider configuration (Gemini, OpenAI, Anthropic)
- All comments in `application.yml` converted to English

## [1.0.0] - 2026-02-07

### Added
- **Multi-Agent Orchestration**: Sequential (체이닝), Parallel (동시실행+ResultMerger), Supervisor (WorkerAgentTool로 위임) 패턴. DSL 빌더 API `MultiAgent.sequential/parallel/supervisor()`
- **Context Window Management**: Token-based message trimming that preserves the current user prompt and maintains AssistantMessage + ToolResponseMessage pair integrity
- **LLM Retry**: Exponential backoff with +-25% jitter for transient errors (rate limit, timeout, 5xx). Configurable via `RetryProperties` with custom `transientErrorClassifier` support
- **Parallel Tool Execution**: Concurrent tool calls via `coroutineScope { async {} }.awaitAll()` with preserved result ordering and per-tool Hook lifecycle
- **Structured Output**: JSON response mode via system prompt injection (provider-independent). Supports optional `responseSchema` for guided output
- **PostgreSQL MemoryStore**: `JdbcMemoryStore` with Flyway migration, FIFO eviction per session, TTL-based session cleanup. Auto-configured when `DataSource` is available
- **ConversationManager**: Extracted conversation history lifecycle management from `SpringAiAgentExecutor`
- **AgentResult.errorCode**: Programmatic error classification (`GUARD_REJECTED`, `HOOK_REJECTED`, `RATE_LIMITED`, `TIMEOUT`, `CONTEXT_TOO_LONG`, `TOOL_ERROR`, `UNKNOWN`)
- **Fork-Friendliness**: Dockerfile (multi-stage, non-root), docker-compose.yml (PostgreSQL), .env.example, .editorconfig, .dockerignore, GitHub Actions CI
- **Example Code**: WeatherTool, AuditLogHook, BudgetLimitHook, BusinessHoursGuard, PiiDetectionGuard
- **Documentation**: 멀티에이전트 가이드 (한글), 아키텍처 가이드, 배포 가이드
- `TokenEstimator` interface with CJK-aware default implementation
- `ResponseFormat` enum (TEXT, JSON) on `AgentCommand`

### Changed
- Guard pipeline pre-sorts stages at construction time (previously sorted on every call)
- Hook executor pre-sorts hooks at construction time (previously sorted on every call)
- Injection detection patterns moved to companion object (avoid per-instance allocation)
- Sensitive parameter masking uses word-boundary regex instead of substring matching
- Vector store retriever sorts by score before deduplication (keeps highest-scored version)
- Example tools (`CalculatorTool`, `DateTimeTool`) no longer have `@Component` (prevents unintended auto-registration in production)

### Fixed
- **Security**: Guard was skipped entirely when `userId` was null — now uses "anonymous" fallback
- `CancellationException` was caught as generic `Exception` in execute/executeStream — now correctly rethrown for structured concurrency
- `maxToolCalls` reached only logged a warning but didn't remove tools — now sets `activeTools = emptyList()` to force final answer
- Context trimming removed the current user prompt (oldest message) — now protects the last `UserMessage`
- Streaming memory saved accumulated content from all ReAct iterations — now saves only the final iteration
- Streaming memory save was inside `withTimeout` — moved to `finally` block for atomicity
- `saveConversationHistory` exception could lose a successful LLM result — now wrapped in try-catch
- `saveConversationHistory` saved user message even on failure — now skips on `!result.success`
- `executeAfterAgentComplete` hook exception in catch/finally blocks could mask the original error — now wrapped in try-catch
- `executeToolCallsInParallel` used the original `tools` list instead of `activeTools` — fixed to use `activeTools`
- `toolsUsed` recorded tool names before confirming the adapter exists (hallucinated tool names) — now adds only after lookup
- Streaming controller dropped `responseFormat`/`responseSchema` from request — now passes through
- RAG pipeline `topK / 2` halved the requested document count (topK=1 returned 0 documents) — removed division
- `getHistoryWithinTokenLimit` used O(n^2) `add(0, msg)` — changed to O(n) collect-then-reverse

## [0.2.0] - 2025-01-15

### Added
- MCP (Model Context Protocol) integration with STDIO and SSE transport support
- RAG pipeline with query transformation, retrieval, reranking, and context building
- Session-based conversation memory with token-aware truncation
- CJK-aware token estimation for multilingual support
- Error message internationalization via `ErrorMessageResolver`
- Hook `failOnError` property for fail-close behavior
- Comprehensive KDoc API documentation

### Changed
- MCP Manager uses `ConcurrentHashMap` for thread-safe access
- Replaced `runBlocking` with proper coroutine suspension in agent executor
- Cached reflection calls in `SpringAiToolCallbackAdapter` via `by lazy`
- All source comments converted to English for international contributors

### Fixed
- `SystemMessage` was incorrectly converted to `UserMessage` in conversation history

## [0.1.0] - 2025-01-10

### Added
- Spring AI-based Agent Executor with ReAct pattern
- 5-stage Guard Pipeline (RateLimit, InputValidation, InjectionDetection, Classification, Permission)
- Hook system with 4 lifecycle extension points
- Local Tool support with `@Tool` annotation integration
- Tool category-based selection for optimized context usage
- Spring Boot auto-configuration with sensible defaults
- `ToolCallback` abstraction for framework-agnostic tool handling
