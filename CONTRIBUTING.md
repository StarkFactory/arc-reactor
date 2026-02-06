# Contributing to Arc Reactor

Thank you for your interest in contributing to Arc Reactor! This document provides guidelines and information for contributors.

## Code of Conduct

Please be respectful and constructive in all interactions. We're building something together.

## How to Contribute

### Reporting Issues

1. **Search existing issues** first to avoid duplicates
2. **Use issue templates** when available
3. **Provide detailed information**:
   - Arc Reactor version
   - Spring Boot version
   - Kotlin version
   - Steps to reproduce
   - Expected vs actual behavior
   - Error messages/stack traces

### Suggesting Features

1. Open an issue with `[Feature]` prefix
2. Describe the use case
3. Explain why existing features don't solve the problem
4. Provide examples of desired API/behavior

### Pull Requests

1. **Fork the repository**
2. **Create a feature branch**: `git checkout -b feature/my-feature`
3. **Make your changes**
4. **Write/update tests**
5. **Run tests**: `./gradlew test`
6. **Commit with clear messages**
7. **Push and create PR**

## Development Setup

### Prerequisites

- JDK 21+
- Kotlin 2.3.0+
- Gradle 8.x

### Build

```bash
# Clone
git clone https://github.com/StarkFactory/arc-reactor.git
cd arc-reactor

# Build
./gradlew build

# Run tests
./gradlew test

# Run tests with coverage
./gradlew test jacocoTestReport
```

### Project Structure

```
src/
├── main/kotlin/com/arc/reactor/
│   ├── agent/          # Agent core
│   ├── tool/           # Tool system
│   ├── hook/           # Lifecycle hooks
│   ├── guard/          # Security guardrails
│   ├── rag/            # RAG pipeline
│   ├── memory/         # Conversation memory
│   ├── mcp/            # MCP integration
│   └── autoconfigure/  # Spring Boot auto-config
└── test/kotlin/com/arc/reactor/
    └── ...             # Test files mirror main structure
```

## Coding Guidelines

### Kotlin Style

- Follow [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use meaningful names
- Prefer immutable data (`val` over `var`)
- Use data classes for models
- Use sealed classes for result types

### Documentation

- Add KDoc for all public APIs
- Include `@param`, `@return`, `@throws` where applicable
- Provide usage examples in KDoc

```kotlin
/**
 * Executes the agent with the given command.
 *
 * @param command The agent command containing prompt and configuration
 * @return AgentResult with success status and response content
 * @throws AgentExecutionException if execution fails
 *
 * Example:
 * ```kotlin
 * val result = agentExecutor.execute(
 *     AgentCommand(
 *         systemPrompt = "You are helpful.",
 *         userPrompt = "Hello!"
 *     )
 * )
 * ```
 */
suspend fun execute(command: AgentCommand): AgentResult
```

### Testing

- Write tests for all new features
- Maintain test coverage above 70%
- Use descriptive test names with backticks

```kotlin
@Test
fun `should reject request when rate limit exceeded`() = runBlocking {
    // Arrange
    // Act
    // Assert
}
```

### Commit Messages

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
feat: add MCP SSE transport support
fix: handle null response in tool callback
docs: update README with RAG examples
test: add integration tests for guard pipeline
refactor: simplify hook executor logic
```

## Architecture Decisions

When proposing significant changes, please:

1. Discuss in an issue first
2. Document the decision in `docs/adr/` (Architecture Decision Records)
3. Consider backward compatibility

## Review Process

1. All PRs require at least one approval
2. CI must pass (tests, linting)
3. Documentation must be updated
4. Breaking changes need discussion

## Areas for Contribution

### High Priority

- [ ] MCP SSE/HTTP transport implementation
- [ ] Integration tests for full agent flow
- [ ] Performance benchmarks
- [ ] Docker support

### Medium Priority

- [ ] Additional Guard stages (content filtering, PII detection)
- [ ] Persistent memory store (Redis, PostgreSQL)
- [ ] Metrics/observability (Micrometer)
- [ ] More reranker implementations

### Documentation

- [ ] API reference documentation
- [ ] Tutorial: Building a custom Guard
- [ ] Tutorial: Implementing RAG with PGVector
- [ ] Example projects

## Questions?

- Open a [Discussion](https://github.com/StarkFactory/arc-reactor/discussions)
- Check existing issues and PRs

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.
