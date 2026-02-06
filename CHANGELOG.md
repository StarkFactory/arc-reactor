# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
