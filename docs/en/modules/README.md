# Arc Reactor Module Documentation

Per-module reference documentation for the Arc Reactor framework. Each document covers key components, all configuration properties (sourced from actual code), extension points, API reference (where applicable), practical code examples, and common pitfalls.

## Modules

| Module | Description |
|---|---|
| [arc-core](./arc-core.md) | Agent executor, Guard pipeline, Hook system, Tool abstraction, auto-configuration, RAG, memory, Prompt Lab, resilience |
| [arc-web](./arc-web.md) | REST controllers, SSE streaming, security filters, authentication, tenant resolution, global exception handler |

## Quick Navigation

**I want to...**

- Build a custom tool → [arc-core: ToolCallback](./arc-core.md#toolcallback----custom-tool)
- Add a guard stage (input validation, rate limiting) → [arc-core: GuardStage](./arc-core.md#guardstage----custom-guard-stage)
- Hook into agent lifecycle (audit, billing) → [arc-core: Hook](./arc-core.md#hook----lifecycle-extension)
- Configure memory and conversation history → [arc-core: Configuration](./arc-core.md#memory-summary-arcreactormemorysummary)
- Enable RAG → [arc-core: RAG Configuration](./arc-core.md#rag-arcreactorrag)
- Understand all REST endpoints → [arc-web: API Reference](./arc-web.md#api-reference)
- Add security headers / CORS → [arc-web: Configuration](./arc-web.md#configuration)
- Register an MCP server → [arc-web: MCP Servers](./arc-web.md#mcp-servers)
- Enable JWT authentication → [arc-web: Auth](./arc-web.md#authentication-requires-arcreactorauthenabled-true)
- Override error messages (i18n) → [arc-core: ErrorMessageResolver](./arc-core.md#errormessageresolver----custom-error-messages)
